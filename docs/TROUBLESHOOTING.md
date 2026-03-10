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

# Complete reset (⚠️ deletes data)
make reset ENV=dev

# Check configuration
make config-show ENV=dev
```

## Podman Issues

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
   
   # Recreate volumes
   make volumes-delete ENV=dev
   make volumes-create ENV=dev
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

**Problem:** `FATAL: password authentication failed`

**Cause:** Password mismatch between app and database

**Solution:**

```bash
# Check password is set
echo $STAREXEC_DB_PASSWORD

# Verify it matches database
make db-shell
# Try logging in - if it works, password is correct

# If password is wrong, reset:
make stop
export STAREXEC_DB_PASSWORD="new-password"
make start
```

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

**For corrupted state:**

```bash
# ⚠️ DESTRUCTIVE: Complete database reset
make stop
make volumes-delete ENV=dev
make volumes-create ENV=dev
make start  # Migrations run automatically
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
# Recreate volumes
make volumes-delete ENV=dev
make volumes-create ENV=dev
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

## Job Execution Issues

### Jobs Stuck in ENQUEUED

**Problem:** Jobs never start running

**Diagnostics:**

```bash
# Check backend logs
make logs-app | grep Backend

# Query job status
make db-shell
SELECT jp.id, jp.status_code, jp.start_time 
FROM job_pairs jp 
WHERE jp.job_id = <JOB_ID>;
```

**Common causes:**

1. **Backend not initialized**
   - Check logs for `PodmanBackend initialized`

2. **No compute capacity**
   ```bash
   # Check concurrency limit
   grep STAREXEC_LOCAL_CONCURRENCY render.yaml
   ```

3. **Script path invalid**
   ```bash
   # Verify solver script exists
   podman exec starexec-app ls -la /app/data/solvers/
   ```

4. **[ROOT CAUSE — FIXED in jobscript] `(( ))` arithmetic kills script before EXIT trap**

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
find /var/starexec -name "<JOB_ID>" -type d
cat /var/starexec/output/<JOB_ID>/*/status.json
```

**Solution:**

1. **Job timeout hit**
   ```bash
   # Increase timeout
   export STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS=7200
   make stop && make start
   ```

2. **Monitor crashed**
   ```bash
   # Restart application
   make stop && make start
   ```

3. **Solver crashed without output**
   - Check solver logs in job output directory

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
   - Fix: Set `STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS` so stuck jobs are killed; check `STAREXEC_LOCAL_CONCURRENCY`.

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
   helm upgrade starexec starexec/starexec \
     --set resources.requests.memory=1Gi
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

# Check JVM heap
podman exec starexec-app jstat -gc 1
```

**Solution:**

```bash
# Tune JVM settings
helm upgrade starexec starexec/starexec \
  --set javaOpts="-Xmx2g -XX:+UseG1GC"
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

**Solution:**

```bash
# Increase container resources
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=8192
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=2
make stop && make start
```

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

# Start fresh
make start
```

## Known Issues

### Backend Maturity

| Backend | Status | Notes |
|---------|--------|-------|
| `local` | ✅ Stable | Dev only, no isolation |
| `podman` | ✅ Production | Recommended |
| `kubernetes` | ⚠️ Limited | 50-job hardcoded limit |
| `sge` | ⚠️ Legacy | Tests disabled |
| `oar` | ⚠️ Legacy | Tests disabled |

### Known Gaps

1. **No horizontal scaling** - Single-instance monolith
2. **No built-in metrics** - Manual Prometheus setup needed
3. **Kubernetes backend incomplete** - Hybrid design has bottleneck
4. **SGE/OAR tests disabled** - Use PowerMockito, not maintained

## Getting More Help

### Enable Debug Logging

Edit `/WEB-INF/classes/logback.xml`:

```xml
<logger name="org.starexec" level="DEBUG"/>
```

Or set environment variable:

```bash
export LOGGING_LEVEL_ORG_STAREXEC=DEBUG
```

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