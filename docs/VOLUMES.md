# Volume Management Guide

Complete guide to managing StarExec data volumes, backups, and disaster recovery.

## Overview

StarExec uses persistent volumes to store:

- **Database data** - PostgreSQL data directory
- **Application data** - Solvers, benchmarks, job outputs
- **Working data** - Backend, sandbox, and work directories

Volume management ensures data durability across container restarts and enables backup/restore operations.

---

## Volume Architecture

### Podman Volumes

StarExec creates environment-specific named volumes:

| Volume Name | Purpose | Size Estimate |
|-------------|---------|---------------|
| `starexec-{ENV}-data` | Application data (solvers, benchmarks, uploads) | 10-500 GB |
| `starexec-{ENV}-sandbox` | Job sandbox/working data | 1-100 GB |
| `starexec-{ENV}-backend` | Backend working data | 1-100 GB |
| `starexec-{ENV}-work` | Job work directory | 1-100 GB |
| `starexec-{ENV}-postgres` | PostgreSQL database | 1-50 GB |

Where `{ENV}` is `dev`, `ci`, or `prod`.

### Kubernetes PersistentVolumeClaims

```yaml
# PVCs created by the Helm chart (one per volume type)
starexec-{ENV}-data      # Application data
starexec-{ENV}-sandbox   # Sandbox data
starexec-{ENV}-backend   # Backend data
starexec-{ENV}-work      # Work data
starexec-{ENV}-postgres  # Database data

# The Kubernetes-native backend's data claim can be overridden with
# kubernetes.dataPvc.name (see charts/starexec/values-microk8s.yaml).
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

# Restore a backup set, named by its timestamp
make volumes-restore ENV=prod RESTORE_TIMESTAMP=20250101-020000

# Export volume for sharing
make volumes-export ENV=prod VOLUME=data

# Delete volumes (⚠️ destructive: all five volumes of ENV, and the legacy
# starexec-data and starexec-postgres volumes whatever ENV is)
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
podman volume create starexec-prod-sandbox
podman volume create starexec-prod-backend
podman volume create starexec-prod-work
podman volume create starexec-prod-postgres

# Verify creation
podman volume ls | grep starexec
```

### Kubernetes

PVCs are created by the Helm chart. Customize in `values.yaml`:

```yaml
persistence:
  enabled: true
  storageClass: "fast-ssd"  # Your storage class
  appDataSize: 100Gi
  backendSize: 10Gi
  sandboxSize: 10Gi
  workSize: 10Gi
  postgresDataSize: 50Gi
```

---

## Backup Procedures

### Automated Backup

Create a cron job for regular backups:

```bash
# /etc/cron.d/starexec-backup
0 2 * * * starexec cd /opt/starexec && make volumes-backup ENV=prod >> /var/log/starexec-backup.log 2>&1
```

`make volumes-backup` copies the volumes with `tar` and does not stop the
stack. Run from cron as above, it copies the running database: with the stack
running it warns, and asks whether to continue only when it has a terminal.
PostgreSQL's files make a usable backup only when the server is stopped
([File System Level Backup](https://www.postgresql.org/docs/current/backup-file.html)),
so a copy of a running database may not restore. Stop the stack for the copy
(see [Manual Backup](#manual-backup)), or check regularly that these copies
restore (see [Testing Disaster Recovery](#testing-disaster-recovery)).

### Manual Backup

```bash
# Stop application for consistent backup (recommended)
make stop

# Create backup
make volumes-backup ENV=prod

# Restart production
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1

# Backup location: one archive per volume, plus metadata
ls -la backups/
# starexec-prod-full-20250115-020000-data.tar.gz (and -sandbox, -backend,
# -work, -postgres, -metadata.json, -manifest.json)
```

Every `make start` for production in this guide passes the settings
production was deployed with. `ENV=prod` selects the `starexec-prod-*`
volumes. `VALS=` names the Podman production values file: with `ENV=prod`
alone, make selects the Kubernetes profile `values-prod.yaml`, whose pod mounts
other volumes. `LOCAL=1` keeps the image already on the host; without it,
`make start` replaces that image with the registry's `latest` whenever the
registry is reachable, and a newer image runs its migrations on your data.
Run it in a shell where `STAREXEC_DB_PASSWORD` is set, as for the first
deployment ([Production Checklist](DEPLOYMENT.md#production-checklist)): with
`ENV=prod`, `make start` stops without it.

### Backup Without Downtime

For production systems that can't stop:

```bash
# Backup with live database: its archive may not restore, because
# PostgreSQL's files are only a consistent backup when the server is stopped
make volumes-backup ENV=prod

# Or use PostgreSQL's built-in backup
make db-dump
# Creates: backups/starexec-db-20250115.sql
```

### What's Included in Backups

Each backup set is one archive per volume, named by environment and timestamp:

```
backups/
├── starexec-prod-full-20250115-020000-data.tar.gz      # solvers, benchmarks, job output
├── starexec-prod-full-20250115-020000-postgres.tar.gz  # database files
├── starexec-prod-full-20250115-020000-sandbox.tar.gz
├── starexec-prod-full-20250115-020000-backend.tar.gz
├── starexec-prod-full-20250115-020000-work.tar.gz
├── starexec-prod-full-20250115-020000-metadata.json
├── starexec-prod-full-20250115-020000-manifest.json
└── starexec-prod-full-20250115-020000-emptyvols.txt    # only when a volume was empty
```

`make volumes-restore` imports the data and postgres archives of a set; it does
not restore the sandbox, backend and work archives.

### Backup Encryption

For sensitive data, encrypt backups:

```bash
# Create encrypted backup
make volumes-backup ENV=prod

# Encrypt each archive of the set; remove a plain archive only once its
# encrypted copy was written. Decrypt them into backups/ before a restore.
for f in backups/starexec-prod-full-20250115-020000-*.tar.gz; do
  gpg --encrypt --recipient admin@example.com "$f" && rm "$f"
done
```

### Offsite Backup

Transfer backups to remote storage:

```bash
# AWS S3
aws s3 cp backups/starexec-prod-*.tar.gz s3://my-backups/starexec/ \
  --sse AES256

# Rsync to backup server (no --delete: pruning the local backups must not
# delete the offsite copies)
rsync -avz backups/ backup-server:/backups/starexec/

# Google Cloud Storage
gsutil cp backups/starexec-prod-*.tar.gz gs://my-backups/starexec/
```

---

## Restore Procedures

### Full Restore

```bash
# ⚠️ STOP APPLICATION FIRST
make stop

# Restore the data and database volumes of one backup set, named by the
# timestamp in its file names (backups/starexec-prod-full-<timestamp>-*).
# Without RESTORE_TIMESTAMP, make lists the sets and asks, or takes the newest
# when it has no terminal. It asks before overwriting each existing volume, so
# run it from a terminal; it does not remove files added after the backup.
make volumes-restore ENV=prod RESTORE_TIMESTAMP=20250115-020000

# Start production (settings explained under Manual Backup)
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1

# Verify restore
make status
make db-shell
# SELECT COUNT(*) FROM users;
```

### Partial Restore (Database Only)

`make volumes-restore` has no `BACKUP=` or `VOLUMES=` option: it always
restores both the data and the database volume of the chosen set. Restoring
the database alone would leave it out of step with the files on the data
volume, so restore the whole set as above.

### Restore from SQL Dump

No make target restores a SQL dump, and a dump holds only the database: the
solvers, benchmarks and job output on the data volume are not in it. To recover
a deployment, restore a `make volumes-backup` set (see [Full Restore](#full-restore)).
Load a dump only into a new, empty database, and do not delete the
environment's volumes to make room for it: `make volumes-delete` removes all
five, the data volume included.

### Kubernetes Restore

```bash
# Scale down application
kubectl scale deployment starexec -n starexec --replicas=0

# Embedded PostgreSQL is the `postgres` sidecar of the app Deployment
# (when postgres.host=localhost); exec into that container instead of a pod.
APP_POD=$(kubectl get pods -n starexec -l app.kubernetes.io/name=starexec -o jsonpath='{.items[0].metadata.name}')

# Restore database
kubectl exec -i -n starexec "$APP_POD" -c postgres -- \
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
# Delete one environment's volumes (here CI). This also removes the legacy
# starexec-data and starexec-postgres volumes if they exist.
make volumes-delete ENV=ci

# Avoid `podman volume prune`: on Podman 4 and 5 it removes every volume that
# no container uses, named ones included, so after `make stop` it deletes all
# StarExec volumes, and other projects' too.

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

2. **Stop affected services and copy their volumes**
   ```bash
   make stop
   # File-level copy of the damaged volumes, if they are still readable;
   # the restore writes over them
   make volumes-backup ENV=prod
   ```

3. **Identify the last valid backup**
   ```bash
   ls -la backups/
   # A set is backups/starexec-prod-full-<timestamp>-<volume>.tar.gz. The copy
   # from step 2 is now the newest set; check the archives of the set you
   # will restore
   for f in backups/starexec-prod-full-20250115-020000-*.tar.gz; do
     tar -tzf "$f" > /dev/null && echo "OK $f"
   done
   ```

4. **Restore from backup**
   ```bash
   make volumes-restore ENV=prod RESTORE_TIMESTAMP=20250115-020000
   ```

5. **Verify restoration**
   ```bash
   # On a new host, first build the version production ran (make build)
   make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1
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

Schedule quarterly DR tests on a separate host, never on the production host:
`make start` and `make stop` act on every pod of the release
(`app.kubernetes.io/instance=starexec`) whatever `ENV` is, so starting a test
environment there stops production. A backup set restores only into the
environment named in its files, so the test host uses `ENV=prod` as well, with
a copy of production's values file:

```bash
# 1. Copy one set (backups/starexec-prod-full-<timestamp>-*) into backups/ on
#    the test host, then create the volumes
make volumes-create ENV=prod

# 2. Restore it
make volumes-restore ENV=prod RESTORE_TIMESTAMP=20250115-020000

# 3. Build the version production runs, start it and verify
git checkout <production commit>
make build
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1
# Test application functionality

# 4. Cleanup, on the test host only
make stop
make volumes-delete ENV=prod
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

Copy the damaged volumes before anything else, then restore a backup.
`pg_resetwal` is a last resort for when no usable backup exists: the database
can come back with inconsistent data, and PostgreSQL says to dump, re-initialise
and restore right after using it (see
[pg_resetwal](https://www.postgresql.org/docs/current/app-pgresetwal.html)).

```bash
# Check PostgreSQL logs
make logs-postgres

# Keep a file-level copy of the damaged volumes (stack stopped)
make stop
make volumes-backup ENV=prod

# That copy is now the newest set, so name the last good one explicitly
make volumes-restore ENV=prod RESTORE_TIMESTAMP=<timestamp of the last good set>
make start ENV=prod VALS=charts/starexec/values-podman-prod.yaml LOCAL=1
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