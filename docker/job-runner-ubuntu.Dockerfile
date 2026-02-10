# =============================================================================
# ⚠️  DEPRECATED - Ubuntu-based Job Runner Image
# =============================================================================
#
# THIS FILE IS DEPRECATED AND WILL BE REMOVED IN A FUTURE RELEASE.
#
# The Alpine-based image (job-runner.Dockerfile) is now the recommended and
# only officially supported job runner image. It provides:
#
#   ✅ 4x smaller image size (~15 MB vs ~80 MB)
#   ✅ glibc compatibility via gcompat for most solver binaries
#   ✅ Faster container startup times
#   ✅ Reduced attack surface
#   ✅ Multi-architecture support (amd64, arm64)
#   ✅ Automated builds via GitHub Actions
#
# MIGRATION:
#   Use the official image from GitHub Container Registry:
#
#     docker pull ghcr.io/starexecmiami/starexec-job-runner:latest
#
#   Or set the environment variable:
#
#     STAREXEC_CONTAINER_JOB_IMAGE=ghcr.io/starexecmiami/starexec-job-runner:latest
#
# IF YOU ABSOLUTELY NEED UBUNTU:
#   If you have a specific solver that requires full glibc and fails with
#   Alpine's gcompat, you can still build this image locally:
#
#     podman build -t starexec/job-runner:ubuntu -f docker/job-runner-ubuntu.Dockerfile .
#
#   Then configure:
#
#     STAREXEC_CONTAINER_JOB_IMAGE=starexec/job-runner:ubuntu
#
#   However, please report such compatibility issues so we can investigate.
#
# =============================================================================
# @deprecated Since v2.0.0 - Use job-runner.Dockerfile instead
# =============================================================================

# StarExec Job Runner Image (Ubuntu/glibc version)
# 
# This is an alternative base image for maximum binary compatibility.
# Use this if you encounter issues with solver binaries on the Alpine version.
#
# Build:
#   podman build -t starexec/job-runner:ubuntu -f docker/job-runner-ubuntu.Dockerfile .
#
# Usage:
#   Set STAREXEC_CONTAINER_JOB_IMAGE=starexec/job-runner:ubuntu in environment
#
# Note: This image is ~4x larger than the Alpine version (~80 MB vs ~20 MB)
#       but provides full glibc compatibility for all solver binaries.

FROM docker.io/library/ubuntu:22.04

LABEL maintainer="StarExec Team"
LABEL org.opencontainers.image.title="StarExec Job Runner (Ubuntu)"
LABEL org.opencontainers.image.description="Ubuntu-based image for maximum solver binary compatibility"
LABEL org.opencontainers.image.version="2.0.0"
LABEL org.opencontainers.image.source="https://github.com/StarExecMiami/StarExec"

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Install minimal runtime dependencies in a single optimized layer
# Note: libc6, libstdc++6, libgcc-s1 are already in base image, no need to reinstall
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       coreutils \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
    && apt-get clean

# Create non-root user for security
# --no-log-init prevents large sparse files when UID > 65535
RUN useradd --no-log-init --create-home --shell /bin/bash starexec_user \
    && mkdir -p /starexec/output /starexec/input /starexec/solver \
       /starexec/pre-processor /starexec/post-processor \
    && chown -R starexec_user:starexec_user /starexec

# Copy the entrypoint script and set permissions in single layer
COPY --chmod=755 docker/job-entrypoint.sh /starexec/entrypoint.sh

# Set working directory
WORKDIR /starexec

# Switch to non-root user
USER starexec_user

# Default environment variables (can be overridden at runtime)
ENV STAREXEC_OUTPUT_DIR=/starexec/output \
    STAREXEC_INPUT_DIR=/starexec/input \
    STAREXEC_SOLVER_PATH=/starexec/solver/starexec_run \
    STAREXEC_PRE_PROCESSOR_PATH=/starexec/pre-processor/starexec_run \
    STAREXEC_POST_PROCESSOR_PATH=/starexec/post-processor/starexec_run \
    STAREXEC_BENCHMARK_PATH=/starexec/input/benchmark \
    STAREXEC_CPU_LIMIT=600 \
    STAREXEC_WALLCLOCK_LIMIT=600 \
    STAREXEC_MEM_LIMIT=2048

# Set entrypoint
ENTRYPOINT ["/bin/bash", "/starexec/entrypoint.sh"]
