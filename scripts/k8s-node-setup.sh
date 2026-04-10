#!/bin/bash
# =============================================================================
# k8s-node-setup.sh - Kubernetes Node Labeling and Configuration
# =============================================================================
# Automatically labels nodes, creates namespaces, and configures storage for
# StarExec Kubernetes deployments.
#
# Usage:
#   ./scripts/k8s-node-setup.sh [--all] [--label-workers] [--create-pvc] [--dry-run]
#   ./scripts/k8s-node-setup.sh --help
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Options
DRY_RUN=false
LABEL_WORKERS=false
CREATE_PVC=false
CREATE_NAMESPACES=false
ALL_TASKS=false

# kubectl command (will be set by check_connectivity)
KUBECTL_CMD="kubectl"

# Defaults
NAMESPACE="starexec-jobs"
WORKER_LABEL="starexec.org/worker=true"
DEFAULT_QUEUE_LABEL="starexec/queue=default"
STORAGE_CLASS=""
PVC_SIZE="50Gi"
PVC_NAME="starexec-data"

# =============================================================================
# Helper Functions
# =============================================================================

log_info() {
  echo -e "${BLUE}ℹ${NC} $*"
}

log_success() {
  echo -e "${GREEN}✓${NC} $*"
}

log_warning() {
  echo -e "${YELLOW}⚠${NC} $*"
}

log_error() {
  echo -e "${RED}✗${NC} $*" >&2
}

dry_run_prefix() {
  if [ "$DRY_RUN" = true ]; then
    echo "[DRY RUN] "
  fi
}

kubectl_run() {
  if [ "$DRY_RUN" = true ]; then
    log_info "$(dry_run_prefix)Would run: kubectl $*"
  else
    kubectl "$@" || return 1
  fi
}

# Check kubectl connectivity
check_connectivity() {
  log_info "Checking kubectl connectivity..."
  
  # Try kubectl first
  if kubectl cluster-info &> /dev/null; then
    KUBECTL_CMD="kubectl"
    log_success "kubectl is available and connected"
    return 0
  fi
  
  # Try microk8s kubectl
  if microk8s kubectl cluster-info &> /dev/null 2>&1; then
    KUBECTL_CMD="microk8s kubectl"
    log_success "MicroK8s kubectl is available and connected"
    return 0
  fi
  
  log_error "Cannot connect to Kubernetes cluster. Please ensure kubectl is configured or MicroK8s is running."
  return 1
}

# Get default storage class
get_default_storage_class() {
  local sc=$($KUBECTL_CMD get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")].metadata.name}' 2>/dev/null || echo "")
  
  if [ -z "$sc" ]; then
    # Try common defaults
    $KUBECTL_CMD get storageclass -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo ""
  else
    echo "$sc"
  fi
}

# Label worker nodes
label_worker_nodes() {
  log_info "Labeling worker nodes..."
  
  local nodes=$($KUBECTL_CMD get nodes -o jsonpath='{.items[*].metadata.name}')
  local labeled_count=0
  
  if [ -z "$nodes" ]; then
    log_warning "No nodes found in cluster"
    return 1
  fi
  
  log_info "Found nodes: $nodes"
  
  for node in $nodes; do
    log_info "Labeling node: $node"
    
    # Add worker label
    kubectl_run label nodes "$node" "$WORKER_LABEL" --overwrite || log_warning "Failed to label $node with $WORKER_LABEL"
    
    # Add default queue label if not already present
    if ! $KUBECTL_CMD get node "$node" -o jsonpath='{.metadata.labels}' | grep -q "$DEFAULT_QUEUE_LABEL"; then
      kubectl_run label nodes "$node" "$DEFAULT_QUEUE_LABEL" --overwrite || log_warning "Failed to label $node with $DEFAULT_QUEUE_LABEL"
    fi
    
    labeled_count=$((labeled_count + 1))
  done
  
  if [ "$DRY_RUN" != true ]; then
    log_success "Labeled $labeled_count node(s)"
  fi
}

# Create namespaces
create_namespaces() {
  log_info "Creating namespaces..."
  
  for ns in "starexec" "starexec-jobs"; do
    if $KUBECTL_CMD get namespace "$ns" &>/dev/null; then
      log_info "Namespace $ns already exists"
    else
      log_info "Creating namespace: $ns"
      kubectl_run create namespace "$ns" || log_warning "Failed to create namespace $ns"
    fi
  done
  
  if [ "$DRY_RUN" != true ]; then
    log_success "Namespaces ready"
  fi
}

# Create PersistentVolumeClaim
create_pvc() {
  log_info "Creating PersistentVolumeClaim..."
  
  # Detect storage class if not provided
  if [ -z "$STORAGE_CLASS" ]; then
    STORAGE_CLASS=$(get_default_storage_class)
    log_info "Using storage class: $STORAGE_CLASS"
  fi
  
  if [ -z "$STORAGE_CLASS" ]; then
    log_warning "No storage class found or specified. Skipping PVC creation."
    log_info "Create a storage class or specify one with --storage-class option"
    return 1
  fi
  
  # Check if PVC already exists
  if $KUBECTL_CMD get pvc "$PVC_NAME" -n "$NAMESPACE" &>/dev/null; then
    log_info "PVC $PVC_NAME already exists in namespace $NAMESPACE"
    return 0
  fi
  
  local pvc_yaml=$(cat << EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: $PVC_NAME
  namespace: $NAMESPACE
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: $STORAGE_CLASS
  resources:
    requests:
      storage: $PVC_SIZE
EOF
)
  
  if [ "$DRY_RUN" = true ]; then
    log_info "$(dry_run_prefix)Would create PVC with the following spec:"
    echo "$pvc_yaml" | sed 's/^/  /'
  else
    echo "$pvc_yaml" | $KUBECTL_CMD apply -f - || return 1
    log_success "Created PVC: $PVC_NAME"
  fi
}

# Verify setup
verify_setup() {
  log_info "Verifying setup..."
  
  echo ""
  log_info "=== Node Status ==="
  $KUBECTL_CMD get nodes -o wide
  
  echo ""
  log_info "=== Worker Node Labels ==="
  $KUBECTL_CMD get nodes -L starexec.org/worker,starexec/queue 2>/dev/null || $KUBECTL_CMD get nodes
  
  echo ""
  log_info "=== Namespaces ==="
  $KUBECTL_CMD get namespaces | grep -E "starexec|NAME"
  
  echo ""
  log_info "=== PersistentVolumeClaims ==="
  $KUBECTL_CMD get pvc -A | grep -E "starexec|NAMESPACE" || $KUBECTL_CMD get pvc -A
  
  echo ""
  log_success "Verification complete"
}

# Print help
print_help() {
  cat << EOF
${BLUE}k8s-node-setup.sh${NC} - Kubernetes Node Labeling and Configuration

${BLUE}Usage:${NC}
  ./scripts/k8s-node-setup.sh [OPTIONS]

${BLUE}Options:${NC}
  --all               Run all setup tasks (label, namespaces, PVC)
  --label-workers     Label all nodes as StarExec workers
  --create-ns         Create required namespaces
  --create-pvc        Create PersistentVolumeClaim for shared data
  --storage-class SC  Use specific storage class (default: auto-detect)
  --pvc-size SIZE     PVC size (default: 50Gi)
  --namespace NS      Target namespace (default: starexec-jobs)
  --dry-run           Preview changes without applying
  --help              Show this help message

${BLUE}Examples:${NC}
  # Complete setup with all tasks
  ./scripts/k8s-node-setup.sh --all

  # Only label worker nodes
  ./scripts/k8s-node-setup.sh --label-workers

  # Create PVC with specific storage class
  ./scripts/k8s-node-setup.sh --create-pvc --storage-class nfs

  # Preview changes without applying
  ./scripts/k8s-node-setup.sh --all --dry-run

${BLUE}Default Behavior:${NC}
  If no options are specified, runs verification only.

${BLUE}Requirements:${NC}
  - kubectl installed and configured
  - Access to Kubernetes cluster
  - Sufficient permissions to label nodes and create resources

EOF
}

# =============================================================================
# Main
# =============================================================================

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --all)
      ALL_TASKS=true
      LABEL_WORKERS=true
      CREATE_NAMESPACES=true
      CREATE_PVC=true
      shift
      ;;
    --label-workers)
      LABEL_WORKERS=true
      shift
      ;;
    --create-ns)
      CREATE_NAMESPACES=true
      shift
      ;;
    --create-pvc)
      CREATE_PVC=true
      shift
      ;;
    --storage-class)
      STORAGE_CLASS="$2"
      shift 2
      ;;
    --pvc-size)
      PVC_SIZE="$2"
      shift 2
      ;;
    --namespace)
      NAMESPACE="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --help)
      print_help
      exit 0
      ;;
    *)
      log_error "Unknown option: $1"
      print_help
      exit 1
      ;;
  esac
done

# Check connectivity
check_connectivity || exit 1

# Print dry-run warning
if [ "$DRY_RUN" = true ]; then
  log_warning "Running in DRY-RUN mode - no changes will be applied"
  echo ""
fi

# Run tasks
if [ "$LABEL_WORKERS" = true ]; then
  label_worker_nodes
  echo ""
fi

if [ "$CREATE_NAMESPACES" = true ]; then
  create_namespaces
  echo ""
fi

if [ "$CREATE_PVC" = true ]; then
  create_pvc
  echo ""
fi

# Always verify at the end
verify_setup

if [ "$DRY_RUN" = true ]; then
  echo ""
  log_warning "This was a DRY-RUN. No changes were applied."
  log_info "Run without --dry-run to apply changes."
fi
