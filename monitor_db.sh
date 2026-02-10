#!/bin/bash
echo "Starting database monitoring. Logging to db_metrics.csv"
echo "timestamp,pending_jobs,running_jobs" > db_metrics.csv

psql_exec() {
    podman exec starexec-app psql -U starexec -h localhost -d starexec -t -c "$1" | xargs
}

while true; do
    TIMESTAMP=$(date +%s)
    PENDING=$(psql_exec "SELECT COUNT(*) FROM job_pairs WHERE status_code = 1;")
    RUNNING=$(psql_exec "SELECT COUNT(*) FROM job_pairs WHERE status_code = 4;")
    echo "$TIMESTAMP,$PENDING,$RUNNING" >> db_metrics.csv
    sleep 1
done
