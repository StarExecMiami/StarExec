# StarExec DevOps Build System
#
# ============================================================================
# SHELL CONFIGURATION
# ============================================================================
# We explicitly use /bin/sh for POSIX compliance across Linux distributions.
# This ensures compatibility with dash (Debian/Ubuntu), ash (Alpine), and bash.
# All shell code in this Makefile MUST be POSIX-compliant:
#   - Use 'printf' instead of 'echo -n' or 'read -p'
#   - Use $(cmd) instead of `cmd` for command substitution
#   - Avoid bash arrays, [[ ]], and other bash-specific features
# ============================================================================
SHELL := /bin/sh
.SHELLFLAGS := -ec
MAKEFLAGS += --no-print-directory

# ============================================================================
# CONFIGURATION VARIABLES
# ============================================================================
# These can be overridden via environment variables or command-line:
#   make deploy-podman ENV=prod IMAGE_TAG=v1.2.3
#
# IMAGE_REGISTRY  - Container registry URL (default: ghcr.io/starexecmiami)
# IMAGE_TAG       - Image version tag (default: latest)
# ENV             - Deployment environment: dev, ci, prod (default: dev)
# FORCE           - Skip confirmation prompts: 0 or 1 (default: 0)
# DRY_RUN         - Preview volumes-delete, clean-hard, reset and nuke without executing: 0 or 1 (default: 0)
# VERBOSE         - Show technical mutation output and details: 0 or 1 (default: 0)
#
# Database Configuration:
# STAREXEC_DB_PASSWORD      - Database password (⚠️ set for non-dev environments)
# STAREXEC_DB_PASSWORD_FILE - Path to file containing DB password (preferred)
# STAREXEC_DB_USER          - Database user (default: starexec)
# STAREXEC_DB_DATABASE      - Database name (default: starexec)
# DB_HOST                   - Database host (default: localhost)
#
# SECURITY WARNING: Defaults are for DEVELOPMENT ONLY.
# Override STAREXEC_DB_PASSWORD (or provide STAREXEC_DB_PASSWORD_FILE)
# before deploying to shared environments.
# ============================================================================

# IMAGE_REGISTRY?=ghcr.io/andrescdo
# IMAGE_NAME?=starexec
IMAGE_REGISTRY?=ghcr.io/starexecmiami
IMAGE_NAME?=$(IMAGE_REGISTRY)/starexec
IMAGE_TAG?=latest
CHART_DIR=./charts/starexec
RELEASE_NAME?=starexec
HELM_VALUES?=charts/starexec/values-auto-detected.yaml
SECRET_NAME=secret-postgres
STAREXEC_DB_PASSWORD?=starexec_dev_password
STAREXEC_DB_PASSWORD_FILE?=/run/secrets/starexec-db-password
DB_PASSWORD_DEFAULT?=starexec_dev_password
DB_USER_DEFAULT?=starexec
DB_NAME_DEFAULT?=starexec
DB_HOST_DEFAULT?=localhost
ENV?=dev

# Match the Helm release namespace to each Kubernetes values profile unless
# callers explicitly provide K8S_NAMESPACE.
ifeq ($(origin K8S_NAMESPACE), undefined)
  ifeq ($(ENV),dev)
    K8S_NAMESPACE := starexec-dev
  else ifeq ($(ENV),ci)
    K8S_NAMESPACE := starexec-ci
  else ifeq ($(ENV),local-dev)
    K8S_NAMESPACE := starexec-local
  else
    K8S_NAMESPACE := starexec
  endif
endif

ENV_VALUES=$(CHART_DIR)/values-$(ENV).yaml
VOLUME_SCRIPT=./scripts/podman-volumes.sh

PODMAN_CGROUP_MANAGER ?= cgroupfs
PODMAN_CGROUP_FLAG := $(if $(PODMAN_CGROUP_MANAGER),--cgroup-manager=$(PODMAN_CGROUP_MANAGER),)

VOLUME_PREFIX=starexec
VALS := $(if $(wildcard $(ENV_VALUES)),$(ENV_VALUES),$(CHART_DIR)/values.yaml)

# ============================================================================
# Podman deployments require values files that do not rely on pre-existing
# Kubernetes secrets.  The K8s profiles (values-dev.yaml, values-prod.yaml)
# use `existingSecret` which must be created before deployment.  The Podman
# profiles (values-local-dev.yaml and copies of values-podman.yaml.example) use
# `allowInsecureDevCredentials: true` so the Helm chart creates the secret.
#
#   ENV=dev  → values-local-dev.yaml (Podman local dev)
#   ENV=ci   → values-ci.yaml          (CI — already Podman-compatible)
#   ENV=prod → values-prod.yaml        (Kubernetes only; see below)
#
# Explicit command-line overrides (VALS=...) are always respected. A Podman
# deploy with Helm refuses values-prod.yaml however it was selected
# (require_podman_values): on Podman, production passes
# VALS=charts/starexec/values-podman-prod.yaml, a copy of the example.
# ============================================================================
PODMAN_DEV_VALUES_FILE := $(CHART_DIR)/values-local-dev.yaml
ifneq ($(origin VALS),command line)
  ifeq ($(ENV),dev)
    ifneq ($(wildcard $(PODMAN_DEV_VALUES_FILE)),)
      PODMAN_VALS := $(PODMAN_DEV_VALUES_FILE)
    else
      PODMAN_VALS := $(VALS)
    endif
  else
    PODMAN_VALS := $(VALS)
  endif
else
  PODMAN_VALS := $(VALS)
endif

FORCE?=0
DRY_RUN?=0
LOCAL?=0
VERBOSE?=0
export VERBOSE
CLI_OUTPUT = . "$(CURDIR)/scripts/lib/cli-output.sh";

# Container naming (derived from RELEASE_NAME for consistency)
APP_CONTAINER=$(RELEASE_NAME)-app
DB_CONTAINER=$(RELEASE_NAME)-postgres
POD_NAME=$(RELEASE_NAME)-pod

define resolve_pod_state
POD_STATE=""; \
PODS="$(1)"; \
if [ "$(RELEASE_NAME)" = "starexec" ]; then PODS="$$PODS starexec"; fi; \
for pod in $$PODS; do \
	POD_STATE="$$( $(strip $(PODMAN_CMD)) pod inspect "$$pod" --format '{{.State}}' 2>/dev/null || true )"; \
	if [ -n "$$POD_STATE" ]; then \
		break; \
	fi; \
done
endef

# Port configuration
APP_PORT?=7827

# Network configuration
# ============================================================================
# PODMAN DETECTION & CONFIGURATION
# ============================================================================
# We detect if podman requires sudo and which OCI runtime is available.

# 1. Detect if podman requires sudo (rootful vs rootless)
# We handle the case where podman might be broken due to a missing default runtime.
PODMAN_REQUIRES_SUDO := $(shell \
	if podman system info >/dev/null 2>&1; then \
		if podman system info 2>/dev/null | grep -q -E 'rootless[[:space:]]*[:=][[:space:]]*true'; then \
			echo no; \
		else \
			echo yes; \
		fi; \
	else \
		if sudo podman system info >/dev/null 2>&1; then \
			echo yes; \
		else \
			echo yes; \
		fi; \
	fi)

# 2. OCI Runtime Detection (crun vs runc)
OCI_RUNTIME_AVAILABLE := $(shell \
	if which crun >/dev/null 2>&1; then echo crun; \
	elif which runc >/dev/null 2>&1; then echo runc; \
	else echo none; fi)

ifeq ($(OCI_RUNTIME_AVAILABLE),none)
    $(warning Neither 'crun' nor 'runc' OCI runtime found. Podman commands will fail.)
    $(warning Install one: 'sudo apt-get install crun' or 'sudo dnf install crun')
endif

# 3. Detect what podman *thinks* it should use
PODMAN_BASE_CMD := $(if $(filter yes,$(PODMAN_REQUIRES_SUDO)),sudo podman,podman)
PODMAN_CONFIG_RUNTIME := $(shell $(PODMAN_BASE_CMD) info --format '{{.Host.OCIRuntime.Name}}' 2>/dev/null || \
                          $(PODMAN_BASE_CMD) info --format '{{.Host.OCIRuntime}}' 2>/dev/null || \
                          echo unknown)

# 4. Determine if we need to force a runtime
# If podman is broken (unknown runtime) or there's a mismatch, we force the available one.
FORCE_RUNTIME := $(if $(filter unknown,$(PODMAN_CONFIG_RUNTIME)),yes,$(if $(filter-out $(PODMAN_CONFIG_RUNTIME),$(OCI_RUNTIME_AVAILABLE)),yes,no))

# 5. Final PODMAN_CMD construction
PODMAN_RUNTIME_FLAG := $(if $(filter yes,$(FORCE_RUNTIME)),$(if $(filter-out none,$(OCI_RUNTIME_AVAILABLE)),--runtime=$(OCI_RUNTIME_AVAILABLE),),)
PODMAN_CMD := $(PODMAN_BASE_CMD) $(PODMAN_RUNTIME_FLAG)

# Export for sub-processes/scripts
export PODMAN_CMD
export PODMAN_REQUIRES_SUDO

# Current user UID, used when deriving the rootless Podman socket path.
CURRENT_UID := $(shell id -u)

# Active Podman socket path for the current environment.
# Rootful uses the system socket; rootless prefers XDG_RUNTIME_DIR and falls
# back to the standard /run/user/UID path. Override with PODMAN_SOCKET_PATH if
# your host uses a nonstandard socket location.
PODMAN_SOCKET_PATH ?= $(if $(filter yes,$(PODMAN_REQUIRES_SUDO)),/run/podman/podman.sock,$(if $(XDG_RUNTIME_DIR),$(XDG_RUNTIME_DIR)/podman/podman.sock,/run/user/$(CURRENT_UID)/podman/podman.sock))
export PODMAN_SOCKET_PATH

# Legacy targets remain uncolored; migrated emitters use CLI_OUTPUT at runtime.
GREEN  :=
YELLOW :=
RED    :=
BLUE   :=
RESET  :=
BOLD   :=


.PHONY: help build build-fresh build-prod build-offline cache-images image \
	preflight-podman test-podman-socket \
	deploy-podman deploy-podman-helm deploy-podman-direct network-setup network-reset deploy-podman-cached undeploy-podman \
	deploy-k8s undeploy-k8s k8s-detect k8s-setup k8s-setup-dry-run k8s-label-workers k8s-generate-values k8s-deploy-auto k8s-status \
	volumes-create volumes-fix-permissions volumes-list volumes-backup volumes-restore volumes-export volumes-delete volumes-help \
	db-shell db-dump db-migrate db-status migrate-repair migrate-podman \
	clean-podman clean-cache clean-all clean-hard reset nuke status lint template config-show runtime-check \
	logs logs-app logs-postgres test-deps verify-deps wait-postgres test docs pull pull-job-runner \
	start stop preflight-cgroup fix-cgroup-delegation

start: deploy-podman

stop: undeploy-podman


help:
	@echo "StarExec DevOps Build System"
	@echo "============================="
	@echo ""
	@echo "Usage: make <target> [ENV=<env>]"
	@echo ""
	@echo "Build Targets:"
	@echo "  build                  Build container image (--pull=missing: uses local cache if available)"
	@echo "  build-fresh            Force pull all base images and rebuild (--pull=always --no-cache)"
	@echo "  build-prod             Build production image with registry tag"
	@echo "  build-offline          Build strictly from local cache (--pull=never); fails fast if image absent"
	@echo "  cache-images           Pre-fetch all base images for offline builds (run once while online)"
	@echo "  image                  Ensure image exists (pulls rolling tags automatically)"
	@echo "  pull                   Force update all images from registry"
	@echo ""
	@echo "Deployment Targets:"
	@echo "  deploy-podman          Deploy to Podman (ensures image + render + apply)"
	@echo "  deploy-podman-cached   Fast deploy using existing render.yaml (ensures image)"
	@echo "  test-podman-socket     Verify app container can reach the Podman socket"
	@echo "  deploy-k8s             Deploy to Kubernetes (requires Helm)"
	@echo "  k8s-deploy-auto        ⭐ Complete automated K8s setup and deployment"
	@echo "  undeploy-podman        Remove Podman deployment"
	@echo "  undeploy-k8s           Remove Kubernetes deployment"
	@echo ""
	@echo "Kubernetes Auto-Detection & Setup:"
	@echo "  k8s-detect             Detect cluster configuration (nodes, storage, etc.)"
	@echo "  k8s-setup              Configure K8s cluster (label nodes, create PVC)"
	@echo "  k8s-setup-dry-run      Preview K8s setup without applying changes"
	@echo "  k8s-label-workers      Label worker nodes for StarExec"
	@echo "  k8s-generate-values    Generate optimized Helm values file"
	@echo "  k8s-status             Show cluster and deployment status"
	@echo ""
	@echo "Volume Management (Podman):"
	@echo "  volumes-create         Create named volumes for ENV"
	@echo "  volumes-list           List all volumes"
	@echo "  volumes-backup         Backup all volumes for ENV"
	@echo "  volumes-restore        Restore volumes from backup"
	@echo "  volumes-cleanup        Remove old backups (keep last 10)"
	@echo "  volumes-health         Check volume health and size"
	@echo "  volumes-export         Export volume for sharing"
	@echo "  volumes-delete         Delete volumes for ENV"
	@echo "  volumes-help           Show detailed volume management help"
	@echo ""
	@echo "Database Management:"
	@echo "  migrate-podman         ⚠️  MANUAL migration (debugging only - automatic on startup)"
	@echo "  migrate-repair         Repair Flyway schema history"
	@echo "  db-shell               Open PostgreSQL shell (Podman)"
	@echo "  db-dump                Create PostgreSQL logical dump"
	@echo "  db-migrate             Run Flyway migrations (direct)"
	@echo "  db-status              Check Flyway migration status"
	@echo ""
	@echo "Maintenance Targets:"
	@echo "  clean-podman           Clean Podman artifacts (keeps volumes)"
	@echo "  clean-cache            Clear Podman build cache (prune + builder prune)"
	@echo "  clean-all              Clean everything including volumes AND cache"
	@echo "  clean-hard             ⚠️  HARD RESET: Remove ALL StarExec resources for ENV"
	@echo "  reset                  ⚠️  Alias for stop + clean-hard (recommended)"
	@echo "  nuke                   ⚠️  Alias for reset (complete environment wipe)"
	@echo "  preflight-cgroup       Check and fix cgroup controller delegation for Podman rootless"
	@echo "  fix-cgroup-delegation  Fix cgroup controller delegation for Podman rootless"
	@echo "  status                 Show current deployment status"
	@echo "  lint                   Lint Helm charts (if Helm available)"
	@echo "  template               Render Helm templates (if Helm available)"
	@echo "  docs                   Generate auto-updated documentation reference"
	@echo ""
	@echo "Debugging Targets:"
	@echo "  logs                   Show all container logs"
	@echo "  logs-app               Show application logs (follow mode)"
	@echo "  logs-postgres          Show PostgreSQL logs (follow mode)"
	@echo "  runtime-check          Check OCI runtime configuration (crun/runc)"
	@echo "  test-deps              Test job execution dependencies in container"
	@echo "  test                   Run all unit and integration tests"
	@echo "  test-podman-socket     Verify app container can reach the Podman socket"
	@echo ""
	@echo "Quick Start Aliases:"
	@echo "  start                  Alias for deploy-podman"
	@echo "  stop                   Alias for undeploy-podman"
	@echo "  reset                  ⚠️  Complete cleanup (stop + clean-hard)"
	@echo ""
	@echo "Environments:"
	@echo "  ENV=dev               Development (default, named volumes)"
	@echo "  ENV=ci                CI/testing (ephemeral)"
	@echo "  ENV=prod              Production (Kubernetes PVCs)"
	@echo ""
	@echo "Flags:"
	@echo "  DRY_RUN=1             Preview volumes-delete, clean-hard, reset and nuke without executing"
	@echo "  FORCE=1               Skip confirmation prompts (use with caution)"
	@echo "  VERBOSE=1             Show technical output and details (default: 0)"
	@echo ""
	@echo "Examples:"
	@echo "  make deploy-podman ENV=dev"
	@echo "  make deploy-podman-cached"
	@echo "  make volumes-backup ENV=dev"
	@echo "  make reset ENV=dev                 # Complete cleanup"
	@echo "  make clean-hard ENV=dev DRY_RUN=1  # Preview cleanup"
	@echo "  make deploy-k8s ENV=prod"

build:
	@echo "Building image: $(RELEASE_NAME):$(IMAGE_TAG)"
	$(PODMAN_CMD) build $(PODMAN_CGROUP_FLAG) --pull=missing -t $(RELEASE_NAME):$(IMAGE_TAG) .
	@echo "${GREEN}✓ Image built successfully: $(RELEASE_NAME):$(IMAGE_TAG)${RESET}"
	@$(PODMAN_CMD) images --format "  Size: {{.Size}}" $(RELEASE_NAME):$(IMAGE_TAG)

build-fresh:
	@echo "Building fresh image (no cache): $(RELEASE_NAME):$(IMAGE_TAG)"
	$(PODMAN_CMD) build --no-cache $(PODMAN_CGROUP_FLAG) --pull=always -t $(RELEASE_NAME):$(IMAGE_TAG) .
	@echo "${GREEN}✓ Fresh image built successfully: $(RELEASE_NAME):$(IMAGE_TAG)${RESET}"
	@$(PODMAN_CMD) images --format "  Size: {{.Size}}" $(RELEASE_NAME):$(IMAGE_TAG)

build-prod:
	@echo "Building production image"
	@IMAGE_REGISTRY=$${IMAGE_REGISTRY:-ghcr.io/starExecmiami}; \
	IMAGE_VERSION=$${IMAGE_VERSION:-2.5.1}; \
	$(PODMAN_CMD) build $(PODMAN_CGROUP_FLAG) --pull=missing -t $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION -t $$IMAGE_REGISTRY/starexec:latest .
	@echo "${GREEN}✓ Production image built successfully ${RESET}"
	@echo "  Image: $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION"
	@$(PODMAN_CMD) images --format "  Size: {{.Size}}" $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION
	@echo "Push with: podman push $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION"

# Pre-fetch all base images required by the Dockerfile.
# Run once while online; subsequent offline builds will use the local cache.
# Base images:
#   node:24-alpine             Stage 1 - SCSS/CSS asset compilation
#   alpine:3.19                Stage 2 - runsolver build
#   maven:3.9-eclipse-temurin-17-alpine  Stage 3 & 4 - credential handler + app build
#   eclipse-temurin:17-jre-alpine        Stage 5 - runtime
cache-images:
	@echo "Pre-fetching base images for offline builds..."
	$(PODMAN_CMD) pull docker.io/library/node:24-alpine
	$(PODMAN_CMD) pull docker.io/library/alpine:3.19
	$(PODMAN_CMD) pull docker.io/library/maven:3.9-eclipse-temurin-17-alpine
	$(PODMAN_CMD) pull docker.io/library/eclipse-temurin:17-jre-alpine
	@echo "${GREEN}✓ All base images cached. Offline builds now available via 'make build' or 'make build-offline'.${RESET}"

# Build strictly from local cache. Fails immediately if any base image is absent
# rather than hanging on a registry timeout. Run 'make cache-images' first if needed.
build-offline:
	@echo "Building from local cache only (--pull=never)..."
	$(PODMAN_CMD) build --pull=never -t $(RELEASE_NAME):$(IMAGE_TAG) .
	@echo "${GREEN}✓ Offline build complete: $(RELEASE_NAME):$(IMAGE_TAG)${RESET}"
	@$(PODMAN_CMD) images --format "  Size: {{.Size}}" $(RELEASE_NAME):$(IMAGE_TAG)

image:
	@$(CLI_OUTPUT) ui_info "Checking for image: $(RELEASE_NAME):$(IMAGE_TAG)"
	@# For 'latest' or 'dev' tags, we should at least attempt to check for updates from the registry
	@# Set LOCAL=1 to skip the registry pull and always use the locally-built image.
	@$(CLI_OUTPUT) if [ "$(LOCAL)" = "1" ]; then \
		ui_info "LOCAL=1: Skipping registry pull, using local image only"; \
	elif [ "$(IMAGE_TAG)" = "latest" ] || [ "$(IMAGE_TAG)" = "dev" ]; then \
		ui_info "Checking registry for $(IMAGE_NAME):$(IMAGE_TAG)..."; \
		if ui_run $(PODMAN_CMD) pull $(IMAGE_NAME):$(IMAGE_TAG); then \
			ui_success "Pulled $(IMAGE_NAME):$(IMAGE_TAG)"; \
			$(PODMAN_CMD) tag $(IMAGE_NAME):$(IMAGE_TAG) $(RELEASE_NAME):$(IMAGE_TAG) 2>/dev/null || true; \
		else \
			ui_warn "Image pull failed. Proceeding with local image if available..."; \
		fi; \
	fi
	@$(CLI_OUTPUT) if $(PODMAN_CMD) image inspect $(RELEASE_NAME):$(IMAGE_TAG) >/dev/null 2>&1; then \
		ui_info "Image $(RELEASE_NAME):$(IMAGE_TAG) found locally"; \
	else \
		ui_info "Image not found locally at $(RELEASE_NAME):$(IMAGE_TAG). Pulling from registry..."; \
		if ui_run $(PODMAN_CMD) pull $(IMAGE_NAME):$(IMAGE_TAG); then \
			$(PODMAN_CMD) tag $(IMAGE_NAME):$(IMAGE_TAG) $(RELEASE_NAME):$(IMAGE_TAG); \
			ui_success "Pulled and tagged $(RELEASE_NAME):$(IMAGE_TAG)"; \
		else \
			ui_warn "Image pull failed. Building locally..."; \
			$(MAKE) build; \
		fi; \
	fi

# Force an update of the local images from the registry
pull:
	@echo "Updating images from registry..."
	$(PODMAN_CMD) pull $(IMAGE_NAME):$(IMAGE_TAG)
	$(PODMAN_CMD) tag $(IMAGE_NAME):$(IMAGE_TAG) $(RELEASE_NAME):$(IMAGE_TAG)
	$(MAKE) pull-job-runner
	@echo "${GREEN}✓ All images updated and tagged${RESET}"

# Build the job-runner image used by PodmanBackend for solver execution
# Production images are pulled from GHCR: ghcr.io/starexecmiami/starexec-job-runner
# Local builds are for development/testing only
JOB_RUNNER_IMAGE?=ghcr.io/starexecmiami/starexec-job-runner
JOB_RUNNER_TAG?=latest
JOB_RUNNER_LOCAL_IMAGE?=starexec/job-runner

# Pull the production job-runner image from GHCR (recommended)
pull-job-runner:
	@echo "Pulling job-runner image from GHCR: $(JOB_RUNNER_IMAGE):$(JOB_RUNNER_TAG)"
	$(PODMAN_CMD) pull $(JOB_RUNNER_IMAGE):$(JOB_RUNNER_TAG)
	@echo "${GREEN}✓ Job runner image pulled successfully: ${RESET}"
	@$(PODMAN_CMD) images --format "  Size: {{.Size}}" $(JOB_RUNNER_IMAGE):$(JOB_RUNNER_TAG)

# Build job-runner locally (for development only)
build-job-runner:
	@echo "Building job-runner image locally (Alpine): $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG)"
	@echo "Note: Production deployments should use 'make pull-job-runner' instead"
	$(PODMAN_CMD) build -t $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG) -f docker/job-runner.Dockerfile .
	@echo "${GREEN}✓ Job runner image built successfully: ${RESET}"
	@echo "  Image: $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG)"
	@$(PODMAN_CMD) images --format "  Size: {{.Size}}" $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG)

# ============================================================================
# VOLUME MANAGEMENT (Podman Named Volumes - RECOMMENDED APPROACH)
# ============================================================================

volumes-create:
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) create $(ENV)

volumes-fix-permissions:
	@echo "Fixing PostgreSQL volume permissions for environment: $(ENV)"
	@PG_VOL="$(VOLUME_PREFIX)-$(ENV)-postgres"; \
	if ! $(PODMAN_CMD) volume exists "$$PG_VOL" 2>/dev/null; then \
		echo "${YELLOW}Volume $$PG_VOL does not exist yet (run make volumes-create ENV=$(ENV))${RESET}"; \
		exit 1; \
	fi; \
	PG_MOUNT=$$($(PODMAN_CMD) volume inspect "$$PG_VOL" --format '{{.Mountpoint}}'); \
	echo "  Mountpoint: $$PG_MOUNT"; \
	if $(PODMAN_CMD) unshare chown -R 999:999 "$$PG_MOUNT" 2>/dev/null; then \
		echo "${GREEN}✓ PostgreSQL volume ownership set to 999:999${RESET}"; \
	else \
		echo "${RED}✗ Failed to set ownership with podman unshare.${RESET}"; \
		echo "Try manually: $(PODMAN_CMD) unshare chown -R 999:999 $$PG_MOUNT"; \
		exit 1; \
	fi

volumes-list:
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) list

volumes-backup: verify-deps
	@echo "Backing up volumes for environment: $(ENV)"
	@# Safety check: warn if containers are running during backup
	@$(call resolve_pod_state,$(POD_NAME)); \
	if [ "$$POD_STATE" = "Running" ] || [ "$$POD_STATE" = "running" ] || [ "$$POD_STATE" = "Degraded" ] || [ "$$POD_STATE" = "degraded" ]; then \
		echo ""; \
		echo "${YELLOW}⚠️  WARNING: StarExec containers are currently RUNNING${RESET}"; \
		echo "${YELLOW}   For a consistent backup, consider stopping first:${RESET}"; \
		echo "   ${BLUE}make stop${RESET}"; \
		echo ""; \
		if [ "$(FORCE)" != "1" ] && [ -t 0 ]; then \
			printf "Continue with backup anyway? (y/N): "; \
			read ans; \
			if [ "$$ans" != "y" ] && [ "$$ans" != "Y" ]; then \
				echo "Backup cancelled; nothing was changed."; \
				exit 1; \
			fi; \
		fi; \
	fi
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) backup-all $(ENV)

volumes-restore: verify-deps
	@# =========================================================================
	@# Backup Restore Wizard
	@# =========================================================================
	@# This target implements an interactive backup selection menu.
	@# For complex restore scenarios, consider using the volume script directly:
	@#   ./scripts/podman-volumes.sh restore-all ENV TIMESTAMP
	@# =========================================================================
	@echo "Restore requires timestamp. Available backups:"
	@# Safety check: ensure containers are stopped before restore
	@$(call resolve_pod_state,$(POD_NAME)); \
	if [ "$$POD_STATE" = "Running" ] || [ "$$POD_STATE" = "running" ] || [ "$$POD_STATE" = "Degraded" ] || [ "$$POD_STATE" = "degraded" ]; then \
		echo ""; \
		echo "${RED}╔══════════════════════════════════════════════════════════════╗${RESET}"; \
		echo "${RED}║  ⚠️  DANGER: StarExec is currently RUNNING!                   ║${RESET}"; \
		echo "${RED}║                                                              ║${RESET}"; \
		echo "${RED}║  Restoring volumes while the database is running will        ║${RESET}"; \
		echo "${RED}║  CORRUPT your PostgreSQL data (WAL mismatch).                ║${RESET}"; \
		echo "${RED}╚══════════════════════════════════════════════════════════════╝${RESET}"; \
		echo ""; \
		echo "Please stop the deployment first:"; \
		echo "  ${BLUE}make stop${RESET}"; \
		echo ""; \
		echo "Then retry:"; \
		echo "  ${BLUE}make volumes-restore${RESET}"; \
		echo ""; \
		exit 1; \
	fi
	@# Parse backup timestamps from filenames using a robust pattern
	@# Expected format: backups/starexec-ENV-full-YYYYMMDD-HHMMSS-{data,postgres}.tar.gz
	@BACKUP_PATTERN="backups/$(VOLUME_PREFIX)-$(ENV)-full-*-*.tar.gz"; \
	BACKUPS=$$(ls -1 $$BACKUP_PATTERN 2>/dev/null | \
		sed -n 's|.*/$(VOLUME_PREFIX)-$(ENV)-full-\([0-9]\{8\}-[0-9]\{6\}\)-.*\.tar\.gz|\1|p' | \
		sort -u); \
	if [ -z "$$BACKUPS" ]; then \
		echo "${RED}✗ No backups found for environment: $(ENV)${RESET}"; \
		echo "  Expected pattern: $$BACKUP_PATTERN"; \
		exit 1; \
	fi; \
	BACKUP_COUNT=$$(echo "$$BACKUPS" | wc -l | tr -d ' '); \
	LATEST=$$(echo "$$BACKUPS" | tail -1); \
	ts=$${RESTORE_TIMESTAMP:-}; \
	if [ -z "$$ts" ]; then \
		echo ""; \
		echo "Available backups (newest last):"; \
		echo "$$BACKUPS" | awk -v latest="$$LATEST" -v green="${GREEN}" -v reset="${RESET}" '{ \
			if ($$0 == latest) printf "  %s%d) %s (latest)%s\n", green, NR, $$0, reset; \
			else printf "  %d) %s\n", NR, $$0; \
		}'; \
		echo ""; \
		if [ -t 0 ]; then \
			printf "Select backup [1-$$BACKUP_COUNT] or timestamp (default: $$BACKUP_COUNT = latest): "; \
			read selection; \
		else \
			echo "Non-interactive mode: using latest backup"; \
			selection=""; \
		fi; \
		if [ -z "$$selection" ]; then \
			ts=$$LATEST; \
			echo "Using latest backup: $$ts"; \
		elif echo "$$selection" | grep -qE '^[0-9]+$$' && [ "$$selection" -ge 1 ] && [ "$$selection" -le "$$BACKUP_COUNT" ]; then \
			ts=$$(echo "$$BACKUPS" | sed -n "$${selection}p"); \
			echo "Selected backup: $$ts"; \
		else \
			ts=$$selection; \
			if ! echo "$$BACKUPS" | grep -qx "$$ts"; then \
				echo "${YELLOW}⚠ Warning: '$$ts' not in backup list, attempting anyway...${RESET}"; \
			fi; \
			echo "Using timestamp: $$ts"; \
		fi; \
	fi; \
	if [ -z "$$ts" ]; then \
		echo "${RED}✗ No timestamp provided, aborting${RESET}"; \
		exit 1; \
	fi; \
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) restore-all "$(ENV)" "$$ts"

volumes-export:
	@printf "Volume name: "; \
	read vol; \
	if [ -z "$$vol" ]; then \
		echo "${RED}✗ No volume name provided, aborting${RESET}"; \
		exit 1; \
	fi; \
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) export $$vol

volumes-cleanup:
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) cleanup-backups $(ENV) 10

volumes-health:
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) health-check $(ENV)

# Each recipe line runs in its own shell, so `exit` in one line cannot stop the next. A guard therefore either
# fails (`exit 1` stops make) or, for DRY_RUN, leaves the destructive lines out of the recipe altogether.
volumes-delete:
ifeq ($(DRY_RUN),1)
	@$(CLI_OUTPUT) ui_info "[DRY RUN] Would delete volumes for environment: $(ENV)"
	@$(CLI_OUTPUT) ui_info "  - $(VOLUME_PREFIX)-$(ENV)-data"
	@$(CLI_OUTPUT) ui_info "  - $(VOLUME_PREFIX)-$(ENV)-sandbox"
	@$(CLI_OUTPUT) ui_info "  - $(VOLUME_PREFIX)-$(ENV)-backend"
	@$(CLI_OUTPUT) ui_info "  - $(VOLUME_PREFIX)-$(ENV)-work"
	@$(CLI_OUTPUT) ui_info "  - $(VOLUME_PREFIX)-$(ENV)-postgres"
	@$(CLI_OUTPUT) ui_info "  - $(VOLUME_PREFIX)-data and $(VOLUME_PREFIX)-postgres, if they exist (older layouts)"
	@$(CLI_OUTPUT) if [ "$${CLEAN_HOSTPATH:-0}" = "1" ]; then \
		ui_info "  - host directories $${HOSTPATH_DATA:-/tmp/starexec-data} and $${HOSTPATH_POSTGRES:-/tmp/postgres-data} (CLEAN_HOSTPATH=1)"; \
	fi
else
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) delete $(ENV)
endif

volumes-help:
	@PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) help

# ============================================================================
# REUSABLE GUARDS
# ============================================================================

# require_values_file — abort if the expected values-$(ENV).yaml is missing.
# Usage: @$(call require_values_file)
define require_values_file
	$(CLI_OUTPUT) if [ ! -f "$(ENV_VALUES)" ]; then \
		ui_error "Missing required values file: $(ENV_VALUES)"; \
		ui_info "  ENV=$(ENV) requires an environment-specific values file."; \
		if [ -f "$(ENV_VALUES).example" ]; then \
			ui_info "  An example file exists. Create your values file with:"; \
			ui_info "  cp $(ENV_VALUES).example $(ENV_VALUES)"; \
			ui_info "  Then edit it to set your credentials and socket path."; \
		else \
			ui_info "  Create $(ENV_VALUES) with the required configuration."; \
			ui_info "  Available values files:"; \
			ls -1 $(CHART_DIR)/values*.yaml $(CHART_DIR)/values*.yaml.example 2>/dev/null | sed 's/^/  /' >&2; \
		fi; \
		exit 1; \
	fi
endef

# require_podman_values — refuse to deploy values-prod.yaml, the Kubernetes
# profile, to Podman with Helm, however it was selected (ENV=prod selects it
# unless VALS= names another file). Its pod reads a database Secret that the
# render does not create, and cleanup_deployment removes the release's Podman
# secrets before every deploy; it also mounts other volumes than the
# starexec-$(ENV)-* volumes that job containers and volumes-backup use.
# Runs before anything is pulled, created or stopped. Without Helm,
# deploy-podman-direct renders no values file, so the check does not apply.
# Usage: @$(call require_podman_values)
define require_podman_values
	$(CLI_OUTPUT) if [ "$(abspath $(PODMAN_VALS))" = "$(abspath $(CHART_DIR)/values-prod.yaml)" ] && command -v helm >/dev/null 2>&1; then \
		ui_error "values-prod.yaml is the Kubernetes profile; Podman cannot deploy it"; \
		ui_info "  Its pod needs a database Secret this deployment does not create, and"; \
		ui_info "  it mounts other volumes than the starexec-$(ENV)-* volumes."; \
		ui_info "  ENV=prod selects it unless VALS= names the Podman values file:"; \
		ui_info "  cp $(CHART_DIR)/values-podman.yaml.example $(CHART_DIR)/values-podman-prod.yaml"; \
		ui_info "  # set postgres.password and postgres.rootPassword in it, then:"; \
		ui_info "  make deploy-podman ENV=prod VALS=$(CHART_DIR)/values-podman-prod.yaml"; \
		exit 1; \
	fi
endef

# require_podman_engine_ready — fail fast if Podman engine is unavailable.
# In prod, block rootful mode unless FORCE=1 is explicitly set.
# Usage: @$(call require_podman_engine_ready)
define require_podman_engine_ready
	$(CLI_OUTPUT) if ! $(PODMAN_CMD) info >/dev/null 2>&1; then \
		ui_error "Podman engine is not reachable."; \
		ui_info "  Command: $(PODMAN_CMD) info"; \
		ui_info "  Try: podman system service --time=0 (or ensure rootless session is active)"; \
		exit 1; \
	fi; \
	if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
		ui_warn "Podman appears to require sudo (rootful mode)."; \
		ui_info "  Rootless mode is recommended for safer default operation."; \
		if [ "$(ENV)" = "prod" ] && [ "$(FORCE)" != "1" ]; then \
			ui_error "Refusing ENV=prod deployment in rootful mode without FORCE=1."; \
			ui_info "  Use rootless Podman, or override explicitly with FORCE=1."; \
			exit 1; \
		fi; \
	fi
endef

# require_rootless_network_healthy — on rootless setups where pasta is the default
# networking helper, verify it actually works. A segfaulting pasta binary silently
# breaks all rootless networking (IPAM errors, container startup failures).
# Falls back to slirp4netns guidance if available.
# Usage: @$(call require_rootless_network_healthy)
define require_rootless_network_healthy
	$(CLI_OUTPUT) if [ "$(PODMAN_REQUIRES_SUDO)" != "yes" ]; then \
		NET_CMD=$$($(PODMAN_CMD) info --format '{{.Host.RootlessNetworkCmd}}' 2>/dev/null || echo "unknown"); \
		if [ "$$NET_CMD" = "pasta" ]; then \
			if ! timeout 3 pasta --version >/dev/null 2>&1; then \
				ui_error "Rootless networking helper 'pasta --version' failed or timed out."; \
				if command -v slirp4netns >/dev/null 2>&1; then \
					ui_info "  slirp4netns is available as an alternative."; \
					ui_info "  Configure default_rootless_network_cmd = \"slirp4netns\" in ~/.config/containers/containers.conf under [network]."; \
				else \
					ui_info "  Install slirp4netns first: sudo apt-get install -y slirp4netns"; \
					ui_info "  Then configure default_rootless_network_cmd = \"slirp4netns\" in ~/.config/containers/containers.conf under [network]."; \
				fi; \
				exit 1; \
			fi; \
		fi; \
	fi
endef

# verify_podman_socket_from_values — verify the resolved Podman socket path.
# Uses PODMAN_SOCKET_PATH (derived from the active environment) and warns when
# the values file contains a different hostPath.
# Usage: @$(call verify_podman_socket_from_values)
define verify_podman_socket_from_values
	$(CLI_OUTPUT) SOCKET_PATH="$(PODMAN_SOCKET_PATH)"; \
	CURRENT_UID=$$(id -u); \
	if [ -z "$$SOCKET_PATH" ]; then \
		ui_error "Podman socket path is empty."; \
		ui_info "  Set PODMAN_SOCKET_PATH or export XDG_RUNTIME_DIR before running make."; \
		exit 1; \
	fi; \
	if [ ! -S "$$SOCKET_PATH" ]; then \
		if command -v systemctl >/dev/null 2>&1; then \
			if [ "$(PODMAN_REQUIRES_SUDO)" != "yes" ]; then \
				SOCKET_STATUS=$$(systemctl --user is-active podman.socket 2>&1 || echo "error"); \
				if ! echo "$$SOCKET_STATUS" | grep -q masked; then \
					ui_warn "Podman socket not running. Attempting auto-start via systemctl --user..."; \
					systemctl --user start podman.socket 2>/dev/null || true; \
					_retries=0; \
					while [ $$_retries -lt 5 ] && [ ! -S "$$SOCKET_PATH" ]; do \
						sleep 1; \
						_retries=$$(( $$_retries + 1 )); \
					done; \
					if [ -S "$$SOCKET_PATH" ]; then \
						ui_success "Podman socket started automatically."; \
					fi; \
				fi; \
			fi; \
		fi; \
		if [ ! -S "$$SOCKET_PATH" ]; then \
			ui_error "Podman socket not found: $$SOCKET_PATH"; \
			if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
				ui_info "  Expected rootful default: /run/podman/podman.sock"; \
				ui_info "  Start the system service or provide PODMAN_SOCKET_PATH explicitly."; \
			else \
				ui_info "  Resolved from PODMAN_SOCKET_PATH or the active runtime directory."; \
				ui_info "  Expected rootless default: /run/user/$$CURRENT_UID/podman/podman.sock"; \
			fi; \
			if command -v systemctl >/dev/null 2>&1; then \
				if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
					SOCKET_STATUS=$$(systemctl is-active podman.socket 2>&1 || echo "error"); \
					if echo "$$SOCKET_STATUS" | grep -q masked; then \
						ui_warn "Rootful Podman socket is masked by system"; \
						ui_info "  To enable it:"; \
						ui_info "  sudo systemctl daemon-reload"; \
						ui_info "  sudo systemctl enable --now podman.socket"; \
					else \
						ui_info "  Start the rootful Podman socket service:"; \
						ui_info "  sudo systemctl start podman.socket"; \
					fi; \
				else \
					SOCKET_STATUS=$$(systemctl --user is-active podman.socket 2>&1 || echo "error"); \
					if echo "$$SOCKET_STATUS" | grep -q masked; then \
						ui_warn "Podman socket is masked by system"; \
						ui_info "  To enable it, copy systemd units to your user:"; \
						ui_info "  mkdir -p ~/.config/systemd/user/"; \
						ui_info "  cp /usr/lib/systemd/user/podman.* ~/.config/systemd/user/"; \
						ui_info "  systemctl --user daemon-reload"; \
						ui_info "  systemctl --user enable --now podman.socket"; \
					else \
						ui_info "  Start the Podman socket service:"; \
						ui_info "  systemctl --user start podman.socket"; \
					fi; \
				fi; \
			else \
				ui_warn "systemd not found on this system"; \
				ui_info "  Ensure Podman daemon is running. On macOS/Windows: podman machine start"; \
				ui_info "  On rootless Linux without systemd:"; \
				ui_info "  podman system service --time=0 unix:///run/user/$$CURRENT_UID/podman/podman.sock &"; \
			fi; \
			ui_info "  Verify the socket exists: ls -l $$SOCKET_PATH"; \
			ui_info "  See docs/TROUBLESHOOTING.md#podman-issues for detailed help."; \
			exit 1; \
		fi; \
	fi; \
	if command -v curl >/dev/null 2>&1; then \
		if ! curl -s --unix-socket "$$SOCKET_PATH" http://localhost/_ping >/dev/null 2>&1; then \
			ui_error "Podman socket exists but is not responding: $$SOCKET_PATH"; \
			ui_info "  The Podman service may not be running or socket activation is not configured."; \
			ui_info "  Check: systemctl --user is-active podman.socket"; \
			exit 1; \
		fi; \
	fi; \
	if command -v yq >/dev/null 2>&1; then \
		SOCKET_ENABLED=$$(yq '.podman.containerSocket.enabled // false' "$(PODMAN_VALS)"); \
		case "$$SOCKET_ENABLED" in \
			true|TRUE|True) \
				VALUES_SOCKET_PATH=$$(yq -r '.podman.containerSocket.hostPath // ""' "$(PODMAN_VALS)"); \
				if [ -n "$$VALUES_SOCKET_PATH" ] && [ "$$VALUES_SOCKET_PATH" != "$$SOCKET_PATH" ]; then \
					ui_warn "values file hostPath ($$VALUES_SOCKET_PATH) differs from resolved socket ($$SOCKET_PATH)."; \
					ui_info "  make start will render with $$SOCKET_PATH without rewriting the values file."; \
				fi; \
				;; \
			esac; \
	fi
endef

preflight-podman: verify-deps
	@$(call require_values_file)
	@$(call require_podman_engine_ready)
	@$(call require_rootless_network_healthy)
	@$(call verify_podman_socket_from_values)
	@$(CLI_OUTPUT) ui_detail "Using values file: $(PODMAN_VALS)"

define resolve_app_container
	APP_CTR=""; \
	for c in "$(APP_CONTAINER)" "$(RELEASE_NAME)-pod-app" "starexec-pod-app" "starexec-app"; do \
		if $(PODMAN_CMD) container exists $$c >/dev/null 2>&1; then \
			APP_CTR=$$c; \
			break; \
		fi; \
	done; \
	if [ -z "$$APP_CTR" ]; then \
		for c in $$($(PODMAN_CMD) ps -a --filter "label=app.kubernetes.io/instance=$(RELEASE_NAME)" --format "{{.Names}}"); do \
			case "$$c" in \
				*app*) APP_CTR=$$c; break ;; \
			esac; \
		done; \
	fi; \
	if [ -z "$$APP_CTR" ]; then \
		$(CLI_OUTPUT) ui_error "Application container not found for release $(RELEASE_NAME)."; \
		ui_info "  Use '$(PODMAN_CMD) ps -a' to inspect containers."; \
		exit 1; \
	fi
endef

# Verify the running app container can actually talk to the mounted Podman socket.
# This polls deterministically for the app container to be running, then executes
# curl from inside the container against the socket path exposed via
# STAREXEC_CONTAINER_SOCKET.
test-podman-socket:
	@$(CLI_OUTPUT) ui_info "Waiting for application container to reach running state..."
	@$(CLI_OUTPUT) $(call resolve_app_container); \
	MAX_RETRIES=$${PODMAN_SOCKET_TEST_RETRIES:-60}; \
	WAIT_SECONDS=$${PODMAN_SOCKET_TEST_INTERVAL_SECONDS:-2}; \
	i=1; \
	ready=0; \
	while [ $$i -le $$MAX_RETRIES ]; do \
		INSPECT_STATE=$$($(PODMAN_CMD) inspect $$APP_CTR --format '{{.State.Running}} {{.State.Status}} {{.State.ExitCode}}' 2>/dev/null || echo "false missing -1"); \
		IS_RUNNING=$${INSPECT_STATE%% *}; \
		REST_STATE=$${INSPECT_STATE#* }; \
		STATUS=$${REST_STATE% *}; \
		EXIT_CODE=$${INSPECT_STATE##* }; \
		LAST_STATUS="$$STATUS"; \
		LAST_EXIT_CODE="$$EXIT_CODE"; \
		if [ "$$IS_RUNNING" = "true" ]; then \
			ready=1; \
			break; \
		fi; \
		ui_detail "App container not running yet ($$i/$$MAX_RETRIES), status: $${STATUS:-not found}, exit: $${EXIT_CODE:-unknown}, retrying in $${WAIT_SECONDS}s..."; \
		i=$$((i + 1)); \
		sleep $$WAIT_SECONDS; \
	done; \
	if [ $$ready -ne 1 ]; then \
		ui_error "App container failed to reach running state after $$MAX_RETRIES attempts (last status: $${LAST_STATUS:-unknown}, exit: $${LAST_EXIT_CODE:-unknown})."; \
		$(PODMAN_CMD) ps -a --format 'table {{.Names}}\t{{.Status}}' >&2; \
		ui_info "  Recent logs from $$APP_CTR:"; \
		$(PODMAN_CMD) logs --tail=80 $$APP_CTR >&2 2>/dev/null || true; \
		exit 1; \
	fi; \
	set +e; \
		SOCKET_PATH=$$($(PODMAN_CMD) exec $$APP_CTR sh -c 'printf "%s" "$${STAREXEC_CONTAINER_SOCKET#unix://}"' 2>&1); \
	EXEC_STATUS=$$?; \
	set -e; \
	if [ $$EXEC_STATUS -ne 0 ]; then \
		ui_error "Could not exec into app container $$APP_CTR."; \
		$(PODMAN_CMD) inspect $$APP_CTR --format '  state={{.State.Status}} running={{.State.Running}} exit={{.State.ExitCode}}' >&2 2>/dev/null || true; \
		ui_info "  Recent logs from $$APP_CTR:"; \
		$(PODMAN_CMD) logs --tail=80 $$APP_CTR >&2 2>/dev/null || true; \
		printf '%s\n' "$$SOCKET_PATH" >&2; \
		exit 1; \
	fi; \
	if [ -z "$$SOCKET_PATH" ]; then \
		ui_error "STAREXEC_CONTAINER_SOCKET is not set inside the app container."; \
		exit 1; \
	fi; \
	set +e; \
		CURL_OUTPUT=$$($(PODMAN_CMD) exec $$APP_CTR sh -c 'curl -fsS --unix-socket "$${STAREXEC_CONTAINER_SOCKET#unix://}" http://localhost/_ping' 2>&1); \
	CURL_STATUS=$$?; \
		set -e; \
	if [ $$CURL_STATUS -ne 0 ]; then \
		ui_error "Application container could not reach the host Podman API via $$SOCKET_PATH."; \
		printf '%s\n' "$$CURL_OUTPUT" >&2; \
		exit 1; \
	fi; \
	ui_success "Podman API is reachable from the application container."

# Quick socket validation (standalone, no values file required)
preflight-socket:
	@bash ./scripts/preflight-podman.sh || exit 1

# ============================================================================
# DATABASE MANAGEMENT (PostgreSQL)
# ============================================================================

define resolve_postgres_container
	PG_CONTAINER=""; \
	for c in "$(DB_CONTAINER)" "$(RELEASE_NAME)-pod-postgres" "starexec-pod-postgres" "starexec-postgres"; do \
		if $(PODMAN_CMD) container exists $$c >/dev/null 2>&1; then \
			PG_CONTAINER=$$c; \
			break; \
		fi; \
	done; \
	if [ -z "$$PG_CONTAINER" ]; then \
		for c in $$($(PODMAN_CMD) ps -a --filter "label=app.kubernetes.io/instance=$(RELEASE_NAME)" --format "{{.Names}}"); do \
			case "$$c" in \
				*postgres*) PG_CONTAINER=$$c; break ;; \
			esac; \
		done; \
	fi; \
	if [ -z "$$PG_CONTAINER" ]; then \
		$(CLI_OUTPUT) ui_error "PostgreSQL container not found for release $(RELEASE_NAME)."; \
		ui_info "  Use '$(PODMAN_CMD) ps -a' to inspect containers."; \
		exit 1; \
	fi
endef

db-shell:
	@echo "Opening PostgreSQL shell (container must be running)"
	@$(call resolve_postgres_container); \
	DB_PASS=$$( \
		if [ -n "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -f "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -r "$${STAREXEC_DB_PASSWORD_FILE}" ]; then \
			tr -d '\n' < "$${STAREXEC_DB_PASSWORD_FILE}"; \
		else \
			echo "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}"; \
		fi \
	); \
	DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	DB_NAME=$${STAREXEC_DB_DATABASE:-$(DB_NAME_DEFAULT)}; \
	echo "Using PostgreSQL container: $$PG_CONTAINER"; \
	PGPASSWORD="$$DB_PASS" $(PODMAN_CMD) exec -it "$$PG_CONTAINER" psql -U "$$DB_USER" -d "$$DB_NAME"

db-dump:
	@echo "Creating PostgreSQL dump (uses volume script if available)"
	@$(call resolve_postgres_container); \
	PG_CONTAINER="$$PG_CONTAINER" PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) dump-postgres $(ENV)

db-migrate:
	@echo "Running Flyway migrations (this may take 30-60 seconds)..."
	@DB_PASS=$$( \
		if [ -n "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -f "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -r "$${STAREXEC_DB_PASSWORD_FILE}" ]; then \
			tr -d '\n' < "$${STAREXEC_DB_PASSWORD_FILE}"; \
		else \
			echo "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}"; \
		fi \
	); \
	DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	DB_NAME=$${STAREXEC_DB_DATABASE:-$(DB_NAME_DEFAULT)}; \
	DB_HOST=$${DB_HOST:-$(DB_HOST_DEFAULT)}; \
	if command -v pg_isready >/dev/null 2>&1 && ! pg_isready -h $$DB_HOST -p 5432 -U $$DB_USER >/dev/null 2>&1; then \
		echo "❌ Unable to reach PostgreSQL at $$DB_HOST:5432"; \
		exit 1; \
	fi; \
	mvn -q -DskipTests \
		-Dflyway.url=jdbc:postgresql://$$DB_HOST:5432/$$DB_NAME \
		-Dflyway.user=$$DB_USER \
		-Dflyway.password=$$DB_PASS \
		flyway:migrate
	@echo "${GREEN}✓ Migrations completed successfully!${RESET}"

db-status:
	@echo "Checking Flyway migration status..."
	@DB_PASS=$$( \
		if [ -n "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -f "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -r "$${STAREXEC_DB_PASSWORD_FILE}" ]; then \
			tr -d '\n' < "$${STAREXEC_DB_PASSWORD_FILE}"; \
		else \
			echo "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}"; \
		fi \
	); \
	DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	DB_NAME=$${STAREXEC_DB_DATABASE:-$(DB_NAME_DEFAULT)}; \
	DB_HOST=$${DB_HOST:-$(DB_HOST_DEFAULT)}; \
	if command -v pg_isready >/dev/null 2>&1 && ! pg_isready -h $$DB_HOST -p 5432 -U $$DB_USER >/dev/null 2>&1; then \
		echo "❌ Unable to reach PostgreSQL at $$DB_HOST:5432"; \
		exit 1; \
	fi; \
	mvn -q -DskipTests \
		-Dflyway.url=jdbc:postgresql://$$DB_HOST:5432/$$DB_NAME \
		-Dflyway.user=$$DB_USER \
		-Dflyway.password=$$DB_PASS \
		flyway:info

migrate-repair:
	@echo "Running Flyway repair..."
	@DB_PASS=$$( \
		if [ -n "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -f "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -r "$${STAREXEC_DB_PASSWORD_FILE}" ]; then \
			tr -d '\n' < "$${STAREXEC_DB_PASSWORD_FILE}"; \
		else \
			echo "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}"; \
		fi \
	); \
	  echo "Using values file: $(VALS)"; \
	  DB_HOST=$${DB_HOST:-$(DB_HOST_DEFAULT)}; \
	  DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	  DB_NAME=$${STAREXEC_DB_DATABASE:-$(DB_NAME_DEFAULT)}; \
	  if command -v pg_isready >/dev/null 2>&1 && ! pg_isready -h $$DB_HOST -p 5432 -U $$DB_USER >/dev/null 2>&1; then \
		  echo "❌ Unable to reach PostgreSQL at $$DB_HOST:5432"; \
		  exit 1; \
	  fi; \
	  if command -v psql >/dev/null 2>&1; then \
		  LOCK_COUNT=$$(PGPASSWORD="$$DB_PASS" psql -h $$DB_HOST -U $$DB_USER -d $$DB_NAME -t -A -c "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'" 2>/dev/null || echo "0"); \
		  if [ "$${LOCK_COUNT:-0}" -gt 0 ]; then \
			  echo "${YELLOW}⚠️  Advisory locks detected before Flyway repair (count=$$LOCK_COUNT).${RESET}"; \
			  echo "   If repair hangs, inspect blockers with:"; \
			  echo "   SELECT pid, usename, application_name, state, query FROM pg_stat_activity WHERE wait_event_type = 'Lock';"; \
		  fi; \
	  fi; \
	  echo "Running Flyway repair against $$DB_HOST:5432/$$DB_NAME"; \
	  mvn clean flyway:repair -e \
		-Dflyway.url=jdbc:postgresql://$$DB_HOST:5432/$$DB_NAME \
		-Dflyway.user=$$DB_USER \
		-Dflyway.password=$$DB_PASS \
		-Dflyway.schemas=$$DB_NAME || { \
			echo "${RED}✗ Flyway repair failed.${RESET}"; \
			echo "If this is a stuck advisory lock issue, find and terminate blocker sessions:"; \
			echo "  SELECT pid, usename, application_name, state, wait_event, query FROM pg_stat_activity WHERE wait_event_type = 'Lock';"; \
			echo "  SELECT pg_terminate_backend(<pid>);"; \
			exit 1; \
		}


migrate-podman:
	@echo "============================================================================"
	@echo "⚠️  NOTICE: Migrations now run AUTOMATICALLY on container startup"
	@echo "============================================================================"
	@echo ""
	@echo "This manual command should ONLY be used for:"
	@echo "  - Debugging migration failures"
	@echo "  - Applying migrations to external/remote databases"
	@echo "  - Development testing of migration scripts"
	@echo ""
	@printf "Continue with manual migration? (y/N): "; \
	read ans; \
	if [ "$$ans" != "y" ] && [ "$$ans" != "Y" ]; then \
		echo "Cancelled; nothing was changed"; \
		exit 1; \
	fi
	@echo "Running Flyway migration against Podman PostgreSQL"
	@echo "Waiting for PostgreSQL to be ready..."
	@$(call resolve_postgres_container); \
	DB_PASS=$$( \
		if [ -n "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -f "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -r "$${STAREXEC_DB_PASSWORD_FILE}" ]; then \
			tr -d '\n' < "$${STAREXEC_DB_PASSWORD_FILE}"; \
		else \
			echo "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}"; \
		fi \
	); \
	DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	DB_NAME=$${STAREXEC_DB_DATABASE:-$(DB_NAME_DEFAULT)}; \
	DB_HOST=$${DB_HOST:-$(DB_HOST_DEFAULT)}; \
	echo "Using PostgreSQL container: $$PG_CONTAINER"; \
	for i in 1 2 3 4 5; do \
		if $(PODMAN_CMD) exec "$$PG_CONTAINER" pg_isready -h localhost -p 5432 -U"$$DB_USER" >/dev/null 2>&1; then \
			echo "PostgreSQL is ready"; \
			break; \
		fi; \
		echo "Waiting for PostgreSQL... ($$i/5)"; \
		sleep 2; \
	done; \
	echo "Running Flyway migration against $$DB_HOST:5432/$$DB_NAME"; \
	mvn clean flyway:migrate -e \
		-Dflyway.url=jdbc:postgresql://$$DB_HOST:5432/$$DB_NAME \
		-Dflyway.user="$$DB_USER" \
		-Dflyway.password="$$DB_PASS" \
		-Dflyway.schemas="$$DB_NAME"

# ============================================================================
# PODMAN DEPLOYMENT
# ============================================================================
network-setup:
	@$(CLI_OUTPUT) ui_info "Configuring Podman network..."
	@$(CLI_OUTPUT) if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
		ui_warn "Running in rootful mode. Consider running rootless for better security."; \
		ui_info "  See: https://github.com/containers/podman/blob/main/docs/tutorials/rootless_tutorial.md"; \
	fi
	@# Detect and repair stale/broken network state, not just existence.
	@$(CLI_OUTPUT) if $(PODMAN_CMD) network exists starexec-net 2>/dev/null; then \
		ui_detail "Network starexec-net exists. Validating configuration..."; \
		if ! $(PODMAN_CMD) network inspect starexec-net >/dev/null 2>&1; then \
			ui_warn "Network inspection failed. Recreating starexec-net..."; \
			ui_run $(PODMAN_CMD) network rm starexec-net || true; \
			ui_run $(PODMAN_CMD) network create starexec-net; \
			ui_success "Recreated network starexec-net"; \
		else \
			ui_detail "Network starexec-net can be inspected."; \
		fi; \
	else \
		ui_info "Creating network starexec-net..."; \
		ui_run $(PODMAN_CMD) network create starexec-net; \
		ui_success "Network starexec-net created"; \
	fi

network-reset:
	@$(CLI_OUTPUT) ui_info "Resetting Podman network: starexec-net"
	@$(CLI_OUTPUT) ui_run $(PODMAN_CMD) network rm starexec-net || true
	@$(MAKE) network-setup

define cleanup_deployment
	@$(CLI_OUTPUT) ui_info "Cleaning up existing StarExec pods, containers, and secrets..."
	@# Remove resources by label for robustness. This targets everything created by 'podman play kube'.
	@# The label 'app.kubernetes.io/instance' is standard for Helm.
	@$(CLI_OUTPUT) POD_IDS=$$($(PODMAN_CMD) pod ls --filter "label=app.kubernetes.io/instance=$(RELEASE_NAME)" --format "{{.Id}}"); \
	if [ -n "$$POD_IDS" ]; then \
		ui_info "Stopping pods for release $(RELEASE_NAME) (10s timeout)..."; \
		for pod in $$POD_IDS; do \
			ui_run $(PODMAN_CMD) pod stop -t 10 $$pod || true; \
			ui_detail "Removing pod $$pod..."; \
			if ! ui_run $(PODMAN_CMD) pod rm -f $$pod; then \
				ui_warn "Initial pod removal failed. Verifying pod state..."; \
				if $(PODMAN_CMD) pod exists $$pod; then \
					ui_warn "Pod $$pod still exists. Waiting 3s before retrying..."; \
					sleep 3; \
					ui_info "Retrying pod removal with extended timeout..."; \
					ui_run $(PODMAN_CMD) pod rm -f -t 30 $$pod || { \
						ui_error "Pod $$pod could not be removed after retry."; \
						exit 1; \
					}; \
				else \
					ui_info "Pod $$pod is now absent."; \
				fi; \
			else \
				ui_success "Pod $$pod removed."; \
			fi; \
		done; \
	fi
	@# Fallback for older naming scheme to ensure full cleanup during transition
	@$(CLI_OUTPUT) for pod in starexec starexec-pod $(POD_NAME); do \
		if $(PODMAN_CMD) pod exists $$pod 2>/dev/null; then \
			ui_info "Removing existing pod by legacy name: $$pod"; \
			ui_run $(PODMAN_CMD) pod stop -t 10 $$pod || true; \
			if ! ui_run $(PODMAN_CMD) pod rm -f $$pod; then \
				if $(PODMAN_CMD) pod exists $$pod; then \
					sleep 3; \
					ui_run $(PODMAN_CMD) pod rm -f -t 30 $$pod || exit 1; \
				fi; \
			fi; \
		fi; \
	done
	@# Clean up secrets associated with the release. Note: 'label' filter not supported for secrets in some Podman versions.
	@$(CLI_OUTPUT) SECRET_NAMES=$$($(PODMAN_CMD) secret ls --filter "name=$(RELEASE_NAME)" --format "{{.Name}}") ; \
	if [ -n "$$SECRET_NAMES" ]; then \
		ui_detail "Removing secrets matching name $(RELEASE_NAME)"; \
		echo "$$SECRET_NAMES" | ui_run xargs $(PODMAN_CMD) secret rm; \
	fi
	@# Safely cleanup only the StarExec network (do NOT global-prune networks).
	@# Deterministic approach: disconnect any remaining endpoints before removal,
	@# rather than blindly retrying and hoping the kernel GCs the veth pair.
	@$(CLI_OUTPUT) if $(PODMAN_CMD) network exists starexec-net 2>/dev/null; then \
		ui_info "Removing network starexec-net..."; \
		CONNECTED=$$($(PODMAN_CMD) network inspect starexec-net --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null || true); \
		if [ -n "$$CONNECTED" ]; then \
			ui_detail "Disconnecting remaining endpoints: $$CONNECTED"; \
			for ctr in $$CONNECTED; do \
				ui_run $(PODMAN_CMD) network disconnect -f starexec-net "$$ctr" || true; \
			done; \
		fi; \
		if ! ui_run $(PODMAN_CMD) network rm starexec-net; then \
			ui_warn "Network starexec-net could not be removed. Retrying in 3s..."; \
			sleep 3; \
			if ! ui_run $(PODMAN_CMD) network rm starexec-net; then \
				ui_warn "Network starexec-net could not be removed after retry."; \
				ui_info "  Inspect: $(PODMAN_CMD) network inspect starexec-net"; \
			else \
				ui_success "Network starexec-net removed after retry."; \
			fi; \
		else \
			ui_success "Network starexec-net removed."; \
		fi; \
	fi
endef

deploy-podman: preflight-podman preflight-cgroup
	@$(call require_podman_values)
	@$(CLI_OUTPUT) ui_info "Preparing deployment for ENV=$(ENV)..."
	@$(MAKE) image
	@$(MAKE) volumes-create
	@$(CLI_OUTPUT) if [ "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}" = "$(DB_PASSWORD_DEFAULT)" ]; then \
		if [ "$(ENV)" = "prod" ]; then \
			ui_error "Cannot deploy to production with default password."; \
			ui_info "  Set STAREXEC_DB_PASSWORD or use STAREXEC_DB_PASSWORD_FILE to provide a secure password."; \
			ui_info "  To override this check (not recommended): FORCE=1 make deploy-podman ENV=prod"; \
			if [ "$(FORCE)" != "1" ]; then \
				exit 1; \
			else \
				ui_warn "FORCE=1 specified, proceeding with insecure password..."; \
			fi; \
		fi; \
	fi
	@$(CLI_OUTPUT) if command -v helm >/dev/null 2>&1; then \
		if [ "$(ENV)" != "prod" ] && [ "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}" = "$(DB_PASSWORD_DEFAULT)" ]; then \
			ui_warn "Using default development database credentials."; \
		fi; \
		$(MAKE) deploy-podman-helm; \
	else \
		ui_info "Helm not found, using direct deployment..."; \
		$(MAKE) deploy-podman-direct; \
	fi

deploy-podman-helm:
	@$(call require_podman_values)
	@# Verify values file exists before proceeding
	@$(CLI_OUTPUT) if [ ! -f "$(PODMAN_VALS)" ]; then \
		ui_error "Values file not found: $(PODMAN_VALS)"; \
		ui_info "  Available values files:"; \
		ls -1 $(CHART_DIR)/values*.yaml >&2 2>/dev/null || ui_info "  (none found)"; \
		exit 1; \
	fi
	$(call cleanup_deployment)
	@$(MAKE) network-setup
	@$(CLI_OUTPUT) ui_info "Rendering deployment manifest..."
	@$(CLI_OUTPUT) DATA_VOL_NAME="$(VOLUME_PREFIX)-$(ENV)-data"; \
	SOCKET_GID=$$(stat -c '%g' "$(PODMAN_SOCKET_PATH)" 2>/dev/null || echo ""); \
	HOST_DATA_PATH=$$($(PODMAN_CMD) volume inspect "$$DATA_VOL_NAME" --format '{{.Mountpoint}}' 2>/dev/null || echo ""); \
	if ! helm template $(RELEASE_NAME) $(CHART_DIR) -f "$(PODMAN_VALS)" \
		--set environment=$(ENV) \
		--set image.repository=$(RELEASE_NAME) \
		--set image.tag=$(IMAGE_TAG) \
		--set image.pullPolicy=Never \
		--set backend.type=podman \
		--set podman.hostPort=$(APP_PORT) \
		--set-string podman.containerSocket.enabled=true \
		--set-string podman.containerSocket.hostPath="$(PODMAN_SOCKET_PATH)" \
		$${SOCKET_GID:+--set security.pod.supplementalGroups[0]=$$SOCKET_GID} \
		$${HOST_DATA_PATH:+--set backend.hostDataPath=$$HOST_DATA_PATH} > render.yaml; then \
		ui_error "Helm template generation failed"; \
		ui_info "  Check your values file: $(PODMAN_VALS)"; \
		exit 1; \
	fi
	@$(CLI_OUTPUT) ui_info "Applying deployment manifest..."
	@# Ensure pause image exists (Podman uses it automatically for pod infra)
	@./scripts/ensure-pause-image.sh
	@$(CLI_OUTPUT) ui_run $(PODMAN_CMD) play kube --network starexec-net --userns=keep-id render.yaml
	@$(CLI_OUTPUT) ui_success "Deployment manifest applied."
	@$(MAKE) wait-postgres
	@$(MAKE) test-podman-socket
	@$(CLI_OUTPUT) ui_detail "Environment: $(ENV); values: $(PODMAN_VALS)"; \
	ui_info "Migrations: not verified by this command"; \
	ui_info "Application URL (HTTP readiness not checked): http://localhost:$(APP_PORT)/starexec"; \
	ui_detail "Application logs: make logs-app ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"; \
	ui_detail "Database logs: make logs-postgres ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"

deploy-podman-direct:
	$(call cleanup_deployment)
	@$(MAKE) network-setup
	@./scripts/ensure-pause-image.sh
	@STAREXEC_DATA_VOL=$${STAREXEC_DATA_VOL:-starexec-$(ENV)-data} \
	 STAREXEC_POSTGRES_VOL=$${STAREXEC_POSTGRES_VOL:-starexec-$(ENV)-postgres} \
	 STAREXEC_BACKEND_TYPE=$${STAREXEC_BACKEND_TYPE:-podman} \
	 APP_PORT=$(APP_PORT) \
	 IMAGE_NAME=$(RELEASE_NAME) \
	 IMAGE_TAG=$(IMAGE_TAG) \
	 ./scripts/generate-render-yaml.sh
	@$(CLI_OUTPUT) ui_info "Applying deployment manifest..."
	@$(CLI_OUTPUT) ui_run $(PODMAN_CMD) play kube --network starexec-net --userns=keep-id render.yaml
	@$(CLI_OUTPUT) ui_success "Deployment manifest applied."
	@$(MAKE) wait-postgres
	@$(CLI_OUTPUT) ui_detail "Environment: $(ENV)"; \
	ui_info "Migrations: not verified by this command"; \
	ui_info "Application URL (HTTP readiness not checked): http://localhost:$(APP_PORT)/starexec"; \
	ui_detail "Application logs: make logs-app ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"; \
	ui_detail "Database logs: make logs-postgres ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"

deploy-podman-cached: preflight-podman
	@$(MAKE) image
	@$(CLI_OUTPUT) if [ ! -f render.yaml ]; then \
		ui_info "render.yaml not found. Running 'make template' to generate..."; \
		$(MAKE) template; \
	fi
	@$(CLI_OUTPUT) ui_info "Deploying using cached render.yaml..."
	$(call cleanup_deployment)
	@$(MAKE) network-setup
	@$(CLI_OUTPUT) ui_run $(PODMAN_CMD) play kube --network starexec-net --userns=keep-id render.yaml
	@$(CLI_OUTPUT) ui_success "Deployment manifest applied."
	@$(MAKE) wait-postgres
	@$(CLI_OUTPUT) ui_info "Migrations: not verified by this command"; \
	ui_info "Application URL (HTTP readiness not checked): http://localhost:$(APP_PORT)/starexec"; \
	ui_detail "To regenerate manifest: make template or make deploy-podman"

wait-postgres:
	@$(CLI_OUTPUT) ui_info "Waiting for PostgreSQL to accept connections..."
	@$(CLI_OUTPUT) $(call resolve_postgres_container); \
	DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	MAX_RETRIES=$${WAIT_POSTGRES_RETRIES:-60}; \
	WAIT_SECONDS=$${WAIT_POSTGRES_INTERVAL_SECONDS:-2}; \
	i=1; \
	ready=0; \
	ui_detail "Using PostgreSQL container: $$PG_CONTAINER"; \
	while [ $$i -le $$MAX_RETRIES ]; do \
		if $(PODMAN_CMD) exec "$$PG_CONTAINER" pg_isready -h localhost -p 5432 -U "$$DB_USER" >/dev/null 2>&1; then \
			ready=1; \
			break; \
		fi; \
		ui_detail "PostgreSQL not ready yet ($$i/$$MAX_RETRIES), retrying in $${WAIT_SECONDS}s..."; \
		i=$$((i + 1)); \
		sleep $$WAIT_SECONDS; \
	done; \
	if [ $$ready -ne 1 ]; then \
		ui_error "PostgreSQL did not become ready after $$MAX_RETRIES attempts."; \
		PG_EXIT=$$($(PODMAN_CMD) inspect "$$PG_CONTAINER" --format '{{.State.ExitCode}}' 2>/dev/null || echo "unknown"); \
		PG_RUNNING=$$($(PODMAN_CMD) inspect "$$PG_CONTAINER" --format '{{.State.Running}}' 2>/dev/null || echo "unknown"); \
		if [ "$$PG_RUNNING" = "false" ] && [ "$$PG_EXIT" != "0" ] && [ "$$PG_EXIT" != "unknown" ]; then \
			ui_info "  Container exited with code $$PG_EXIT."; \
			case "$$PG_EXIT" in \
				137) \
					ui_info "  Exit 137 can indicate SIGKILL (OOM killer or external kill); the cause is not verified."; \
					ui_info "  Inspect host memory pressure: dmesg | grep -i oom"; \
					ui_info "  If memory pressure is confirmed, increase container memory or host swap, or reduce shared_buffers/work_mem."; \
					;; \
				143) \
					ui_info "  Exit 143 can indicate SIGTERM; the shutdown cause is not verified."; \
					;; \
				*) \
					if $(PODMAN_CMD) logs "$$PG_CONTAINER" 2>&1 | grep -Eqi "(invalid primary checkpoint|could not locate a valid checkpoint|PANIC:|WAL)"; then \
						ui_warn "PostgreSQL logs contain WAL/checkpoint messages; inspect them before choosing recovery."; \
						ui_info "  Restore a known-good backup: make volumes-restore ENV=$(ENV)"; \
						ui_info "  Inspect logs: make logs-postgres ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"; \
						ui_info "  Last resort (destructive): manual pg_resetwal in postgres volume"; \
					fi; \
					;; \
			esac; \
		fi; \
		ui_info "  Last PostgreSQL logs:"; \
		$(PODMAN_CMD) logs --tail 80 "$$PG_CONTAINER" >&2 2>&1 || true; \
		exit 1; \
	fi
	@$(CLI_OUTPUT) ui_success "PostgreSQL is accepting connections."

undeploy-podman:
	@$(CLI_OUTPUT) ui_info "Removing Podman deployment (volumes preserved)"
	$(call cleanup_deployment)
	@$(CLI_OUTPUT) ui_info "Waiting for resource release..."
	@sleep 2
	@$(CLI_OUTPUT) ui_info "Deployment cleanup steps finished (volumes preserved); review any warnings above."; \
	ui_detail "To remove data: make volumes-delete ENV=$(ENV)"

# ============================================================================
# KUBERNETES DEPLOYMENT (with Auto-Detection)
# ============================================================================

# Auto-detect Kubernetes configuration
k8s-detect:
	@bash ./scripts/k8s-auto-detect.sh

# Generate optimized Kubernetes values file
k8s-generate-values:
	@bash ./scripts/k8s-auto-detect.sh --generate-values
	@$(CLI_OUTPUT) ui_detail "Deploy with: make deploy-k8s ENV=prod HELM_VALUES=charts/starexec/values-auto-detected.yaml"

# Setup Kubernetes cluster (nodes, namespaces, storage)
k8s-setup:
	@bash ./scripts/k8s-node-setup.sh --all

# Setup with dry-run (preview changes)
k8s-setup-dry-run:
	@bash ./scripts/k8s-node-setup.sh --all --dry-run

# Label worker nodes only
k8s-label-workers:
	@bash ./scripts/k8s-node-setup.sh --label-workers

# Complete automated Kubernetes deployment (auto-setup + deployment)
k8s-deploy-auto: k8s-detect
	@$(CLI_OUTPUT) ui_info "Setting up cluster infrastructure..."
	@bash ./scripts/k8s-node-setup.sh --all
	@$(CLI_OUTPUT) ui_info "Generating configuration..."
	@bash ./scripts/k8s-auto-detect.sh --generate-values
	@$(CLI_OUTPUT) ui_info "Deploying StarExec..."
	@$(MAKE) deploy-k8s ENV=prod HELM_VALUES=charts/starexec/values-auto-detected.yaml
	@KUBECTL_CMD="kubectl"; \
	if ! kubectl cluster-info >/dev/null 2>&1; then \
		if command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
			KUBECTL_CMD="microk8s kubectl"; \
		fi; \
	fi; \
	$$KUBECTL_CMD get pods -n starexec
	@$(CLI_OUTPUT) ui_info "Application URL (HTTP readiness not checked): http://<cluster-ip>/starexec"

# Deploy to Kubernetes with specified environment
deploy-k8s:
	@echo "Deploying to Kubernetes with environment: $(ENV)"
	@# HELM_VALUES names the values file: by default the one make k8s-generate-values writes.
	@# A file that does not exist stops the deploy rather than being swapped for another profile.
	@VALUES_FILE="$(HELM_VALUES)"; \
	if [ ! -f "$$VALUES_FILE" ]; then \
		echo "${RED}✗ Values file not found: $$VALUES_FILE${RESET}"; \
		echo "  Run 'make k8s-generate-values', or pass HELM_VALUES=<values file>."; \
		exit 1; \
	fi; \
	TMP_KUBECONFIG=""; \
	KUBECTL_CMD="kubectl"; \
	if kubectl cluster-info >/dev/null 2>&1; then \
		:; \
	elif command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
		TMP_KUBECONFIG="$$(mktemp)"; \
		microk8s config > "$$TMP_KUBECONFIG"; \
		KUBECTL_CMD="microk8s kubectl"; \
	else \
		echo "${RED}Cannot connect to Kubernetes with kubectl or microk8s kubectl${RESET}"; \
		exit 1; \
	fi; \
	echo "Using values file: $$VALUES_FILE"; \
 	if [ -n "$$TMP_KUBECONFIG" ]; then \
 		KUBECONFIG="$$TMP_KUBECONFIG" helm upgrade --install $(RELEASE_NAME) $(CHART_DIR) \
 			-f $$VALUES_FILE \
 			--namespace $(K8S_NAMESPACE) \
 			--create-namespace; \
 	else \
 		helm upgrade --install $(RELEASE_NAME) $(CHART_DIR) \
 			-f $$VALUES_FILE \
 			--namespace $(K8S_NAMESPACE) \
 			--create-namespace; \
 	fi; \
 	DEPLOY_EXIT=$$?; \
 	if [ $$DEPLOY_EXIT -ne 0 ]; then \
 		rm -f "$$TMP_KUBECONFIG"; \
 		exit $$DEPLOY_EXIT; \
 	fi; \
 	eval "$$KUBECTL_CMD get pods -n $(K8S_NAMESPACE)"; \
 	rm -f "$$TMP_KUBECONFIG"
	@echo ""
	@echo "${GREEN}✓ Kubernetes deployment complete!${RESET}"

undeploy-k8s:
	@TMP_KUBECONFIG=""; \
	if ! kubectl cluster-info >/dev/null 2>&1; then \
		if command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
			TMP_KUBECONFIG="$$(mktemp)"; \
			microk8s config > "$$TMP_KUBECONFIG"; \
		fi; \
	fi; \
 	if [ -n "$$TMP_KUBECONFIG" ]; then \
 		KUBECONFIG="$$TMP_KUBECONFIG" helm uninstall $(RELEASE_NAME) --namespace $(K8S_NAMESPACE) || true; \
 	else \
 		helm uninstall $(RELEASE_NAME) --namespace $(K8S_NAMESPACE) || true; \
	fi; \
	rm -f "$$TMP_KUBECONFIG"

# Check Kubernetes cluster health
k8s-status:
	@echo "${BOLD}${BLUE}Kubernetes Cluster Status${RESET}"
	@echo ""
	@echo "=== Nodes ==="
	@KUBECTL_CMD="kubectl"; \
	if ! kubectl cluster-info >/dev/null 2>&1; then \
		if command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
			KUBECTL_CMD="microk8s kubectl"; \
		fi; \
	fi; \
	if [ "$$KUBECTL_CMD" = "microk8s kubectl" ] || kubectl cluster-info >/dev/null 2>&1; then \
		$$KUBECTL_CMD get nodes -o wide; \
	else \
		echo "No working Kubernetes client found"; \
		exit 1; \
	fi
	@echo ""
	@echo "=== StarExec Pods ==="
	@KUBECTL_CMD="kubectl"; \
	if ! kubectl cluster-info >/dev/null 2>&1; then \
		if command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
			KUBECTL_CMD="microk8s kubectl"; \
		fi; \
	fi; \
	if [ "$$KUBECTL_CMD" = "microk8s kubectl" ] || kubectl cluster-info >/dev/null 2>&1; then \
		$$KUBECTL_CMD get pods -n starexec -o wide || echo "No pods deployed yet"; \
	else \
		echo "No working Kubernetes client found"; \
		exit 1; \
	fi
	@echo ""
	@echo "=== PersistentVolumeClaims ==="
	@KUBECTL_CMD="kubectl"; \
	if ! kubectl cluster-info >/dev/null 2>&1; then \
		if command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
			KUBECTL_CMD="microk8s kubectl"; \
		fi; \
	fi; \
	if [ "$$KUBECTL_CMD" = "microk8s kubectl" ] || kubectl cluster-info >/dev/null 2>&1; then \
		$$KUBECTL_CMD get pvc -n starexec -o wide || echo "No PVCs created yet"; \
	else \
		echo "No working Kubernetes client found"; \
		exit 1; \
	fi
	@echo ""
	@echo "=== Storage Classes ==="
	@KUBECTL_CMD="kubectl"; \
	if ! kubectl cluster-info >/dev/null 2>&1; then \
		if command -v microk8s >/dev/null 2>&1 && microk8s kubectl cluster-info >/dev/null 2>&1; then \
			KUBECTL_CMD="microk8s kubectl"; \
		fi; \
	fi; \
	if [ "$$KUBECTL_CMD" = "microk8s kubectl" ] || kubectl cluster-info >/dev/null 2>&1; then \
		$$KUBECTL_CMD get storageclass; \
	else \
		echo "No working Kubernetes client found"; \
		exit 1; \
	fi

# ============================================================================
# MAINTENANCE AND CLEANUP
# ============================================================================

clean-podman:
	@$(CLI_OUTPUT) ui_info "Cleaning Podman artifacts (preserving volumes and cache)"
	$(call cleanup_deployment)
	@$(CLI_OUTPUT) ui_info "Removing StarExec images..."
	@$(CLI_OUTPUT) ui_run $(PODMAN_CMD) rmi $(RELEASE_NAME):$(IMAGE_TAG) || true
	@$(CLI_OUTPUT) ui_info "Cleanup steps finished (volumes and cache preserved); review any warnings above."

clean-cache:
	@echo "${YELLOW}⚠️  WARNING: Clearing Podman build cache${RESET}"
	@echo "${YELLOW}This affects ALL projects on this system, not just StarExec${RESET}"
	@echo ""
	@echo "This will remove:"
	@echo "  - Unused images"
	@echo "  - Build cache"
	@echo "  - Builder instances"
	@echo ""
	@if [ "$(FORCE)" = "1" ]; then \
		echo "${YELLOW}FORCE=1 detected, skipping confirmation${RESET}"; \
	elif [ -t 0 ]; then \
		printf "Continue? (y/N): "; \
		read ans; \
		if [ "$$ans" != "y" ] && [ "$$ans" != "Y" ]; then \
			echo "Cancelled; nothing was changed"; \
			exit 1; \
		fi; \
	else \
		echo "${RED}Running non-interactively without FORCE=1. Aborting.${RESET}"; \
		echo "Set FORCE=1 to skip confirmation: make clean-cache FORCE=1"; \
		exit 1; \
	fi
	@echo "Pruning system..."
	@$(PODMAN_CMD) system prune -a -f || { echo "${RED}✗ Error during system prune${RESET}"; exit 1; }
	@echo "Pruning builder cache..."
	@$(PODMAN_CMD) builder prune -a -f 2>/dev/null || true
	@echo "${GREEN}✓ Build cache cleared successfully${RESET}"

clean-all: clean-podman volumes-delete
	@$(CLI_OUTPUT) ui_info "Cleanup steps finished (cache preserved); review any warnings above."

clean-hard:
ifeq ($(DRY_RUN),1)
	@$(CLI_OUTPUT) ui_info "[DRY RUN] Would perform hard reset for ENV=$(ENV):"
	@$(CLI_OUTPUT) ui_info "  - Remove pods: starexec, starexec-pod, $(POD_NAME)"
	@$(CLI_OUTPUT) ui_info "  - Remove containers: $(APP_CONTAINER), $(DB_CONTAINER)"
	@$(CLI_OUTPUT) ui_info "  - Remove secrets: $(RELEASE_NAME)-$(SECRET_NAME)-*"
	@$(CLI_OUTPUT) ui_info "  - Remove volumes: $(VOLUME_PREFIX)-$(ENV)-data, $(VOLUME_PREFIX)-$(ENV)-sandbox, $(VOLUME_PREFIX)-$(ENV)-backend, $(VOLUME_PREFIX)-$(ENV)-work, $(VOLUME_PREFIX)-$(ENV)-postgres, and $(VOLUME_PREFIX)-data and $(VOLUME_PREFIX)-postgres if they exist"
	@$(CLI_OUTPUT) if [ "$${CLEAN_HOSTPATH:-0}" = "1" ]; then \
		ui_info "  - Remove host directories: $${HOSTPATH_DATA:-/tmp/starexec-data}, $${HOSTPATH_POSTGRES:-/tmp/postgres-data} (CLEAN_HOSTPATH=1)"; \
	fi
	@$(CLI_OUTPUT) ui_info "  - Remove image: $(RELEASE_NAME):$(IMAGE_TAG)"
else
	@$(CLI_OUTPUT) ui_warn "Hard reset: removes StarExec resources for ENV=$(ENV)"; \
	ui_info "  This will delete pods, containers, secrets, volumes and images."; \
	ui_warn "All data in the selected volumes will be lost."
	@$(CLI_OUTPUT) if [ "$(FORCE)" != "1" ]; then \
		printf '%s' "Type '$(ENV)' to confirm: " >&2; \
		read ans; \
		if [ "$$ans" != "$(ENV)" ]; then \
			ui_info "Cancelled; nothing was changed"; \
			exit 1; \
		fi; \
	else \
		ui_warn "FORCE=1 detected, skipping confirmation"; \
	fi
	$(call cleanup_deployment)
	@sleep 3  # Allow network cleanup to complete
	@$(MAKE) volumes-delete ENV=$(ENV) FORCE=1
	@$(CLI_OUTPUT) ui_info "Removing StarExec image..."
	@$(CLI_OUTPUT) ui_run $(PODMAN_CMD) rmi $(RELEASE_NAME):$(IMAGE_TAG) || true
	@$(CLI_OUTPUT) ui_info "Reset steps finished for ENV=$(ENV); review any warnings above."
endif

# ============================================================================
# CONVENIENT CLEANUP ALIASES
# ============================================================================

# clean-hard removes the deployment itself, after its confirmation; a `stop` prerequisite would take the
# deployment down before the question is even asked, and during a DRY_RUN.
reset: clean-hard

nuke: reset

status:
	@printf 'StarExec status (ENV=%s)\n\n' "$(ENV)"
	@printf "%-20s: " "Pod state"
	@$(call resolve_pod_state,$(POD_NAME)); \
	if [ -z "$$POD_STATE" ]; then \
		printf '%s\n' 'unavailable (absent or inspection failed)'; \
	elif [ "$$POD_STATE" = "Degraded" ] || [ "$$POD_STATE" = "degraded" ]; then \
		printf '%s\n' "$$POD_STATE"; \
	elif [ "$$POD_STATE" = "Running" ] || [ "$$POD_STATE" = "running" ]; then \
		printf '%s\n' "$$POD_STATE"; \
	else \
		printf '%s\n' "$$POD_STATE"; \
	fi
	@printf "%-20s: %s\n" "Environment" "$(ENV)"
	@printf "%-20s: %s\n" "Image" "$(RELEASE_NAME):$(IMAGE_TAG)"
	@printf "%-20s: " "Data Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-data 2>/dev/null; then \
		printf '%s\n' 'exists'; \
	else \
		printf '%s\n' 'unavailable (absent or query failed)'; \
	fi
	@printf "%-20s: " "Sandbox Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-sandbox 2>/dev/null; then \
		printf '%s\n' 'exists'; \
	else \
		printf '%s\n' 'unavailable (absent or query failed)'; \
	fi
	@printf "%-20s: " "Backend Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-backend 2>/dev/null; then \
		printf '%s\n' 'exists'; \
	else \
		printf '%s\n' 'unavailable (absent or query failed)'; \
	fi
	@printf "%-20s: " "Work Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-work 2>/dev/null; then \
		printf '%s\n' 'exists'; \
	else \
		printf '%s\n' 'unavailable (absent or query failed)'; \
	fi
	@printf "%-20s: " "Postgres Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-postgres 2>/dev/null; then \
		printf '%s\n' 'exists'; \
	else \
		printf '%s\n' 'unavailable (absent or query failed)'; \
	fi
	@echo ""
	@printf '%s\n' 'Containers:'
	@$(CLI_OUTPUT) $(PODMAN_CMD) ps -a --filter name=$(RELEASE_NAME) --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || ui_warn "Could not query StarExec containers."
	@echo ""
	@printf '%s\n' 'Images:'
	@$(CLI_OUTPUT) $(PODMAN_CMD) images --filter reference=$(RELEASE_NAME) --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.Created}}" 2>/dev/null || ui_warn "Could not query StarExec images."
	@echo ""
	@$(call resolve_pod_state,$(POD_NAME)); \
	if [ "$$POD_STATE" = "Running" ] || [ "$$POD_STATE" = "running" ]; then \
		printf '%s\n' "Application URL (HTTP readiness not checked): http://localhost:$(APP_PORT)/starexec"; \
	elif [ "$$POD_STATE" = "Degraded" ] || [ "$$POD_STATE" = "degraded" ]; then \
		printf '%s\n' "  Inspect container states in the table above."; \
	elif [ -n "$$POD_STATE" ]; then \
		printf '%s\n' "  Restart with: make start ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"; \
	else \
		printf '%s\n' "  Deploy with: make start ENV=$(ENV) RELEASE_NAME=$(RELEASE_NAME)"; \
	fi

# ============================================================================
# DEBUGGING AND DIAGNOSTICS
# ============================================================================

# Log tail limits (adjust via LOG_LINES_APP/LOG_LINES_DB if needed)
LOG_LINES_APP?=50
LOG_LINES_DB?=30

logs:
	@echo "=== Application Logs (last $(LOG_LINES_APP) lines) ==="
	@CONTAINER=$$($(PODMAN_CMD) ps --filter "ancestor=$(RELEASE_NAME)" --format "{{.Names}}" | head -1); \
	if [ -n "$$CONTAINER" ]; then \
		$(PODMAN_CMD) logs --tail $(LOG_LINES_APP) "$$CONTAINER" 2>&1; \
	else \
		echo "App container not running"; \
	fi
	@echo ""
	@echo "=== PostgreSQL Logs (last $(LOG_LINES_DB) lines) ==="
	@$(PODMAN_CMD) logs --tail $(LOG_LINES_DB) $$($(PODMAN_CMD) ps --filter "ancestor=postgres" --format "{{.Names}}" | head -1) 2>&1 || echo "Postgres container not running"

logs-app:
	@echo "Following application logs (Ctrl+C to stop)..."
	@CONTAINER=$$($(PODMAN_CMD) ps --filter "ancestor=$(RELEASE_NAME)" --format "{{.Names}}" | head -1); \
	if [ -n "$$CONTAINER" ]; then \
		$(PODMAN_CMD) logs -f "$$CONTAINER"; \
	else \
		echo "App container not running"; \
	fi

logs-postgres:
	@echo "Following PostgreSQL logs (Ctrl+C to stop)..."
	@PG_CONTAINER=$$($(PODMAN_CMD) ps -a --filter "label=app.kubernetes.io/instance=$(RELEASE_NAME)" --format "{{.Names}}" | grep postgres | head -1); \
	if [ -z "$$PG_CONTAINER" ]; then \
		PG_CONTAINER=$$($(PODMAN_CMD) ps -a --filter "ancestor=postgres" --format "{{.Names}}" | head -1); \
	fi; \
	if [ -z "$$PG_CONTAINER" ]; then \
		echo "No PostgreSQL container found"; \
		exit 1; \
	fi; \
	echo "Using PostgreSQL container: $$PG_CONTAINER"; \
	$(PODMAN_CMD) logs -f "$$PG_CONTAINER"

test:
	@mvn test

test-deps:
	@echo "${BOLD}Testing job execution dependencies in container...${RESET}"
	@echo ""
	@echo "${BOLD}=== Installed Packages ===${RESET}"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) apk list --installed | grep -E "bash|util-linux|postgresql-client|procps" || true
	@echo ""
	@echo "${BOLD}=== Tool Versions ===${RESET}"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "echo 'bash:' && bash --version | head -1"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "echo 'flock:' && flock --version"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "echo 'lscpu:' && lscpu --version"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "echo 'psql:' && psql --version"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "echo 'ps:' && ps --version"
	@echo ""
	@echo "${BOLD}=== Command Availability ===${RESET}"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "which bash flock lscpu psql ps runsolver"
	@echo ""
	@echo "${BOLD}=== Test ps -p Command ===${RESET}"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c 'ps -p $$$$ -o pid,cmd'
	@echo ""
	@echo "${BOLD}=== Test flock -w Command ===${RESET}"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) bash -c "timeout 2 flock -x -w 1 /tmp/test.lock echo 'flock -w works!'"
	@echo ""
	@echo "${BOLD}=== CPU Info ===${RESET}"
	@$(PODMAN_CMD) exec $(APP_CONTAINER) lscpu | head -10
	@echo ""
	@echo "${GREEN}✓ All job execution dependencies validated${RESET}"

fix-cgroup-delegation:
	@./scripts/check-cgroup-delegation.sh --fix

preflight-cgroup:
	@$(CLI_OUTPUT) ui_info "Checking cgroup controller delegation..."
	@$(CLI_OUTPUT) if ./scripts/check-cgroup-delegation.sh >/dev/null 2>&1; then \
		ui_detail "Cgroup delegation check passed."; \
	else \
		ui_warn "Cgroup delegation check failed"; \
		if [ -t 0 ]; then \
			ui_info "  Run 'make fix-cgroup-delegation' to apply manually (requires sudo)"; \
		else \
			ui_info "  Non-interactive environment detected, skipping auto-fix."; \
			ui_info "  If deployment fails with cgroup errors: make fix-cgroup-delegation"; \
		fi; \
	fi

verify-deps:
	@$(CLI_OUTPUT) ui_info "Checking Podman prerequisites..."
	@$(CLI_OUTPUT) command -v podman >/dev/null && ui_detail "podman: available" || { ui_error "podman is required"; exit 1; }
	@$(CLI_OUTPUT) command -v sha256sum >/dev/null && ui_detail "sha256sum: available" || { ui_error "sha256sum is required"; exit 1; }
	@$(CLI_OUTPUT) command -v helm >/dev/null && ui_detail "helm: available" || ui_detail "helm: not installed (needed for Helm deployment)"
	@$(CLI_OUTPUT) command -v yq >/dev/null 2>&1 && yq --version >/dev/null 2>&1 && ui_detail "yq: available" || ui_detail "yq: not installed (optional)"
	@$(CLI_OUTPUT) ./scripts/check-cgroup-delegation.sh >/dev/null 2>&1 && ui_detail "cgroup delegation: check passed" || ui_warn "Cgroup delegation needs configuration (run 'make fix-cgroup-delegation')"
	@$(CLI_OUTPUT) if [ "$(PODMAN_REQUIRES_SUDO)" = "no" ]; then \
		if command -v newuidmap >/dev/null 2>&1 && command -v newgidmap >/dev/null 2>&1; then \
			ui_detail "newuidmap/newgidmap: available"; \
		else \
			ui_error "newuidmap/newgidmap: missing"; \
			ui_info "  Install uidmap package (Debian/Ubuntu: sudo apt install uidmap)"; \
			exit 1; \
		fi; \
	else \
		ui_detail "newuidmap/newgidmap: skipped (rootful podman mode)"; \
	fi
	@$(CLI_OUTPUT) if [ "$(PODMAN_REQUIRES_SUDO)" = "no" ]; then \
		U=$$(id -un); \
		if grep -q "^$$U:" /etc/subuid 2>/dev/null && grep -q "^$$U:" /etc/subgid 2>/dev/null; then \
			ui_detail "subuid/subgid: mapping found"; \
		else \
			ui_error "subuid/subgid: missing mapping"; \
			ui_info "  Rootless Podman requires subuid/subgid entries for user '$$U'."; \
			ui_info "  Ask admin to add ranges in /etc/subuid and /etc/subgid, then relogin."; \
			exit 1; \
		fi; \
	else \
		ui_detail "subuid/subgid: skipped (rootful podman mode)"; \
	fi
	@# Check for common rootless Podman issue (missing /run/user/UID)
	@$(CLI_OUTPUT) if [ -z "$$XDG_RUNTIME_DIR" ] && [ ! -w "/run/user/$$(id -u)" ]; then \
		ui_error "XDG_RUNTIME_DIR not set and /run/user/$$(id -u) is not writable."; \
		ui_info "  This is common on lab/shared machines without full PAM integration."; \
		ui_info "  Podman cannot start without a place to store runtime data."; \
		ui_info "  Run before make: export XDG_RUNTIME_DIR=/tmp/run-$$(id -u) && mkdir -p \$$XDG_RUNTIME_DIR"; \
		exit 1; \
	fi
	@$(CLI_OUTPUT) ui_detail "Required dependency checks passed."
	@$(CLI_OUTPUT) if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
		if command -v catatonit >/dev/null 2>&1; then \
            ui_detail "catatonit: available"; \
        else \
            ui_warn "catatonit: not installed"; \
            ui_info "  Rootful Podman needs catatonit to build infra/pause containers."; \
            ui_info "  Install with: sudo apt install catatonit"; \
        fi; \
    else \
        ui_detail "catatonit: skipped (rootless mode uses pre-pulled pause image)"; \
    fi

lint:
	@if command -v helm >/dev/null 2>&1; then \
		echo "Linting Helm chart..."; \
		if [ -f "$(CHART_DIR)/values-local-dev.yaml" ]; then \
			echo "  Using values-local-dev.yaml for basic validation..."; \
			helm lint $(CHART_DIR) -f $(CHART_DIR)/values-local-dev.yaml; \
		else \
			echo "  Using default values.yaml (may show password warnings)..."; \
			helm lint $(CHART_DIR) || echo "  ⚠️  Lint failed - this is expected if passwords not set in defaults"; \
		fi; \
		echo "Validating all value files..."; \
		for f in $(CHART_DIR)/values*.yaml; do \
			if [ "$$f" != "$(CHART_DIR)/values.yaml" ]; then \
				case "$$f" in \
					*/values-dev.yaml) namespace=starexec-dev ;; \
					*/values-prod.yaml) namespace=starexec ;; \
					*/values-ci.yaml) namespace=starexec-ci ;; \
					*/values-local-dev.yaml) namespace=starexec-local ;; \
					*) namespace=default ;; \
				esac; \
				echo "  Checking $$f..."; \
				helm template $(CHART_DIR) --namespace "$$namespace" -f $$f > /dev/null && echo "    ✓ Valid" || echo "    ✗ Invalid"; \
			fi; \
		done; \
	else \
		echo "Helm not installed, skipping chart validation"; \
	fi

template:
	@$(CLI_OUTPUT) if command -v helm >/dev/null 2>&1; then \
		ui_info "Rendering templates with environment: $(ENV)"; \
		$(call require_values_file); \
		helm template $(RELEASE_NAME) $(CHART_DIR) -f $(PODMAN_VALS) \
			> render.yaml; \
		ui_success "Output written to: render.yaml"; \
	else \
		ui_detail "Helm not installed, generating from template..."; \
		STAREXEC_DATA_VOL=$${STAREXEC_DATA_VOL:-starexec-$(ENV)-data} \
		STAREXEC_SANDBOX_VOL=$${STAREXEC_SANDBOX_VOL:-starexec-$(ENV)-sandbox} \
		STAREXEC_BACKEND_VOL=$${STAREXEC_BACKEND_VOL:-starexec-$(ENV)-backend} \
		STAREXEC_WORK_VOL=$${STAREXEC_WORK_VOL:-starexec-$(ENV)-work} \
		STAREXEC_POSTGRES_VOL=$${STAREXEC_POSTGRES_VOL:-starexec-$(ENV)-postgres} \
		STAREXEC_BACKEND_TYPE=$${STAREXEC_BACKEND_TYPE:-podman} \
		APP_PORT=$(APP_PORT) \
		IMAGE_NAME=$(RELEASE_NAME) \
		IMAGE_TAG=$(IMAGE_TAG) \
		./scripts/generate-render-yaml.sh; \
	fi

config-show:
	@echo "========================================"
	@echo "Configuration Report for ENV=$(ENV)"
	@echo "========================================"
	@echo ""
	@echo "=== Source Files ==="
	@echo "Values file: $(VALS)"
	@echo "Helm chart: $(CHART_DIR)"
	@echo "Volume prefix: $(VOLUME_PREFIX)"
	@echo ""
	@echo "=== Database Configuration ==="
	@if command -v yq >/dev/null 2>&1; then \
		DB_HOST=$$(yq '.postgres.host // "NOT_SET"' $(VALS)); \
		DB_USER=$$(yq '.postgres.user // "NOT_SET"' $(VALS)); \
		DB_NAME=$$(yq '.postgres.database // "NOT_SET"' $(VALS)); \
		echo "  Host: $$DB_HOST (from values file)"; \
		echo "  User: $$DB_USER (from values file)"; \
		echo "  Database: $$DB_NAME (from values file)"; \
		echo "  Password: ***REDACTED*** (check $(VALS))"; \
	else \
		echo "  yq not available - install yq to parse YAML values"; \
	fi
	@echo ""
	@echo "=== Environment Variable Overrides ==="
	@echo "  STAREXEC_DB_HOST=$${STAREXEC_DB_HOST:-<not set>}"
	@echo "  STAREXEC_DB_USER=$${STAREXEC_DB_USER:-<not set>}"
	@echo "  STAREXEC_DB_PASSWORD_FILE=$${STAREXEC_DB_PASSWORD_FILE:-<not set>}"
	@echo "  STAREXEC_DB_PASSWORD=***REDACTED***"
	@echo ""
	@echo "=== Java Defaults (fallback) ==="
	@echo "  DB User: starexec (EnvironmentConfig.java)"
	@echo "  DB Password: empty (EnvironmentConfig.java)"
	@echo "  DB Host: localhost (EnvironmentConfig.java)"
	@echo ""
	@echo "=== Effective Configuration ==="
	@echo "  (This shows what would actually be used at runtime)"
	@if [ -f render.yaml ]; then \
		echo "  From rendered manifest (render.yaml):"; \
		awk -f scripts/mask-env-values.awk render.yaml | \
			grep -A 5 "STAREXEC_DB" | head -20; \
	else \
		echo "  ⚠️  No render.yaml found. Run 'make template' first."; \
	fi
	@echo ""
	@echo "=== Validation ==="
	@echo "  Run 'make config-validate ENV=$(ENV)' to check for conflicts"

runtime-check:
	@echo "========================================"
	@echo "${BOLD}OCI Runtime Configuration Check${RESET}"
	@echo "========================================"
	@echo ""
	@echo "Available Runtimes:"
	@echo "  crun: $(if $(shell which crun 2>/dev/null),${GREEN}✓ available${RESET},${RED}✗ not found${RESET})"
	@echo "  runc: $(if $(shell which runc 2>/dev/null),${GREEN}✓ available${RESET},${RED}✗ not found${RESET})"
	@echo ""
	@echo "Podman Configuration:"
	@echo "  Requires sudo:      $(PODMAN_REQUIRES_SUDO)"
	@echo "  Configured runtime: $(PODMAN_CONFIG_RUNTIME)"
	@echo "  Effective runtime:  $(OCI_RUNTIME_AVAILABLE)"
	@echo "  Force override:     $(FORCE_RUNTIME)"
	@echo ""
	@echo "Final Command Setup:"
	@echo "  PODMAN_CMD: $(BOLD)$(PODMAN_CMD)$(RESET)"
	@echo ""
	@if [ "$(OCI_RUNTIME_AVAILABLE)" = "none" ]; then \
		echo "${RED}ERROR: No OCI runtime (crun or runc) found!${RESET}"; \
		echo "Please install one to use Podman:"; \
		echo "  Ubuntu/Debian: sudo apt-get install crun"; \
		echo "  RHEL/CentOS:   sudo dnf install crun"; \
		exit 1; \
	elif [ "$(FORCE_RUNTIME)" = "yes" ]; then \
		echo "${YELLOW}ADVISORY: Runtime mismatch or broken config detected.${RESET}"; \
		echo "Podman is configured to use '${PODMAN_CONFIG_RUNTIME}' but only '${OCI_RUNTIME_AVAILABLE}' is available."; \
		echo "The Makefile is automatically applying --runtime=$(OCI_RUNTIME_AVAILABLE) to all commands."; \
		echo ""; \
		echo "To fix permanently, update your podman configuration:"; \
		echo "  mkdir -p ~/.config/containers"; \
		echo "  echo '[engine]' > ~/.config/containers/containers.conf"; \
		echo "  echo 'runtime = \"$(OCI_RUNTIME_AVAILABLE)\"' >> ~/.config/containers/containers.conf"; \
	else \
		echo "${GREEN}✓ OCI runtime configuration is healthy.${RESET}"; \
	fi

# ============================================================================
# DOCUMENTATION GENERATION (prevents drift)
# ============================================================================

docs:
	@echo "Generating documentation reference..."
	@mkdir -p docs/reference
	@echo "# Makefile Targets Reference" > docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "> **Auto-generated from Makefile** - Do not edit manually" >> docs/reference/makefile-targets.md
	@echo "> Run \`make docs\` to update this file" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "## All Available Targets" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^[a-zA-Z0-9_-]+:" Makefile | \
		grep -v "^.PHONY" | \
		sed 's/:.*//' | \
		sort | \
		uniq | \
		awk '{print "- `" $$1 "`"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "## Target Categories" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "### Build & Image Management" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^(build|image|pull)" Makefile | grep ":" | sed 's/:.*//' | grep -v "pull-job-runner" | awk '{print "- `" $$1 "` - Build or update container images"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "### Deployment Targets" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^(deploy|undeploy|start|stop)" Makefile | grep ":" | sed 's/:.*//' | awk '{print "- `" $$1 "` - Deployment operation"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "### Volume Management" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^volumes-" Makefile | grep ":" | sed 's/:.*//' | awk '{print "- `" $$1 "` - Volume operation"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "### Database Management" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^(db-|migrate-)" Makefile | grep ":" | sed 's/:.*//' | awk '{print "- `" $$1 "` - Database operation"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "### Maintenance & Cleanup" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^(clean-|reset|nuke|status)" Makefile | grep ":" | sed 's/:.*//' | awk '{print "- `" $$1 "` - Maintenance operation"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "### Debugging & Diagnostics" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@grep -E "^(logs|test-deps|verify-deps|lint|template|config-)" Makefile | grep ":" | sed 's/:.*//' | awk '{print "- `" $$1 "` - Diagnostic operation"}' >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "---" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "For detailed usage of each target, run \`make help\`" >> docs/reference/makefile-targets.md
	@echo "" >> docs/reference/makefile-targets.md
	@echo "${GREEN}✓ Generated docs/reference/makefile-targets.md successfully!${RESET}"
	@echo "  Remember to run 'make docs' after adding new targets!"
