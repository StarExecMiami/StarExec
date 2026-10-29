#!/bin/sh
# scripts/cleanup-deployment.sh
# Robust cleanup script for StarExec deployment
# Handles different Podman versions and potential missing flags

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/lib/cli-output.sh"

RELEASE_NAME="${1:-starexec}"
PODMAN_CMD="${PODMAN_CMD:-podman}"
FORCE="${2:-false}"

ui_info "Cleaning up existing StarExec deployment resources..."

# 1. Remove Pods
# We need to find pods associated with the release.
# Modern Podman supports --filter, older ones might not.

ui_detail "Scanning for pods..."
POD_IDS=""

# Try obtaining Pod IDs using multiple methods

# Method 1: Using --filter (standard for modern Podman)
if PODS=$($PODMAN_CMD pod ls --filter "label=app.kubernetes.io/instance=$RELEASE_NAME" --format "{{.Id}}" 2>/dev/null); then
    POD_IDS="$POD_IDS $PODS"
fi

# Method 2: Fallback to name matching if filter returned nothing or failed
# This catches cases where --filter isn't supported or labels weren't applied correctly
ALL_PODS=$($PODMAN_CMD pod ls --format "{{.Id}} {{.Name}} {{.Labels}}" 2>/dev/null || true)
if [ -n "$ALL_PODS" ]; then
     # Grep for release name in names or legacy names
    MATCHED=$(echo "$ALL_PODS" | grep -E "$RELEASE_NAME|starexec-pod|starexec" | awk '{print $1}')
    POD_IDS="$POD_IDS $MATCHED"
fi

# Deduplicate IDs
POD_IDS=$(echo "$POD_IDS" | tr ' ' '\n' | sort -u | grep -v '^$')

if [ -n "$POD_IDS" ]; then
    ui_detail "Found pods to remove: $POD_IDS"
    # PODMAN_CMD is intentionally split into its command prefix and options.
    # shellcheck disable=SC2086
    echo "$POD_IDS" | ui_run xargs -n 1 $PODMAN_CMD pod rm -f || {
        ui_warn "Some pods could not be removed (continuing)"
    }
else
    ui_detail "Pod queries returned no matching pods."
fi

# 2. Cleanup Secrets
# Secret filtering is newer than pod filtering.
ui_detail "Scanning for secrets..."
SECRET_NAMES=""

# Method 1: --filter
if SECRETS=$($PODMAN_CMD secret ls --filter "name=$RELEASE_NAME" --format "{{.Name}}" 2>/dev/null); then
    SECRET_NAMES="$SECRET_NAMES $SECRETS"
fi

# Method 2: Name grep
ALL_SECRETS=$($PODMAN_CMD secret ls --format "{{.Name}}" 2>/dev/null || true)
if [ -n "$ALL_SECRETS" ]; then
    # Match secrets containing the release name or specific known secrets
    MATCHED=$(echo "$ALL_SECRETS" | grep -E "$RELEASE_NAME|secret-postgres" | grep -v "grep")
    SECRET_NAMES="$SECRET_NAMES $MATCHED"
fi

# Deduplicate
SECRET_NAMES=$(echo "$SECRET_NAMES" | tr ' ' '\n' | sort -u | grep -v '^$')

if [ -n "$SECRET_NAMES" ]; then
    ui_detail "Found secrets to remove: $SECRET_NAMES"
    # shellcheck disable=SC2086
    echo "$SECRET_NAMES" | ui_run xargs -n 1 $PODMAN_CMD secret rm || true
else
    ui_detail "Secret queries returned no matching secrets."
fi

ui_info "Cleanup steps finished; review any warnings above."
