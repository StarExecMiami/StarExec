# Performance Tuning Guide

Complete guide to optimizing StarExec for maximum throughput and efficiency.

## Overview

This guide covers:

- Capacity planning
- Resource tuning
- Backend optimization
- Database performance
- Monitoring and bottleneck identification

---

## Quick Reference

### Key Performance Parameters

| Parameter | Default | Tuning Goal |
|-----------|---------|-------------|
| `STAREXEC_LOCAL_CONCURRENCY` | 4 | Match CPU cores (up to 16) |
| `STAREXEC_NUM_JOB_PAIRS_AT_A_TIME` | 5 | Keep queue 50-100 deep |
| `STAREXEC_CONTAINER_MONITOR_POLL_MS` | 5000 | Lower for latency, higher for scale |
| `STAREXEC_CONTAINER_DEFAULT_MEMORY_MB` | 4096 | Match solver requirements |
| `STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT` | 1 | 1 for single-threaded, more for parallel |

### Live Log Streaming Parameters (SSE)

| Parameter | Default | Benchmark-priority recommendation |
|-----------|---------|-----------------------------------|
| `STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE` | 100 | 30 |
| `STAREXEC_PAIR_LOG_STREAM_POLL_INTERVAL_MS` | 1000 | 2500 |
| `STAREXEC_PAIR_LOG_STREAM_STATUS_POLL_INTERVAL_MS` | 5000 | 15000 |
| `STAREXEC_PAIR_LOG_STREAM_HEARTBEAT_SECONDS` | 15 | 25 |
| `STAREXEC_PAIR_LOG_STREAM_MAX_DURATION_SECONDS` | 1800 | 900 |
| `STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES` | 8192 | 8192 |

### Performance Targets

| Metric | Target | Critical |
|--------|--------|----------|
| CPU utilization | 70-90% | > 95% (overloaded) |
| Memory utilization | 60-80% | > 90% (risk OOM) |
| Queue depth | 50-100 | > 500 (backlog) |
| Job timeout rate | < 1% | > 5% (timeout too low) |
| Database response | < 50ms | > 200ms (bottleneck) |

### Live Log Streaming Guardrails

When live log streaming is enabled, protect benchmark throughput with these goals:

| Metric | Target | Critical |
|--------|--------|----------|
| Active streams / max | < 70% sustained | > 90% sustained |
| Stream 429 rate | < 1% | > 5% sustained |
| Non-stream API p95 latency | < +20% vs baseline | > +50% vs baseline |
| Benchmark pair throughput | >= 95% of baseline | < 90% of baseline |

**Overhead model (rough):**
- File polls/sec ≈ `activeStreams / (POLL_INTERVAL_MS / 1000)`
- Status DB QPS ≈ `activeStreams / (STATUS_POLL_INTERVAL_MS / 1000)`
- Max stream egress ≈ `activeStreams × READ_CHUNK_BYTES / (POLL_INTERVAL_MS / 1000)`

---

## Capacity Planning

### Hardware Requirements by Workload

| Workload | CPU | Memory | Storage | Network |
|----------|-----|--------|---------|---------|
| Development | 4 cores | 8 GB | 50 GB SSD | 1 Gbps |
| Small (< 100 jobs/day) | 8 cores | 16 GB | 100 GB SSD | 1 Gbps |
| Medium (< 1K jobs/day) | 16 cores | 32 GB | 500 GB SSD | 10 Gbps |
| Large (< 10K jobs/day) | 32 cores | 64 GB | 1 TB NVMe | 10 Gbps |
| Competition (> 10K jobs/day) | 64+ cores | 128+ GB | 2+ TB NVMe | 10+ Gbps |

### Throughput Estimates

#### Local Backend

| Cores | Concurrency | Jobs/Hour (10-min) | Jobs/Day |
|-------|-------------|-------------------|----------|
| 4 | 4 | 24 | 576 |
| 8 | 8 | 48 | 1,152 |
| 16 | 16 | 96 | 2,304 |
| 32 | 16 | 96 | 2,304 |

*Note: Local backend maxes out at 16 concurrent jobs*

#### Podman Backend

| System | Containers | Jobs/Hour (10-min) | Jobs/Day |
|--------|------------|-------------------|----------|
| 16-core, 64GB | 16 | 96 | 2,304 |
| 32-core, 128GB | 32 | 192 | 4,608 |
| 64-core, 256GB | 64 | 384 | 9,216 |

#### Kubernetes Backend

| Nodes | Pods/Node | Total Pods | Jobs/Hour (10-min) |
|-------|-----------|------------|-------------------|
| 3 | 16 | 48 | 288 |
| 5 | 32 | 160 | 960 |
| 10 | 64 | 640 | 3,840 |

### Storage Planning

| Component | Size Estimate | Growth Rate |
|-----------|---------------|-------------|
| Solver (avg) | 50-500 MB | Per upload |
| Benchmark set | 10-100 MB | Per upload |
| Job output | 1-10 MB | Per job pair |
| Database row | ~1 KB | Per job pair |

**Example calculation (1 year, 10K jobs/day):**
- Job outputs: 10K × 5MB × 365 = 18 TB
- Database: 10K × 1KB × 365 = 3.6 GB
- Total: ~20 TB (plan for 50 TB with headroom)

---

## Backend Tuning

### Local Backend

#### Concurrency Optimization

```bash
# Check CPU cores
nproc

# Set concurrency (rule: cores - 2 for system overhead)
export STAREXEC_LOCAL_CONCURRENCY=$(( $(nproc) - 2 ))

# For memory-bound solvers, reduce further
export STAREXEC_LOCAL_CONCURRENCY=$(( $(nproc) / 2 ))
```

#### Timeout Tuning

```bash
# Default: 1 hour
export STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS=3600

# For quick benchmarks (SAT competition)
export STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS=300

# For long-running jobs (SMT-LIB)
export STAREXEC_LOCAL_JOB_TIMEOUT_SECONDS=7200
```

### Podman Backend

#### Batch Size Optimization

```bash
# Default batch size
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=5

# High-throughput (lots of short jobs)
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=10

# Memory-bound (fewer large jobs)
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=3
```

#### Poll Interval Tuning

```bash
# Default: 5 seconds
export STAREXEC_CONTAINER_MONITOR_POLL_MS=5000

# Low latency (fast result visibility)
export STAREXEC_CONTAINER_MONITOR_POLL_MS=1000

# High scale (reduce CPU overhead)
export STAREXEC_CONTAINER_MONITOR_POLL_MS=10000
```

**Tradeoffs:**

| Poll Interval | CPU Overhead | Result Latency | Best For |
|---------------|--------------|----------------|----------|
| 500ms | ~5% | < 1s | Interactive use |
| 2000ms | ~2% | 2s | Balanced |
| 5000ms | ~1% | 5s | Default |
| 10000ms | ~0.5% | 10s | High scale |
| 30000ms | ~0.2% | 30s | Batch processing |

#### Container Resource Limits

```bash
# Memory per container
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=4096

# Calculate total memory needed:
# MEMORY_NEEDED = NUM_JOB_PAIRS_AT_A_TIME × CONTAINER_DEFAULT_MEMORY_MB
# Example: 5 × 4096 = 20GB

# CPU per container
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1

# For parallel solvers
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=4
```

### Resource Limit Profiles

#### Lightweight Solvers (SMT QF_BV)

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=2048
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=300
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=8
```

#### Standard Solvers (SAT)

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=4096
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=1
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=600
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=5
```

#### Heavy Solvers (QF_NIA, LIA)

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=8192
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=2
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=1200
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=4
```

#### Parallel Solvers (CaDiCaL, Lingeling)

```bash
export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=16384
export STAREXEC_CONTAINER_DEFAULT_CPU_LIMIT=8
export STAREXEC_CONTAINER_DEFAULT_WALLCLOCK_LIMIT=3600
export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=2
```

---

## Database Performance

### Connection Pool Tuning

```yaml
# Helm values
datasource:
  maximumPoolSize: 20      # Max connections
  minimumIdle: 5           # Min idle connections
  connectionTimeout: 30000 # Connection timeout (ms)
  idleTimeout: 600000      # Idle connection timeout (ms)
```

**Sizing guide:**

| Workload | Pool Size | Minimum Idle |
|----------|-----------|--------------|
| Development | 5 | 2 |
| Small | 10 | 3 |
| Medium | 20 | 5 |
| Large | 50 | 10 |

### PostgreSQL Configuration

Edit `postgresql.conf`:

```ini
# Memory (adjust to 25% of RAM)
shared_buffers = 4GB

# Effective cache (adjust to 75% of RAM)
effective_cache_size = 12GB

# Work memory (per-operation)
work_mem = 64MB

# Maintenance operations
maintenance_work_mem = 512MB

# Write-ahead log
wal_buffers = 64MB
checkpoint_completion_target = 0.9
max_wal_size = 4GB

# Query planning (for SSD)
random_page_cost = 1.1
effective_io_concurrency = 200

# Connections
max_connections = 100

# Logging (for debugging)
log_min_duration_statement = 1000  # Log queries > 1s
```

### Index Optimization

```sql
-- Check for missing indexes
SELECT 
  schemaname, tablename,
  seq_scan, seq_tup_read,
  idx_scan, idx_tup_fetch
FROM pg_stat_user_tables
WHERE seq_scan > 1000 AND seq_tup_read / seq_scan > 1000
ORDER BY seq_tup_read DESC;

-- Add common indexes
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_pairs_status 
ON job_pairs(status_code);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_pairs_job_id 
ON job_pairs(job_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_status_created 
ON jobs(status_code, created);
```

### Query Optimization

```sql
-- Find slow queries
SELECT query, calls, mean_time, total_time
FROM pg_stat_statements
ORDER BY mean_time DESC
LIMIT 20;

-- Check cache hit ratio (should be > 99%)
SELECT 
  sum(heap_blks_hit) / (sum(heap_blks_hit) + sum(heap_blks_read)) * 100 
  AS cache_hit_ratio
FROM pg_statio_user_tables;
```

### Regular Maintenance

```sql
-- Vacuum and analyze (run weekly)
VACUUM ANALYZE;

-- Full vacuum (run monthly, requires downtime)
VACUUM FULL;

-- Reindex (if index bloat detected)
REINDEX DATABASE starexec;
```

---

## JVM Tuning

### Heap Size

```bash
# Set JVM options
export JAVA_OPTS="-Xms2g -Xmx4g"

# For larger workloads
export JAVA_OPTS="-Xms4g -Xmx8g"
```

### Garbage Collection

```bash
# G1GC (recommended for heap > 4GB)
export JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
export JAVA_OPTS="$JAVA_OPTS -XX:MaxGCPauseMillis=200"

# ZGC (for very low latency, Java 17+)
export JAVA_OPTS="$JAVA_OPTS -XX:+UseZGC"
```

### Monitoring JVM

```bash
# Enable JMX for monitoring
export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote"
export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=9010"
export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.ssl=false"
```

---

## System Tuning

### Linux Kernel Parameters

```bash
# /etc/sysctl.conf

# Increase file descriptors
fs.file-max = 2097152

# Network tuning
net.core.somaxconn = 65535
net.core.netdev_max_backlog = 65535
net.ipv4.tcp_max_syn_backlog = 65535

# Memory overcommit (careful with this)
vm.overcommit_memory = 1
vm.swappiness = 10
```

### File Descriptor Limits

```bash
# /etc/security/limits.conf
starexec soft nofile 65535
starexec hard nofile 65535
starexec soft nproc 65535
starexec hard nproc 65535
```

### Disk I/O

```bash
# Check current scheduler
cat /sys/block/sda/queue/scheduler

# For SSDs, use 'none' or 'mq-deadline'
echo none > /sys/block/sda/queue/scheduler

# Increase read-ahead for sequential workloads
blockdev --setra 4096 /dev/sda
```

---

## Monitoring

### Key Metrics to Track

#### System Metrics

| Metric | Healthy | Warning | Critical |
|--------|---------|---------|----------|
| CPU usage | 50-80% | 80-90% | > 95% |
| Memory usage | 50-70% | 70-85% | > 90% |
| Disk I/O wait | < 5% | 5-15% | > 20% |
| Disk usage | < 70% | 70-85% | > 90% |

#### Application Metrics

| Metric | Healthy | Warning | Critical |
|--------|---------|---------|----------|
| Queue depth | 50-100 | 200-500 | > 1000 |
| Job completion rate | Stable | Declining | Zero |
| Error rate | < 1% | 1-5% | > 10% |
| Response time | < 500ms | 500ms-2s | > 5s |

#### Database Metrics

| Metric | Healthy | Warning | Critical |
|--------|---------|---------|----------|
| Active connections | < 50% max | 50-80% | > 90% |
| Cache hit ratio | > 99% | 95-99% | < 95% |
| Transaction rate | Stable | Declining | Erratic |
| Replication lag | < 1s | 1-10s | > 60s |

### Monitoring Commands

```bash
# Overall system status
make status

# Container resource usage
podman stats --no-stream

# Database connections
make db-shell
SELECT count(*) FROM pg_stat_activity WHERE state = 'active';

# Queue depth
make db-shell
SELECT COUNT(*) FROM job_pairs WHERE status_code = 1;  # PENDING

# Job throughput
make db-shell
SELECT 
  date_trunc('hour', completed) as hour,
  COUNT(*) as jobs_completed
FROM job_pairs
WHERE completed > NOW() - INTERVAL '24 hours'
GROUP BY hour
ORDER BY hour;
```

### Alerting Thresholds

```yaml
# Example Prometheus alerts
groups:
  - name: starexec
    rules:
      - alert: HighCPUUsage
        expr: cpu_usage_percent > 90
        for: 5m
        
      - alert: LowDiskSpace
        expr: disk_free_percent < 10
        for: 1m
        
      - alert: HighQueueDepth
        expr: starexec_queue_depth > 500
        for: 10m
        
      - alert: JobsNotCompleting
        expr: rate(starexec_jobs_completed[5m]) == 0
        for: 15m
```

---

## Bottleneck Identification

### Symptom: Jobs Complete Slowly

1. **Check CPU usage**
   ```bash
   top -bn1 | head -20
   ```
   - If high: Reduce concurrency or add CPU

2. **Check memory**
   ```bash
   free -h
   ```
   - If swapping: Reduce container memory or concurrency

3. **Check I/O**
   ```bash
   iostat -x 5
   ```
   - If I/O wait high: Move to SSD, reduce concurrent jobs

### Symptom: Queue Keeps Growing

1. **Check job completion rate**
   ```sql
   SELECT COUNT(*) FROM job_pairs WHERE status_code = 7;  -- COMPLETE
   ```

2. **Check for stuck jobs**
   ```sql
   SELECT COUNT(*) FROM job_pairs 
   WHERE status_code = 2  -- RUNNING
   AND start_time < NOW() - INTERVAL '2 hours';
   ```

3. **Check backend health**
   ```bash
   make logs-app | grep -i error
   ```

### Symptom: Database Slow

1. **Check active queries**
   ```sql
   SELECT pid, now() - query_start as duration, query
   FROM pg_stat_activity
   WHERE state = 'active' AND now() - query_start > interval '1 second';
   ```

2. **Check for locks**
   ```sql
   SELECT * FROM pg_locks WHERE NOT granted;
   ```

3. **Check index usage**
   ```sql
   SELECT tablename, seq_scan, idx_scan
   FROM pg_stat_user_tables
   ORDER BY seq_scan DESC;
   ```

### Symptom: Out of Memory

1. **Check container OOM kills**
   ```bash
   podman events --filter type=container --filter event=oom
   ```

2. **Reduce concurrency**
   ```bash
   export STAREXEC_NUM_JOB_PAIRS_AT_A_TIME=3
   ```

3. **Reduce per-container memory**
   ```bash
   export STAREXEC_CONTAINER_DEFAULT_MEMORY_MB=2048
   ```

---

## Performance Baseline

### Reference Benchmarks

Test system: 16-core, 64GB RAM, NVMe SSD

| Workload | Jobs | Duration | Throughput |
|----------|------|----------|------------|
| Local (4 threads) | 1,000 | 2.8 hours | 360/hour |
| Local (8 threads) | 1,000 | 1.4 hours | 720/hour |
| Podman (16 containers) | 1,000 | 1.0 hour | 1,000/hour |
| Podman (32 containers) | 1,000 | 0.5 hours | 2,000/hour |

*Jobs: 1-minute SAT problems, 2GB memory limit*

### Expected Latencies

| Operation | Expected | Acceptable | Investigate |
|-----------|----------|------------|-------------|
| Job submission | < 100ms | < 500ms | > 1s |
| Container startup | < 500ms | < 2s | > 5s |
| Result collection | < 5s | < 15s | > 30s |
| Database query | < 50ms | < 200ms | > 500ms |
| Page load | < 1s | < 3s | > 5s |

---

## Optimization Checklist

### Pre-Production

- [ ] Set appropriate concurrency for CPU count
- [ ] Configure container memory limits
- [ ] Tune database connection pool
- [ ] Set up monitoring and alerting
- [ ] Verify disk I/O performance (SSD recommended)
- [ ] Configure log rotation

### Weekly

- [ ] Review queue depth trends
- [ ] Check job completion rates
- [ ] Monitor disk space usage
- [ ] Review slow query log
- [ ] Check for OOM events

### Monthly

- [ ] Run VACUUM ANALYZE on database
- [ ] Review and update timeout settings
- [ ] Analyze job duration distribution
- [ ] Review resource limit profiles
- [ ] Test backup restore procedure

---

## Related Documentation

- **[Backend Configuration](BACKENDS.md)** - Backend-specific tuning
- **[Database Guide](DATABASE.md)** - Database optimization
- **[Configuration Reference](CONFIGURATION.md)** - All parameters
- **[Troubleshooting](TROUBLESHOOTING.md)** - Performance issues
