#!/bin/bash
# StarExec Long-Running Job Solver
# =================================
# Runs for an extended period to test queue management, timeout handling,
# and long-running job monitoring.
#
# Environment Variables:
#   STRESS_LONG_DURATION - Sleep duration in seconds (default: 60)
#   STRESS_REPORT_INTERVAL - Progress report interval in seconds (default: 10)

DURATION="${STRESS_LONG_DURATION:-60}"
REPORT_INTERVAL="${STRESS_REPORT_INTERVAL:-10}"

echo "starexec-long-running: starting"
echo "  Duration: ${DURATION}s"
echo "  Report interval: ${REPORT_INTERVAL}s"
echo "  PID: $$"
echo "  Hostname: $(hostname)"
echo "  Started at: $(date -Iseconds)"
echo ""

START_TIME=$(date +%s)
ELAPSED=0

# Progress loop
while [ $ELAPSED -lt $DURATION ]; do
    # Calculate remaining time
    REMAINING=$((DURATION - ELAPSED))
    PROGRESS=$((100 * ELAPSED / DURATION))

    echo "starexec-long-running: progress ${PROGRESS}% (${ELAPSED}s elapsed, ${REMAINING}s remaining)"

    # Sleep for the report interval (or remaining time if less)
    if [ $REMAINING -lt $REPORT_INTERVAL ]; then
        SLEEP_TIME=$REMAINING
    else
        SLEEP_TIME=$REPORT_INTERVAL
    fi

    sleep $SLEEP_TIME

    # Update elapsed time
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
done

END_TIME=$(date +%s)
ACTUAL_DURATION=$((END_TIME - START_TIME))

echo ""
echo "starexec-long-running: completed"
echo "  Finished at: $(date -Iseconds)"
echo "  Actual duration: ${ACTUAL_DURATION}s"
echo "  Target duration: ${DURATION}s"
echo ""
echo "starexec-result=LONG_DONE"
exit 0
