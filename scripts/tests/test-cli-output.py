#!/usr/bin/env python3
"""Isolated CLI presentation regressions; no container engine is contacted."""

import json
import os
from pathlib import Path
import pty
import re
import shutil
import socket
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SOURCE_FILES = [
    "Makefile",
    "scripts/podman-volumes.sh",
    "scripts/ensure-pause-image.sh",
    "scripts/generate-render-yaml.sh",
    "scripts/check-cgroup-delegation.sh",
    "scripts/preflight-podman.sh",
    "scripts/deploy-podman.sh",
    "scripts/cleanup-deployment.sh",
    "scripts/k8s-auto-detect.sh",
    "scripts/k8s-node-setup.sh",
    "scripts/install-git-hooks.sh",
]

# Only these host utilities are reachable. Engines, sudo, cluster clients,
# network clients and systemctl below are always doubles, even during make's
# parse-time $(shell ...) evaluation and recursive makes.
UTILITIES = (
    "dirname",
    "printf",
    "echo",
    "sh",
    "bash",
    "awk",
    "grep",
    "sed",
    "sort",
    "head",
    "tail",
    "cut",
    "wc",
    "tr",
    "stat",
    "ls",
    "mkdir",
    "mktemp",
    "rm",
    "cat",
    "date",
    "which",
    "id",
    "sha256sum",
    "xargs",
    "du",
    "make",
)
MOCK = r"""#!/usr/bin/python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
home = pathlib.Path(os.environ['MOCK_HOME'])
scenario = os.environ.get('SCENARIO', 'success')
with (home / 'trace').open('a') as trace:
    trace.write(json.dumps([name, args, os.environ.get('VERBOSE', '0')]) + '\n')
if name == 'sudo':
    while args and args[0].startswith('-'): args.pop(0)
    os.execv(str(home / 'bin' / args[0]), args)
if name in ('crun', 'newuidmap', 'newgidmap', 'catatonit', 'sleep', 'systemctl'):
    sys.exit(0)
if name == 'curl':
    print('OK')
    sys.exit(0)
if name == 'yq':
    print('false')
    sys.exit(0)
if name == 'git':
    if args == ['rev-parse', '--show-toplevel']: print(home)
    sys.exit(0)
if name == 'helm':
    print('apiVersion: v1\nkind: Pod\nmetadata:\n  name: simulated')
    sys.exit(0)
if name == 'envsubst':
    sys.stdout.write(sys.stdin.read())
    sys.exit(0)
if name in ('kubectl', 'microk8s'):
    if args[:1] == ['kubectl']: args = args[1:]
    text = ' '.join(args)
    if args[:1] == ['apply']:
        (home / 'applied.yaml').write_text(sys.stdin.read())
        print('resource/simulated created')
    elif args[:1] == ['cluster-info']: print('Kubernetes control plane is running at https://example.invalid')
    elif args[:2] == ['config', 'current-context']: print('fixture-cluster')
    elif args[:1] == ['version']: print('{"gitVersion":"v1.30.0"}')
    elif args[:2] == ['get', 'node']:
        if 'allocatable.cpu' in text: print('4')
        elif 'allocatable.memory' in text: print('8Gi')
    elif args[:2] == ['get', 'nodes']:
        if 'containerRuntimeVersion' in text: print('containerd://1.7')
        elif 'jsonpath' not in text: print('node1 Ready worker 1d v1.30.0')
    elif args[:2] == ['get', 'storageclass']:
        if '.provisioner' in text: print('unknown.example/storage')
        elif 'jsonpath' in text: print('unknown-storage')
        else: print('unknown-storage example 1d')
    elif args[:2] == ['get', 'ingressclass']: sys.exit(1)
    elif args[:2] == ['get', 'pvc']:
        if 'jsonpath' in text: print('Pending')
        elif 'starexec-data' in args: sys.exit(1)
    sys.exit(0)
if name != 'podman':
    print('Unexpected double: ' + name, file=sys.stderr)
    sys.exit(98)
while args and args[0].startswith('--'):
    if args[0] == '--url': del args[:2]
    else: args.pop(0)
text = ' '.join(args)
def present(kind, item):
    return not (home / (kind + '-' + item)).exists()
def remove(kind, item):
    (home / (kind + '-' + item)).touch()
if args[:2] == ['system', 'info'] or args[:1] == ['info']:
    if 'RootlessNetworkCmd' in text: print('slirp4netns')
    elif 'Rootless' in text: print('true')
    elif 'OCIRuntime' in text: print('crun')
    else: print('rootless: true')
elif args[:1] == ['image']:
    sys.exit(1 if scenario in ('pause-fallback', 'pause-pull-failure') else 0)
elif args[:2] == ['pod', 'ls']:
    print('simulated-pod' if '--filter' in args else 'simulated-pod starexec-pod app=starexec')
elif args[:2] == ['pod', 'inspect']:
    print({'pod-starting': 'Starting', 'pod-degraded': 'Degraded'}.get(scenario, 'Running'))
elif args[:2] == ['pod', 'exists']: sys.exit(0 if present('pod', args[-1]) else 1)
elif args[:2] == ['pod', 'rm']:
    remove('pod', args[-1]); print('raw-pod-id')
elif args[:2] == ['pod', 'stop']: print('raw-stopped-id')
elif args[:2] == ['secret', 'ls']: print('starexec-simulated-secret')
elif args[:2] == ['secret', 'rm']: print('raw-secret-id')
elif args[:2] == ['network', 'inspect']:
    if '--format' not in args: print('{}')
elif args[:2] == ['network', 'rm']:
    if scenario == 'network-failure':
        print('simulated network refusal', file=sys.stderr); sys.exit(37)
    print('raw-network-id')
elif args[:2] == ['network', 'exists']: sys.exit(0)
elif args[:2] == ['network', 'create']: print('raw-network-id')
elif args[:2] == ['container', 'exists']: sys.exit(0)
elif args[:2] == ['container', 'rm']: print('raw-container-id')
elif args[:2] == ['volume', 'exists']:
    if scenario in ('create', 'create-failure'): sys.exit(1)
    sys.exit(0 if present('volume', args[-1]) else 1)
elif args[:2] == ['volume', 'create']:
    print('raw-volume-id')
    if scenario == 'create-failure' and args[-1].endswith('-sandbox'):
        print('simulated create failure', file=sys.stderr); sys.exit(37)
elif args[:2] == ['volume', 'rm']:
    if scenario == 'volume-failure' and args[-1].endswith('-data'):
        print('simulated volume refusal', file=sys.stderr); sys.exit(37)
    remove('volume', args[-1]); print('raw-volume-id')
elif args[:2] == ['volume', 'inspect']:
    print(str(home / 'mountpoint') if '--format' in args else '{}')
elif args[:2] == ['volume', 'ls']: print('VOLUME NAME DRIVER MOUNTPOINT')
elif args[:1] == ['ps']:
    if scenario == 'query-failure': sys.exit(37)
    if 'table ' in text: print('NAMES STATUS PORTS\nstarexec-app Up 7827')
elif args[:1] == ['images']:
    if scenario == 'query-failure': sys.exit(37)
    print('REPOSITORY TAG SIZE CREATED\nstarexec latest 1GB today')
elif args[:1] == ['inspect']:
    if scenario == 'socket-not-running': print('false exited 37')
    elif 'ExitCode' in text: print('true running 0' if 'Status' in text else '0')
    elif 'Running' in text: print('true')
elif args[:1] == ['exec']:
    if 'pg_isready' in args and scenario == 'postgres-failure': sys.exit(1)
    if (scenario == 'socket-exec-failure' and 'printf' in text) or (scenario == 'socket-api-failure' and 'curl' in text):
        print('simulated socket diagnostic', file=sys.stderr); sys.exit(37)
    if 'printf' in text: print('/run/podman/podman.sock')
    elif 'curl' in text: print('OK')
elif args[:2] == ['play', 'kube']:
    print('raw-play-id')
    if scenario == 'play-failure':
        print('simulated play failure', file=sys.stderr); sys.exit(37)
elif args[:1] == ['logs']: print('simulated diagnostic logs')
elif args[:1] == ['version']: print('simulated version')
elif args[:1] == ['rmi']: print('raw-image-id')
elif args[:1] == ['pull']:
    print('raw-pull-id')
    if scenario == 'pause-pull-failure' or (scenario == 'pause-fallback' and args[-1].startswith('registry.k8s.io/')):
        print('simulated registry refusal', file=sys.stderr); sys.exit(37)
elif args[:1] not in (['tag'], ['run'], ['unshare']):
    print('Unexpected podman argv: ' + repr(args), file=sys.stderr); sys.exit(98)
"""


class IsolatedProject:
    """Allowlisted PATH and synthetic files, installed before invoking Make."""

    def __init__(self, work: Path, baseline: str | None = None) -> None:
        self.root = work
        (work / "bin").mkdir()
        for relative in SOURCE_FILES:
            destination = work / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if baseline:
                content = subprocess.check_output(
                    ["git", "show", f"{baseline}:{relative}"], cwd=ROOT
                )
            else:
                content = (ROOT / relative).read_bytes()
            destination.write_bytes(content)
            destination.chmod(0o700)
        if not baseline and (ROOT / "scripts/fix-pv-affinity.sh").is_file():
            shutil.copyfile(
                ROOT / "scripts/fix-pv-affinity.sh", work / "scripts/fix-pv-affinity.sh"
            )
        library = work / "scripts/lib/cli-output.sh"
        library.parent.mkdir(parents=True, exist_ok=True)
        if (ROOT / "scripts/lib/cli-output.sh").exists():
            shutil.copyfile(ROOT / "scripts/lib/cli-output.sh", library)
        for utility in UTILITIES:
            executable = shutil.which(utility)
            if not executable:
                raise RuntimeError(f"Required test utility unavailable: {utility}")
            (work / "bin" / utility).symlink_to(executable)
        mock = work / "bin/mock"
        mock.write_text(MOCK)
        mock.chmod(0o700)
        for name in (
            "sudo",
            "podman",
            "kubectl",
            "microk8s",
            "helm",
            "curl",
            "yq",
            "crun",
            "newuidmap",
            "newgidmap",
            "catatonit",
            "sleep",
            "systemctl",
            "git",
            "envsubst",
        ):
            (work / "bin" / name).symlink_to(mock)
        charts = work / "charts/starexec"
        charts.mkdir(parents=True)
        for name in ("values.yaml", "values-dev.yaml", "values-local-dev.yaml"):
            (charts / name).write_text("# synthetic values\n")
        (work / "render.yaml.template").write_text(
            "kind: Pod\nimage: ${IMAGE_NAME:-localhost/local/starexec}:${IMAGE_TAG:-latest}\n"
        )
        (work / "render.yaml").write_text("kind: Pod\n")
        (work / "scripts/check-cgroup-delegation.sh").write_text(
            '#!/bin/sh\nprintf "simulated cgroup check\\n" >&2\n'
        )
        self.socket = socket.socket(socket.AF_UNIX)
        self.socket.bind(str(work / "podman.sock"))
        self.env = {
            "PATH": str(work / "bin"),
            "MOCK_HOME": str(work),
            "TMPDIR": str(work),
            "XDG_RUNTIME_DIR": str(work),
            "PODMAN_SOCKET_PATH": str(work / "podman.sock"),
            "WAIT_POSTGRES_RETRIES": "2",
            "WAIT_POSTGRES_INTERVAL_SECONDS": "0",
            "PODMAN_SOCKET_TEST_RETRIES": "2",
            "PODMAN_SOCKET_TEST_INTERVAL_SECONDS": "0",
            "ENV": "dev",
            "FORCE": "1",
            "NO_COLOR": "1",
            "TERM": "dumb",
            "PODMAN_CMD": "sudo podman --runtime=crun",
            "CLEAN_HOSTPATH": "0",
        }

    def run(
        self, *args: str, scenario: str = "success", verbose: str = "0", stdin: str = ""
    ) -> subprocess.CompletedProcess[str]:
        """Run only inside the fixture with its sealed command search path."""
        return subprocess.run(
            args,
            cwd=self.root,
            env={**self.env, "SCENARIO": scenario, "VERBOSE": verbose},
            input=stdin,
            text=True,
            capture_output=True,
            check=False,
        )

    def trace(self) -> list:
        """Return simulated invocations; ignore only presentation internals."""
        return [
            json.loads(line) for line in (self.root / "trace").read_text().splitlines()
        ]

    def close(self) -> None:
        self.socket.close()


class OperationalTests(unittest.TestCase):
    def setUp(self) -> None:
        self.work = tempfile.TemporaryDirectory(prefix="cli-", dir="/tmp/opencode")
        self.project = IsolatedProject(Path(self.work.name))

    def tearDown(self) -> None:
        self.project.close()
        self.work.cleanup()

    def test_unknown_storage_warning_does_not_enter_pvc(self) -> None:
        result = self.project.run("bash", "scripts/k8s-node-setup.sh", "--create-pvc")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Warning: Storage class unknown-storage", result.stderr)
        pvc = (self.project.root / "applied.yaml").read_text()
        self.assertIn("accessModes:\n    - ReadWriteOnce\n", pvc)
        self.assertNotIn("Warning", pvc)
        self.assertNotIn("\x1b", pvc)

    def test_main_make_flow_has_only_requested_reports_on_stdout(self) -> None:
        result = self.project.run(
            "make",
            "stop",
            "nuke",
            "start",
            "status",
            "FORCE=1",
            "PODMAN_REQUIRES_SUDO=yes",
            "PODMAN_CMD=sudo podman --runtime=crun",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Pod state", result.stdout)
        self.assertIn("Containers:\n", result.stdout)
        self.assertIn("Images:\n", result.stdout)
        self.assertNotIn("raw-", result.stdout + result.stderr)
        self.assertNotIn("PODMAN_CMD=", result.stdout)
        self.assertNotIn("\x1b", result.stdout + result.stderr)
        self.assertNotIn("StarExec is RUNNING", result.stdout + result.stderr)
        self.assertEqual(result.stderr.count("Reset steps finished for ENV=dev"), 1)
        self.assertEqual(
            result.stderr.count("Creating volumes for environment: dev"), 1
        )
        self.assertIn("Migrations: not verified by this command", result.stderr)
        self.assertIn("HTTP readiness not checked", result.stderr)
        self.assertIn(
            "Warning: Using default development database credentials.", result.stderr
        )

    def test_failure_diagnostics_and_neutral_cleanup_summary(self) -> None:
        for scenario, target, expected in (
            ("network-failure", "stop", "Network starexec-net could not be removed"),
            ("volume-failure", "clean-hard", "some volumes remain"),
            (
                "postgres-failure",
                "deploy-podman-helm",
                "PostgreSQL did not become ready",
            ),
            ("play-failure", "deploy-podman-helm", "simulated play failure"),
        ):
            with self.subTest(scenario=scenario):
                result = self.project.run(
                    "make",
                    target,
                    "FORCE=1",
                    "PODMAN_REQUIRES_SUDO=yes",
                    "PODMAN_CMD=sudo podman --runtime=crun",
                    scenario=scenario,
                )
                self.assertIn(expected, result.stderr)
                self.assertNotIn("raw-", result.stdout)
                self.assertNotIn("complete!", result.stdout + result.stderr)
                if scenario in ("postgres-failure", "play-failure"):
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn("Application URL", result.stdout + result.stderr)
                    if scenario == "play-failure":
                        self.assertIn("raw-play-id", result.stderr)
                        self.assertNotIn(
                            "OK: Deployment manifest applied", result.stderr
                        )
                    else:
                        self.assertIn("simulated diagnostic logs", result.stderr)
                        self.assertNotIn("OK: PostgreSQL", result.stderr)
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_json_and_captured_access_mode_contracts(self) -> None:
        result = self.project.run("bash", "scripts/k8s-auto-detect.sh", "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data["cluster_name"], "fixture-cluster")
        self.assertEqual(data["total_cpu_millicores"], 4000)
        self.assertEqual(data["total_memory_gb"], 8)
        self.assertEqual(data["storage_access_mode"], "ReadWriteOnce")
        self.assertNotIn("\x1b", result.stdout + result.stderr)
        source = (self.project.root / "scripts/k8s-node-setup.sh").read_text()
        functions = self.project.root / "scripts/access-mode.sh"
        functions.write_text(source.split("# Parse arguments")[0])
        result = self.project.run(
            "bash",
            "-eu",
            "-c",
            '. "$1"; get_pvc_access_mode unknown-storage',
            "test",
            str(functions),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "ReadWriteOnce\n")
        self.assertIn("Warning: Storage class unknown-storage", result.stderr)

    def test_verbose_reaches_recursive_makes_and_scripts(self) -> None:
        result = self.project.run(
            "make",
            "start",
            "FORCE=1",
            "VERBOSE=1",
            "PODMAN_REQUIRES_SUDO=yes",
            "PODMAN_CMD=sudo podman --runtime=crun",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("raw-", result.stdout)
        self.assertIn("raw-play-id", result.stderr)
        self.assertIn("raw-pull-id", result.stderr)
        self.assertIn(
            "Warning: Using default development database credentials.", result.stderr
        )
        self.assertTrue(all(entry[2] == "1" for entry in self.project.trace()))

    def test_error_messages_remain_visible_in_verbose_mode(self) -> None:
        result = self.project.run(
            "make", "deploy-podman-helm", verbose="1", scenario="postgres-failure"
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("Error: PostgreSQL did not become ready", result.stderr)
        self.assertIn("simulated diagnostic logs", result.stderr)
        self.assertNotIn("Application URL", result.stderr)

    def test_pause_fallback_and_socket_failure_diagnostics(self) -> None:
        for scenario in (
            "pause-fallback",
            "pause-pull-failure",
            "socket-not-running",
            "socket-exec-failure",
            "socket-api-failure",
        ):
            argv = (
                ["bash", "scripts/ensure-pause-image.sh"]
                if scenario.startswith("pause-")
                else ["make", "test-podman-socket"]
            )
            result = self.project.run(*argv, scenario=scenario)
            self.assertEqual(result.stdout, "")
            if scenario.startswith("pause-"):
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Warning:", result.stderr)
                self.assertIn("simulated registry refusal", result.stderr)
                if scenario == "pause-pull-failure":
                    self.assertNotIn("OK: Pulled", result.stderr)
                    self.assertIn(
                        "Could not pull pause image from any source", result.stderr
                    )
            else:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Error:", result.stderr)
                self.assertNotIn("OK: Podman API", result.stderr)

    def test_sudo_prefix_options_and_id_visibility(self) -> None:
        self.project.env["PODMAN_CMD"] = "sudo -n podman --runtime=crun"
        result = self.project.run(
            "bash",
            "scripts/podman-volumes.sh",
            "create",
            "dev",
            scenario="create",
            verbose="1",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn("raw-volume-id", result.stderr)
        self.assertTrue(
            any(
                entry[:2]
                == [
                    "sudo",
                    [
                        "-n",
                        "podman",
                        "--runtime=crun",
                        "volume",
                        "create",
                        "starexec-dev-data",
                    ],
                ]
                for entry in self.project.trace()
            )
        )

    def test_standalone_volume_confirmation_stays_on_stderr(self) -> None:
        self.project.env["FORCE"] = "0"
        result = self.project.run(
            "bash", "scripts/podman-volumes.sh", "delete", "dev", stdin="DELETE\n"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn("Type 'DELETE' to confirm:", result.stderr)
        self.assertIn("Warning: This will DELETE all data", result.stderr)
        self.assertIn("legacy volumes", result.stderr)

    def test_direct_volume_creation_stops_on_intermediate_failure(self) -> None:
        result = self.project.run(
            "bash",
            "scripts/podman-volumes.sh",
            "create",
            "dev",
            scenario="create-failure",
        )
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn("raw-volume-id", result.stderr)
        self.assertIn("simulated create failure", result.stderr)
        self.assertNotIn("OK: Created starexec-dev-sandbox", result.stderr)
        self.assertNotIn("starexec-dev-backend", result.stderr)

    def test_direct_and_cached_deployment_do_not_claim_socket_or_http_readiness(
        self,
    ) -> None:
        for target in ("deploy-podman-direct", "deploy-podman-cached"):
            result = self.project.run("make", target, "PODMAN_REQUIRES_SUDO=yes")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "")
            self.assertIn("Migrations: not verified by this command", result.stderr)
            self.assertIn("HTTP readiness not checked", result.stderr)
            self.assertNotIn("Podman API is reachable", result.stderr)

    def test_direct_start_has_one_default_credentials_warning(self) -> None:
        (self.project.root / "bin/helm").unlink()
        for supplied in (False, True):
            if supplied:
                self.project.env["STAREXEC_DB_PASSWORD"] = "starexec_dev_password"
            result = self.project.run(
                "make", "start", "FORCE=1", "PODMAN_REQUIRES_SUDO=yes"
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                result.stderr.count(
                    "Warning: Using default development database credentials."
                ),
                1,
            )
            self.assertNotIn("Warning: STAREXEC_DB_PASSWORD is not set", result.stderr)

    def test_default_credentials_warning_also_covers_synthetic_password_file(
        self,
    ) -> None:
        password_file = self.project.root / "db-password.synthetic"
        password_file.write_text("starexec_dev_password\n")
        self.project.env["STAREXEC_DB_PASSWORD_FILE"] = str(password_file)
        result = self.project.run("bash", "scripts/generate-render-yaml.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stderr.count(
                "Warning: Using default development database credentials."
            ),
            1,
        )

    def test_status_query_errors_are_not_reported_as_absence(self) -> None:
        result = self.project.run("make", "status", scenario="query-failure")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Warning: Could not query StarExec containers.", result.stderr)
        self.assertIn("Warning: Could not query StarExec images.", result.stderr)
        self.assertNotIn("No StarExec", result.stdout + result.stderr)

    def test_observed_pod_state_is_not_application_readiness(self) -> None:
        for scenario, state in (
            ("success", "Running"),
            ("pod-starting", "Starting"),
            ("pod-degraded", "Degraded"),
        ):
            result = self.project.run("make", "status", scenario=scenario)
            self.assertEqual(result.returncode, 0, result.stderr)
            state_line = next(
                line
                for line in result.stdout.splitlines()
                if line.startswith("Pod state")
            )
            self.assertEqual(state_line.split(":", 1)[1].strip(), state)
            self.assertNotIn("healthy", result.stdout)
            self.assertNotIn("StarExec is", result.stdout)

    def test_generated_values_and_json_have_no_human_messages(self) -> None:
        result = self.project.run(
            "bash", "scripts/k8s-auto-detect.sh", "--json", "--generate-values"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            json.loads(result.stdout)["storage_access_mode"], "ReadWriteOnce"
        )
        values = (
            self.project.root / "charts/starexec/values-auto-detected.yaml"
        ).read_text()
        self.assertIn("- ReadWriteOnce", values)
        for decoration in ("Warning:", "OK:", "\x1b", "[INFO]"):
            self.assertNotIn(decoration, values)

    def test_skipped_socket_check_is_not_reported_as_passed(self) -> None:
        (self.project.root / "bin/podman").unlink()
        result = self.project.run(
            "bash",
            "scripts/preflight-podman.sh",
            str(self.project.root / "podman.sock"),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn("skipping connection test", result.stderr)
        self.assertNotIn("OK:", result.stderr)

    def test_standalone_emitters_use_stderr_and_keep_files_clean(self) -> None:
        for argv in (
            ["bash", "scripts/ensure-pause-image.sh"],
            ["bash", "scripts/generate-render-yaml.sh"],
            ["sh", "scripts/cleanup-deployment.sh"],
            ["sh", "scripts/install-git-hooks.sh"],
            [
                "bash",
                "scripts/preflight-podman.sh",
                str(self.project.root / "podman.sock"),
            ],
            ["sh", "scripts/deploy-podman.sh"],
        ):
            with self.subTest(argv=argv):
                result = self.project.run(*argv)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertNotIn("\x1b", result.stderr)
                self.assertNotIn("raw-", result.stderr)
                self.assertNotIn("[INFO]", result.stderr)
                manifest = (self.project.root / "render.yaml").read_text()
                self.assertNotIn("Warning:", manifest)
                self.assertNotIn("OK:", manifest)

    def test_optional_affinity_script_error_output(self) -> None:
        if not (self.project.root / "scripts/fix-pv-affinity.sh").is_file():
            self.skipTest(
                "The pre-existing untracked affinity script is not part of this checkout"
            )
        result = self.project.run("bash", "scripts/fix-pv-affinity.sh")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("Error: No Ready node found.", result.stderr)

    def test_cgroup_messages_with_synthetic_controller_files(self) -> None:
        source = (ROOT / "scripts/check-cgroup-delegation.sh").read_text()
        cgroup_root = self.project.root / "cgroup"
        cgroup_root.mkdir()
        script = self.project.root / "scripts/check-cgroup-delegation.sh"
        script.write_text(source.replace("/sys/fs/cgroup", str(cgroup_root)))
        controllers = "cpuset cpu io memory pids"
        (cgroup_root / "cgroup.controllers").write_text(controllers)
        user_id = subprocess.check_output(["/usr/bin/id", "-u"], text=True).strip()
        user_slice = cgroup_root / f"user.slice/user-{user_id}.slice"
        user_slice.mkdir(parents=True)
        (user_slice / "cgroup.subtree_control").write_text(controllers)
        result = self.project.run("bash", str(script))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn("OK: Cgroup controller delegation check passed", result.stderr)
        (cgroup_root / "cgroup.controllers").write_text("cpu")
        result = self.project.run("bash", str(script))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("Error: Missing required controllers", result.stderr)
        self.assertNotIn("check passed", result.stderr)

    def test_functional_traces_match_optional_baseline(self) -> None:
        baseline = os.environ.get("CLI_BASELINE")
        if not baseline:
            self.skipTest(
                "Set CLI_BASELINE to an inspected pre-patch commit for trace comparison"
            )
        cases = [
            (
                "success",
                [
                    "make",
                    "stop",
                    "nuke",
                    "start",
                    "status",
                    "FORCE=1",
                    "PODMAN_REQUIRES_SUDO=yes",
                ],
            ),
            *[
                (scenario, ["make", target, "FORCE=1", "PODMAN_REQUIRES_SUDO=yes"])
                for scenario, target in (
                    ("network-failure", "stop"),
                    ("volume-failure", "clean-hard"),
                    ("postgres-failure", "deploy-podman-helm"),
                    ("play-failure", "deploy-podman-helm"),
                    ("success", "deploy-podman-direct"),
                    ("success", "deploy-podman-cached"),
                )
            ],
            ("success", ["bash", "scripts/k8s-auto-detect.sh", "--json"]),
            (
                "success",
                ["bash", "scripts/k8s-auto-detect.sh", "--json", "--generate-values"],
            ),
            ("success", ["sh", "scripts/cleanup-deployment.sh"]),
            ("success", ["sh", "scripts/deploy-podman.sh"]),
            ("create-failure", ["bash", "scripts/podman-volumes.sh", "create", "dev"]),
            ("direct-start", ["make", "start", "FORCE=1", "PODMAN_REQUIRES_SUDO=yes"]),
            (
                "cached-missing-render",
                ["make", "deploy-podman-cached", "PODMAN_REQUIRES_SUDO=yes"],
            ),
            *[
                (scenario, ["bash", "scripts/ensure-pause-image.sh"])
                for scenario in ("pause-fallback", "pause-pull-failure")
            ],
            *[
                (scenario, ["make", "test-podman-socket"])
                for scenario in (
                    "socket-not-running",
                    "socket-exec-failure",
                    "socket-api-failure",
                )
            ],
        ]
        for scenario, argv in cases:
            with self.subTest(scenario=scenario, argv=argv):
                traces, results, generated, generated_values = [], [], [], []
                for revision in (baseline, None):
                    with tempfile.TemporaryDirectory(
                        prefix="cli-", dir="/tmp/opencode"
                    ) as work:
                        project = IsolatedProject(Path(work), revision)
                        try:
                            if scenario == "direct-start":
                                (project.root / "bin/helm").unlink()
                            if scenario == "cached-missing-render":
                                (project.root / "render.yaml").unlink()
                            result = project.run(*argv, scenario=scenario)
                            results.append(result)
                            traces.append(
                                json.dumps(project.trace()).replace(work, "<fixture>")
                            )
                            generated.append((project.root / "render.yaml").read_text())
                            values = (
                                project.root
                                / "charts/starexec/values-auto-detected.yaml"
                            )
                            generated_values.append(
                                values.read_bytes() if values.exists() else None
                            )
                        finally:
                            project.close()
                self.assertEqual(results[0].returncode, results[1].returncode)
                self.assertEqual(traces[0], traces[1])
                self.assertEqual(generated[0], generated[1])
                self.assertEqual(generated_values[0], generated_values[1])
                if "--json" in argv:
                    data = [json.loads(result.stdout) for result in results]
                    for document in data:
                        document.pop("timestamp")
                    self.assertEqual(data[0], data[1])

    def test_execution_settings_prerequisites_and_shell_options_match_baseline(
        self,
    ) -> None:
        baseline = os.environ.get("CLI_BASELINE")
        if not baseline:
            self.skipTest("Set CLI_BASELINE to compare execution invariants")

        def original(relative: str) -> str:
            return subprocess.check_output(
                ["git", "show", f"{baseline}:{relative}"], cwd=ROOT, text=True
            )

        old_make = original("Makefile")
        new_make = (ROOT / "Makefile").read_text()
        targets = r"^[A-Za-z0-9_.-]+:.*$"
        self.assertEqual(
            re.findall(targets, old_make, re.M), re.findall(targets, new_make, re.M)
        )
        assignments = r"^([A-Za-z_.][A-Za-z0-9_.]*)\s*[?:+]?=.*$"
        presentation = {
            "VERBOSE",
            "CLI_OUTPUT",
            "GREEN",
            "YELLOW",
            "RED",
            "BLUE",
            "RESET",
            "BOLD",
        }

        def functional_assignments(text: str) -> list[str]:
            return [
                line
                for line in text.splitlines()
                if (match := re.match(assignments, line))
                and match[1] not in presentation
            ]

        self.assertEqual(
            functional_assignments(old_make), functional_assignments(new_make)
        )
        for relative in SOURCE_FILES[1:]:
            old = original(relative)
            new = (ROOT / relative).read_text()
            options = r"^\s*(?:#![^\n]+|set [^\n]+|trap [^\n]+)$"
            self.assertEqual(
                re.findall(options, old, re.M), re.findall(options, new, re.M), relative
            )
            self.assertLessEqual(new.count("|| true"), old.count("|| true"), relative)
        for relative in (
            "scripts/tests/test-entrypoint-flyway-launch.sh",
            "scripts/tests/test-entrypoint-context-substitution.sh",
        ):
            self.assertEqual(original(relative), (ROOT / relative).read_text())


class LibraryTests(unittest.TestCase):
    def test_fixture_does_not_require_untracked_files(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="cli-clean-", dir="/tmp/opencode"
        ) as work:
            clean = Path(work) / "clean"
            for relative in SOURCE_FILES + ["scripts/lib/cli-output.sh"]:
                destination = clean / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, destination)
            fixture = Path(work) / "fixture"
            fixture.mkdir()
            with patch.dict(IsolatedProject.__init__.__globals__, {"ROOT": clean}):
                project = IsolatedProject(fixture)
                try:
                    result = project.run("bash", "scripts/ensure-pause-image.sh")
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout, "")
                finally:
                    project.close()

    def test_stdin_and_errexit_are_preserved(self) -> None:
        for shell in ("/bin/sh", "/bin/bash"):
            for verbose in ("0", "1"):
                with self.subTest(shell=shell, verbose=verbose):
                    result = subprocess.run(
                        [
                            shell,
                            "-eu",
                            "-c",
                            """
. "$1"
ui_run /bin/sh -c 'read -r input; printf "%s\\n" "$input"; exit 37'
ui_success 'must not appear'
""",
                            "test",
                            str(ROOT / "scripts/lib/cli-output.sh"),
                        ],
                        env={
                            "PATH": "/usr/bin:/bin",
                            "VERBOSE": verbose,
                            "TMPDIR": "/tmp/opencode",
                        },
                        input="literal input % \\ -\n",
                        text=True,
                        capture_output=True,
                        check=False,
                    )
                    self.assertEqual(result.returncode, 37)
                    self.assertEqual(result.stdout, "")
                    self.assertEqual(result.stderr, "literal input % \\ -\n")

    def test_plain_output_on_pipes_and_each_tty_descriptor(self) -> None:
        for variables in (
            {},
            {"TERM": "dumb"},
            {"TERM": "xterm", "NO_COLOR": "1"},
            {"TERM": "xterm", "NO_COLOR": "0"},
            {"TERM": "xterm"},
        ):
            for tty_fd in (None, 1, 2):
                with self.subTest(variables=variables, tty_fd=tty_fd):
                    master = slave = None
                    if tty_fd:
                        master, slave = pty.openpty()
                    try:
                        result = subprocess.run(
                            [
                                "/bin/sh",
                                "-eu",
                                "-c",
                                '. "$1"; ui_warn "same text"',
                                "test",
                                str(ROOT / "scripts/lib/cli-output.sh"),
                            ],
                            env={"PATH": "/usr/bin:/bin", **variables},
                            stdout=slave if tty_fd == 1 else subprocess.PIPE,
                            stderr=slave if tty_fd == 2 else subprocess.PIPE,
                            check=False,
                        )
                        self.assertEqual(result.returncode, 0)
                        if tty_fd == 2:
                            self.assertEqual(
                                os.read(master, 1024).replace(b"\r\n", b"\n"),
                                b"Warning: same text\n",
                            )
                        else:
                            self.assertEqual(result.stderr, b"Warning: same text\n")
                        if tty_fd != 1:
                            self.assertEqual(result.stdout, b"")
                    finally:
                        if master is not None:
                            os.close(master)
                            os.close(slave)

    def test_external_command_runs_once_and_preserves_failure(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="cli-output-", dir="/tmp/opencode"
        ) as work:
            for verbose in ("0", "1"):
                for code in (0, 37):
                    with self.subTest(verbose=verbose, code=code):
                        counter = Path(work) / "calls"
                        counter.write_text("")
                        result = subprocess.run(
                            [
                                "/bin/sh",
                                "-eu",
                                "-c",
                                """
. "$1"
trap ':' TERM
before_traps=$(trap)
set +e
ui_run /bin/sh -c 'printf "call\\n" >> "$1"; printf "raw-id\\n"; printf "diagnostic\\n" >&2; exit "$2"' test "$2" "$3"
status=$?
set -e
[ "$before_traps" = "$(trap)" ]
exit "$status"
""",
                                "test",
                                str(ROOT / "scripts/lib/cli-output.sh"),
                                str(counter),
                                str(code),
                            ],
                            env={
                                "PATH": "/usr/bin:/bin",
                                "VERBOSE": verbose,
                                "TMPDIR": work,
                            },
                            text=True,
                            capture_output=True,
                            check=False,
                        )
                        self.assertEqual(result.returncode, code, result.stderr)
                        self.assertEqual(counter.read_text(), "call\n")
                        self.assertEqual(result.stdout, "")
                        self.assertIn("diagnostic\n", result.stderr)
                        self.assertEqual(
                            "raw-id\n" in result.stderr, code != 0 or verbose == "1"
                        )
                        self.assertEqual(list(Path(work).iterdir()), [counter])

    def test_silent_load_literal_messages_and_disabled_detail(self) -> None:
        message = r"- 100% ready: C:\path\n  two spaces"
        for shell in ("/bin/sh", "/bin/bash"):
            with self.subTest(shell=shell):
                result = subprocess.run(
                    [
                        shell,
                        "-eu",
                        "-c",
                        """
before_options=$-
before_ifs=$IFS
before_pwd=$PWD
trap ':' TERM
before_traps=$(trap)
. "$1"
[ "$before_options" = "$-" ]
[ "$before_ifs" = "$IFS" ]
[ "$before_pwd" = "$PWD" ]
[ "$before_traps" = "$(trap)" ]
ui_detail 'hidden'
printf '%s\n' 'data'
ui_info "$2"
ui_success "$2"
ui_warn "$2"
ui_error "$2"
""",
                        "test",
                        str(ROOT / "scripts/lib/cli-output.sh"),
                        message,
                    ],
                    env={"PATH": "/usr/bin:/bin", "VERBOSE": "0"},
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "data\n")
                self.assertEqual(
                    result.stderr,
                    "\n".join(
                        [
                            message,
                            "OK: " + message,
                            "Warning: " + message,
                            "Error: " + message,
                            "",
                        ]
                    ),
                )


if __name__ == "__main__":
    unittest.main()
