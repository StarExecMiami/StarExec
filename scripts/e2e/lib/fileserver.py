"""A throwaway HTTP file server on the stack's own network, for checks that make the app download a URL.

The app reaches the server by its container name, which resolves to a private address, so the stack must
set STAREXEC_URL_DOWNLOAD_ALLOW_PRIVATE_NETWORKS=true. Alpine's busybox has no httpd applet, so a Node
one-liner serves the files. Podman networks only (the Local and Podman stacks); where the server cannot
start, the caller is BLOCKED with the reason.
"""

import contextlib
import subprocess
import time

from lib.report import Blocked

IMAGE = "docker.io/library/node:24-alpine"
PORT = 8080
SERVER = (
    "const h=require('http'),f=require('fs'),p=require('path');"
    "h.createServer((q,s)=>{const n=p.join('/srv',p.basename(q.url.split('?')[0]));"
    "f.stat(n,(e,st)=>{if(e||!st.isFile()){s.writeHead(404);s.end();return;}"
    "s.writeHead(200,{'Content-Length':st.size});f.createReadStream(n).pipe(s);});}).listen(%d);"
    % PORT
)


@contextlib.contextmanager
def serving(ctx, folder, probe):
    """Serve `folder` read-only; yield the host name the app reaches the server at, on port PORT.

    `probe` is a file in `folder`. The server must serve it before this yields, so the caller's first
    request fetches the very file the app will fetch. The server is removed on exit, however the caller ends.
    """
    stack = ctx.cfg.deployment_name
    host = stack + "-files"
    subprocess.run(["podman", "rm", "-f", host], capture_output=True)
    started = subprocess.run(
        [
            "podman",
            "run",
            "-d",
            "--name",
            host,
            "--network",
            stack,
            "-v",
            folder + ":/srv:ro",
            IMAGE,
            "node",
            "-e",
            SERVER,
        ],
        capture_output=True,
        text=True,
    )
    try:
        if started.returncode != 0:
            raise Blocked(
                "could not start the file server: " + started.stderr.strip()[:200]
            )
        for _ in range(40):
            fetched = subprocess.run(
                [
                    "podman",
                    "exec",
                    host,
                    "wget",
                    "-q",
                    "-O",
                    "/dev/null",
                    "http://127.0.0.1:%d/%s" % (PORT, probe),
                ],
                capture_output=True,
            )
            if fetched.returncode == 0:
                break
            time.sleep(0.5)
        else:
            tail = subprocess.run(
                ["podman", "logs", "--tail", "5", host], capture_output=True, text=True
            )
            raise Blocked(
                "the file server never served the archive: "
                + (tail.stdout + tail.stderr)[-300:]
            )
        yield host
    finally:
        subprocess.run(["podman", "rm", "-f", host], capture_output=True)
