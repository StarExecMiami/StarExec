# Volume Management Guide

Complete guide to managing StarExec data volumes, backups, and disaster recovery.

## Overview

StarExec uses persistent volumes to store:

- **Database data** - PostgreSQL data directory
- **Application data** - Solvers, benchmarks, job outputs
- **Configuration** - Runtime configuration files

Volume management ensures data durability across container restarts and enables backup/restore operations.

---

## Volume Architecture

### Podman Volumes

StarExec creates environment-specific named volumes:

| Volume Name | Purpose | Size Estimate |
|-------------|---------|---------------|
| `starexec-{ENV}-data` | Application data (solvers, benchmarks) | 10-500 GB |
| `starexec-{ENV}-postgres` | PostgreSQL database | 1-50 GB |
| `starexec-{ENV}-config` | Configuration files | < 100 MB |

Where `{ENV}` is `dev`, `ci`, or `prod`.

### Kubernetes PersistentVolumeClaims

```yaml
# PVCs created by Helm chart
starexec-data-pvc      # Application data
starexec-postgres-pvc  # Database data
```

---

## Quick Reference

```bash
# List all volumes
make volumes-list

# Create volumes for environment
make volumes-create ENV=prod

# Check volume health and sizes
make volumes-health ENV=prod

# Backup all volumes
make volumes-backup ENV=prod

# Restore from backup
make volumes-restore ENV=prod BACKUP=backups/starexec-prod-20250101.tar.gz

# Export volume for sharing
make volumes-export ENV=prod VOLUME=data

# Delete volumes (⚠️ destructive)
make volumes-delete ENV=prod

# Show detailed help
make volumes-help
```

---

## Creating Volumes

### Podman

Volumes are created automatically on first deployment, but you can pre-create them:

```bash
# Create all volumes for development
make volumes-create ENV=dev

# Create all volumes for production
make volumes-create ENV=prod
```

### Manual Creation

```bash
# Create individual volumes
podman volume create starexec-prod-data
podman volume create starexec-prod-postgres
podman volume create starexec-prod-config

# Verify creation
podman volume ls | grep starexec
```

### Kubernetes

PVCs are created by the Helm chart. Customize in `values.yaml`:

```yaml
persistence:
  enabled: true
  storageClass: "fast-ssd"  # Your storage class
  
  data:
    size: 100Gi
    accessModes:
      - ReadWriteOnce
  
  postgres:
    size: 50Gi
    accessModes:
      - ReadWriteOnce
```

---

## Backup Procedures

### Automated Backup (Recommended)

Create a cron job for regular backups:

```bash
# /etc/cron.d/starexec-backup
0 2 * * * starexec cd /opt/starexec && make volumes-backup ENV=prod >> /var/log/starexec-backup.log 2>&1
```

### Manual Backup

```bash
# Stop application for consistent backup (recommended)
make stop

# Create backup
make volumes-backup ENV=prod

# Restart application
make start

# Backup location
ls -la backups/
# starexec-prod-20250115-020000.tar.gz
```

### Backup Without Downtime

For production systems that can't stop:

```bash
# Backup with live database (may have minor inconsistencies)
make volumes-backup ENV=prod

# Or use PostgreSQL's built-in backup
make db-dump
# Creates: backups/starexec-db-20250115.sql
```

### What's Included in Backups

Each backup archive contains:

```
starexec-prod-20250115-020000.tar.gz
├── data/           # Application data
│   ├── solvers/    # Uploaded solvers
│   ├── benchmarks/ # Uploaded benchmarks
│   ├── outputs/    # Job outputs
│   └── uploads/    # Pending uploads
├── postgres/       # Database files
│   ├── base/       # PostgreSQL data
│   └── pg_wal/     # Write-ahead logs
├── config/         # Configuration
└── metadata.json   # Backup metadata
```

### Backup Encryption

For sensitive data, encrypt backups:

```bash
# Create encrypted backup
make volumes-backup ENV=prod
gpg --encrypt --recipient admin@example.com \
  backups/starexec-prod-20250115-020000.tar.gz

# Remove unencrypted backup
rm backups/starexec-prod-20250115-020000.tar.gz
```

### Offsite Backup

Transfer backups to remote storage:

```bash
# AWS S3
aws s3 cp backups/starexec-prod-*.tar.gz s3://my-backups/starexec/ \
  --sse AES256

# Rsync to backup server
rsync -avz --delete backups/ backup-server:/backups/starexec/

# Google Cloud Storage
gsutil cp backups/starexec-prod-*.tar.gz gs://my-backups/starexec/
```

---

## Restore Procedures

### Full Restore

```bash
# ⚠️ STOP APPLICATION FIRST
make stop

# Restore all volumes from backup
make volumes-restore ENV=prod \
  BACKUP=backups/starexec-prod-20250115-020000.tar.gz

# Start application
make start

# Verify restore
make status
make db-shell
# SELECT COUNT(*) FROM users;
```

### Partial Restore (Database Only)

```bash
# Stop application
make stop

# Restore only PostgreSQL data
make volumes-restore ENV=prod \
  BACKUP=backups/starexec-prod-20250115-020000.tar.gz \
  VOLUMES=postgres

# Start application
make start
```

### Restore from SQL Dump

```bash
# Stop application
make stop

# Recreate database volume
make volumes-delete ENV=prod VOLUMES=postgres
make volumes-create ENV=prod

# Start only PostgreSQL
podman start starexec-postgres

# Wait for PostgreSQL to be ready
sleep 10

# Restore from SQL dump
cat backups/starexec-db-20250115.sql | \
  podman exec -i starexec-postgres psql -U starexec

# Start application
make start
```

### Kubernetes Restore

```bash
# Scale down application
kubectl scale deployment starexec -n starexec --replicas=0

# Find postgres pod
POSTGRES_POD=$(kubectl get pods -n starexec -l app=postgres -o jsonpath='{.items[0].metadata.name}')

# Restore database
kubectl exec -i -n starexec $POSTGRES_POD -- \
  psql -U starexec starexec < backup.sql

# Scale up application
kubectl scale deployment starexec -n starexec --replicas=1
```

---

## Volume Health Checks

### Check Volume Status

```bash
# Quick health check
make volumes-health ENV=prod

# Output example:
# Volume: starexec-prod-data
#   Size: 45.2 GB
#   Used: 38.1 GB (84%)
#   Status: ✓ Healthy
#
# Volume: starexec-prod-postgres
#   Size: 10.1 GB
#   Used: 8.2 GB (81%)
#   Status: ✓ Healthy
```

### Check Volume Sizes

```bash
# List all volumes with sizes
podman volume ls --format "{{.Name}}: {{.Size}}"

# Detailed volume inspection
podman volume inspect starexec-prod-data
```

### Database Integrity Check

```bash
# Connect to database
make db-shell

# Check for corruption
SELECT pg_catalog.pg_relation_check_pages('users');

# Verify table counts
SELECT 
  'users' as table_name, COUNT(*) FROM users
UNION ALL
  SELECT 'jobs', COUNT(*) FROM jobs
UNION ALL
  SELECT 'solvers', COUNT(*) FROM solvers;
```

---

## Cleanup Operations

### Remove Old Backups

```bash
# Keep only last 10 backups (automatic cleanup)
make volumes-cleanup ENV=prod

# Custom retention (keep last 30 days)
find backups/ -name "starexec-prod-*.tar.gz" -mtime +30 -delete
```

### Reclaim Disk Space

```bash
# Clean up unused volumes
podman volume prune

# Clean up specific environment
make volumes-delete ENV=ci  # Delete CI volumes

# Clean up orphaned containers
podman container prune
```

### Database Vacuum

```bash
# Connect and vacuum
make db-shell

-- Reclaim space from deleted rows
VACUUM FULL;

-- Update statistics
ANALYZE;

-- Check bloat
SELECT 
  schemaname, tablename, 
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size
FROM pg_tables 
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

---

## Migration Between Environments

### Export for Migration

```bash
# Create exportable archive
make volumes-export ENV=dev

# This creates: exports/starexec-dev-export-20250115.tar.gz
```

### Import to New Environment

```bash
# On target system
make volumes-create ENV=prod

# Extract and restore
tar -xzf starexec-dev-export-20250115.tar.gz -C /tmp/import/

# Copy data to volumes
podman volume import starexec-prod-data /tmp/import/data.tar
podman volume import starexec-prod-postgres /tmp/import/postgres.tar
```

### Cross-Platform Migration

```bash
# Export using portable format
pg_dump -U starexec -Fc starexec > starexec.dump
tar -czf data-export.tar.gz /path/to/data/

# Import on target
pg_restore -U starexec -d starexec starexec.dump
tar -xzf data-export.tar.gz -C /new/path/to/data/
```

---

## Storage Planning

### Capacity Estimation

| Workload | Database | Application Data | Total |
|----------|----------|------------------|-------|
| Development | 1 GB | 5 GB | 10 GB |
| Small (< 100 solvers) | 5 GB | 20 GB | 30 GB |
| Medium (< 1000 solvers) | 20 GB | 100 GB | 150 GB |
| Large (competition) | 50 GB | 500 GB | 600 GB |

### Growth Factors

- **Solvers**: 10-500 MB each (varies widely)
- **Benchmarks**: 1-100 MB per benchmark set
- **Job outputs**: 1-10 MB per job pair
- **Database**: ~1 KB per job pair record

### Monitoring Disk Usage

```bash
# Check host disk space
df -h

# Check volume sizes
make volumes-health ENV=prod

# Alert when disk usage > 80%
if [ $(df / --output=pcent | tail -1 | tr -d '% ') -gt 80 ]; then
  echo "WARNING: Disk usage above 80%"
fi
```

---

## Disaster Recovery

### Recovery Time Objectives

| Scenario | RTO | Procedure |
|----------|-----|-----------|
| Container crash | < 5 min | Automatic restart |
| Volume corruption | < 30 min | Restore from backup |
| Host failure | < 2 hours | Deploy on new host + restore |
| Data center outage | < 24 hours | Deploy in new region + restore |

### Recovery Runbook

1. **Assess damage**
   ```bash
   make status
   make logs
   make volumes-health ENV=prod
   ```

2. **Stop affected services**
   ```bash
   make stop
   ```

3. **Identify latest valid backup**
   ```bash
   ls -la backups/
   # Verify backup integrity
   tar -tzf backups/starexec-prod-20250115.tar.gz > /dev/null && echo "OK"
   ```

4. **Restore from backup**
   ```bash
   make volumes-restore ENV=prod \
     BACKUP=backups/starexec-prod-20250115.tar.gz
   ```

5. **Verify restoration**
   ```bash
   make start
   make db-shell
   # SELECT COUNT(*) FROM users;
   # SELECT MAX(created) FROM jobs;
   ```

6. **Document incident**
   - Time of failure
   - Root cause
   - Data loss window
   - Recovery time

### Testing Disaster Recovery

Schedule quarterly DR tests:

```bash
# 1. Create test environment
make volumes-create ENV=dr-test

# 2. Restore backup
make volumes-restore ENV=dr-test \
  BACKUP=backups/starexec-prod-latest.tar.gz

# 3. Start and verify
make deploy-podman ENV=dr-test
# Test application functionality

# 4. Cleanup
make stop ENV=dr-test
make volumes-delete ENV=dr-test
```

---

## Troubleshooting

### Volume Not Found

```bash
# Check if volume exists
podman volume ls | grep starexec

# Recreate if missing
make volumes-create ENV=prod
```

### Permission Denied

```bash
# Check volume ownership
podman volume inspect starexec-prod-data | grep -A5 Mountpoint

# Fix permissions (rootless Podman)
podman unshare chown -R $(id -u):$(id -g) /path/to/volume
```

### Backup Too Large

```bash
# Exclude job outputs (largest component)
tar --exclude='outputs' -czf backup-no-outputs.tar.gz data/

# Compress more aggressively
tar -cf - data/ | xz -9 > backup.tar.xz

# Use incremental backups
tar --listed-incremental=backup.snar -czf backup-incremental.tar.gz data/
```

### Restore Fails

```bash
# Check backup integrity
tar -tzf backup.tar.gz

# Check disk space
df -h

# Try extracting to temp location first
mkdir /tmp/restore-test
tar -xzf backup.tar.gz -C /tmp/restore-test

# If successful, manually copy to volumes
```

### Database Corruption

```bash
# Check PostgreSQL logs
make logs-postgres

# Try to recover
podman exec starexec-postgres pg_resetwal -f /var/lib/postgresql/data

# If that fails, restore from backup
make volumes-restore ENV=prod VOLUMES=postgres BACKUP=last-good-backup.tar.gz
```

---

## Best Practices

### Backup Strategy

1. **Daily full backups** - Keep 7 days
2. **Weekly archives** - Keep 4 weeks
3. **Monthly archives** - Keep 12 months
4. **Offsite replication** - At least 2 locations

### Volume Naming

Use consistent naming:
- `starexec-{env}-{component}`
- Examples: `starexec-prod-data`, `starexec-dev-postgres`

### Security

- Encrypt backups at rest
- Restrict access to backup storage
- Use separate credentials for backup operations
- Audit backup access

### Documentation

- Document backup locations
- Document restore procedures
- Test restores regularly
- Keep runbooks updated

---

## Related Documentation

- **[Deployment Guide](DEPLOYMENT.md)** - Initial setup
- **[Database Guide](DATABASE.md)** - Database operations
- **[Troubleshooting](TROUBLESHOOTING.md)** - Common issues
- **[Security Guide](SECURITY.md)** - Security considerations