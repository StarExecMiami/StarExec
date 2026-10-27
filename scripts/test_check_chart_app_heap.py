#!/usr/bin/env python3
"""Tests for scripts/check-chart-app-heap.py. Run: python3 -m unittest discover -s scripts -p 'test_check_chart_app_heap.py'"""

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import yaml

SCRIPT = Path(__file__).resolve().parent / "check-chart-app-heap.py"
spec = importlib.util.spec_from_file_location("check_chart_app_heap", SCRIPT)
heap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(heap)

GIB = 2**30
IMAGE_OPTS = (
    "-Djava.security.egd=file:/dev/./urandom -Djava.awt.headless=true -Xms512m -Xmx2048m "
    "-XX:+UseG1GC -XX:+UseStringDeduplication"
)


def manifest(env=None, limits=None):
    """A rendered chart holding one Deployment whose app container has this env and these limits."""
    app = {"name": "app"}
    if env is not None:
        app["env"] = env
    if limits is not None:
        app["resources"] = {"limits": limits}
    deployment = {
        "kind": "Deployment",
        "spec": {"template": {"spec": {"containers": [app]}}},
    }
    return yaml.safe_dump_all([{"kind": "Service"}, deployment])


def verdict(rendered, default_opts=IMAGE_OPTS):
    """check() for one profile whose render is `rendered`; None means the profile passed."""
    with (
        mock.patch.object(heap, "render", return_value=rendered),
        contextlib.redirect_stdout(io.StringIO()),
    ):
        return heap.check("values-x.yaml", default_opts)


class HeapCeilingTest(unittest.TestCase):
    def test_image_default(self):
        self.assertEqual(heap.heap_ceiling(IMAGE_OPTS), 2 * GIB)

    def test_the_last_heap_flag_wins_as_in_the_jvm(self):
        self.assertEqual(heap.heap_ceiling("-Xmx2048m -XX:MaxHeapSize=4g"), 4 * GIB)
        self.assertEqual(heap.heap_ceiling("-XX:MaxHeapSize=4g -Xmx2048m"), 2 * GIB)
        self.assertEqual(heap.heap_ceiling("-Xmx16g -Xmx2048m"), 2 * GIB)

    def test_units(self):
        self.assertEqual(heap.heap_ceiling("-Xmx2G"), 2 * GIB)
        self.assertEqual(heap.heap_ceiling("-Xmx2097152k"), 2 * GIB)
        self.assertEqual(heap.heap_ceiling("-Xmx2147483648"), 2 * GIB)

    def test_a_flag_inside_a_quoted_property_is_not_a_heap_flag(self):
        # catalina.sh passes JAVA_OPTS through eval, so quotes group words as in the shell.
        self.assertEqual(heap.heap_ceiling("-Xmx4g -Dnote=' -Xmx1g '"), 4 * GIB)
        self.assertEqual(heap.heap_ceiling("-Xmx1g -Dnote=' -Xmx4g '"), 1 * GIB)
        self.assertEqual(heap.heap_ceiling('-Dx="a -Xmx9g" -Xmx2g'), 2 * GIB)

    def test_no_heap_flag(self):
        self.assertIsNone(heap.heap_ceiling(""))
        self.assertIsNone(
            heap.heap_ceiling("-Djava.awt.headless=true -XX:MaxRAMPercentage=50")
        )

    def test_unreadable_java_opts_raise(self):
        with self.assertRaises(ValueError):
            heap.heap_ceiling("-Xmx2g -Dnote='open")
        with self.assertRaises(ValueError):
            heap.heap_ceiling("-Xmx2g -Xmx$HEAP")


class QuantityBytesTest(unittest.TestCase):
    def test_kubernetes_quantities(self):
        self.assertEqual(heap.quantity_bytes("512Mi"), 512 * 2**20)
        self.assertEqual(heap.quantity_bytes("3Gi"), 3 * GIB)
        self.assertEqual(heap.quantity_bytes("3G"), 3 * 10**9)
        self.assertEqual(heap.quantity_bytes("3221225472000m"), 3 * GIB)
        self.assertEqual(heap.quantity_bytes("1e3"), 1000)
        self.assertEqual(heap.quantity_bytes("1E3"), 1000)
        self.assertEqual(heap.quantity_bytes(2147483648), 2 * GIB)

    def test_not_a_quantity(self):
        with self.assertRaises(ValueError):
            heap.quantity_bytes("lots")


def java_opts_env(value):
    return [{"name": "JAVA_OPTS", "value": value}]


class CheckTest(unittest.TestCase):
    def assert_fails(self, rendered, expected, default_opts=IMAGE_OPTS):
        message = verdict(rendered, default_opts)
        self.assertIsNotNone(message, f"the profile passed; expected {expected!r}")
        self.assertIn(expected, message)

    def test_a_heap_at_most_75_percent_of_the_limit_passes(self):
        self.assertIsNone(verdict(manifest(limits={"memory": "3Gi"})))
        self.assertIsNone(
            verdict(manifest(limits={"memory": "4Gi"}), default_opts="-Xmx3g")
        )

    def test_a_heap_above_75_percent_of_the_limit_fails(self):
        self.assert_fails(manifest(limits={"memory": "2Gi"}), "100% of the 2Gi limit")
        self.assert_fails(
            manifest(limits={"memory": "2.5Gi"}), "80% of the 2.5Gi limit"
        )

    def test_the_pods_java_opts_replace_the_images(self):
        rendered = manifest(env=java_opts_env("-Xmx4g"), limits={"memory": "3Gi"})
        self.assert_fails(rendered, "heap 4096 MiB (env.JAVA_OPTS) is 133%")

    def test_a_heap_flag_inside_a_quoted_property_does_not_count(self):
        rendered = manifest(
            env=java_opts_env("-Xmx4g -Dnote=' -Xmx1g '"), limits={"memory": "3Gi"}
        )
        self.assert_fails(rendered, "heap 4096 MiB (env.JAVA_OPTS) is 133%")

    def test_an_empty_java_opts_in_the_pod_leaves_no_heap_cap(self):
        rendered = manifest(env=java_opts_env(""), limits={"memory": "3Gi"})
        self.assert_fails(rendered, "env.JAVA_OPTS sets no -Xmx")

    def test_java_opts_from_value_from_cannot_be_read(self):
        rendered = manifest(
            env=[{"name": "JAVA_OPTS", "valueFrom": {"configMapKeyRef": {}}}],
            limits={"memory": "3Gi"},
        )
        self.assert_fails(rendered, "has no plain value")

    def test_unreadable_java_opts_fail_the_profile(self):
        rendered = manifest(
            env=java_opts_env("-Xmx2g -Dx='open"), limits={"memory": "3Gi"}
        )
        self.assert_fails(rendered, "cannot be read as catalina.sh reads it")

    def test_limits_that_cannot_hold_a_heap_fail_the_profile(self):
        self.assert_fails(manifest(limits={"cpu": "1"}), "has no memory limit")
        self.assert_fails(manifest(), "has no memory limit")
        self.assert_fails(
            manifest(limits={"memory": "lots"}), "is not a Kubernetes quantity"
        )
        self.assert_fails(manifest(limits={"memory": "0"}), "is not a positive size")


class ProfilesTest(unittest.TestCase):
    def test_values_files_must_match_the_profile_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            chart = Path(tmp)
            for name in ("values.yaml", "values-new.yaml", "Chart.yaml"):
                (chart / name).write_text("")
            out = io.StringIO()
            with (
                mock.patch.object(heap, "CHART", chart),
                mock.patch.object(
                    heap,
                    "PROFILES",
                    {"values.yaml": ("ns", []), "values-gone.yaml": ("ns", [])},
                ),
                mock.patch.object(heap, "image_java_opts", return_value=IMAGE_OPTS),
                mock.patch.object(heap, "check", return_value=None),
                contextlib.redirect_stdout(out),
                self.assertRaises(SystemExit) as exit_,
            ):
                heap.main()
        self.assertEqual(exit_.exception.code, 1)
        self.assertIn("values-new.yaml: not in PROFILES", out.getvalue())
        self.assertIn("values-gone.yaml: in PROFILES but not in", out.getvalue())

    def test_the_dockerfile_must_set_java_opts_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            dockerfile = Path(tmp) / "Dockerfile"
            dockerfile.write_text('ENV JAVA_OPTS="-Xmx2048m"\n')
            with mock.patch.object(heap, "DOCKERFILE", dockerfile):
                self.assertEqual(heap.image_java_opts(), "-Xmx2048m")
                dockerfile.write_text(
                    'ENV JAVA_OPTS="-Xmx2048m"\nENV JAVA_OPTS="-Xmx1g"\n'
                )
                with self.assertRaises(SystemExit):
                    heap.image_java_opts()


if __name__ == "__main__":
    unittest.main()
