# Makefile - StarExec Professional DevOps Build System
# Supports: Podman (local dev), Kubernetes (prod), multiple environments
# Single source of truth: Helm charts with environment-specific values

IMAGE_NAME=localhost/local/starexec
IMAGE_TAG=dev
CHART_DIR=./chart
RELEASE_NAME?=starexec
HELM_VALUES?=values.yaml
SECRET_NAME=secret-mysql

# Environment selection (dev, ci, prod)
ENV?=dev
ENV_VALUES=$(CHART_DIR)/values-$(ENV).yaml

# Volume management
VOLUME_SCRIPT=./scripts/podman-volumes.sh
VOLUME_PREFIX=starexec

# Values file selection: prefer values-$(ENV).yaml, fallback to values.yaml
VALS := $(if $(wildcard $(ENV_VALUES)),$(ENV_VALUES),$(CHART_DIR)/values.yaml)

.PHONY: help build deploy-podman undeploy-podman deploy-k8s undeploy-k8s

# Display help information
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
	@echo ""
	@echo "Deployment Targets:"
	@echo "  deploy-podman          Deploy to Podman (local dev)"
	@echo "  deploy-k8s             Deploy to Kubernetes (prod/staging)"
	@echo "  undeploy-podman        Remove Podman deployment"
	@echo "  undeploy-k8s           Remove Kubernetes deployment"
	@echo ""
	@echo "Volume Management (Podman):"
	@echo "  volumes-create         Create named volumes for ENV"
	@echo "  volumes-list           List all volumes"
	@echo "  volumes-backup         Backup all volumes for ENV"
	@echo "  volumes-restore        Restore volumes from backup"
	@echo "  volumes-export         Export volume for sharing"
	@echo "  volumes-delete         Delete volumes for ENV"
	@echo "  volumes-help           Show detailed volume management help"
	@echo ""
	@echo "Database Management:"
	@echo "  migrate-podman         Run Flyway migrations against Podman DB"
	@echo "  migrate-repair         Repair Flyway schema history"
	@echo "  db-shell               Open MySQL shell (Podman)"
	@echo "  db-dump                Create MySQL logical dump"
	@echo ""
	@echo "Maintenance Targets:"
	@echo "  clean-podman           Clean Podman artifacts (keeps volumes)"
	@echo "  clean-cache            Clear all Podman build cache"
	@echo "  clean-all              Clean everything including volumes"
	@echo "  lint                   Lint Helm charts"
	@echo "  template               Render Helm templates"
	@echo ""
	@echo "Environments:"
	@echo "  ENV=dev               Development (default, named volumes)"
	@echo "  ENV=ci                CI/testing (ephemeral)"
	@echo "  ENV=prod              Production (Kubernetes PVCs)"
	@echo ""
	@echo "Examples:"
	@echo "  make deploy-podman ENV=dev"
	@echo "  make volumes-backup ENV=dev"
	@echo "  make deploy-k8s ENV=prod"

build:
	@echo "Building image: $(IMAGE_NAME):$(IMAGE_TAG)"
	podman build -t $(IMAGE_NAME):$(IMAGE_TAG) .

build-fresh:
	@echo "Building fresh image (no cache): $(IMAGE_NAME):$(IMAGE_TAG)"
	podman build --no-cache -t $(IMAGE_NAME):$(IMAGE_TAG) .

build-prod:
	@echo "Building production image"
	@IMAGE_REGISTRY=$${IMAGE_REGISTRY:-ghcr.io/andrescdo}; \
	IMAGE_VERSION=$${IMAGE_VERSION:-1.0.0}; \
	podman build -t $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION -t $$IMAGE_REGISTRY/starexec:latest .
	@echo "Push with: podman push $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION"

# ============================================================================
# VOLUME MANAGEMENT (Podman Named Volumes - RECOMMENDED APPROACH)
# ============================================================================

volumes-create:
	@echo "Creating volumes for environment: $(ENV)"
	$(VOLUME_SCRIPT) create $(ENV)

volumes-list:
	$(VOLUME_SCRIPT) list

volumes-backup:
	@echo "Backing up volumes for environment: $(ENV)"
	$(VOLUME_SCRIPT) backup-all $(ENV)

volumes-restore:
	@echo "Restore requires timestamp. Available backups:"
	@ls -1 backups/$(VOLUME_PREFIX)-$(ENV)-full-*.tar.gz 2>/dev/null | sed 's/.*full-//' | sed 's/-.*//' | sort -u || echo "(none)"
	@read -p "Enter timestamp (YYYYMMDD-HHMMSS): " ts && \
	$(VOLUME_SCRIPT) restore-all $(ENV) $$ts

volumes-export:
	@read -p "Volume name: " vol && \
	$(VOLUME_SCRIPT) export $$vol

volumes-delete:
	$(VOLUME_SCRIPT) delete $(ENV)

volumes-help:
	$(VOLUME_SCRIPT) help

# ============================================================================
# DATABASE MANAGEMENT
# ============================================================================

db-shell:
	@echo "Opening MySQL shell (container must be running)"
	@DB_PASS=$${STAREXEC_DB_PASSWORD:-starexec_dev_password}; \
	podman exec -it starexec-mysql mysql -uroot -p$$DB_PASS starexec

db-dump:
	$(VOLUME_SCRIPT) dump-mysql $(ENV)

db-migrate:
	@echo "Running Flyway migrations (this may take 30-60 seconds)..."
	@DB_PASS=$${STAREXEC_DB_PASSWORD:-starexec_dev_password}; \
	mvn -q -DskipTests \
		-Dflyway.url=jdbc:mysql://localhost:3306/starexec \
		-Dflyway.user=root \
		-Dflyway.password=$$DB_PASS \
		flyway:migrate
	@echo "✓ Migrations completed successfully"

db-status:
	@echo "Checking Flyway migration status..."
	@DB_PASS=$${STAREXEC_DB_PASSWORD:-starexec_dev_password}; \
	mvn -q -DskipTests \
		-Dflyway.url=jdbc:mysql://localhost:3306/starexec \
		-Dflyway.user=root \
		-Dflyway.password=$$DB_PASS \
		flyway:info

migrate-repair:
	@echo "Running Flyway repair..."
	@ echo "Using values file: $(VALS)"; \
	  mysql_host=$${MYSQL_HOST:-localhost}; \
	  : $${STAREXEC_DB_USER:=root}; : $${STAREXEC_DB_DATABASE:=starexec}; \
	  [ -n "$$STAREXEC_DB_PASSWORD" ] || { echo "Error: Database password required. Set the STAREXEC_DB_PASSWORD environment variable." >&2; exit 1; }; \
	  echo "Running Flyway repair against $$mysql_host:3306/$$STAREXEC_DB_DATABASE"; \
	  mvn clean flyway:repair -e \
		-Dflyway.url=jdbc:mysql://$$mysql_host:3306/$$STAREXEC_DB_DATABASE \
		-Dflyway.user=$$STAREXEC_DB_USER \
		-Dflyway.password=$$STAREXEC_DB_PASSWORD \
		-Dflyway.schemas=$$STAREXEC_DB_DATABASE

# Run Flyway migration in podman (simplified)
migrate-podman: migrate-repair
	@echo "Running Flyway migration against Podman MySQL"
	@echo "Using values file: $(VALS)"; \
	  mysql_host=$${MYSQL_HOST:-localhost}; \
	  : $${STAREXEC_DB_USER:=root}; : $${STAREXEC_DB_DATABASE:=starexec}; \
	  [ -n "$$STAREXEC_DB_PASSWORD" ] || { echo "Error: Set STAREXEC_DB_PASSWORD" >&2; exit 1; }; \
	  echo "Running Flyway migration against $$mysql_host:3306/$$STAREXEC_DB_DATABASE"; \
	  mvn clean flyway:migrate -e \
		-Dflyway.url=jdbc:mysql://$$mysql_host:3306/$$STAREXEC_DB_DATABASE \
		-Dflyway.user=$$STAREXEC_DB_USER \
		-Dflyway.password=$$STAREXEC_DB_PASSWORD \
		-Dflyway.schemas=$$STAREXEC_DB_DATABASE

# ============================================================================
# PODMAN DEPLOYMENT
# ============================================================================

deploy-podman: build volumes-create
	@echo "Deploying to Podman with environment: $(ENV)"
	@echo "Using values file: $(VALS)"
	@# Validate DB password
	@mysql_pass=$$(yq e '.mysql.password // ""' $(VALS) 2>/dev/null || echo ""); \
	if [ -z "$$mysql_pass" ] && [ -z "$$STAREXEC_DB_PASSWORD" ]; then \
		echo "ERROR: Set STAREXEC_DB_PASSWORD or define .mysql.password in $(VALS)"; exit 1; \
	fi
	@# Clean up old pods and secrets
	@echo "Cleaning up existing deployment..."
	@for pod in starexec starexec-pod $(RELEASE_NAME)-pod; do \
		if podman pod exists $$pod 2>/dev/null; then \
			echo "  Removing existing pod: $$pod"; \
			podman pod rm -f $$pod 2>/dev/null; \
		fi \
	done
	@for key in user password database rootPassword; do \
		podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-$$key 2>/dev/null || true; \
	done
	@# Render and create secrets
	@echo "Rendering secrets..."
	@helm template $(RELEASE_NAME) $(CHART_DIR) --show-only templates/$(SECRET_NAME).yaml -f "$(VALS)" > secret-render.yaml
	@for key in user password database rootPassword; do \
		b64=$$(grep "$$key:" secret-render.yaml | sed 's/.*: //' | sed 's/^"//' | sed 's/"$$//'); \
		[ -z "$$b64" ] && echo "ERROR: No base64 data for $$key" && cat secret-render.yaml && exit 1; \
		echo "$$b64" | base64 --decode | podman secret create $(RELEASE_NAME)-$(SECRET_NAME)-$$key -; \
	done
	@# Deploy application (skip PVC rendering for Podman - not applicable)
	@echo "Deploying application pod..."
	@helm template $(RELEASE_NAME) $(CHART_DIR) -f "$(VALS)" > render.yaml
	@podman play kube render.yaml
	@echo ""
	@echo "✓ Deployment complete!"
	@echo "  Environment: $(ENV)"
	@echo "  Values: $(VALS)"
	@echo "  Access: http://localhost:8080/starexec"
	@echo ""
	@echo "Useful commands:"
	@echo "  make db-shell              - MySQL shell"
	@echo "  make volumes-backup ENV=$(ENV) - Backup volumes"
	@echo "  podman logs starexec-app   - Application logs"
	@echo "  podman logs starexec-mysql - Database logs"
undeploy-podman:
	@echo "Removing Podman deployment (volumes preserved)"
	@# Stop and remove all pods (try both possible names)
	@for pod in starexec starexec-pod $(RELEASE_NAME)-pod; do \
		if podman pod exists $$pod 2>/dev/null; then \
			echo "Removing pod: $$pod"; \
			podman pod rm -f $$pod; \
		fi \
	done
	@# Remove secrets
	@for key in user password database rootPassword; do \
		podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-$$key 2>/dev/null || true; \
	done
	@echo ""
	@echo "✓ Cleanup complete (volumes preserved)"
	@echo "Note: Use 'make volumes-delete ENV=$(ENV)' to remove data"

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
	@echo "✓ Kubernetes deployment complete!"
	@kubectl get pods -n starexec

undeploy-k8s:
	helm uninstall $(RELEASE_NAME) --namespace starexec || true

# ============================================================================
# MAINTENANCE AND CLEANUP
# ============================================================================

clean-podman:
	@echo "Cleaning Podman artifacts (preserving volumes)"
	-podman pod rm -f starexec starexec-pod 2>/dev/null || true
	-podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-user 2>/dev/null || true
	-podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-password 2>/dev/null || true
	-podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-database 2>/dev/null || true
	-podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-rootPassword 2>/dev/null || true
	@echo "Removing StarExec images..."
	@podman rmi $(IMAGE_NAME):$(IMAGE_TAG) 2>/dev/null || true
	@echo "✓ Cleanup complete (volumes preserved)"

clean-cache:
	@echo "⚠️  WARNING: This will remove ALL Podman build cache"
	@echo "This affects all projects, not just StarExec"
	@read -p "Continue? (y/N): " ans; \
	[ "$$ans" = "y" ] && podman system prune -a -f || echo "Cancelled"
	@echo "✓ Build cache cleared"

clean-all: clean-podman
	@echo "WARNING: This will also delete volumes AND build cache!"
	@read -p "Delete all volumes for ENV=$(ENV)? (y/N): " ans; \
	[ "$$ans" = "y" ] && $(VOLUME_SCRIPT) delete $(ENV) || echo "Volumes preserved"
	@read -p "Delete build cache? (y/N): " ans; \
	[ "$$ans" = "y" ] && podman system prune -a -f || echo "Build cache preserved"

lint:
	@echo "Linting Helm chart..."
	helm lint $(CHART_DIR)
	@echo "Validating all value files..."
	@for f in $(CHART_DIR)/values*.yaml; do \
		echo "  Checking $$f..."; \
		helm template $(CHART_DIR) -f $$f > /dev/null && echo "    ✓ Valid" || echo "    ✗ Invalid"; \
	done

template:
	@echo "Rendering templates with environment: $(ENV)"
	helm template $(RELEASE_NAME) $(CHART_DIR) -f $(VALS) > render.yaml
	@echo "Output written to: render.yaml"

# ============================================================================
# DEPRECATED TARGETS (kept for backward compatibility)
# ============================================================================

# Legacy hostPath purge targets (not needed with named volumes)
purge-app-data purge-mysql-data purge-all-host-data gen-ephemeral-values clean-podman-aggressive:
	@echo "⚠️  DEPRECATED: This target is for legacy hostPath mode only"
	@echo "   Modern approach: Use 'make volumes-*' commands for named volume management"
	@echo "   See: make volumes-help"