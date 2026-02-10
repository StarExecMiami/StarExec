#!/bin/bash

# A script to submit a large number of jobs directly to the database for stress testing.

# --- Configuration ---
# Default values
NUM_JOBS=100
USER_ID=2
SPACE_ID=1
BENCH_ID=3
QUEUE_ID=1
POST_PROC_ID=3
JOB_NAME="stress-test-$(date +%s)"
SOLVER_ID=5 # Default to the trivial solver

# --- Functions ---
usage() {
    echo "Usage: $0 [--num-jobs <int>] [--solver-id <int>] [--name <string>]"
    echo "  --num-jobs:     Number of jobs to create (default: 100)"
    echo "  --solver-id:    The ID of the solver to use (default: 5, 'stress_trivial')"
    echo "  --name:         A name for this job batch (default: 'stress-test-<timestamp>')"
    exit 1
}

psql_exec() {
    podman exec starexec-app psql -U starexec -h localhost -d starexec -c "$1"
}

psql_exec_file() {
    podman exec -i starexec-app psql -U starexec -h localhost -d starexec
}

# --- Argument Parsing ---
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --num-jobs) NUM_JOBS="$2"; shift ;;
        --solver-id) SOLVER_ID="$2"; shift ;;
        --name) JOB_NAME="$2"; shift ;;
        --help) usage ;;
        *) echo "Unknown parameter passed: $1"; usage ;;
    esac
    shift
done

# --- Main Script ---

echo "Starting stress test job submission..."
echo "Job Name:     $JOB_NAME"
echo "Number of Jobs: $NUM_JOBS"
echo "Solver ID:      $SOLVER_ID"
echo "Queue ID:       $QUEUE_ID"
echo "--------------------------------"

# 1. Create a single "jobs" entry for this batch
echo "Creating parent job entry..."
JOB_ID_OUTPUT=$(psql_exec "INSERT INTO jobs (user_id, name, description, queue_id, primary_space, total_pairs, disk_size) VALUES ($USER_ID, '$JOB_NAME', 'Stress test batch', $QUEUE_ID, $SPACE_ID, $NUM_JOBS, 0) RETURNING id;")
JOB_ID=$(echo "$JOB_ID_OUTPUT" | grep -o '[0-9]\+' | head -n 1)


if [[ -z "$JOB_ID" || "$JOB_ID" -le 0 ]]; then
    echo "Error: Failed to create parent job in the database. Aborting."
    echo "PSQL output was: $JOB_ID_OUTPUT"
    exit 1
fi
echo "Parent job created with ID: $JOB_ID"

# 1a. Associate the post-processor with the job stage
echo "Setting post-processor for job stage..."
psql_exec "INSERT INTO job_stage_params (job_id, stage_number, post_processor) VALUES ($JOB_ID, 1, $POST_PROC_ID);"


# 2. Create the job pairs using a temporary file for the bulk insert
echo "Submitting $NUM_JOBS job pairs..."

# Build the multi-row INSERT statement
{
    echo "INSERT INTO job_pairs (job_id, bench_id) VALUES ";
    for (( i=1; i<=$NUM_JOBS; i++ )); do
        echo "($JOB_ID, $BENCH_ID)"
        if [ $i -ne $NUM_JOBS ]; then
            echo ","
        else
            echo ";"
        fi
    done
} | psql_exec_file

# Associate the solver with every pair in the job.
echo "Associating solver with job pairs..."
psql_exec "
    INSERT INTO jobpair_stage_data (jobpair_id, stage_number, solver_id, config_id, disk_size)
    SELECT id, 1, $SOLVER_ID, -1, 0 FROM job_pairs WHERE job_id = $JOB_ID;
"

echo "--------------------------------"
echo "Submission complete!"
echo "$NUM_JOBS jobs for solver ID $SOLVER_ID have been submitted under job ID $JOB_ID."
