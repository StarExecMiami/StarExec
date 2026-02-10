# StarExec Staging Validation Checklist

**Purpose:** Comprehensive, step-by-step validation of the new decoupled migration architecture before production rollout.

**Audience:** Operations team, DevOps engineers, platform maintainers

**Duration:** ~30 minutes per deployment target (Docker Compose, Podman, Kubernetes)

**Success Criteria:** All checks pass with green ✅ status

---

## Pre-Validation: Prerequisites

Before you begin, verify you have:

- [ ] Access to the staging environment (Docker, Podman, or Kubernetes cluster)
- [ ] Git repository cloned: `git clone https://github.com/starexecmiami/starexec.git`
- [ ] Latest code on `main` or `develop` branch: `git pull origin main`
- [ ] Docker/Podman installed and running (for local tests)
- [ ] `kubectl` configured for Kubernetes staging cluster (for K8s tests)
- [ ] Database credentials available (see `.env` or team secrets)
- [ ] A test database password you can use (not production)
- [ ] Network access to required registries (ghcr.io if pulling images)

---

## Section 1: Docker Compose Validation (Local/Staging)

> **Target Environment:** Local Docker or staging Docker infrastructure  
> **Expected Duration:** 15 minutes  
> **Success Indicator:** Application responding at http://localhost:8080/starexec

### Step 1.1: Verify Docker Images Available

**Command:**
```bash
docker pull ghcr.io/starexecmiami/starexec:latest
docker images | grep starexec
```

**Expected Output:**
```
REPOSITORY                            TAG       IMAGE ID      CREATED      SIZE
ghcr.io/starexecmiami/starexec        latest    abc1234def56  2 hours ago  1.2GB
```

**✅ Pass Condition:**
- [ ] Image pulls successfully
- [ ] Image size is reasonable (>500MB, <2GB)
- [ ] Created timestamp is recent (within last 24 hours)

**❌ Fail Condition:**
- [ ] Image not found or pull fails → Update `STAREXEC_VERSION` in `.env`
- [ ] Image is very old (>1 week) → Rebuild with `docker compose up -d --build`

---

### Step 1.2: Verify Docker Compose Configuration

**Command:**
```bash
cd /path/to/starexec
cat docker-compose.yml | head -50
```

**Expected Output (First 50 lines should include):**
```
services:
  postgres:
    container_name: starexec-postgres
    image: postgres:15
    ...
  migrations:
    container_name: starexec-migrations
    image: ghcr.io/starexecmiami/starexec:...
    ...
  starexec:
    container_name: starexec-app
    ...
```

**✅ Pass Condition:**
- [ ] File exists and contains `postgres`, `migrations`, and `starexec` services
- [ ] `migrations` service depends on `postgres`
- [ ] `starexec` service depends on both `postgres` and `migrations`
- [ ] Services configured with health checks or completion conditions

**❌ Fail Condition:**
- [ ] File missing or malformed YAML → Clone fresh repository
- [ ] Services missing → Use consolidated docker-compose.yml from git main

---

### Step 1.3: Set Environment Variables

**Command:**
```bash
cd /path/to/starexec

# Create or update .env file
cat > .env << 'EOF'
STAREXEC_DB_PASSWORD=staging_test_password_12345
STAREXEC_VERSION=latest
VOLUME_PREFIX=starexec_staging
EOF

# Verify .env was created
cat .env
```

**Expected Output:**
```
STAREXEC_DB_PASSWORD=staging_test_password_12345
STAREXEC_VERSION=latest
VOLUME_PREFIX=starexec_staging
```

**✅ Pass Condition:**
- [ ] .env file created with valid variables
- [ ] `STAREXEC_DB_PASSWORD` is set to a test password (not empty)
- [ ] `STAREXEC_VERSION` matches available image tag

**❌ Fail Condition:**
- [ ] Variables not set → Environment variables won't be passed to containers

---

### Step 1.4: Clean Up Previous Deployments

**Command:**
```bash
# Stop and remove old containers and volumes
docker compose down -v

# Verify cleanup
docker compose ps
```

**Expected Output:**
```
NAME      COMMAND   SERVICE   STATUS   PORTS
(empty - no running containers)
```

**✅ Pass Condition:**
- [ ] No containers running
- [ ] No error messages
- [ ] Previous data volumes removed

**❌ Fail Condition:**
- [ ] Containers still running → Run `docker compose kill` then `docker compose down -v`

---

### Step 1.5: Start Services

**Command:**
```bash
docker compose up -d --build
```

**Expected Output:**
```
[+] Building 0.0s (0/0)
[+] Running 4/4
 ✔ Network starexec_starexec_network  Created
 ✔ Container starexec-postgres        Started
 ✔ Container starexec-migrations      Started
 ✔ Container starexec-app             Started
```

**✅ Pass Condition:**
- [ ] All services show "Created" or "Started" status
- [ ] No error messages or warnings
- [ ] Docker Compose returns successfully

**❌ Fail Condition:**
- [ ] Services fail to start → Check with `docker compose logs`
- [ ] Network creation fails → Run `docker network prune` then retry

---

### Step 1.6: Wait for PostgreSQL to Be Healthy

**Command:**
```bash
# Method 1: Monitor until healthy (poll for ~30 seconds)
for i in {1..30}; do
  if docker compose exec -T postgres pg_isready -U starexec >/dev/null 2>&1; then
    echo "✅ PostgreSQL is healthy (attempt $i)"
    break
  fi
  echo "⏳ Waiting for PostgreSQL... (attempt $i/30)"
  sleep 1
done

# Method 2: Check health status directly
docker compose ps postgres
```

**Expected Output (Method 1):**
```
✅ PostgreSQL is healthy (attempt 5)
```

**Expected Output (Method 2):**
```
NAME               COMMAND                 SERVICE   STATUS       PORTS
starexec-postgres  "docker-entrypoint..."  postgres  healthy      5432/tcp
```

**✅ Pass Condition:**
- [ ] PostgreSQL reaches healthy status within 30 seconds
- [ ] `pg_isready` returns success exit code (0)
- [ ] Docker Compose status shows `healthy`

**❌ Fail Condition:**
- [ ] PostgreSQL never becomes healthy → Check logs with `docker compose logs postgres`
- [ ] Connection refused → Verify port 5432 is free

---

### Step 1.7: Monitor Migration Service

**Command:**
```bash
# Watch migration progress for up to 120 seconds
timeout 120 bash -c '
while true; do
  STATUS=$(docker compose ps migrations --format "{{.State}}" 2>/dev/null || echo "unknown")
  EXIT_CODE=$(docker compose ps migrations --format "{{.ExitCode}}" 2>/dev/null || echo "unknown")
  
  echo "[$(date +%H:%M:%S)] Status: $STATUS | Exit Code: $EXIT_CODE"
  
  if [ "$STATUS" = "exited" ]; then
    if [ "$EXIT_CODE" = "0" ]; then
      echo "✅ Migrations completed successfully!"
      break
    else
      echo "❌ Migrations failed with exit code $EXIT_CODE"
      exit 1
    fi
  fi
  
  sleep 3
done
'

# View migration logs
docker compose logs migrations
```

**Expected Output:**
```
[14:35:20] Status: running | Exit Code: unknown
[14:35:23] Status: running | Exit Code: unknown
[14:35:26] Status: exited | Exit Code: 0
✅ Migrations completed successfully!

[MIGRATION][CONFIG] Loading database configuration from environment variables...
[MIGRATION][CONFIG] Configuration loaded:
[MIGRATION][CONFIG]   Host: postgres
[MIGRATION][CONFIG]   Port: 5432
[MIGRATION][CONFIG]   Database: starexec
[MIGRATION][CONFIG]   User: starexec
[MIGRATION][CONFIG]   Password: ***REDACTED***
[MIGRATION][VERIFY] ✅ Migration directory verified: 23 SQL files found
[MIGRATION][SUCCESS] ✅ Migration execution completed
```

**✅ Pass Condition:**
- [ ] Migrations service transitions to "exited" status
- [ ] Exit code is 0 (success)
- [ ] Logs show "Migration execution completed"
- [ ] Multiple migration files counted (>20)
- [ ] No actual passwords visible in logs (only `***REDACTED***`)

**❌ Fail Condition:**
- [ ] Exit code non-zero → Check logs for specific error
- [ ] Service never exits → May be stuck; check database logs
- [ ] Actual password visible in logs → Security issue; see Section 5

---

### Step 1.8: Verify Credential Redaction (Security Check)

**Command:**
```bash
# Check that migration logs DO NOT contain actual password
LOGS=$(docker compose logs migrations 2>/dev/null)

# Check for the password we set in .env
if echo "$LOGS" | grep -i "staging_test_password_12345"; then
  echo "❌ SECURITY ISSUE: Password appears unredacted in logs!"
  exit 1
fi

# Check that redacted marker appears instead
if echo "$LOGS" | grep "\*\*\*REDACTED\*\*\*"; then
  echo "✅ Credentials properly redacted in logs"
else
  echo "⚠️  Warning: Could not verify credential redaction"
fi
```

**Expected Output:**
```
✅ Credentials properly redacted in logs
```

**✅ Pass Condition:**
- [ ] Actual password (staging_test_password_12345) NOT present in logs
- [ ] Logs contain `***REDACTED***` marker instead
- [ ] No error checking for credentials

**❌ Fail Condition:**
- [ ] Actual password appears in logs → Critical security issue; see remediation in Section 5

---

### Step 1.9: Wait for Application to Start

**Command:**
```bash
# Method 1: Poll HTTP endpoint
timeout 180 bash -c '
while ! curl -sf http://localhost:8080/starexec/ >/dev/null 2>&1; do
  echo "⏳ Waiting for application to respond..."
  sleep 3
done
echo "✅ Application is responding!"
'

# Method 2: Check container status
docker compose ps starexec
```

**Expected Output (Method 1):**
```
⏳ Waiting for application to respond...
⏳ Waiting for application to respond...
✅ Application is responding!
```

**Expected Output (Method 2):**
```
NAME             COMMAND                    SERVICE   STATUS    PORTS
starexec-app     "/opt/tomcat/bin/cata..."  starexec  running   8080/tcp
```

**✅ Pass Condition:**
- [ ] HTTP endpoint returns 200 or 302 status
- [ ] Application responds within 180 seconds
- [ ] Container status shows "running"
- [ ] No connection refused errors

**❌ Fail Condition:**
- [ ] HTTP 500+ errors → Check with `docker compose logs starexec`
- [ ] Timeout after 180 seconds → Application may not have started
- [ ] Connection refused → Port 8080 may be in use

---

### Step 1.10: Verify Application Health

**Command:**
```bash
# Test main application endpoint
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/starexec/)
echo "HTTP Status: $HTTP_STATUS"

if [ "$HTTP_STATUS" = "200" ] || [ "$HTTP_STATUS" = "302" ]; then
  echo "✅ Application health check passed"
else
  echo "❌ Application health check failed"
  exit 1
fi

# Optional: Fetch and display first 100 chars of response
echo ""
echo "Response preview:"
curl -s http://localhost:8080/starexec/ | head -c 200
echo ""
```

**Expected Output:**
```
HTTP Status: 200
✅ Application health check passed

Response preview:
<!DOCTYPE html>
<html>
<head>
    <title>StarExec</title>
    ...
```

**✅ Pass Condition:**
- [ ] HTTP status is 200 (success) or 302 (redirect, acceptable)
- [ ] Response contains HTML or valid content
- [ ] No 4xx or 5xx errors

**❌ Fail Condition:**
- [ ] HTTP 500 errors → Database not initialized; check migration logs
- [ ] HTTP 503 Service Unavailable → Dependencies not ready
- [ ] Connection refused → Application crashed after startup

---

### Step 1.11: Verify Database Migrations Applied

**Command:**
```bash
# Check that Flyway schema history table exists and has records
docker compose exec -T postgres psql -U starexec -d starexec -c \
  "SELECT version, description, success FROM flyway_schema_history LIMIT 5;"

# Count total migrations
docker compose exec -T postgres psql -U starexec -d starexec -c \
  "SELECT COUNT(*) as total_migrations FROM flyway_schema_history WHERE success = true;"
```

**Expected Output:**
```
 version | description                    | success
---------+--------------------------------+---------
   1    | baseline schema                | t
   2    | add user table                 | t
   3    | add job table                  | t
   4    | add solver table               | t
   5    | add benchmark table            | t
(5 rows)

 total_migrations
-----------------
        23
(1 row)
```

**✅ Pass Condition:**
- [ ] `flyway_schema_history` table exists
- [ ] Multiple migration records present (>10)
- [ ] All migrations show `success = t` (true)
- [ ] Total count reasonable for schema (>20)

**❌ Fail Condition:**
- [ ] Table does not exist → Migrations never ran; check Step 1.7
- [ ] Zero successful migrations → Flyway not executing properly
- [ ] Some migrations show `success = f` → Flyway validation failed

---

### Step 1.12: Verify Database Schema Initialized

**Command:**
```bash
# Check that starexec schema exists
docker compose exec -T postgres psql -U starexec -d starexec -c \
  "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'starexec';"

# List tables in starexec schema
docker compose exec -T postgres psql -U starexec -d starexec -c \
  "SELECT COUNT(*) as table_count FROM information_schema.tables WHERE table_schema = 'starexec';"
```

**Expected Output:**
```
 schema_name
-----------
 starexec
(1 row)

 table_count
-----------
      45
(1 row)
```

**✅ Pass Condition:**
- [ ] Schema `starexec` exists
- [ ] Multiple tables present (>20)
- [ ] Table count matches expectations

**❌ Fail Condition:**
- [ ] Schema does not exist → Migrations failed; check logs
- [ ] No tables or very few tables → Schema initialization incomplete

---

### Step 1.13: Run Integration Test Suite

**Command:**
```bash
# Test 1: Basic application connectivity
echo "Test 1: Basic connectivity..."
if curl -sf http://localhost:8080/starexec/ >/dev/null 2>&1; then
  echo "✅ Test 1 PASSED: Application responds to requests"
else
  echo "❌ Test 1 FAILED: Application not responding"
  exit 1
fi

# Test 2: Database connectivity
echo ""
echo "Test 2: Database connectivity..."
if docker compose exec -T postgres psql -U starexec -d starexec -c "SELECT 1;" >/dev/null 2>&1; then
  echo "✅ Test 2 PASSED: Database is accessible"
else
  echo "❌ Test 2 FAILED: Database not accessible"
  exit 1
fi

# Test 3: All services running
echo ""
echo "Test 3: Service status..."
RUNNING=$(docker compose ps --format "{{.State}}" | grep "running\|exited" | wc -l)
TOTAL=$(docker compose ps --format "{{.State}}" | wc -l)
if [ "$RUNNING" -ge "$TOTAL" ]; then
  echo "✅ Test 3 PASSED: All services running or completed"
else
  echo "⚠️  Test 3 WARNING: Some services not in expected state"
  docker compose ps
fi

echo ""
echo "✅ All integration tests completed"
```

**Expected Output:**
```
Test 1: Basic connectivity...
✅ Test 1 PASSED: Application responds to requests

Test 2: Database connectivity...
✅ Test 2 PASSED: Database is accessible

Test 3: Service status...
✅ Test 3 PASSED: All services running or completed

✅ All integration tests completed
```

**✅ Pass Condition:**
- [ ] All three tests pass
- [ ] No critical errors
- [ ] Application and database responsive

**❌ Fail Condition:**
- [ ] Any test fails → Diagnose with appropriate logs from earlier steps

---

### Step 1.14: Collect Baseline Metrics

**Command:**
```bash
echo "=== Docker Compose Resource Usage ==="
docker stats --no-stream

echo ""
echo "=== Container Sizes ==="
docker compose ps --format "table {{.Names}}\t{{.Size}}"

echo ""
echo "=== Volume Usage ==="
docker volume ls | grep starexec
```

**Expected Output:**
```
CONTAINER ID     NAME                   CPU %  MEM USAGE / LIMIT
abc1234def56     starexec-postgres      0.1%   250MiB / 2GiB
def5678ghi901    starexec-migrations    0.0%   0B / 2GiB
ghi9012jkl345    starexec-app           0.8%   450MiB / 2GiB
```

**✅ Pass Condition:**
- [ ] All containers show reasonable resource usage
- [ ] No container using >80% of allocated memory
- [ ] CPU usage reasonable

**Note:** These are baseline metrics. Keep them for comparison during load testing.

---

### Step 1.15: Docker Compose Summary

**Completion Checklist:**

- [ ] Step 1.1: Docker images verified
- [ ] Step 1.2: docker-compose.yml validated
- [ ] Step 1.3: Environment variables set
- [ ] Step 1.4: Previous deployments cleaned up
- [ ] Step 1.5: Services started successfully
- [ ] Step 1.6: PostgreSQL healthy
- [ ] Step 1.7: Migrations completed (exit code 0)
- [ ] Step 1.8: ✅ Credentials properly redacted
- [ ] Step 1.9: Application responding
- [ ] Step 1.10: Application health check passed
- [ ] Step 1.11: Migrations applied (>20 in history)
- [ ] Step 1.12: Database schema initialized (>20 tables)
- [ ] Step 1.13: Integration tests passed
- [ ] Step 1.14: Baseline metrics collected

**Result:** ✅ **PASS** - Docker Compose deployment validated

If all steps pass, proceed to Section 2 or skip to Section 4 (Summary).

---

## Section 2: Podman Validation (Optional for Podman-Based Deployments)

> **Target Environment:** Podman with Docker Compose or DooD (Docker-outside-of-Docker)  
> **Expected Duration:** 15 minutes  
> **Prerequisite:** Complete Section 1 (Docker Compose) first

### Step 2.1: Verify Podman Installation

**Command:**
```bash
podman --version
podman info | head -20
```

**Expected Output:**
```
podman version 4.4.0
[system info omitted]
```

**✅ Pass Condition:**
- [ ] Podman version 4.0+
- [ ] Podman info shows healthy system

---

### Step 2.2: Switch to Podman Backend

**Command:**
```bash
# For Docker Compose with Podman backend
export DOCKER_HOST=unix:///run/podman/podman.sock

# Verify Docker CLI can talk to Podman
docker --version
docker ps
```

**Expected Output:**
```
Docker version 20.10.x, build [hash]
CONTAINER ID   IMAGE    COMMAND   CREATED   STATUS   PORTS   NAMES
(empty or list of podman containers)
```

**✅ Pass Condition:**
- [ ] Docker CLI connects to Podman socket
- [ ] No permission errors
- [ ] `docker ps` works without sudo (or with sudo as needed)

---

### Step 2.3: Deploy with Podman Backend

**Command:**
```bash
export DOCKER_HOST=unix:///run/podman/podman.sock
cd /path/to/starexec

# Clean up previous deployments
docker compose down -v

# Deploy with Podman backend and DooD compose override (if using DooD)
docker compose -f docker-compose.yml -f docker-compose.podman.yml up -d --build
```

**Expected Output:**
```
[+] Building 0.0s (0/0)
[+] Running 4/4
 ✔ Network starexec_starexec_network  Created
 ✔ Container starexec-postgres        Started
 ✔ Container starexec-migrations      Started
 ✔ Container starexec-app             Started
```

**✅ Pass Condition:**
- [ ] All services start successfully
- [ ] No permission or socket errors
- [ ] Services are visible with `docker ps`

**❌ Fail Condition:**
- [ ] Socket permission denied → Run `docker ps` with appropriate sudo/usermod
- [ ] Services don't start → Check Podman logs with `docker compose logs`

---

### Step 2.4: Validate Podman Deployment

**Command (Reuse Steps 1.6 through 1.14):**
Follow the exact same validation steps as Docker Compose:
- Wait for PostgreSQL healthy (Step 1.6)
- Monitor migrations (Step 1.7)
- Verify credential redaction (Step 1.8)
- Wait for application (Step 1.9)
- Verify health (Step 1.10)
- Check migrations in DB (Step 1.11)
- Verify schema (Step 1.12)
- Run integration tests (Step 1.13)
- Collect metrics (Step 1.14)

All commands are identical; just use `docker` instead of `docker compose`.

---

### Step 2.5: Podman Summary

**Completion Checklist:**

- [ ] Step 2.1: Podman installed and functional
- [ ] Step 2.2: Docker CLI configured for Podman backend
- [ ] Step 2.3: Services deployed successfully
- [ ] Step 2.4: All validations passed (same as Docker Compose)

**Result:** ✅ **PASS** - Podman deployment validated

---

## Section 3: Kubernetes/Helm Validation

> **Target Environment:** Kubernetes staging cluster  
> **Expected Duration:** 25 minutes  
> **Prerequisite:** Access to staging K8s cluster, Helm 3+, kubectl

### Step 3.1: Verify Kubernetes Cluster Access

**Command:**
```bash
kubectl cluster-info
kubectl get nodes
```

**Expected Output:**
```
Kubernetes control plane is running at https://[ip]:6443
CoreDNS is running at https://[ip]:6443/api/v1/namespaces/kube-system/services/coredns:dns/proxy

NAME                   STATUS   ROLES           AGE     VERSION
node-1                 Ready    control-plane   30d     v1.28.0
node-2                 Ready    worker          30d     v1.28.0
```

**✅ Pass Condition:**
- [ ] Cluster info shows control plane
- [ ] All nodes show "Ready" status
- [ ] Cluster version 1.26+

---

### Step 3.2: Create Staging Namespace

**Command:**
```bash
# Create namespace
kubectl create namespace starexec-staging || true

# Verify it exists
kubectl get namespace starexec-staging
```

**Expected Output:**
```
NAME                  STATUS   AGE
starexec-staging      Active   10s
```

**✅ Pass Condition:**
- [ ] Namespace created or already exists
- [ ] Status is "Active"

---

### Step 3.3: Create Database Credentials Secret

**Command:**
```bash
# Create K8s secret for database credentials
kubectl create secret generic starexec-db-credentials \
  --from-literal=password="staging_test_k8s_password_12345" \
  -n starexec-staging \
  --dry-run=client -o yaml | kubectl apply -f -

# Verify secret exists
kubectl get secret starexec-db-credentials -n starexec-staging
```

**Expected Output:**
```
NAME                         TYPE     DATA   AGE
starexec-db-credentials      Opaque   1      5s
```

**✅ Pass Condition:**
- [ ] Secret created successfully
- [ ] Secret shows type "Opaque"
- [ ] Data count is 1 (the password)

---

### Step 3.4: Deploy PostgreSQL StatefulSet

**Command:**
```bash
# Deploy PostgreSQL (using provided Helm chart or manifest)
# This is a prerequisite for the StarExec application

# Example using Bitnami Helm chart (adjust as needed)
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

helm install postgres-starexec bitnami/postgresql \
  --namespace starexec-staging \
  --values - <<'EOF'
auth:
  username: starexec
  password: staging_test_k8s_password_12345
  database: starexec
primary:
  persistence:
    enabled: true
    size: 10Gi
EOF

# Wait for PostgreSQL to be ready
kubectl wait --for=condition=ready pod \
  -l app.kubernetes.io/name=postgresql \
  -n starexec-staging \
  --timeout=300s
```

**Expected Output:**
```
Release name: postgres-starexec
STATUS: deployed
[... pod conditions ...]
condition met
```

**✅ Pass Condition:**
- [ ] Helm release deployed successfully
- [ ] PostgreSQL pod shows "Running" status
- [ ] Database is accessible from within cluster

---

### Step 3.5: Deploy StarExec with Helm

**Command:**
```bash
cd /path/to/starexec

# Deploy StarExec Helm chart with staging values
helm install starexec-staging ./charts/starexec \
  --namespace starexec-staging \
  --values charts/starexec/values-kubernetes.yaml \
  --set image.tag=latest \
  --set database.host=postgres-starexec-postgresql.starexec-staging.svc.cluster.local \
  --set database.password="staging_test_k8s_password_12345"

# Verify deployment
kubectl get all -n starexec-staging
```

**Expected Output:**
```
NAME                                          READY   STATUS    RESTARTS   AGE
pod/starexec-migrate-abc1234-def56            0/1     Pending   0          5s
pod/starexec-app-def5678-ghi90                0/1     Pending   0          5s

NAME                           READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/starexec-app   0/1     1            0           5s

NAME                      COMPLETIONS   DURATION   AGE
job.batch/starexec-migrate 0/1           5s         5s
```

**✅ Pass Condition:**
- [ ] Helm release installed successfully
- [ ] Migrate job created
- [ ] App deployment created
- [ ] Services and ConfigMaps created

---

### Step 3.6: Monitor Migration Job

**Command:**
```bash
# Watch migration job progress (timeout 300 seconds)
timeout 300 bash -c '
while [ $(kubectl get job starexec-migrate -n starexec-staging --no-headers 2>/dev/null | awk "{print \$2}") != "1/1" ]; do
  echo "[$(date +%H:%M:%S)] Waiting for migration job to complete..."
  kubectl get job starexec-migrate -n starexec-staging
  sleep 5
done
echo "✅ Migration job completed!"
'

# View migration job logs
kubectl logs -n starexec-staging -l app=starexec,component=migrations --tail=100
```

**Expected Output:**
```
[14:35:20] Waiting for migration job to complete...
NAME               COMPLETIONS   DURATION   AGE
starexec-migrate   0/1           10s        10s
[14:35:25] Waiting for migration job to complete...
starexec-migrate   1/1           15s        15s
✅ Migration job completed!

[MIGRATION][CONFIG] Loading database configuration...
[MIGRATION][VERIFY] ✅ Migration directory verified: 23 SQL files found
[MIGRATION][SUCCESS] ✅ Migration execution completed
```

**✅ Pass Condition:**
- [ ] Job shows "1/1" completions
- [ ] Duration is reasonable (30-120 seconds)
- [ ] Logs show migration success message
- [ ] No error messages in logs

**❌ Fail Condition:**
- [ ] Job stuck in "0/1" completions → Check pod logs for errors
- [ ] Job status shows "Error" or "BackoffLimitExceeded" → See Step 3.9

---

### Step 3.7: Verify Credential Redaction (Kubernetes)

**Command:**
```bash
# Get full migration pod name
MIGRATE_POD=$(kubectl get pods -n starexec-staging -l app=starexec,component=migrations -o jsonpath='{.items[0].metadata.name}')

# Check logs for actual password
LOGS=$(kubectl logs -n starexec-staging "$MIGRATE_POD")

# Security check
if echo "$LOGS" | grep -i "staging_test_k8s_password_12345"; then
  echo "❌ SECURITY ISSUE: Password appears unredacted in migration logs!"
  echo "This is a critical vulnerability."
  exit 1
fi

# Verify redacted marker
if echo "$LOGS" | grep "\*\*\*REDACTED\*\*\*"; then
  echo "✅ Credentials properly redacted in Kubernetes logs"
else
  echo "⚠️  Warning: Could not verify credential redaction"
fi
```

**Expected Output:**
```
✅ Credentials properly redacted in Kubernetes logs
```

**✅ Pass Condition:**
- [ ] Actual password NOT in logs
- [ ] `***REDACTED***` marker present instead

**❌ Fail Condition:**
- [ ] Password visible → Critical security issue; escalate immediately

---

### Step 3.8: Wait for StarExec Application Pod

**Command:**
```bash
# Wait for application pod to be ready
timeout 300 kubectl wait --for=condition=ready pod \
  -l app=starexec,component=app \
  -n starexec-staging \
  --timeout=300s

# Verify deployment
kubectl get deployment starexec-app -n starexec-staging
kubectl get pods -n starexec-staging -l app=starexec,component=app
```

**Expected Output:**
```
pod/starexec-app-def5678-ghi90 condition met

NAME            READY   UP-TO-DATE   AVAILABLE   AGE
starexec-app    1/1     1            1           2m

NAME                          READY   STATUS    RESTARTS   AGE
starexec-app-def5678-ghi90    1/1     Running   0          2m
```

**✅ Pass Condition:**
- [ ] Pod reaches "Ready" state
- [ ] Deployment shows "1/1" available
- [ ] No error or CrashLoopBackOff status

**❌ Fail Condition:**
- [ ] Pod stuck in "Pending" → Check resource requests
- [ ] CrashLoopBackOff → Check logs with `kubectl logs <pod>`

---

### Step 3.9: Verify Application Health in Kubernetes

**Command:**
```bash
# Port-forward to access application
kubectl port-forward -n starexec-staging svc/starexec 8080:8080 &
PF_PID=$!

sleep 2

# Test application
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/starexec/)

echo "HTTP Status: $HTTP_STATUS"

if [ "$HTTP_STATUS" = "200" ] || [ "$HTTP_STATUS" = "302" ]; then
  echo "✅ Application health check passed"
else
  echo "❌ Application health check failed"
fi

# Cleanup port-forward
kill $PF_PID 2>/dev/null || true
```

**Expected Output:**
```
Forwarding from 127.0.0.1:8080 -> 8080
HTTP Status: 200
✅ Application health check passed
```

**✅ Pass Condition:**
- [ ] Port-forward establishes successfully
- [ ] HTTP status 200 or 302
- [ ] Application responds to requests

---

### Step 3.10: Verify Database in Kubernetes

**Command:**
```bash
# Get PostgreSQL pod name
PG_POD=$(kubectl get pods -n starexec-staging -l app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].metadata.name}')

# Check database connectivity
kubectl exec -n starexec-staging "$PG_POD" -- \
  psql -U starexec -d starexec -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;"
```

**Expected Output:**
```
 count
-------
    23
(1 row)
```

**✅ Pass Condition:**
- [ ] Command executes without error
- [ ] Migration count >10
- [ ] All migrations show success = true

---

### Step 3.11: Kubernetes Integration Tests

**Command:**
```bash
# Test 1: Check all resources created
echo "Test 1: Resource creation..."
RESOURCES=$(kubectl get all,cm,secret -n starexec-staging --no-headers | wc -l)
if [ "$RESOURCES" -gt 10 ]; then
  echo "✅ Test 1 PASSED: Expected Kubernetes resources created"
else
  echo "❌ Test 1 FAILED: Missing expected resources"
  exit 1
fi

# Test 2: All pods running or completed
echo ""
echo "Test 2: Pod status..."
kubectl get pods -n starexec-staging --no-headers | awk '{print $3}' | sort | uniq -c
RUNNING=$(kubectl get pods -n starexec-staging --field-selector=status.phase=Running --no-headers | wc -l)
if [ "$RUNNING" -gt 0 ]; then
  echo "✅ Test 2 PASSED: Application pods running"
else
  echo "❌ Test 2 FAILED: No running pods"
  exit 1
fi

# Test 3: Services responding
echo ""
echo "Test 3: Service availability..."
kubectl port-forward -n starexec-staging svc/starexec 8080:8080 >/dev/null 2>&1 &
PF_PID=$!
sleep 2

if curl -sf http://localhost:8080/starexec/ >/dev/null 2>&1; then
  echo "✅ Test 3 PASSED: Application service responding"
else
  echo "❌ Test 3 FAILED: Application service not responding"
fi

kill $PF_PID 2>/dev/null || true

echo ""
echo "✅ All Kubernetes integration tests completed"
```

**Expected Output:**
```
Test 1: Resource creation...
✅ Test 1 PASSED: Expected Kubernetes resources created

Test 2: Pod status...
      1 Completed
      1 Running
✅ Test 2 PASSED: Application pods running

Test 3: Service availability...
✅ Test 3 PASSED: Application service responding

✅ All Kubernetes integration tests completed
```

---

### Step 3.12: Collect Kubernetes Metrics

**Command:**
```bash
echo "=== Node Resources ==="
kubectl top nodes -n starexec-staging 2>/dev/null || echo "(metrics server not available)"

echo ""
echo "=== Pod Resources ==="
kubectl top pods -n starexec-staging 2>/dev/null || echo "(metrics server not available)"

echo ""
echo "=== Persistent Volumes ==="
kubectl get pv -n starexec-staging

echo ""
echo "=== Storage Capacity ==="
kubectl get pvc -n starexec-staging
```

**Expected Output:**
```
=== Node Resources ===
NAME      CPU(cores)   MEMORY(Mi)
node-1    450m         2000Mi
node-2    380m         1800Mi

=== Pod Resources ===
NAME                          CPU(m)   MEMORY(Mi)
starexec-migrate-abc1234      100m     256Mi
starexec-app-def5678          200m     512Mi
```

---

### Step 3.13: Kubernetes Summary

**Completion Checklist:**

- [ ] Step 3.1: Cluster access verified
- [ ] Step 3.2: Namespace created
- [ ] Step 3.3: Database credentials secret created
- [ ] Step 3.4: PostgreSQL deployed
- [ ] Step 3.5: StarExec deployed with Helm
- [ ] Step 3.6: Migration job completed (1/1)
- [ ] Step 3.7: ✅ Credentials properly redacted
- [ ] Step 3.8: Application pod ready
- [ ] Step 3.9: Application responding
- [ ] Step 3.10: Database initialized
- [ ] Step 3.11: Kubernetes integration tests passed
- [ ] Step 3.12: Metrics collected

**Result:** ✅ **PASS** - Kubernetes deployment validated

---

## Section 4: Security Verification

### Step 4.1: Credential Exposure Audit

**Purpose:** Ensure database passwords are never exposed in logs, processes, or configuration files.

**Command:**
```bash
# Check Docker Compose logs (all services)
echo "=== Checking Docker Compose Logs for Credential Leakage ==="
docker compose logs 2>/dev/null | grep -iE "(password|passwd|pwd|secret)" | grep -v "REDACTED" || echo "✅ No credentials in Docker logs"

# Check environment variables (should not show password)
echo ""
echo "=== Checking Process Environment ==="
docker compose ps --format "{{.Names}}" | while read container; do
  docker inspect "$container" -f '{{json .Config.Env}}' | grep -i password || true
done | grep -v "REDACTED" || echo "✅ Passwords not exposed in process environment"

# Check running processes
echo ""
echo "=== Checking Process List (ps) ==="
ps aux | grep -i "password\|STAREXEC_DB_PASSWORD" | grep -v grep || echo "✅ Passwords not in process list"
```

**✅ Pass Condition:**
- [ ] No actual passwords in Docker logs
- [ ] No passwords in process list
- [ ] Only `***REDACTED***` markers appear

---

### Step 4.2: Configuration File Audit

**Command:**
```bash
# Check that credentials are NOT in docker-compose.yml
echo "=== Checking docker-compose.yml for Hardcoded Credentials ==="
grep -E "STAREXEC_DB_PASSWORD.*=.*[^$]" docker-compose.yml || echo "✅ No hardcoded passwords in docker-compose.yml"

# Check .env file permissions
echo ""
echo "=== Checking .env File Permissions ==="
if [ -f .env ]; then
  ls -la .env
  if [ $(stat -f%OLp .env 2>/dev/null || stat -c%a .env) = "600" ]; then
    echo "✅ .env has correct permissions (600)"
  else
    echo "⚠️  .env should have permissions 600"
  fi
else
  echo "⚠️  .env file not found"
fi
```

**✅ Pass Condition:**
- [ ] No hardcoded passwords in docker-compose.yml
- [ ] .env file exists with 600 permissions
- [ ] All credentials sourced from environment variables

---

### Step 4.3: Image Security Scan

**Command:**
```bash
# Scan Docker image for vulnerabilities (requires Trivy or similar)
which trivy >/dev/null 2>&1 || {
  echo "Installing Trivy..."
  curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin
}

echo "=== Scanning StarExec Docker Image ==="
trivy image --severity CRITICAL,HIGH ghcr.io/starexecmiami/starexec:latest

# Summary
echo ""
echo "✅ Image scanning completed. Review results above."
```

**✅ Pass Condition:**
- [ ] No CRITICAL severity vulnerabilities
- [ ] HIGH vulnerabilities documented and understood
- [ ] Base image is up-to-date

---

## Section 5: Troubleshooting Guide

### Problem: Migration Directory Not Found

**Error Message:**
```
[EmbeddedFlyway] Migration directory not found: /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration
```

**Root Causes:**
1. WAR file doesn't include migration files
2. WAR extraction didn't complete
3. Container image built with old/stale code

**Diagnosis:**
```bash
# Check if migrations are in WAR file
unzip -l starexec-app/target/starexec.war | grep -c "WEB-INF/classes/db/migration"

# Check if directory exists in running container
docker compose exec starexec ls -la /opt/tomcat/webapps/starexec/WEB-INF/classes/db/migration/
```

**Resolution:**
1. Rebuild WAR: `cd starexec-app && mvn clean package`
2. Rebuild image: `docker compose up -d --build`
3. Use immutable image tag (not `:latest`)

---

### Problem: Credentials Exposed in Logs

**Error Message:**
```
Password: staging_test_password_12345
```

**Root Cause:** Credentials passed via system properties instead of environment variables

**Resolution:**
1. Update EmbeddedFlywayLauncher to read from `System.getenv()` only
2. Remove any `-Dflyway.*` flags from migration commands
3. Rebuild application with fixed code
4. Redeploy with new image

---

### Problem: Application Fails to Start After Migrations

**Error Message:**
```
HTTP 500: Internal Server Error
```

**Root Causes:**
1. Migrations executed but database state is corrupted
2. Application waiting for migrations but migration job never completed
3. Database connection pool exhausted

**Diagnosis:**
```bash
# Check migration status
docker compose logs migrations | tail -20

# Check application logs
docker compose logs starexec | tail -50

# Check database connectivity
docker compose exec starexec psql -U starexec -d starexec -c "SELECT 1;"
```

**Resolution:**
1. Review migration logs for specific errors
2. If migrations failed, fix the migration script and rebuild
3. If migrations succeeded but app fails, check application logs
4. May need to clear database and restart: `docker compose down -v && docker compose up -d`

---

### Problem: Migrations Timeout or Never Complete

**Error Message:**
```
Timeout waiting for migration job to complete
```

**Root Causes:**
1. Database unreachable or unhealthy
2. Large migration taking longer than expected
3. Migration script has infinite loop or deadlock

**Diagnosis:**
```bash
# Check database health
docker compose exec postgres pg_isready -U starexec

# Check migration logs in real-time
docker compose logs -f migrations

# Monitor database activity
docker compose exec postgres psql -U starexec -d starexec -c "SELECT * FROM pg_stat_activity;"
```

**Resolution:**
1. Increase timeout in docker-compose.yml (activeDeadlineSeconds)
2. Check for long-running queries in database
3. Verify network connectivity between services
4. If migration script is hanging, interrupt and review script

---

## Section 6: Success Criteria & Sign-Off

### All Validation Steps Complete?

Review the completion checklists:

- [ ] Section 1: Docker Compose Validation (15 checks)
- [ ] Section 2: Podman Validation (5 checks) - optional
- [ ] Section 3: Kubernetes Validation (13 checks) - if using K8s
- [ ] Section 4: Security Verification (3 checks)

### Key Success Indicators

✅ **Must Pass:**
- [ ] All migrations execute successfully (exit code 0)
- [ ] Database credentials properly redacted in all logs
- [ ] Application responds to HTTP requests
- [ ] Database schema initialized with >20 tables
- [ ] No errors in migration or application logs
- [ ] All integration tests pass

⚠️ **Should Pass:**
- [ ] Resource usage reasonable (<80% memory)
- [ ] Service startup times acceptable (<120 seconds)
- [ ] No warnings in logs

---

## Signoff

**Validated By:** _____________________  
**Date:** _____________________  
**Environment:** Docker Compose / Podman / Kubernetes (circle one)  
**Image Version:** _____________________  
**Database:** _____________________  
**Notes:** _______________________________________________________________

---

## Next Steps After Validation

1. **If validation passes:**
   - ✅ Proceed to production deployment
   - ✅ Use immutable image tag (not `:latest`)
   - ✅ Monitor application health post-deployment
   - ✅ Keep these validation results for audit

2. **If validation fails:**
   - ❌ Do NOT deploy to production
   - ❌ Investigate root cause using Section 5 troubleshooting
   - ❌ Fix issue and rebuild/redeploy to staging
   - ❌ Re-run full validation before retrying production

---

## Support & Escalation

**Questions About Validation?**
- See OPERATIONAL_RUNBOOK.md (comprehensive reference)
- See ARCHITECTURE_IMPROVEMENTS.md (design decisions)
- Contact: <operations-team-contact>

**Critical Issues?**
- Credential exposure: Escalate immediately (Security team)
- Database corruption: Contact DBA team
- Kubernetes issues: Contact DevOps team

**Document Version:** 1.0  
**Last Updated:** December 2024  
**Next Review:** January 2025