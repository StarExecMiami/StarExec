#!/bin/bash
# StarExec I/O Stress Test Solver
# Creates high read/write volume on the filesystem
#
# Environment variables (optional):
#   STAREXEC_IO_FILE_COUNT - Number of files to create (default: 1000)
#   STAREXEC_IO_FILE_SIZE  - Size of each file in bytes (default: 1024)
#   STAREXEC_IO_ITERATIONS - Number of write/read cycles (default: 1)

set -e

# Configuration
FILE_COUNT=${STAREXEC_IO_FILE_COUNT:-1000}
FILE_SIZE=${STAREXEC_IO_FILE_SIZE:-1024}
ITERATIONS=${STAREXEC_IO_ITERATIONS:-1}
OUTPUT_DIR="io_stress_output_$$"

echo "starexec-io-stress: starting"
echo "  File count: $FILE_COUNT"
echo "  File size: $FILE_SIZE bytes"
echo "  Iterations: $ITERATIONS"

# Create a random data buffer for writing
RANDOM_DATA=$(head -c $FILE_SIZE /dev/urandom | base64 | head -c $FILE_SIZE)

cleanup() {
    echo "starexec-io-stress: cleaning up"
    rm -rf "$OUTPUT_DIR" 2>/dev/null || true
}

# Ensure cleanup on exit
trap cleanup EXIT

for iter in $(seq 1 $ITERATIONS); do
    echo "starexec-io-stress: iteration $iter/$ITERATIONS"

    # Create output directory
    mkdir -p "$OUTPUT_DIR"

    # Write phase
    echo "starexec-io-stress: writing $FILE_COUNT files"
    for i in $(seq 1 $FILE_COUNT); do
        echo "$RANDOM_DATA" > "$OUTPUT_DIR/file_$i.txt"
    done

    # Read phase
    echo "starexec-io-stress: reading $FILE_COUNT files"
    TOTAL_READ=0
    for i in $(seq 1 $FILE_COUNT); do
        CONTENT=$(cat "$OUTPUT_DIR/file_$i.txt")
        TOTAL_READ=$((TOTAL_READ + ${#CONTENT}))
    done
    echo "starexec-io-stress: read $TOTAL_READ bytes total"

    # Delete phase (except last iteration - cleanup will handle it)
    if [ $iter -lt $ITERATIONS ]; then
        rm -rf "$OUTPUT_DIR"
    fi
done

# Calculate summary
TOTAL_WRITTEN=$((FILE_COUNT * FILE_SIZE * ITERATIONS))
echo "starexec-io-stress: completed"
echo "starexec-io-stress: total written: $TOTAL_WRITTEN bytes"
echo "starexec-result=IO_DONE"
