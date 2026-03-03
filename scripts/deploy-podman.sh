#!/bin/sh
# StarExec Podman Deployment Script
# Handles the complex deployment logic extracted from Makefile

set -e

# Configuration (passed as environment variables)
ENV="${ENV:-dev}"
RELEASE_NAME="${RELEASE_NAME:-starexec}"
IMAGE_NAME="${IMAGE_NAME:-$RELEASE_NAME}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
CHART_DIR="${CHART_DIR:-./charts/starexec}"
SECRET_NAME="${SECRET_NAME:-secret-postgres}"
VALS="${VALS:-$CHART_DIR/values-$ENV.yaml}"
VOLUME_PREFIX="${VOLUME_PREFIX:-starexec}"
FORCE="${FORCE:-0}"
DRY_RUN="${DRY_RUN:-0}"

# Use PODMAN_CMD from environment or default to podman
PODMAN_CMD="${PODMAN_CMD:-podman}"

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    printf "${GREEN}[INFO]${NC} %s\n" "$1"
}

log_warn() {
    printf "${YELLOW}[WARN]${NC} %s\n" "$1"
}

log_error() {
    printf "${RED}[ERROR]${NC} %s\n" "$1" >&2
}

cleanup_deployment() {
    log_info "Cleaning up existing StarExec pods, containers, and secrets..."

    # Remove existing pods
    ${PODMAN_CMD} pod rm -f starexec 2>/dev/null || true

    # Remove orphaned containers
    for container in starexec-app starexec-postgres; do
        ${PODMAN_CMD} container rm -f "$container" 2>/dev/null || true
    done

    # Remove secrets
    log_info "  Removing existing secrets..."
    ${PODMAN_CMD} secret rm "${RELEASE_NAME}-postgres-credentials" 2>/dev/null || true
}

deploy_via_helm() {
    log_info "Deploying via Helm..."
    # For Podman, use static template with env substitution
    if [ "$ENV" = "dev" ] || [ "$ENV" = "ci" ]; then
        log_info "Using Pod template for Podman..."
        export IMAGE_NAME="${IMAGE_NAME:-starexec}"
        export IMAGE_TAG="${IMAGE_TAG:-latest}"
        export STAREXEC_DATA_VOL="${VOLUME_PREFIX}-${ENV}-data"
        export STAREXEC_POSTGRES_VOL="${VOLUME_PREFIX}-${ENV}-postgres"
        export STAREXEC_DB_HOST="${STAREXEC_DB_HOST:-localhost}"
        export STAREXEC_DB_PORT="${STAREXEC_DB_PORT:-5432}"
        export STAREXEC_DB_USER="${STAREXEC_DB_USER:-starexec}"
        if [ -z "${STAREXEC_DB_PASSWORD:-}" ]; then
            echo "WARNING: STAREXEC_DB_PASSWORD is not set. Using insecure dev default. Set this variable before deploying." >&2
            export STAREXEC_DB_PASSWORD="starexec_dev_password"
        else
            export STAREXEC_DB_PASSWORD
        fi
        export STAREXEC_DB_NAME="${STAREXEC_DB_NAME:-starexec}"
        envsubst < render.yaml.template > render.yaml
        # Deploy the pod
        ./scripts/ensure-pause-image.sh
        ${PODMAN_CMD} play kube render.yaml
    else
        # For Kubernetes, use Helm
        helm template "$RELEASE_NAME" "$CHART_DIR" -f "$VALS" \
            --set image.repository="$IMAGE_REPO" \
            --set image.tag="$IMAGE_VER" \
            --set image.pullPolicy=Never > render.yaml
    fi
}

deploy_via_direct() {
    log_info "Deploying directly..."
    # Direct deployment logic
}

main() {
    log_info "Starting Podman deployment for ENV=$ENV"

    # Safety checks
    if [ "$ENV" = "prod" ] && [ "${STAREXEC_DB_PASSWORD:-$DB_PASSWORD_DEFAULT}" = "$DB_PASSWORD_DEFAULT" ]; then
        log_error "Cannot deploy to production with default password"
        exit 1
    fi

    cleanup_deployment

    if command -v helm >/dev/null 2>&1; then
        deploy_via_helm
    else
        deploy_via_direct
    fi

    log_info "Deployment complete!"
}

main "$@"
