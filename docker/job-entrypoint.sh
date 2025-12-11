#!/bin/bash
#==========================================================================
# StarExec Job Container Entrypoint
#
# This script executes inside job containers and orchestrates the execution
# of solvers with pre/post processing stages. It's the containerized
# equivalent of the SGE jobscript.
#
# Environment variables (passed by PodmanBackend):
#   STAREXEC_PAIR_ID            - The job pair ID
#   STAREXEC_CPU_LIMIT          - CPU time limit in seconds
#   STAREXEC_WALLCLOCK_LIMIT    - Wall clock time limit in seconds
#   STAREXEC_MEM_LIMIT          - Memory limit in MB
#   STAREXEC_SOLVER_PATH        - Path to solver executable (optional)
#   STAREXEC_PRE_PROCESSOR_PATH - Path to pre-processor (optional)
#   STAREXEC_POST_PROCESSOR_PATH- Path to post-processor (optional)
#   STAREXEC_BENCHMARK_PATH     - Path to benchmark file (optional)
#   STAREXEC_OUTPUT_DIR         - Path to output directory (optional)
#   STAREXEC_DEBUG              - Enable debug logging (0 or 1)
#
# All paths can be overridden via environment variables, allowing the
# PodmanBackend to configure paths at runtime without modifying this script.
#
#==========================================================================

set -euo pipefail  # Exit on error, undefined vars, and pipe failures

# --- Configuration (from environment with fallback defaults) ---
readonly SCRIPT_VERSION="1.1.0"

# Paths are configurable via environment variables for decoupling
readonly OUTPUT_DIR="${STAREXEC_OUTPUT_DIR:-/starexec/output}"
readonly INPUT_DIR="${STAREXEC_INPUT_DIR:-/starexec/input}"
readonly SOLVER_PATH="${STAREXEC_SOLVER_PATH:-/starexec/solver/starexec_run}"
readonly PRE_PROCESSOR_PATH="${STAREXEC_PRE_PROCESSOR_PATH:-/starexec/pre-processor/starexec_run}"
readonly POST_PROCESSOR_PATH="${STAREXEC_POST_PROCESSOR_PATH:-/starexec/post-processor/starexec_run}"
readonly BENCHMARK_FILE="${STAREXEC_BENCHMARK_PATH:-${INPUT_DIR}/benchmark}"

# --- Resource Limits (with sensible defaults) ---
readonly CPU_LIMIT="${STAREXEC_CPU_LIMIT:-600}"
readonly WALLCLOCK_LIMIT="${STAREXEC_WALLCLOCK_LIMIT:-600}"
readonly MEM_LIMIT="${STAREXEC_MEM_LIMIT:-2048}"
readonly PAIR_ID="${STAREXEC_PAIR_ID:-0}"

# --- Logging Functions ---
# Output format: [LEVEL] timestamp - message
# Prefix with [JOB-LOG] for easy parsing by Java backend

log_info() {
    echo "[JOB-LOG][INFO] $(date -u '+%Y-%m-%d %H:%M:%S UTC') - $1"
}

log_error() {
    echo "[JOB-LOG][ERROR] $(date -u '+%Y-%m-%d %H:%M:%S UTC') - $1" >&2
}

log_debug() {
    if [ "${STAREXEC_DEBUG:-0}" = "1" ]; then
        echo "[JOB-LOG][DEBUG] $(date -u '+%Y-%m-%d %H:%M:%S UTC') - $1"
    fi
}

# --- Initialization ---
initialize() {
    log_info "=== StarExec Job Container v${SCRIPT_VERSION} ==="
    log_info "Job Pair ID: ${PAIR_ID}"
    log_info "Resource Limits: CPU=${CPU_LIMIT}s, Wall=${WALLCLOCK_LIMIT}s, Mem=${MEM_LIMIT}MB"
    log_info "Start Time: $(date -u)"

    # Log configured paths for debugging
    log_debug "SOLVER_PATH: ${SOLVER_PATH}"
    log_debug "PRE_PROCESSOR_PATH: ${PRE_PROCESSOR_PATH}"
    log_debug "POST_PROCESSOR_PATH: ${POST_PROCESSOR_PATH}"
    log_debug "BENCHMARK_FILE: ${BENCHMARK_FILE}"
    log_debug "OUTPUT_DIR: ${OUTPUT_DIR}"

    # Create necessary directories
    mkdir -p "${OUTPUT_DIR}" "${INPUT_DIR}"

    # Verify solver exists
    if [ ! -f "${SOLVER_PATH}" ]; then
        log_error "Solver not found at ${SOLVER_PATH}"
        exit 1
    fi

    # Verify benchmark exists
    if [ ! -f "${BENCHMARK_FILE}" ]; then
        log_error "Benchmark file not found at ${BENCHMARK_FILE}"
        exit 1
    fi

    # Ensure solver is executable (artifacts should ideally be executable before mounting)
    if [ ! -x "${SOLVER_PATH}" ]; then
        log_debug "Making solver executable: ${SOLVER_PATH}"
        chmod +x "${SOLVER_PATH}" 2>/dev/null || log_debug "chmod failed (may be read-only mount)"
    fi

    log_info "Initialization complete"
}

# --- Stage 1: Pre-processing ---
run_preprocessor() {
    if [ -f "${PRE_PROCESSOR_PATH}" ]; then
        log_info "Stage 1: Running pre-processor..."

        # Make executable if needed (warn if fails on read-only mount)
        if [ ! -x "${PRE_PROCESSOR_PATH}" ]; then
            log_debug "Making pre-processor executable"
            chmod +x "${PRE_PROCESSOR_PATH}" 2>/dev/null || log_debug "chmod failed (may be read-only mount)"
        fi

        local processed_file="${OUTPUT_DIR}/benchmark.preprocessed"

        if "${PRE_PROCESSOR_PATH}" "${BENCHMARK_FILE}" > "${processed_file}" 2>"${OUTPUT_DIR}/preprocessor_error.txt"; then
            log_info "Pre-processing completed successfully"
            echo "${processed_file}"
        else
            log_error "Pre-processor failed with exit code $?"
            echo "${BENCHMARK_FILE}"  # Fall back to original
        fi
    else
        log_info "Stage 1: No pre-processor found, skipping"
        echo "${BENCHMARK_FILE}"
    fi
}

# --- Stage 2: Solver Execution ---
run_solver() {
    local input_file="$1"
    local solver_args="${@:2}"

    log_info "Stage 2: Executing solver..."
    log_debug "Input file: ${input_file}"
    log_debug "Solver args: ${solver_args}"

    local exit_code=0

    # Execute solver with resource limits
    # Using a subshell to apply ulimits without affecting the parent
    (
        # Apply CPU time limit (seconds)
        ulimit -t "${CPU_LIMIT}" 2>/dev/null || true

        # Apply virtual memory limit (kilobytes)
        ulimit -v "$((MEM_LIMIT * 1024))" 2>/dev/null || true

        # Execute with wallclock timeout
        timeout "${WALLCLOCK_LIMIT}" "${SOLVER_PATH}" "${input_file}" ${solver_args} \
            > "${OUTPUT_DIR}/solver_output.txt" \
            2> "${OUTPUT_DIR}/solver_error.txt"
    ) || exit_code=$?

    # Record timing and exit information
    {
        echo "STAREXEC_PAIR_ID=${PAIR_ID}"
        echo "STAREXEC_EXIT_CODE=${exit_code}"
        echo "STAREXEC_COMPLETION_TIME=$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    } > "${OUTPUT_DIR}/starexec_result.txt"

    # Handle exit codes
    case ${exit_code} in
        0)
            log_info "Solver completed successfully"
            ;;
        124)
            log_info "Solver terminated: wallclock timeout exceeded"
            echo "STAREXEC_WALLCLOCK_EXCEEDED=true" >> "${OUTPUT_DIR}/starexec_result.txt"
            ;;
        137)
            log_info "Solver terminated: killed (likely OOM or CPU limit)"
            echo "STAREXEC_RESOURCE_EXCEEDED=true" >> "${OUTPUT_DIR}/starexec_result.txt"
            ;;
        *)
            log_info "Solver exited with code: ${exit_code}"
            ;;
    esac

    return ${exit_code}
}

# --- Stage 3: Post-processing ---
run_postprocessor() {
    if [ -f "${POST_PROCESSOR_PATH}" ]; then
        log_info "Stage 3: Running post-processor..."

        # Make executable if needed (warn if fails on read-only mount)
        if [ ! -x "${POST_PROCESSOR_PATH}" ]; then
            log_debug "Making post-processor executable"
            chmod +x "${POST_PROCESSOR_PATH}" 2>/dev/null || log_debug "chmod failed (may be read-only mount)"
        fi

        if "${POST_PROCESSOR_PATH}" "${OUTPUT_DIR}/solver_output.txt" > "${OUTPUT_DIR}/attributes.txt" 2>"${OUTPUT_DIR}/postprocessor_error.txt"; then
            log_info "Post-processing completed successfully"
        else
            log_error "Post-processor failed with exit code $?"
        fi
    else
        log_info "Stage 3: No post-processor found, skipping"
    fi
}

# --- Cleanup ---
cleanup() {
    log_info "Cleaning up..."
    # Remove any temporary files if needed
    log_info "Job completed at: $(date -u)"
}

# --- Main Execution ---
main() {
    local solver_exit_code=0

    # Set up trap for cleanup
    trap cleanup EXIT

    # Initialize
    initialize

    # Stage 1: Pre-processing
    local processed_benchmark
    processed_benchmark=$(run_preprocessor)

    # Stage 2: Solver execution
    run_solver "${processed_benchmark}" "$@" || solver_exit_code=$?

    # Stage 3: Post-processing
    run_postprocessor

    log_info "=== Job Execution Complete ==="
    log_info "Final exit code: ${solver_exit_code}"

    exit ${solver_exit_code}
}

# Execute main with all script arguments
main "$@"
