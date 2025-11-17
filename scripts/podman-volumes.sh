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
    
    # Ensure backup directory exists and is writable
    if ! mkdir -p "${BACKUP_DIR}" 2>/dev/null; then
        log_error "Failed to create backup directory: ${BACKUP_DIR}"
        exit 1
    fi
    
    if ! touch "${BACKUP_DIR}/.write_test" 2>/dev/null; then
        log_error "Backup directory is not writable: ${BACKUP_DIR}"
        rm -f "${BACKUP_DIR}/.write_test" 2>/dev/null
        exit 1
    fi
    rm -f "${BACKUP_DIR}/.write_test"
    
    # Create metadata file first
    local metadata="${BACKUP_DIR}/${backup_name}-metadata.json"
    
    # Get StarExec version if available
    local starexec_version="unknown"
    if [ -f "pom.xml" ]; then
        starexec_version=$(grep -m 1 "<version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/' 2>/dev/null || echo "unknown")
    fi
    
    # Create metadata with error checking
    if ! cat > "$metadata" <<EOF
{
  "timestamp": "${DATE_STAMP}",
  "environment": "${env}",
  "hostname": "$(hostname)",
  "user": "$(whoami)",
  "volumes": [
    "${VOLUME_PREFIX}-${env}-data",
    "${VOLUME_PREFIX}-${env}-postgres"
  ],
  "starexec_version": "${starexec_version}",
  "backup_tool_version": "1.0.0"
}
EOF
    then
        log_error "Failed to create metadata file: $metadata"
        exit 1
    fi
    
    log_info "Created backup metadata: $metadata"
    
    export_volume "${VOLUME_PREFIX}-${env}-data" "${BACKUP_DIR}/${backup_name}-data.tar.gz"
    export_volume "${VOLUME_PREFIX}-${env}-postgres" "${BACKUP_DIR}/${backup_name}-postgres.tar.gz"
    
    log_info "Full backup complete. Archive contents:"
    ls -lh "${BACKUP_DIR}/${backup_name}"*.tar.gz "${BACKUP_DIR}/${backup_name}"*.json 2>/dev/null || true

    record_checksum "${BACKUP_DIR}/${backup_name}-data.tar.gz"
    record_checksum "${BACKUP_DIR}/${backup_name}-postgres.tar.gz"
    
    log_info "Backup metadata and checksums recorded"
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

# Verify checksum of backup file
verify_checksum() {
    local file="$1"
    local checksum_file="${BACKUP_DIR}/checksums.txt"
    
    # Verify file exists first
    if [ ! -f "$file" ]; then
        log_error "❌ Archive file not found: $file"
        return 1
    fi
    
    # Check file is readable
    if [ ! -r "$file" ]; then
        log_error "❌ Archive file is not readable: $file"
        return 1
    fi
    
    # Check file size (catch truncated files)
    local file_size
    if command -v stat >/dev/null 2>&1; then
        file_size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file" 2>/dev/null || echo "0")
        if [ "$file_size" -lt 1024 ]; then
            log_error "❌ Archive file suspiciously small (${file_size} bytes): $file"
            return 1
        fi
    fi
    
    if [ ! -f "$checksum_file" ]; then
        log_warn "No checksum file found - skipping verification"
        log_warn "This backup may not be verifiable"
        return 0
    fi
    
    local expected=$(grep "$(basename "$file")" "$checksum_file" | tail -1 | awk '{print $1}')
    if [ -z "$expected" ]; then
        log_warn "No checksum found for $(basename "$file")"
        return 0
    fi
    
    local actual=$(sha256sum "$file" | awk '{print $1}')
    
    if [ "$expected" != "$actual" ]; then
        log_error "❌ CHECKSUM MISMATCH for $file"
        log_error "   Expected: $expected"
        log_error "   Got:      $actual"
        log_error "   File may be corrupted or tampered with!"
        return 1
    fi
    
    log_info "✅ Checksum verified: $(basename "$file")"
    return 0
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
    
    # Verify checksums before restore
    log_info "Verifying backup integrity..."
    verify_checksum "$data_archive" || exit 1
    verify_checksum "$pg_archive" || exit 1
    
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
    local data_vol="${VOLUME_PREFIX}-${env}-data"
    local pg_vol="${VOLUME_PREFIX}-${env}-postgres"
    
    log_warn "This will DELETE all data for environment: $env"
    read -p "Are you absolutely sure? Type 'DELETE' to confirm: " -r
    echo
    
    if [ "$REPLY" != "DELETE" ]; then
        log_error "Deletion cancelled"
        exit 1
    fi
    
    # Delete data volume with improved feedback
    if podman volume exists "$data_vol" 2>/dev/null; then
        podman volume rm -f "$data_vol" && log_info "Deleted volume: $data_vol"
    else
        log_info "Data volume already absent (skipping): $data_vol"
    fi
    
    # Delete postgres volume with improved feedback
    if podman volume exists "$pg_vol" 2>/dev/null; then
        podman volume rm -f "$pg_vol" && log_info "Deleted volume: $pg_vol"
    else
        log_info "PostgreSQL volume already absent (skipping): $pg_vol"
    fi
    
    log_info "Volume cleanup complete for environment: $env"
}

# Cleanup old backups (retention policy)
cleanup_old_backups() {
    local env="${1:-dev}"
    local keep_count="${2:-10}"  # Keep last 10 backups by default
    
    log_info "Cleaning up old backups for environment: $env"
    log_info "Retention policy: Keep last $keep_count backups"
    
    # List all backup sets sorted by timestamp (newest first)
    local backups=$(ls -1 "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-"*-data.tar.gz 2>/dev/null \
        | sed 's/.*full-//' \
        | sed 's/-data.*//' \
        | sort -ru)
    
    if [ -z "$backups" ]; then
        log_info "No backups found for environment: $env"
        return 0
    fi
    
    local count=0
    local deleted=0
    
    while IFS= read -r timestamp; do
        count=$((count + 1))
        if [ $count -gt $keep_count ]; then
            log_info "Removing backup set: $timestamp"
            rm -f "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}"-data.tar.gz 2>/dev/null || true
            rm -f "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}"-postgres.tar.gz 2>/dev/null || true
            rm -f "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}"-metadata.json 2>/dev/null || true
            # Clean up corresponding checksums (portable across platforms)
            if [ -f "${BACKUP_DIR}/checksums.txt" ]; then
                grep -v "full-${timestamp}" "${BACKUP_DIR}/checksums.txt" > "${BACKUP_DIR}/checksums.txt.tmp" 2>/dev/null || true
                mv "${BACKUP_DIR}/checksums.txt.tmp" "${BACKUP_DIR}/checksums.txt" 2>/dev/null || true
            fi
            deleted=$((deleted + 1))
        fi
    done <<< "$backups"
    
    if [ $deleted -eq 0 ]; then
        log_info "No old backups to remove (have $count, keeping $keep_count)"
    else
        log_info "Cleanup complete. Removed $deleted backup set(s), kept $keep_count most recent"
    fi
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
    local container_name="starexec-postgres"

    mkdir -p "$BACKUP_DIR"

    log_info "Creating PostgreSQL logical dump for environment: $env"

    # This assumes PostgreSQL container is running
    local db_name="${STAREXEC_DB_NAME:-starexec}"
    local db_user="${STAREXEC_DB_USER:-postgres}"
    
    # Use password file inside container (more secure)
    if [ -n "${STAREXEC_DB_PASSWORD_FILE:-}" ]; then
        if [ ! -f "${STAREXEC_DB_PASSWORD_FILE}" ]; then
            log_error "Password file not found: ${STAREXEC_DB_PASSWORD_FILE}"
            exit 1
        fi
        
        # Check file permissions (should be 0600 or 0400)
        local perms
        if command -v stat >/dev/null 2>&1; then
            perms=$(stat -c "%a" "${STAREXEC_DB_PASSWORD_FILE}" 2>/dev/null || stat -f "%Lp" "${STAREXEC_DB_PASSWORD_FILE}" 2>/dev/null || echo "000")
            if [ "$perms" != "600" ] && [ "$perms" != "400" ]; then
                log_warn "Insecure password file permissions: $perms (should be 600 or 400)"
            fi
        fi
        
        # Copy password file into container temporarily
        local temp_pass="/tmp/.pgpass.$$"
        podman cp "${STAREXEC_DB_PASSWORD_FILE}" "${container_name}:${temp_pass}" || {
            log_error "Failed to copy password file to container"
            exit 1
        }
        
        # Create .pgpassfile format inside container (secure - no host-side command substitution)
        podman exec "$container_name" bash -c '
            # Read password from temp file and construct pgpass inside container
            password=$(cat "'"${temp_pass}"'" | tr -d "\n")
            echo "localhost:5432:'"${db_name}"':'"${db_user}"':$password" > /tmp/.pgpass_formatted
            chmod 600 /tmp/.pgpass_formatted
            rm -f "'"${temp_pass}"'"
        ' || {
            podman exec "$container_name" rm -f /tmp/.pgpass_formatted 2>/dev/null || true
            log_error "Failed to format password file"
            exit 1
        }
        
        # Use password file (no password in env or args)
        podman exec "$container_name" bash -c "
            PGPASSFILE='/tmp/.pgpass_formatted' pg_dump -h localhost -U '${db_user}' -d '${db_name}' | gzip
        " > "$output" || {
            podman exec "$container_name" rm -f /tmp/.pgpass_formatted 2>/dev/null || true
            log_error "PostgreSQL dump failed"
            exit 1
        }
        
        # Clean up
        podman exec "$container_name" rm -f /tmp/.pgpass_formatted 2>/dev/null || true
    else
        # Fallback: use PGPASSWORD (less secure but works for dev)
        log_warn "Using PGPASSWORD environment variable (less secure)"
        log_warn "Consider setting STAREXEC_DB_PASSWORD_FILE for better security"
        local db_pass="${STAREXEC_DB_PASSWORD:-starexec_password}"
        podman exec -e PGPASSWORD="$db_pass" "$container_name" \
            pg_dump -U "$db_user" -d "$db_name" | gzip > "$output"
    fi
    
    log_info "Postgres dump complete: $output"
    log_info "Size: $(du -h "$output" | cut -f1)"
    
    # Record checksum for backup
    record_checksum "$output"
}

# Health check for volumes
health_check() {
    local env="${1:-dev}"
    local errors=0
    local warnings=0
    
    log_info "Running health checks for environment: $env"
    echo ""
    
    # Check volume existence
    log_info "=== Volume Existence Check ==="
    for vol in "${VOLUME_PREFIX}-${env}-data" "${VOLUME_PREFIX}-${env}-postgres"; do
        if ! podman volume exists "$vol" 2>/dev/null; then
            log_error "❌ Volume missing: $vol"
            log_error "   Fix: make volumes-create ENV=$env"
            errors=$((errors + 1))
        else
            log_info "✅ Volume exists: $vol"
        fi
    done
    echo ""
    
    # Check volume mountability
    log_info "=== Volume Mount Check ==="
    for vol in "${VOLUME_PREFIX}-${env}-data" "${VOLUME_PREFIX}-${env}-postgres"; do
        if podman volume exists "$vol" 2>/dev/null; then
            if podman run --rm -v "$vol:/test:ro" docker.io/library/alpine:latest test -d /test 2>/dev/null; then
                log_info "✅ Volume mountable: $vol"
            else
                log_error "❌ Volume mount failed: $vol"
                log_error "   Volume may be corrupted"
                log_error "   Fix: make volumes-restore ENV=$env (from backup)"
                errors=$((errors + 1))
            fi
        fi
    done
    echo ""
    
    # Check volume sizes
    log_info "=== Volume Size Check ==="
    local warn_threshold_gb=80
    local critical_threshold_gb=95
    
    for vol in "${VOLUME_PREFIX}-${env}-data" "${VOLUME_PREFIX}-${env}-postgres"; do
        if podman volume exists "$vol" 2>/dev/null; then
            local size_bytes=$(podman run --rm -v "$vol:/data:ro" docker.io/library/alpine:latest \
                du -sb /data 2>/dev/null | awk '{print $1}')
            local size_gb=$((size_bytes / 1024 / 1024 / 1024))
            
            log_info "📊 Volume: $vol - Size: ${size_gb}GB"
            
            if [ $size_gb -gt $critical_threshold_gb ]; then
                log_error "  ⚠️  CRITICAL: Volume size exceeds ${critical_threshold_gb}GB"
                log_error "  Action required: Immediate backup and cleanup"
                log_error "  Run: make volumes-backup ENV=$env"
                errors=$((errors + 1))
            elif [ $size_gb -gt $warn_threshold_gb ]; then
                log_warn "  ⚠️  WARNING: Volume size exceeds ${warn_threshold_gb}GB threshold"
                log_warn "  Recommended: make volumes-backup ENV=$env"
                warnings=$((warnings + 1))
            else
                log_info "  ✅ Size within normal range"
            fi
        fi
    done
    echo ""
    
    # Check last backup age
    log_info "=== Backup Status Check ==="
    local latest_backup=$(ls -1t "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-"*-data.tar.gz 2>/dev/null | head -1)
    
    if [ -z "$latest_backup" ]; then
        log_warn "⚠️  No backups found for environment: $env"
        log_warn "   Recommended: make volumes-backup ENV=$env"
        warnings=$((warnings + 1))
    else
        local backup_age_days
        if command -v stat >/dev/null 2>&1; then
            backup_age_days=$(( ($(date +%s) - $(stat -c%Y "$latest_backup" 2>/dev/null || stat -f%m "$latest_backup")) / 86400 ))
        else
            backup_age_days=0
        fi
        
        if [ $backup_age_days -gt 7 ]; then
            log_warn "⚠️  Latest backup is ${backup_age_days} days old"
            log_warn "   Recommended: make volumes-backup ENV=$env"
            warnings=$((warnings + 1))
        else
            log_info "✅ Recent backup found (${backup_age_days} days old)"
        fi
    fi
    echo ""
    
    # Summary
    log_info "=== Health Check Summary ==="
    if [ $errors -eq 0 ] && [ $warnings -eq 0 ]; then
        log_info "✅ All health checks passed - system is healthy"
        return 0
    elif [ $errors -eq 0 ]; then
        log_warn "⚠️  Health check completed with $warnings warning(s)"
        log_warn "System is operational but attention recommended"
        return 0
    else
        log_error "❌ Health check FAILED with $errors error(s) and $warnings warning(s)"
        log_error "System requires immediate attention"
        return 1
    fi
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
  cleanup-backups <env> [keep]  Remove old backups (default: keep last 10)
  clone <source-env> <target>   Clone one environment to another
  delete <env>                  Delete all volumes for environment
  health-check <env>            Run volume health checks
  inspect <volume>              Show volume details and contents
  dump-postgres <env>           Create PostgreSQL logical dump
  help                          Show this help message

Examples:
  # Setup new dev environment
  $0 create dev
  
  # Backup before major changes
  $0 backup-all dev
  
  # Clean up old backups (keep last 5)
  $0 cleanup-backups dev 5
  
  # Check volume health
  $0 health-check dev
  
  # Share data with colleague
  $0 export starexec-dev-data
  # Send the .tar.gz file, then colleague runs:
  $0 import starexec-dev-data-*.tar.gz starexec-dev-data
  
  # Clone prod to staging for testing
  $0 clone prod staging
  
  # Restore from backup (with integrity verification)
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
        cleanup-backups) cleanup_old_backups "$@" ;;
        clone) clone_env "$@" ;;
        delete) delete_volumes "$@" ;;
        health-check) health_check "$@" ;;
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
