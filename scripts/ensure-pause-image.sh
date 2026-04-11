#!/bin/bash
set -e

# Ensure Podman infra image exists
# This script handles the deprecation of k8s.gcr.io by falling back to registry.k8s.io and other sources
# Uses PODMAN_CMD environment variable if set (for runtime override support)

PODMAN_CMD="${PODMAN_CMD:-podman}"
PAUSE_IMAGE_TAG="${PAUSE_IMAGE_TAG:-3.9}"
TARGET_TAG="${TARGET_TAG:-registry.k8s.io/pause:${PAUSE_IMAGE_TAG}}"

echo "Ensuring Podman infra image exists..."

if ${PODMAN_CMD} image exists "${TARGET_TAG}"; then
    echo "✓ Image ${TARGET_TAG} found locally"
    exit 0
fi

echo "⚠️  Default pause image (${TARGET_TAG}) missing. Attempting to pull..."

# Try 1: New K8s Registry (preferred source)
echo "  Trying registry.k8s.io/pause:${PAUSE_IMAGE_TAG}..."
if ${PODMAN_CMD} pull "registry.k8s.io/pause:${PAUSE_IMAGE_TAG}" 2>/dev/null; then
    echo "✓ Pulled registry.k8s.io/pause:${PAUSE_IMAGE_TAG}"
    echo "  Tagging as ${TARGET_TAG} for compatibility..."
    ${PODMAN_CMD} tag "registry.k8s.io/pause:${PAUSE_IMAGE_TAG}" "${TARGET_TAG}"
    exit 0
fi

# Try 2: Google Container Registry mirror
echo "  registry.k8s.io pull failed. Trying gcr.io..."
if ${PODMAN_CMD} pull "gcr.io/google-containers/pause:${PAUSE_IMAGE_TAG}" 2>/dev/null; then
    echo "✓ Pulled gcr.io/google-containers/pause:${PAUSE_IMAGE_TAG}"
    echo "  Tagging as ${TARGET_TAG} for compatibility..."
    ${PODMAN_CMD} tag "gcr.io/google-containers/pause:${PAUSE_IMAGE_TAG}" "${TARGET_TAG}"
    exit 0
fi

# Try 3: Quay.io mirror
echo "  gcr.io pull failed. Trying quay.io..."
if ${PODMAN_CMD} pull "quay.io/openshift/origin-pod:latest" 2>/dev/null; then
    echo "✓ Pulled quay.io/openshift/origin-pod:latest"
    echo "  Tagging as ${TARGET_TAG} for compatibility..."
    ${PODMAN_CMD} tag "quay.io/openshift/origin-pod:latest" "${TARGET_TAG}"
    exit 0
fi

echo "⚠️  Warning: Could not pull pause image from any source."
echo "   'podman play kube' might fail if image is not in cache."
echo ""
echo "   Manual fix options:"
echo "   1. podman pull registry.k8s.io/pause:${PAUSE_IMAGE_TAG} && podman tag registry.k8s.io/pause:${PAUSE_IMAGE_TAG} ${TARGET_TAG}"
echo "   2. Set PODMAN_INFRA_IMAGE environment variable"
exit 0 # Don't fail the build, let podman play kube try its luck
