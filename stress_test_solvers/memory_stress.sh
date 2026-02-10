#!/bin/bash
# StarExec Memory Stress Test Solver
# ===================================
# Allocates and holds memory for a configurable duration to stress-test
# the system's memory management capabilities.
#
# Environment Variables:
#   STRESS_MEMORY_MB    - Amount of memory to allocate (default: 256)
#   STRESS_DURATION_SEC - How long to hold the memory (default: 20)

MEMORY_MB="${STRESS_MEMORY_MB:-256}"
DURATION_SEC="${STRESS_DURATION_SEC:-20}"

echo "starexec-memory-stress: starting"
echo "  Memory to allocate: ${MEMORY_MB}MB"
echo "  Duration: ${DURATION_SEC}s"
echo "  PID: $$"
echo "  Hostname: $(hostname)"
echo ""

# Track start time
START_TIME=$(date +%s.%N)

# Allocate memory using Python (more reliable cross-platform)
if command -v python3 &> /dev/null; then
    echo "Using Python3 for memory allocation..."
    python3 -c "
import time
import sys

memory_mb = ${MEMORY_MB}
duration = ${DURATION_SEC}

print(f'Allocating {memory_mb}MB of memory...')
try:
    # Allocate memory as a bytearray (actually consumes memory)
    data = bytearray(memory_mb * 1024 * 1024)

    # Touch the memory to ensure it's actually allocated
    for i in range(0, len(data), 4096):
        data[i] = 0xFF

    print(f'Successfully allocated {memory_mb}MB')
    print(f'Holding for {duration} seconds...')

    time.sleep(duration)

    print('Memory hold complete, releasing...')
    del data
    print('Memory released')
    sys.exit(0)
except MemoryError:
    print(f'ERROR: Failed to allocate {memory_mb}MB - not enough memory', file=sys.stderr)
    sys.exit(1)
except Exception as e:
    print(f'ERROR: {e}', file=sys.stderr)
    sys.exit(1)
"
    RESULT=$?
elif command -v python &> /dev/null; then
    echo "Using Python2 for memory allocation..."
    python -c "
import time
import sys

memory_mb = ${MEMORY_MB}
duration = ${DURATION_SEC}

print('Allocating %dMB of memory...' % memory_mb)
try:
    data = bytearray(memory_mb * 1024 * 1024)
    for i in range(0, len(data), 4096):
        data[i] = 0xFF
    print('Successfully allocated %dMB' % memory_mb)
    print('Holding for %d seconds...' % duration)
    time.sleep(duration)
    print('Memory hold complete')
    del data
    sys.exit(0)
except MemoryError:
    print('ERROR: Failed to allocate memory')
    sys.exit(1)
"
    RESULT=$?
else
    echo "WARNING: Python not available, using dd fallback (less accurate)"
    # Fallback: use dd to create memory pressure
    # This is less reliable but works without Python
    TEMP_FILE=$(mktemp)
    dd if=/dev/zero of="$TEMP_FILE" bs=1M count="$MEMORY_MB" 2>/dev/null
    # Map it into memory
    cat "$TEMP_FILE" > /dev/null &
    CAT_PID=$!
    sleep "$DURATION_SEC"
    kill $CAT_PID 2>/dev/null
    rm -f "$TEMP_FILE"
    RESULT=0
fi

END_TIME=$(date +%s.%N)
ELAPSED=$(echo "$END_TIME - $START_TIME" | bc 2>/dev/null || echo "N/A")

echo ""
echo "Execution time: ${ELAPSED}s"
echo "Exit code: ${RESULT}"
echo ""

if [ $RESULT -eq 0 ]; then
    echo "starexec-result=MEMORY_DONE"
else
    echo "starexec-result=MEMORY_FAILED"
fi

echo "starexec-memory-stress: finished"
exit $RESULT
