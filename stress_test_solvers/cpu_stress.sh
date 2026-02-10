#!/bin/bash
# StarExec Stress Test Solver: CPU Intensive
# Purpose: Stresses raw processing power with a CPU-bound busy loop
# Expected runtime: ~20 seconds (configurable via STRESS_DURATION)

set -e

# Configuration
DURATION=${STRESS_DURATION:-20}  # Duration in seconds
START_TIME=$(date +%s)

echo "starexec-cpu-stress: starting CPU stress test"
echo "starexec-cpu-stress: duration=${DURATION}s"
echo "starexec-cpu-stress: pid=$$"
echo "starexec-cpu-stress: hostname=$(hostname)"

# CPU-intensive loop - pure computation
count=0
end=$((SECONDS + DURATION))
while [ $SECONDS -lt $end ]; do
    # Busy loop with some arithmetic to keep CPU busy
    for i in {1..1000}; do
        count=$((count + i * 2 - i))
    done
done

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo "starexec-cpu-stress: completed"
echo "starexec-cpu-stress: iterations=$count"
echo "starexec-cpu-stress: elapsed=${ELAPSED}s"
echo "starexec-result=CPU_DONE"

exit 0
