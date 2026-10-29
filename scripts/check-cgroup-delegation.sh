#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/lib/cli-output.sh"

# Check cgroup controller delegation for Podman rootless mode
# This script verifies that required cgroup controllers are available and properly delegated
# Usage: check-cgroup-delegation.sh [--fix]

FIX_MODE=false
if [ "$1" = "--fix" ]; then
    FIX_MODE=true
fi

REQUIRED_CONTROLLERS="cpuset cpu io memory pids"
USER_ID=$(id -u)

if [ "$FIX_MODE" = true ]; then
    ui_info "Checking and fixing cgroup controller delegation for Podman rootless mode..."
else
    ui_info "Checking cgroup controller delegation for Podman rootless mode..."
fi

# Check if we're running on a system with systemd and cgroups v2
if [ ! -f /sys/fs/cgroup/cgroup.controllers ]; then
    ui_warn "cgroups v2 not detected. This system may not support proper cgroup delegation."
    ui_info "  Podman rootless mode may not work correctly."
    exit 1
fi

# Check root cgroup controllers
ROOT_CONTROLLERS=$(cat /sys/fs/cgroup/cgroup.controllers)
ui_detail "Root cgroup.controllers: $ROOT_CONTROLLERS"

MISSING_CONTROLLERS=""
for controller in $REQUIRED_CONTROLLERS; do
    if ! echo "$ROOT_CONTROLLERS" | grep -q "$controller"; then
        MISSING_CONTROLLERS="$MISSING_CONTROLLERS $controller"
    fi
done

if [ -n "$MISSING_CONTROLLERS" ]; then
    ui_error "Missing required controllers in root cgroup:$MISSING_CONTROLLERS"
    ui_info "  This indicates a system configuration issue."
    ui_info "  Required controllers: $REQUIRED_CONTROLLERS"
    exit 1
fi

ui_detail "All required controllers available in root cgroup"

# Check user slice delegation
USER_SLICE_CGROUP="/sys/fs/cgroup/user.slice/user-${USER_ID}.slice"
if [ ! -d "$USER_SLICE_CGROUP" ]; then
    ui_warn "User slice cgroup not found: $USER_SLICE_CGROUP"
    ui_info "  This may indicate systemd user service is not running."
    ui_info "  Try: systemctl --user start"
    exit 1
fi

if [ -f "$USER_SLICE_CGROUP/cgroup.subtree_control" ]; then
    SUBTREE_CONTROL=$(cat "$USER_SLICE_CGROUP/cgroup.subtree_control")
    ui_detail "User slice cgroup.subtree_control: $SUBTREE_CONTROL"

    MISSING_DELEGATION=""
    for controller in $REQUIRED_CONTROLLERS; do
        if ! echo "$SUBTREE_CONTROL" | grep -q "$controller"; then
            MISSING_DELEGATION="$MISSING_DELEGATION $controller"
        fi
    done

    if [ -n "$MISSING_DELEGATION" ]; then
        ui_error "Missing controller delegation in user slice:$MISSING_DELEGATION"

        if [ "$FIX_MODE" = true ]; then
            ui_info "Attempting to fix automatically..."

            # Check if we have sudo access
            if ! sudo -n true 2>/dev/null; then
                ui_error "Cannot fix automatically: sudo access required but not available"
                ui_info "  Please run the manual fix commands shown below."
                exit 1
            fi

            # ⚠️ WARNING: Restarting user@UID.service terminates the current
            # login session. Confirm before proceeding.
            ui_warn "Restarting user@${USER_ID}.service will terminate your current login session."
            ui_warn "All running applications will be killed immediately."
            ui_info "  After the restart you must log in again."
            ui_info "  Then run: podman system migrate"
            if [ -t 0 ]; then
                printf '%s' "Type 'yes' to proceed, anything else to cancel: " >&2
                read -r REPLY
                if [ "$REPLY" != "yes" ]; then
                    ui_info "Cancelled."
                    exit 1
                fi
                ui_info ""
            else
                ui_warn "Non-interactive environment; proceeding as --fix was explicitly requested."
                ui_info "  Ensure no critical work is running."
            fi

            # Create the drop-in configuration
            ui_info "Creating systemd drop-in configuration..."
            sudo mkdir -p "/etc/systemd/system/user@${USER_ID}.service.d"

            sudo tee "/etc/systemd/system/user@${USER_ID}.service.d/delegate.conf" > /dev/null <<EOF
[Service]
Delegate=cpu cpuset io memory pids
MemoryAccounting=yes
CPUAccounting=yes
IOAccounting=yes
TasksAccounting=yes
EOF

            ui_info "Reloading systemd configuration..."
            sudo systemctl daemon-reload

            ui_info "Restarting user service..."
            sudo systemctl restart "user@${USER_ID}.service"

            ui_success "Configuration applied."
            ui_info "  Run: systemctl --user daemon-reload"
            ui_info "Re-running check to verify fix..."
            exec "$0"  # Re-run the script to verify
        else
            ui_info "  Manual fix required. Run with --fix to apply automatically: $0 --fix"
            ui_info "  Or apply manually:"
            ui_info "  sudo mkdir -p /etc/systemd/system/user@${USER_ID}.service.d"
            ui_info "  sudo tee /etc/systemd/system/user@${USER_ID}.service.d/delegate.conf > /dev/null <<EOF"
            ui_info "  [Service]"
            ui_info "  Delegate=cpu cpuset io memory pids"
            ui_info "  MemoryAccounting=yes"
            ui_info "  CPUAccounting=yes"
            ui_info "  IOAccounting=yes"
            ui_info "  TasksAccounting=yes"
            ui_info "  EOF"
            ui_info "  sudo systemctl daemon-reload"
            ui_info "  sudo systemctl restart user@${USER_ID}.service"
            ui_info "  Then restart your user session or run: systemctl --user daemon-reload"
            exit 1
        fi
    fi

    ui_detail "All required controllers properly delegated to user slice"
else
    ui_error "cgroup.subtree_control file not found in user slice"
    ui_info "  This indicates cgroups v2 delegation is not working."
    exit 1
fi

ui_success "Cgroup controller delegation check passed"
exit 0
