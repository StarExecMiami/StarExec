#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/lib/cli-output.sh"

# Ensure Podman infra image exists
# This script handles the deprecation of k8s.gcr.io by falling back to registry.k8s.io and other sources
# Uses PODMAN_CMD environment variable if set (for runtime override support)

PODMAN_CMD="${PODMAN_CMD:-podman}"
PAUSE_IMAGE_TAG="${PAUSE_IMAGE_TAG:-3.9}"
TARGET_TAG="${TARGET_TAG:-registry.k8s.io/pause:${PAUSE_IMAGE_TAG}}"

ui_info "Ensuring Podman infra image exists..."

if ${PODMAN_CMD} image exists "${TARGET_TAG}"; then
    ui_detail "Image ${TARGET_TAG} found locally"
    exit 0
fi

ui_info "Pause image (${TARGET_TAG}) not cached. Pulling..."

# Try 1: New K8s Registry (preferred source)
ui_detail "Trying registry.k8s.io/pause:${PAUSE_IMAGE_TAG}..."
# PODMAN_CMD intentionally contains a command prefix and runtime options.
# shellcheck disable=SC2086
if ui_run ${PODMAN_CMD} pull "registry.k8s.io/pause:${PAUSE_IMAGE_TAG}"; then
    ui_success "Pulled registry.k8s.io/pause:${PAUSE_IMAGE_TAG}"
    ui_detail "Tagging as ${TARGET_TAG} for compatibility..."
    ${PODMAN_CMD} tag "registry.k8s.io/pause:${PAUSE_IMAGE_TAG}" "${TARGET_TAG}"
    exit 0
fi

# Try 2: Google Container Registry mirror
ui_warn "registry.k8s.io pull failed. Trying gcr.io..."
# shellcheck disable=SC2086
if ui_run ${PODMAN_CMD} pull "gcr.io/google-containers/pause:${PAUSE_IMAGE_TAG}"; then
    ui_success "Pulled gcr.io/google-containers/pause:${PAUSE_IMAGE_TAG}"
    ui_detail "Tagging as ${TARGET_TAG} for compatibility..."
    ${PODMAN_CMD} tag "gcr.io/google-containers/pause:${PAUSE_IMAGE_TAG}" "${TARGET_TAG}"
    exit 0
fi

# Try 3: Quay.io mirror
ui_warn "gcr.io pull failed. Trying quay.io..."
# shellcheck disable=SC2086
if ui_run ${PODMAN_CMD} pull "quay.io/openshift/origin-pod:latest"; then
    ui_success "Pulled quay.io/openshift/origin-pod:latest"
    ui_detail "Tagging as ${TARGET_TAG} for compatibility..."
    ${PODMAN_CMD} tag "quay.io/openshift/origin-pod:latest" "${TARGET_TAG}"
    exit 0
fi

ui_warn "Could not pull pause image from any source."
ui_info "  'podman play kube' might fail if image is not in cache."
ui_info "  Manual fix options:"
ui_info "  1. podman pull registry.k8s.io/pause:${PAUSE_IMAGE_TAG} && podman tag registry.k8s.io/pause:${PAUSE_IMAGE_TAG} ${TARGET_TAG}"
ui_info "  2. Set PODMAN_INFRA_IMAGE environment variable"
exit 0 # Don't fail the build, let podman play kube try its luck
