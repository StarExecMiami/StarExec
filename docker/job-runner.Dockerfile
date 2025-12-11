# StarExec Job Runner Image
#
# Minimal base image for executing solver jobs in containers.
# Optimized for size: ~19 MB (vs 115 MB before optimization)
#
# =============================================================================
# IMAGE SIZE OPTIMIZATION (v2.1.0)
# =============================================================================
# This image was reduced from 115 MB to 19 MB by removing packages that are
# only needed in SGE/Local backends but not in containerized execution:
#
#   REMOVED (not used in containers):
#   - postgresql16-client (psql only used in SGE/Local via functions.bash)
#   - python3 (run_image.py runs on head node, not in container)
#   - perl (GetComputerInfo rewritten as shell script)
#   - bind-tools (busybox nslookup sufficient)
#   - iputils (ping skipped in containers - no CAP_NET_RAW)
#   - sudo (containers run as root with namespace isolation)
#   - util-linux full (only minimal utils needed)
#
#   KEPT (essential for solver execution):
#   - bash, coreutils: entrypoint.sh and solver scripts
#   - gcompat, libstdc++, libgcc: solver binary compatibility
#   - procps-ng: process monitoring
#   - numactl: runsolver dependency
#   - tcsh: some solvers use #!/bin/tcsh
#
# See docs/JOB_RUNNER_IMAGE_OPTIMIZATION.md for full analysis.
#
# =============================================================================
# PRODUCTION USE
# =============================================================================
#   docker pull ghcr.io/starexecmiami/starexec-job-runner:latest
#
# =============================================================================
# LOCAL DEVELOPMENT
# =============================================================================
#   make build-job-runner
#
# =============================================================================

FROM docker.io/library/alpine:3.21

LABEL maintainer="StarExec Team"
LABEL org.opencontainers.image.title="StarExec Job Runner"
LABEL org.opencontainers.image.description="Minimal base image for executing StarExec solver jobs"
LABEL org.opencontainers.image.version="2.1.0"
LABEL org.opencontainers.image.source="https://github.com/StarExecMiami/StarExec"

# Install ONLY essential runtime dependencies
# Every package is justified - see docs/JOB_RUNNER_IMAGE_OPTIMIZATION.md
RUN apk add --no-cache \
    # Core execution requirements
    bash \
    coreutils \
    # Solver binary compatibility (glibc + C++ runtime)
    gcompat \
    libstdc++ \
    libgcc \
    # Process control and monitoring
    procps-ng \
    # runsolver dependency (libnuma)
    numactl \
    # Alternative shells (some solvers need tcsh)
    tcsh \
    # Basic utilities
    findutils \
    grep \
    sed \
    && rm -rf /var/cache/apk/* /tmp/*

# Create users for job isolation
# Note: In containerized mode, jobs run as root with container isolation
# These users exist for compatibility with solvers that expect them
RUN adduser -D -s /bin/bash -h /home/starexec_user starexec_user \
    && addgroup -g 2001 starexec1 \
    && adduser -D -u 2001 -G starexec1 -s /bin/bash starexec1 \
    && addgroup -g 2002 starexec2 \
    && adduser -D -u 2002 -G starexec2 -s /bin/bash starexec2 \
    && mkdir -p /starexec/output /starexec/input /starexec/solver \
    /starexec/pre-processor /starexec/post-processor \
    && chown -R starexec_user:starexec_user /starexec

# Copy runsolver binary for resource limiting
COPY --chmod=755 starexec-app/src/main/java/org/starexec/config/sge/RunSolverSource/runsolver /usr/local/bin/runsolver

# Copy GetComputerInfo script (shell version - 154 lines vs 36MB Perl)
# Backward compatibility: create symlink at legacy path
COPY --chmod=755 scripts/GetComputerInfo /usr/local/bin/GetComputerInfo
RUN mkdir -p /home/starexec/bin && \
    ln -s /usr/local/bin/GetComputerInfo /home/starexec/bin/GetComputerInfo

# Copy the entrypoint script
COPY --chmod=755 docker/job-entrypoint.sh /starexec/entrypoint.sh

WORKDIR /starexec

# Default environment variables
ENV STAREXEC_OUTPUT_DIR=/starexec/output \
    STAREXEC_INPUT_DIR=/starexec/input \
    STAREXEC_SOLVER_PATH=/starexec/solver/starexec_run \
    STAREXEC_PRE_PROCESSOR_PATH=/starexec/pre-processor/starexec_run \
    STAREXEC_POST_PROCESSOR_PATH=/starexec/post-processor/starexec_run \
    STAREXEC_BENCHMARK_PATH=/starexec/input/benchmark \
    STAREXEC_CPU_LIMIT=600 \
    STAREXEC_WALLCLOCK_LIMIT=600 \
    STAREXEC_MEM_LIMIT=2048

ENTRYPOINT ["/bin/bash", "/starexec/entrypoint.sh"]
