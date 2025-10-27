# StarExec DevOps Build System

IMAGE_REGISTRY?=ghcr.io/andrescdo
IMAGE_NAME?=$(IMAGE_REGISTRY)/starexec
IMAGE_TAG?=latest
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

# Network configuration
PODMAN_NETWORK?=pasta
PODMAN_REQUIRES_SUDO=$(shell podman system info 2>/dev/null | grep -q 'rootless.*true' && echo no || echo yes)

.PHONY: help build build-fresh build-prod image \
	deploy-podman deploy-podman-helm deploy-podman-direct network-setup deploy-podman-cached undeploy-podman \
	deploy-k8s undeploy-k8s \
	volumes-create volumes-list volumes-backup volumes-restore volumes-export volumes-delete volumes-help \
	db-shell db-dump db-migrate db-status migrate-repair migrate-podman \
	clean-podman clean-cache clean-all clean-hard lint template \
	logs logs-app logs-mysql test-deps \
	start stop

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
	@echo "  image                  Ensure image exists (pull from GHCR if needed)"
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
	@echo "  clean-cache            Clear Podman build cache (prune + builder prune)"
	@echo "  clean-all              Clean everything including volumes AND cache"
	@echo "  clean-hard             ⚠️  HARD RESET: Remove ALL Podman storage (aggressive)"
	@echo "  lint                   Lint Helm charts (if Helm available)"
	@echo "  template               Render Helm templates (if Helm available)"
	@echo ""
	@echo "Debugging Targets:"
	@echo "  logs                   Show all container logs"
	@echo "  logs-app               Show application logs (follow mode)"
	@echo "  logs-mysql             Show MySQL logs (follow mode)"
	@echo "  test-deps              Test job execution dependencies in container"
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
	@echo "Building image: $(RELEASE_NAME):$(IMAGE_TAG)"
	podman build -t $(RELEASE_NAME):$(IMAGE_TAG) .

build-fresh:
	@echo "Building fresh image (no cache): $(RELEASE_NAME):$(IMAGE_TAG)"
	podman build --no-cache -t $(RELEASE_NAME):$(IMAGE_TAG) .

build-prod:
	@echo "Building production image"
	@IMAGE_REGISTRY=$${IMAGE_REGISTRY:-ghcr.io/andrescdo}; \
	IMAGE_VERSION=$${IMAGE_VERSION:-1.0.0}; \
	podman build -t $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION -t $$IMAGE_REGISTRY/starexec:latest .
	@echo "Push with: podman push $$IMAGE_REGISTRY/starexec:$$IMAGE_VERSION"

image:
	@echo "Checking for image: $(RELEASE_NAME):$(IMAGE_TAG)"
	@if podman image inspect $(RELEASE_NAME):$(IMAGE_TAG) >/dev/null 2>&1; then \
		echo "✓ Image $(RELEASE_NAME):$(IMAGE_TAG) found locally"; \
	else \
		echo "Image not found locally at $(RELEASE_NAME):$(IMAGE_TAG)"; \
		echo "Attempting to pull from registry..."; \
		if podman pull $(RELEASE_NAME):$(IMAGE_TAG) 2>/dev/null; then \
			echo "✓ Successfully pulled $(RELEASE_NAME):$(IMAGE_TAG)"; \
		else \
			echo "⚠️  Image not available in registry. Building locally..."; \
			$(MAKE) build; \
		fi; \
	fi

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
	  : $${STAREXEC_DB_PASSWORD:=starexec_dev_password}; \
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
network-setup:
	@echo "Configuring Podman network (rootless mode)"
	@if [ "$(PODMAN_REQUIRES_SUDO)" = "yes" ]; then \
		echo "⚠️  Running in rootful mode. Consider running rootless for better security."; \
		echo "See: https://github.com/containers/podman/blob/main/docs/tutorials/rootless_tutorial.md"; \
	fi
	@# For rootless, Podman uses pasta/slirp4netns automatically via netavark
	@if ! podman network exists starexec-net 2>/dev/null; then \
		echo "Creating network (pasta/slirp4netns handled automatically)"; \
		podman network create starexec-net; \
	fi

deploy-podman: image network-setup volumes-create
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
	@IMAGE_REPO=$$(echo "$(IMAGE_NAME)" | cut -d: -f1); \
	IMAGE_VER="$(IMAGE_TAG)"; \
	helm template $(RELEASE_NAME) $(CHART_DIR) -f "$(VALS)" \
		--set image.repository=$$IMAGE_REPO \
		--set image.tag=$$IMAGE_VER \
		--set image.pullPolicy=Never > render.yaml
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
	 IMAGE_NAME=$(RELEASE_NAME) \
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

deploy-podman-cached: image
	@if [ ! -f render.yaml ]; then \
		echo "WARNING: render.yaml not found! Running 'make template' to generate..."; \
		$(MAKE) template; \
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
	@echo "Cleaning Podman artifacts (preserving volumes and cache)"
	@podman pod rm -f starexec starexec-pod $(RELEASE_NAME)-pod 2>/dev/null || true
	@podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-user 2>/dev/null || true
	@podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-password 2>/dev/null || true
	@podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-database 2>/dev/null || true
	@podman secret rm $(RELEASE_NAME)-$(SECRET_NAME)-rootPassword 2>/dev/null || true
	@echo "Removing StarExec images..."
	@podman rmi $(IMAGE_NAME):$(IMAGE_TAG) 2>/dev/null || true
	@echo "✓ Cleanup complete (volumes and cache preserved)"

clean-cache:
	@echo "⚠️  WARNING: Clearing Podman build cache"
	@echo "This affects ALL projects on this system, not just StarExec"
	@echo ""
	@echo "This will remove:"
	@echo "  - Unused images"
	@echo "  - Build cache"
	@echo "  - Builder instances"
	@echo ""
	@if [ -t 0 ]; then \
		read -p "Continue? (y/N): " ans; \
		if [ "$$ans" != "y" ]; then \
			echo "Cancelled"; \
			exit 0; \
		fi; \
	else \
		echo "Running non-interactively. Set FORCE=1 to skip confirmation"; \
		exit 1; \
	fi
	@echo "Pruning system..."
	@podman system prune -a -f || { echo "✗ Error during system prune"; exit 1; }
	@echo "Pruning builder cache..."
	@podman builder prune -a -f 2>/dev/null || true
	@echo "✓ Build cache cleared successfully"

clean-all: clean-podman volumes-delete
	@echo "✓ Full cleanup complete (pods, images, volumes removed; cache preserved)"

clean-hard:
	@echo "⚠️  HARD RESET: This removes ALL Podman storage"
	@echo "⚠️  This affects ALL containers/images/volumes on this system"
	@echo ""
	@echo "This will PERMANENTLY delete:"
	@echo "  - All containers (running and stopped)"
	@echo "  - All images"
	@echo "  - All volumes (including StarExec data!)"
	@echo "  - All networks (except default)"
	@echo "  - Build cache"
	@echo ""
	@echo "⚠️  THIS CANNOT BE UNDONE!"
	@echo ""
	@if [ -t 0 ]; then \
		read -p "Type 'yes, delete everything' to confirm: " ans; \
		if [ "$$ans" != "yes, delete everything" ]; then \
			echo "Cancelled"; \
			exit 0; \
		fi; \
	else \
		echo "❌ Running non-interactively. Refusing to proceed."; \
		echo "Use this command manually if you really want to reset:"; \
		echo "  podman system reset"; \
		exit 1; \
	fi
	@echo ""
	@echo "Starting hard reset..."
	@podman ps -aq --all | xargs -r podman rm -f 2>/dev/null || true && \
		echo "✓ Removed all containers"
	@podman images -q | xargs -r podman rmi -f 2>/dev/null || true && \
		echo "✓ Removed all images"
	@podman volume ls -q | xargs -r podman volume rm -f 2>/dev/null || true && \
		echo "✓ Removed all volumes"
	@podman network ls --filter "driver!=bridge" --format "{{.Name}}" | xargs -r podman network rm 2>/dev/null || true && \
		echo "✓ Removed  networks"
	@podman builder prune -a -f 2>/dev/null || true && \
		echo "✓ Cleared builder cache"
	@echo ""
	@echo "✓ Hard reset complete!"
	@echo "💾 Backup any important data before running this again"

# ============================================================================
# DEBUGGING AND DIAGNOSTICS
# ============================================================================

logs:
	@echo "=== Application Logs ==="
	@podman logs --tail 50 starexec-app 2>&1 || echo "App container not running"
	@echo ""
	@echo "=== MySQL Logs ==="
	@podman logs --tail 30 starexec-mysql 2>&1 || echo "MySQL container not running"

logs-app:
	@echo "Following application logs (Ctrl+C to stop)..."
	@podman logs -f starexec-app

logs-mysql:
	@echo "Following MySQL logs (Ctrl+C to stop)..."
	@podman logs -f starexec-mysql

test-deps:
	@echo "Testing job execution dependencies in container..."
	@echo ""
	@echo "=== Installed Packages ==="
	@podman exec starexec-app apk list --installed | grep -E "bash|util-linux|mysql-client|procps" || true
	@echo ""
	@echo "=== Tool Versions ==="
	@podman exec starexec-app bash -c "echo 'bash:' && bash --version | head -1"
	@podman exec starexec-app bash -c "echo 'flock:' && flock --version"
	@podman exec starexec-app bash -c "echo 'lscpu:' && lscpu --version"
	@podman exec starexec-app bash -c "echo 'mysql:' && mysql --version"
	@podman exec starexec-app bash -c "echo 'ps:' && ps --version"
	@echo ""
	@echo "=== Command Availability ==="
	@podman exec starexec-app bash -c "which bash flock lscpu mysql ps runsolver"
	@echo ""
	@echo "=== Test ps -p Command ==="
	@podman exec starexec-app bash -c 'ps -p $$$$ -o pid,cmd'
	@echo ""
	@echo "=== Test flock -w Command ==="
	@podman exec starexec-app bash -c "timeout 2 flock -x -w 1 /tmp/test.lock echo 'flock -w works!'"
	@echo ""
	@echo "=== CPU Info ==="
	@podman exec starexec-app lscpu | head -10
	@echo ""
	@echo "✓ All job execution dependencies validated"

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
		IMAGE_NAME=$(RELEASE_NAME) \
		IMAGE_TAG=$(IMAGE_TAG) \
		./scripts/generate-render-yaml.sh; \
	fi