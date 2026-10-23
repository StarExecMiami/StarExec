# Observability Guide

Complete guide to logging, monitoring, and debugging StarExec.

## Overview

This guide covers:

- Log locations and formats
- Debugging techniques
- Metrics collection
- Health checks
- Tracing job execution

---

## Quick Reference

```bash
# View all logs
make logs

# View application logs
make logs-app

# View database logs
make logs-postgres

# Check system status
make status

# Database health check
make db-shell
SELECT 1;
```

---

## Logging

### Log Locations

#### Podman Deployment

| Component | Access Method |
|-----------|---------------|
| Application | `make logs-app` or `podman logs starexec-app` |
| PostgreSQL | `make logs-postgres` or `podman logs starexec-postgres` |
| Job containers | `podman logs starexec-job-<pair_id>` |
| Combined | `make logs` |

#### Kubernetes Deployment

```bash
# Application logs
kubectl logs -n starexec -l app.kubernetes.io/name=starexec -f

# Database logs
kubectl logs -n starexec -l app.kubernetes.io/name=starexec -c postgres -f

# All pods
kubectl logs -n starexec --all-containers -f

# Previous container (after crash)
kubectl logs -n starexec <pod-name> --previous
```

#### File Locations (Inside Container)

| Log | Path |
|-----|------|
| Application | `/var/log/starexec/starexec.log` |
| Tomcat access | `/var/log/tomcat/access.log` |
| Job output | `/starexec/output/<job_id>/` |

### Log Levels

StarExec uses Logback for logging. Configure in `logback.xml`:

```xml
<!-- Root level -->
<root level="INFO">
  <appender-ref ref="CONSOLE"/>
  <appender-ref ref="FILE"/>
</root>

<!-- Package-specific levels -->
<logger name="org.starexec" level="INFO"/>
<logger name="org.starexec.backend" level="DEBUG"/>
<logger name="org.starexec.data" level="INFO"/>
<logger name="org.starexec.jobs" level="DEBUG"/>

<!-- SQL logging (verbose) -->
<logger name="org.hibernate.SQL" level="DEBUG"/>
<logger name="org.hibernate.type" level="TRACE"/>
```

### Changing Log Level at Runtime

```bash
# Via environment variable
export LOGGING_LEVEL_ORG_STAREXEC=DEBUG
make stop && make start

# Edit logback.xml and restart
podman exec starexec-app vi /WEB-INF/classes/logback.xml
podman restart starexec-app
```

### Log Format

Default format:

```
2025-01-15 10:30:45.123 [thread-name] LEVEL class.Name - Message
```

Example log entries:

```
2025-01-15 10:30:45.123 [main] INFO  o.s.app.StarExec - StarExec starting...
2025-01-15 10:30:46.456 [main] INFO  o.s.backend.PodmanBackend - Backend initialized
2025-01-15 10:30:47.789 [job-worker-1] DEBUG o.s.jobs.JobManager - Submitting job pair 12345
2025-01-15 10:30:48.012 [monitor] INFO  o.s.backend.ContainerJobMonitor - Job 12345 completed
```

### Log Rotation

Configure log rotation in `logback.xml`:

```xml
<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
  <file>/var/log/starexec/starexec.log</file>
  <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <fileNamePattern>/var/log/starexec/starexec-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
    <maxFileSize>100MB</maxFileSize>
    <maxHistory>30</maxHistory>
    <totalSizeCap>3GB</totalSizeCap>
  </rollingPolicy>
  <encoder>
    <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
  </encoder>
</appender>
```

---

## Debugging

### Debug Mode

Enable comprehensive debugging:

```bash
# Set environment
export STAREXEC_DEBUG=true
export LOGGING_LEVEL_ORG_STAREXEC=DEBUG

# Restart
make stop && make start

# Tail logs
make logs-app | grep -E "(DEBUG|ERROR|WARN)"
```

### Common Debug Scenarios

#### Job Not Making Progress (pairs stuck before running)

StarExec reports a job that has stopped making progress: no pair of it has started or finished for longer than
its wallclock limit plus 10 minutes, and for at least 30 minutes. Paused, killed and deleted jobs are not
reported. The report is one WARN line, written to the app log and to the `error_logs` table, and repeated every
6 hours while the job stays stuck. It names the likely cause, for example:

```bash
make logs-app | grep "has made no progress"
# Job 42 (my-job) has made no progress for 1440 minutes (35 pending, 0 enqueued, 0 running, 0 awaiting result processing): its queue 'default' is INACTIVE, so its pending pairs cannot be dispatched
```

```bash
# Check that the backend started (the line depends on the backend)
make logs-app | grep -E "LocalBackend initialized|KubernetesNativeBackend initialized|ContainerJobMonitor started"

# Podman backend only: one line per submitted pair
make logs-app | grep "Submitting job"

# Query database: pairs not running yet (1 = pending submission, 2 = enqueued) and their queue
make db-shell
SELECT p.id, p.job_id, p.status_code, p.queuesub_time, q.name AS queue, q.status AS queue_status
FROM starexec.job_pairs p
JOIN starexec.jobs j ON j.id = p.job_id
LEFT JOIN starexec.queues q ON q.id = j.queue_id
WHERE p.status_code IN (1, 2)
ORDER BY p.id
LIMIT 20;
```

A pending pair (code 1) is never dispatched while its job's queue is not `ACTIVE`, or no longer exists.

#### Job Stuck in RUNNING

```bash
# Check container status
podman ps | grep starexec-job

# Check container logs
podman logs starexec-job-<pair_id>

# Check monitor is running
make logs-app | grep ContainerJobMonitor

# Check for completion polling
make logs-app | grep "completed containers"
```

#### Container Won't Start

```bash
# Check container creation
make logs-app | grep "Creating container"

# Check Podman events
podman events --filter type=container | head -50

# Inspect failed container
podman inspect starexec-job-<pair_id>

# Check resource limits
podman stats --no-stream
```

#### Database Connection Issues

```bash
# Test connection
make db-shell

# Check connection pool
make logs-app | grep -i "connection\|pool"

# Check PostgreSQL logs
make logs-postgres

# Verify credentials
echo $STAREXEC_DB_PASSWORD
```

### Enabling SQL Query Logging

```xml
<!-- In logback.xml -->
<logger name="org.hibernate.SQL" level="DEBUG"/>
<logger name="org.hibernate.type.descriptor.sql.BasicBinder" level="TRACE"/>
```

Or via JDBC URL:

```
jdbc:postgresql://host:5432/starexec?loggerLevel=DEBUG
```

### Thread Dump

```bash
# Get Java thread dump
podman exec starexec-app jstack 1 > thread-dump.txt

# Find blocked threads
grep -A 20 "BLOCKED" thread-dump.txt

# Find waiting threads
grep -A 20 "WAITING" thread-dump.txt
```

### Heap Dump

```bash
# Generate heap dump
podman exec starexec-app jmap -dump:format=b,file=/tmp/heap.hprof 1

# Copy out of container
podman cp starexec-app:/tmp/heap.hprof ./heap.hprof

# Analyze with Eclipse MAT, VisualVM, or jhat
jhat heap.hprof
```

---

## Health Checks

### Application Health

```bash
# Check if application is responding
curl -s http://localhost:7827/starexec/ | head -20

# Check specific endpoint
curl -s http://localhost:7827/starexec/services/session/logged-in

# Makefile status
make status
```

### Database Health

```bash
# Quick check
make db-shell
SELECT 1;

# Connection count
SELECT count(*) FROM pg_stat_activity;

# Long-running queries
SELECT pid, now() - query_start as duration, query
FROM pg_stat_activity
WHERE state = 'active' AND now() - query_start > interval '10 seconds';

# Replication status (if applicable)
SELECT * FROM pg_stat_replication;
```

### Container Health

```bash
# Check all containers
podman ps -a | grep starexec

# Container health
podman healthcheck run starexec-app

# Resource usage
podman stats --no-stream starexec-app starexec-postgres
```

### Kubernetes Probes

```yaml
# Helm values for health probes
livenessProbe:
  httpGet:
    path: /starexec/public/health/liveness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 30
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /starexec/public/health/readiness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3
```

### Health Check Script

```bash
#!/bin/bash
# healthcheck.sh

# Check application
if ! curl -sf http://localhost:7827/starexec/ > /dev/null; then
  echo "CRITICAL: Application not responding"
  exit 2
fi

# Check database
if ! podman exec starexec-postgres pg_isready > /dev/null 2>&1; then
  echo "CRITICAL: Database not ready"
  exit 2
fi

# Check disk space
DISK_USAGE=$(df / --output=pcent | tail -1 | tr -d '% ')
if [ "$DISK_USAGE" -gt 90 ]; then
  echo "WARNING: Disk usage at ${DISK_USAGE}%"
  exit 1
fi

# Check memory
MEM_USAGE=$(free | grep Mem | awk '{print int($3/$2 * 100)}')
if [ "$MEM_USAGE" -gt 90 ]; then
  echo "WARNING: Memory usage at ${MEM_USAGE}%"
  exit 1
fi

echo "OK: All checks passed"
exit 0
```

---

## Metrics Collection

### Built-in Metrics

Query via the `getStats()` method on backends. These statistics are internal to
the application and are not currently exposed over HTTP (there is no
`/services/admin/backend-stats` endpoint).

LocalBackend stats:
- `activeJobs`: Currently running + queued
- `runningJobs`: Actively executing
- `queuedJobs`: Waiting for worker thread
- `completedJobs`: Successful executions (cumulative)
- `failedJobs`: Failed executions (cumulative)
- `threadPoolActiveThreads`: Currently busy threads
- `threadPoolQueueSize`: Pending jobs in queue

### Prometheus Integration (Future)

Expose metrics endpoint for Prometheus:

```yaml
# Helm values
metrics:
  enabled: true
  port: 9090
  path: /metrics
```

Example metrics:

```
# HELP starexec_jobs_total Total number of jobs processed
# TYPE starexec_jobs_total counter
starexec_jobs_total{status="completed"} 12345
starexec_jobs_total{status="failed"} 123

# HELP starexec_queue_depth Current job queue depth
# TYPE starexec_queue_depth gauge
starexec_queue_depth 42

# HELP starexec_job_duration_seconds Job execution duration
# TYPE starexec_job_duration_seconds histogram
starexec_job_duration_seconds_bucket{le="60"} 1000
starexec_job_duration_seconds_bucket{le="300"} 5000
starexec_job_duration_seconds_bucket{le="600"} 8000
```

### Database Metrics

```sql
-- Connection metrics
SELECT 
  count(*) as total_connections,
  count(*) FILTER (WHERE state = 'active') as active,
  count(*) FILTER (WHERE state = 'idle') as idle
FROM pg_stat_activity;

-- Transaction metrics
SELECT 
  xact_commit as commits,
  xact_rollback as rollbacks,
  blks_read as blocks_read,
  blks_hit as blocks_hit,
  tup_returned as rows_returned,
  tup_fetched as rows_fetched,
  tup_inserted as rows_inserted,
  tup_updated as rows_updated,
  tup_deleted as rows_deleted
FROM pg_stat_database WHERE datname = 'starexec';

-- Cache hit ratio
SELECT 
  round(100.0 * sum(blks_hit) / (sum(blks_hit) + sum(blks_read)), 2) as cache_hit_ratio
FROM pg_stat_database WHERE datname = 'starexec';
```

### System Metrics

```bash
# CPU usage
top -bn1 | head -5

# Memory usage
free -h

# Disk usage
df -h

# Disk I/O
iostat -x 1 5

# Network
ss -tuln | grep -E "(7827|5432)"

# Container resource usage
podman stats --no-stream
```

---

## Tracing Job Execution

### Job Lifecycle Tracing

```bash
# Trace job from submission to completion
JOB_ID=12345

# 1. Find job in logs
make logs-app | grep "job.*$JOB_ID"

# 2. Check database state
make db-shell
SELECT * FROM jobs WHERE id = $JOB_ID;
SELECT * FROM job_pairs WHERE job_id = $JOB_ID;

# 3. Check container (if running)
podman ps | grep $JOB_ID
podman logs starexec-job-$JOB_ID

# 4. Check output files
ls -la /starexec/output/$JOB_ID/
cat /starexec/output/$JOB_ID/*/status.json
cat /starexec/output/$JOB_ID/*/stats.json
```

### Job Pair Status Codes

From `Status.StatusCode` in `starexec-app/src/main/java/org/starexec/data/to/Status.java`. Codes 3, 5 and 6 are
not used. "Complete" is how job statistics count the pair.

| Code | Name | Shown as | Meaning | Complete |
|------|------|----------|---------|----------|
| 0 | STATUS_UNKNOWN | unknown | Not set | no |
| 1 | STATUS_PENDING_SUBMIT | pending submission | In the database, not yet submitted to the backend | no |
| 2 | STATUS_ENQUEUED | enqueued | Submitted, waiting for an execution host | no |
| 4 | STATUS_RUNNING | running | Running | no |
| 7 | STATUS_COMPLETE | complete | Finished and its results recorded | yes |
| 8 | ERROR_SGE_REJECT | rejected | The backend rejected the submission | no |
| 9 | ERROR_SUBMIT_FAIL | submit failed | Submitting it to the backend failed | no |
| 10 | ERROR_RESULTS | results error | Ran, but its statistics or attributes could not be read | no |
| 11 | ERROR_RUNSCRIPT | run script error | No valid run script | no |
| 12 | ERROR_BENCHMARK | benchmark error | Benchmark not found | no |
| 13 | ERROR_DISK_QUOTA_EXCEEDED | environment error | Its execution environment could not be set up | no |
| 14 | EXCEED_RUNTIME | timeout (wallclock) | Exceeded its wallclock limit | yes |
| 15 | EXCEED_CPU | timeout (cpu) | Exceeded its CPU time limit | yes |
| 16 | EXCEED_FILE_WRITE | file write exceeded | Exceeded its file write limit | yes |
| 17 | EXCEED_MEM | memout | Exceeded its virtual memory limit | yes |
| 18 | ERROR_GENERAL | error | An unknown error somewhere in the execution pipeline | no |
| 19 | STATUS_PROCESSING_RESULTS | processing results | Its results are being processed | no |
| 20 | STATUS_PAUSED | paused | Its job was paused before it finished | no |
| 21 | STATUS_KILLED | killed | Its job was killed before it finished | no |
| 22 | STATUS_PROCESSING | awaiting processing | Waiting for a new post-processor run | no |
| 23 | STATUS_NOT_REACHED | stage not reached | An earlier stage failed | no |
| 24 | ERROR_BENCH_DEPENDENCY_MISSING | benchmark dependency missing | A benchmark dependency is missing | no |
| 25 | ERROR_PRE_PROCESSOR | pre-processor error | The pre-processor failed, so the solver did not run | yes |
| 26 | ERROR_POST_PROCESSOR | post-processor error | The post-processor failed | yes |

### Container Lifecycle

```bash
# Watch container events
podman events --filter type=container

# Container lifecycle stages:
# 1. create - Container created
# 2. start - Container started
# 3. attach - I/O attached
# 4. die - Container exited
# 5. remove - Container removed
```

### Debugging Job Output

```bash
# Check runsolver output
cat /starexec/output/<job_id>/<pair_id>/watcher.out

# Check solver stdout
cat /starexec/output/<job_id>/<pair_id>/var.out

# Check job statistics
cat /starexec/output/<job_id>/<pair_id>/stats.json

# Check job status
cat /starexec/output/<job_id>/<pair_id>/status.json
```

---

## Alerting

### Log-Based Alerts

Monitor logs for critical patterns:

```bash
# Error monitoring
tail -f /var/log/starexec/starexec.log | grep -E "ERROR|FATAL|Exception"

# OOM detection
podman events --filter event=oom

# Database connection issues
tail -f logs | grep -i "connection refused\|authentication failed"
```

### Alert Examples

#### Systemd Service Failure Alert

```bash
# /etc/systemd/system/starexec-alert.service
[Unit]
Description=StarExec failure alert

[Service]
Type=oneshot
ExecStart=/usr/local/bin/send-alert.sh "StarExec service failed"
```

#### Cron-Based Health Check

```bash
# /etc/cron.d/starexec-health
*/5 * * * * starexec /opt/starexec/healthcheck.sh || /usr/local/bin/send-alert.sh "StarExec health check failed"
```

### Integration with Monitoring Systems

#### Nagios/Icinga

```bash
#!/bin/bash
# check_starexec.sh
if curl -sf http://localhost:7827/starexec/ > /dev/null; then
  echo "OK - StarExec responding"
  exit 0
else
  echo "CRITICAL - StarExec not responding"
  exit 2
fi
```

#### Datadog

```yaml
# datadog.yaml
logs:
  - type: file
    path: /var/log/starexec/starexec.log
    service: starexec
    source: java
```

---

## Diagnostic Collection

### Collecting Diagnostics for Bug Reports

```bash
#!/bin/bash
# collect-diagnostics.sh

OUTPUT_DIR="starexec-diagnostics-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUTPUT_DIR"

# System info
uname -a > "$OUTPUT_DIR/system.txt"
cat /etc/os-release >> "$OUTPUT_DIR/system.txt"

# StarExec version
git rev-parse HEAD > "$OUTPUT_DIR/version.txt" 2>/dev/null

# Configuration (redacted)
make config-show ENV=dev 2>&1 | sed 's/password=.*/password=REDACTED/' > "$OUTPUT_DIR/config.txt"

# Container status
podman ps -a > "$OUTPUT_DIR/containers.txt"
podman images > "$OUTPUT_DIR/images.txt"

# Logs (last 1000 lines)
make logs 2>&1 | tail -1000 > "$OUTPUT_DIR/logs.txt"

# Database state
make db-shell << EOF > "$OUTPUT_DIR/database.txt"
SELECT version();
\dt
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
SELECT status_code, COUNT(*) FROM jobs GROUP BY status_code;
SELECT status_code, COUNT(*) FROM job_pairs GROUP BY status_code;
EOF

# Resource usage
free -h > "$OUTPUT_DIR/memory.txt"
df -h > "$OUTPUT_DIR/disk.txt"
podman stats --no-stream > "$OUTPUT_DIR/container-stats.txt"

# Package it
tar -czf "$OUTPUT_DIR.tar.gz" "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR"

echo "Diagnostics collected: $OUTPUT_DIR.tar.gz"
```

---

## Best Practices

### Logging Best Practices

1. **Use appropriate log levels**
   - ERROR: System failures requiring immediate attention
   - WARN: Potential problems that don't require immediate action
   - INFO: Normal operational events
   - DEBUG: Detailed information for troubleshooting

2. **Include context in log messages**
   ```java
   log.info("Job {} submitted by user {} to space {}", jobId, userId, spaceId);
   ```

3. **Don't log sensitive data**
   - Passwords
   - API keys
   - Personal information

4. **Configure log rotation**
   - Prevent disk exhaustion
   - Keep sufficient history for debugging

### Monitoring Best Practices

1. **Set up baseline metrics** before production
2. **Alert on symptoms**, not causes
3. **Use dashboards** for visual monitoring
4. **Automate health checks**
5. **Document runbooks** for common alerts

### Live Log Streaming Monitoring Checklist

If using `/services/jobs/pairs/{id}/log/stream`, track these signals to ensure
streaming does not degrade benchmark execution:

1. **Active stream saturation**
   - Warning: active streams > 70% of `STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE` for 5m
   - Critical: > 90% for 2m

2. **Rate limiting pressure**
   - Warning: HTTP 429 rate for stream endpoint > 1%
   - Critical: > 5% sustained for 5m

3. **API responsiveness**
   - Warning: non-stream API p95 latency > 20% above baseline
   - Critical: > 50% above baseline

4. **Benchmark throughput regression**
   - Warning: pair throughput < 95% of baseline
   - Critical: < 90% of baseline

5. **Servlet/thread pressure**
   - Warning: busy request threads > 75%
   - Critical: > 90%

**Tuning order for incidents:**
1) reduce `STAREXEC_PAIR_LOG_STREAM_MAX_ACTIVE`
2) increase `STAREXEC_PAIR_LOG_STREAM_POLL_INTERVAL_MS`
3) increase `STAREXEC_PAIR_LOG_STREAM_STATUS_POLL_INTERVAL_MS`
4) increase `STAREXEC_PAIR_LOG_STREAM_HEARTBEAT_SECONDS` / reduce max duration

### Debugging Best Practices

1. **Check logs first** - most issues leave traces
2. **Reproduce in development** when possible
3. **Collect diagnostics immediately** after issues
4. **Document findings** for future reference

---

## Related Documentation

- **[Troubleshooting](TROUBLESHOOTING.md)** - Common issues and solutions
- **[Performance Tuning](PERFORMANCE.md)** - Optimization guide
- **[Backend Configuration](BACKENDS.md)** - Backend-specific logging
- **[Database Guide](DATABASE.md)** - Database monitoring
