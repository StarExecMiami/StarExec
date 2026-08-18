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

# ==============================================================================
# Stage 1: Build runsolver natively on Alpine (musl libc)
# ==============================================================================
# Pinned to the SAME base as the root Dockerfile's runsolver-builder stage (alpine:3.19).
# The two images must ship the same measurement binary, and building identical source on
# two different Alpine releases means two different musl/gcc toolchains and so potentially
# two different binaries -- which is the divergence the vendored-source change exists to
# remove. Deliberately the older of the two bases: a binary built against musl 1.2.4 runs
# on the 3.21 runtime stage below, and CI asserts the resulting SHA-256 matches the app
# image's (see .github/workflows/runsolver-parity.yml). Change both stages or neither.
FROM docker.io/library/alpine:3.19 AS runsolver-builder

# /build, matching the root Dockerfile's runsolver-builder stage, NOT /tmp. gcc embeds the
# compilation directory in the binary's debug information, so the same source built at a
# different path produces a different file: measured, the two stages produced SHA-256
# 05cacb4c... and 08b79941... purely from /build/src versus /tmp/src. Aligning the path is
# what makes the two images' runsolver byte-identical and the CI parity gate meaningful.
WORKDIR /build

# Install build dependencies. curl/tar/bzip2 are gone with the network fetch below.
RUN apk upgrade --no-cache && \
    apk add --no-cache build-base

# Build runsolver from the source vendored in this repository.
#
# This stage used to curl runsolver-3.4.1.tar.bz2 from cril.univ-artois.fr with no
# checksum and no pinned digest. The root Dockerfile was moved off that pattern because
# "runsolver is the instrument every recorded measurement comes from, so its provenance is
# not a packaging detail" -- but THIS is the image that actually runs solvers under the
# Kubernetes backend (charts/starexec/values.yaml jobImage -> STAREXEC_K8S_JOB_IMAGE ->
# KubernetesNativeBackend's Job container), so the fix had to reach here or it protected
# nothing in K8s mode. The chart pins tag: latest with pullPolicy: Always, which means a
# rebuild propagates to running clusters with no Helm action -- a silently changed
# measurement instrument.
#
# Kept byte-identical in patches and assertions to the root Dockerfile: two images
# producing the same measurements must build the same binary from the same source.
COPY starexec-app/src/main/java/org/starexec/config/sge/RunSolverSource/ ./src/

RUN set -eux; \
    cd src; \
    # The repo carries committed build artifacts (runsolver, runsolver.o,
    # SignalNames.o). Never link against those -- build from source every time.
    rm -f ./*.o runsolver; \
    # Patches, applied fail-closed. A sed whose pattern stops matching is a silent
    # no-op, so each anchor is asserted first: under set -e a failed grep aborts the
    # build rather than shipping a binary that quietly missed a patch.
    #
    # NUMA support is removed because the headers are absent in this image, and
    # 'long long' becomes 'long' to compile under this toolchain.
    grep -q 'long long mem,memFree;' runsolver.cc; \
    sed -i 's/long long mem,memFree;/long mem,memFree;/g' runsolver.cc; \
    grep -q -- '-DWITH_NUMA' Makefile; \
    sed -i 's/-DWITH_NUMA//g' Makefile; \
    grep -q -- '-lnuma' Makefile; \
    sed -i 's/-lnuma//g' Makefile; \
    make; \
    # Smoke test: a binary that cannot report its own version is not one to ship.
    ./runsolver --version; \
    mkdir -p /tmp/runsolver-output; \
    cp runsolver /tmp/runsolver-output/runsolver; \
    chmod +x /tmp/runsolver-output/runsolver; \
    # Provenance, so a recorded result can name the instrument that produced it.
    ./runsolver --version | head -1 > /tmp/runsolver-output/runsolver.version; \
    sha256sum runsolver | cut -d' ' -f1 > /tmp/runsolver-output/runsolver.sha256

# ==============================================================================
# Stage 2: Minimal runtime image
# ==============================================================================
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

# Copy runsolver binary compiled natively on Alpine (musl libc) from the vendored source.
COPY --from=runsolver-builder --chmod=755 /tmp/runsolver-output/runsolver /usr/local/bin/runsolver

# Instrument provenance, readable from inside a running job pod. A result recorded by
# this image can therefore name the binary that produced it, rather than resting on the
# mutable :latest tag being whatever it was when the measurement was taken.
COPY --from=runsolver-builder --chmod=444 /tmp/runsolver-output/runsolver.version /usr/local/share/runsolver.version
COPY --from=runsolver-builder --chmod=444 /tmp/runsolver-output/runsolver.sha256 /usr/local/share/runsolver.sha256

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
