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
# DRY_RUN         - Preview destructive ops without executing: 0 or 1 (default: 0)
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
HELM_VALUES?=values.yaml
SECRET_NAME=secret-postgres
STAREXEC_DB_PASSWORD?=starexec_dev_password
STAREXEC_DB_PASSWORD_FILE?=/run/secrets/starexec-db-password
DB_PASSWORD_DEFAULT?=starexec_dev_password
DB_USER_DEFAULT?=starexec
DB_NAME_DEFAULT?=starexec
DB_HOST_DEFAULT?=localhost
ENV?=dev
ENV_VALUES=$(CHART_DIR)/values-$(ENV).yaml
VOLUME_SCRIPT=./scripts/podman-volumes.sh
VOLUME_PREFIX=starexec
VALS := $(if $(wildcard $(ENV_VALUES)),$(ENV_VALUES),$(CHART_DIR)/values.yaml)

FORCE?=0
DRY_RUN?=0

# Container naming (derived from RELEASE_NAME for consistency)
APP_CONTAINER=$(RELEASE_NAME)-app
DB_CONTAINER=$(RELEASE_NAME)-postgres
POD_NAME=$(RELEASE_NAME)-pod

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

# ANSI Colors for UI
GREEN  := $(shell tput -Txterm setaf 2)
YELLOW := $(shell tput -Txterm setaf 3)
RED    := $(shell tput -Txterm setaf 1)
BLUE   := $(shell tput -Txterm setaf 4)
RESET  := $(shell tput -Txterm sgr0)
BOLD   := $(shell tput -Txterm bold)

.PHONY: help build build-fresh build-prod image \
	deploy-podman deploy-podman-helm deploy-podman-direct network-setup deploy-podman-cached undeploy-podman \
	deploy-k8s undeploy-k8s \
	volumes-create volumes-list volumes-backup volumes-restore volumes-export volumes-delete volumes-help \
	db-shell db-dump db-migrate db-status migrate-repair migrate-podman \
	clean-podman clean-cache clean-all clean-hard reset nuke status lint template config-show runtime-check \
	logs logs-app logs-postgres test-deps verify-deps test docs \
	start stop fix-cgroup-delegation

start: deploy-podman

stop: undeploy-podman


help:
	@echo "StarExec DevOps Build System"
	@echo "============================="
	@echo ""
	@echo "Usage: make <target> [ENV=<env>]"
	@echo ""
	@echo "Build Targets:"
	@echo "  build                  Build container image (uses cache)"
	@echo "  build-fresh            Build without cache (slower, guaranteed fresh)"
	@echo "  build-prod             Build production image with registry tag"
	@echo "  image                  Ensure image exists (pulls rolling tags automatically)"
	@echo "  pull                   Force update all images from registry"
	@echo ""
	@echo "Deployment Targets:"
	@echo "  deploy-podman          Deploy to Podman (ensures image + render + apply)"
	@echo "  deploy-podman-cached   Fast deploy using existing render.yaml (ensures image)"
	@echo "  deploy-k8s             Deploy to Kubernetes (requires Helm)"
	@echo "  undeploy-podman        Remove Podman deployment"
	@echo "  undeploy-k8s           Remove Kubernetes deployment"
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
	@echo "  DRY_RUN=1             Preview destructive operations without executing"
	@echo "  FORCE=1               Skip confirmation prompts (use with caution)"
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
	podman build -t $(RELEASE_NAME):$(IMAGE_TAG) .
	@echo "${GREEN}✓ Image built successfully: $(RELEASE_NAME):$(IMAGE_TAG)${RESET}"
	@podman images --format "  Size: {{.Size}}" $(RELEASE_NAME):$(IMAGE_TAG)

build-fresh:
	@echo "Building fresh image (no cache): $(RELEASE_NAME):$(IMAGE_TAG)"
	podman build --no-cache -t $(RELEASE_NAME):$(IMAGE_TAG) .
	@echo "${GREEN}✓ Fresh image built successfully: $(RELEASE_NAME):$(IMAGE_TAG)${RESET}"
	@podman images --format "  Size: {{.Size}}" $(RELEASE_NAME):$(IMAGE_TAG)

build-prod:
	@echo "Building production image"
	@IMAGE_REGISTRY=$${IMAGE_REGISTRY:-ghcr.io/starExecmiami}; \
	IMAGE_VERSION=$${IMAGE_VERSION:-1.0.0}; \
	podman build -t $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION -t $$IMAGE_REGISTRY/starexec:latest .
	@echo "${GREEN}✓ Production image built successfully ${RESET}"
	@echo "  Image: $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION"
	@podman images --format "  Size: {{.Size}}" $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION
	@echo "Push with: podman push $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION"

image:
	@echo "Checking for image: $(RELEASE_NAME):$(IMAGE_TAG)"
	@# For 'latest' or 'dev' tags, we should at least attempt to check for updates from the registry
	@if [ "$(IMAGE_TAG)" = "latest" ] || [ "$(IMAGE_TAG)" = "dev" ]; then \
		echo "Rolling tag detected ($(IMAGE_TAG)). Checking registry for updates..."; \
		if $(PODMAN_CMD) pull $(IMAGE_NAME):$(IMAGE_TAG) 2>/dev/null; then \
			echo "${GREEN}✓ Successfully checked/pulled $(IMAGE_NAME):$(IMAGE_TAG)${RESET}"; \
			$(PODMAN_CMD) tag $(IMAGE_NAME):$(IMAGE_TAG) $(RELEASE_NAME):$(IMAGE_TAG) 2>/dev/null || true; \
		else \
			echo "${YELLOW}⚠️  Could not reach registry. Proceeding with local image if available...${RESET}"; \
		fi; \
	fi
	@if $(PODMAN_CMD) image inspect $(RELEASE_NAME):$(IMAGE_TAG) >/dev/null 2>&1; then \
		echo "${GREEN}✓ Image $(RELEASE_NAME):$(IMAGE_TAG) found locally${RESET}"; \
	else \
		echo "Image not found locally at $(RELEASE_NAME):$(IMAGE_TAG)"; \
		echo "Attempting to pull from registry..."; \
		if $(PODMAN_CMD) pull $(IMAGE_NAME):$(IMAGE_TAG) 2>/dev/null; then \
			$(PODMAN_CMD) tag $(IMAGE_NAME):$(IMAGE_TAG) $(RELEASE_NAME):$(IMAGE_TAG); \
			echo "${GREEN}✓ Successfully pulled and tagged $(RELEASE_NAME):$(IMAGE_TAG)${RESET}"; \
		else \
			echo "⚠️  Image not available in registry. Building locally..."; \
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
	podman pull $(JOB_RUNNER_IMAGE):$(JOB_RUNNER_TAG)
	@echo "${GREEN}✓ Job runner image pulled successfully: ${RESET}"
	@podman images --format "  Size: {{.Size}}" $(JOB_RUNNER_IMAGE):$(JOB_RUNNER_TAG)

# Build job-runner locally (for development only)
build-job-runner:
	@echo "Building job-runner image locally (Alpine): $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG)"
	@echo "Note: Production deployments should use 'make pull-job-runner' instead"
	podman build -t $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG) -f docker/job-runner.Dockerfile .
	@echo "${GREEN}✓ Job runner image built successfully: ${RESET}"
	@echo "  Image: $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG)"
	@podman images --format "  Size: {{.Size}}" $(JOB_RUNNER_LOCAL_IMAGE):$(JOB_RUNNER_TAG)

# ============================================================================
# VOLUME MANAGEMENT (Podman Named Volumes - RECOMMENDED APPROACH)
# ============================================================================

volumes-create:
	@echo "Creating volumes for environment: $(ENV)"
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) create $(ENV)

volumes-list:
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) list

volumes-backup: verify-deps
	@echo "Backing up volumes for environment: $(ENV)"
	@# Safety check: warn if containers are running during backup
	@if podman pod exists $(POD_NAME) 2>/dev/null || podman pod exists starexec 2>/dev/null; then \
		echo ""; \
		echo "${YELLOW}⚠️  WARNING: StarExec containers are currently RUNNING${RESET}"; \
		echo "${YELLOW}   For a consistent backup, consider stopping first:${RESET}"; \
		echo "   ${BLUE}make stop${RESET}"; \
		echo ""; \
		if [ "$(FORCE)" != "1" ] && [ -t 0 ]; then \
			printf "Continue with backup anyway? (y/N): "; \
			read ans; \
			if [ "$$ans" != "y" ] && [ "$$ans" != "Y" ]; then \
				echo "Backup cancelled."; \
				exit 0; \
			fi; \
		fi; \
	fi
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) backup-all $(ENV)

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
	@if podman pod exists $(POD_NAME) 2>/dev/null || podman pod exists starexec 2>/dev/null; then \
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
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) restore-all $(ENV) $$ts

volumes-export:
	@printf "Volume name: "; \
	read vol; \
	if [ -z "$$vol" ]; then \
		echo "${RED}✗ No volume name provided, aborting${RESET}"; \
		exit 1; \
	fi; \
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) export $$vol

volumes-cleanup:
	@echo "Cleaning up old backups (keeping last 10)"
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) cleanup-backups $(ENV) 10

volumes-health:
	@echo "Running volume health checks for environment: $(ENV)"
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) health-check $(ENV)

volumes-delete:
	@if [ "$(DRY_RUN)" = "1" ]; then \
		echo "[DRY RUN] Would delete volumes for environment: $(ENV)"; \
		echo "  - $(VOLUME_PREFIX)-$(ENV)-data"; \
		echo "  - $(VOLUME_PREFIX)-$(ENV)-sandbox"; \
		echo "  - $(VOLUME_PREFIX)-$(ENV)-backend"; \
		echo "  - $(VOLUME_PREFIX)-$(ENV)-work"; \
		echo "  - $(VOLUME_PREFIX)-$(ENV)-postgres"; \
		exit 0; \
	fi
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) delete $(ENV)

volumes-help:
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) help

# ============================================================================
# DATABASE MANAGEMENT (PostgreSQL)
# ============================================================================

db-shell:
	@echo "Opening PostgreSQL shell (container must be running)"
	@if ! podman container exists $(DB_CONTAINER) >/dev/null 2>&1; then \
		echo "❌ PostgreSQL container not running. Run 'make deploy-podman' first."; \
		exit 1; \
	fi
	@DB_PASS=$$( \
		if [ -n "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -f "$${STAREXEC_DB_PASSWORD_FILE}" ] && [ -r "$${STAREXEC_DB_PASSWORD_FILE}" ]; then \
			tr -d '\n' < "$${STAREXEC_DB_PASSWORD_FILE}"; \
		else \
			echo "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}"; \
		fi \
	); \
	DB_USER=$${STAREXEC_DB_USER:-$(DB_USER_DEFAULT)}; \
	DB_NAME=$${STAREXEC_DB_DATABASE:-$(DB_NAME_DEFAULT)}; \
	PGPASSWORD="$$DB_PASS" podman exec -it $(DB_CONTAINER) psql -U "$$DB_USER" -d "$$DB_NAME"

db-dump:
	@echo "Creating PostgreSQL dump (uses volume script if available)"
	PODMAN_CMD="$(PODMAN_CMD)" $(VOLUME_SCRIPT) dump-postgres $(ENV)

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
	  echo "Running Flyway repair against $$DB_HOST:5432/$$DB_NAME"; \
	  mvn clean flyway:repair -e \
		-Dflyway.url=jdbc:postgresql://$$DB_HOST:5432/$$DB_NAME \
		-Dflyway.user=$$DB_USER \
		-Dflyway.password=$$DB_PASS \
		-Dflyway.schemas=$$DB_NAME


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
		echo "Cancelled"; \
		exit 0; \
	fi
	@echo "Running Flyway migration against Podman PostgreSQL"
	@echo "Waiting for PostgreSQL to be ready..."
	@if ! podman container exists $(DB_CONTAINER) >/dev/null 2>&1; then \
		echo "❌ PostgreSQL container not running. Run 'make deploy-podman' first."; \
		exit 1; \
	fi
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
	for i in 1 2 3 4 5; do \
		if podman exec $(DB_CONTAINER) pg_isready -h localhost -p 5432 -U"$$DB_USER" >/dev/null 2>&1; then \
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
	@echo "Configuring Podman network (rootless mode)"
	@if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
		echo "⚠️  Running in rootful mode. Consider running rootless for better security."; \
		echo "See: https://github.com/containers/podman/blob/main/docs/tutorials/rootless_tutorial.md"; \
	fi
	@# For rootless, Podman uses pasta/slirp4netns automatically via netavark
	@if ! $(PODMAN_CMD) network exists starexec-net 2>/dev/null; then \
		echo "Creating network (pasta/slirp4netns handled automatically)"; \
		$(PODMAN_CMD) network create starexec-net; \
	fi

define cleanup_deployment
	@echo "Cleaning up existing StarExec pods, containers, and secrets..."
	@# Remove resources by label for robustness. This targets everything created by 'podman play kube'.
	@# The label 'app.kubernetes.io/instance' is standard for Helm.
	@POD_IDS=$$($(PODMAN_CMD) pod ls --filter "label=app.kubernetes.io/instance=$(RELEASE_NAME)" --format "{{.Id}}"); \
	if [ -n "$$POD_IDS" ]; then \
		echo "  Removing existing pod(s) with label app.kubernetes.io/instance=$(RELEASE_NAME)"; \
		echo "$$POD_IDS" | xargs $(PODMAN_CMD) pod rm -f || { \
			echo "⚠️  Pod removal reported error (likely network cleanup race), verifying..."; \
			REMAINING=$$($(PODMAN_CMD) pod ls --filter "label=app.kubernetes.io/instance=$(RELEASE_NAME)" -q); \
			if [ -n "$$REMAINING" ]; then \
				echo "${RED}❌ Error: Pods still exist: $$REMAINING${RESET}"; \
				exit 1; \
			else \
				echo "${GREEN}✓ Pods successfully removed despite error message${RESET}"; \
			fi; \
		}; \
	fi
	@# Fallback for older naming scheme to ensure full cleanup during transition
	@for pod in starexec starexec-pod $(POD_NAME); do \
		if $(PODMAN_CMD) pod exists $$pod 2>/dev/null; then \
			echo "  Removing existing pod by legacy name: $$pod"; \
			$(PODMAN_CMD) pod rm -f $$pod 2>/dev/null || true; \
		fi \
	done
	@# Clean up secrets associated with the release. Note: 'label' filter not supported for secrets in some Podman versions.
	@SECRET_NAMES=$$($(PODMAN_CMD) secret ls --filter "name=$(RELEASE_NAME)" --format "{{.Name}}") ; \
	if [ -n "$$SECRET_NAMES" ]; then \
		echo "  Removing secrets matching name $(RELEASE_NAME)"; \
		echo "$$SECRET_NAMES" | xargs $(PODMAN_CMD) secret rm; \
	fi
endef

deploy-podman: verify-deps image network-setup volumes-create
	@echo "${BOLD}${BLUE}Deploying to Podman with environment: $(ENV)${RESET}"
	@echo "Using values file: $(VALS)"
	@if [ "$${STAREXEC_DB_PASSWORD:-$(DB_PASSWORD_DEFAULT)}" = "$(DB_PASSWORD_DEFAULT)" ]; then \
		echo ""; \
		echo "${RED}╔════════════════════════════════════════════════════════╗${RESET}"; \
		echo "${RED}║  ⚠️  INSECURE: Using default development DB password    ║${RESET}"; \
		echo "${RED}╚════════════════════════════════════════════════════════╝${RESET}"; \
		echo ""; \
		if [ "$(ENV)" = "prod" ]; then \
			echo "${RED}ERROR: Cannot deploy to production with default password!${RESET}"; \
			echo ""; \
			echo "Set STAREXEC_DB_PASSWORD or use STAREXEC_DB_PASSWORD_FILE to provide a secure password."; \
			echo "To override this check (NOT RECOMMENDED), use: FORCE=1 make deploy-podman ENV=prod"; \
			echo ""; \
			if [ "$(FORCE)" != "1" ]; then \
				exit 1; \
			else \
				echo "${YELLOW}WARNING: FORCE=1 specified, proceeding with insecure password...${RESET}"; \
			fi; \
		fi; \
	fi
	@if command -v helm >/dev/null 2>&1; then \
		$(MAKE) deploy-podman-helm; \
	else \
		echo "${YELLOW}Helm not found, using direct deployment...${RESET}"; \
		$(MAKE) deploy-podman-direct; \
	fi

deploy-podman-helm:
	@# Verify values file exists before proceeding
	@if [ ! -f "$(VALS)" ]; then \
		echo "${RED}✗ Values file not found: $(VALS)${RESET}"; \
		echo "Available values files:"; \
		ls -1 $(CHART_DIR)/values*.yaml 2>/dev/null || echo "  (none found)"; \
		exit 1; \
	fi
	@echo "Cleaning up existing deployment..."
	$(call cleanup_deployment)
	@echo "Rendering deployment manifest..."
	@DATA_VOL_NAME="$(VOLUME_PREFIX)-$(ENV)-data"; \
	HOST_DATA_PATH=$$($(PODMAN_CMD) volume inspect "$$DATA_VOL_NAME" --format '{{.Mountpoint}}' 2>/dev/null || echo ""); \
	if ! helm template $(RELEASE_NAME) $(CHART_DIR) -f "$(VALS)" \
		--set environment=$(ENV) \
		--set image.repository=$(RELEASE_NAME) \
		--set image.tag=$(IMAGE_TAG) \
		--set image.pullPolicy=Never \
		$${HOST_DATA_PATH:+--set backend.hostDataPath=$$HOST_DATA_PATH} > render.yaml; then \
		echo "${RED}✗ Helm template generation failed${RESET}"; \
		echo "Check your values file: $(VALS)"; \
		exit 1; \
	fi
	@echo "Deploying application pod..."
	@# Ensure pause image exists (Podman uses it automatically for pod infra)
	@./scripts/ensure-pause-image.sh
	@$(PODMAN_CMD) play kube --userns=keep-id render.yaml
	@echo ""
	@echo "${GREEN}✓ Deployment complete!${RESET}"
	@echo "  Environment: ${BOLD}$(ENV)${RESET}"
	@echo "  Values: $(VALS)"
	@echo "  Migrations: Executed automatically during startup"
	@echo "  Access: ${BLUE}http://localhost:$(APP_PORT)/starexec${RESET}"
	@echo ""
	@echo "${BOLD}Useful commands:${RESET}"
	@echo "  make db-shell               	- PostgreSQL shell"
	@echo "  make volumes-backup ENV=$(ENV)	- Backup volumes"
	@echo "  podman logs $(APP_CONTAINER)	- Application logs"
	@echo "  podman logs $(DB_CONTAINER)	- Database logs"

deploy-podman-direct:
	@echo "Cleaning up existing deployment..."
	$(call cleanup_deployment)
	@echo "Ensuring Podman infra image exists..."
	@./scripts/ensure-pause-image.sh
	@echo "Generating deployment manifest from template..."
	@STAREXEC_DATA_VOL=$${STAREXEC_DATA_VOL:-starexec-$(ENV)-data} \
	 STAREXEC_POSTGRES_VOL=$${STAREXEC_POSTGRES_VOL:-starexec-$(ENV)-postgres} \
	 IMAGE_NAME=$(RELEASE_NAME) \
	 IMAGE_TAG=$(IMAGE_TAG) \
	 ./scripts/generate-render-yaml.sh
	@echo "Deploying application pod..."
	@# Ensure pause image exists (Podman uses it automatically for pod infra)
	@./scripts/ensure-pause-image.sh
	@$(PODMAN_CMD) play kube --userns=keep-id render.yaml
	@echo ""
	@echo "${GREEN}✓ Deployment complete!${RESET}"
	@echo "  Environment: ${BOLD}$(ENV)${RESET}"
	@echo "  Access: ${BLUE}http://localhost:$(APP_PORT)/starexec${RESET}"
	@echo "  Migrations: Executed automatically during startup"
	@echo ""
	@echo "${BOLD}Useful commands:${RESET}"
	@echo "  make db-shell                	- PostgreSQL shell"
	@echo "  make volumes-backup ENV=$(ENV)	- Backup volumes"
	@echo "  podman logs $(APP_CONTAINER)	- Application logs"
	@echo "  podman logs $(DB_CONTAINER)	- Database logs"

deploy-podman-cached: image
	@if [ ! -f render.yaml ]; then \
		echo "${YELLOW}WARNING: render.yaml not found! Running 'make template' to generate...${RESET}"; \
		$(MAKE) template; \
	fi
	@echo "Deploying using cached render.yaml..."
	@echo "Cleaning up existing deployment..."
	$(call cleanup_deployment)
	@$(PODMAN_CMD) play kube --userns=keep-id render.yaml
	@echo ""
	@echo "${GREEN}✓ Deployment complete (using cached manifest)!${RESET}"
	@echo "  Access: ${BLUE}http://localhost:$(APP_PORT)/starexec${RESET}"
	@echo ""
	@echo "Note: To regenerate manifest, run '${BLUE}make template${RESET}' or '${BLUE}make deploy-podman${RESET}'"

undeploy-podman:
	@echo "Removing Podman deployment (volumes preserved)"
	$(call cleanup_deployment)
	@echo "Waiting for resources to be fully released..."
	@sleep 2
	@echo ""
	@echo "${GREEN}✓ Cleanup complete (volumes preserved)${RESET}"
	@echo "Note: Use '${BLUE}make volumes-delete ENV=$(ENV)${RESET}' to remove data"

# ============================================================================
# KUBERNETES DEPLOYMENT
# ============================================================================

deploy-k8s:
	@echo "Deploying to Kubernetes with environment: $(ENV)"
	helm upgrade --install $(RELEASE_NAME) $(CHART_DIR) \
		-f $(VALS) \
		--namespace starexec \
		--create-namespace \
		--wait \
		--timeout 5m
	@echo ""
	@echo "${GREEN}✓ Kubernetes deployment complete!${RESET}"
	@kubectl get pods -n starexec

undeploy-k8s:
	helm uninstall $(RELEASE_NAME) --namespace starexec || true

# ============================================================================
# MAINTENANCE AND CLEANUP
# ============================================================================

clean-podman:
	@echo "Cleaning Podman artifacts (preserving volumes and cache)"
	$(call cleanup_deployment)
	@echo "Removing StarExec images..."
	@$(PODMAN_CMD) rmi $(RELEASE_NAME):$(IMAGE_TAG) 2>/dev/null || true
	@echo "${GREEN}✓ Cleanup complete (volumes and cache preserved)${RESET}"

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
			echo "Cancelled"; \
			exit 0; \
		fi; \
	else \
		echo "${RED}Running non-interactively without FORCE=1. Aborting.${RESET}"; \
		echo "Set FORCE=1 to skip confirmation: make clean-cache FORCE=1"; \
		exit 1; \
	fi
	@echo "Pruning system..."
	@podman system prune -a -f || { echo "${RED}✗ Error during system prune${RESET}"; exit 1; }
	@echo "Pruning builder cache..."
	@podman builder prune -a -f 2>/dev/null || true
	@echo "${GREEN}✓ Build cache cleared successfully${RESET}"

clean-all: clean-podman volumes-delete
	@echo "${GREEN}✓ Full cleanup complete (pods, images, volumes removed; cache preserved)${RESET}"

clean-hard:
	@if [ "$(DRY_RUN)" = "1" ]; then \
		echo "[DRY RUN] Would perform HARD RESET for ENV=$(ENV):"; \
		echo "  - Remove pods: starexec, starexec-pod, $(POD_NAME)"; \
		echo "  - Remove containers: $(APP_CONTAINER), $(DB_CONTAINER)"; \
		echo "  - Remove secrets: $(RELEASE_NAME)-$(SECRET_NAME)-*"; \
		echo "  - Remove volumes: $(VOLUME_PREFIX)-$(ENV)-data, $(VOLUME_PREFIX)-$(ENV)-sandbox, $(VOLUME_PREFIX)-$(ENV)-backend, $(VOLUME_PREFIX)-$(ENV)-work, $(VOLUME_PREFIX)-$(ENV)-postgres"; \
		echo "  - Remove image: $(RELEASE_NAME):$(IMAGE_TAG)"; \
		exit 0; \
	fi
	@echo "${YELLOW}⚠️  HARD RESET: Removes ALL StarExec resources for ENV=$(ENV)${RESET}"
	@echo "${RED}This will delete:${RESET}"
	@echo "  - Pods and containers"
	@echo "  - Secrets"
	@echo "  - ${RED}Volumes (ALL DATA will be lost)${RESET}"
	@echo "  - Images"
	@echo ""
	@if [ "$(FORCE)" != "1" ]; then \
		printf "Type '$(ENV)' to confirm: "; \
		read ans; \
		if [ "$$ans" != "$(ENV)" ]; then \
			echo "${YELLOW}Cancelled${RESET}"; \
			exit 0; \
		fi; \
	else \
		echo "${YELLOW}FORCE=1 detected, skipping confirmation${RESET}"; \
	fi
	$(call cleanup_deployment)
	@sleep 3  # Allow network cleanup to complete
	@echo "Removing StarExec volumes..."
	@$(MAKE) volumes-delete ENV=$(ENV) FORCE=1
	@echo "Removing StarExec image..."
	@$(PODMAN_CMD) rmi $(RELEASE_NAME):$(IMAGE_TAG) 2>/dev/null || true
	@echo "${GREEN}✓ Hard reset complete for ENV=$(ENV)${RESET}"

# ============================================================================
# CONVENIENT CLEANUP ALIASES
# ============================================================================

reset: stop clean-hard
	@echo ""
	@echo "${GREEN}✓✓✓ Complete reset finished ✓✓✓${RESET}"
	@echo "Environment ${BOLD}$(ENV)${RESET} has been completely cleaned:"
	@echo "  - All containers stopped and removed"
	@echo "  - All volumes deleted"
	@echo "  - All images removed"
	@echo ""
	@echo "To redeploy: ${BLUE}make deploy-podman ENV=$(ENV)${RESET}"

nuke: reset
	@echo "${GREEN}Environment $(ENV) nuked successfully${RESET}"

status:
	@echo "${BOLD}${BLUE}══════════════════════════════════════════${RESET}"
	@echo "${BOLD}${BLUE}  StarExec Status (ENV=$(ENV))${RESET}"
	@echo "${BOLD}${BLUE}══════════════════════════════════════════${RESET}"
	@echo ""
	@printf "%-20s: " "Deployment State"
	@if $(PODMAN_CMD) pod exists $(POD_NAME) 2>/dev/null || $(PODMAN_CMD) pod exists starexec 2>/dev/null; then \
		echo "${GREEN}RUNNING${RESET}"; \
	else \
		echo "${RED}STOPPED${RESET}"; \
	fi
	@printf "%-20s: %s\n" "Environment" "$(ENV)"
	@printf "%-20s: %s\n" "Image" "$(RELEASE_NAME):$(IMAGE_TAG)"
	@printf "%-20s: " "Data Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-data 2>/dev/null; then \
		echo "${GREEN}✓ exists${RESET}"; \
	else \
		echo "${RED}✗ missing${RESET}"; \
	fi
	@printf "%-20s: " "Sandbox Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-sandbox 2>/dev/null; then \
		echo "${GREEN}✓ exists${RESET}"; \
	else \
		echo "${RED}✗ missing${RESET}"; \
	fi
	@printf "%-20s: " "Backend Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-backend 2>/dev/null; then \
		echo "${GREEN}✓ exists${RESET}"; \
	else \
		echo "${RED}✗ missing${RESET}"; \
	fi
	@printf "%-20s: " "Work Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-work 2>/dev/null; then \
		echo "${GREEN}✓ exists${RESET}"; \
	else \
		echo "${RED}✗ missing${RESET}"; \
	fi
	@printf "%-20s: " "Postgres Volume"
	@if $(PODMAN_CMD) volume exists $(VOLUME_PREFIX)-$(ENV)-postgres 2>/dev/null; then \
		echo "${GREEN}✓ exists${RESET}"; \
	else \
		echo "${RED}✗ missing${RESET}"; \
	fi
	@echo ""
	@echo "${BOLD}=== Containers ===${RESET}"
	@$(PODMAN_CMD) ps -a --filter name=starexec --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || echo "${YELLOW}No StarExec containers found${RESET}"
	@echo ""
	@echo "${BOLD}=== Images ===${RESET}"
	@$(PODMAN_CMD) images --filter reference=$(RELEASE_NAME) --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.Created}}" 2>/dev/null || echo "${YELLOW}No StarExec images found${RESET}"
	@echo ""
	@if $(PODMAN_CMD) pod exists $(POD_NAME) 2>/dev/null || $(PODMAN_CMD) pod exists starexec 2>/dev/null; then \
		echo "${GREEN}✓ StarExec is RUNNING${RESET}"; \
		echo "  Access: ${BLUE}http://localhost:$(APP_PORT)/starexec${RESET}"; \
	else \
		echo "${YELLOW}○ StarExec is NOT running${RESET}"; \
		echo "  Deploy with: ${BLUE}make deploy-podman ENV=$(ENV)${RESET}"; \
	fi

# ============================================================================
# DEBUGGING AND DIAGNOSTICS
# ============================================================================

# Log tail limits (adjust via LOG_LINES_APP/LOG_LINES_DB if needed)
LOG_LINES_APP?=50
LOG_LINES_DB?=30

logs:
	@echo "=== Application Logs (last $(LOG_LINES_APP) lines) ==="
	@$(PODMAN_CMD) logs --tail $(LOG_LINES_APP) $($(PODMAN_CMD) ps --filter "ancestor=$(RELEASE_NAME)" --format "{{.Names}}" | head -1) 2>&1 || echo "App container not running"
	@echo ""
	@echo "=== PostgreSQL Logs (last $(LOG_LINES_DB) lines) ==="
	@podman logs --tail $(LOG_LINES_DB) $$(podman ps --filter "ancestor=postgres" --format "{{.Names}}" | head -1) 2>&1 || echo "Postgres container not running"

logs-app:
	@echo "Following application logs (Ctrl+C to stop)..."
	@podman logs -f $$(podman ps --filter "ancestor=$(RELEASE_NAME)" --format "{{.Names}}" | head -1)

logs-postgres:
	@echo "Following PostgreSQL logs (Ctrl+C to stop)..."
	@podman logs -f $$(podman ps --filter "ancestor=postgres" --format "{{.Names}}" | head -1)

test: test-deps
	@mvn test

test-deps:
	@echo "${BOLD}Testing job execution dependencies in container...${RESET}"
	@echo ""
	@echo "${BOLD}=== Installed Packages ===${RESET}"
	@podman exec $(APP_CONTAINER) apk list --installed | grep -E "bash|util-linux|postgresql-client|procps" || true
	@echo ""
	@echo "${BOLD}=== Tool Versions ===${RESET}"
	@podman exec $(APP_CONTAINER) bash -c "echo 'bash:' && bash --version | head -1"
	@podman exec $(APP_CONTAINER) bash -c "echo 'flock:' && flock --version"
	@podman exec $(APP_CONTAINER) bash -c "echo 'lscpu:' && lscpu --version"
	@podman exec $(APP_CONTAINER) bash -c "echo 'psql:' && psql --version"
	@podman exec $(APP_CONTAINER) bash -c "echo 'ps:' && ps --version"
	@echo ""
	@echo "${BOLD}=== Command Availability ===${RESET}"
	@podman exec $(APP_CONTAINER) bash -c "which bash flock lscpu psql ps runsolver"
	@echo ""
	@echo "${BOLD}=== Test ps -p Command ===${RESET}"
	@podman exec $(APP_CONTAINER) bash -c 'ps -p $$$$ -o pid,cmd'
	@echo ""
	@echo "${BOLD}=== Test flock -w Command ===${RESET}"
	@podman exec $(APP_CONTAINER) bash -c "timeout 2 flock -x -w 1 /tmp/test.lock echo 'flock -w works!'"
	@echo ""
	@echo "${BOLD}=== CPU Info ===${RESET}"
	@podman exec $(APP_CONTAINER) lscpu | head -10
	@echo ""
	@echo "${GREEN}✓ All job execution dependencies validated${RESET}"

fix-cgroup-delegation:
	@echo "Fixing cgroup controller delegation for Podman rootless mode..."
	@./scripts/check-cgroup-delegation.sh --fix

verify-deps:
	@echo "${BOLD}Validating required CLI tooling...${RESET}"
	@echo -n "  podman: "
	@command -v podman >/dev/null && echo "${GREEN}✓${RESET}" || { echo "${RED}✗ required${RESET}"; exit 1; }
	@echo -n "  yq: "
	@command -v yq >/dev/null && yq --version >/dev/null 2>&1 && echo "${GREEN}✓${RESET}" || { echo "${RED}✗ required${RESET}"; exit 1; }
	@echo -n "  sha256sum: "
	@command -v sha256sum >/dev/null && echo "${GREEN}✓${RESET}" || { echo "${RED}✗ required${RESET}"; exit 1; }
	@echo "${BOLD}Optional tools:${RESET}"
	@echo -n "  helm: "
	@command -v helm >/dev/null && echo "${GREEN}✓${RESET}" || echo "${YELLOW}○ not installed (needed for template/deploy)${RESET}"
	@echo "${BOLD}Podman rootless configuration:${RESET}"
	@echo -n "  cgroup delegation: "
	@./scripts/check-cgroup-delegation.sh >/dev/null 2>&1 && echo "${GREEN}✓${RESET}" || echo "${YELLOW}○ needs configuration (run 'make fix-cgroup-delegation')${RESET}"
	@# Check for common rootless Podman issue (missing /run/user/UID)
	@if [ -z "$$XDG_RUNTIME_DIR" ] && [ ! -w "/run/user/$$(id -u)" ]; then \
		echo ""; \
		echo "${RED}✗ Error: XDG_RUNTIME_DIR not set and /run/user/$$(id -u) is not writable.${RESET}"; \
		echo "  This is common on lab/shared machines without full PAM integration."; \
		echo "  Podman cannot start without a place to store runtime data."; \
		echo ""; \
		echo "  ${BOLD}Solution:${RESET} Run this before make:"; \
		echo "    ${BLUE}export XDG_RUNTIME_DIR=/tmp/run-$$(id -u) && mkdir -p \$$XDG_RUNTIME_DIR${RESET}"; \
		echo ""; \
		exit 1; \
	fi
	@echo "${GREEN}✓ All required dependencies satisfied${RESET}"

lint:
	@if command -v helm >/dev/null 2>&1; then \
		echo "Linting Helm chart..."; \
		if [ -f "$(CHART_DIR)/values-dev.yaml" ]; then \
			echo "  Using values-dev.yaml for basic validation..."; \
			helm lint $(CHART_DIR) -f $(CHART_DIR)/values-dev.yaml; \
		else \
			echo "  Using default values.yaml (may show password warnings)..."; \
			helm lint $(CHART_DIR) || echo "  ⚠️  Lint failed - this is expected if passwords not set in defaults"; \
		fi; \
		echo "Validating all value files..."; \
		for f in $(CHART_DIR)/values*.yaml; do \
			if [ "$$f" != "$(CHART_DIR)/values.yaml" ]; then \
				echo "  Checking $$f..."; \
				helm template $(CHART_DIR) -f $$f > /dev/null && echo "    ✓ Valid" || echo "    ✗ Invalid"; \
			fi; \
		done; \
	else \
		echo "Helm not installed, skipping chart validation"; \
	fi

template:
	@if command -v helm >/dev/null 2>&1; then \
		echo "Rendering templates with environment: $(ENV)"; \
		helm template $(RELEASE_NAME) $(CHART_DIR) -f $(VALS) > render.yaml; \
		echo "Output written to: render.yaml"; \
	else \
		echo "Helm not installed, generating from template..."; \
		STAREXEC_DATA_VOL=$${STAREXEC_DATA_VOL:-starexec-$(ENV)-data} \
		STAREXEC_SANDBOX_VOL=$${STAREXEC_SANDBOX_VOL:-starexec-$(ENV)-sandbox} \
		STAREXEC_BACKEND_VOL=$${STAREXEC_BACKEND_VOL:-starexec-$(ENV)-backend} \
		STAREXEC_WORK_VOL=$${STAREXEC_WORK_VOL:-starexec-$(ENV)-work} \
		STAREXEC_POSTGRES_VOL=$${STAREXEC_POSTGRES_VOL:-starexec-$(ENV)-postgres} \
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
		grep -A 5 "STAREXEC_DB" render.yaml | head -20; \
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
