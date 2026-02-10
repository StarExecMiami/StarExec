#!/bin/bash
# StarExec Failing Job Solver
# ============================
# This solver intentionally fails to test error handling paths.
#
# Environment Variables:
#   STAREXEC_FAIL_EXIT_CODE - Exit code to return (default: 1)
#   STAREXEC_FAIL_MESSAGE   - Error message to output (default: "Intentional failure")
#   STAREXEC_FAIL_DELAY     - Delay before failing in seconds (default: 0)

EXIT_CODE=${STAREXEC_FAIL_EXIT_CODE:-1}
ERROR_MSG=${STAREXEC_FAIL_MESSAGE:-"Intentional failure for testing"}
DELAY=${STAREXEC_FAIL_DELAY:-0}

echo "starexec-failing-job: starting"
echo "starexec-failing-job: pid=$$"
echo "starexec-failing-job: hostname=$(hostname)"
echo "starexec-failing-job: exit_code=$EXIT_CODE"
echo "starexec-failing-job: delay=${DELAY}s"
echo ""

# Optional delay before failing
if [ "$DELAY" -gt 0 ]; then
    echo "starexec-failing-job: waiting ${DELAY}s before failure..."
    sleep "$DELAY"
fi

# Output error message to stderr (as a real failing job would)
echo "ERROR: $ERROR_MSG" >&2

# Output status for starexec parsing
echo ""
echo "starexec-failing-job: this job will now fail with exit code $EXIT_CODE"
echo "starexec-result=FAILED"

exit "$EXIT_CODE"
