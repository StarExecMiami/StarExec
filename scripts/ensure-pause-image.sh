#!/bin/bash
set -e

# Ensure Podman infra image exists
# This script handles the deprecation of k8s.gcr.io by falling back to registry.k8s.io and docker.io

PAUSE_IMAGE_TAG="3.5"
TARGET_TAG="k8s.gcr.io/pause:${PAUSE_IMAGE_TAG}"

echo "Ensuring Podman infra image exists..."

if podman image exists "${TARGET_TAG}"; then
    echo "✓ Image ${TARGET_TAG} found locally"
    exit 0
fi

echo "⚠️  Default pause image (${TARGET_TAG}) missing. Attempting to pull..."

# Try 1: Original (likely deprecated/redirected)
if podman pull "${TARGET_TAG}" 2>/dev/null; then
    echo "✓ Pulled ${TARGET_TAG}"
    exit 0
fi

echo "  Pull failed (likely deprecated registry). Pulling from registry.k8s.io..."

# Try 2: New K8s Registry
if podman pull "registry.k8s.io/pause:${PAUSE_IMAGE_TAG}" 2>/dev/null; then
    echo "✓ Pulled registry.k8s.io/pause:${PAUSE_IMAGE_TAG}"
    echo "  Tagging as ${TARGET_TAG} for compatibility..."
    podman tag "registry.k8s.io/pause:${PAUSE_IMAGE_TAG}" "${TARGET_TAG}"
    exit 0
fi

echo "  registry.k8s.io pull failed. Trying docker.io fallback..."

# Try 3: Docker Hub
if podman pull "docker.io/library/pause:${PAUSE_IMAGE_TAG}" 2>/dev/null; then
    echo "✓ Pulled docker.io/library/pause:${PAUSE_IMAGE_TAG}"
    echo "  Tagging as ${TARGET_TAG} for compatibility..."
    podman tag "docker.io/library/pause:${PAUSE_IMAGE_TAG}" "${TARGET_TAG}"
    exit 0
fi

echo "⚠️  Warning: Could not pull pause image from any source."
echo "   'podman play kube' might fail if image is not in cache."
exit 0 # Don't fail the build, let podman play kube try its luck
