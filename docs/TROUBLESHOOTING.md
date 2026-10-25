# Troubleshooting Guide

Solutions to common StarExec issues.

## Quick Diagnostics

### Check System Status

```bash
# Overall status
make status

# View logs
make logs

# Check specific services
podman ps -a
kubectl get pods -n starexec
```

### Common Commands

```bash
# Restart everything
make stop && make start

# Complete reset: deletes every volume of the environment (database and
# files) and the image. Back up first: make stop && make volumes-backup ENV=dev
make reset ENV=dev

# Check configuration
make config-show ENV=dev
```

## Podman Issues

### Rootless Prerequisites Missing (`newuidmap/newgidmap`, `/etc/subuid`, `/etc/subgid`)

**Problem:** `make start` or `podman` fails with user-namespace/rootless errors.

**Cause:** Rootless Podman requires:
- `newuidmap` / `newgidmap` binaries (usually from `uidmap` package)
- Subordinate UID/GID ranges for your user in `/etc/subuid` and `/etc/subgid`

**Solution:**

```bash
# Validate quickly
make verify-deps

# Manual checks
command -v newuidmap
command -v newgidmap
grep "^$USER:" /etc/subuid
grep "^$USER:" /etc/subgid
```

If mappings are missing, ask your admin to add them and re-login.

### Stale/Busy `starexec-net` Network

**Problem:** Deployment fails with errors like `remove device or resource busy`, especially after forced kills.

**Cause:** Stale network namespace state in rootless Podman.

**Solution:**

```bash
# Safe StarExec-only reset (does not prune all networks)
make network-reset

# Then redeploy
make start
```

Avoid `podman network prune` on shared systems unless you intentionally want global cleanup.

### Pasta Segfault / IPAM Errors on Rootless Podman

**Problem:** Deployment fails with `IPAM error: failed to get ips` or `pasta failed with exit code -1` during `podman play kube`. Volume creation (`make volumes-create`) may also fail with a false `Could not fix ownership` error.

**Cause:** The `pasta` rootless networking helper installed on the host is crashing (segfault). When Podman defaults to `pasta` for rootless networking and the binary is broken, every container start that requires networking fails.

**Symptoms include:**
- `pasta failed with exit code -1` or `signal: segmentation fault`
- `IPAM error: failed to find ip for subnet` during pod startup
- `Could not fix ownership for ...` during volume creation (false positive)

**Solution:**

```bash
# Switch rootless networking from broken pasta to slirp4netns.
# Single command fix (creates ~/.config/containers/containers.conf):
mkdir -p ~/.config/containers && printf '[network]\ndefault_rootless_network_cmd = "slirp4netns"\n' > ~/.config/containers/containers.conf

# Verify the change took effect:
podman info --format '{{.Host.RootlessNetworkCmd}}'
# Should output: slirp4netns
```

The Makefile `preflight-podman` target includes a guard (`require_rootless_network_healthy`) that detects this condition and prints the fix before deployment starts.

### PostgreSQL Permission Errors on Rootless Volumes

**Problem:** Postgres fails during init/start with permission denied in data directory.

**Cause:** Volume mountpoint owned by host user; postgres container runs as UID/GID `999`.

**Solution:**

```bash
# Recommended
make volumes-fix-permissions ENV=dev

# Manual equivalent
podman unshare chown -R 999:999 "$(podman volume inspect starexec-dev-postgres --format '{{.Mountpoint}}')"
```

### Permission Denied (Rootless Networking)

**Problem:** `podman` commands fail with permission denied or networking errors

**Cause:** Missing `passt` or rootless network helpers

**Solution:**

```bash
# Install helper tools
sudo apt-get install -y passt fuse-overlayfs

# Migrate podman data
podman system migrate

# Verify rootless mode
podman system info | grep rootless
# Should show: rootless: true
```

### Podman Socket Path Is Not Available

**Problem:** Startup fails because the configured socket path does not exist.

Typical symptoms:

```text
Podman socket path is not available: /run/user/1000/podman/podman.sock
```

**Cause:** The socket service is not running, the configured UID does not match the current user, or the host uses a non-standard rootless Podman setup.

**Solution:**

```bash
# Confirm your current UID
id -u

# Start the user socket on systemd-based Linux
systemctl --user enable --now podman.socket
systemctl --user status podman.socket

# Verify the expected socket path
ls -l /run/user/$(id -u)/podman/podman.sock
```

If your environment does not provide `systemd --user`, start the service manually:

```bash
podman system service --time=0 unix:///run/user/$(id -u)/podman/podman.sock &
```

If the values file hardcodes a different UID, `make start` will override it with the resolved
`PODMAN_SOCKET_PATH` and inject the socket gid into the pod. For direct Helm use, set the final
socket path explicitly and pass the socket gid as a supplemental group:

```bash
export PODMAN_SOCKET_PATH="/run/user/$(id -u)/podman/podman.sock"
export PODMAN_SOCKET_GID="$(stat -c '%g' "$PODMAN_SOCKET_PATH")"
helm template starexec ./charts/starexec -f ./charts/starexec/values-local-dev.yaml \
  --set backend.type=podman \
  --set security.pod.supplementalGroups[0]="$PODMAN_SOCKET_GID" \
  --set-string podman.containerSocket.enabled=true \
  --set-string podman.containerSocket.hostPath="$PODMAN_SOCKET_PATH"
```

### Podman Socket Permission Denied

**Problem:** StarExec reaches the socket path but cannot open it.

Typical symptoms:

```text
com.sun.jna.LastErrorException: [13] Permission denied
```

**Cause:** The container user does not match the socket owner or is missing the socket group.

**Solution:**

```bash
# Run the preflight diagnostic
./scripts/preflight-podman.sh

# Inspect socket ownership
stat -c '%u %g %A %n' /run/user/$(id -u)/podman/podman.sock
id
```

If the socket group is the issue, inject that group into the container runtime configuration:

```yaml
services:
  starexec:
    group_add: ["<socket_gid>"]
```

For Podman-specific compose overrides:

```bash
docker compose -f docker-compose.yml -f docker-compose.podman.yml up -d
```

If your institution masks the user socket service, copy the Podman user unit files into `~/.config/systemd/user/`, reload the user daemon, and then enable `podman.socket`.

### Rootless Podman Pod Exits After Suspend or Logout

**Problem:** `make status` shows StarExec as `Exited` after the laptop sleeps, the user logs out, or the host restarts.

**Cause:** StarExec runs under rootless Podman in the user's systemd session. If that session is stopped, Podman cleans up the pod. Lingering keeps user services alive outside an active login session, but it does not survive a full reboot.

**Solution:**

```bash
# Keep user services alive outside the login session
loginctl enable-linger $USER

# After a reboot or if the pod already exited, start StarExec again
make start
```

### Cgroup Controller Delegation Errors

**Problem:** Containers fail to start with cgroup-related errors

**Cause:** Missing cgroup controller delegation for systemd user slices

**Solution:**

```bash
# Check current status
make verify-deps

# Automatic fix (requires sudo)
make fix-cgroup-delegation

# Verify fix
make verify-deps
```

**Manual fix** (if automatic fails):

```bash
# Get your user ID
echo $UID  # Example: 1000

# Create systemd drop-in
sudo mkdir -p /etc/systemd/system/user@$UID.service.d
sudo tee /etc/systemd/system/user@$UID.service.d/delegate.conf > /dev/null <<EOF
[Service]
Delegate=cpu cpuset io memory pids
MemoryAccounting=yes
CPUAccounting=yes
IOAccounting=yes
TasksAccounting=yes
EOF

# Reload systemd
sudo systemctl daemon-reload
sudo systemctl restart user@$UID.service

# Restart user session
systemctl --user daemon-reload
```

### Slow Maven Builds

**Problem:** `make build` takes very long

**Cause:** Missing local Maven cache

**Solution:**

```bash
# Use cached build
make build-cached

# Or pull prebuilt image
podman pull ghcr.io/starexecmiami/starexec:latest
```

### Build Fails with Registry Timeout ("server misbehaving")

**Problem:** `make build` or `make start` fails with:
```
Error: creating build container: initializing source docker://node:24-alpine: 
pinging container registry registry-1.docker.io: Get "https://registry-1.docker.io/v2/": 
dial tcp: lookup registry-1.docker.io on 127.0.0.53:53: server misbehaving
make: *** [Makefile:231: build] Error 125
```

**Root Cause:** Podman cannot reach the Docker Hub registry. Base images are not cached locally. This may indicate no internet connectivity, DNS failure, or firewall blocking.

**Solution:**

**If you have not yet run `make cache-images`:**

1. Restore internet connectivity (or configure DNS/firewall rules)
2. Pre-cache all base images:
   ```bash
   make cache-images
   ```
3. Retry the build, then deploy it. `LOCAL=1` deploys the image you just
   built; without it, `make start` replaces it with the registry's `latest`
   whenever the registry is reachable:
   ```bash
   make build
   make start ENV=dev LOCAL=1
   make status
   ```

**If you have already run `make cache-images`:**

1. Use strict offline build mode (fails fast instead of timing out):
   ```bash
   make build-offline
   ```

2. If the error persists, internet may be required to pull a missing image. Restore connectivity and run:
   ```bash
   make build-fresh
   ```

**Pull Policy Reference:**

The Makefile now supports three pull policies for container base images:

| Command | Flag | Behavior | Use Case |
|---------|------|----------|----------|
| `make build` | `--pull=missing` | Use local cache if available; pull from registry if absent | Normal development (default) |
| `make build-fresh` | `--pull=always` | Always fetch fresh base images from registry | Updating cached layers or debugging stale cache issues |
| `make build-offline` | `--pull=never` | Fail immediately if base image is not cached locally | Strict offline environments (after `make cache-images`) |

**Note:** For Podman/Makefile deployments only — Docker Compose does not support these targets.

### Make Hangs Waiting for sudo Password

**Problem:** `make` stalls mid-run on a target such as `make build` or `make test-deps`

**Cause:** When `$(PODMAN_CMD)` resolves to `sudo podman` (rootful mode), `sudo` re-prompts for a password once the credential cache expires (default 5–15 minutes). On an interactive terminal the prompt may be swallowed by `make`'s output buffering; on CI runners this is a non-issue when `NOPASSWD` is set in `sudoers`.

**Solution:** Refresh the credential cache immediately before starting a long build:

```bash
sudo -v && make test-deps
```

## Image and Registry Issues

### Changes not appearing after push

**Problem:** You pushed a backend change to the remote repository, but `make start` still runs the old version.

**Cause:** Podman often uses the local image if it exists. Rolling tags like `latest` or `dev` may not be automatically updated if an image with that tag already exists in the local cache, or there may be a tag synchronization issue.

**Solution:**

```bash
# Update all images from the registry (handles tags and synchronization)
make pull

# Restart the application
make start
```

### Container Won't Start

**Problem:** Container exits immediately after starting

**Diagnostics:**

```bash
# Check logs
podman logs starexec-app

# Inspect exit code
podman inspect starexec-app | grep -A 5 State
```

**Common causes:**

1. **Database not ready**
   ```bash
   # Wait for PostgreSQL
   podman logs starexec-postgres | grep "ready to accept"
   ```

2. **Port already in use**
   ```bash
   # Check port 7827
   sudo lsof -i :7827
   
   # Change port if needed
   make deploy-podman APP_PORT=8080
   ```

3. **Volume mount issues**
   ```bash
   # Check volume exists
   podman volume ls | grep starexec
   
   # Create any missing volumes; existing volumes keep their data
   make volumes-create ENV=dev

   # Give the PostgreSQL volume back to the postgres user (999:999)
   make volumes-fix-permissions ENV=dev
   ```

## Database Issues

### Connection Refused

**Problem:** Application can't connect to PostgreSQL

**Diagnostics:**

```bash
# Check if PostgreSQL is running
podman ps | grep postgres

# Test connection
podman exec starexec-postgres pg_isready

# Check network
podman network inspect starexec-net
```

**Solution:**

```bash
# Ensure database is started first
podman start starexec-postgres
sleep 5  # Wait for startup

# Verify connection from app
podman exec starexec-app bash -c '
  psql "postgresql://$STAREXEC_DB_USER:$STAREXEC_DB_PASSWORD@$STAREXEC_DB_HOST:5432/$STAREXEC_DB_NAME" \
    -c "SELECT 1"
'
```

### Authentication Failed

**Problem:** `FATAL: password authentication failed for user "starexec"`

**Cause:** The client sends a password other than the one PostgreSQL stores for the role. The PostgreSQL image
sets that password only when it creates an empty data directory. Changing `postgres.password`, the Secret or
`STAREXEC_DB_PASSWORD` later does not change the stored one, so exporting a new password and restarting
cannot fix this error.

The embedded database checks passwords only for connections from other hosts: its `pg_hba.conf` trusts the
socket and loopback (`127.0.0.1`, `::1`). The application reaches it at `localhost` from the same pod, so it
never fails this way, and neither does `make db-shell`, which runs `psql` inside the PostgreSQL container.
Expect this error from an external database (`postgres.host` other than `localhost`), which the application
logs in to with the password, or from a client outside the pod.

**Solution:** set the stored password to the configured one, from a session that needs no password:

```bash
make db-shell
\password starexec
\q
```

`\password` asks twice and sends only a hash, so the password stays out of the shell history and the server
log. Type the password from the values file (`postgres.password`) or from the Secret named by
`postgres.existingSecret`. On an external database, run `\password` as an administrator of that server.

To change the password, change it with `\password` first, then in the values file or the Secret, then restart
the application: `make stop && make start` on Podman; on Kubernetes, upgrade the release from the changed
values file, or run `kubectl rollout restart` on its Deployment after changing the Secret.

### Migration Failures

**Problem:** Flyway migration errors on startup

**Diagnostics:**

```bash
# Check migration status
make db-status

# View migration history
make db-shell
\dt flyway_schema_history
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

**Solution:**

```bash
# Repair migration metadata
make migrate-repair

# Force re-run migrations
make migrate-podman
```

### Flyway Advisory Lock Stuck

**Problem:** `make migrate-repair` appears stuck or repeatedly fails to acquire lock.

**Cause:** Another backend session still holds an advisory lock.

**Solution:**

```bash
# 1) Inspect waiting/locking sessions
make db-shell
SELECT pid, usename, application_name, state, wait_event_type, wait_event, query
FROM pg_stat_activity
WHERE wait_event_type = 'Lock' OR query ILIKE '%flyway%';

# 2) If safe, terminate blocker
SELECT pg_terminate_backend(<PID>);

# 3) Retry repair
make migrate-repair
```

Only terminate sessions you recognize as stale/non-critical.

### WAL Inconsistency After Forced Stop

**Problem:** PostgreSQL does not become ready; logs show messages like:
- `could not locate a valid checkpoint record`
- `invalid primary checkpoint record`
- `PANIC` / `WAL` recovery failures

**Cause:** Unclean shutdown or abrupt host/process termination while writing WAL.

**Recommended recovery order:**

```bash
# 1) Stop deployment
make stop

# 2) Keep a file-level copy of the damaged volumes: the restore writes over
#    them, and they hold everything written since the last backup
make volumes-backup ENV=dev

# 3) Restore a known-good backup (preferred). The copy from step 2 is now
#    the newest set, so name the good one
make volumes-restore ENV=dev RESTORE_TIMESTAMP=<timestamp of the last good set>

# 4) Start again with the same image
make start ENV=dev LOCAL=1
```

If no valid backup exists, inspect logs first (`make logs-postgres`). Use `pg_resetwal` only as a last resort and only with full awareness of data-loss risk.

**To start over with empty volumes:** this deletes every volume of the
environment, so the uploaded solvers, benchmarks and job output go with the
database. It also removes the legacy `starexec-data` and `starexec-postgres`
volumes if they exist, whatever `ENV` is. There is no database-only reset:
the files on the data volume belong to rows in the database. Keep a copy of
the damaged volumes first:

```bash
make stop
make volumes-backup ENV=dev    # file-level copy of all five volumes
make volumes-delete ENV=dev    # asks you to type DELETE
make volumes-create ENV=dev
make start ENV=dev             # migrations run on startup
```

### Database Locked

**Problem:** `database is locked` or cannot acquire lock

**Cause:** Another process accessing the database

**Solution:**

```bash
# Find blocking processes
make db-shell
SELECT pid, usename, query FROM pg_stat_activity WHERE state = 'active';

# Kill blocking process (if safe)
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid = <PID>;
```

## Deployment Issues

### Image Not Found

**Problem:** `Error: image not found`

**Solution:**

```bash
# Build locally
make build

# Or pull from registry
make image
```

### Volume Mount Failures

**Problem:** Volume mount errors or permission denied

**Diagnostics:**

```bash
# Check volume exists
podman volume ls | grep starexec-dev

# Inspect volume
podman volume inspect starexec-dev-data
```

**Solution:**

```bash
# Create any missing volumes; existing volumes keep their data
make volumes-create ENV=dev

# Give the PostgreSQL volume back to the postgres user (999:999)
make volumes-fix-permissions ENV=dev
```

### Port Already in Use

**Problem:** `bind: address already in use`

**Solution:**

```bash
# Find process using port
sudo lsof -i :7827

# Kill process or use different port
make deploy-podman APP_PORT=8080
```

### A setting has no effect

**Problem:** You exported a `STAREXEC_*` variable and restarted, but StarExec behaves as before.

**Cause:** StarExec reads these settings only from its container's environment, and an exported variable
reaches that environment only when the deployment passes it on:

| How StarExec was started | Where its settings live |
|---|---|
| `make start` with Helm installed | The values file: `charts/starexec/values-local-dev.yaml` for the default `ENV=dev`, `charts/starexec/values-<ENV>.yaml` for another `ENV`, or the file named by `VALS=`. `make start` also passes Helm the fixed `--set` options listed under `deploy-podman-helm` in the `Makefile`, such as the image, the port and the socket; no `STAREXEC_*` variable from your shell is among them. This path always runs the Podman backend. |
| `make start` without Helm | `render.yaml.template`. Only the variables that `scripts/generate-render-yaml.sh` substitutes come from your shell: the database settings, the port, the backend type, the volume names and the image. |
| `docker compose up` | The `environment:` of the `starexec` service in `docker-compose.yml` (Local backend). Docker Compose also merges a `docker-compose.override.yml` next to it, which keeps your changes out of the tracked file. |
| Kubernetes | The values file the release is deployed from, applied by upgrading the release. On Quokka that file is in git and Jenkins deploys it. |

**Solution:** put the setting where your deployment reads it, then restart. In a values file:

```yaml
podman:
  maxConcurrentJobs: 2       # STAREXEC_CONTAINER_MAX_CONCURRENT_JOBS (Podman backend)
backend:
  local:
    concurrency: 4           # STAREXEC_LOCAL_CONCURRENCY (Local backend)
env:                         # any variable without a key of its own
  STAREXEC_CPU_PARTITION_COUNT: "2"
```

```bash
make stop && make start
# What the application now sees (never print a password this way)
APP=$(podman ps --format '{{.Names}}' | grep -E '^starexec(-pod)?-app$')
podman exec "$APP" printenv STAREXEC_CPU_PARTITION_COUNT
```

Where `charts/starexec/templates/deployment.yaml` sets a variable from a key, use that key: the same name
under `env:` gives the container the variable twice. The `scalability.*` keys reach the Kubernetes backend only;
on Podman, set `STAREXEC_NODE_MULTIPLIER` or `STAREXEC_NUM_JOB_PAIRS_AT_A_TIME` under `env:`.

## Job Execution Issues

### Jobs Stuck in ENQUEUED

**Problem:** Jobs never start running

**Diagnostics:** count the job's pairs by status. A pair waits in one of two states, and each has its own causes:

```bash
make db-shell
SELECT status_code, COUNT(*) FROM job_pairs WHERE job_id = <JOB_ID> GROUP BY status_code;
```

Then search the application log (`make logs-app`) for the lines named below. A backend takes no work until it
has started, which the Local and Kubernetes backends log as `LocalBackend initialized successfully` and
`KubernetesNativeBackend initialized successfully`, and the Podman backend as `ContainerJobMonitor started`.
Use that line for Podman: some versions log `PodmanBackend initialized successfully` as soon as the container
engine answers, before start-up has finished.

**Status 1 (pending submission): the dispatcher has not handed the pairs to the backend.** It runs every 20
seconds and skips a queue in these cases:

- **The system is paused.** Log: `Not adding more job pairs to any queues, as the system is paused`. Resume
  with **Resume all** on the admin job page (`/secure/admin/job.jsp`).
- **The job's queue is not active.** Nothing is logged: the dispatcher reads only queues whose status is
  `ACTIVE`. Check with
  `SELECT q.name, q.status FROM jobs j JOIN queues q ON q.id = j.queue_id WHERE j.id = <JOB_ID>;`.
  Pause the job, use **Change Queue** on its page to pick an active queue, and resume it; or activate the queue.
- **The queue has no nodes.** Log: `Queue <q> has no nodes available (<n> pair(s) waiting to be submitted, <m>
  enqueued)`. As a WARN, the backend agrees that no node serves the queue: attach one, or bring it back. As an
  ERROR (`Queue <q> has no nodes associated with it (...), yet the backend reports it can accept work`),
  StarExec's node table and the cluster disagree, usually because a node was
  never registered: nodes are registered by a periodic task that logs `Loading worker nodes into the db`, so
  look for a warning after that line. The line is written when the condition starts, again every 6 hours while
  it lasts (`(first seen <n> minutes ago)`), and `Queue <q> has nodes again` at INFO when it ends.
- **The queue is full.** Log: `Not adding more job pairs to queue <q>, which has <n> pairs enqueued.` The queue
  already holds `STAREXEC_NODE_MULTIPLIER` (default 16) pairs per node in status 2, which are not starting.
  See status 2 below.
- **The backend refuses new work.** Log: `Queue <q> cannot accept work at the moment; deferring its pairs to a
  later pass rather than failing them`. The backend has not started or is shutting down, or, on Kubernetes,
  holds dispatch for a reason it logs itself on every pass while pairs wait, just before the first of these
  lines. A Podman backend that failed to start logged `Failed to initialize backend` at start-up, with the
  reason, and is not started again until StarExec restarts. The line is written when the refusal starts, again
  every 6 hours while pairs wait (`(first seen <n> minutes ago)`), and `Queue <q> accepts work again` at INFO
  when the queue takes pairs again.
- **Kubernetes is at its concurrency cap.** Log: `deferring pair <id> (...): at concurrency cap (<n>/<max>)`.
  At most `kubernetes.maxConcurrentJobs` pairs run at once; the rest wait in status 1.

StarExec keeps the no-nodes and refusal conditions in memory, so after a restart one that lasts is reported again
on the first pass that sees it.

**Status 2 (enqueued): the backend has the pairs but no free slot.** How many pairs run at once:

- **Podman:** `podman.maxConcurrentJobs` (default 1) per CPU partition, or `STAREXEC_PARTITION_MAX_JOBS` when
  set. The partitions come from `STAREXEC_CPU_PARTITIONS`, else `STAREXEC_CPU_PARTITION_COUNT` (default `auto`:
  one per NUMA node, so one on a single-node host). The startup log prints both numbers,
  `CpuPartitionManager: <n> partition(s) from ...` and `Container max concurrent jobs per CPU partition: <m>`,
  and a pair waiting for a slot logs `Podman submission waiting for available partition slot (...)`.
- **Local:** `STAREXEC_LOCAL_CONCURRENCY` (the chart sets it from `backend.local.concurrency`, default 1;
  unset, it is the smaller of 4 and the number of CPUs), or one per CPU in `STAREXEC_LOCAL_CORE_LIST` when that
  is set. The startup log prints `Max concurrency: <n>`.
- **Kubernetes:** the pair's pod is Pending. See [Pods Pending](#pods-pending) and
  [Kubernetes pair will not rerun or new work remains queued](#kubernetes-pair-will-not-rerun-or-new-work-remains-queued).

To change these settings, see [A setting has no effect](#a-setting-has-no-effect).

**Other causes:**

1. **Script path invalid**
   ```bash
   # Verify solver script exists
   podman exec starexec-app ls -la /app/data/solvers/
   ```

2. **[ROOT CAUSE — FIXED in jobscript] `(( ))` arithmetic kills script before EXIT trap**

   **Symptom:** LocalJobMonitor shows `checked N pairs, found 0 status files` indefinitely. Job watcher file shows `Child status: 1`, `Real time: ~0.011s`. The pair log (e.g. `1.txt`) is empty.

   **Root cause (confirmed):**
   In `jobscript` (the SGE job template), line 124 used the bash compound command `(( ))` to compute `NUM_BENCH_INPUTS`:
   ```bash
   ((NUM_BENCH_INPUTS = ${#BENCH_INPUT_PATHS[@]} - 1))
   ```
   `functions.bash` is sourced *before* this line and enables `set -euo pipefail`. When a job has no benchmark inputs, `BENCH_INPUT_PATHS[0]=""` is the only element, so the expression evaluates to `0`. In bash, `(( 0 ))` returns **exit code 1**, which `set -e` treats as a fatal error. The script aborts **before line 140** where `trap 'exitJobscript $?' EXIT` is registered. With no EXIT trap, no `status.json` is written. Meanwhile, runsolver (which wraps the script) **exits with 0** even when its child exits with 1, so `LocalBackend` sees a successful exit. `LocalJobMonitor` waits for a `status.json` that never arrives, leaving all pairs stuck in `ENQUEUED` forever.

   **Fix applied:** `jobscript` now uses arithmetic *expansion* `$(( ))` which never returns a non-zero exit code:
   ```bash
   NUM_BENCH_INPUTS=$(( ${#BENCH_INPUT_PATHS[@]} - 1 ))
   ```
   A secondary safety net was added to `LocalBackend`: if the process exits 0 but no `status.json` exists, the pair is immediately marked `ERROR_RUNSCRIPT`.

   **How to diagnose before the fix:**
   ```bash
   # Check watcher file for a pair (e.g. pair_1)
   podman exec starexec-pod-app cat /app/data/logs/1/1/pair_1/1.txt.watcher
   # Look for: Child status: 1 AND Real time: ~0.01s → early script crash
   
   # Confirm no status.json
   podman exec starexec-pod-app ls /app/data/logs/1/1/pair_1/
   
   # Reproduce in container (should exit 1 before the fix, 0 after)
   podman exec starexec-pod-app bash -c '
     set -euo pipefail
     BENCH_INPUT_PATHS[0]=""
     (( NUM_BENCH_INPUTS = ${#BENCH_INPUT_PATHS[@]} - 1 )) && echo OK || echo FAILS_WITH_SET_E
   '
   ```

### Jobs Never Complete

**Problem:** Jobs stuck in RUNNING status

**Diagnostics:**

```bash
# Check for running containers
podman ps | grep starexec-job

# Check monitor logs
make logs-app | grep ContainerJobMonitor

# Check job output
podman exec starexec-app sh -c 'find "${STAREXEC_DATA_DIR:-/var/starexec/data}/output" -name "<JOB_ID>" -type d'
podman exec starexec-app sh -c 'cat "${STAREXEC_DATA_DIR:-/var/starexec/data}/output/<JOB_ID>"/*/status.json'
```

**Solution:**

1. **The Local backend's job timeout fired** (Local backend only)
   - The log carries `exceeded timeout of <N> seconds, killing process`. The Local backend stops every job
     script after `STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS` (default 3600, printed at startup as `Job timeout:`),
     whatever the pair's own limits.
   - Fix: raise it above the longest wallclock limit your jobs use, as described in
     [A setting has no effect](#a-setting-has-no-effect), and restart.

2. **Monitor crashed**
   ```bash
   # Restart application
   make stop && make start
   ```

3. **Solver crashed without output**
   - Check solver logs in job output directory

### A pair is held: "INGESTION REQUIRES INTERVENTION"

**Problem:** the log carries

```
Monitor: INGESTION REQUIRES INTERVENTION for pairId=<N> in <dir>. The results are retained
and the pair is left unresolved ... Cause: InvalidSnapshotException: <dir>/stage-status/1.json
carries status 4, which is not a terminal execution result
```

The pair stays `RUNNING`, and within three hours `FIND_BROKEN_JOB_PAIRS` records it as
`ERROR_SUBMIT_FAIL(9)` — a terminal status its own output contradicts.

**Tell the two causes apart by the stage number in `status.json`:**

```bash
cat /app/data/logs/<job_id>/<job_space_id>/pair_<pair_id>/status.json
```

| what it reads | cause | fixed by |
| --- | --- | --- |
| `"stageNumber":<N>` with N ≥ 1 | the stage failure race: the pair reported a limit breach and a poll landed between its two writes. Local backend only — the container and Kubernetes monitors read after exit. | reporting a stage failure once, as that stage |
| `"stageNumber":0` | a failure that belongs to no stage, most often a missing benchmark dependency (`jobscript:217`). Deterministic, and it reaches **every** backend. | the monitor accepting a pair-level result whose in-flight stage never finished |

**Finding every pair this has already affected**, read-only:

```sql
SELECT p.id AS pair_id, p.job_id, p.job_space_id, j.name AS job, p.end_time
FROM   starexec.job_pairs p JOIN starexec.jobs j ON j.id = p.job_id
WHERE  p.status_code = 9
ORDER  BY p.id;
```

then compare each candidate's `status.json` with that verdict. A pair whose output carries a
terminal status other than 9 — especially one whose `stage-status/*.json` still reads `4` — was
misrecorded.

**Which version this applies to.** On a deployment that predates these fixes, both causes
produce the hold and then the sweep's `ERROR_SUBMIT_FAIL`. On one that carries them, neither
cause can hold a pair — so a held pair on a current deployment is something else, and the table
above will send you the wrong way.

**There is no automatic repair.** Nothing re-examines a pair that is already `RUNNING`
(`LocalJobMonitor.registerJob` is called only at submit time), and a pair already swept to
`ERROR_SUBMIT_FAIL` is terminal. Rerunning such a pair **re-executes it**: that is a new
experiment, not a recovery of the original run, whose measurements are never ingested.

### Jobs Start but No status.json (LocalBackend in container)

**Problem:** Logs show `LocalJobMonitor - Monitor poll: checked N pairs, found 0 status files`. Jobs are enqueued and the monitor has registered them (they started), but no job writes `status.json`, so pairs never complete.

**Diagnostics:**

```bash
# Confirm backend: logs should show LocalJobMonitor (LocalBackend), not ContainerJobMonitor
podman logs starexec-pod-app 2>&1 | grep -E "LocalJobMonitor|PodmanBackend initialized"

# See if any job process is running inside the app container
podman exec starexec-pod-app ps aux | grep -E "bash|runsolver"

# Inspect one job’s log (path from job pair output dir, e.g. under JOB_LOG_DIRECTORY)
podman exec starexec-pod-app cat /path/to/pair_12345.txt

# Run one job script manually to see the real error (replace paths with real pair paths)
podman exec -e CONTAINER_MODE=true -e STAREXEC_OUTPUT_DIR=/tmp/out starexec-pod-app \
  bash /path/to/sge_scripts/<script> 2>&1
```

**Common causes:**

1. **Script fails before writing status** (e.g. `SCRIPT_DIR` or `functions.bash` missing in container, or `runsolver` not installed).
   - Fix: Install runsolver in the image or set `STAREXEC_LOCAL_USE_RUNSOLVER=false` and ensure script/solver paths exist inside the container.

2. **Jobs blocked in executor** (e.g. few worker threads and scripts hang).
   - Fix: a hung script holds its slot until `STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS` (default 3600) stops it, so
     lower that to free slots sooner; the number of slots is `STAREXEC_LOCAL_CONCURRENCY`
     (`backend.local.concurrency` in the chart), or the number of CPUs in `STAREXEC_LOCAL_CORE_LIST` when that
     is set. Both are printed at startup (`Job timeout:`,
     `Max concurrency:`); to change them, see [A setting has no effect](#a-setting-has-no-effect).

3. **Queue full so no new submissions:** `Not adding more job pairs to queue X, which has N pairs enqueued` — those N pairs are already submitted but not completing.
   - Fix: Resolve why they don’t write status (above); or reset pairs to PENDING and fix environment, then re-run.

## Kubernetes Issues

### Pods Pending

**Problem:** Pods stuck in `Pending` state

**Diagnostics:**

```bash
kubectl describe pod <pod-name> -n starexec
```

**Common causes:**

1. **Insufficient resources**
   ```bash
   # Check node capacity
   kubectl top nodes
   
   # Reduce resource requests
   helm upgrade starexec starexec/starexec --reuse-values \
     --set resources.app.requests.memory=1Gi
   ```

2. **PVC not bound**
   ```bash
   kubectl get pvc -n starexec
   
   # Check storage class exists
   kubectl get storageclass
   ```

### ImagePullBackOff

**Problem:** Can't pull container image

**Solution:**

```bash
# Check image exists
helm get values starexec -n starexec | grep image

# Pull image manually
podman pull ghcr.io/starexecmiami/starexec:latest

# Or use local image
helm upgrade starexec starexec/starexec \
  --set image.pullPolicy=Never \
  --set image.repository=localhost/starexec
```

### CrashLoopBackOff

**Problem:** Container repeatedly crashing

**Diagnostics:**

```bash
# Check logs
kubectl logs <pod-name> -n starexec --previous

# Check events
kubectl get events -n starexec --sort-by='.lastTimestamp'
```

**Common causes:**

1. **Database not ready** - Add init container or readiness check
2. **Missing configuration** - Verify secrets exist
3. **OOM killed** - Increase memory limit

### Rollout never becomes available

**Problem:** `kubectl wait --for=condition=available` times out, or a Helm upgrade never finishes, while the application pod is running but never ready.

**Cause:** Readiness reports whether this instance can serve usefully, which means reaching the database. It returns 503 while PostgreSQL is unreachable. Liveness deliberately does not check the database, so the pod keeps running rather than restarting into an outage. An unready deployment during a database problem is the probe working, not a probe defect.

**Solution:**

```bash
# 1) Confirm it is readiness, not a crash
kubectl get pods -n starexec
kubectl describe pod <pod-name> -n starexec | grep -A5 Readiness

# 2) Ask the endpoint directly
kubectl exec -n starexec <pod-name> -- \
  curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/starexec/public/health/readiness

# 3) 503 means the database is unreachable: check it
kubectl get pods -n starexec -l app.kubernetes.io/name=starexec
kubectl logs -n starexec <pod-name> | grep -i "database probe failed"

# 4) Verify credentials and service resolution
kubectl get secret starexec-postgres-credentials -n starexec

# 5) Retry the rollout only once the database is healthy
```

Do not relax or remove the readiness probe to make the wait succeed; that only hides the dependency failure until a user finds it.

### NFS server outage

**Problem:** A pod is stuck `Terminating`, `kubectl describe` reports `FailedKillPod ... KillContainerError: context deadline exceeded`, the replacement pod sits in `Init:0/1` for hours, `starexec` endpoints are empty, and the Helm release is left `failed`.

**Cause:** The PVCs are NFS-backed (`starexec-nfs-rwo`, served by `nfs-server.service` on the host). When the NFS server stops exporting `/starexec/k8s-shared`, every process doing I/O on the mounts blocks in uninterruptible sleep (D state) — including PostgreSQL. A D-state process cannot be killed, not even by SIGKILL, so the kubelet can never stop the old pod, its RWO volumes stay attached, and the rollout and its `--atomic` rollback both hang until the Helm timeout. On 2026-09-17 an unattended upgrade left `rpc.nfsd` failed and its stop hook ran `exportfs -au`; production lost its database and two deploy builds could not proceed.

**Diagnostics:**

```bash
# 1) Is the NFS server exporting?
systemctl status nfs-server --no-pager
sudo exportfs -v                  # must list /starexec/k8s-shared

# 2) Are processes blocked on NFS I/O?
ps -eo pid,stat,args | awk '$2 ~ /D/ {print}'   # D/Ds = uninterruptible sleep
mount | grep 'type nfs4'

# 3) What the cluster thinks
microk8s kubectl -n starexec get pods -o wide
microk8s kubectl -n starexec get events --sort-by=.lastTimestamp | tail
microk8s helm3 history starexec -n starexec | tail
```

**Solution:**

```bash
# 1) Restore the exports (root). Hard-mounted clients retry and unblock.
sudo systemctl restart nfs-server
sudo exportfs -v

# 2) Wait for D-state processes to clear and the pod to finish init
watch microk8s kubectl -n starexec get pods

# 3) Only if a pod is still stuck Terminating after the exports are back:
microk8s kubectl -n starexec delete pod <stuck-pod> --grace-period=0 --force

# 4) Once pods are Ready and endpoints are populated, clear a failed release:
microk8s helm3 rollback starexec <last-deployed-revision> -n starexec
```

**Prevention:**

- The Jenkins deploy pre-flight now refuses to start when `nfs-server` is not active, and when any pod is `Terminating` or not ready, so a deploy can no longer wedge on this condition.
- Install a health timer so a failed `nfs-server` is re-exported within a minute instead of staying down silently:

```ini
# /etc/systemd/system/nfs-health.service
[Unit]
Description=Verify the NFS exports and restart nfs-server if they are missing

[Service]
Type=oneshot
ExecStart=/bin/sh -c 'systemctl is-active --quiet nfs-server || { logger -t nfs-health "nfs-server inactive; restarting"; systemctl restart nfs-server; }; exportfs -v | grep -q /starexec/k8s-shared || { logger -t nfs-health "exports missing; restarting"; systemctl restart nfs-server; }'
```

```ini
# /etc/systemd/system/nfs-health.timer
[Unit]
Description=Periodic NFS export health check

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now nfs-health.timer
```

- Alert on the unit so a failure is noticed even without the timer: `node_systemd_unit_state{name="nfs-server.service",state="failed"} == 1`.
- Longer term, PostgreSQL on NFS is the fragility. A local volume (or an external database) removes this failure class, because the data directory can then never block in NFS D state.

### Kubernetes pair will not rerun or new work remains queued

**Problem:** A manual rerun reports that it did not complete, an automatic rerun stays eligible without resetting, a pair remains enqueued, or a queue stops accepting new work while capacity looks retained.

**Cause:** StarExec will not authorize replacement work until it can positively establish that the previous Kubernetes execution can no longer create a pod, execute, or write results. When the cluster cannot confirm that, the pair is left alone and its accounting is held, so a replacement is never scheduled beside an execution that may still be writing results. This is a deliberate trade: a deferred pair is visible and recoverable, a duplicated measurement is neither.

**Diagnostics:**

```bash
# What the application says it is waiting on
kubectl logs -n starexec deploy/<release> | grep -E \
  "Admission deferred|Admission still deferred|Deferring dispatch for queue|OPERATOR ACTION|could not be confirmed stopped|withholding its reset"
```

**Common causes:**

1. **A managed pod carries no usable execution identity**

   A pod labelled as managed but missing or carrying a malformed `starexec.org/exec-id` cannot be attributed to any pair, so no slot can be reserved for it. Admission for the affected queue is held until it disappears or is corrected.

   ```bash
   kubectl get pods -n starexec -l starexec.org/managed=true -o wide
   kubectl get pods -n starexec -l starexec.org/managed=true \
     -o custom-columns=NAME:.metadata.name,PHASE:.status.phase,EXEC:.metadata.labels.starexec\.org/exec-id
   ```

   Recovery is to let the pod finish, or to establish what it is and why it lost its identity. The log line naming it is `Admission deferred`, and `Admission restored` confirms the hold has cleared.

2. **An execution could not be confirmed stopped**

   The controller was deleted but its pods cannot be shown to have stopped, so the slot and tracking are retained. Inspect the execution named in the log line:

   ```bash
   kubectl get jobs,pods -n starexec -l starexec.org/exec-id=<execId>
   kubectl get jobs,pods -n starexec -l starexec.org/pair-id=<pairId>
   ```

   The labels are the supported way to find these objects. Do not reconstruct a job name by hand.

3. **The periodic sweep is disabled**

   The sweep is what settles ambiguous submissions, removes orphaned jobs, revisits executions that were never confirmed stopped, and inventories pods that outlived their job. Disabling it turns all four off, so held capacity is never reclaimed and the admission hold can neither engage nor clear. Recovery then requires restarting the application.

   ```bash
   kubectl logs -n starexec deploy/<release> | grep "Kubernetes orphan sweep disabled"
   kubectl get deploy/<release> -n starexec -o yaml | grep -A1 ORPHAN_SWEEP
   ```

   The interval is `kubernetes.orphanSweepIntervalMs` in the chart, surfaced as `STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS`, and defaults to `300000` (five minutes). Setting it to `0` disables the sweep entirely and is not recommended outside short-lived debugging; raise the interval instead if the sweep is too costly.

Deleting pods is not the normal remediation, and the backend deliberately holds no permission to do it. A pair that will not rerun is reporting that the cluster still cannot account for its previous execution — resolve that, and the pair proceeds on the next pass.

### Podman pair will not rerun, or a job or user cannot be deleted

**Problem:** A manual rerun reports that it did not complete, deleting a job leaves its pairs recorded as running, or deleting a user reports that one or more active jobs could not be safely stopped and retired.

**Cause:** A rerun, a job deletion and a user deletion each retire a pair's execution, so StarExec first asks the backend to stop it and to confirm that it can no longer run or write a result. The Podman backend stops the container it started for that execution, then lists every container labelled with the execution id. It withholds the confirmation while one of them is running, paused, restarting or cannot be inspected, or has exited without a `starexec.attempt` label and its result is not recorded yet. The pair is then left as it was, so a replacement never runs beside a container that may still write.

**Diagnostics:**

```bash
# What the application is waiting on
podman logs starexec-app 2>&1 | grep -E "is not confirmed stopped|could not be confirmed stopped|refusing to delete job"

# The containers the log line names (use its execution id)
podman ps -a --filter label=starexec.exec.id=<execId> \
  --format '{{.Names}} {{.Status}} pair={{index .Labels "starexec.pair.id"}} attempt={{index .Labels "starexec.attempt"}}'
```

The backend does not stop a container it is not tracking, even one that carries the execution id: execution ids start again with each application start, so that container may belong to another pair. Its `starexec.pair.id` label says which pair it runs. Once it has exited and its result is recorded, or it is gone, a rerun or user deletion asked for again proceeds, and a refused job deletion completes on its own: it is retried every five minutes. An exited container without an attempt label clears as soon as the monitor records its result.

## Performance Issues

### Slow Startup

**Problem:** Application takes 5+ minutes to start

**Cause:** Usually database migration bottleneck

**Solution:**

```bash
# Check migration progress
make logs-postgres | grep migration

# Migrations should complete in 30-60 seconds
# If longer, may indicate database performance issue
```

### High Memory Usage

**Problem:** Container using excessive memory

**Diagnostics:**

```bash
# Check memory usage
podman stats starexec-app

# Check the container's memory limit, current use and OOM kills (cgroup v2)
podman exec starexec-app sh -c 'cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.current; grep oom_kill /sys/fs/cgroup/memory.events'

# On Kubernetes, a container the kernel killed shows "Reason: OOMKilled" under "Last State"
# once it has restarted, or under "State" until it does
kubectl describe pod <pod-name> -n starexec
```

The image ships a JRE, without `jstat` or `jcmd`. Its JVM heap may grow to 2048 MiB (`-Xmx2048m`), so a memory limit at or below that leaves nothing for the memory the JVM uses outside the heap.

**Solution:**

Raise the limit, or lower `-Xmx` through `env.JAVA_OPTS` (see [Java System Properties](CONFIGURATION.md#java-system-properties)), so that the limit stays well above the heap:

```bash
helm upgrade starexec starexec/starexec --reuse-values \
  --set resources.app.limits.memory=4Gi
```

### Jobs Running Slowly

**Problem:** Jobs take much longer than expected

**Diagnostics:**

```bash
# Check CPU/memory limits
podman inspect starexec-job-<id> | grep -A 10 Resources

# Check for resource contention
podman stats
```

**Solution:** set these in the values file's `env:` map (see [A setting has no effect](#a-setting-has-no-effect)),
then run `make stop && make start`:

```yaml
env:
  # The Podman cgroup memory ceiling of every job container (default 2048)
  STAREXEC_CONTAINER_DEFAULT_MEMORY_MB: "8192"
  # Example for a 16-physical-core host: four cores per solver partition
  STAREXEC_CPU_PARTITION_COUNT: "4"
```

Each partition runs `podman.maxConcurrentJobs` pairs at once, so four partitions also run four times as many
pairs.

CPU-time and wallclock budgets must be changed in the StarExec queue/job. For
scientific runs, derive partitions from the host topology as described in
[CPU partition scheduling](cpu-partition-scheduling.md). On an unpartitioned
host, `STAREXEC_CONTAINER_CPU_QUOTA_CORES` is only a CFS safety cap and does not
provide affinity.

## Volume and Backup Issues

### Backup Fails

**Problem:** `make volumes-backup` fails

**Diagnostics:**

```bash
# Check available disk space
df -h /tmp

# Check volume exists
podman volume ls | grep starexec
```

**Solution:**

```bash
# Free up space
make volumes-cleanup ENV=dev

# Use different backup location
BACKUP_DIR=/mnt/backups make volumes-backup
```

### Restore Corrupts Database

**Problem:** Database won't start after restore

**Cause:** Restored while database was running (WAL mismatch)

**Solution:**

```bash
# ⚠️ ALWAYS stop before restore
make stop

# Restore
make volumes-restore ENV=dev

# Start again with the same image
make start ENV=dev LOCAL=1
```

## Known Issues

### Backend Maturity

| Backend | Status | Notes |
|---------|--------|-------|
| `local` | ✅ Stable | Dev only, no isolation |
| `podman` | ✅ Production | Recommended |
| `kubernetes` | ⚠️ Limited | Max concurrent jobs configurable via `STAREXEC_K8S_MAX_CONCURRENT_JOBS` (default 50) |
| `sge` | ⚠️ Legacy | Tests disabled |
| `oar` | ⚠️ Legacy | Tests disabled |

### Known Gaps

1. **No horizontal scaling** - Single-instance monolith
2. **No Prometheus endpoint** - Backend `getStats()` is internal only; manual Prometheus setup needed
3. **Kubernetes backend incomplete** - Hybrid design has bottleneck
4. **SGE/OAR tests disabled** - Use PowerMockito, not maintained

## Upload Extraction Issues

### Large benchmark upload fails during extraction

**Symptoms:**

- upload session finishes successfully, but the background upload job fails
- error messages include `Failed to extract archive`
- failures are more common with `.tgz` archives containing many small files

**What changed:**

- async `.tar`, `.tar.gz`, and `.tgz` extraction now runs in-process
- extraction uses temporary `upload_*.extracting` directories before promotion
- extraction is limited by both disk quota and explicit safety caps

**Things to check:**

1. User has enough remaining disk quota for the extracted archive contents
2. `STAREXEC_UPLOAD_EXTRACTION_TIMEOUT_SECONDS` is high enough for your storage backend
3. `STAREXEC_UPLOAD_EXTRACTION_MAX_UNCOMPRESSED_BYTES` is not lower than the expected extracted dataset size
4. Shared storage latency, especially NFS, is not causing extraction to exceed the timeout window

**Relevant settings:**

```bash
STAREXEC_UPLOAD_EXTRACTION_TIMEOUT_SECONDS=1800
STAREXEC_UPLOAD_EXTRACTION_MAX_UNCOMPRESSED_BYTES=107374182400
```

**Recommended tuning approach:**

- increase `STAREXEC_UPLOAD_EXTRACTION_TIMEOUT_SECONDS` first for slow storage
- increase `STAREXEC_UPLOAD_EXTRACTION_MAX_UNCOMPRESSED_BYTES` only when the expected extracted size legitimately exceeds the default cap
- do not raise limits blindly; confirm expected archive size and user quota first

### Temporary upload extraction directories accumulate

Temporary extraction directories now end with `.extracting` and are safe to clean only when they are stale and unfinished. Finalized `upload_*` extraction directories may contain benchmark files that are already referenced by the database and should **not** be removed manually unless you have confirmed they are unreferenced.

## Getting More Help

### Enable Debug Logging

StarExec's own classes (`org.starexec`) already log at DEBUG, except `SessionFilter`, `SessionUtil` and
`Permissions`, which log at INFO. The lines go to the container log (`make logs-app`, `podman logs`,
`kubectl logs`) and to `/var/log/starexec/starexec.log` inside the application container.

Everything else logs at the root level, INFO, unless `logback.xml` names it (Hibernate logs at WARN). The root
level is fixed when the WAR is built: `logback.xml` takes it from the `LOGGING_LEVEL` property in `pom.xml`, and
the file is not reloaded while Tomcat runs. No environment variable
changes it; `LOG_LEVEL`, which the chart sets, is not read by the application. To raise it, change
`<LOGGING_LEVEL>` in `pom.xml` and rebuild the image (`make build`): the image runs Maven itself, so a `-D`
option on your own Maven command does not reach it. A WAR you build yourself takes `-DLOGGING_LEVEL=DEBUG`.

### Collect Diagnostic Info

```bash
# System info
make status > diagnostics.txt
make config-show ENV=dev >> diagnostics.txt

# Logs
make logs >> diagnostics.txt

# Database state
make db-shell << EOF >> diagnostics.txt
SELECT version();
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
EOF
```

### Report Issues

Include in bug reports:

1. StarExec version (`git rev-parse HEAD`)
2. Deployment method (Podman/Docker/Kubernetes)
3. Environment (dev/ci/prod)
4. Error messages and logs
5. Steps to reproduce

**Submit issues:** [GitHub Issues](https://github.com/StarExecMiami/StarExec/issues)

## Next Steps

- **Architecture deep dive:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Performance tuning:** [PERFORMANCE.md](PERFORMANCE.md)
- **Security hardening:** [SECURITY.md](SECURITY.md)
