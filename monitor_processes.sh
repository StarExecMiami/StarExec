#!/bin/bash
echo "Starting process monitoring. Logging to process_metrics.csv"
echo "timestamp,user,pid,pcpu,pmem,stat,comm" > process_metrics.csv
while true; do
    TIMESTAMP=$(date +%s)
    ps -eo user,pid,pcpu,pmem,stat,comm --no-headers | grep 'runsolver\|jobscript' | while read -r line; do
        echo "$TIMESTAMP,$line" >> process_metrics.csv
    done
    sleep 1
done
