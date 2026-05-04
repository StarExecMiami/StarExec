#!/bin/bash
set -e

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
    echo "Checking and fixing cgroup controller delegation for Podman rootless mode..."
else
    echo "Checking cgroup controller delegation for Podman rootless mode..."
fi

# Check if we're running on a system with systemd and cgroups v2
if [ ! -f /sys/fs/cgroup/cgroup.controllers ]; then
    echo "⚠️  cgroups v2 not detected. This system may not support proper cgroup delegation."
    echo "   Podman rootless mode may not work correctly."
    exit 1
fi

# Check root cgroup controllers
echo -n "  Checking root cgroup.controllers: "
ROOT_CONTROLLERS=$(cat /sys/fs/cgroup/cgroup.controllers)
echo "$ROOT_CONTROLLERS"

MISSING_CONTROLLERS=""
for controller in $REQUIRED_CONTROLLERS; do
    if ! echo "$ROOT_CONTROLLERS" | grep -q "$controller"; then
        MISSING_CONTROLLERS="$MISSING_CONTROLLERS $controller"
    fi
done

if [ -n "$MISSING_CONTROLLERS" ]; then
    echo "❌ Missing required controllers in root cgroup:$MISSING_CONTROLLERS"
    echo "   This indicates a system configuration issue."
    echo "   Required controllers: $REQUIRED_CONTROLLERS"
    exit 1
fi

echo "✓ All required controllers available in root cgroup"

# Check user slice delegation
USER_SLICE_CGROUP="/sys/fs/cgroup/user.slice/user-${USER_ID}.slice"
if [ ! -d "$USER_SLICE_CGROUP" ]; then
    echo "⚠️  User slice cgroup not found: $USER_SLICE_CGROUP"
    echo "   This may indicate systemd user service is not running."
    echo "   Try: systemctl --user start"
    exit 1
fi

echo -n "  Checking user slice cgroup.subtree_control: "
if [ -f "$USER_SLICE_CGROUP/cgroup.subtree_control" ]; then
    SUBTREE_CONTROL=$(cat "$USER_SLICE_CGROUP/cgroup.subtree_control")
    echo "$SUBTREE_CONTROL"

    MISSING_DELEGATION=""
    for controller in $REQUIRED_CONTROLLERS; do
        if ! echo "$SUBTREE_CONTROL" | grep -q "$controller"; then
            MISSING_DELEGATION="$MISSING_DELEGATION $controller"
        fi
    done

    if [ -n "$MISSING_DELEGATION" ]; then
        echo "❌ Missing controller delegation in user slice:$MISSING_DELEGATION"

        if [ "$FIX_MODE" = true ]; then
            echo ""
            echo "🔧 Attempting to fix automatically..."

            # Check if we have sudo access
            if ! sudo -n true 2>/dev/null; then
                echo "❌ Cannot fix automatically: sudo access required but not available"
                echo "   Please run the manual fix commands shown below."
                exit 1
            fi

            # ⚠️ WARNING: Restarting user@UID.service terminates the current
            # login session. Confirm before proceeding.
            echo ""
            echo "╔══════════════════════════════════════════════════════════════╗"
            echo "║  ⚠️  WARNING: Restarting user@${USER_ID}.service            ║"
            echo "║                                                              ║"
            echo "║  This WILL terminate your current login session.             ║"
            echo "║  All running applications will be killed immediately.        ║"
            echo "║                                                              ║"
            echo "║  After the restart you MUST log in again.                    ║"
            echo "║  Then run: podman system migrate                             ║"
            echo "╚══════════════════════════════════════════════════════════════╝"
            echo ""
            if [ -t 0 ]; then
                printf "Type 'yes' to proceed, anything else to cancel: "
                read -r REPLY
                if [ "$REPLY" != "yes" ]; then
                    echo "Cancelled."
                    exit 1
                fi
                echo ""
            else
                echo "Non-interactive environment — proceeding as --fix was"
                echo "explicitly requested. Ensure no critical work is running."
                echo ""
            fi

            # Create the drop-in configuration
            echo "   Creating systemd drop-in configuration..."
            sudo mkdir -p "/etc/systemd/system/user@${USER_ID}.service.d"

            sudo tee "/etc/systemd/system/user@${USER_ID}.service.d/delegate.conf" > /dev/null <<EOF
[Service]
Delegate=cpu cpuset io memory pids
MemoryAccounting=yes
CPUAccounting=yes
IOAccounting=yes
TasksAccounting=yes
EOF

            echo "   Reloading systemd configuration..."
            sudo systemctl daemon-reload

            echo "   Restarting user service..."
            sudo systemctl restart "user@${USER_ID}.service"

            echo "✓ Configuration applied. You may need to restart your user session."
            echo "  Run: systemctl --user daemon-reload"
            echo ""
            echo "Re-running check to verify fix..."
            exec "$0"  # Re-run the script to verify
        else
            echo ""
            echo "🔧 Manual fix required. Run with --fix to apply automatically:"
            echo "   $0 --fix"
            echo ""
            echo "Or apply manually:"
            echo ""
            echo "   sudo mkdir -p /etc/systemd/system/user@${USER_ID}.service.d"
            echo "   sudo tee /etc/systemd/system/user@${USER_ID}.service.d/delegate.conf > /dev/null <<EOF"
            echo "   [Service]"
            echo "   Delegate=cpu cpuset io memory pids"
            echo "   MemoryAccounting=yes"
            echo "   CPUAccounting=yes"
            echo "   IOAccounting=yes"
            echo "   TasksAccounting=yes"
            echo "   EOF"
            echo ""
            echo "   sudo systemctl daemon-reload"
            echo "   sudo systemctl restart user@${USER_ID}.service"
            echo ""
            echo "   Then restart your user session or run: systemctl --user daemon-reload"
            exit 1
        fi
    fi

    echo "✓ All required controllers properly delegated to user slice"
else
    echo "❌ cgroup.subtree_control file not found in user slice"
    echo "   This indicates cgroups v2 delegation is not working."
    exit 1
fi

echo "✓ Cgroup controller delegation check passed"
exit 0