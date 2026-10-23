"""Tests for scripts/generate-render-yaml.sh, which renders the Podman pod when Helm is not installed.

`make start` falls back to this script when Helm is missing (`deploy-podman-direct`), and with the Podman backend
the pod it renders must let StarExec start job containers: the container engine's socket mounted in, the group
that owns it, the data volume's path on the host, and the job image. Each test runs the real script on a copy of
the real template, with a fake `podman` on PATH and a real Unix socket.

Run: python3 -m unittest discover -s scripts -p 'test_generate_render_yaml.py'
"""

import os
import re
import shutil
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts" / "generate-render-yaml.sh"
TEMPLATE = REPO / "render.yaml.template"
CHART_VALUES = REPO / "charts" / "starexec" / "values.yaml"

# What `make deploy-podman-direct` passes for ENV=dev, minus the Makefile's own exports (set per test).
BASE_ENV = {
    "ENV": "dev",
    "STAREXEC_DB_PASSWORD": "test-password",
    "STAREXEC_DATA_VOL": "starexec-dev-data",
    "STAREXEC_POSTGRES_VOL": "starexec-dev-postgres",
    "APP_PORT": "7827",
    "IMAGE_NAME": "starexec",
    "IMAGE_TAG": "latest",
}

# A stand-in for `podman [global options] volume exists|create|inspect ... NAME`. It knows the volumes listed in
# FAKE_PODMAN_VOLUMES and the ones it has created, fails `volume create` when FAKE_PODMAN_CREATE_FAILS is set,
# prints an empty mountpoint when FAKE_PODMAN_NO_MOUNTPOINT is set, and logs every call.
FAKE_PODMAN = """#!/bin/sh
echo "$*" >> "$FAKE_PODMAN_LOG"
while [ "${1#-}" != "$1" ]; do shift; done
if [ "$1" != volume ]; then
	echo "fake podman: unexpected arguments: $*" >&2
	exit 2
fi
for name in "$@"; do :; done
known() {
	for v in $FAKE_PODMAN_VOLUMES $(cat "$FAKE_PODMAN_CREATED"); do
		[ "$v" = "$1" ] && return 0
	done
	return 1
}
case $2 in
exists)
	known "$name" && exit 0
	exit 1 ;;
create)
	if [ -n "$FAKE_PODMAN_CREATE_FAILS" ]; then
		echo "Error: cannot create volume $name" >&2
		exit 125
	fi
	echo "$name" >> "$FAKE_PODMAN_CREATED"
	echo "$name"
	exit 0 ;;
inspect)
	if known "$name"; then
		if [ -n "$FAKE_PODMAN_NO_MOUNTPOINT" ]; then
			echo
		else
			echo "/srv/podman/volumes/$name/_data"
		fi
		exit 0
	fi
	echo "Error: no such volume $name" >&2
	exit 125 ;;
esac
echo "fake podman: unexpected arguments: $*" >&2
exit 2
"""

PODMAN_ONLY_ENV = (
    "STAREXEC_CONTAINER_SOCKET",
    "STAREXEC_CONTAINER_JOB_IMAGE",
    "STAREXEC_CONTAINER_HOST_DATA_PATH",
)


class Render:
    """One run of the script in a scratch copy of the repository layout it expects."""

    def __init__(self, test, **env):
        self.dir = Path(tempfile.mkdtemp(prefix="render-"))
        test.addCleanup(shutil.rmtree, self.dir)
        (self.dir / "scripts").mkdir()
        shutil.copy2(SCRIPT, self.dir / "scripts" / SCRIPT.name)
        shutil.copy2(TEMPLATE, self.dir / TEMPLATE.name)
        bin_dir = self.dir / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "podman"
        fake.write_text(FAKE_PODMAN)
        fake.chmod(0o755)
        self.podman_log = self.dir / "podman.log"
        self.podman_log.touch()
        self.created = self.dir / "created-volumes"
        self.created.touch()
        self.output = self.dir / "render.yaml"
        self.sockets = []
        test.addCleanup(self.close_sockets)
        self.env = {
            "PATH": f"{bin_dir}:/usr/bin:/bin",
            "HOME": str(self.dir),
            "FAKE_PODMAN_LOG": str(self.podman_log),
            "FAKE_PODMAN_CREATED": str(self.created),
            "FAKE_PODMAN_VOLUMES": "starexec-dev-data",
            **BASE_ENV,
        }
        self.env.update(env)

    def socket(self, name="podman.sock"):
        """A listening Unix socket in the scratch directory, as the Podman service provides."""
        path = self.dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.bind(str(path))
        s.listen(1)
        self.sockets.append(s)
        return path

    def close_sockets(self):
        for s in self.sockets:
            s.close()

    def run(self):
        self.result = subprocess.run(
            ["bash", str(self.dir / "scripts" / SCRIPT.name)],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        return self.result

    def pod(self):
        return yaml.safe_load(self.output.read_text())

    def podman_calls(self):
        return self.podman_log.read_text().splitlines()


def app(pod):
    return next(c for c in pod["spec"]["containers"] if c["name"] == "app")


def env_of(container):
    return {e["name"]: e.get("value") for e in container.get("env", [])}


def volumes_of(pod):
    return {v["name"]: v for v in pod["spec"]["volumes"]}


def marker_lines(text):
    """The template's section markers left in a rendered manifest."""
    return [
        line
        for line in text.splitlines()
        if re.match(r"\s*# (BEGIN|END) podman-backend", line)
    ]


def chart_job_image():
    return yaml.safe_load(CHART_VALUES.read_text())["podman"]["jobImage"]


class PodmanBackendTests(unittest.TestCase):
    def setUp(self):
        self.r = Render(self, STAREXEC_BACKEND_TYPE="podman", PODMAN_CMD="podman")
        self.sock = self.r.socket()
        self.r.env["PODMAN_SOCKET_PATH"] = str(self.sock)

    def rendered(self):
        result = self.r.run()
        self.assertEqual(0, result.returncode, result.stderr)
        return self.r.pod()

    def test_the_engine_socket_is_mounted_where_the_app_looks_for_it(self):
        pod = self.rendered()
        self.assertEqual(
            {"path": str(self.sock), "type": "Socket"},
            volumes_of(pod)["podman-socket"]["hostPath"],
        )
        mounts = {m["name"]: m["mountPath"] for m in app(pod)["volumeMounts"]}
        self.assertEqual("/run/podman/podman.sock", mounts["podman-socket"])
        self.assertEqual(
            "unix:///run/podman/podman.sock",
            env_of(app(pod))["STAREXEC_CONTAINER_SOCKET"],
        )

    def test_the_pod_joins_the_group_that_owns_the_socket(self):
        # Where the user belongs to another group, give the socket that group, so that the socket's group differs
        # from its owner's user id and primary group.
        other = [g for g in os.getgroups() if g not in (os.getuid(), os.getgid())]
        if other:
            os.chown(self.sock, -1, other[0])
        pod = self.rendered()
        self.assertEqual(
            [os.stat(self.sock).st_gid],
            pod["spec"]["securityContext"]["supplementalGroups"],
        )

    def test_the_default_socket_is_the_users_podman_socket(self):
        runtime = self.r.dir / "runtime"
        sock = self.r.socket("runtime/podman/podman.sock")
        del self.r.env["PODMAN_SOCKET_PATH"]
        self.r.env["XDG_RUNTIME_DIR"] = str(runtime)
        pod = self.rendered()
        self.assertEqual(
            str(sock), volumes_of(pod)["podman-socket"]["hostPath"]["path"]
        )

    def test_the_default_socket_is_the_system_socket_when_podman_needs_sudo(self):
        """Like the Makefile: rootful Podman listens on /run/podman/podman.sock."""
        del self.r.env["PODMAN_SOCKET_PATH"]
        self.r.env["PODMAN_REQUIRES_SUDO"] = "yes"
        self.r.env["XDG_RUNTIME_DIR"] = str(self.r.dir)
        result = self.r.run()
        if result.returncode == 0:  # a host where this user can reach the system socket
            path = volumes_of(self.r.pod())["podman-socket"]["hostPath"]["path"]
            self.assertEqual("/run/podman/podman.sock", path)
        else:
            self.assertIn(
                "no container engine socket at /run/podman/podman.sock", result.stderr
            )

    def test_job_containers_get_the_data_volume_path_on_the_host(self):
        pod = self.rendered()
        self.assertEqual(
            "/srv/podman/volumes/starexec-dev-data/_data",
            env_of(app(pod))["STAREXEC_CONTAINER_HOST_DATA_PATH"],
        )
        calls = self.r.podman_calls()
        self.assertIn(
            "volume inspect --format {{.Mountpoint}} starexec-dev-data", calls
        )
        self.assertEqual([], [c for c in calls if c.startswith("volume create")])

    def test_the_data_volume_is_the_one_the_pod_mounts(self):
        self.r.env["STAREXEC_DATA_VOL"] = "other-data"
        self.r.env["FAKE_PODMAN_VOLUMES"] = "other-data"
        pod = self.rendered()
        self.assertEqual(
            "other-data", volumes_of(pod)["data"]["persistentVolumeClaim"]["claimName"]
        )
        self.assertEqual(
            "/srv/podman/volumes/other-data/_data",
            env_of(app(pod))["STAREXEC_CONTAINER_HOST_DATA_PATH"],
        )

    def test_on_a_first_deploy_the_data_volume_is_created_and_its_path_used(self):
        """No volume yet, as on a first `make start`: podman play kube would create it, so the script does."""
        self.r.env["FAKE_PODMAN_VOLUMES"] = ""
        pod = self.rendered()
        self.assertEqual(["starexec-dev-data"], self.r.created.read_text().split())
        self.assertEqual(
            "/srv/podman/volumes/starexec-dev-data/_data",
            env_of(app(pod))["STAREXEC_CONTAINER_HOST_DATA_PATH"],
        )

    def test_a_data_volume_that_cannot_be_created_stops_the_render(self):
        self.r.env["FAKE_PODMAN_VOLUMES"] = ""
        self.r.env["FAKE_PODMAN_CREATE_FAILS"] = "1"
        result = self.r.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("starexec-dev-data", result.stderr)
        self.assertIn("STAREXEC_CONTAINER_HOST_DATA_PATH", result.stderr)
        self.assertFalse(self.r.output.exists())

    def test_a_data_volume_with_no_path_on_the_host_stops_the_render(self):
        """A volume driver can keep a volume somewhere with no local directory; job containers could not use it."""
        self.r.env["FAKE_PODMAN_NO_MOUNTPOINT"] = "1"
        result = self.r.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("starexec-dev-data", result.stderr)
        self.assertIn("STAREXEC_CONTAINER_HOST_DATA_PATH", result.stderr)
        self.assertFalse(self.r.output.exists())

    def test_an_explicit_host_data_path_is_used_as_given(self):
        self.r.env["STAREXEC_CONTAINER_HOST_DATA_PATH"] = "/srv/starexec/data"
        self.r.env["FAKE_PODMAN_VOLUMES"] = ""
        pod = self.rendered()
        self.assertEqual(
            "/srv/starexec/data", env_of(app(pod))["STAREXEC_CONTAINER_HOST_DATA_PATH"]
        )
        self.assertEqual([], self.r.podman_calls())

    def test_jobs_run_in_the_job_image_the_chart_uses(self):
        pod = self.rendered()
        self.assertEqual(
            chart_job_image(), env_of(app(pod))["STAREXEC_CONTAINER_JOB_IMAGE"]
        )

    def test_the_job_image_can_be_chosen(self):
        self.r.env["STAREXEC_CONTAINER_JOB_IMAGE"] = "localhost/job-runner:test"
        pod = self.rendered()
        self.assertEqual(
            "localhost/job-runner:test",
            env_of(app(pod))["STAREXEC_CONTAINER_JOB_IMAGE"],
        )

    def test_a_podman_command_with_options_is_split_into_words(self):
        self.r.env["PODMAN_CMD"] = "podman --runtime=crun"
        self.rendered()
        self.assertIn(
            "--runtime=crun volume inspect --format {{.Mountpoint}} starexec-dev-data",
            self.r.podman_calls(),
        )

    def test_paths_with_sed_metacharacters_are_rendered_as_they_are(self):
        sock = self.r.socket("a&b|c.sock")
        self.r.env["PODMAN_SOCKET_PATH"] = str(sock)
        self.r.env["STAREXEC_CONTAINER_HOST_DATA_PATH"] = "/srv/a&b|c"
        pod = self.rendered()
        self.assertEqual(
            str(sock), volumes_of(pod)["podman-socket"]["hostPath"]["path"]
        )
        self.assertEqual(
            "/srv/a&b|c", env_of(app(pod))["STAREXEC_CONTAINER_HOST_DATA_PATH"]
        )

    def test_nothing_is_left_unsubstituted(self):
        self.rendered()
        body = [
            line
            for line in self.r.output.read_text().splitlines()
            if not line.lstrip().startswith("#")
        ]
        self.assertEqual([], [line for line in body if "${" in line])

    def test_a_missing_socket_stops_the_render(self):
        self.r.env["PODMAN_SOCKET_PATH"] = str(self.r.dir / "absent.sock")
        result = self.r.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(str(self.r.dir / "absent.sock"), result.stderr)
        self.assertIn("systemctl --user enable --now podman.socket", result.stderr)
        self.assertNotIn("sudo systemctl", result.stderr)
        self.assertFalse(self.r.output.exists())

    def test_a_missing_system_socket_names_the_system_service(self):
        """Rootful Podman's socket belongs to the system service, which systemctl --user does not start."""
        self.r.env["PODMAN_REQUIRES_SUDO"] = "yes"
        self.r.env["PODMAN_SOCKET_PATH"] = str(self.r.dir / "absent.sock")
        result = self.r.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(str(self.r.dir / "absent.sock"), result.stderr)
        self.assertIn("sudo systemctl enable --now podman.socket", result.stderr)
        self.assertNotIn("systemctl --user", result.stderr)
        self.assertFalse(self.r.output.exists())

    def test_a_file_that_is_not_a_socket_stops_the_render(self):
        plain = self.r.dir / "plain-file"
        plain.write_text("")
        self.r.env["PODMAN_SOCKET_PATH"] = str(plain)
        result = self.r.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(str(plain), result.stderr)
        self.assertFalse(self.r.output.exists())

    def test_a_value_that_would_break_the_manifest_stops_the_render(self):
        """A quote, a backslash or a line break would end the manifest's quoted string early."""
        for i, char in enumerate(('"', "\\", "\n")):
            values = {
                # A real socket, so that only the character can be what refuses it.
                "PODMAN_SOCKET_PATH": str(self.r.socket(f"s{i}{char}x.sock")),
                "STAREXEC_CONTAINER_HOST_DATA_PATH": f"/srv/a{char}b",
                "STAREXEC_CONTAINER_JOB_IMAGE": f"localhost/job{char}runner:test",
            }
            for name, value in values.items():
                with self.subTest(variable=name, char=char):
                    # A render left by an earlier subtest must not decide this one.
                    self.r.output.unlink(missing_ok=True)
                    env = dict(self.r.env)
                    self.r.env[name] = value
                    result = self.r.run()
                    self.r.env = env
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(name, result.stderr)
                    self.assertFalse(self.r.output.exists())

    def test_no_section_marker_is_left_in_the_manifest(self):
        self.rendered()
        self.assertEqual([], marker_lines(self.r.output.read_text()))


class LocalBackendTests(unittest.TestCase):
    def setUp(self):
        self.r = Render(
            self,
            STAREXEC_BACKEND_TYPE="local",
            PODMAN_SOCKET_PATH="/nonexistent/podman.sock",
            FAKE_PODMAN_VOLUMES="",
        )

    def test_the_local_backend_needs_no_socket_and_gets_none(self):
        result = self.r.run()
        self.assertEqual(0, result.returncode, result.stderr)
        pod = self.r.pod()
        self.assertNotIn("securityContext", pod["spec"])
        self.assertNotIn("podman-socket", volumes_of(pod))
        self.assertNotIn("podman-socket", [m["name"] for m in app(pod)["volumeMounts"]])
        self.assertEqual([], [n for n in PODMAN_ONLY_ENV if n in env_of(app(pod))])
        self.assertEqual("local", env_of(app(pod))["STAREXEC_BACKEND_TYPE"])
        self.assertEqual([], self.r.podman_calls())

    def test_no_section_marker_is_left_in_the_manifest(self):
        self.assertEqual(0, self.r.run().returncode)
        self.assertEqual([], marker_lines(self.r.output.read_text()))


if __name__ == "__main__":
    unittest.main()
