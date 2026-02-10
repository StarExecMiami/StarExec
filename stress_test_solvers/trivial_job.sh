#!/bin/bash
# Trivial Job Solver
# Purpose: Minimal job that completes instantly to measure pure overhead
# Expected duration: <1 second

START_TIME=$(date +%s.%N)

echo "starexec-trivial-job: started at $(date -Iseconds)"
echo "starexec-trivial-job: hostname=$(hostname)"
echo "starexec-trivial-job: pid=$$"

# Output required result
echo "starexec-result=TRIVIAL_DONE"

END_TIME=$(date +%s.%N)
DURATION=$(echo "$END_TIME - $START_TIME" | bc 2>/dev/null || echo "unknown")

echo "starexec-trivial-job: completed in ${DURATION}s"
exit 0
