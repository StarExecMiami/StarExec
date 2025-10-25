# StarExec DevOps Build System

IMAGE_NAME=localhost/local/starexec
IMAGE_TAG=dev
CHART_DIR=./chart
RELEASE_NAME?=starexec
HELM_VALUES?=values.yaml
SECRET_NAME=secret-mysql
STAREXEC_DB_PASSWORD?=admin
ENV?=dev
ENV_VALUES=$(CHART_DIR)/values-$(ENV).yaml
VOLUME_SCRIPT=./scripts/podman-volumes.sh
VOLUME_PREFIX=starexec
VALS := $(if $(wildcard $(ENV_VALUES)),$(ENV_VALUES),$(CHART_DIR)/values.yaml)

.PHONY: help build build-fresh build-prod \
	deploy-podman deploy-podman-helm deploy-podman-direct deploy-podman-cached undeploy-podman \
	deploy-k8s undeploy-k8s \
	volumes-create volumes-list volumes-backup volumes-restore volumes-export volumes-delete volumes-help \
	db-shell db-dump db-migrate db-status migrate-repair migrate-podman \
	clean-podman clean-cache clean-all lint template \
	start stop

start: deploy-podman

stop: undeploy-podman

deploy-podman-cached:
	@if [ ! -f render.yaml ]; then \
		echo "Error: render.yaml not found. Run 'make template' first or use 'make deploy-podman'"; \
		exit 1; \
	fi
	@echo "Deploying using cached render.yaml..."
	@echo "Cleaning up existing deployment..."
	@for pod in starexec starexec-pod $(RELEASE_NAME)-pod; do \
		if podman pod exists $$pod 2>/dev/null; then \
			echo "  Removing existing pod: $$pod"; \
			podman pod rm -f $$pod 2>/dev/null || true; \
		fi \
	done
	@for container in starexec-app starexec-mysql; do \
		if podman container exists $$container 2>/dev/null; then \
			echo "  Removing orphaned container: $$container"; \
			podman rm -f $$container 2>/dev/null || true; \
		fi \
	done
	@podman play kube render.yaml
	@echo ""
	@echo "✓ Deployment complete (using cached manifest)!"
	@echo "  Access: http://localhost:7827/starexec"
	@echo ""
	@echo "Note: To regenerate manifest, run 'make template' or 'make deploy-podman'"

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
	@echo "  deploy-podman          Deploy to Podman (build + render + apply)"
	@echo "  deploy-podman-cached   Fast deploy using existing render.yaml (no rebuild)"
	@echo "  deploy-k8s             Deploy to Kubernetes (requires Helm)"
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
	@echo "  db-migrate             Run Flyway migrations (direct)"
	@echo "  db-status              Check Flyway migration status"
	@echo ""
	@echo "Maintenance Targets:"
	@echo "  clean-podman           Clean Podman artifacts (keeps volumes)"
	@echo "  clean-cache            Clear all Podman build cache"
	@echo "  clean-all              Clean everything including volumes"
	@echo "  lint                   Lint Helm charts (if Helm available)"
	@echo "  template               Render Helm templates (if Helm available)"
	@echo ""
	@echo "Quick Start Aliases:"
	@echo "  start                  Alias for deploy-podman"
	@echo "  stop                   Alias for undeploy-podman"
	@echo ""
	@echo "Environments:"
	@echo "  ENV=dev               Development (default, named volumes)"
	@echo "  ENV=ci                CI/testing (ephemeral)"
	@echo "  ENV=prod              Production (Kubernetes PVCs)"
	@echo ""
	@echo "Examples:"
	@echo "  make deploy-podman ENV=dev"
	@echo "  make deploy-podman-cached"
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

migrate-podman:
	@echo "Running Flyway migration against Podman MySQL"
	@echo "Waiting for MySQL to be ready..."
	@DB_PASS=$${STAREXEC_DB_PASSWORD:-starexec_dev_password}; \
	DB_USER=$${STAREXEC_DB_USER:-root}; \
	DB_NAME=$${STAREXEC_DB_DATABASE:-starexec}; \
	MYSQL_HOST=$${MYSQL_HOST:-localhost}; \
	for i in 1 2 3 4 5; do \
		if podman exec starexec-mysql mysqladmin ping -h localhost -u$$DB_USER -p$$DB_PASS 2>/dev/null; then \
			echo "MySQL is ready"; \
			break; \
		fi; \
		echo "Waiting for MySQL... ($$i/5)"; \
		sleep 2; \
	done; \
	echo "Running Flyway migration against $$MYSQL_HOST:3306/$$DB_NAME"; \
	mvn clean flyway:migrate -e \
		-Dflyway.url=jdbc:mysql://$$MYSQL_HOST:3306/$$DB_NAME \
		-Dflyway.user=$$DB_USER \
		-Dflyway.password=$$DB_PASS \
		-Dflyway.schemas=$$DB_NAME

# ============================================================================
# PODMAN DEPLOYMENT
# ============================================================================

deploy-podman: build volumes-create
	@echo "Deploying to Podman with environment: $(ENV)"
	@echo "Using values file: $(VALS)"
	@if command -v helm >/dev/null 2>&1; then \
		$(MAKE) deploy-podman-helm; \
	else \
		echo "Helm not found, using direct deployment..."; \
		$(MAKE) deploy-podman-direct; \
	fi

deploy-podman-helm:
	@echo "Cleaning up existing deployment..."
	@for pod in starexec starexec-pod $(RELEASE_NAME)-pod; do \
		if podman pod exists $$pod 2>/dev/null; then \
			echo "  Removing existing pod: $$pod"; \
			podman pod rm -f $$pod 2>/dev/null || true; \
		fi \
	done
	@for container in starexec-app starexec-mysql; do \
		if podman container exists $$container 2>/dev/null; then \
			echo "  Removing orphaned container: $$container"; \
			podman rm -f $$container 2>/dev/null || true; \
		fi \
	done
	@for key in user password database rootPassword; do \
		podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-$$key 2>/dev/null || true; \
	done
	@echo "Rendering secrets..."
	@helm template $(RELEASE_NAME) $(CHART_DIR) --show-only templates/$(SECRET_NAME).yaml -f "$(VALS)" > secret-render.yaml
	@for key in user password database rootPassword; do \
		b64=$$(grep "$$key:" secret-render.yaml | sed 's/.*: //' | sed 's/^"//' | sed 's/"$$//'); \
		[ -z "$$b64" ] && echo "ERROR: No base64 data for $$key" && cat secret-render.yaml && exit 1; \
		echo "$$b64" | base64 --decode | podman secret create $(RELEASE_NAME)-$(SECRET_NAME)-$$key -; \
	done
	@echo "Deploying application pod..."
	@helm template $(RELEASE_NAME) $(CHART_DIR) -f "$(VALS)" > render.yaml
	@podman play kube render.yaml
	@echo ""
	@echo "✓ Deployment complete!"
	@echo "  Environment: $(ENV)"
	@echo "  Values: $(VALS)"
	@echo "  Access: http://localhost:7827/starexec"
	@echo ""
	@echo "Useful commands:"
	@echo "  make db-shell              - MySQL shell"
	@echo "  make volumes-backup ENV=$(ENV) - Backup volumes"
	@echo "  podman logs starexec-app   - Application logs"
	@echo "  podman logs starexec-mysql - Database logs"

deploy-podman-direct:
	@echo "Cleaning up existing deployment..."
	@for pod in starexec starexec-pod $(RELEASE_NAME)-pod; do \
		if podman pod exists $$pod 2>/dev/null; then \
			echo "  Removing existing pod: $$pod"; \
			podman pod rm -f $$pod 2>/dev/null || true; \
		fi \
	done
	@for container in starexec-app starexec-mysql; do \
		if podman container exists $$container 2>/dev/null; then \
			echo "  Removing orphaned container: $$container"; \
			podman rm -f $$container 2>/dev/null || true; \
		fi \
	done
	@echo "Generating deployment manifest from template..."
	@STAREXEC_DATA_VOL=$${STAREXEC_DATA_VOL:-starexec-$(ENV)-data} \
	 STAREXEC_MYSQL_VOL=$${STAREXEC_MYSQL_VOL:-starexec-$(ENV)-mysql} \
	 IMAGE_NAME=$(IMAGE_NAME) \
	 IMAGE_TAG=$(IMAGE_TAG) \
	 ./scripts/generate-render-yaml.sh
	@echo "Deploying application pod..."
	@podman play kube render.yaml
	@echo ""
	@echo "✓ Deployment complete!"
	@echo "  Environment: $(ENV)"
	@echo "  Access: http://localhost:7827/starexec"
	@echo ""
	@echo "Useful commands:"
	@echo "  make db-shell              - MySQL shell"
	@echo "  make volumes-backup ENV=$(ENV) - Backup volumes"
	@echo "  podman logs starexec-app   - Application logs"
	@echo "  podman logs starexec-mysql - Database logs"

undeploy-podman:
	@echo "Removing Podman deployment (volumes preserved)"
	@for pod in starexec starexec-pod $(RELEASE_NAME)-pod; do \
		if podman pod exists $$pod 2>/dev/null; then \
			echo "Removing pod: $$pod"; \
			podman pod rm -f $$pod 2>/dev/null || true; \
		fi \
	done
	@for container in starexec-app starexec-mysql; do \
		if podman container exists $$container 2>/dev/null; then \
			echo "Removing orphaned container: $$container"; \
			podman rm -f $$container 2>/dev/null || true; \
		fi \
	done
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
	@if command -v helm >/dev/null 2>&1; then \
		echo "Linting Helm chart..."; \
		helm lint $(CHART_DIR); \
		echo "Validating all value files..."; \
		for f in $(CHART_DIR)/values*.yaml; do \
			echo "  Checking $$f..."; \
			helm template $(CHART_DIR) -f $$f > /dev/null && echo "    ✓ Valid" || echo "    ✗ Invalid"; \
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
		STAREXEC_MYSQL_VOL=$${STAREXEC_MYSQL_VOL:-starexec-$(ENV)-mysql} \
		IMAGE_NAME=$(IMAGE_NAME) \
		IMAGE_TAG=$(IMAGE_TAG) \
		./scripts/generate-render-yaml.sh; \
	fi