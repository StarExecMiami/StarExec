# Deployment Guide

Detailed deployment instructions for StarExec across all supported environments.

## Deployment Overview

StarExec supports three deployment methods:

| Method | Use Case | Complexity | Time |
|--------|----------|------------|------|
| **Docker Compose** | Development, CI | Low | ~10 min |
| **Podman + Makefile** | Production (single-node) | Medium | ~20 min |
| **Kubernetes + Helm** | Production (cluster) | High | ~30 min |

---

## Prerequisites

### All Deployments

- Git (to clone repository)
- 4GB RAM minimum (8GB recommended)
- 10GB disk space

### Docker Compose

- Docker Engine 20.10+ or Docker Desktop
- docker-compose v2.0+

### Podman + Makefile

- Linux (Ubuntu 22.04+, Debian 12+, RHEL 9+, Fedora 38+)
- Podman 4.0+ (rootless recommended)
- Required packages: `passt`, `catatonit`, `fuse-overlayfs`, `uidmap`; `yq` is optional (only needed by `make config-show`)
- cgroups v2 enabled

### Kubernetes + Helm

- Kubernetes 1.24+
- Helm 3.8+
- kubectl configured for your cluster
- StorageClass available for PersistentVolumeClaims

---

## Method 1: Docker Compose

### Quick Start

```bash
# Clone repository
git clone https://github.com/StarExecMiami/StarExec.git
cd StarExec
git checkout containerised

# Build and start
docker compose up --build

# Access at http://localhost:8080/starexec
```

### Configuration

Edit `docker-compose.yml` or use environment variables:

```bash
# Custom configuration
export STAREXEC_DB_PASSWORD=secure-password
docker compose up --build
```

### Stopping

```bash
# Stop and remove containers
docker compose down

# Stop and remove containers + volumes (⚠️ data loss)
docker compose down -v
```

### Production Notes

Docker Compose is **not recommended for production**. Use Podman or Kubernetes instead for:
- Better security (rootless execution)
- Volume management and backups
- Resource isolation

---

## Method 2: Podman + Makefile (Recommended for Production)

### System Preparation

#### Ubuntu/Debian

```bash
# Install required packages
sudo apt-get update
sudo apt-get install -y podman catatonit passt fuse-overlayfs yq

# Verify rootless mode
podman system info | grep rootless
# Should show: rootless: true

# Fix cgroup delegation (if needed)
make fix-cgroup-delegation
```

#### RHEL/Fedora

```bash
# Install required packages
sudo dnf install -y podman catatonit passt fuse-overlayfs yq

# Enable lingering for rootless systemd
loginctl enable-linger $USER
```

### Deployment

```bash
# Clone repository
git clone https://github.com/StarExecMiami/StarExec.git
cd StarExec
git checkout containerised

# Development deployment (default)
make start

# Production deployment (requires env-specific values + secure password)
cp charts/starexec/values-podman.yaml.example charts/starexec/values-podman-prod.yaml
# Edit values-podman-prod.yaml: set postgres.password and postgres.rootPassword
# to strong values, and the production limits
export STAREXEC_DB_PASSWORD='<the postgres.password you set>'
make deploy-podman ENV=prod VALS=charts/starexec/values-podman-prod.yaml
```

`VALS=` is required. `ENV=prod` alone selects the Kubernetes profile
`values-prod.yaml`, whose pod needs a database Secret this deployment does not
create and mounts other volumes than the `starexec-prod-*` volumes that job
containers and `make volumes-backup ENV=prod` use; `make deploy-podman` refuses
it. The database password is `postgres.password`. `make deploy-podman ENV=prod`
also stops while `STAREXEC_DB_PASSWORD` is unset or the development default,
and without Helm (`deploy-podman-direct`) the password comes from that variable
instead, so set both to the same value. `values-podman-prod.yaml` is gitignored
because it holds these passwords.

### Environment-Specific Configuration

| Environment | Command | Purpose |
|-------------|---------|---------|
| `dev` | `make deploy-podman ENV=dev` | Local development with defaults |
| `ci` | `make deploy-podman ENV=ci` | CI/testing with ephemeral volumes |
| `prod` | `make deploy-podman ENV=prod VALS=charts/starexec/values-podman-prod.yaml` | Production, with the Podman values file created above |

### Production Checklist

Before deploying to production:

- [ ] **Change default password**: set `postgres.password` in `values-podman-prod.yaml`, and `STAREXEC_DB_PASSWORD` to the same value
- [ ] **Create env-specific values file**: `cp charts/starexec/values-podman.yaml.example charts/starexec/values-podman-prod.yaml`
- [ ] **Create volumes**: `make volumes-create ENV=prod`
- [ ] **Configure backups**: Set up `make volumes-backup ENV=prod`, with the stack stopped for a consistent database copy (see [VOLUMES.md](VOLUMES.md#automated-backup))
- [ ] **Set resource limits**: Configure CPU/memory in `values-podman-prod.yaml`
- [ ] **Enable TLS**: Configure reverse proxy (nginx, traefik)
- [ ] **Review security**: Follow [Security Guide](SECURITY.md)

### Verify Deployment

```bash
# Check status
make status

# View logs
make logs

# Test database connection
make db-shell
# \q to exit
```

### Common Operations

```bash
# Stop deployment
make stop

# Restart with a fresh build. LOCAL=1 deploys it; without it, make start
# replaces it with the registry's latest image
make stop && make build-fresh && make start LOCAL=1

# View configuration
make config-show ENV=dev

# Complete reset: deletes every volume of the environment (database and
# files) and the image
make reset ENV=dev
```

---

## Method 3: Kubernetes + Helm

### Cluster Preparation

Ensure your cluster has:

1. **StorageClass** for persistent volumes
2. **Ingress controller** (optional, for external access)
3. **cert-manager** (optional, for TLS)

For a single-node MicroK8s deployment that uses this machine as the only compute node, follow [MICROK8S_SINGLE_NODE.md](MICROK8S_SINGLE_NODE.md). That runbook uses the Kubernetes-native backend, `microk8s-hostpath`, Kubernetes secrets, and a chart-level PostgreSQL permission bootstrap.

### Installation

#### From Helm Repository

```bash
# Add repository
helm repo add starexec https://starexecmiami.github.io/StarExec
helm repo update

# Install with secure configuration (the chart refuses to render without
# postgres.existingSecret or explicit dev credentials)
helm install starexec starexec/starexec \
  --create-namespace \
  -n starexec \
  --set postgres.password="$(openssl rand -base64 32)" \
  --set postgres.rootPassword="$(openssl rand -base64 32)"
```

#### From Local Chart

```bash
# Clone repository
git clone https://github.com/StarExecMiami/StarExec.git
cd StarExec

# Install from local chart
helm install starexec ./charts/starexec \
  --create-namespace \
  -n starexec \
  -f charts/starexec/values-dev.yaml
```

`values-dev.yaml` expects the pre-existing secret `starexec-postgres-credentials` in the release namespace. For Helm-specific linting and credential-handling notes, see [charts/starexec/README.md](../charts/starexec/README.md).

### Custom Values

Create a `my-values.yaml`:

```yaml
# Image configuration
image:
  repository: ghcr.io/starexecmiami/starexec
  tag: latest
  pullPolicy: IfNotPresent

# Database configuration
postgres:
  host: postgres.starexec.svc.cluster.local
  port: 5432
  user: starexec
  database: starexec
  # For production, use existingSecret instead
  existingSecret: starexec-db-secret

# Resource limits
resources:
  limits:
    memory: 4Gi
    cpu: 2
  requests:
    memory: 2Gi
    cpu: 1

# Persistence
persistence:
  enabled: true
  storageClass: "standard"
  appDataSize: 50Gi
  postgresDataSize: 20Gi

# Ingress (optional)
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: starexec.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: starexec-tls
      hosts:
        - starexec.example.com
```

Install with custom values:

```bash
helm install starexec starexec/starexec \
  -f my-values.yaml \
  -n starexec --create-namespace
```

### Verify Installation

```bash
# Check pods
kubectl get pods -n starexec

# Check services
kubectl get svc -n starexec

# View logs
kubectl logs -n starexec -l app.kubernetes.io/name=starexec -f

# Port forward for testing
kubectl port-forward -n starexec svc/starexec 8080:8080
# Access at http://localhost:8080/starexec
```

### Quokka Jenkins Deployment

Jenkins is the sole deployment authority for the canonical quokka MicroK8s
cluster. GitHub Actions validates and publishes application images; it does not
access the cluster.

| Setting | Production value |
|---------|------------------|
| Jenkins job | `StarExec Containerised` |
| Source branch | `containerised` |
| Kubernetes namespace | `starexec` |
| Helm release | `starexec` |
| Values file | `charts/starexec/values-prod.yaml` |
| Application URL | `https://quokka.acorn.miami.edu/starexec` |

Jenkins waits for a successful GitHub Container Publish run for the checked-out
commit, pulls its `sha-<full-git-sha>` image, resolves the OCI digest, imports
that exact image into MicroK8s, and deploys the digest with
`imagePullPolicy: IfNotPresent`. Do not deploy a mutable `latest` tag through
Jenkins.


### Upgrading

```bash
# Upgrade with new values
helm upgrade starexec starexec/starexec \
  -f my-values.yaml \
  -n starexec

# Upgrade to specific version
helm upgrade starexec starexec/starexec \
  --version 1.2.0 \
  -n starexec
```

### Uninstalling

`helm uninstall` deletes the PersistentVolumeClaims the chart created, and with
them the volumes when their reclaim policy is `Delete` (the default for a
StorageClass). Every values profile except `values-prod.yaml` lets the chart
create its claims; `values-prod.yaml` uses claims created outside the chart,
which `helm uninstall` leaves in place. Back up first (see
[Backup and Disaster Recovery](#backup-and-disaster-recovery)).

```bash
# Remove the release, and the PVCs the chart created
helm uninstall starexec -n starexec

# Remove the namespace and everything in it, PVCs included
kubectl delete namespace starexec
```

---

## Production Hardening

### Security

1. **Change all default credentials**
   - Application admin password
   - Database password
   - Any API keys

2. **Enable TLS/HTTPS**
   - Use cert-manager for automatic certificates
   - Configure ingress with TLS termination

3. **Restrict network access**
   - Database should not be publicly accessible
   - Use NetworkPolicies in Kubernetes

4. **Enable rootless mode** (Podman)
   - Never run as root in production

See [Security Guide](SECURITY.md) for complete security recommendations.

### High Availability (Kubernetes)

For production Kubernetes deployments:

```yaml
# my-values.yaml
replicaCount: 2

podDisruptionBudget:
  enabled: true
  minAvailable: 1

affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchLabels:
              app: starexec
          topologyKey: kubernetes.io/hostname
```

### Resource Sizing

| Workload | Memory | CPU | Storage |
|----------|--------|-----|---------|
| Development | 2Gi | 1 | 10Gi |
| Small (< 1K jobs/day) | 4Gi | 2 | 50Gi |
| Medium (< 10K jobs/day) | 8Gi | 4 | 100Gi |
| Large (< 100K jobs/day) | 16Gi | 8 | 500Gi |

---

## Backup and Disaster Recovery

### Podman

```bash
# Create backup, with the stack stopped so the database copy is consistent
make stop
make volumes-backup ENV=prod
# Restart production; VOLUMES.md (Manual Backup) explains each setting
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1

# Backups stored in ./backups/ as starexec-prod-full-<timestamp>-<volume>.tar.gz
ls -la backups/

# Restore a backup set (data and database volumes), named by its timestamp
make stop
make volumes-restore ENV=prod RESTORE_TIMESTAMP=20250101-120000
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1
```

### Kubernetes

```bash
# Backup using kubectl (embedded PostgreSQL runs as the `postgres` sidecar
# container of the app Deployment when postgres.host=localhost)
kubectl exec -n starexec deploy/starexec -c postgres -- \
  pg_dump -U starexec starexec > backup.sql

# Restore
kubectl exec -i -n starexec deploy/starexec -c postgres -- \
  psql -U starexec starexec < backup.sql
```

See [Volume Management](VOLUMES.md) for detailed backup procedures.

---

## Upgrading StarExec

### Podman

```bash
# Pull latest changes
git pull origin containerised

# Back up with the stack stopped: the new version's migrations change the
# database, and a rollback does not undo them
make stop
make volumes-backup ENV=prod

# Rebuild and redeploy production
make build-fresh
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1
```

`ENV=prod` selects the `starexec-prod-*` volumes, and `VALS=` names the Podman
production values file: with `ENV=prod` alone, make selects the Kubernetes
profile `values-prod.yaml`, whose pod mounts other volumes. `LOCAL=1` deploys
the image you just built; without it, `make start` replaces that image with
the registry's `latest` whenever the registry is reachable. Run it in a shell
where `STAREXEC_DB_PASSWORD` is set, as for the first deployment
([Production Checklist](#production-checklist)): with `ENV=prod`, `make start`
stops without it.

### Kubernetes

```bash
# Update Helm repo
helm repo update

# Upgrade release
helm upgrade starexec starexec/starexec -n starexec

# Watch rollout
kubectl rollout status deployment/starexec -n starexec
```

### Database Migrations

Migrations run automatically on startup via Flyway. If issues occur:

```bash
# Check migration status
make db-status

# Repair migration history
make migrate-repair

# View migration logs
make logs-app | grep -i flyway
```

---

## Rollback

### Podman

```bash
# Restore the backup set taken before the upgrade, named by its timestamp
make stop
make volumes-restore ENV=prod RESTORE_TIMESTAMP=<timestamp>

# Build the previous version and deploy that image, with the settings
# explained under Upgrading StarExec
git checkout <previous-version>
make build
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1
```

### Kubernetes

```bash
# Rollback to previous revision
helm rollback starexec -n starexec

# Rollback to specific revision
helm history starexec -n starexec
helm rollback starexec 2 -n starexec
```

---

## Monitoring Deployment Health

### Health Checks

```bash
# Podman - check container status
make status

# Kubernetes - check pod health
kubectl get pods -n starexec
kubectl describe pod <pod-name> -n starexec
```

### Application Health

Access health endpoints:

- `/starexec/public/health/liveness` - Liveness probe
- `/starexec/public/health/readiness` - Readiness probe
- `/starexec/` - Main application
- Database: `make db-shell` then `SELECT 1;`

### Log Aggregation

```bash
# Podman logs
make logs

# Kubernetes logs
kubectl logs -n starexec -l app.kubernetes.io/name=starexec --tail=100 -f
```

---

## Troubleshooting

### Deployment Fails to Start

```bash
# Check logs
make logs

# Check container status
podman ps -a  # or kubectl get pods -n starexec

# Common issues:
# 1. Port already in use → change APP_PORT
# 2. Database not ready → wait or restart
# 3. Volume permissions → check ownership
```

### Database Connection Issues

```bash
# Test database connectivity
make db-shell

# Check database logs
make logs-postgres

# Verify credentials
echo $STAREXEC_DB_PASSWORD
```

### Out of Disk Space

```bash
# Check disk usage
df -h

# Clean up old images
podman system prune -a

# Clean up old backups
make volumes-cleanup ENV=prod
```

See [Troubleshooting Guide](TROUBLESHOOTING.md) for more solutions.

---

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `STAREXEC_DB_HOST` | `localhost` | Database hostname |
| `STAREXEC_DB_PORT` | `5432` | Database port |
| `STAREXEC_DB_NAME` | `starexec` | Database name |
| `STAREXEC_DB_USER` | `starexec` | Database username |
| `STAREXEC_DB_PASSWORD` | *(empty)* | Database password (**required**) |
| `STAREXEC_BACKEND_TYPE` | `local` | Backend: local, podman, kubernetes, k8s, kubernetes-native, sge, oar (legacy). Runtime fallback default is `local`, but `make start` deploys Podman by default. |
| `ENV` | `dev` | Environment: dev, ci, prod |
| `APP_PORT` | `7827` | Application port (Podman) |

See [Configuration Reference](CONFIGURATION.md) for complete list.

---

## Next Steps

- **Secure your deployment**: [Security Guide](SECURITY.md)
- **Configure backends**: [Backend Configuration](BACKENDS.md)
- **Set up backups**: [Volume Management](VOLUMES.md)
- **Monitor operations**: [Observability](OBSERVABILITY.md)
