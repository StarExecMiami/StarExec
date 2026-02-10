# StarExec Installation on MicroK8s using Helm

A comprehensive guide for deploying StarExec on MicroK8s with Helm charts.

## Overview

This guide walks you through installing StarExec on a MicroK8s cluster using Helm. MicroK8s is a lightweight, upstream Kubernetes distribution ideal for development, testing, and edge deployments.

**Estimated Installation Time:** 30-45 minutes

## Prerequisites

### System Requirements

- **MicroK8s cluster:** v1.24 or higher
- **RAM:** Minimum 4GB per node (8GB recommended)
- **Disk Space:** 20GB for application + data
- **Network:** Cluster must allow inter-pod communication

### Software Requirements

- `microk8s` installed and running
- `helm` 3.8 or higher
- `kubectl` configured to access your MicroK8s cluster

### Verify Prerequisites

```bash
# Check MicroK8s status
microk8s status

# Check Helm version
helm version

# Verify kubectl access
kubectl cluster-info
```

## Step 1: Prepare MicroK8s Cluster

### 1.1 Start MicroK8s

```bash
# If not already running
microk8s start

# Verify cluster is ready
microk8s status
```

### 1.2 Enable Required Addons

StarExec requires persistent storage and DNS. Enable these MicroK8s addons:

```bash
# Enable storage addon (for PersistentVolumes)
microk8s enable storage

# Enable DNS addon (for service discovery)
microk8s enable dns

# Enable RBAC (recommended for security)
microk8s enable rbac

# Verify addons
microk8s status
```

### 1.3 Create Namespace for StarExec

```bash
# Create the main starexec namespace
microk8s kubectl create namespace starexec

# Create namespace for Kubernetes-native job runners (optional, for advanced setup)
microk8s kubectl create namespace starexec-jobs

# Verify namespaces
microk8s kubectl get namespaces
```

### 1.4 Configure kubectl Alias (Optional but Recommended)

```bash
# Add alias for convenience
alias kubectl='microk8s kubectl'

# Or use full commands as shown below with microk8s kubectl
```

## Step 2: Prepare Values Configuration

### 2.1 Create Local Values Override File

Create a file `starexec-values.yaml` in your local directory:

```yaml
# StarExec Helm Values Override for MicroK8s
# ============================================

# Image configuration
image:
  repository: ghcr.io/starexecmiami/starexec
  tag: latest
  pullPolicy: IfNotPresent

replicaCount: 1

# Backend type for MicroK8s (local is simplest for single-node)
backend:
  type: "local"
  root: "/app/backend"
  workingDir: "/app/work"

# PostgreSQL configuration
postgres:
  image:
    repository: docker.io/library/postgres
    tag: "15"
  
  host: postgres.starexec.svc.cluster.local
  port: 5432
  user: starexec
  database: starexec
  
  # Use embedded PostgreSQL for single-node setup
  # For production, use an external database
  
  persistence:
    enabled: true
    storageClass: "microk8s-hostpath"  # Use MicroK8s default storage
    size: 30Gi

# Application persistence
persistence:
  enabled: true
  storageClass: "microk8s-hostpath"
  size: 50Gi

# Resource allocation for MicroK8s
resources:
  app:
    requests:
      memory: "1Gi"
      cpu: "500m"
    limits:
      memory: "2Gi"
      cpu: "2"
  postgres:
    requests:
      memory: "512Mi"
      cpu: "250m"
    limits:
      memory: "1Gi"
      cpu: "500m"

# Database migration settings
migrations:
  enabled: true

# Service configuration
service:
  type: NodePort
  port: 8080
  nodePort: 30080

# Environment variables
env:
  LOG_LEVEL: "INFO"
  FLYWAY_BASELINE_ON_MIGRATE: "true"
```

### 2.2 Create PostgreSQL Secret

Create a file `postgres-secret.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: starexec-postgres-credentials
  namespace: starexec
type: Opaque
stringData:
  username: starexec
  password: changeme-secure-password-12345
  database: starexec
  postgres-password: postgres-root-password-secure
```

**⚠️ Security Note:** Replace `changeme-secure-password-12345` and `postgres-root-password-secure` with strong, random passwords.

Generate secure passwords:

```bash
# Generate random password
openssl rand -base64 32
```

## Step 3: Install Helm Chart

### 3.1 Add StarExec Helm Repository (Optional)

If the chart is available in a public repository:

```bash
helm repo add starexec https://starexecmiami.github.io/StarExec
helm repo update
```

If this fails or the repository doesn't exist, use the local chart from the repository.

### 3.2 Install Using Local Chart

Navigate to your StarExec repository directory:

```bash
cd /path/to/StarExec

# Create the PostgreSQL secret first
microk8s kubectl apply -f postgres-secret.yaml

# Install the chart
microk8s helm install starexec ./charts/starexec \
  --namespace starexec \
  --values starexec-values.yaml
```

### 3.3 Verify Installation

Check the deployment status:

```bash
# Watch pod creation
microk8s kubectl get pods -n starexec -w

# Wait until all pods are Running
# Expected pods: starexec-app, postgres, (optional: migration-job)

# Press Ctrl+C to stop watching
```

Check pod details:

```bash
# Get detailed pod information
microk8s kubectl describe pods -n starexec

# Check logs for any errors
microk8s kubectl logs -n starexec -l app=starexec
microk8s kubectl logs -n starexec -l app=postgres
```

## Step 4: Configure Access

### 4.1 Identify Access Method

For MicroK8s, the NodePort service provides external access:

```bash
# Get the NodePort
microk8s kubectl get svc -n starexec

# Note the NodePort (typically 30080)
```

### 4.2 Determine Node IP

```bash
# Get MicroK8s node IP
microk8s kubectl get nodes -o wide

# For local MicroK8s, typically: 127.0.0.1 or 192.168.x.x
# Record this as YOUR_NODE_IP
```

### 4.3 Access StarExec Web UI

Open your browser and navigate to:

```
http://YOUR_NODE_IP:30080/starexec
```

Or if running locally:

```
http://localhost:30080/starexec
```

### 4.4 Default Credentials

**Username:** `admin`
**Password:** `admin`

⚠️ **IMPORTANT:** Change the default password immediately after first login!

## Step 5: Verify Deployment

### 5.1 Check All Services

```bash
# List all resources in starexec namespace
microk8s kubectl get all -n starexec

# Check persistent volumes
microk8s kubectl get pvc -n starexec
```

### 5.2 Test Database Connection

```bash
# Connect to PostgreSQL pod
microk8s kubectl exec -it -n starexec $(microk8s kubectl get pods -n starexec -l app=postgres -o jsonpath='{.items[0].metadata.name}') -- bash

# Inside the pod, connect to database
psql -U starexec -d starexec -h localhost

# List tables to verify schema
\dt

# Exit
\q
exit
```

### 5.3 Check Application Logs

```bash
# View recent application logs
microk8s kubectl logs -n starexec -l app=starexec --tail=50

# Follow logs in real-time
microk8s kubectl logs -n starexec -l app=starexec -f
```

## Step 6: Post-Installation Configuration

### 6.1 Change Default Password

1. Log in with `admin:admin`
2. Navigate to **Admin Panel** → **Users**
3. Select the `admin` user
4. Change password to something secure
5. Save changes

### 6.2 Configure Storage Locations

Verify that StarExec can write to storage:

```bash
# Check mount points in the app pod
microk8s kubectl exec -it -n starexec $(microk8s kubectl get pods -n starexec -l app=starexec -o jsonpath='{.items[0].metadata.name}') -- bash

# Verify data directory
ls -la /app/data/
ls -la /app/sandbox/

# Exit
exit
```

### 6.3 Configure Backup Location (Optional)

Create a backup location outside the cluster:

```bash
# Create backup directory on host
mkdir -p ~/starexec-backups

# Set permissions
chmod 755 ~/starexec-backups
```

## Troubleshooting

### Issue: Pods Not Starting

**Symptoms:** Pods stuck in `Pending` or `CrashLoopBackOff`

**Solutions:**

```bash
# Check pod status and events
microk8s kubectl describe pod -n starexec <pod-name>

# Check available resources
microk8s kubectl top nodes
microk8s kubectl top pods -n starexec

# Check storage availability
microk8s kubectl get pvc -n starexec

# Check logs
microk8s kubectl logs -n starexec <pod-name>
```

### Issue: Database Connection Failures

**Symptoms:** Application logs show database connection errors

**Solutions:**

```bash
# Verify PostgreSQL pod is running
microk8s kubectl get pods -n starexec -l app=postgres

# Check PostgreSQL logs
microk8s kubectl logs -n starexec -l app=postgres

# Verify service endpoint
microk8s kubectl get endpoints -n starexec postgres

# Test connectivity from app pod
microk8s kubectl exec -it -n starexec $(microk8s kubectl get pods -n starexec -l app=starexec -o jsonpath='{.items[0].metadata.name}') -- \
  bash -c 'nc -zv postgres.starexec.svc.cluster.local 5432'
```

### Issue: Storage Space Issues

**Symptoms:** Jobs fail with "no space left on device"

**Solutions:**

```bash
# Check storage usage
microk8s kubectl exec -it -n starexec $(microk8s kubectl get pods -n starexec -l app=postgres -o jsonpath='{.items[0].metadata.name}') -- \
  df -h /var/lib/postgresql/data

# Expand PVC if needed
microk8s kubectl patch pvc -n starexec starexec-data -p '{"spec":{"resources":{"requests":{"storage":"100Gi"}}}}'
```

### Issue: Cannot Access Web UI

**Symptoms:** Browser shows connection refused

**Solutions:**

```bash
# Verify service is created
microk8s kubectl get svc -n starexec

# Check service endpoints
microk8s kubectl get endpoints -n starexec

# Test NodePort connectivity
curl -i http://localhost:30080/starexec

# If using remote host, replace localhost with node IP
```

## Advanced Configuration

### Using Kubernetes-Native Backend

For multi-node clusters with distributed job execution:

```bash
# Create override file for K8s-native backend
cat > starexec-k8s-values.yaml << 'EOF'
backend:
  type: "kubernetes-native"

kubernetes:
  enabled: true
  jobNamespace: "starexec-jobs"
  jobImage: "ghcr.io/starexecmiami/starexec-job-runner:latest"
  dataPvc:
    name: "starexec-data"
    storageClass: "microk8s-hostpath"
    size: "100Gi"
    accessModes:
      - ReadWriteMany
  nodeSelector:
    starexec.org/worker: "true"
EOF

# Install with K8s-native backend
microk8s helm install starexec ./charts/starexec \
  --namespace starexec \
  --values starexec-values.yaml \
  --values starexec-k8s-values.yaml
```

### Using External PostgreSQL

To use an external database instead of the bundled one:

```bash
cat > postgres-external-values.yaml << 'EOF'
postgres:
  host: "your-postgres-host.example.com"
  port: 5432
  user: "starexec"
  database: "starexec"
  existingSecret: "starexec-postgres-credentials"
  persistence:
    enabled: false
EOF

# Install with external database
microk8s helm install starexec ./charts/starexec \
  --namespace starexec \
  --values starexec-values.yaml \
  --values postgres-external-values.yaml
```

### Enabling Ingress

For HTTP/HTTPS access without NodePort:

```bash
# Enable ingress addon
microk8s enable ingress

# Create ingress configuration
cat > starexec-ingress.yaml << 'EOF'
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: starexec
  namespace: starexec
spec:
  ingressClassName: nginx
  rules:
  - host: starexec.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: starexec
            port:
              number: 8080
EOF

microk8s kubectl apply -f starexec-ingress.yaml
```

## Uninstall

To remove StarExec from your cluster:

```bash
# Delete the Helm release (keeps PVCs and data)
microk8s helm uninstall starexec -n starexec

# Delete the namespace
microk8s kubectl delete namespace starexec

# Delete PVCs if you want to free storage
microk8s kubectl delete pvc -n starexec --all

# Delete the job namespace (if created)
microk8s kubectl delete namespace starexec-jobs
```

## Backup and Restore

### Backup Volumes

```bash
# Create backup directory
mkdir -p ~/starexec-backups/$(date +%Y%m%d-%H%M%S)

# Backup database
microk8s kubectl exec -n starexec $(microk8s kubectl get pods -n starexec -l app=postgres -o jsonpath='{.items[0].metadata.name}') -- \
  pg_dump -U starexec starexec > ~/starexec-backups/$(date +%Y%m%d-%H%M%S)/database.sql

# Backup persistent data
microk8s kubectl exec -it -n starexec $(microk8s kubectl get pods -n starexec -l app=starexec -o jsonpath='{.items[0].metadata.name}') -- \
  tar -czf - /app/data > ~/starexec-backups/$(date +%Y%m%d-%H%M%S)/app-data.tar.gz
```

### Restore from Backup

```bash
# Restore database
microk8s kubectl exec -i -n starexec $(microk8s kubectl get pods -n starexec -l app=postgres -o jsonpath='{.items[0].metadata.name}') -- \
  psql -U starexec starexec < ~/starexec-backups/YYYYMMDD-HHMMSS/database.sql

# Restore application data
# Contact StarExec support for proper restore procedures
```

## Monitoring

### Check Resource Usage

```bash
# View current resource usage
microk8s kubectl top pods -n starexec

# View resource requests vs actual
microk8s kubectl describe nodes
```

### View Events

```bash
# Get cluster events
microk8s kubectl get events -n starexec --sort-by='.lastTimestamp'

# Watch for new events
microk8s kubectl get events -n starexec -w
```

### Enable Metrics Server (Optional)

```bash
# Enable metrics
microk8s enable metrics-server

# Wait a moment for metrics to be collected
sleep 30

# View metrics
microk8s kubectl top pods -n starexec
```

## Support and Documentation

- **GitHub Repository:** https://github.com/StarExecMiami/StarExec
- **Issue Tracker:** https://github.com/StarExecMiami/StarExec/issues
- **Documentation:** See `docs/` directory in the repository
- **Architecture Guide:** `docs/ARCHITECTURE.md`
- **Troubleshooting:** `docs/TROUBLESHOOTING.md`
- **Configuration Reference:** `docs/CONFIGURATION.md`

## Next Steps

After successful installation:

1. **Change default password** (admin:admin)
2. **Create user accounts** for your team
3. **Upload benchmark problems** to the system
4. **Create solvers** and register them
5. **Submit benchmark jobs** to run on the cluster
6. **Configure backups** for data protection
7. **Review security settings** for production use

## Security Considerations

For production deployments:

- [ ] Change all default passwords
- [ ] Use strong credentials for PostgreSQL
- [ ] Enable HTTPS/TLS for ingress
- [ ] Implement network policies
- [ ] Configure RBAC properly
- [ ] Enable audit logging
- [ ] Use external database for better management
- [ ] Implement backup and disaster recovery
- [ ] Monitor resource usage and logs
- [ ] Keep Kubernetes and applications updated

See `docs/SECURITY.md` for comprehensive security guidance.

## Additional Resources

- **Quick Start Guide:** `docs/QUICKSTART.md`
- **Deployment Guide:** `docs/DEPLOYMENT.md`
- **Configuration Reference:** `docs/CONFIGURATION.md`
- **Database Management:** `docs/DATABASE.md`
- **Performance Tuning:** `docs/PERFORMANCE.md`

---

**Last Updated:** 2025
**Compatible with:** StarExec 2025.x, MicroK8s 1.24+, Helm 3.8+