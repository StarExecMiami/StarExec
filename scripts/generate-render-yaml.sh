#!/usr/bin/env bash
# StarExec render.yaml generator
# Generates deployment manifest from template with environment variable substitution

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE_FILE="$PROJECT_ROOT/render.yaml.template"
OUTPUT_FILE="$PROJECT_ROOT/render.yaml"

# Set defaults
ENVIRONMENT="${ENV:-dev}"
VOLUME_PREFIX="${VOLUME_PREFIX:-starexec}"
DEFAULT_DATA_VOL="${VOLUME_PREFIX}-${ENVIRONMENT}-data"
DEFAULT_POSTGRES_VOL="${VOLUME_PREFIX}-${ENVIRONMENT}-postgres"

export STAREXEC_DB_USER="${STAREXEC_DB_USER:-starexec}"
if [ -n "${STAREXEC_DB_PASSWORD_FILE:-}" ] && [ -f "${STAREXEC_DB_PASSWORD_FILE}" ]; then
    export STAREXEC_DB_PASSWORD="$(cat "${STAREXEC_DB_PASSWORD_FILE}" | tr -d '\n')"
elif [ -n "${STAREXEC_DB_PASSWORD:-}" ]; then
    export STAREXEC_DB_PASSWORD
else
    if [ "$ENVIRONMENT" != "dev" ] && [ "$ENVIRONMENT" != "local" ]; then
        echo "ERROR: STAREXEC_DB_PASSWORD is not set for '$ENVIRONMENT' environment." \
             "Set STAREXEC_DB_PASSWORD or STAREXEC_DB_PASSWORD_FILE before deploying." >&2
        exit 1
    fi
    echo "WARNING: STAREXEC_DB_PASSWORD is not set. Using insecure dev default. Only acceptable for local dev." >&2
    export STAREXEC_DB_PASSWORD="starexec_dev_password"
fi
export STAREXEC_DB_NAME="${STAREXEC_DB_NAME:-starexec}"
export STAREXEC_DB_PORT="${STAREXEC_DB_PORT:-5432}"
export STAREXEC_DATA_VOL="${STAREXEC_DATA_VOL:-$DEFAULT_DATA_VOL}"
export STAREXEC_SANDBOX_VOL="${STAREXEC_SANDBOX_VOL:-$VOLUME_PREFIX-$ENVIRONMENT-sandbox}"
export STAREXEC_BACKEND_VOL="${STAREXEC_BACKEND_VOL:-$VOLUME_PREFIX-$ENVIRONMENT-backend}"
export STAREXEC_WORK_VOL="${STAREXEC_WORK_VOL:-$VOLUME_PREFIX-$ENVIRONMENT-work}"
export STAREXEC_POSTGRES_VOL="${STAREXEC_POSTGRES_VOL:-$DEFAULT_POSTGRES_VOL}"
export STAREXEC_DB_HOST="${STAREXEC_DB_HOST:-localhost}"
export STAREXEC_BACKEND_TYPE="${STAREXEC_BACKEND_TYPE:-local}"
export APP_PORT="${APP_PORT:-7827}"
export IMAGE_NAME="${IMAGE_NAME:-localhost/local/starexec}"
export IMAGE_TAG="${IMAGE_TAG:-latest}"

# Check if template exists
if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "Error: Template file not found: $TEMPLATE_FILE" >&2
    exit 1
fi

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

# manifest_safe NAME VALUE: refuse a value that would end its quoted string in render.yaml early
manifest_safe() {
    case $2 in
        *[\"\\]* | *$'\n'*)
            fail "$1 contains a double quote, a backslash or a line break, which render.yaml cannot hold: $2" ;;
    esac
}

# sed_escape VALUE: VALUE as the replacement of a s|...|...| command, with & | and \ taken literally
sed_escape() {
    printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'
}

# The Podman backend starts each job in a container through the container engine's socket. So the pod needs
# the socket, the group that owns it, the data volume's path on the host (job containers mount files from it)
# and the job image: what deploy-podman-helm passes to the chart. The template's podman-backend sections hold
# them; they are removed for any other backend.
backend_subs=()
if [ "$STAREXEC_BACKEND_TYPE" = podman ]; then
    # The same default as the Makefile's PODMAN_SOCKET_PATH, and the service that provides that socket.
    if [ "${PODMAN_REQUIRES_SUDO:-no}" = yes ]; then
        default_socket=/run/podman/podman.sock
        socket_service="sudo systemctl enable --now podman.socket"
    else
        default_socket="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
        socket_service="systemctl --user enable --now podman.socket"
    fi
    PODMAN_SOCKET_PATH="${PODMAN_SOCKET_PATH:-$default_socket}"
    STAREXEC_CONTAINER_JOB_IMAGE="${STAREXEC_CONTAINER_JOB_IMAGE:-ghcr.io/starexecmiami/starexec-job-runner:latest}"
    manifest_safe PODMAN_SOCKET_PATH "$PODMAN_SOCKET_PATH"
    manifest_safe STAREXEC_CONTAINER_JOB_IMAGE "$STAREXEC_CONTAINER_JOB_IMAGE"
    [ -S "$PODMAN_SOCKET_PATH" ] ||
        fail "no container engine socket at $PODMAN_SOCKET_PATH, and the Podman backend starts jobs through it." \
            "Start it with: $socket_service. Or set PODMAN_SOCKET_PATH."
    PODMAN_SOCKET_GID=$(stat -c '%g' "$PODMAN_SOCKET_PATH")
    if [ -z "${STAREXEC_CONTAINER_HOST_DATA_PATH:-}" ]; then
        read -r -a podman_cmd <<< "${PODMAN_CMD:-podman}"
        # On a first deploy the data volume does not exist yet, and podman play kube would create it. Create it
        # now instead, the same way, so that its path on the host is known.
        if ! "${podman_cmd[@]}" volume exists "$STAREXEC_DATA_VOL"; then
            "${podman_cmd[@]}" volume create "$STAREXEC_DATA_VOL" > /dev/null ||
                fail "cannot create the data volume $STAREXEC_DATA_VOL. Or set STAREXEC_CONTAINER_HOST_DATA_PATH."
            echo "Created the data volume $STAREXEC_DATA_VOL"
        fi
        if ! STAREXEC_CONTAINER_HOST_DATA_PATH=$("${podman_cmd[@]}" volume inspect --format '{{.Mountpoint}}' \
            "$STAREXEC_DATA_VOL") || [ -z "$STAREXEC_CONTAINER_HOST_DATA_PATH" ]; then
            fail "cannot read the path on the host of the data volume $STAREXEC_DATA_VOL." \
                "Or set STAREXEC_CONTAINER_HOST_DATA_PATH."
        fi
    fi
    manifest_safe STAREXEC_CONTAINER_HOST_DATA_PATH "$STAREXEC_CONTAINER_HOST_DATA_PATH"
    sections=(-e '/# BEGIN podman-backend/d' -e '/# END podman-backend/d')
    backend_subs=(
        -e "s|\${PODMAN_SOCKET_PATH}|$(sed_escape "$PODMAN_SOCKET_PATH")|g"
        -e "s|\${PODMAN_SOCKET_GID}|$PODMAN_SOCKET_GID|g"
        -e "s|\${STAREXEC_CONTAINER_HOST_DATA_PATH}|$(sed_escape "$STAREXEC_CONTAINER_HOST_DATA_PATH")|g"
        -e "s|\${STAREXEC_CONTAINER_JOB_IMAGE:-ghcr.io/starexecmiami/starexec-job-runner:latest}|$(sed_escape "$STAREXEC_CONTAINER_JOB_IMAGE")|g"
    )
else
    sections=(-e '/# BEGIN podman-backend/,/# END podman-backend/d')
fi

echo "Generating render.yaml from template..."
echo "  Using environment:"
echo "    DB_USER:    $STAREXEC_DB_USER"
echo "    DB_NAME:    $STAREXEC_DB_NAME"
echo "    APP_PORT:   $APP_PORT"
echo "    DATA_VOL:   $STAREXEC_DATA_VOL"
echo "    POSTGRES_VOL: $STAREXEC_POSTGRES_VOL"
echo "    IMAGE:      $IMAGE_NAME:$IMAGE_TAG"
echo "    BACKEND:    $STAREXEC_BACKEND_TYPE"
if [ "$STAREXEC_BACKEND_TYPE" = podman ]; then
    echo "    SOCKET:     $PODMAN_SOCKET_PATH (group $PODMAN_SOCKET_GID)"
    echo "    HOST_DATA:  $STAREXEC_CONTAINER_HOST_DATA_PATH"
    echo "    JOB_IMAGE:  $STAREXEC_CONTAINER_JOB_IMAGE"
fi

# Use sed to replace placeholders with actual values.
# Note: template must use the corresponding ${VAR:-default} placeholders.
sed "${sections[@]}" "$TEMPLATE_FILE" |
    sed -e "s|\${STAREXEC_DB_USER:-starexec}|$STAREXEC_DB_USER|g" \
    -e "s|\${STAREXEC_DB_PASSWORD:-starexec_dev_password}|$STAREXEC_DB_PASSWORD|g" \
    -e "s|\${STAREXEC_DB_NAME:-starexec}|$STAREXEC_DB_NAME|g" \
    -e "s|\${STAREXEC_DB_PORT:-5432}|$STAREXEC_DB_PORT|g" \
    -e "s|\${STAREXEC_DB_HOST:-localhost}|$STAREXEC_DB_HOST|g" \
    -e "s|\${APP_PORT:-7827}|$APP_PORT|g" \
    -e "s|\${STAREXEC_BACKEND_TYPE:-local}|$STAREXEC_BACKEND_TYPE|g" \
    -e "s|\${STAREXEC_DATA_VOL:-starexec-dev-data}|$STAREXEC_DATA_VOL|g" \
    -e "s|\${STAREXEC_SANDBOX_VOL:-starexec-dev-sandbox}|$STAREXEC_SANDBOX_VOL|g" \
    -e "s|\${STAREXEC_BACKEND_VOL:-starexec-dev-backend}|$STAREXEC_BACKEND_VOL|g" \
    -e "s|\${STAREXEC_WORK_VOL:-starexec-dev-work}|$STAREXEC_WORK_VOL|g" \
    -e "s|\${STAREXEC_POSTGRES_VOL:-starexec-dev-postgres}|$STAREXEC_POSTGRES_VOL|g" \
    -e "s|\${IMAGE_NAME:-localhost/local/starexec}:|$IMAGE_NAME:|g" \
    -e "s|\${IMAGE_TAG:-latest}|$IMAGE_TAG|g" \
    ${backend_subs[@]+"${backend_subs[@]}"} > "$OUTPUT_FILE"

echo "✓ Generated: $OUTPUT_FILE"
