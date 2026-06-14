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

# ANSI Colors (match Makefile)
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

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

    echo -e "${YELLOW}⚠  Podman socket not running. Attempting auto-start via systemctl --user...${RESET}"
    set +e
    systemctl --user start podman.socket 2>/dev/null
    set -e

    local retries=0
    while [ "$retries" -lt 5 ] && [ ! -S "$socket_path" ]; do
        sleep 1
        retries=$((retries + 1))
    done

    if [ -S "$socket_path" ]; then
        echo -e "${GREEN}✓ Podman socket started automatically.${RESET}"
        return 0
    fi

    PODMAN_AUTOSTART_REASON="autostart-failed"
    return 1
}

print_podman_autostart_context() {
    local socket_path="$1"

    case "$PODMAN_AUTOSTART_REASON" in
        masked)
            echo ""
            echo -e "${YELLOW}⚠️  Podman socket is masked by system (common on school-managed networks)${RESET}"
            echo ""
            echo "To enable it, copy systemd units to your user:"
            echo "  mkdir -p ~/.config/systemd/user/"
            echo "  cp /usr/lib/systemd/user/podman.* ~/.config/systemd/user/"
            echo "  systemctl --user daemon-reload"
            echo "  systemctl --user enable --now podman.socket"
            ;;
        no-systemctl)
            echo ""
            echo -e "${YELLOW}⚠️  systemd not found on this system (e.g., macOS, Windows WSL without systemd)${RESET}"
            echo ""
            echo "Ensure Podman daemon is running. On macOS/Windows, this typically means:"
            echo "  podman machine start"
            echo ""
            echo "Or on rootless Linux without systemd:"
            echo "  podman system service --time=0 unix:///run/user/$CURRENT_UID/podman/podman.sock &"
            ;;
        not-rootless)
            if [[ "$socket_path" == /run/podman/* ]]; then
                echo ""
                echo "Start the rootful Podman socket service:"
                echo "  sudo systemctl start podman.socket"
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
            echo -e "${RED}ERROR: No container socket found at standard locations${RESET}"
            echo ""
            echo "Standard socket paths:"
            echo "  - /var/run/docker.sock"
            echo "  - /run/user/\$UID/podman/podman.sock"
            echo "  - /run/podman/podman.sock"
            echo ""
            echo "To find your socket, run:"
            echo "  find /run -name '*podman.sock' -o -name '*docker.sock' 2>/dev/null"
            print_podman_autostart_context "$ROOTLESS_SOCKET_PATH"
            exit 1
        fi
    fi
fi

echo "=================================="
echo "Podman Socket Preflight Check"
echo "=================================="
echo ""
echo "Current User: $CURRENT_USER (UID: $CURRENT_UID, GID: $CURRENT_GID)"
echo "Groups: $CURRENT_GROUPS"
echo ""

# Check if socket exists
if [ ! -e "$SOCKET_PATH" ]; then
    if ! attempt_rootless_podman_socket_autostart "$SOCKET_PATH"; then
        echo -e "${RED}ERROR: Socket does not exist: $SOCKET_PATH${RESET}"
        echo ""
        echo "Diagnostics:"
        echo "   1. Is Podman/Docker running?"
        if command -v systemctl &>/dev/null; then
            echo "      podman.socket status: $(systemctl --user is-active podman.socket 2>/dev/null || echo 'NOT_FOUND')"
            echo "      docker.socket status: $(systemctl is-active docker 2>/dev/null || echo 'NOT_FOUND')"
        fi
        echo "   2. Try starting Podman:"
        echo "      systemctl --user start podman.socket  # for rootless"
        echo "      sudo systemctl start docker          # for Docker (requires sudo)"
        print_podman_autostart_context "$SOCKET_PATH"
        exit 1
    fi
fi

echo -e "${GREEN}[OK]${RESET} Socket exists: $SOCKET_PATH"
echo ""

# Get socket details
SOCKET_OWNER=$(ls -l "$SOCKET_PATH" | awk '{print $3}')
SOCKET_GROUP=$(ls -l "$SOCKET_PATH" | awk '{print $4}')
SOCKET_UID=$(stat -c '%u' "$SOCKET_PATH")
SOCKET_GID=$(stat -c '%g' "$SOCKET_PATH")
SOCKET_PERMS=$(stat -c '%a' "$SOCKET_PATH")

echo "Socket Details:"
echo "  Owner: $SOCKET_OWNER (UID: $SOCKET_UID)"
echo "  Group: $SOCKET_GROUP (GID: $SOCKET_GID)"
echo "  Permissions: $SOCKET_PERMS"
echo ""

# Check if socket is readable
if [ -r "$SOCKET_PATH" ]; then
    echo -e "${GREEN}[OK]${RESET} Socket is readable by current user"
else
    echo -e "${RED}[FAIL]${RESET} Socket is NOT readable by current user ($CURRENT_USER)"
    echo ""
    echo "Fix options:"
    echo ""
    
    # Case 1: UID mismatch (rootless Podman)
    if [[ "$SOCKET_PATH" =~ /run/user/[0-9]+/ ]]; then
        if [ "$CURRENT_UID" != "$SOCKET_UID" ]; then
            echo "   ISSUE: UID mismatch for rootless Podman"
            echo "   Your UID: $CURRENT_UID"
            echo "   Socket UID: $SOCKET_UID"
            echo ""
            echo "   SOLUTION:"
            echo "   The socket is owned by a different user. Options:"
            echo ""
            echo "   Option A: Run container as the socket owner"
            echo "     docker run --user $SOCKET_UID:$SOCKET_GID ..."
            echo ""
            echo "   Option B: Inject the socket group into the container"
            echo "     docker-compose group_add: [\"$SOCKET_GID\"]"
            echo ""
            exit 1
        fi
    fi
    
    # Case 2: Group membership
    if [ "$CURRENT_GID" != "$SOCKET_GID" ]; then
        if echo "$CURRENT_GROUPS" | grep -q "\b$SOCKET_GID\b"; then
            echo "   User is in socket group but group may not be active"
            echo ""
            echo "   SOLUTION:"
            echo "   Inject the socket group into the container via docker-compose:"
            echo "     group_add: [\"$SOCKET_GID\"]"
            exit 1
        fi
    fi
    
    # Generic fix
    if [ "$SOCKET_UID" = "0" ]; then
        echo "   ISSUE: Socket is root-owned and not world-readable"
        echo ""
        echo "   SOLUTION:"
        echo "   If running Docker/Podman locally, use rootless mode:"
        echo "     systemctl --user enable --now podman.socket"
        echo ""
        echo "   Otherwise, ensure container has appropriate group access"
    else
        echo "   ISSUE: Permission denied"
        echo ""
        echo "   SOLUTION:"
        echo "   Add your user to the socket owner's group:"
        echo "     sudo usermod -aG $SOCKET_GROUP $CURRENT_USER"
        echo "     then log out and log back in to activate the group"
    fi
    echo ""
    exit 1
fi

# Try to connect to the socket
echo "Testing connection to socket..."

# Use podman CLI first (more portable), then fall back to docker
if command -v podman &>/dev/null; then
    CHECK_CMD="podman --url unix://$SOCKET_PATH version"
elif command -v docker &>/dev/null; then
    CHECK_CMD="docker -H unix://$SOCKET_PATH version"
else
    echo -e "${YELLOW}[WARN]${RESET} Neither 'podman' nor 'docker' CLI found to test socket connection"
    echo "   The socket may still be usable; skipping connection test"
    exit 0
fi

if $CHECK_CMD >/dev/null 2>&1; then
    echo -e "${GREEN}[OK]${RESET} Socket connection successful"
    echo ""
    echo "=================================="
    echo -e "${GREEN}SUCCESS: All checks passed! Ready for Podman backend${RESET}"
    echo "=================================="
    exit 0
else
    echo -e "${YELLOW}[WARN]${RESET} Socket exists and is readable, but connection failed"
    echo ""
    echo "This may indicate:"
    echo "  - Socket file is corrupted"
    echo "  - Container engine is not responding"
    echo "  - Docker/Podman service needs restart"
    echo ""
    echo "Try restarting the service:"
    if [[ "$SOCKET_PATH" == *"podman"* ]]; then
        echo "  systemctl --user restart podman.socket"
    else
        echo "  sudo systemctl restart docker"
    fi
    exit 1
fi
