#!/usr/bin/env python3
"""Deterministic failure-mode tests for Helm repository index retrieval.

helm-repo-prepare.sh fetches the published index.yaml, downloads every package
it references, and regenerates the index with --merge so previously published
versions survive. If that fetch is allowed to fail OPEN, the consequences are
silent and permanent:

    transient 5xx / DNS / timeout / empty body
      -> "no published index (first publication)"
      -> the merge has nothing to merge
      -> the regenerated index no longer advertises the published version
      -> keep_files keeps the orphaned .tgz, but `helm pull --version <old>`
         fails because the index is what clients read
      -> the retention validator compares against the same empty input and
         reports PASS

and it does not self-heal, because the next run's "old index" is whatever is
live at that moment -- which by then no longer lists the dropped version.

These tests drive the real scripts against a local HTTP fixture that returns
controlled responses, so every failure mode is reproducible without waiting for
a real outage. Nothing here touches the network or publishes anything.

Run: scripts/test-helm-repo-fetch.py [-v]
"""
import argparse
import contextlib
import http.server
import os
import shutil
import socket
import socketserver
import subprocess
import sys
import tarfile
import tempfile
import threading
import time

try:
    import yaml
except ImportError:
    sys.exit("test-helm-repo-fetch: PyYAML is required")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREPARE = os.path.join(REPO, "scripts", "helm-repo-prepare.sh")
RETENTION = os.path.join(REPO, "scripts", "check-helm-retention.py")
CHART = "starexec"

# Keep the suite fast; the scripts default to production-sized values.
FAST_ENV = {
    "HELM_INDEX_RETRIES": "2",
    "HELM_INDEX_RETRY_DELAY": "1",
    "HELM_INDEX_CONNECT_TIMEOUT": "2",
    "HELM_INDEX_MAX_TIME": "5",
}

VERBOSE = False


# --------------------------------------------------------------------------
# HTTP fixture
# --------------------------------------------------------------------------
class Fixture:
    """Serves a directory, with a switchable failure mode for index.yaml."""

    def __init__(self, pub_dir):
        self.pub = pub_dir
        self.mode = "ok"
        outer = self

        class Handler(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def translate_path(self, path):
                rel = path.split("?", 1)[0].lstrip("/")
                return os.path.join(outer.pub, rel)

            def _send(self, code, body=b""):
                self.send_response(code)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if body:
                    self.wfile.write(body)

            def do_GET(self):
                m = outer.mode
                if self.path.endswith("index.yaml"):
                    if m == "http404":
                        return self._send(404, b"not found")
                    if m == "http500":
                        return self._send(500, b"upstream failure")
                    if m == "empty200":
                        return self._send(200, b"")
                    if m == "malformed200":
                        return self._send(200, b"entries: [oops\n  - {{{ not yaml\n")
                    if m == "nostructure200":
                        return self._send(200, b"apiVersion: v1\nunrelated: true\n")
                    if m == "hang":
                        time.sleep(30)
                        return
                if m == "nopkg" and self.path.endswith(".tgz"):
                    return self._send(404, b"gone")
                return super().do_GET()

        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        self.httpd = Server(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def dead_port():
    """A port nothing is listening on, for connection-refused."""
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# --------------------------------------------------------------------------
# workspace helpers
# --------------------------------------------------------------------------
def write_chart(ws, version, app_version="2025.12", extra=""):
    d = os.path.join(ws, "charts", CHART, "templates")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(ws, "charts", CHART, "Chart.yaml"), "w") as fh:
        fh.write(f"apiVersion: v2\nname: {CHART}\nversion: {version}\n"
                 f"appVersion: \"{app_version}\"\ndescription: fixture\n")
    with open(os.path.join(d, "cm.yaml"), "w") as fh:
        fh.write("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: fixture\n"
                 f"data:\n  marker: \"{version}{extra}\"\n")


def helm(*args, cwd=None):
    r = subprocess.run(["helm", *args], cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"helm {' '.join(args)} failed: {r.stderr[-400:]}")
    return r.stdout


def publish(ws, pub, url, version):
    """Package `version` into pub/ and build a real index for it."""
    write_chart(ws, version)
    helm("package", os.path.join(ws, "charts", CHART), "--destination", pub)
    helm("repo", "index", pub, "--url", url)


def run(cmd, cwd, env_extra=None):
    env = dict(os.environ)
    env.update(FAST_ENV)
    env.update(env_extra or {})
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, env=env)
    out = (r.stdout or "") + (r.stderr or "")
    if VERBOSE:
        print(f"      $ {' '.join(cmd)}\n        rc={r.returncode}\n"
              + "\n".join("        | " + l for l in out.splitlines()[-25:]))
    return r.returncode, out


def prepare(ws, url, out_dir, save_old, *extra):
    return run([PREPARE, url, out_dir, save_old, *extra], cwd=ws)


def retention(ws, repo_dir, save_old, *extra):
    return run([RETENTION, "--repo-dir", repo_dir,
                "--chart", os.path.join(ws, "charts", CHART, "Chart.yaml"),
                "--old-index", save_old, *extra], cwd=ws)


def index_versions(path):
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        return []
    d = yaml.safe_load(open(path)) or {}
    return sorted(e.get("version") for e in (d.get("entries") or {}).get(CHART, []) or [])


# --------------------------------------------------------------------------
# scenarios
# --------------------------------------------------------------------------
@contextlib.contextmanager
def scenario(mode, publish_first=True):
    """A workspace with 0.1.0 already published, chart bumped to 0.2.0."""
    ws = tempfile.mkdtemp(prefix="helmfetch-")
    pub = os.path.join(ws, "pub")
    os.makedirs(pub, exist_ok=True)
    fx = Fixture(pub)
    try:
        if publish_first:
            publish(ws, pub, fx.url, "0.1.0")
        write_chart(ws, "0.2.0", extra="-changed")
        fx.mode = mode
        yield ws, fx, os.path.join(ws, "helm-repo"), os.path.join(ws, "old-index.yaml")
    finally:
        fx.close()
        shutil.rmtree(ws, ignore_errors=True)


RESULTS = []


def check(name, ok, detail):
    RESULTS.append((name, ok, detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if not ok or VERBOSE:
        print(f"        {detail}")


def case_ok200():
    with scenario("ok") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        vs = index_versions(os.path.join(out, "index.yaml"))
        rrc, rlog = retention(ws, out, old)
        ok = rc == 0 and vs == ["0.1.0", "0.2.0"] and rrc == 0
        check("200 valid index: retains old, adds current", ok,
              f"prepare rc={rc} versions={vs} retention rc={rrc}")


def case_http404_default():
    with scenario("http404") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        ok = rc != 0 and ("missing" in log.lower() or "404" in log)
        check("404 (default): fails closed with a precise diagnostic", ok,
              f"prepare rc={rc}; log mentions 404/missing: "
              f"{'yes' if ('404' in log or 'missing' in log.lower()) else 'no'}")


def case_http404_allowed():
    with scenario("http404", publish_first=False) as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old, "--allow-missing-index")
        vs = index_versions(os.path.join(out, "index.yaml"))
        valid_empty = False
        if os.path.isfile(old) and os.path.getsize(old) > 0:
            d = yaml.safe_load(open(old)) or {}
            valid_empty = isinstance(d.get("entries"), dict)
        rrc, _ = retention(ws, out, old, "--allow-missing-index")
        ok = rc == 0 and vs == ["0.2.0"] and valid_empty and rrc == 0
        check("404 + --allow-missing-index: explicit first publication", ok,
              f"prepare rc={rc} versions={vs} old-index-is-valid-empty={valid_empty} "
              f"retention rc={rrc}")


def case_http500():
    with scenario("http500") as (ws, fx, out, old):
        t0 = time.time()
        rc, log = prepare(ws, fx.url, out, old)
        elapsed = time.time() - t0
        produced = os.path.isfile(os.path.join(out, "index.yaml"))
        empty_old = os.path.isfile(old) and os.path.getsize(old) == 0
        ok = rc != 0 and not produced and not empty_old
        check("500: retries, fails closed, no empty authoritative index", ok,
              f"prepare rc={rc} elapsed={elapsed:.1f}s proposed_index_written={produced} "
              f"zero_byte_old_index={empty_old}")


def case_conn_refused():
    with scenario("ok") as (ws, fx, out, old):
        url = f"http://127.0.0.1:{dead_port()}"
        rc, log = prepare(ws, url, out, old)
        empty_old = os.path.isfile(old) and os.path.getsize(old) == 0
        ok = rc != 0 and not empty_old
        check("connection refused: fails closed", ok,
              f"prepare rc={rc} zero_byte_old_index={empty_old}")


def case_timeout():
    with scenario("hang") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        empty_old = os.path.isfile(old) and os.path.getsize(old) == 0
        ok = rc != 0 and not empty_old
        check("timeout: fails closed after bounded max-time", ok,
              f"prepare rc={rc} zero_byte_old_index={empty_old}")


def case_empty200():
    with scenario("empty200") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        ok = rc != 0 and "empty" in log.lower()
        check("empty 200: rejected as an empty response", ok,
              f"prepare rc={rc} log_mentions_empty={'empty' in log.lower()}")


def case_malformed200():
    with scenario("malformed200") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        ok = rc != 0 and "malformed" in log.lower()
        check("malformed YAML 200: rejected as malformed", ok,
              f"prepare rc={rc} log_mentions_malformed={'malformed' in log.lower()}")


def case_nostructure200():
    with scenario("nostructure200") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        ok = rc != 0 and ("not a helm" in log.lower() or "invalid" in log.lower())
        check("valid YAML without entries: rejected as not a Helm index", ok,
              f"prepare rc={rc} log={log.strip().splitlines()[-1] if log.strip() else '(none)'}")


def case_missing_package():
    with scenario("nopkg") as (ws, fx, out, old):
        rc, log = prepare(ws, fx.url, out, old)
        ok = rc != 0
        check("published package unreachable: preparation fails", ok,
              f"prepare rc={rc}")


def case_old_package_mutated():
    with scenario("ok") as (ws, fx, out, old):
        rc, _ = prepare(ws, fx.url, out, old)
        pkg = os.path.join(out, f"{CHART}-0.1.0.tgz")
        with open(pkg, "ab") as fh:
            fh.write(b"\x00tamper")
        rrc, rlog = retention(ws, out, old)
        ok = rc == 0 and rrc != 0 and "digest" in rlog.lower()
        check("retained package altered: retention fails on digest", ok,
              f"prepare rc={rc} retention rc={rrc} mentions_digest={'digest' in rlog.lower()}")


def case_empty_old_index_rejected():
    with scenario("ok") as (ws, fx, out, old):
        rc, _ = prepare(ws, fx.url, out, old)
        open(old, "w").close()               # simulate an empty old-index input
        rrc, rlog = retention(ws, out, old)
        ok = rc == 0 and rrc != 0 and "empty" in rlog.lower()
        check("empty old index: validator refuses to pass vacuously", ok,
              f"prepare rc={rc} retention rc={rrc} mentions_empty={'empty' in rlog.lower()}")


def case_atomic_destination():
    with scenario("http500") as (ws, fx, out, old):
        sentinel = b"apiVersion: v1\nentries:\n  starexec:\n  - version: 0.1.0\n"
        with open(old, "wb") as fh:
            fh.write(sentinel)
        before = open(old, "rb").read()
        rc, _ = prepare(ws, fx.url, out, old)
        after = open(old, "rb").read() if os.path.isfile(old) else b"<deleted>"
        ok = rc != 0 and after == before
        check("failed fetch never replaces a good destination file", ok,
              f"prepare rc={rc} destination_unchanged={after == before} "
              f"size_before={len(before)} size_after={len(after)}")


CASES = [
    case_ok200,
    case_http404_default,
    case_http404_allowed,
    case_http500,
    case_conn_refused,
    case_timeout,
    case_empty200,
    case_malformed200,
    case_nostructure200,
    case_missing_package,
    case_old_package_mutated,
    case_empty_old_index_rejected,
    case_atomic_destination,
]


def main():
    global VERBOSE
    ap = argparse.ArgumentParser()
    ap.add_argument("-v", "--verbose", action="store_true")
    VERBOSE = ap.parse_args().verbose

    if not shutil.which("helm"):
        sys.exit("test-helm-repo-fetch: helm is required")

    print("test-helm-repo-fetch: driving the real scripts against a local HTTP fixture\n")
    for c in CASES:
        try:
            c()
        except Exception as e:  # a crashing case is a failing case
            check(c.__name__, False, f"raised {type(e).__name__}: {e}")

    failed = [n for n, ok, _ in RESULTS if not ok]
    print(f"\ntest-helm-repo-fetch: {len(RESULTS) - len(failed)}/{len(RESULTS)} passed")
    if failed:
        print("test-helm-repo-fetch: FAILED", file=sys.stderr)
        for n in failed:
            print(f"  - {n}", file=sys.stderr)
        return 1
    print("test-helm-repo-fetch: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
