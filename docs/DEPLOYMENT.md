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
- Required packages: `passt`, `catatonit`, `fuse-overlayfs`, `yq`
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
cp charts/starexec/values-podman.yaml charts/starexec/values-prod.yaml
# Edit values-prod.yaml for production limits, credentials, and socket path
export STAREXEC_DB_PASSWORD="$(openssl rand -base64 32)"
make deploy-podman ENV=prod
```

### Environment-Specific Configuration

| Environment | Command | Purpose |
|-------------|---------|---------|
| `dev` | `make deploy-podman ENV=dev` | Local development with defaults |
| `ci` | `make deploy-podman ENV=ci` | CI/testing with ephemeral volumes |
| `prod` | `make deploy-podman ENV=prod` | Production with secure settings (requires `charts/starexec/values-prod.yaml`) |

### Production Checklist

Before deploying to production:

- [ ] **Change default password**: `export STAREXEC_DB_PASSWORD="secure-value"`
- [ ] **Create env-specific values file**: `cp charts/starexec/values-podman.yaml charts/starexec/values-prod.yaml`
- [ ] **Create volumes**: `make volumes-create ENV=prod`
- [ ] **Configure backups**: Set up automated `make volumes-backup ENV=prod`
- [ ] **Set resource limits**: Configure CPU/memory in `values-prod.yaml`
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

# Restart with fresh build
make stop && make build-fresh && make start

# View configuration
make config-show ENV=dev

# Complete reset (⚠️ deletes data)
make reset ENV=dev
```

---

## Method 3: Kubernetes + Helm

### Cluster Preparation

Ensure your cluster has:

1. **StorageClass** for persistent volumes
2. **Ingress controller** (optional, for external access)
3. **cert-manager** (optional, for TLS)

### Installation

#### From Helm Repository

```bash
# Add repository
helm repo add starexec https://starexecmiami.github.io/StarExec
helm repo update

# Install with defaults (development only)
helm install starexec starexec/starexec \
  --create-namespace \
  -n starexec

# Install with secure configuration
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
  size: 50Gi

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
kubectl logs -n starexec -l app=starexec -f

# Port forward for testing
kubectl port-forward -n starexec svc/starexec 8080:8080
# Access at http://localhost:8080/starexec
```

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

```bash
# Remove release (keeps PVCs)
helm uninstall starexec -n starexec

# Remove namespace and all resources
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
# Create backup
make volumes-backup ENV=prod

# Backups stored in ./backups/
ls -la backups/

# Restore from backup
make stop
make volumes-restore ENV=prod BACKUP=backups/starexec-prod-20250101-120000.tar.gz
make start
```

### Kubernetes

```bash
# Backup using kubectl
kubectl exec -n starexec starexec-postgres-0 -- \
  pg_dump -U starexec starexec > backup.sql

# Restore
kubectl exec -i -n starexec starexec-postgres-0 -- \
  psql -U starexec starexec < backup.sql
```

See [Volume Management](VOLUMES.md) for detailed backup procedures.

---

## Upgrading StarExec

### Podman

```bash
# Pull latest changes
git pull origin containerised

# Rebuild and redeploy
make stop
make build-fresh
make start
```

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
# Restore from backup
make stop
make volumes-restore ENV=prod BACKUP=backups/last-known-good.tar.gz
git checkout <previous-version>
make build
make start
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

- `/starexec/` - Main application
- Database: `make db-shell` then `SELECT 1;`

### Log Aggregation

```bash
# Podman logs
make logs

# Kubernetes logs
kubectl logs -n starexec -l app=starexec --tail=100 -f
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
| `STAREXEC_BACKEND_TYPE` | `local` | Backend: local, podman, kubernetes. Runtime fallback default is `local`, but `make start` deploys Podman by default. |
| `ENV` | `dev` | Environment: dev, ci, prod |
| `APP_PORT` | `7827` | Application port (Podman) |

See [Configuration Reference](CONFIGURATION.md) for complete list.

---

## Next Steps

- **Secure your deployment**: [Security Guide](SECURITY.md)
- **Configure backends**: [Backend Configuration](BACKENDS.md)
- **Set up backups**: [Volume Management](VOLUMES.md)
- **Monitor operations**: [Observability](OBSERVABILITY.md)
