# Database Guide

Complete guide to PostgreSQL database operations for StarExec.

## Overview

StarExec uses PostgreSQL 15+ as its primary database. This guide covers:

- Database administration
- Flyway migrations
- Backup and restore
- Performance tuning
- Common operations

---

## Quick Reference

```bash
# Open database shell
make db-shell

# Check migration status
make db-status

# Create database dump
make db-dump

# Run migrations manually
make migrate-podman

# Repair migration history
make migrate-repair
```

---

## Database Architecture

### Schema Overview

StarExec's database contains these primary tables:

| Table | Purpose |
|-------|---------|
| `users` | User accounts and authentication |
| `spaces` | Hierarchical space organization |
| `solvers` | Uploaded solver programs |
| `benchmarks` | Problem files for testing |
| `jobs` | Job definitions and metadata |
| `job_pairs` | Individual solver-benchmark pairs |
| `configurations` | Solver configurations |
| `processors` | Pre/post processors |
| `queues` | Execution queues |

### Entity Relationships

```
users ─┬── spaces ─┬── solvers ─── configurations
       │           ├── benchmarks
       │           └── jobs ─── job_pairs
       │
       └── permissions
```

---

## Connection Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `STAREXEC_DB_HOST` | `localhost` | Database hostname |
| `STAREXEC_DB_PORT` | `5432` | Database port |
| `STAREXEC_DB_NAME` | `starexec` | Database name |
| `STAREXEC_DB_USER` | `starexec` | Database username |
| `STAREXEC_DB_PASSWORD` | *(required)* | Database password |

### Connection String

```
jdbc:postgresql://${STAREXEC_DB_HOST}:${STAREXEC_DB_PORT}/${STAREXEC_DB_NAME}
```

### Testing Connection

```bash
# Via Makefile
make db-shell

# Direct connection
podman exec -it starexec-postgres psql -U starexec

# From host (if port is exposed)
psql -h localhost -p 5432 -U starexec -d starexec
```

---

## Flyway Migrations

### How Migrations Work

StarExec uses [Flyway](https://flywaydb.org/) for database schema management:

1. SQL migration files are stored in `src/main/resources/db/migration/`
2. Files are named: `V{version}__{description}.sql`
3. Flyway tracks applied migrations in `flyway_schema_history`
4. Migrations run automatically on application startup

### Migration File Naming

```
V1__Initial_schema.sql          # Version 1
V2__Add_users_table.sql         # Version 2
V3.1__Add_email_column.sql      # Version 3.1
V10__Performance_indexes.sql    # Version 10
```

### Notable Migrations

| Migration | Description |
|-----------|-------------|
| `V0001__baseline_schema.sql` | Full schema baseline |
| `V0021__bcrypt_only_passwords.sql` | BCrypt password migration |
| `V0025__batch_rerun_and_indexes.sql` | PL/pgSQL function `RerunJobPairsBatch(int[])` for efficient batch job pair resets (replaces N+1 loop) |
| `V0026__add_missing_indexes.sql` | 13 performance indexes on hot-path columns; runs non-transactionally (`-- flyway:executeInTransaction=false`) to allow `CREATE INDEX CONCURRENTLY` |

### Checking Migration Status

```bash
# Via Makefile
make db-status

# Via database
make db-shell
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
```

### Running Migrations Manually

```bash
# Run pending migrations
make migrate-podman

# This is rarely needed - migrations run on startup
```

### Repairing Failed Migrations

If a migration fails partway through:

```bash
# Repair Flyway schema history
make migrate-repair

# Then restart to retry migrations
make stop && make start
```

### Creating New Migrations

1. Create a new file in `src/main/resources/db/migration/`:
   ```bash
   touch src/main/resources/db/migration/V{next_version}__Description.sql
   ```

2. Add SQL statements:
   ```sql
   -- V0027__Add_job_priority.sql
   ALTER TABLE jobs ADD COLUMN priority INTEGER DEFAULT 0;
   CREATE INDEX idx_jobs_priority ON jobs(priority);
   ```

   If the migration contains `CREATE INDEX CONCURRENTLY`, add the following annotation
   at the top of the file so Flyway does not wrap it in a transaction (PostgreSQL forbids
   `CONCURRENTLY` inside a transaction block):
   ```sql
   -- flyway:executeInTransaction=false
   ```

3. Test locally:
   ```bash
   make stop && make start
   make db-status
   ```

---

## Backup and Restore

### Creating Backups

#### Logical Backup (Recommended)

```bash
# Via Makefile (creates SQL dump)
make db-dump

# Output: backups/starexec-db-20250115-120000.sql

# Manual backup
podman exec starexec-postgres pg_dump -U starexec starexec > backup.sql
```

#### Custom Format (Faster Restore)

```bash
podman exec starexec-postgres \
  pg_dump -U starexec -Fc starexec > backup.dump
```

#### Compressed Backup

```bash
podman exec starexec-postgres \
  pg_dump -U starexec starexec | gzip > backup.sql.gz
```

### Restoring Backups

#### From SQL Dump

```bash
# Stop application
make stop

# Restore
cat backup.sql | podman exec -i starexec-postgres psql -U starexec

# Start application
make start
```

#### From Custom Format

```bash
podman exec -i starexec-postgres \
  pg_restore -U starexec -d starexec < backup.dump
```

#### To Fresh Database

```bash
# Drop and recreate database
podman exec starexec-postgres psql -U postgres -c "DROP DATABASE starexec;"
podman exec starexec-postgres psql -U postgres -c "CREATE DATABASE starexec OWNER starexec;"

# Restore
cat backup.sql | podman exec -i starexec-postgres psql -U starexec
```

### Automated Backups

Create a cron job:

```bash
# /etc/cron.d/starexec-db-backup
0 3 * * * starexec cd /opt/starexec && make db-dump >> /var/log/starexec-backup.log 2>&1
```

---

## Common Operations

### User Management

```sql
-- List all users
SELECT id, email, first_name, last_name, role FROM users;

-- Reset user password (hash required from application)
-- Use application UI or API instead

-- Disable user
UPDATE users SET enabled = false WHERE email = 'user@example.com';

-- Enable user
UPDATE users SET enabled = true WHERE email = 'user@example.com';

-- Check admin users
SELECT id, email FROM users WHERE role = 'admin';
```

### Job Management

```sql
-- Count jobs by status
SELECT status_code, COUNT(*) 
FROM jobs 
GROUP BY status_code;

-- Find stuck jobs (running > 24 hours)
SELECT id, name, created 
FROM jobs 
WHERE status_code = 'RUNNING' 
AND created < NOW() - INTERVAL '24 hours';

-- Cancel stuck job
UPDATE jobs SET status_code = 'KILLED' WHERE id = ?;
UPDATE job_pairs SET status_code = 16 WHERE job_id = ?;

-- Clean old completed jobs (> 90 days)
DELETE FROM job_pairs 
WHERE job_id IN (
  SELECT id FROM jobs 
  WHERE status_code = 'COMPLETE' 
  AND completed < NOW() - INTERVAL '90 days'
);
DELETE FROM jobs 
WHERE status_code = 'COMPLETE' 
AND completed < NOW() - INTERVAL '90 days';
```

### Space Management

```sql
-- List root spaces
SELECT id, name FROM spaces WHERE parent_id IS NULL;

-- Count items in space
SELECT 
  (SELECT COUNT(*) FROM solvers WHERE space_id = ?) as solvers,
  (SELECT COUNT(*) FROM benchmarks WHERE space_id = ?) as benchmarks,
  (SELECT COUNT(*) FROM jobs WHERE primary_space = ?) as jobs;

-- Find orphaned resources
SELECT id, name FROM solvers 
WHERE space_id NOT IN (SELECT id FROM spaces);
```

### Storage Statistics

```sql
-- Database size
SELECT pg_size_pretty(pg_database_size('starexec'));

-- Table sizes
SELECT 
  tablename,
  pg_size_pretty(pg_total_relation_size('public.' || tablename)) as size
FROM pg_tables 
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size('public.' || tablename) DESC;

-- Row counts
SELECT 
  'users' as table_name, COUNT(*) FROM users
UNION ALL SELECT 'jobs', COUNT(*) FROM jobs
UNION ALL SELECT 'job_pairs', COUNT(*) FROM job_pairs
UNION ALL SELECT 'solvers', COUNT(*) FROM solvers
UNION ALL SELECT 'benchmarks', COUNT(*) FROM benchmarks;
```

---

## Performance Tuning

### PostgreSQL Configuration

For production workloads, adjust `postgresql.conf`:

```ini
# Memory (adjust based on available RAM)
shared_buffers = 1GB              # 25% of RAM
effective_cache_size = 3GB        # 75% of RAM
work_mem = 64MB                   # Per-operation memory
maintenance_work_mem = 256MB      # For VACUUM, CREATE INDEX

# Connections
max_connections = 100

# Write Ahead Log
wal_buffers = 64MB
checkpoint_completion_target = 0.9

# Query Planning
random_page_cost = 1.1            # For SSD storage
effective_io_concurrency = 200   # For SSD storage

# Logging
log_min_duration_statement = 1000 # Log queries > 1 second
```

### Index Optimization

Performance-critical indexes on `job_pairs`, `jobs`, `solvers`, `configurations`,
`benchmarks`, `logins`, `job_spaces`, and `jobpair_stage_data` are created automatically
by Flyway migration **V0026** using `CREATE INDEX CONCURRENTLY`. Do not create them
manually — Flyway will detect duplicate index names and fail.

To diagnose missing indexes on other columns:

```sql
-- Find tables with high sequential scan counts (candidates for new indexes)
SELECT 
  schemaname, tablename, 
  seq_scan, seq_tup_read,
  idx_scan, idx_tup_fetch
FROM pg_stat_user_tables
WHERE seq_scan > 1000
ORDER BY seq_tup_read DESC;
```

### Query Analysis

```sql
-- Enable timing
\timing on

-- Explain slow query
EXPLAIN ANALYZE 
SELECT * FROM job_pairs 
WHERE job_id = 123 
AND status_code = 7;

-- Find slow queries (if pg_stat_statements enabled)
SELECT query, calls, mean_time, total_time
FROM pg_stat_statements
ORDER BY mean_time DESC
LIMIT 10;
```

### Maintenance

```sql
-- Reclaim space from deleted rows
VACUUM FULL;

-- Update query planner statistics
ANALYZE;

-- Rebuild all indexes
REINDEX DATABASE starexec;

-- Check for bloat
SELECT 
  schemaname, tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as total_size,
  pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) as table_size,
  pg_size_pretty(pg_indexes_size(schemaname||'.'||tablename::regclass)) as index_size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

---

## Troubleshooting

### Connection Refused

```bash
# Check if PostgreSQL is running
podman ps | grep postgres

# Check PostgreSQL logs
make logs-postgres

# Test connection
podman exec starexec-postgres pg_isready

# Verify network
podman network inspect starexec-net
```

### Authentication Failed

```bash
# Verify password is set
echo $STAREXEC_DB_PASSWORD

# Check pg_hba.conf authentication
podman exec starexec-postgres cat /var/lib/postgresql/data/pg_hba.conf

# Reset password
podman exec starexec-postgres psql -U postgres -c \
  "ALTER USER starexec WITH PASSWORD 'new-password';"
```

### Migration Failure

```bash
# Check Flyway status
make db-status

# View migration history
make db-shell
SELECT * FROM flyway_schema_history WHERE success = false;

# Repair and retry
make migrate-repair
make stop && make start
```

### Database Locked

```sql
-- Find blocking queries
SELECT pid, usename, query, state, wait_event
FROM pg_stat_activity 
WHERE state = 'active';

-- Terminate blocking query (use with caution)
SELECT pg_terminate_backend(pid);

-- Find locks
SELECT 
  l.locktype, l.mode, l.granted,
  a.usename, a.query
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE NOT l.granted;
```

### Out of Disk Space

```sql
-- Check database size
SELECT pg_size_pretty(pg_database_size('starexec'));

-- Find largest tables
SELECT tablename, pg_size_pretty(pg_total_relation_size('public.' || tablename))
FROM pg_tables WHERE schemaname = 'public'
ORDER BY pg_total_relation_size('public.' || tablename) DESC
LIMIT 10;

-- Clean up old job data
DELETE FROM job_pairs WHERE job_id IN (
  SELECT id FROM jobs WHERE completed < NOW() - INTERVAL '180 days'
);
VACUUM FULL job_pairs;
```

### Slow Queries

```sql
-- Enable slow query logging
ALTER SYSTEM SET log_min_duration_statement = 500;
SELECT pg_reload_conf();

-- Check current long-running queries
SELECT pid, now() - query_start as duration, query
FROM pg_stat_activity
WHERE state = 'active' AND now() - query_start > interval '5 seconds';
```

---

## Security

### Password Management

```bash
# Use password file (recommended)
echo "secure-password" > /run/secrets/db-password
chmod 600 /run/secrets/db-password
export STAREXEC_DB_PASSWORD_FILE=/run/secrets/db-password
```

### Network Security

```sql
-- Restrict connections (pg_hba.conf)
# Only allow from application container
host    starexec    starexec    10.88.0.0/16    scram-sha-256
# Deny all other connections
host    all         all         0.0.0.0/0       reject
```

### Audit Logging

```sql
-- Enable logging of all statements (verbose)
ALTER SYSTEM SET log_statement = 'all';
SELECT pg_reload_conf();

-- Or just DDL and modifications
ALTER SYSTEM SET log_statement = 'mod';
SELECT pg_reload_conf();
```

### SSL/TLS

```bash
# Enable SSL in PostgreSQL
# In postgresql.conf:
ssl = on
ssl_cert_file = '/var/lib/postgresql/server.crt'
ssl_key_file = '/var/lib/postgresql/server.key'

# Application connection string
jdbc:postgresql://host:5432/starexec?ssl=true&sslmode=require
```

---

## Monitoring

### Health Checks

```sql
-- Simple health check
SELECT 1;

-- Connection count
SELECT count(*) FROM pg_stat_activity;

-- Replication lag (if applicable)
SELECT 
  client_addr,
  state,
  sent_lsn - write_lsn AS write_lag,
  sent_lsn - flush_lsn AS flush_lag,
  sent_lsn - replay_lsn AS replay_lag
FROM pg_stat_replication;
```

### Key Metrics

```sql
-- Active connections
SELECT count(*) FROM pg_stat_activity WHERE state = 'active';

-- Cache hit ratio (should be > 99%)
SELECT 
  sum(heap_blks_hit) / (sum(heap_blks_hit) + sum(heap_blks_read)) as ratio
FROM pg_statio_user_tables;

-- Transaction rate
SELECT xact_commit + xact_rollback as total_transactions
FROM pg_stat_database WHERE datname = 'starexec';

-- Deadlocks
SELECT deadlocks FROM pg_stat_database WHERE datname = 'starexec';
```

---

## High Availability

### Streaming Replication Setup

Primary server:

```sql
-- Create replication user
CREATE USER replicator WITH REPLICATION PASSWORD 'repl-password';

-- postgresql.conf
wal_level = replica
max_wal_senders = 3
wal_keep_size = 1GB
```

Standby server:

```bash
# Initialize from primary
pg_basebackup -h primary -U replicator -D /var/lib/postgresql/data -P

# standby.signal file
touch /var/lib/postgresql/data/standby.signal

# postgresql.conf
primary_conninfo = 'host=primary user=replicator password=repl-password'
```

### Failover

```bash
# Promote standby to primary
podman exec postgres-standby pg_ctl promote
```

---

## Related Documentation

- **[Configuration Reference](CONFIGURATION.md)** - Database environment variables
- **[Volume Management](VOLUMES.md)** - Database backups with volumes
- **[Deployment Guide](DEPLOYMENT.md)** - Initial database setup
- **[Troubleshooting](TROUBLESHOOTING.md)** - Common database issues