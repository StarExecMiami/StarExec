#!/usr/bin/env bash
# Check whether this Linux host/container supports LocalBackend strict CPU isolation
# (cgroups v2 cpuset delegation, isolated partition support, and full sysfs SMT topology).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/cli-output.sh
. "$SCRIPT_DIR/lib/cli-output.sh"

CGROUP_ROOT="${STAREXEC_CHECK_CGROUP_ROOT:-/sys/fs/cgroup}"
SYSFS_CPU_ROOT="${STAREXEC_CHECK_SYSFS_CPU_ROOT:-/sys/devices/system/cpu}"
PROC_SELF_CGROUP="${STAREXEC_CHECK_PROC_SELF_CGROUP:-/proc/self/cgroup}"
DO_PROBE=1

usage() {
    cat <<'EOF'
Usage: scripts/check-local-cpu-isolation.sh [--no-probe] [--help]

Verifies prerequisites for STAREXEC_LOCAL_ISOLATION_MODE=strict:
  1. Unified cgroups v2 hierarchy with delegated 'cpuset' controller
  2. Writable cgroup subtree allowing cpuset.cpus.partition=isolated
  3. Complete sysfs topology (core_id, physical_package_id, thread_siblings_list)
  4. Consistency of STAREXEC_LOCAL_CORE_LIST (when set) across online SMT sibling groups

Exit status:
  0  Strict local CPU isolation prerequisites satisfied
  1  Missing or unverified prerequisite (permissive mode only)
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --no-probe)
            DO_PROBE=0
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            ui_error "Unknown argument: $1"
            usage >&2
            exit 2
            ;;
    esac
done

expand_cpuset() {
    local spec="$1"
    python3 -c '
import sys
spec = sys.argv[1].strip()
cpus = []
if spec:
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            a, b = part.split("-", 1)
            cpus.extend(range(int(a), int(b) + 1))
        else:
            cpus.append(int(part))
print(" ".join(str(c) for c in sorted(set(cpus))))
' "$spec"
}

failures=0
probe_dir=""

cleanup_probe() {
    if [ -n "$probe_dir" ] && [ -d "$probe_dir" ]; then
        rmdir "$probe_dir" 2>/dev/null || true
    fi
}
trap cleanup_probe EXIT

ui_info "Checking LocalBackend strict CPU isolation prerequisites (#291)..."

# 1. Cgroups v2 unified hierarchy check
if [ ! -f "$CGROUP_ROOT/cgroup.controllers" ]; then
    ui_error "Cgroups v2 unified hierarchy not found at $CGROUP_ROOT (missing cgroup.controllers)."
    failures=$(( failures + 1 ))
else
    ui_success "Cgroups v2 hierarchy present at $CGROUP_ROOT"
fi

# 2. Self cgroup resolution and cpuset controller delegation
self_rel=""
if [ -r "$PROC_SELF_CGROUP" ]; then
    while IFS= read -r line; do
        case "$line" in
            0::*)
                self_rel="${line#0::}"
                break
                ;;
        esac
    done < "$PROC_SELF_CGROUP"
fi

if [ -z "$self_rel" ]; then
    ui_error "No cgroups v2 entry ('0::...') found in $PROC_SELF_CGROUP."
    failures=$(( failures + 1 ))
else
    if [ "$self_rel" = "/" ]; then
        self_dir="$CGROUP_ROOT"
    else
        self_dir="$CGROUP_ROOT${self_rel}"
    fi
    ui_info "Resolved process cgroup path: $self_dir"

    slot_root="$self_dir"
    parent_dir="$(dirname "$self_dir")"
    if [ "$(basename "$self_dir")" = "starexec-jvm" ] && [ -f "$parent_dir/cgroup.subtree_control" ]; then
        if grep -qw "cpuset" "$parent_dir/cgroup.subtree_control" 2>/dev/null; then
            slot_root="$parent_dir"
        fi
    fi

    controllers_file="$self_dir/cgroup.controllers"
    controllers=""
    if [ -r "$controllers_file" ]; then
        controllers="$(tr '\n' ' ' < "$controllers_file" | xargs || true)"
    fi

    if printf '%s\n' "$controllers" | grep -qw "cpuset"; then
        ui_success "'cpuset' controller is delegated in $controllers_file"
    else
        ui_error "'cpuset' controller is NOT delegated in $controllers_file (controllers: '${controllers:-none}')."
        ui_info "Remediation: enable systemd 'Delegate=cpuset cpu memory pids' or run in a container with delegated cgroup v2 cpuset; otherwise use STAREXEC_LOCAL_ISOLATION_MODE=permissive."
        failures=$(( failures + 1 ))
    fi

    if [ ! -w "$slot_root" ] || [ ! -w "$slot_root/cgroup.subtree_control" ]; then
        ui_error "Cgroup root $slot_root or $slot_root/cgroup.subtree_control is not writable by current user ($(id -un))."
        failures=$(( failures + 1 ))
    else
        ui_success "Cgroup directory $slot_root is writable"
    fi
fi

# 3. Sysfs CPU topology inspection
online_file="$SYSFS_CPU_ROOT/online"
if [ ! -r "$online_file" ]; then
    ui_error "Cannot read online CPU list at $online_file."
    failures=$(( failures + 1 ))
    online_cpus=""
else
    online_spec="$(tr -d '[:space:]' < "$online_file")"
    online_cpus="$(expand_cpuset "$online_spec")"
    ui_info "Online CPUs ($online_file): $online_spec"
fi

first_siblings=""
topo_failures=0
logical_count=0
physical_keys=""
physical_count=0

for cpu in $online_cpus; do
    logical_count=$(( logical_count + 1 ))
    topo_dir="$SYSFS_CPU_ROOT/cpu${cpu}/topology"
    if [ ! -r "$topo_dir/core_id" ] || [ ! -r "$topo_dir/physical_package_id" ] || [ ! -r "$topo_dir/thread_siblings_list" ]; then
        ui_error "Incomplete sysfs topology for CPU $cpu under $topo_dir (missing core_id, physical_package_id, or thread_siblings_list)."
        topo_failures=$(( topo_failures + 1 ))
        continue
    fi
    core_id="$(tr -d '[:space:]' < "$topo_dir/core_id")"
    pkg_id="$(tr -d '[:space:]' < "$topo_dir/physical_package_id")"
    sib_list="$(tr -d '[:space:]' < "$topo_dir/thread_siblings_list")"
    if [ -z "$first_siblings" ]; then
        first_siblings="$sib_list"
    fi
    key="${pkg_id}:${core_id}"
    case " $physical_keys " in
        *" $key "*) ;;
        *)
            physical_keys="$physical_keys $key"
            physical_count=$(( physical_count + 1 ))
            ;;
    esac
    ui_detail "CPU $cpu -> package=$pkg_id core=$core_id siblings=$sib_list"
done

if [ "$logical_count" -eq 0 ] || [ "$topo_failures" -gt 0 ]; then
    failures=$(( failures + 1 ))
else
    ui_success "Verified complete sysfs topology for $logical_count online logical CPU(s) across $physical_count physical core(s)"
fi

# 4. Validate STAREXEC_LOCAL_CORE_LIST if set
if [ -n "${STAREXEC_LOCAL_CORE_LIST:-}" ] && [ -n "$online_cpus" ]; then
    configured_cpus="$(expand_cpuset "$STAREXEC_LOCAL_CORE_LIST")"
    seen_groups=""
    list_ok=1
    for cpu in $configured_cpus; do
        case " $online_cpus " in
            *" $cpu "*) ;;
            *)
                ui_error "STAREXEC_LOCAL_CORE_LIST references offline CPU $cpu."
                list_ok=0
                continue
                ;;
        esac
        sib_file="$SYSFS_CPU_ROOT/cpu${cpu}/topology/thread_siblings_list"
        if [ -r "$sib_file" ]; then
            sib_spec="$(tr -d '[:space:]' < "$sib_file")"
            for sib in $(expand_cpuset "$sib_spec"); do
                case " $online_cpus " in
                    *" $sib "*) ;;
                    *)
                        ui_error "Leased CPU $cpu has offline SMT sibling $sib ($sib_spec), which breaks strict physical exclusivity."
                        list_ok=0
                        ;;
                esac
            done
            case " $seen_groups " in
                *" $sib_spec "*)
                    ui_error "STAREXEC_LOCAL_CORE_LIST leases multiple logical CPUs from SMT sibling group $sib_spec."
                    list_ok=0
                    ;;
                *)
                    seen_groups="$seen_groups $sib_spec"
                    ;;
            esac
        fi
    done
    if [ "$list_ok" -eq 1 ]; then
        ui_success "STAREXEC_LOCAL_CORE_LIST ($STAREXEC_LOCAL_CORE_LIST) maps to distinct complete physical cores"
    else
        failures=$(( failures + 1 ))
    fi
fi

# 5. Live isolated partition probe when cgroup root is already delegated & writable
if [ "$DO_PROBE" -eq 1 ] && [ "$failures" -eq 0 ] && [ -n "$first_siblings" ]; then
    subtree_file="$slot_root/cgroup.subtree_control"
    if grep -qw "cpuset" "$subtree_file" 2>/dev/null; then
        probe_dir="$slot_root/starexec-check-probe-$$"
        rmdir "$probe_dir" 2>/dev/null || true
        if mkdir "$probe_dir" 2>/dev/null; then
            mems_val=""
            if [ -r "$slot_root/cpuset.mems.effective" ]; then
                mems_val="$(tr -d '[:space:]' < "$slot_root/cpuset.mems.effective")"
            elif [ -r "$slot_root/cpuset.mems" ]; then
                mems_val="$(tr -d '[:space:]' < "$slot_root/cpuset.mems")"
            fi
            if [ -n "$mems_val" ]; then
                printf '%s\n' "$mems_val" > "$probe_dir/cpuset.mems"
            fi
            if printf '%s\n' "$first_siblings" > "$probe_dir/cpuset.cpus" 2>/dev/null \
                && printf 'isolated\n' > "$probe_dir/cpuset.cpus.partition" 2>/dev/null; then
                actual_part="$(tr -d '[:space:]' < "$probe_dir/cpuset.cpus.partition" 2>/dev/null || true)"
                if [ "$actual_part" = "isolated" ]; then
                    ui_success "Live probe verified cpuset.cpus.partition=isolated for sibling group $first_siblings"
                else
                    ui_error "Probe cpuset.cpus.partition reported '$actual_part' instead of 'isolated'."
                    failures=$(( failures + 1 ))
                fi
            else
                ui_error "Failed writing cpuset.cpus / cpuset.cpus.partition=isolated in $probe_dir."
                failures=$(( failures + 1 ))
            fi
            cleanup_probe
            probe_dir=""
        else
            ui_error "Could not create probe cgroup directory $probe_dir."
            failures=$(( failures + 1 ))
        fi
    else
        ui_info "Note: $subtree_file does not yet enable +cpuset because internal processes remain in $self_dir; LocalBackend strict mode will move its JVM PID into starexec-jvm before enabling +cpuset if writable."
    fi
fi

if [ "$failures" -gt 0 ]; then
    ui_error "Strict local CPU isolation check failed ($failures issue(s) found)."
    exit 1
fi

ui_success "All LocalBackend strict physical-core isolation checks passed."
exit 0
