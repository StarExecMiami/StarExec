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

json_escape() {
  local value="$1"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  value=${value//$'\r'/\\r}
  value=${value//$'\t'/\\t}
  printf '%s' "$value"
}

print_json_string() {
  local key="$1"
  local value="$2"
  echo "  \"$key\": \"$(json_escape "$value")\","
}

# Check if kubectl is available and setup proper command
KUBECTL_CMD=(kubectl)

kubectl_cmd() {
  "${KUBECTL_CMD[@]}" "$@"
}

get_ready_nodes() {
  kubectl_cmd get nodes --no-headers 2>/dev/null | awk '$2 == "Ready" {print $1}'
}

get_ready_worker_nodes() {
  kubectl_cmd get nodes -l starexec.org/worker=true --no-headers 2>/dev/null | awk '$2 == "Ready" {print $1}'
}

get_sizing_nodes() {
  local worker_nodes
  worker_nodes="$(get_ready_worker_nodes)"
  if [[ -n "${worker_nodes}" ]]; then
    printf '%s\n' "${worker_nodes}"
    return 0
  fi

  get_ready_nodes
}

sum_sizing_node_allocatable() {
  local resource="$1"
  local node
  local total=0
  local saw_value=false

  while IFS= read -r node; do
    [ -z "$node" ] && continue
    local value
    value=$(kubectl_cmd get node "$node" -o jsonpath="{.status.allocatable.${resource}}" 2>/dev/null || true)
    [ -z "$value" ] && continue
    saw_value=true

    case "$resource" in
      cpu)
        if printf '%s' "$value" | grep -q 'm$'; then
          value=${value%m}
        else
          value=$((value * 1000))
        fi
        ;;
      memory)
        if printf '%s' "$value" | grep -q 'Ki$'; then
          value=${value%Ki}
          value=$((value / 1024 / 1024))
        elif printf '%s' "$value" | grep -q 'Mi$'; then
          value=${value%Mi}
          value=$((value / 1024))
        elif printf '%s' "$value" | grep -q 'Gi$'; then
          value=${value%Gi}
        else
          continue
        fi
        ;;
    esac

    total=$((total + value))
  done < <(get_sizing_nodes)

  if [[ "$saw_value" = true ]]; then
    echo "$total"
  fi
}

format_cpu_quantity() {
  local millicores="$1"
  if (( millicores % 1000 == 0 )); then
    echo $((millicores / 1000))
  else
    echo "${millicores}m"
  fi
}

describe_node_resource() {
  local resource="$1"
  local value="$2"

  case "$resource" in
    cpu)
      format_cpu_quantity "$value"
      ;;
    memory)
      echo "${value}Gi"
      ;;
  esac
}

print_node_details() {
  local node_name
  local node_status

  while IFS=$'\t' read -r node_name node_status; do
    [ -z "$node_name" ] && continue

    local cpu_value
    cpu_value=$(kubectl_cmd get node "$node_name" -o jsonpath='{.status.allocatable.cpu}' 2>/dev/null || true)
    local memory_value
    memory_value=$(kubectl_cmd get node "$node_name" -o jsonpath='{.status.allocatable.memory}' 2>/dev/null || true)

    printf "  %-20s CPU: %-6s Memory: %-10s (Status: %s)\n" \
      "$node_name" \
      "${cpu_value:-unknown}" \
      "${memory_value:-unknown}" \
      "$node_status"
  done < <(kubectl_cmd get nodes --no-headers 2>/dev/null | awk '{print $1 "\t" $2}')
}

check_kubectl() {
  # Try kubectl first
  if command -v kubectl &> /dev/null && kubectl cluster-info &> /dev/null; then
    KUBECTL_CMD=(kubectl)
    log_success "kubectl is available and connected"
    return 0
  fi
  
  # Try microk8s kubectl
  if command -v microk8s &> /dev/null && microk8s kubectl cluster-info &> /dev/null; then
    KUBECTL_CMD=(microk8s kubectl)
    log_success "MicroK8s kubectl is available and connected"
    return 0
  fi
  
  log_error "Cannot connect to Kubernetes cluster. Please ensure kubectl is configured or MicroK8s is running."
  return 1
}

# Get cluster info
get_cluster_info() {
  log_info "Detecting cluster information..."
  
  local cluster_name
  cluster_name=$(kubectl_cmd config current-context 2>/dev/null || echo "unknown")
  local api_server
  api_server=$(kubectl_cmd cluster-info 2>&1 | grep 'Kubernetes master\|control plane' | head -1 | awk '{print $NF}' || echo "unknown")
  local k8s_version
  k8s_version=$(kubectl_cmd version -o json 2>/dev/null | sed -n 's/.*"gitVersion":"\([^"]*\)".*/\1/p' | head -1 || echo "unknown")
  if [ -z "$k8s_version" ]; then
    k8s_version="unknown"
  fi
  
  # Detect cluster type (MicroK8s, KinD, EKS, GKE, etc.)
  local cluster_type="generic"
  if kubectl_cmd get nodes -o jsonpath='{.items[0].status.nodeInfo.containerRuntimeVersion}' 2>/dev/null | grep -q "containerd"; then
    cluster_type="containerd"
  fi
  if printf '%s' "$cluster_name" | grep -qi "microk8s" || kubectl_cmd get nodes -o wide 2>/dev/null | grep -q "microk8s"; then
    cluster_type="microk8s"
  fi
  if kubectl_cmd get nodes -o jsonpath='{.items[0].spec.providerID}' 2>/dev/null | grep -q "aws"; then
    cluster_type="eks"
  fi
  if kubectl_cmd get nodes -o jsonpath='{.items[0].spec.providerID}' 2>/dev/null | grep -q "gce"; then
    cluster_type="gke"
  fi
  if kubectl_cmd get nodes -o jsonpath='{.items[0].spec.providerID}' 2>/dev/null | grep -q "azure"; then
    cluster_type="aks"
  fi
  
  if [ "$OUTPUT_JSON" = true ]; then
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
  
  local node_count
  node_count=$(kubectl_cmd get nodes --no-headers 2>/dev/null | wc -l)
  local ready_node_count
  ready_node_count=$(get_ready_nodes | wc -l)
  local worker_count
  worker_count=$(kubectl_cmd get nodes -l starexec.org/worker=true --no-headers 2>/dev/null | wc -l)
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json "total_nodes" "$node_count"
    print_json "ready_nodes" "$ready_node_count"
    print_json "worker_nodes_labeled" "$worker_count"
  else
    echo ""
    echo "=== Node Information ==="
    echo "Total Nodes: $node_count"
    echo "Ready Nodes: $ready_node_count"
    echo "StarExec Worker Nodes (labeled): $worker_count"
  fi
  
  # Detailed node information
  if [ "$OUTPUT_JSON" != true ]; then
    echo ""
    echo "Nodes:"
    print_node_details
  fi
}

get_preferred_storage_class() {
  local default_sc
  default_sc=$(kubectl_cmd get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")].metadata.name}' 2>/dev/null || true)
  if [ -n "$default_sc" ]; then
    echo "$default_sc"
    return 0
  fi

  kubectl_cmd get storageclass -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true
}

get_storage_class_provisioner() {
  local storage_class="$1"
  [ -z "$storage_class" ] && return 0

  kubectl_cmd get storageclass "$storage_class" -o jsonpath='{.provisioner}' 2>/dev/null || true
}

infer_storage_access_mode() {
  local storage_class="$1"
  local provisioner
  provisioner="$(get_storage_class_provisioner "$storage_class")"

  local storage_class_lc
  storage_class_lc=$(printf '%s' "$storage_class" | tr '[:upper:]' '[:lower:]')
  local provisioner_lc
  provisioner_lc=$(printf '%s' "$provisioner" | tr '[:upper:]' '[:lower:]')

  case "${storage_class_lc} ${provisioner_lc}" in
    *microk8s-hostpath*|*hostpath*|*local-path*|*openebs.io/local*|*ebs.csi.aws.com*|*kubernetes.io/aws-ebs*|*pd.csi.storage.gke.io*|*kubernetes.io/gce-pd*|*disk.csi.azure.com*|*kubernetes.io/azure-disk*)
      echo "ReadWriteOnce"
      ;;
    *nfs*|*efs.csi.aws.com*|*file.csi.azure.com*|*azurefile*|*cephfs*|*gluster*|*smb*)
      echo "ReadWriteMany"
      ;;
    *)
      log_warning "Storage class ${storage_class:-<none>} has unknown access-mode characteristics; defaulting to ReadWriteOnce. Configure ReadWriteMany explicitly for validated multi-node shared storage."
      echo "ReadWriteOnce"
      ;;
  esac
}

# Get storage classes
get_storage_info() {
  log_info "Detecting storage classes..."
  
  local storage_classes
  storage_classes=$(kubectl_cmd get storageclass --no-headers 2>/dev/null | awk '{print $1}' | tr '\n' ',' | sed 's/,$//')
  local default_sc
  default_sc=$(get_preferred_storage_class)
  local storage_access_mode="unknown"
  if [ -n "$default_sc" ]; then
    storage_access_mode=$(infer_storage_access_mode "$default_sc")
  fi
  local storage_class_state="detected"

  if [ -z "$default_sc" ]; then
    default_sc="none"
    storage_class_state="missing"
  fi

  if [ "$OUTPUT_JSON" = true ]; then
    print_json_string "storage_classes" "$storage_classes"
    print_json_string "default_storage_class" "$default_sc"
    print_json_string "storage_access_mode" "$storage_access_mode"
    print_json_string "storage_class_state" "$storage_class_state"
  else
    echo ""
    echo "=== Storage Classes ==="
    echo "Available: $storage_classes"
    echo "Default: $default_sc"
    echo "Access mode heuristic: $storage_access_mode"
  fi
}

# Check namespace existence
check_namespaces() {
  log_info "Checking namespaces..."
  
  local starexec_ns="missing"
  kubectl_cmd get ns starexec --no-headers &>/dev/null && starexec_ns="exists"
  local starexec_jobs_ns="missing"
  kubectl_cmd get ns starexec-jobs --no-headers &>/dev/null && starexec_jobs_ns="exists"
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json_string "starexec_namespace" "$starexec_ns"
    print_json_string "legacy_starexec_jobs_namespace" "$starexec_jobs_ns"
  else
    echo ""
    echo "=== Namespaces ==="
    echo "starexec: $starexec_ns"
    echo "starexec-jobs (legacy, unused by current backend contract): $starexec_jobs_ns"
  fi
}

# Check existing PVCs
check_pvcs() {
  log_info "Checking PersistentVolumeClaims..."
  
  local pvc_count
  pvc_count=$(kubectl_cmd get pvc -A --no-headers 2>/dev/null | wc -l)
  local starexec_pvcs
  starexec_pvcs=$(kubectl_cmd get pvc -n starexec --no-headers 2>/dev/null | wc -l)
  
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
  
  local total_cpu_millicores
  total_cpu_millicores=$(sum_sizing_node_allocatable cpu)
  local total_memory
  total_memory=$(sum_sizing_node_allocatable memory)

  if [ -z "$total_cpu_millicores" ]; then
    total_cpu_millicores=0
  fi
  if [ -z "$total_memory" ]; then
    total_memory=0
  fi

  local total_cpu_cores=$((total_cpu_millicores / 1000))
  local recommended_cpu_millicores=$((total_cpu_millicores / 2))
  
  # Recommended values based on resources
  local recommended_memory=$((total_memory / 2))
  
  if [ "$OUTPUT_JSON" = true ]; then
    print_json "total_cpu_cores" "$total_cpu_cores"
    print_json "total_cpu_millicores" "$total_cpu_millicores"
    print_json "total_memory_gb" "$total_memory"
    print_json "recommended_cpu_cores" $((recommended_cpu_millicores / 1000))
    print_json "recommended_cpu_millicores" "$recommended_cpu_millicores"
    print_json "recommended_memory_gb" "$recommended_memory"
  else
    echo ""
    echo "=== Resource Availability ==="
    echo "Total CPU: $(describe_node_resource cpu "$total_cpu_millicores")"
    echo "Total Memory: ${total_memory}GB"
    echo "Recommended for StarExec:"
    echo "  CPU: $(describe_node_resource cpu "$recommended_cpu_millicores")"
    echo "  Memory: ${recommended_memory}GB"
  fi
}

# Detect ingress controller
detect_ingress() {
  log_info "Detecting ingress controller..."
  
  local ingress_type="none"
  kubectl_cmd get ingressclass nginx &>/dev/null && ingress_type="nginx"
  kubectl_cmd get ingressclass traefik &>/dev/null && ingress_type="traefik"
  kubectl_cmd get ingressclass istio &>/dev/null && ingress_type="istio"
  
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
  local cluster_type
  cluster_type=$(detect_cluster_type)
  local default_sc
  default_sc=$(get_preferred_storage_class)
  local total_cpu_millicores
  total_cpu_millicores=$(sum_sizing_node_allocatable cpu)
  local total_memory
  total_memory=$(sum_sizing_node_allocatable memory)
  if [ -z "$total_cpu_millicores" ]; then
    total_cpu_millicores=4000
    log_warning "Could not detect schedulable CPU capacity; defaulting to 4 cores"
  fi
  if [ -z "$total_memory" ]; then
    total_memory=8
    log_warning "Could not detect schedulable memory capacity; defaulting to 8Gi"
  fi
  local data_access_mode="ReadWriteMany"
  if [ -n "$default_sc" ]; then
    data_access_mode=$(infer_storage_access_mode "$default_sc")
  elif [ "$cluster_type" = "microk8s" ]; then
    data_access_mode="ReadWriteOnce"
  fi
  
  # Recommended resources (use 1/2 of available)
  local app_cpu_millicores=$((total_cpu_millicores / 4))
  [ "$app_cpu_millicores" -lt 1000 ] && app_cpu_millicores=1000
  local app_memory=$((total_memory / 4))
  [ $app_memory -lt 2 ] && app_memory=2
  
  local postgres_cpu_millicores=$((total_cpu_millicores / 8))
  [ "$postgres_cpu_millicores" -lt 500 ] && postgres_cpu_millicores=500
  local postgres_memory=$((total_memory / 8))
  [ $postgres_memory -lt 1 ] && postgres_memory=1

  local available_job_cpu_millicores=$((total_cpu_millicores - app_cpu_millicores - postgres_cpu_millicores))
  if [ "$available_job_cpu_millicores" -lt 1000 ]; then
    available_job_cpu_millicores=1000
  fi
  local num_job_pairs_at_a_time=$((available_job_cpu_millicores / 1000))
  [ "$num_job_pairs_at_a_time" -lt 1 ] && num_job_pairs_at_a_time=1
  local node_multiplier=$((num_job_pairs_at_a_time * 2))
  [ "$node_multiplier" -lt 16 ] && node_multiplier=16
  
  cat > "$output_file" << 'YAML_EOF'
# Auto-generated Kubernetes values file
# Generated by: k8s-auto-detect.sh
# DO NOT EDIT MANUALLY - regenerate with: ./scripts/k8s-auto-detect.sh --generate-values

image:
  repository: ghcr.io/starexecmiami/starexec
  # Auto-detected deployments use the mutable latest tag; always check GHCR.
  # CI/CD overrides tag and pullPolicy with a unique per-build tag + IfNotPresent.
  tag: latest
  pullPolicy: Always

backend:
  type: "kubernetes-native"

kubernetes:
  enabled: true
  # Jobs run in the Helm release namespace because the shared PVC and
  # ServiceAccount are namespaced resources.
  jobNamespace: ""
  jobImage: "ghcr.io/starexecmiami/starexec-job-runner:latest"
  jobServiceAccount: "starexec-job"
  dataPvc:
    name: "starexec-data"
    storageClass: "STORAGE_CLASS"
    size: "100Gi"
    accessModes:
      - DATA_ACCESS_MODE
  nodeSelector:
    starexec.org/worker: "true"
  workerSelectorKey: "starexec.org/worker"
  workerSelectorValue: "true"
  queueLabelKey: "starexec/queue"
  resources:
    requests:
      memory: "512Mi"
      cpu: "1"
    limits:
      memory: "2Gi"
      cpu: "1"
  strictOnePairPerCpu: true
  ttlSecondsAfterFinished: 3600
  backoffLimit: 0
  maxConcurrentJobs: 50
  orphanSweepIntervalMs: 300000

postgres:
  image:
    repository: docker.io/library/postgres
    tag: "15"
  host: "localhost"
  port: 5432
  existingSecret: "starexec-postgres-credentials"
  existingSecretKeys:
    user: "user"
    password: "password"
    database: "database"
    rootPassword: "rootPassword"
  persistence:
    enabled: true
    storageClass: "STORAGE_CLASS"
    size: 20Gi

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
  appDataSize: 100Gi
  backendSize: 5Gi
  sandboxSize: 10Gi
  workSize: 20Gi

scalability:
  numJobPairsAtATime: NUM_JOB_PAIRS
  nodeMultiplier: NODE_MULTIPLIER

service:
  type: NodePort
  nodePort: 30080

migrations:
  enabled: false
YAML_EOF

  # Replace placeholders
  sed -i "s|STORAGE_CLASS|${default_sc}|g" "$output_file"
  sed -i "s|DATA_ACCESS_MODE|${data_access_mode}|g" "$output_file"
  sed -i "s|APP_MEMORY_REQ|${app_memory}|g" "$output_file"
  sed -i "s|APP_CPU_REQ|$(format_cpu_quantity "$app_cpu_millicores")|g" "$output_file"
  sed -i "s|APP_MEMORY_LIM|$((app_memory * 2))|g" "$output_file"
  sed -i "s|APP_CPU_LIM|$(format_cpu_quantity $((app_cpu_millicores * 2)))|g" "$output_file"
  sed -i "s|DB_MEMORY_REQ|${postgres_memory}|g" "$output_file"
  sed -i "s|DB_CPU_REQ|$(format_cpu_quantity "$postgres_cpu_millicores")|g" "$output_file"
  sed -i "s|DB_MEMORY_LIM|$((postgres_memory * 2))|g" "$output_file"
  sed -i "s|DB_CPU_LIM|$(format_cpu_quantity $((postgres_cpu_millicores * 2)))|g" "$output_file"
  sed -i "s|NUM_JOB_PAIRS|${num_job_pairs_at_a_time}|g" "$output_file"
  sed -i "s|NODE_MULTIPLIER|${node_multiplier}|g" "$output_file"
  
  log_success "Generated values file: $output_file"
}

# Detect cluster type helper
detect_cluster_type() {
  local cluster_type="generic"
  kubectl_cmd config current-context 2>/dev/null | grep -qi "microk8s" && cluster_type="microk8s"
  kubectl_cmd get nodes -o jsonpath='{.items[0].metadata.labels}' 2>/dev/null | grep -q "microk8s" && cluster_type="microk8s"
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
