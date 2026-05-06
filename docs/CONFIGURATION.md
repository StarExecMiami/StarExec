# Configuration Reference

Complete guide to configuring StarExec.

## Configuration Layers

StarExec uses a layered configuration system:

1. **Java defaults** (`EnvironmentConfig.java`) - Fallback values
2. **Helm values** (`values.yaml`, `values-ENV.yaml`) - Deployment-specific
3. **Environment variables** - Runtime overrides (highest priority)
4. **Kubernetes secrets** - Sensitive data (production)

## Quick Configuration

### Minimal Setup (Development)

```bash
export STAREXEC_DB_HOST=localhost
export STAREXEC_DB_PASSWORD=admin  # Change for production!
```

The following have defaults but can be overridden:

```bash
export STAREXEC_DB_PORT=5432
export STAREXEC_DB_NAME=starexec
export STAREXEC_DB_USER=starexec
```

### Production Setup

**Never use default passwords in production!**

Use environment variables or Kubernetes secrets:

```bash
export STAREXEC_DB_PASSWORD=$(cat /run/secrets/db-password)
export STAREXEC_DB_PASSWORD_FILE=/run/secrets/starexec-db-password
```

## Environment Variables Reference

### Database Configuration

| Variable | Default | Required | Example | Notes |
|----------|---------|----------|---------|-------|
| `STAREXEC_DB_HOST` | `localhost` | Yes | `db.local` | Database hostname |
| `STAREXEC_DB_PORT` | `5432` | No | `5432` | PostgreSQL port |
| `STAREXEC_DB_NAME` | `starexec` | No | `starexec` | Database name |
| `STAREXEC_DB_USER` | `starexec` | No | `starexec` | Database username |
| `STAREXEC_DB_PASSWORD` | *(empty)* | **Yes** | `s3cr3t` | ⚠️ **Sensitive** |
| `STAREXEC_DB_PASSWORD_FILE` | *(empty)* | No | `/run/secrets/db-pwd` | Path to password file (preferred) |

**Security Note:** Use `STAREXEC_DB_PASSWORD_FILE` in production to avoid exposing passwords in process listings.

### Cluster Compute Configuration

| Variable | Default | Required | Example | Notes |
|----------|---------|----------|---------|-------|
| `STAREXEC_CLUSTER_DB_USER` | `starexec` | No | `cluster_user` | Cluster database user |
| `STAREXEC_CLUSTER_DB_PASSWORD` | *(empty)* | No | `cluster_pass` | ⚠️ **Sensitive** |

### Email Configuration

| Variable | Default | Required | Example | Notes |
|----------|---------|----------|---------|-------|
| `STAREXEC_EMAIL_SMTP` | `localhost` | No | `smtp.example.org` | SMTP server |
| `STAREXEC_EMAIL_PORT` | `25` | No | `587` | SMTP port |
| `STAREXEC_EMAIL_USER` | *(empty)* | No | `mailer@example.org` | ⚠️ **Sensitive** |
| `STAREXEC_EMAIL_PASSWORD` | *(empty)* | No | `...` | ⚠️ **Sensitive** |

### Backend / System Configuration

| Variable | Default | Required | Example | Notes |
|----------|---------|----------|---------|-------|
| `STAREXEC_BACKEND_TYPE` | `local` | No | `podman` | Backend: local, podman, kubernetes, sge, oar. Note: Makefile `make start` uses Podman deployment by default. |
| `STAREXEC_DATA_DIR` | `/tmp/starexec/data` | No | `/var/lib/starexec/data` | Data directory path |

### Kubernetes Backend Configuration

These variables configure the KubernetesNativeBackend when `STAREXEC_BACKEND_TYPE=kubernetes`.

| Variable | Default | Required | Example | Notes |
|----------|---------|----------|---------|-------|
| `STAREXEC_K8S_NAMESPACE` | `starexec` | No | `starexec` | Kubernetes namespace for job execution. Must match the namespace that contains the shared data PVC and job ServiceAccount. |
| `STAREXEC_K8S_JOB_IMAGE` | `starexec/job-runner:latest` | No | `ghcr.io/starexecmiami/starexec-job-runner:latest` | Container image for job execution |
| `STAREXEC_K8S_DATA_PVC` | `starexec-data` | No | `starexec-data` | PVC name for shared data volume |
| `STAREXEC_K8S_SERVICE_ACCOUNT` | `starexec-job` | No | `starexec-job` | ServiceAccount for job pods |
| `STAREXEC_K8S_QUEUE_LABEL` | `starexec/queue` | No | `starexec/queue` | Label key for queue discovery and node grouping. Current Kubernetes-native job placement does not yet add queue-specific selectors. |
| `STAREXEC_K8S_MEMORY_LIMIT` | `2Gi` | No | `4Gi` | Memory limit per job pod |
| `STAREXEC_K8S_CPU_LIMIT` | `1` | No | `2` | CPU core limit per job pod |
| `STAREXEC_K8S_JOB_TTL_SECONDS` | `3600` | No | `7200` | Job cleanup TTL after completion |
| `STAREXEC_K8S_JOB_BACKOFF_LIMIT` | `0` | No | `3` | Kubernetes job retry limit |
| `STAREXEC_K8S_MAX_CONCURRENT_JOBS` | `50` | No | `100` | Soft cap on concurrently tracked Kubernetes jobs. New submissions above the cap are rejected until an existing job completes, is killed, or is reconciled away. |
| `STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS` | `300000` | No | `60000` | Periodic sweep interval for deleting managed Kubernetes Jobs whose DB pair rows disappeared while StarExec stayed online. Set to `0` to disable the sweep. |
| `STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU` | `true` | No | `false` | Enforce 1 job pair per physical CPU core for benchmark fidelity. Keep enabled for SAT/SMT competition or academic measurement workloads unless you have measured evidence that relaxing it preserves timing and memory reproducibility. |
| `STAREXEC_K8S_WORKER_SELECTOR_KEY` | `starexec.org/worker` | No | `starexec.org/worker` | Node selector key for worker nodes |
| `STAREXEC_K8S_WORKER_SELECTOR_VALUE` | `true` | No | `true` | Node selector value for worker nodes |

### Performance Tuning

> **Scientific benchmarking default**
>
> StarExec is frequently used for academic SAT/SMT experiments where timing,
> memory, and solver-behavior metrics must remain comparable across runs.
> For that reason, the recommended default is **one job pair per physical CPU
> core**, not per logical CPU. Avoid SMT / Hyper-Threading siblings when
> assigning cores, because shared L1/L2/L3 caches and shared execution
> resources can skew solver measurements. Throughput tuning must not override
> benchmark reproducibility unless you have workload-specific evidence that the
> results remain stable.

| Variable | Default | Description |
|----------|---------|-------------|
| `STAREXEC_LOCAL_CONCURRENCY` | `min(4, CPU_cores)` | Max parallel jobs (local backend) |
| `STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS` | `3600` | Job timeout (1 hour) |
| `STAREXEC_CONTAINER_DEFAULT_MEMORY_MB` | `4096` | Memory per container (4GB) |
| `STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT` | `1` | CPU cores per container |
| `STAREXEC_CONTAINER_EXITED_CLEANUP_AGE_SECONDS` | `86400` | Minimum age before sweeping an exited managed Podman container that was not processed by the normal completion monitor (`0` disables the sweep) |

### Live Log Streaming (SSE)

These settings tune the live pair log stream endpoint (`/services/jobs/pairs/{id}/log/stream`).

| Variable | Default | Recommended (prod, benchmark-priority) | Description |
|----------|---------|------------------------------------------|-------------|
| `STAREXEC_PAIR_LOG_STREAM_ENABLED` | `true` | `true` | Enable/disable live SSE pair log streaming |
| `STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE` | `100` | `30` | Max concurrent active live log streams |
| `STAREXEC_PAIR_LOG_STREAM_POLL_INTERVAL_MS` | `1000` | `2500` | Interval for checking newly appended log bytes |
| `STAREXEC_PAIR_LOG_STREAM_STATUS_POLL_INTERVAL_MS` | `5000` | `15000` | Interval for polling pair terminal status |
| `STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES` | `8192` | `8192` | Max bytes emitted per SSE chunk event |
| `STAREXEC_PAIR_LOG_STREAM_HEARTBEAT_SECONDS` | `15` | `25` | Heartbeat comment interval to keep connections alive |
| `STAREXEC_PAIR_LOG_STREAM_MAX_DURATION_SECONDS` | `1800` | `900` | Max lifetime for one stream connection |
| `STAREXEC_PAIR_LOG_STREAM_RETRY_AFTER_SECONDS` | `10` | `10` | Retry-After value when stream capacity is saturated |

**Operational guidance:**
- Lower `MAX_ACTIVE` first if benchmark throughput drops.
- Increase `POLL_INTERVAL_MS` and `STATUS_POLL_INTERVAL_MS` before increasing capacity.
- Keep `READ_CHUNK_BYTES` moderate to avoid bursty memory/network usage.

## Helm Values Configuration

### Structure

Helm values are organized by environment:

```
charts/starexec/
├── values.yaml           # Base defaults
├── values-dev.yaml       # Development overrides
├── values-ci.yaml        # CI/testing overrides
└── values-prod.yaml      # Production overrides
```

### Base Values Example

```yaml
# values-dev.yaml
image:
  repository: starexec
  tag: latest
  pullPolicy: Never

postgres:
  host: starexec-postgres
  port: 5432
  user: starexec
  database: starexec
  password: "starexec_dev_password"  # Override in production!

resources:
  limits:
    memory: 4Gi
    cpu: 2
  requests:
    memory: 2Gi
    cpu: 1

backend:
  type: podman
  dataDir: /app/data
```

### Production Values Example

```yaml
# values-prod.yaml
image:
  repository: ghcr.io/starexecmiami/starexec
  tag: v1.0.0
  pullPolicy: IfNotPresent

postgres:
  host: postgres.prod.svc.cluster.local
  # Password managed via Kubernetes secret
  existingSecret: starexec-postgres-secret

resources:
  limits:
    memory: 8Gi
    cpu: 4
  requests:
    memory: 4Gi
    cpu: 2

persistence:
  enabled: true
  storageClass: "fast-ssd"
  size: 100Gi

backend:
  type: kubernetes
  dataDir: /var/lib/starexec/data

# Kubernetes-specific configuration
kubernetes:
  enabled: true
  # Must match the Helm release namespace for the current backend design
  jobNamespace: "starexec"
  jobImage: "ghcr.io/starexecmiami/starexec-job-runner:latest"
  dataPvc:
    name: "starexec-data"
    storageClass: "fast-nfs"
    size: "100Gi"
  resources:
    limits:
      memory: "2Gi"
      cpu: "1"
    requests:
      memory: "512Mi"
      cpu: "500m"
```

## Configuration Validation

### Check Current Configuration

```bash
# View effective configuration
make config-show ENV=dev

# Render templates to see final values
make template ENV=prod
cat render.yaml
```

### Verify Database Connection

```bash
# Test connection from app container
make db-shell

# Check migration status
make db-status
```

### Common Validation Issues

**Issue:** Database connection refused
```bash
# Check host resolution
podman exec starexec-app ping -c 1 starexec-postgres

# Verify PostgreSQL is listening
podman exec starexec-postgres pg_isready
```

**Issue:** Password authentication failed
```bash
# Verify password is set correctly
echo $STAREXEC_DB_PASSWORD  # Should not be empty

# Check if using password file
cat $STAREXEC_DB_PASSWORD_FILE
```

## Environment-Specific Configuration

### Development (ENV=dev)

- Uses named volumes for persistence
- Default passwords acceptable
- Local container builds
- Minimal resource limits

```bash
make deploy-podman ENV=dev
```

### CI (ENV=ci)

- Ephemeral volumes
- Automated cleanup
- Fast startup/teardown
- Minimal logging

```bash
make deploy-podman ENV=ci
```

### Production (ENV=prod)

- **Requires secure passwords**
- Persistent volumes with backups
- Registry-pulled images
- Resource limits enforced
- Monitoring enabled

```bash
# Production deployment requires env-specific values + secure password
cp charts/starexec/values-podman.yaml charts/starexec/values-prod.yaml
# Edit values-prod.yaml for production limits, credentials, and socket path
export STAREXEC_DB_PASSWORD="$(generate-secure-password)"

# Live log streaming (benchmark-priority profile)
export STAREXEC_PAIR_LOG_STREAM_ENABLED=true
export STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE=30
export STAREXEC_PAIR_LOG_STREAM_POLL_INTERVAL_MS=2500
export STAREXEC_PAIR_LOG_STREAM_STATUS_POLL_INTERVAL_MS=15000
export STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES=8192
export STAREXEC_PAIR_LOG_STREAM_HEARTBEAT_SECONDS=25
export STAREXEC_PAIR_LOG_STREAM_MAX_DURATION_SECONDS=900
export STAREXEC_PAIR_LOG_STREAM_RETRY_AFTER_SECONDS=10

make deploy-podman ENV=prod
```

These values prioritize benchmark execution throughput over live log latency.

## Advanced Configuration

### Custom Helm Values

Create a custom values file:

```yaml
# my-custom-values.yaml
backend:
  type: kubernetes
  
postgres:
  host: external-db.company.com
  existingSecret: company-db-secret

resources:
  limits:
    memory: 16Gi
    cpu: 8

# SSE live log stream tuning (benchmark-priority profile)
env:
  STAREXEC_PAIR_LOG_STREAM_ENABLED: "true"
  STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE: "30"
  STAREXEC_PAIR_LOG_STREAM_POLL_INTERVAL_MS: "2500"
  STAREXEC_PAIR_LOG_STREAM_STATUS_POLL_INTERVAL_MS: "15000"
  STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES: "8192"
  STAREXEC_PAIR_LOG_STREAM_HEARTBEAT_SECONDS: "25"
  STAREXEC_PAIR_LOG_STREAM_MAX_DURATION_SECONDS: "900"
  STAREXEC_PAIR_LOG_STREAM_RETRY_AFTER_SECONDS: "10"
```

Deploy with custom values:

```bash
helm install starexec starexec/starexec \
  -f my-custom-values.yaml \
  -n starexec
```

### Using Kubernetes Secrets

Create secrets for sensitive data:

```bash
# Create database secret
kubectl create secret generic starexec-postgres-secret \
  --from-literal=password="$(generate-password)" \
  -n starexec

# Create SMTP secret
kubectl create secret generic starexec-smtp-secret \
  --from-literal=user="notifications@example.com" \
  --from-literal=password="$(generate-password)" \
  -n starexec
```

Reference in values:

```yaml
postgres:
  existingSecret: starexec-postgres-secret
  
email:
  existingSecret: starexec-smtp-secret
```

### Java System Properties

Advanced users can set JVM properties:

```bash
# Via environment variable
export JAVA_OPTS="-Xmx4g -XX:+UseG1GC"

# Via Helm values
javaOpts: "-Xmx4g -XX:+UseG1GC"
```

## Configuration Precedence

From highest to lowest priority:

1. **Environment variables** (runtime)
2. **Kubernetes secrets** (mounted)
3. **Helm values** (deployment-specific)
4. **Default values.yaml** (base defaults)
5. **Java EnvironmentConfig.java** (hardcoded fallbacks)

Example resolution for `STAREXEC_DB_HOST`:

```
Environment variable → Helm values → values.yaml → EnvironmentConfig.java → "localhost"
```

## Troubleshooting Configuration

### View Effective Configuration

```bash
# Show all configuration sources
make config-show ENV=dev

# Check rendered manifest
make template ENV=prod
less render.yaml
```

### Debug Database Connection

```bash
# Test from app container
podman exec starexec-app bash -c '
  psql "postgresql://$STAREXEC_DB_USER:$STAREXEC_DB_PASSWORD@$STAREXEC_DB_HOST:5432/$STAREXEC_DB_NAME" \
    -c "SELECT version();"
'
```

### Common Mistakes

1. **Forgetting to set `STAREXEC_DB_PASSWORD`**
   - Symptom: Authentication failed
   - Fix: `export STAREXEC_DB_PASSWORD=your-password`

2. **Wrong environment selected**
   - Symptom: Using dev config in production
   - Fix: Always specify `ENV=prod` explicitly

3. **Secrets not mounted**
   - Symptom: Empty password in container
   - Fix: Verify secret exists and is referenced correctly

## Next Steps

- **Backend configuration:** [BACKENDS.md](BACKENDS.md)
- **Security hardening:** [SECURITY.md](SECURITY.md)
- **Performance tuning:** [PERFORMANCE.md](PERFORMANCE.md)
