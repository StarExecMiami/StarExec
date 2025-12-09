# Quick Start Guide

Get StarExec running in 10-30 minutes.

## Prerequisites Check

Before starting, verify you have the required tools:

```bash
# Check versions
java --version    # Need 17+
mvn --version     # Need 3.8+
podman --version  # Or docker --version
```

## Method 1: Docker Compose (Recommended for Testing)

**Best for:** Local development, quick testing, CI pipelines

**Time:** 5-20 minutes

### Steps

1. Clone and enter the repository:
   ```bash
   git clone https://github.com/StarExecMiami/StarExec.git
   cd StarExec
   git checkout containerised
   ```

2. Build and start:
   ```bash
   docker compose up --build
   ```

3. Access the application:
   - URL: `http://localhost:8080/starexec`
   - Default credentials: `admin:admin`

4. Stop when done:
   ```bash
   docker compose down
   ```

### Expected Output

You should see:
- Flyway migrations running
- Tomcat starting
- Application available at port 8080

## Method 2: Podman + Makefile (Recommended for Production)

**Best for:** Single-node production, rootless security

**Time:** 10-30 minutes

### Requirements

- Linux system with cgroups v2
- Podman rootless mode configured

### Steps

1. Install required packages (Ubuntu/Debian):
   ```bash
   sudo apt-get update
   sudo apt-get install -y podman catatonit passt fuse-overlayfs yq
   ```

2. Verify rootless operation:
   ```bash
   podman system info | grep rootless
   # Should show: rootless: true
   ```

3. Clone the repository:
   ```bash
   git clone https://github.com/StarExecMiami/StarExec.git
   cd StarExec
   git checkout containerised
   ```

4. Start StarExec:
   ```bash
   make start
   ```

5. Access the application:
   - URL: `http://localhost:7827/starexec`
   - Default credentials: `admin:admin`

### Expected Output

```
Building image: starexec:latest
Deploying to Podman with environment: dev
✓ Deployment complete!
  Access: http://localhost:7827/starexec
```

### Troubleshooting Podman

If you see permission errors:

```bash
# Install network helpers
sudo apt-get install -y passt
podman system migrate

# Fix cgroup delegation
make fix-cgroup-delegation
```

See [Troubleshooting Guide](TROUBLESHOOTING.md#podman-issues) for more help.

## Method 3: Kubernetes with Helm

**Best for:** Multi-node production, cloud deployments

**Time:** 30 minutes

### Prerequisites

- Kubernetes cluster (1.24+)
- kubectl configured
- Helm 3.8+

### Steps

1. Add the StarExec Helm repository:
   ```bash
   helm repo add starexec https://starexecmiami.github.io/StarExec
   helm repo update
   ```

2. Install StarExec:
   ```bash
   helm install starexec starexec/starexec \
     --create-namespace \
     -n starexec
   ```

3. Wait for deployment:
   ```bash
   kubectl get pods -n starexec -w
   ```

4. Access the application:
   ```bash
   kubectl port-forward -n starexec svc/starexec 8080:8080
   ```
   
   Then visit: `http://localhost:8080/starexec`

### Custom Configuration

Create a `values.yaml` file:

```yaml
postgres:
  password: "your-secure-password"
  
resources:
  limits:
    memory: 4Gi
    cpu: 2
```

Install with custom values:

```bash
helm install starexec starexec/starexec \
  -f values.yaml \
  -n starexec --create-namespace
```

## Post-Installation Steps

### 1. Change Default Credentials

**⚠️ CRITICAL:** Change the default `admin:admin` credentials immediately!

1. Log in at `/starexec`
2. Navigate to account settings
3. Change password

### 2. Verify Installation

Check that services are running:

**Docker Compose:**
```bash
docker compose ps
```

**Podman:**
```bash
make status
```

**Kubernetes:**
```bash
kubectl get pods -n starexec
```

### 3. Test Job Submission

Follow the [User Manual](https://starexec.ccs.miami.edu/starexec/public/help.jsp) to submit your first benchmark job.

## Next Steps

- **Configure backends:** See [Backend Configuration](BACKENDS.md)
- **Set up backups:** See [Volume Management](VOLUMES.md)
- **Security hardening:** See [Security Guide](SECURITY.md)
- **Production deployment:** See [Deployment Guide](DEPLOYMENT.md)

## Common Commands

### Docker Compose

```bash
docker compose up --build      # Start (rebuild if needed)
docker compose down            # Stop and remove
docker compose logs -f         # View logs
docker compose restart         # Restart services
```

### Podman (Makefile)

```bash
make start                     # Deploy
make stop                      # Undeploy
make status                    # Check status
make logs                      # View logs
make volumes-backup ENV=dev    # Backup data
make reset                     # Complete cleanup
```

### Kubernetes (Helm)

```bash
helm list -n starexec                    # List releases
helm upgrade starexec starexec/starexec  # Upgrade
helm uninstall starexec -n starexec      # Remove
kubectl logs -n starexec -l app=starexec # View logs
```

## Getting Help

- **Documentation:** Check the [docs/](.) directory
- **Troubleshooting:** See [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- **Issues:** [GitHub Issues](https://github.com/StarExecMiami/StarExec/issues)
- **User Manual:** [StarExec Help](https://starexec.ccs.miami.edu/starexec/public/help.jsp)