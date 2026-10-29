#!/bin/bash
#
# Preflight check for Podman/Docker socket accessibility
# This script validates that the socket is accessible and provides
# detailed diagnostics if there are permission issues.
#
# Usage: ./scripts/preflight-podman.sh [socket_path]
# Default socket path: /var/run/docker.sock or /run/podman/podman.sock
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/lib/cli-output.sh"

SOCKET_PATH="${1:-}"
CURRENT_UID=$(id -u)
CURRENT_USER=$(id -un)
CURRENT_GID=$(id -g)
CURRENT_GROUPS=$(id -G)
PODMAN_AUTOSTART_REASON=""

attempt_rootless_podman_socket_autostart() {
    local socket_path="$1"
    PODMAN_AUTOSTART_REASON=""

    if ! command -v systemctl >/dev/null 2>&1; then
        PODMAN_AUTOSTART_REASON="no-systemctl"
        return 1
    fi

    if ! [[ "$socket_path" =~ ^/run/user/[0-9]+/ ]]; then
        PODMAN_AUTOSTART_REASON="not-rootless"
        return 1
    fi

    local socket_status
    set +e
    socket_status=$(systemctl --user is-active podman.socket 2>&1)
    set -e

    if echo "$socket_status" | grep -q masked; then
        PODMAN_AUTOSTART_REASON="masked"
        return 1
    fi

    ui_warn "Podman socket not running. Attempting auto-start via systemctl --user..."
    set +e
    systemctl --user start podman.socket 2>/dev/null
    set -e

    local retries=0
    while [ "$retries" -lt 5 ] && [ ! -S "$socket_path" ]; do
        sleep 1
        retries=$((retries + 1))
    done

    if [ -S "$socket_path" ]; then
        ui_success "Podman socket started automatically."
        return 0
    fi

    PODMAN_AUTOSTART_REASON="autostart-failed"
    return 1
}

print_podman_autostart_context() {
    local socket_path="$1"

    case "$PODMAN_AUTOSTART_REASON" in
        masked)
            ui_warn "Podman socket is masked by system"
            ui_info "  To enable it, copy systemd units to your user:"
            ui_info "  mkdir -p ~/.config/systemd/user/"
            ui_info "  cp /usr/lib/systemd/user/podman.* ~/.config/systemd/user/"
            ui_info "  systemctl --user daemon-reload"
            ui_info "  systemctl --user enable --now podman.socket"
            ;;
        no-systemctl)
            ui_warn "systemd not found on this system"
            ui_info "  Ensure Podman daemon is running. On macOS/Windows: podman machine start"
            ui_info "  On rootless Linux without systemd:"
            ui_info "  podman system service --time=0 unix:///run/user/$CURRENT_UID/podman/podman.sock &"
            ;;
        not-rootless)
            if [[ "$socket_path" == /run/podman/* ]]; then
                ui_info "  Start the rootful Podman socket service:"
                ui_info "  sudo systemctl start podman.socket"
            fi
            ;;
    esac
}

# Find socket if not provided
if [ -z "$SOCKET_PATH" ]; then
    if [ -e "/var/run/docker.sock" ]; then
        SOCKET_PATH="/var/run/docker.sock"
    elif [ -e "/run/user/$CURRENT_UID/podman/podman.sock" ]; then
        SOCKET_PATH="/run/user/$CURRENT_UID/podman/podman.sock"
    elif [ -e "/run/podman/podman.sock" ]; then
        SOCKET_PATH="/run/podman/podman.sock"
    else
        ROOTLESS_SOCKET_PATH="/run/user/$CURRENT_UID/podman/podman.sock"
        if attempt_rootless_podman_socket_autostart "$ROOTLESS_SOCKET_PATH"; then
            SOCKET_PATH="$ROOTLESS_SOCKET_PATH"
        else
            ui_error "No container socket found at standard locations"
            ui_info "  Standard socket paths:"
            ui_info "  - /var/run/docker.sock"
            ui_info "  - /run/user/\$UID/podman/podman.sock"
            ui_info "  - /run/podman/podman.sock"
            ui_info "  To find your socket: find /run -name '*podman.sock' -o -name '*docker.sock' 2>/dev/null"
            print_podman_autostart_context "$ROOTLESS_SOCKET_PATH"
            exit 1
        fi
    fi
fi

ui_info "Checking Podman socket access..."
ui_detail "Current user: $CURRENT_USER (UID: $CURRENT_UID, GID: $CURRENT_GID)"
ui_detail "Groups: $CURRENT_GROUPS"

# Check if socket exists
if [ ! -e "$SOCKET_PATH" ]; then
    if ! attempt_rootless_podman_socket_autostart "$SOCKET_PATH"; then
        ui_error "Socket does not exist: $SOCKET_PATH"
        ui_info "  Diagnostics:"
        ui_info "  Is Podman/Docker running?"
        if command -v systemctl &>/dev/null; then
            ui_info "  podman.socket status: $(systemctl --user is-active podman.socket 2>/dev/null || echo 'NOT_FOUND')"
            ui_info "  docker.socket status: $(systemctl is-active docker 2>/dev/null || echo 'NOT_FOUND')"
        fi
        ui_info "  Try starting Podman:"
        ui_info "  systemctl --user start podman.socket  # for rootless"
        ui_info "  sudo systemctl start docker          # for Docker (requires sudo)"
        print_podman_autostart_context "$SOCKET_PATH"
        exit 1
    fi
fi

ui_detail "Socket exists: $SOCKET_PATH"

# Get socket details
SOCKET_OWNER=$(ls -l "$SOCKET_PATH" | awk '{print $3}')
SOCKET_GROUP=$(ls -l "$SOCKET_PATH" | awk '{print $4}')
SOCKET_UID=$(stat -c '%u' "$SOCKET_PATH")
SOCKET_GID=$(stat -c '%g' "$SOCKET_PATH")
SOCKET_PERMS=$(stat -c '%a' "$SOCKET_PATH")

ui_detail "Socket owner: $SOCKET_OWNER (UID: $SOCKET_UID)"
ui_detail "Socket group: $SOCKET_GROUP (GID: $SOCKET_GID)"
ui_detail "Socket permissions: $SOCKET_PERMS"

# Check if socket is readable
if [ -r "$SOCKET_PATH" ]; then
    ui_detail "Socket is readable by current user"
else
    ui_error "Socket is not readable by current user ($CURRENT_USER)"
    ui_info "  Fix options:"
    
    # Case 1: UID mismatch (rootless Podman)
    if [[ "$SOCKET_PATH" =~ /run/user/[0-9]+/ ]]; then
        if [ "$CURRENT_UID" != "$SOCKET_UID" ]; then
            ui_info "  UID mismatch for rootless Podman"
            ui_info "  Your UID: $CURRENT_UID; socket UID: $SOCKET_UID"
            ui_info "  The socket is owned by a different user. Options:"
            ui_info "  Option A: Run container as the socket owner: docker run --user $SOCKET_UID:$SOCKET_GID ..."
            ui_info "  Option B: Inject the socket group: docker-compose group_add: [\"$SOCKET_GID\"]"
            exit 1
        fi
    fi
    
    # Case 2: Group membership
    if [ "$CURRENT_GID" != "$SOCKET_GID" ]; then
        if echo "$CURRENT_GROUPS" | grep -q "\b$SOCKET_GID\b"; then
            ui_info "  User is in socket group but group may not be active"
            ui_info "  Inject the socket group into the container via docker-compose:"
            ui_info "  group_add: [\"$SOCKET_GID\"]"
            exit 1
        fi
    fi
    
    # Generic fix
    if [ "$SOCKET_UID" = "0" ]; then
        ui_info "  Socket is root-owned and not world-readable"
        ui_info "  If running Docker/Podman locally, use rootless mode:"
        ui_info "  systemctl --user enable --now podman.socket"
        ui_info "  Otherwise, ensure container has appropriate group access"
    else
        ui_info "  Permission denied"
        ui_info "  Add your user to the socket owner's group:"
        ui_info "  sudo usermod -aG $SOCKET_GROUP $CURRENT_USER"
        ui_info "  Then log out and log back in to activate the group"
    fi
    exit 1
fi

# Try to connect to the socket
ui_info "Testing connection to socket..."

# Use podman CLI first (more portable), then fall back to docker
if command -v podman &>/dev/null; then
    CHECK_CMD="podman --url unix://$SOCKET_PATH version"
elif command -v docker &>/dev/null; then
    CHECK_CMD="docker -H unix://$SOCKET_PATH version"
else
    ui_warn "Neither 'podman' nor 'docker' CLI found to test socket connection"
    ui_info "  The socket may still be usable; skipping connection test"
    exit 0
fi

if $CHECK_CMD >/dev/null 2>&1; then
    ui_success "Podman socket connection successful"
    exit 0
else
    ui_warn "Socket exists and is readable, but connection failed"
    ui_info "  Inspect the socket and container engine service before retrying."
    ui_info "  Service restart options:"
    if [[ "$SOCKET_PATH" == *"podman"* ]]; then
        ui_info "  systemctl --user restart podman.socket"
    else
        ui_info "  sudo systemctl restart docker"
    fi
    exit 1
fi
