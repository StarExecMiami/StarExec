#!/usr/bin/env bash
# Podman volume management utilities for StarExec
# Backup, restore, and sharing of persistent data

set -euo pipefail

# Configuration
VOLUME_PREFIX="${VOLUME_PREFIX:-starexec}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
DATE_STAMP=$(date +%Y%m%d-%H%M%S)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# List all StarExec volumes
list_volumes() {
    log_info "StarExec volumes:"
    podman volume ls --filter "name=${VOLUME_PREFIX}" --format "table {{.Name}}\t{{.Driver}}\t{{.Mountpoint}}"
}

# Create named volumes if they don't exist
create_volumes() {
    local env="${1:-dev}"
    local data_vol="${VOLUME_PREFIX}-${env}-data"
    local pg_vol="${VOLUME_PREFIX}-${env}-postgres"
    
    log_info "Creating volumes for environment: ${env}"
    
    if podman volume exists "$data_vol" 2>/dev/null; then
        log_warn "Volume $data_vol already exists"
    else
        podman volume create "$data_vol"
        log_info "Created $data_vol"
    fi
    
    if podman volume exists "$pg_vol" 2>/dev/null; then
        log_warn "Volume $pg_vol already exists"
    else
        podman volume create "$pg_vol"
        log_info "Created $pg_vol"
    fi
}

# Export volume to tar archive (for sharing with colleagues)
export_volume() {
    local volume_name="$1"
    local output_file="${2:-${BACKUP_DIR}/${volume_name}-${DATE_STAMP}.tar.gz}"
    
    mkdir -p "$BACKUP_DIR"
    
    log_info "Exporting volume: $volume_name to $output_file"
    
    # Use a temporary container to mount volume and create archive
    podman run --rm \
        -v "$volume_name:/data:ro" \
        -v "$(pwd)/$BACKUP_DIR:/backup" \
        docker.io/library/alpine:latest \
        tar czf "/backup/$(basename "$output_file")" -C /data .
    
    log_info "Export complete: $output_file"
    log_info "Share this file with colleagues using: scp, email, or cloud storage"
    log_info "Size: $(du -h "$output_file" | cut -f1)"
}

# Import volume from tar archive
import_volume() {
    local archive="$1"
    local volume_name="$2"
    
    if [ ! -f "$archive" ]; then
        log_error "Archive not found: $archive"
        exit 1
    fi
    
    log_info "Importing archive: $archive into volume: $volume_name"
    
    # Create volume if it doesn't exist
    if ! podman volume exists "$volume_name" 2>/dev/null; then
        podman volume create "$volume_name"
        log_info "Created new volume: $volume_name"
    else
        log_warn "Volume $volume_name exists, will overwrite contents"
        read -p "Continue? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_error "Import cancelled"
            exit 1
        fi
    fi
    
    # Extract archive into volume
    podman run --rm \
        -v "$volume_name:/data" \
        -v "$(realpath "$archive"):/backup.tar.gz:ro" \
        docker.io/library/alpine:latest \
        sh -c "cd /data && tar xzf /backup.tar.gz"
    
    log_info "Import complete: $volume_name"
}

# Backup all StarExec volumes
backup_all() {
    local env="${1:-dev}"
    local backup_name="${VOLUME_PREFIX}-${env}-full-${DATE_STAMP}"
    
    log_info "Backing up all volumes for environment: $env"
    
    export_volume "${VOLUME_PREFIX}-${env}-data" "${BACKUP_DIR}/${backup_name}-data.tar.gz"
    export_volume "${VOLUME_PREFIX}-${env}-postgres" "${BACKUP_DIR}/${backup_name}-postgres.tar.gz"
    
    log_info "Full backup complete. Archive contents:"
    ls -lh "${BACKUP_DIR}/${backup_name}"*.tar.gz || true

    record_checksum "${BACKUP_DIR}/${backup_name}-data.tar.gz"
    record_checksum "${BACKUP_DIR}/${backup_name}-postgres.tar.gz"
}

record_checksum() {
    local file="$1"
    if [ -f "$file" ]; then
        log_info "Recording checksum for $file"
        mkdir -p "${BACKUP_DIR}"
        sha256sum "$file" >> "${BACKUP_DIR}/checksums.txt"
    else
        log_warn "Cannot record checksum – file missing: $file"
    fi
}

# Restore all volumes from backup
restore_all() {
    local env="${1:-dev}"
    local timestamp="$2"
    
    if [ -z "$timestamp" ]; then
        log_error "Usage: $0 restore-all <env> <timestamp>"
        log_info "Available backups:"
        ls -1 "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-"*.tar.gz 2>/dev/null | sed 's/.*full-/  /' | sed 's/-.*//' | sort -u || echo "  (none)"
        exit 1
    fi
    
    local data_archive="${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}-data.tar.gz"
    local pg_archive="${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}-postgres.tar.gz"
    
    import_volume "$data_archive" "${VOLUME_PREFIX}-${env}-data"
    import_volume "$pg_archive" "${VOLUME_PREFIX}-${env}-postgres"
    
    log_info "Full restore complete for environment: $env"
}

# Clone environment (e.g., prod -> staging)
clone_env() {
    local source_env="$1"
    local target_env="$2"
    
    log_info "Cloning environment: $source_env -> $target_env"
    
    local temp_dir="${BACKUP_DIR}/temp-clone-${DATE_STAMP}"
    mkdir -p "$temp_dir"
    
    # Export source
    export_volume "${VOLUME_PREFIX}-${source_env}-data" "${temp_dir}/data.tar.gz"
    export_volume "${VOLUME_PREFIX}-${source_env}-postgres" "${temp_dir}/postgres.tar.gz"
    
    # Import to target
    import_volume "${temp_dir}/data.tar.gz" "${VOLUME_PREFIX}-${target_env}-data"
    import_volume "${temp_dir}/postgres.tar.gz" "${VOLUME_PREFIX}-${target_env}-postgres"
    
    rm -rf "$temp_dir"
    log_info "Clone complete"
}

# Delete volumes for environment
delete_volumes() {
    local env="${1:-dev}"
    
    log_warn "This will DELETE all data for environment: $env"
    read -p "Are you absolutely sure? Type 'DELETE' to confirm: " -r
    echo
    
    if [ "$REPLY" != "DELETE" ]; then
        log_error "Deletion cancelled"
        exit 1
    fi
    
    podman volume rm "${VOLUME_PREFIX}-${env}-data" 2>/dev/null || log_warn "Data volume not found"
    podman volume rm "${VOLUME_PREFIX}-${env}-postgres" 2>/dev/null || log_warn "PostgreSQL volume not found"
    
    log_info "Volumes deleted for environment: $env"
}

# Inspect volume details
inspect_volume() {
    local volume_name="$1"
    
    log_info "Volume details: $volume_name"
    podman volume inspect "$volume_name"
    
    log_info "Disk usage:"
    podman run --rm -v "$volume_name:/data:ro" docker.io/library/alpine:latest du -sh /data || true
    
    log_info "Top-level contents:"
    podman run --rm -v "$volume_name:/data:ro" docker.io/library/alpine:latest ls -lah /data || true
}

# PostgreSQL logical dump
dump_postgres() {
    local env="${1:-dev}"
    local output="${BACKUP_DIR}/postgres-dump-${env}-${DATE_STAMP}.sql.gz"

    mkdir -p "$BACKUP_DIR"

    log_info "Creating PostgreSQL logical dump for environment: $env"

    # This assumes PostgreSQL container is running
    local container_name="starexec-postgres"
    local db_name="${STAREXEC_DB_NAME:-starexec}"
    local db_user="${STAREXEC_DB_USER:-postgres}"
    local db_pass
    if [ -n "${STAREXEC_DB_PASSWORD_FILE:-}" ] && [ -f "${STAREXEC_DB_PASSWORD_FILE}" ]; then
        db_pass=$(cat "${STAREXEC_DB_PASSWORD_FILE}" | tr -d '\n')
    else
        db_pass="${STAREXEC_DB_PASSWORD:-starexec_password}"
    fi
    # If the PostgreSQL server is running in the container with the standard socket,
    # pass PGPASSWORD to the container process for non-interactive authentication.
    
    podman exec -e PGPASSWORD="$db_pass" "$container_name" \
        pg_dump -U "$db_user" -d "$db_name" | gzip > "$output"
    
    log_info "Postgres dump complete: $output"
    log_info "Size: $(du -h "$output" | cut -f1)"
}

# Show help
show_help() {
    cat <<EOF
Podman Volume Management for StarExec (PostgreSQL)

Usage: $0 <command> [arguments]

Commands:
  list                          List all StarExec volumes
  create <env>                  Create volumes for environment (default: dev)
  export <volume> [file]        Export volume to tar.gz archive
  import <archive> <volume>     Import archive into volume
  backup-all <env>              Backup all volumes for environment
  restore-all <env> <timestamp> Restore all volumes from backup
  clone <source-env> <target>   Clone one environment to another
  delete <env>                  Delete all volumes for environment
  inspect <volume>              Show volume details and contents
  dump-postgres <env>           Create PostgreSQL logical dump
  help                          Show this help message

Examples:
  # Setup new dev environment
  $0 create dev
  
  # Backup before major changes
  $0 backup-all dev
  
  # Share data with colleague
  $0 export starexec-dev-data
  # Send the .tar.gz file, then colleague runs:
  $0 import starexec-dev-data-*.tar.gz starexec-dev-data
  
  # Clone prod to staging for testing
  $0 clone prod staging
  
  # Restore from backup
  $0 restore-all dev 20250102-143022

Environment Variables:
    VOLUME_PREFIX             Volume name prefix (default: starexec)
    BACKUP_DIR                Backup directory (default: ./backups)
    STAREXEC_DB_USER          PostgreSQL user (default: postgres)
    STAREXEC_DB_PASSWORD      PostgreSQL password (default: starexec_password)
    STAREXEC_DB_PASSWORD_FILE File containing the PostgreSQL password (optional)
    STAREXEC_DB_NAME          Database name (default: starexec)

EOF
}

# Main command dispatcher
main() {
    local cmd="${1:-help}"
    shift || true
    
    case "$cmd" in
        list) list_volumes ;;
        create) create_volumes "$@" ;;
        export) export_volume "$@" ;;
        import) import_volume "$@" ;;
        backup-all) backup_all "$@" ;;
        restore-all) restore_all "$@" ;;
        clone) clone_env "$@" ;;
        delete) delete_volumes "$@" ;;
        inspect) inspect_volume "$@" ;;
        dump-postgres) dump_postgres "$@" ;;
        help|--help|-h) show_help ;;
        *) 
            log_error "Unknown command: $cmd"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
