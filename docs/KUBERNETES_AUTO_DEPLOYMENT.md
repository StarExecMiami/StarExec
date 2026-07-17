# Kubernetes Deployment Automation Guide

Automated tools for deploying StarExec to Kubernetes clusters with dynamic configuration detection, node labeling, and resource optimization.

This guide covers the automation scripts and Make targets. It does not, by itself, prove end-to-end Kubernetes backend maturity or large-scale performance.

> **Canonical Quokka deployment:** Do not run the direct commands in this guide
> against Quokka's MicroK8s cluster. Jenkins is the sole application deployment
> authority there and deploys only GitHub-published images. Use these commands
> for development or separately operated Kubernetes clusters.

## Quick Start (Automated)

Deploy to a non-Quokka Kubernetes cluster with a single command:

```bash
# Complete automated setup and deployment
make k8s-deploy-auto
```

This will:
1. ✅ Detect cluster configuration and available resources
2. ✅ Label worker nodes automatically
3. ✅ Create the required namespace and PersistentVolumeClaims
4. ✅ Generate optimized Helm values
5. ✅ Deploy StarExec with appropriate resource limits

## Detailed Usage

### 1. Detect Cluster Configuration

```bash
# Display cluster information
make k8s-detect

# Output JSON for programmatic use
bash ./scripts/k8s-auto-detect.sh --json
```

**Output includes:**
- Cluster name, type, Kubernetes version
- Available nodes and worker nodes
- Storage classes
- Total CPU and memory
- Namespaces and PersistentVolumeClaims
- Ingress controllers

### 2. Setup Kubernetes Cluster

```bash
# Preview changes (dry-run)
make k8s-setup-dry-run

# Apply setup
make k8s-setup
```

**This will:**
- Label all nodes as `starexec.org/worker=true`
- Add default queue label: `starexec/queue=default`
- Create the `starexec` namespace
- Create PersistentVolumeClaim for shared data
- Auto-detect a compatible storage class when available

**It does not create database credentials automatically.** Before the Helm deploy
step, ensure the `starexec-postgres-credentials` secret exists in namespace
`starexec`, or provide your own `postgres.existingSecret` override.

**For fine-grained control:**

```bash
# Label workers only
make k8s-label-workers

# Create PVC with specific storage class
bash ./scripts/k8s-node-setup.sh --create-pvc --storage-class nfs

# Create with custom size
bash ./scripts/k8s-node-setup.sh --create-pvc --pvc-size 200Gi
```

### 3. Generate Optimized Configuration

```bash
# Generate Helm values based on cluster capabilities
make k8s-generate-values
```

**Generated file:** `charts/starexec/values-auto-detected.yaml`

**Optimizes for:**
- Available CPU and memory
- Storage class compatibility
- Default resource requests/limits (uses ~1/2 of cluster resources)

**Important:** If the generated shared data PVC access mode is `ReadWriteOnce`,
the deployment is only safe for validated single-node or same-node execution.
Use `ReadWriteMany` only with storage that you have explicitly validated for
multi-node shared-PVC scheduling.

When the generated profile uses the bundled PostgreSQL sidecar (`postgres.host: localhost`),
the chart disables the standalone Helm migration hook and lets the main app pod
run migrations during startup instead.

Example generated values:
```yaml
resources:
  app:
    requests:
      memory: "2Gi"
      cpu: "2"
    limits:
      memory: "4Gi"
      cpu: "4"
  postgres:
    requests:
      memory: "1Gi"
      cpu: "1"
    limits:
      memory: "2Gi"
      cpu: "2"
kubernetes:
  dataPvc:
    storageClass: "microk8s-hostpath"  # Example only; cluster-specific
    size: "100Gi"
```

### 4. Deploy StarExec

#### Option A: Fully Automated (Recommended)

```bash
make k8s-deploy-auto
```

#### Option B: Step-by-Step

```bash
# Detect and setup
make k8s-detect
make k8s-setup

# Generate configuration
make k8s-generate-values

# Deploy
make deploy-k8s ENV=prod
```

#### Option C: With Custom Values

```bash
# Generate values
make k8s-generate-values

# Edit the generated file if needed
vi charts/starexec/values-auto-detected.yaml

# Deploy with custom values
helm install starexec ./charts/starexec \
  -f charts/starexec/values-auto-detected.yaml \
  --namespace starexec \
  --create-namespace
```

### 5. Verify Deployment

```bash
# Check cluster status
make k8s-status

# Detailed pod status
kubectl get pods -n starexec -o wide
kubectl get jobs,pods -n starexec -o wide

# Follow logs
kubectl logs -n starexec -l app=starexec -f

# Access application
kubectl port-forward -n starexec svc/starexec 8080:8080
# Visit: http://localhost:8080/starexec
```

## Scripts Reference

### k8s-auto-detect.sh

Auto-detects Kubernetes cluster configuration.

**Usage:**
```bash
./scripts/k8s-auto-detect.sh [OPTIONS]

Options:
  --json              Output as JSON
  --generate-values   Generate Helm values file
  --help              Show help
```

**Detects:**
- Cluster name, type, Kubernetes version
- Node count and resources
- Storage classes
- Ingress controllers
- Namespace status
- PVC status

### k8s-node-setup.sh

Configures Kubernetes cluster for StarExec.

**Usage:**
```bash
./scripts/k8s-node-setup.sh [OPTIONS]

Options:
  --all               Run all tasks
  --label-workers     Label nodes as workers
  --create-ns         Create namespaces
  --create-pvc        Create PersistentVolumeClaim
  --storage-class SC  Use specific storage class
  --pvc-size SIZE     PVC size (default: 50Gi)
  --dry-run           Preview without applying
  --help              Show help
```

**Examples:**
```bash
# Complete setup
./scripts/k8s-node-setup.sh --all

# Preview changes
./scripts/k8s-node-setup.sh --all --dry-run

# Only label nodes
./scripts/k8s-node-setup.sh --label-workers

# Create PVC with NFS
./scripts/k8s-node-setup.sh --create-pvc --storage-class nfs
```

## Supported Kubernetes Platforms

### Environments targeted by the automation scripts

- **MicroK8s** - Single-node, auto-detected
- **KinD** - Kubernetes in Docker
- **Minikube** - Local development
- **Amazon EKS** - AWS Elastic Kubernetes Service
- **Google GKE** - Google Kubernetes Engine
- **Azure AKS** - Azure Kubernetes Service
- **Rancher** - Self-hosted Kubernetes management
- **Vanilla Kubernetes** - Standard Kubernetes distributions with compatible `kubectl` and storage support

Treat every environment as something to validate in your own cluster before relying on it operationally.

### Auto-Detection Features

| Feature | MicroK8s | Cloud (EKS/GKE) | Vanilla K8s |
|---------|----------|-----------------|-------------|
| Cluster detection | ✅ | ✅ | ✅ |
| Node labeling | ✅ | ✅ | ✅ |
| Storage detection | ✅ | ✅ | ✅ |
| Resource calculation | ✅ | ✅ | ✅ |
| PVC creation | ✅ | ✅ | ✅ |

## Makefile Targets

```bash
# Auto-detection
make k8s-detect                 # Detect cluster configuration
bash ./scripts/k8s-auto-detect.sh --json

# Setup
make k8s-setup                  # Configure cluster (all tasks)
make k8s-setup-dry-run          # Preview setup
make k8s-label-workers          # Label nodes only
make k8s-generate-values        # Generate Helm values

# Deployment
make k8s-deploy-auto            # Complete automated deployment
make deploy-k8s ENV=prod        # Deploy with values
make undeploy-k8s               # Remove deployment

# Status
make k8s-status                 # Show cluster and deployment status
```

## Troubleshooting

### "Cannot connect to Kubernetes cluster"

```bash
# Verify kubectl is configured
kubectl cluster-info

# Check context
kubectl config current-context

# Set correct context
kubectl config use-context <context-name>
```

### No storage class found

```bash
# List available storage classes
kubectl get storageclass

# Specify storage class explicitly
bash ./scripts/k8s-node-setup.sh --create-pvc --storage-class <class-name>
```

### PVC stuck in Pending

```bash
# Check PVC status
kubectl get pvc -n starexec -o wide

# Describe for details
kubectl describe pvc starexec-data -n starexec

# Create or specify a compatible storage class if needed
kubectl apply -f - << EOF
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-shared-storage
provisioner: kubernetes.io/no-provisioner
volumeBindingMode: WaitForFirstConsumer
EOF
```

### Nodes not labeled

```bash
# Check node labels
kubectl get nodes --show-labels

# Manually label if needed
kubectl label nodes <node-name> starexec.org/worker=true

# Verify
kubectl get nodes -L starexec.org/worker
```

### Insufficient resources

The auto-detection allocates ~50% of cluster resources to StarExec. For smaller clusters, manually reduce:

```bash
# Edit auto-detected values
vi charts/starexec/values-auto-detected.yaml

# Reduce resource requests:
resources:
  app:
    requests:
      memory: "1Gi"
      cpu: "500m"
```

## Performance Tuning

### Larger Clusters (Validate Incrementally)

```bash
# Generate and customize values
make k8s-generate-values

# Edit for high concurrency
vi charts/starexec/values-auto-detected.yaml

# Increase job concurrency conservatively and validate each step
scalability:
  numJobPairsAtATime: 50
  nodeMultiplier: 50

# Increase resource limits
kubernetes:
  resources:
    limits:
      memory: "8Gi"
      cpu: "4"
```

Increase concurrency gradually, and confirm scheduling, PVC behavior, cleanup, and database status updates before raising limits further.

### Low-Resource Clusters

```bash
# Manual setup with minimal resources
bash ./scripts/k8s-node-setup.sh --all --pvc-size 20Gi

# Deploy with reduced resources
helm install starexec ./charts/starexec \
  -f charts/starexec/values-kubernetes.yaml \
  --set resources.app.requests.memory=512Mi \
  --set resources.app.requests.cpu=250m \
  --set resources.postgres.requests.memory=512Mi
```

## Advanced Usage

### MicroK8s Specific

```bash
# Setup for MicroK8s
microk8s enable storage
make k8s-setup

# Deploy with MicroK8s values
helm install starexec ./charts/starexec \
  -f charts/starexec/values-kubernetes.yaml \
  -f charts/starexec/values-microk8s.yaml
```

### Multiple Clusters

```bash
# Detect configuration for each cluster
kubectl config use-context cluster-1
make k8s-detect > cluster-1-config.txt
make k8s-generate-values

# Switch and deploy
kubectl config use-context cluster-2
make k8s-generate-values
make deploy-k8s ENV=prod
```

### CI/CD Integration

```bash
#!/bin/bash
# In your CI/CD pipeline

# Detect and setup
make k8s-detect

# Generate configuration
make k8s-generate-values

# Deploy with automatic values
make deploy-k8s ENV=prod

# Verify
make k8s-status
```

## Environment Variables

### Script Control

```bash
# Control script behavior
KUBECONFIG=/path/to/kubeconfig make k8s-detect
DRY_RUN=true make k8s-setup
```

When the default `kubectl` context is broken but `microk8s kubectl` works,
the Makefile now exports a temporary kubeconfig from `microk8s config` for
Helm-based deploy and undeploy operations.

### Resource Sizing

Generated values use these environment variables (if set):

```bash
STAREXEC_K8S_NAMESPACE=starexec
STAREXEC_K8S_JOB_IMAGE=ghcr.io/starexecmiami/starexec-job-runner:latest
STAREXEC_K8S_DATA_PVC=starexec-data
STAREXEC_K8S_MEMORY_LIMIT=2Gi
STAREXEC_K8S_CPU_LIMIT=1
```

## Next Steps

1. **Deploy**: `make k8s-deploy-auto`
2. **Verify**: `make k8s-status`
3. **Monitor**: `kubectl logs -n starexec -f`
4. **Configure**: Edit generated values file as needed
5. **Scale**: Adjust `scalability` settings for workload

## Support

For issues or questions:

1. Check logs: `kubectl logs -n starexec -l app=starexec`
2. Verify setup: `make k8s-detect`
3. Debug deployment: `kubectl describe pod -n starexec <pod-name>`
4. Review values: `cat charts/starexec/values-auto-detected.yaml`
