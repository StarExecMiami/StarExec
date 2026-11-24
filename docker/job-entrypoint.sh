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

_json_escape() {
    local val="$1"
    val="${val//\\/\\\\}"
    val="${val//\"/\\\"}"
    val="${val//$'\n'/}"
    val="${val//$'\r'/}"
    printf '%s' "$val"
}

_expand_cpuset() {
    local spec="${1//[[:space:]]/}"
    local -a result=()
    local part start end cur
    [ -z "$spec" ] && return 0
    IFS=',' read -r -a _parts <<< "$spec"
    for part in "${_parts[@]}"; do
        if [[ "$part" == *-* ]]; then
            start="${part%%-*}"
            end="${part#*-}"
            if [[ "$start" =~ ^[0-9]+$ && "$end" =~ ^[0-9]+$ && "$start" -le "$end" ]]; then
                cur="$start"
                while [ "$cur" -le "$end" ]; do
                    result+=("$cur")
                    cur=$(( cur + 1 ))
                done
            fi
        elif [[ "$part" =~ ^[0-9]+$ ]]; then
            result+=("$part")
        fi
    done
    [ "${#result[@]}" -gt 0 ] && printf '%s\n' "${result[@]}" | sort -n -u
}

write_cpu_topology() {
    local target_file="${1:-${OUTPUT_DIR}/cpu-topology.json}"
    local proc_status="${STAREXEC_TOPOLOGY_PROC_STATUS:-/proc/self/status}"
    local proc_cgroup="${STAREXEC_TOPOLOGY_PROC_CGROUP:-/proc/self/cgroup}"
    local cgroup_root="${STAREXEC_TOPOLOGY_CGROUP_ROOT:-/sys/fs/cgroup}"
    local sysfs_cpu_root="${STAREXEC_TOPOLOGY_SYSFS_CPU_ROOT:-/sys/devices/system/cpu}"
    local node_name="${STAREXEC_NODE_NAME:-$(hostname)}"

    local cpus_allowed=""
    [ -r "$proc_status" ] && cpus_allowed="$(awk '/^Cpus_allowed_list:/ { print $2; exit }' "$proc_status" 2>/dev/null || true)"
    local online_cpus=""
    [ -r "$sysfs_cpu_root/online" ] && online_cpus="$(tr -d '[:space:]' < "$sysfs_cpu_root/online" 2>/dev/null || true)"

    local cgroup_hierarchy="unknown"
    local cgroup_effective=""
    if [ -f "$cgroup_root/cgroup.controllers" ]; then
        cgroup_hierarchy="v2"
        local rel_v2=""
        [ -r "$proc_cgroup" ] && rel_v2="$(awk -F: '$1 == "0" && $2 == "" { print $3; exit }' "$proc_cgroup" 2>/dev/null || true)"
        if [ -n "$rel_v2" ] && [ "$rel_v2" != "/" ] && [ -r "$cgroup_root${rel_v2}/cpuset.cpus.effective" ]; then
            cgroup_effective="$(tr -d '[:space:]' < "$cgroup_root${rel_v2}/cpuset.cpus.effective" 2>/dev/null || true)"
        elif [ -r "$cgroup_root/cpuset.cpus.effective" ]; then
            cgroup_effective="$(tr -d '[:space:]' < "$cgroup_root/cpuset.cpus.effective" 2>/dev/null || true)"
        fi
    elif [ -d "$cgroup_root/cpuset" ]; then
        cgroup_hierarchy="v1"
        local rel_v1=""
        [ -r "$proc_cgroup" ] && rel_v1="$(awk -F: '$2 ~ /(^|,)cpuset(,|$)/ { print $3; exit }' "$proc_cgroup" 2>/dev/null || true)"
        local v1_dir="$cgroup_root/cpuset"
        if [ -n "$rel_v1" ] && [ "$rel_v1" != "/" ] && [ -d "$cgroup_root/cpuset${rel_v1}" ]; then
            v1_dir="$cgroup_root/cpuset${rel_v1}"
        fi
        if [ -r "$v1_dir/cpuset.effective_cpus" ]; then
            cgroup_effective="$(tr -d '[:space:]' < "$v1_dir/cpuset.effective_cpus" 2>/dev/null || true)"
        elif [ -r "$v1_dir/cpuset.cpus" ]; then
            cgroup_effective="$(tr -d '[:space:]' < "$v1_dir/cpuset.cpus" 2>/dev/null || true)"
        fi
    fi

    local inspect_spec="$cpus_allowed"
    if [ -z "$inspect_spec" ]; then
        inspect_spec="$cgroup_effective"
    elif [ -n "$cgroup_effective" ] && [ "$cgroup_effective" != "$cpus_allowed" ]; then
        inspect_spec="${cpus_allowed},${cgroup_effective}"
    fi

    local -a expanded_cpus=()
    while IFS= read -r _cpu; do
        [ -n "$_cpu" ] && expanded_cpus+=("$_cpu")
    done < <(_expand_cpuset "$inspect_spec")

    local -a numa_seen=()
    local cpu_entries=""
    local first_entry=true
    local cpu topo_dir core_id pkg_id sib_list numa_id numa_dir numa_json core_json pkg_json sib_json
    for cpu in "${expanded_cpus[@]}"; do
        topo_dir="$sysfs_cpu_root/cpu${cpu}/topology"
        core_id=""
        pkg_id=""
        sib_list=""
        numa_id=""
        [ -r "$topo_dir/core_id" ] && core_id="$(tr -d '[:space:]' < "$topo_dir/core_id" 2>/dev/null || true)"
        [ -r "$topo_dir/physical_package_id" ] && pkg_id="$(tr -d '[:space:]' < "$topo_dir/physical_package_id" 2>/dev/null || true)"
        [ -r "$topo_dir/thread_siblings_list" ] && sib_list="$(tr -d '[:space:]' < "$topo_dir/thread_siblings_list" 2>/dev/null || true)"
        for numa_dir in "$sysfs_cpu_root/cpu${cpu}/node"[0-9]*; do
            if [ -d "$numa_dir" ]; then
                numa_id="${numa_dir##*/node}"
                if [[ "$numa_id" =~ ^[0-9]+$ ]]; then
                    numa_seen+=("$numa_id")
                    break
                else
                    numa_id=""
                fi
            fi
        done

        [[ "$core_id" =~ ^[0-9]+$ ]] && core_json="$core_id" || core_json="null"
        [[ "$pkg_id" =~ ^[0-9]+$ ]] && pkg_json="$pkg_id" || pkg_json="null"
        [[ "$numa_id" =~ ^[0-9]+$ ]] && numa_json="$numa_id" || numa_json="null"
        [ -n "$sib_list" ] && sib_json="\"$(_json_escape "$sib_list")\"" || sib_json="null"

        if [ "$first_entry" = true ]; then
            first_entry=false
        else
            cpu_entries="${cpu_entries},"
        fi
        cpu_entries="${cpu_entries}{\"cpu\":${cpu},\"coreId\":${core_json},\"packageId\":${pkg_json},\"numaNode\":${numa_json},\"threadSiblings\":${sib_json}}"
    done

    local numa_nodes=""
    [ "${#numa_seen[@]}" -gt 0 ] && numa_nodes="$(printf '%s\n' "${numa_seen[@]}" | sort -n -u | paste -sd ',' -)"

    local cgroup_eff_json="null"
    [ -n "$cgroup_effective" ] && cgroup_eff_json="\"$(_json_escape "$cgroup_effective")\""
    local online_json="null"
    [ -n "$online_cpus" ] && online_json="\"$(_json_escape "$online_cpus")\""
    local numa_nodes_json="null"
    [ -n "$numa_nodes" ] && numa_nodes_json="\"$(_json_escape "$numa_nodes")\""

    if printf '{"pairId":%s,"nodeName":"%s","cpusAllowedList":"%s","cgroupEffectiveCpuset":%s,"cgroupHierarchy":"%s","onlineCpus":%s,"numaNodes":%s,"cpus":[%s]}\n' \
        "${PAIR_ID}" \
        "$(_json_escape "$node_name")" \
        "$(_json_escape "$cpus_allowed")" \
        "$cgroup_eff_json" \
        "$(_json_escape "$cgroup_hierarchy")" \
        "$online_json" \
        "$numa_nodes_json" \
        "$cpu_entries" > "${target_file}.tmp" 2>/dev/null; then
        mv -f "${target_file}.tmp" "$target_file" 2>/dev/null || rm -f "${target_file}.tmp" 2>/dev/null || true
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

    # Record effective CPU affinity, cgroup cpuset, and sysfs topology for #286 attestation
    write_cpu_topology "${OUTPUT_DIR}/cpu-topology.json" || true

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
