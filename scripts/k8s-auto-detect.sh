#!/bin/bash
# =============================================================================
# k8s-auto-detect.sh - Kubernetes Environment Auto-Detection
# =============================================================================
# Automatically detects Kubernetes cluster configuration, available resources,
# and generates optimized deployment parameters.
#
# Usage:
#   ./scripts/k8s-auto-detect.sh [--generate-values] [--json]
#   ./scripts/k8s-auto-detect.sh --help
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
CHARTS_DIR="$REPO_ROOT/charts/starexec"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Output format
OUTPUT_JSON=false
GENERATE_VALUES=false

# =============================================================================
# Helper Functions
# =============================================================================

log_info() {
  echo -e "${BLUE}ℹ${NC} $*" >&2
}

log_success() {
  echo -e "${GREEN}✓${NC} $*" >&2
}

log_warning() {
  echo -e "${YELLOW}⚠${NC} $*" >&2
}

log_error() {
  echo -e "${RED}✗${NC} $*" >&2
}

print_json() {
  local key="$1"
  local value="$2"
  echo "  \"$key\": $value,"
}

print_json_string() {
  local key="$1"
  local value="$2"
  echo "  \"$key\": \"$value\","
}

# Check if kubectl is available and setup proper command
KUBECTL_CMD="kubectl"

check_kubectl() {
  # Try kubectl first
  if command -v kubectl &> /dev/null && kubectl cluster-info &> /dev/null; then
    KUBECTL_CMD="kubectl"
    log_success "kubectl is available and connected"
    return 0
  fi
  
  # Try microk8s kubectl
  if command -v microk8s &> /dev/null && microk8s kubectl cluster-info &> /dev/null; then
    KUBECTL_CMD="microk8s kubectl"
    log_success "MicroK8s kubectl is available and connected"
    return 0
  fi
  
  log_error "Cannot connect to Kubernetes cluster. Please ensure kubectl is configured or MicroK8s is running."
  return 1
}

# Get cluster info
get_cluster_info() {
  log_info "Detecting cluster information..."
  
  local cluster_name=$($KUBECTL_CMD config current-context 2>/dev/null || echo "unknown")
  local api_server=$($KUBECTL_CMD cluster-info 2>&1 | grep 'Kubernetes master\|control plane' | head -1 | awk '{print $NF}' || echo "unknown")
  local k8s_version=$($KUBECTL_CMD version --short 2>/dev/null | grep Server | awk '{print $3}' || echo "unknown")
  
  # Detect cluster type (MicroK8s, KinD, EKS, GKE, etc.)
  local cluster_type="generic"
  if $KUBECTL_CMD get nodes -o jsonpath='{.items[0].status.nodeInfo.containerRuntimeVersion}' 2>/dev/null | grep -q "containerd"; then
    cluster_type="containerd"
  fi
  if $KUBECTL_CMD get nodes -o wide 2>/dev/null | grep -q "microk8s"; then
    cluster_type="microk8s"
  fi
  if $KUBECTL_CMD get nodes -o jsonpath='{.items[0].spec.providerID}' 2>/dev/null | grep -q "aws"; then
    cluster_type="eks"
  fi
  if $KUBECTL_CMD get nodes -o jsonpath='{.items[0].spec.providerID}' 2>/dev/null | grep -q "gce"; then
    cluster_type="gke"
  fi
  if $KUBECTL_CMD get nodes -o jsonpath='{.items[0].spec.providerID}' 2>/dev/null | grep -q "azure"; then
    cluster_type="aks"
  fi
  
  if [ "$OUTPUT_JSON" = true ]; then
    echo "{"
    print_json_string "cluster_name" "$cluster_name"
    print_json_string "cluster_type" "$cluster_type"
    print_json_string "api_server" "$api_server"
    print_json_string "kubernetes_version" "$k8s_version"
  else
    echo ""
    echo "=== Cluster Information ==="
    echo "Cluster Name: $cluster_name"
    echo "Cluster Type: $cluster_type"
    echo "API Server: $api_server"
    echo "Kubernetes Version: $k8s_version"
  fi
}

# Get node information
get_nodes_info() {
  log_info "Detecting available nodes..."
  
  local node_count=$($KUBECTL_CMD get nodes --no-headers 2>/dev/null | wc -l)
  local worker_count=$($KUBECTL_CMD get nodes -l starexec.org/worker=true --no-headers 2>/dev/null | wc -l)
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json "total_nodes" "$node_count"
    print_json "worker_nodes_labeled" "$worker_count"
  else
    echo ""
    echo "=== Node Information ==="
    echo "Total Nodes: $node_count"
    echo "StarExec Worker Nodes (labeled): $worker_count"
  fi
  
  # Detailed node information
  if [ "$OUTPUT_JSON" != true ]; then
    echo ""
    echo "Nodes:"
    $KUBECTL_CMD get nodes -o wide 2>/dev/null | awk 'NR>1 {printf "  %-20s CPU: %-6s Memory: %-10s (Status: %s)\n", $1, $6, $7, $5}'
  fi
}

# Get storage classes
get_storage_info() {
  log_info "Detecting storage classes..."
  
  local storage_classes=$($KUBECTL_CMD get storageclass --no-headers 2>/dev/null | awk '{print $1}' | tr '\n' ',' | sed 's/,$//')
  local default_sc=$($KUBECTL_CMD get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")].metadata.name}' 2>/dev/null || echo "none")
  
  if [ -z "$default_sc" ]; then
    default_sc="none"
  fi
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json_string "storage_classes" "$storage_classes"
    print_json_string "default_storage_class" "$default_sc"
  else
    echo ""
    echo "=== Storage Classes ==="
    echo "Available: $storage_classes"
    echo "Default: $default_sc"
  fi
}

# Check namespace existence
check_namespaces() {
  log_info "Checking namespaces..."
  
  local starexec_ns=$($KUBECTL_CMD get ns starexec --no-headers 2>/dev/null && echo "exists" || echo "missing")
  local starexec_jobs_ns=$($KUBECTL_CMD get ns starexec-jobs --no-headers 2>/dev/null && echo "exists" || echo "missing")
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json_string "starexec_namespace" "$starexec_ns"
    print_json_string "starexec_jobs_namespace" "$starexec_jobs_ns"
  else
    echo ""
    echo "=== Namespaces ==="
    echo "starexec: $starexec_ns"
    echo "starexec-jobs: $starexec_jobs_ns"
  fi
}

# Check existing PVCs
check_pvcs() {
  log_info "Checking PersistentVolumeClaims..."
  
  local pvc_count=$($KUBECTL_CMD get pvc -A --no-headers 2>/dev/null | wc -l)
  local starexec_pvcs=$($KUBECTL_CMD get pvc -n starexec-jobs --no-headers 2>/dev/null | wc -l)
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json "total_pvcs" "$pvc_count"
    print_json "starexec_pvcs" "$starexec_pvcs"
  else
    echo ""
    echo "=== PersistentVolumeClaims ==="
    echo "Total PVCs: $pvc_count"
    echo "StarExec PVCs: $starexec_pvcs"
  fi
}

# Get resource availability
get_resource_availability() {
  log_info "Calculating available resources..."
  
  local total_cpu=$($KUBECTL_CMD get nodes -o jsonpath='{range .items[*]}{.status.allocatable.cpu}{"\n"}{end}' 2>/dev/null | sed 's/m$//' | awk '{s+=$1} END {print s}' || echo "0")
  local total_memory=$($KUBECTL_CMD get nodes -o jsonpath='{range .items[*]}{.status.allocatable.memory}{"\n"}{end}' 2>/dev/null | sed 's/Ki$//' | awk '{s+=$1} END {print int(s/1024/1024)}' || echo "0")
  
  # Recommended values based on resources
  local recommended_cpu=$((total_cpu / 2))
  local recommended_memory=$((total_memory / 2))
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json "total_cpu_cores" "$total_cpu"
    print_json "total_memory_gb" "$total_memory"
    print_json "recommended_cpu_cores" "$recommended_cpu"
    print_json "recommended_memory_gb" "$recommended_memory"
  else
    echo ""
    echo "=== Resource Availability ==="
    echo "Total CPU Cores: $total_cpu"
    echo "Total Memory: ${total_memory}GB"
    echo "Recommended for StarExec:"
    echo "  CPU: $recommended_cpu cores"
    echo "  Memory: ${recommended_memory}GB"
  fi
}

# Detect ingress controller
detect_ingress() {
  log_info "Detecting ingress controller..."
  
  local ingress_type="none"
  $KUBECTL_CMD get ingressclass nginx &>/dev/null && ingress_type="nginx"
  $KUBECTL_CMD get ingressclass traefik &>/dev/null && ingress_type="traefik"
  $KUBECTL_CMD get ingressclass istio &>/dev/null && ingress_type="istio"
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json_string "ingress_controller" "$ingress_type"
  else
    echo ""
    echo "=== Ingress Controller ==="
    echo "Type: $ingress_type"
  fi
}

# Generate values file
generate_values_file() {
  log_info "Generating optimized values file..."
  
  local output_file="${CHARTS_DIR}/values-auto-detected.yaml"
  
  # Get detected values
  local cluster_type=$(detect_cluster_type)
  local default_sc=$($KUBECTL_CMD get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")].metadata.name}' 2>/dev/null || echo "")
  local total_cpu=$($KUBECTL_CMD get nodes -o jsonpath='{range .items[*]}{.status.allocatable.cpu}{"\n"}{end}' 2>/dev/null | sed 's/m$//' | awk '{s+=$1} END {print s}' || echo "4")
  local total_memory=$($KUBECTL_CMD get nodes -o jsonpath='{range .items[*]}{.status.allocatable.memory}{"\n"}{end}' 2>/dev/null | sed 's/Ki$//' | awk '{s+=$1} END {print int(s/1024/1024)}' || echo "8")
  
  # Recommended resources (use 1/2 of available)
  local app_cpu=$((total_cpu / 4))
  [ $app_cpu -lt 1 ] && app_cpu=1
  local app_memory=$((total_memory / 4))
  [ $app_memory -lt 2 ] && app_memory=2
  
  local postgres_cpu=$((total_cpu / 8))
  [ $postgres_cpu -lt 1 ] && postgres_cpu=1
  local postgres_memory=$((total_memory / 8))
  [ $postgres_memory -lt 1 ] && postgres_memory=1
  
  cat > "$output_file" << 'YAML_EOF'
# Auto-generated Kubernetes values file
# Generated by: k8s-auto-detect.sh
# DO NOT EDIT MANUALLY - regenerate with: ./scripts/k8s-auto-detect.sh --generate-values

image:
  repository: ghcr.io/starexecmiami/starexec
  tag: latest
  pullPolicy: IfNotPresent

backend:
  type: "kubernetes-native"

kubernetes:
  enabled: true
  jobNamespace: "starexec-jobs"
  jobImage: "ghcr.io/starexecmiami/starexec-job-runner:latest"
  jobServiceAccount: "starexec-job"
  dataPvc:
    name: "starexec-data"
    storageClass: "STORAGE_CLASS"
    size: "100Gi"
    accessModes:
      - ReadWriteMany
  nodeSelector:
    starexec.org/worker: "true"
  queueLabelKey: "starexec/queue"
  resources:
    requests:
      memory: "512Mi"
      cpu: "1"
    limits:
      memory: "2Gi"
      cpu: "1"

postgres:
  image:
    repository: docker.io/library/postgres
    tag: "15"
  host: "localhost"
  port: 5432
  persistence:
    enabled: true
    storageClass: "STORAGE_CLASS"
    size: 50Gi

resources:
  app:
    requests:
      memory: "APP_MEMORY_REQGi"
      cpu: "APP_CPU_REQ"
    limits:
      memory: "APP_MEMORY_LIMGi"
      cpu: "APP_CPU_LIM"
  postgres:
    requests:
      memory: "DB_MEMORY_REQGi"
      cpu: "DB_CPU_REQ"
    limits:
      memory: "DB_MEMORY_LIMGi"
      cpu: "DB_CPU_LIM"

persistence:
  enabled: true
  storageClass: "STORAGE_CLASS"
  size: 100Gi

scalability:
  numJobPairsAtATime: 50
  nodeMultiplier: 100
YAML_EOF

  # Replace placeholders
  sed -i "s|STORAGE_CLASS|${default_sc:-standard}|g" "$output_file"
  sed -i "s|APP_MEMORY_REQ|${app_memory}|g" "$output_file"
  sed -i "s|APP_CPU_REQ|${app_cpu}|g" "$output_file"
  sed -i "s|APP_MEMORY_LIM|$((app_memory * 2))|g" "$output_file"
  sed -i "s|APP_CPU_LIM|$((app_cpu * 2))|g" "$output_file"
  sed -i "s|DB_MEMORY_REQ|${postgres_memory}|g" "$output_file"
  sed -i "s|DB_CPU_REQ|${postgres_cpu}|g" "$output_file"
  sed -i "s|DB_MEMORY_LIM|$((postgres_memory * 2))|g" "$output_file"
  sed -i "s|DB_CPU_LIM|$((postgres_cpu * 2))|g" "$output_file"
  
  log_success "Generated values file: $output_file"
}

# Detect cluster type helper
detect_cluster_type() {
  local cluster_type="generic"
  $KUBECTL_CMD get nodes -o jsonpath='{.items[0].metadata.labels}' 2>/dev/null | grep -q "microk8s" && cluster_type="microk8s"
  echo "$cluster_type"
}

# Print help
print_help() {
  cat << EOF
${BLUE}k8s-auto-detect.sh${NC} - Kubernetes Environment Auto-Detection

${BLUE}Usage:${NC}
  ./scripts/k8s-auto-detect.sh [OPTIONS]

${BLUE}Options:${NC}
  --json              Output detection results as JSON
  --generate-values   Generate optimized Helm values file
  --help              Show this help message

${BLUE}Examples:${NC}
  # Detect and display cluster configuration
  ./scripts/k8s-auto-detect.sh

  # Generate optimized values file
  ./scripts/k8s-auto-detect.sh --generate-values

  # Output as JSON for programmatic use
  ./scripts/k8s-auto-detect.sh --json

${BLUE}Generated Files:${NC}
  - charts/starexec/values-auto-detected.yaml (when using --generate-values)

EOF
}

# =============================================================================
# Main
# =============================================================================

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --json)
      OUTPUT_JSON=true
      shift
      ;;
    --generate-values)
      GENERATE_VALUES=true
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

# Check kubectl availability
check_kubectl || exit 1

# Start JSON output if requested
if [ "$OUTPUT_JSON" = true ]; then
  echo "{"
fi

# Run detection
get_cluster_info
get_nodes_info
get_storage_info
check_namespaces
check_pvcs
get_resource_availability
detect_ingress

# End JSON output if requested
if [ "$OUTPUT_JSON" = true ]; then
  echo '  "timestamp": "'$(date -u +"%Y-%m-%dT%H:%M:%SZ")'"'
  echo "}"
else
  echo ""
  log_success "Auto-detection complete!"
fi

# Generate values file if requested
if [ "$GENERATE_VALUES" = true ]; then
  generate_values_file
fi
