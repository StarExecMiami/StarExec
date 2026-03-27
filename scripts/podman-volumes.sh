#!/usr/bin/env bash
# Podman volume management utilities for StarExec
# Backup, restore, and sharing of persistent data

set -euo pipefail

# Configuration
VOLUME_PREFIX="${VOLUME_PREFIX:-starexec}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
DATE_STAMP=$(date +%Y%m%d-%H%M%S)

# Podman command selection: allow override with PODMAN_CMD and auto-detect when unset
if [ -z "${PODMAN_CMD:-}" ]; then
    # 1. Detect sudo requirements
    PODMAN_BASE="podman"
    if ! podman system info >/dev/null 2>&1; then
        if sudo podman system info >/dev/null 2>&1; then
            PODMAN_BASE="sudo podman"
        fi
    elif ! podman system info 2>/dev/null | grep -q -E 'rootless[[:space:]]*[:=][[:space:]]*true'; then
        PODMAN_BASE="sudo podman"
    fi

    # 2. Detect OCI runtime availability
    AVAILABLE_RUNTIME=""
    if command -v crun >/dev/null 2>&1; then
        AVAILABLE_RUNTIME="crun"
    elif command -v runc >/dev/null 2>&1; then
        AVAILABLE_RUNTIME="runc"
    fi

    # 3. Detect if podman is broken (e.g. missing configured runtime)
    # If the base command doesn't work, try forcing the available runtime
    if ! ${PODMAN_BASE} info >/dev/null 2>&1 && [ -n "$AVAILABLE_RUNTIME" ]; then
        PODMAN_CMD="${PODMAN_BASE} --runtime=${AVAILABLE_RUNTIME}"
    else
        PODMAN_CMD="${PODMAN_BASE}"
    fi
fi

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
    ${PODMAN_CMD:-podman} volume ls --filter "name=${VOLUME_PREFIX}" --format "table {{.Name}}\t{{.Driver}}\t{{.Mountpoint}}"
}

# Create named volumes if they don't exist
# Creates all 5 volumes required by StarExec:
#   data, sandbox, backend, work  — application volumes (owned by app user)
#   postgres                      — PostgreSQL data dir (must be owned by UID 999 inside
#                                   the rootless user namespace, i.e. postgres:postgres)
#
# The UID 999 fix (CRÍTICO-2) is required because rootless Podman creates volume
# mountpoints owned by the *host* user, but the postgres:15 image runs as UID 999.
# Without the chown the PostgreSQL initdb fails with "could not change directory".
create_volumes() {
    local env="${1:-dev}"
    local data_vol="${VOLUME_PREFIX}-${env}-data"
    local sandbox_vol="${VOLUME_PREFIX}-${env}-sandbox"
    local backend_vol="${VOLUME_PREFIX}-${env}-backend"
    local work_vol="${VOLUME_PREFIX}-${env}-work"
    local pg_vol="${VOLUME_PREFIX}-${env}-postgres"

    log_info "Creating volumes for environment: ${env}"

    for vol in "$data_vol" "$sandbox_vol" "$backend_vol" "$work_vol"; do
        if ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
            log_warn "Volume $vol already exists (skipping)"
        else
            ${PODMAN_CMD:-podman} volume create "$vol"
            log_info "Created $vol"
        fi
    done

    # PostgreSQL volume — must be owned by UID/GID 999 inside the container namespace.
    # We use 'podman unshare chown' so the chown runs inside the rootless user namespace
    # (mapping host UID → container UID 999) without requiring sudo on the host.
    if ${PODMAN_CMD:-podman} volume exists "$pg_vol" 2>/dev/null; then
        log_warn "Volume $pg_vol already exists (skipping creation)"
    else
        ${PODMAN_CMD:-podman} volume create "$pg_vol"
        log_info "Created $pg_vol"
    fi

    # Always (re-)apply the UID 999 ownership on the postgres volume mountpoint.
    # This is idempotent and safe to run even if the volume pre-existed, since the
    # ownership may be wrong after a system reboot or volume migration.
    local pg_mountpoint
    pg_mountpoint=$(${PODMAN_CMD:-podman} volume inspect "$pg_vol" --format '{{.Mountpoint}}' 2>/dev/null || true)
    if [ -n "$pg_mountpoint" ]; then
        log_info "Fixing PostgreSQL volume ownership (UID/GID 999) at: $pg_mountpoint"
        # Detect rootful vs rootless to use the correct ownership tool.
        # In rootless mode, only 'podman unshare chown' can map host UIDs → container UIDs.
        # In rootful mode, direct chown works and 'podman unshare' is unnecessary overhead.
        local is_rootful=false
        if ${PODMAN_CMD:-podman} info --format '{{.Host.Security.Rootless}}' 2>/dev/null | grep -qi "false"; then
            is_rootful=true
        fi
        if [ "$is_rootful" = "true" ]; then
            if chown -R 999:999 "$pg_mountpoint"; then
                log_info "✓ PostgreSQL volume ownership set to 999:999 (rootful)"
            else
                log_error "Could not fix ownership for $pg_mountpoint"
                return 1
            fi
        else
            if ${PODMAN_CMD:-podman} unshare chown -R 999:999 "$pg_mountpoint"; then
                log_info "✓ PostgreSQL volume ownership set to 999:999 (rootless via unshare)"
            else
                log_error "Could not fix ownership for $pg_mountpoint"
                log_error "Rootless Podman requires 'podman unshare chown' — direct chown cannot map UIDs."
                log_error "Run manually: ${PODMAN_CMD:-podman} unshare chown -R 999:999 $pg_mountpoint"
                return 1
            fi
        fi
    else
        log_warn "Could not determine mountpoint for $pg_vol — skipping ownership fix"
    fi
}

# Export volume to tar archive (for sharing with colleagues)
export_volume() {
    local volume_name="$1"
    local output_file="${2:-${BACKUP_DIR}/${volume_name}-${DATE_STAMP}.tar.gz}"

    mkdir -p "$BACKUP_DIR"

    log_info "Exporting volume: $volume_name to $output_file"

    # Check if volume has any data before attempting export
    local file_count
    file_count=$(${PODMAN_CMD:-podman} run --rm -v "$volume_name:/data:ro" docker.io/library/alpine:latest sh -c 'find /data -mindepth 1 -maxdepth 1 2>/dev/null | wc -l' 2>/dev/null || echo 0)
    if [ "$file_count" -eq 0 ]; then
        log_info "Volume $volume_name appears to be empty (no files found). Creating placeholder archive to record emptiness."
        # Create a small temporary marker file and package it so the backup records the emptiness explicitly.
        tmpdir=$(mktemp -d)
        marker="$tmpdir/EMPTY_VOLUME.txt"
        echo "EMPTY VOLUME: $volume_name - created at $(date -u +"%Y-%m-%dT%H:%M:%SZ")" > "$marker"
        # Ensure marker has non-compressible content so compressed archive is not tiny
        if command -v head >/dev/null 2>&1; then
            head -c 4096 /dev/urandom >> "$marker" || true
        else
            dd if=/dev/urandom bs=1024 count=4 >> "$marker" 2>/dev/null || true
        fi
        tar -C "$tmpdir" -czf "$output_file" EMPTY_VOLUME.txt
        rm -rf "$tmpdir"
        log_info "Created placeholder archive for empty volume: $output_file"
        # Record the empty volume in an auxiliary empty-volumes file so the metadata consumer can see it
        if [ -n "${BACKUP_NAME:-}" ]; then
            echo "$volume_name" >> "${BACKUP_DIR}/${BACKUP_NAME}-emptyvols.txt" 2>/dev/null || true
        fi
        return 0
    fi

    # Use a temporary container to mount volume and create archive
    ${PODMAN_CMD:-podman} run --rm \
        -v "$volume_name:/data:ro" \
        -v "$(pwd)/$BACKUP_DIR:/backup" \
        docker.io/library/alpine:latest \
        tar czf "/backup/$(basename "$output_file")" -C /data .

        # Validate archive size: catch truncated/empty exports (threshold 45 bytes for minimal gzip)
        MIN_ARCHIVE_BYTES=45
        if command -v stat >/dev/null 2>&1; then
            actual_size=$(stat -c%s "$output_file" 2>/dev/null || stat -f%z "$output_file" 2>/dev/null || echo 0)
        else
            actual_size=0
        fi
        if [ "$actual_size" -lt "$MIN_ARCHIVE_BYTES" ]; then
            log_error "❌ Exported archive seems too small (${actual_size} bytes): $output_file"
            log_error "   This likely indicates an empty or failed export. Aborting backup."
            rm -f "$output_file" 2>/dev/null || true
            exit 1
        fi

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
    if ! ${PODMAN_CMD:-podman} volume exists "$volume_name" 2>/dev/null; then
        ${PODMAN_CMD:-podman} volume create "$volume_name"
        log_info "Created new volume: $volume_name"
    else
        log_warn "Volume $volume_name exists, will overwrite contents"
        printf "Continue? (y/N): "
        read -r REPLY
        if [ "$REPLY" != "y" ] && [ "$REPLY" != "Y" ]; then
            log_error "Import cancelled"
            exit 1
        fi
    fi

    # Extract archive into volume
    ${PODMAN_CMD:-podman} run --rm \
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
    "${VOLUME_PREFIX}-${env}-sandbox",
    "${VOLUME_PREFIX}-${env}-backend",
    "${VOLUME_PREFIX}-${env}-work",
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

    # Exporter can append to ${BACKUP_DIR}/${backup_name}-emptyvols.txt when a volume is empty
    export BACKUP_NAME="${backup_name}"

    if export_volume "${VOLUME_PREFIX}-${env}-data" "${BACKUP_DIR}/${backup_name}-data.tar.gz"; then
        record_checksum "${BACKUP_DIR}/${backup_name}-data.tar.gz"
    fi

    if export_volume "${VOLUME_PREFIX}-${env}-sandbox" "${BACKUP_DIR}/${backup_name}-sandbox.tar.gz"; then
        record_checksum "${BACKUP_DIR}/${backup_name}-sandbox.tar.gz"
    fi

    if export_volume "${VOLUME_PREFIX}-${env}-backend" "${BACKUP_DIR}/${backup_name}-backend.tar.gz"; then
        record_checksum "${BACKUP_DIR}/${backup_name}-backend.tar.gz"
    fi

    if export_volume "${VOLUME_PREFIX}-${env}-work" "${BACKUP_DIR}/${backup_name}-work.tar.gz"; then
        record_checksum "${BACKUP_DIR}/${backup_name}-work.tar.gz"
    fi

    if export_volume "${VOLUME_PREFIX}-${env}-postgres" "${BACKUP_DIR}/${backup_name}-postgres.tar.gz"; then
        record_checksum "${BACKUP_DIR}/${backup_name}-postgres.tar.gz"
    fi

    log_info "Full backup complete. Archive contents:"
    ls -lh "${BACKUP_DIR}/${backup_name}"*.tar.gz "${BACKUP_DIR}/${backup_name}"*.json 2>/dev/null || true

    # If any volumes were empty, produce a small manifest that references the metadata and lists empty volumes
    if [ -f "${BACKUP_DIR}/${backup_name}-emptyvols.txt" ]; then
        log_info "Recording empty volumes into manifest"
        manifest="${BACKUP_DIR}/${backup_name}-manifest.json"
        echo '{' > "$manifest"
        echo "  \"metadata_file\": \"$(basename "$metadata")\"," >> "$manifest"
        echo -n '  "empty_volumes": [' >> "$manifest"
        sep=""
        while IFS= read -r v; do
            [ -z "$v" ] && continue
            printf '%s"%s"' "$sep" "$v" >> "$manifest"
            sep=", "
        done < "${BACKUP_DIR}/${backup_name}-emptyvols.txt"
        echo ']' >> "$manifest"
        echo '}' >> "$manifest"
        log_info "Manifest written to: $manifest"
    fi

    log_info "Backup metadata and checksums recorded"
}

record_checksum() {
    local file="$1"
    if [ -f "$file" ]; then
        # skip suspiciously small files
        if command -v stat >/dev/null 2>&1; then
            fsize=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file" 2>/dev/null || echo 0)
        else
            fsize=0
        fi
        if [ "$fsize" -lt 1024 ]; then
            log_warn "Skipping checksum for suspiciously small file: $file ($fsize bytes)"
            return 0
        fi
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
        ls -1 "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-"*.tar.gz 2>/dev/null | sed 's/.*full-/  /' | sed 's/-data.tar.gz//' | sed 's/-postgres.tar.gz//' | sort -u || echo "  (none)"
        exit 1
    fi

    local data_archive="${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}-data.tar.gz"
    local pg_archive="${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}-postgres.tar.gz"

    # Verify checksums before restore (be tolerant of missing data archive)
    log_info "Verifying backup integrity..."
    if [ -f "$data_archive" ]; then
        verify_checksum "$data_archive" || exit 1
    else
        log_warn "Data archive missing for timestamp: $timestamp; data restore will be skipped."
    fi
    if [ -f "$pg_archive" ]; then
        verify_checksum "$pg_archive" || exit 1
    else
        log_error "Postgres archive not found: $pg_archive"
        exit 1
    fi

    # Import data archive if present and not a placeholder indicating an empty volume
    if [ -f "$data_archive" ]; then
        if tar -tzf "$data_archive" 2>/dev/null | grep -q '^EMPTY_VOLUME.txt$'; then
            log_warn "Data archive for $timestamp contains EMPTY_VOLUME marker; skipping data import to avoid writing marker into the volume."
        else
            import_volume "$data_archive" "${VOLUME_PREFIX}-${env}-data"
        fi
    else
        log_warn "No data archive to import for timestamp: $timestamp"
    fi

    # Import postgres archive (skip if it only contains empty-volume marker)
    if [ -f "$pg_archive" ]; then
        if tar -tzf "$pg_archive" 2>/dev/null | grep -q '^EMPTY_VOLUME.txt$'; then
            log_warn "Postgres archive for $timestamp contains EMPTY_VOLUME marker; skipping postgres import."
        else
            import_volume "$pg_archive" "${VOLUME_PREFIX}-${env}-postgres"
        fi
    fi

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
# Extended: verifies removal and optionally cleans hostPath locations (use with caution)
delete_volumes() {
    local env="${1:-dev}"
    local vols=(
        "${VOLUME_PREFIX}-${env}-data"
        "${VOLUME_PREFIX}-${env}-sandbox"
        "${VOLUME_PREFIX}-${env}-backend"
        "${VOLUME_PREFIX}-${env}-work"
        "${VOLUME_PREFIX}-${env}-postgres"
    )

    # Also consider non-environment-suffixed volumes created by older workflows.
    # Example: "starexec-data" and "starexec-postgres". We attempt to remove them
    # as part of a full cleanup, but only if they exist.
    local legacy_vols=(
        "${VOLUME_PREFIX}-data"
        "${VOLUME_PREFIX}-postgres"
    )

    # Optional hostPath cleanup (only when explicitly requested).
    # WARNING: This will remove host directories and is destructive.
    # To enable: set CLEAN_HOSTPATH=1 (and set CLEAN_HOSTPATH_FORCE=1 to skip prompt).
    local hostpath_cleanup="${CLEAN_HOSTPATH:-0}"
    local hostpath_force="${CLEAN_HOSTPATH_FORCE:-0}"
    # Default host paths used by the Helm/values templates for non-Podman deployments.
    local hostpath_data="${HOSTPATH_DATA:-/tmp/starexec-data}"
    local hostpath_postgres="${HOSTPATH_POSTGRES:-/tmp/postgres-data}"

    log_warn "This will DELETE all data for environment: $env"
    log_warn "This operation will also attempt to remove legacy volumes: ${legacy_vols[*]}"
    if [ "$hostpath_cleanup" = "1" ]; then
        log_warn "HostPath cleanup enabled: will also attempt to remove ${hostpath_data} and ${hostpath_postgres}"
    fi

    if [ "${FORCE:-0}" = "1" ]; then
        log_warn "FORCE=1 detected, skipping confirmation"
    else
        read -p "Are you absolutely sure? Type 'DELETE' to confirm: " -r
        echo

        if [ "$REPLY" != "DELETE" ]; then
            log_error "Deletion cancelled"
            exit 1
        fi
    fi

    # Remove the named volumes for this env
    for vol in "${vols[@]}"; do
        if ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
            if ${PODMAN_CMD:-podman} volume rm -f "$vol" 2>/dev/null; then
                log_info "Deleted volume: $vol"
            else
                log_warn "Failed to delete volume (in use?): $vol"
            fi
        else
            log_info "Volume already absent (skipping): $vol"
        fi
    done

    # Attempt to remove legacy (non-env suffixed) volumes as well
    for vol in "${legacy_vols[@]}"; do
        if ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
            if ${PODMAN_CMD:-podman} volume rm -f "$vol" 2>/dev/null; then
                log_info "Deleted legacy volume: $vol"
            else
                log_warn "Could not delete legacy volume (in use?): $vol"
            fi
        else
            log_info "Legacy volume already absent (skipping): $vol"
        fi
    done

    # Verification: check whether any expected volumes still exist and list possible holders
    local failed=0
    for vol in "${vols[@]}" "${legacy_vols[@]}"; do
        if ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
            log_error "❌ Volume still exists after deletion attempt: $vol"
            # Try to find containers using the volume (best-effort)
            local holders
            holders=$(${PODMAN_CMD:-podman} ps -a --format "{{.Id}}: {{.Names}}" --filter "volume=$vol" 2>/dev/null || true)
            if [ -n "$holders" ]; then
                log_error "  Containers referencing $vol:"
                echo "$holders" | sed 's/^/    /'
            else
                # Show some diagnostic info
                log_info "  Inspecting volume metadata for: $vol"
                ${PODMAN_CMD:-podman} volume inspect "$vol" 2>/dev/null || true
            fi
            failed=1
        fi
    done

    if [ $failed -ne 0 ]; then
        log_warn "One or more volumes could not be removed. They may be in use by containers or the system."
        log_warn "Try: podman ps -a | podman rm -f <container> and retry, or reboot to clear lingering mounts."
    fi

    # Optional hostPath cleanup (VERY DESTRUCTIVE)
    if [ "$hostpath_cleanup" = "1" ]; then
        if [ "$hostpath_force" = "1" ]; then
            log_warn "CLEAN_HOSTPATH_FORCE=1 detected, removing host paths without prompt"
            rm -rf -- "$hostpath_data" "$hostpath_postgres" 2>/dev/null || log_warn "Could not remove some host paths (check permissions)"
            log_info "HostPath cleanup attempted for: $hostpath_data, $hostpath_postgres"
        else
            echo ""
            printf "Also remove host paths '%s' and '%s'? This will permanently delete data (y/N): " "$hostpath_data" "$hostpath_postgres"
            read -r ans
            if [ "$ans" = "y" ] || [ "$ans" = "Y" ]; then
                rm -rf -- "$hostpath_data" "$hostpath_postgres" 2>/dev/null || log_warn "Could not remove some host paths (check permissions)"
                log_info "HostPath cleanup attempted for: $hostpath_data, $hostpath_postgres"
            else
                log_info "Skipped hostPath cleanup"
            fi
        fi
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
            rm -f "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}"-sandbox.tar.gz 2>/dev/null || true
            rm -f "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}"-backend.tar.gz 2>/dev/null || true
            rm -f "${BACKUP_DIR}/${VOLUME_PREFIX}-${env}-full-${timestamp}"-work.tar.gz 2>/dev/null || true
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
    ${PODMAN_CMD:-podman} volume inspect "$volume_name"

    log_info "Disk usage:"
    ${PODMAN_CMD:-podman} run --rm -v "$volume_name:/data:ro" docker.io/library/alpine:latest du -sh /data || true

    log_info "Top-level contents:"
    ${PODMAN_CMD:-podman} run --rm -v "$volume_name:/data:ro" docker.io/library/alpine:latest ls -lah /data || true
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
        ${PODMAN_CMD:-podman} cp "${STAREXEC_DB_PASSWORD_FILE}" "${container_name}:${temp_pass}" || {
            log_error "Failed to copy password file to container"
            exit 1
        }

        # Create .pgpassfile format inside container (secure - no host-side command substitution)
        ${PODMAN_CMD:-podman} exec "$container_name" bash -c '
            # Read password from temp file and construct pgpass inside container
            password=$(cat "'"${temp_pass}"'" | tr -d "\n")
            echo "localhost:5432:'"${db_name}"':'"${db_user}"':$password" > /tmp/.pgpass_formatted
            chmod 600 /tmp/.pgpass_formatted
            rm -f "'"${temp_pass}"'"
        ' || {
            ${PODMAN_CMD:-podman} exec "$container_name" rm -f /tmp/.pgpass_formatted 2>/dev/null || true
            log_error "Failed to format password file"
            exit 1
        }

        # Use password file (no password in env or args)
        ${PODMAN_CMD:-podman} exec "$container_name" bash -c "
            PGPASSFILE='/tmp/.pgpass_formatted' pg_dump -h localhost -U '${db_user}' -d '${db_name}' | gzip
        " > "$output" || {
            ${PODMAN_CMD:-podman} exec "$container_name" rm -f /tmp/.pgpass_formatted 2>/dev/null || true
            log_error "PostgreSQL dump failed"
            exit 1
        }

        # Clean up
        ${PODMAN_CMD:-podman} exec "$container_name" rm -f /tmp/.pgpass_formatted 2>/dev/null || true
    else
        # Fallback: use PGPASSWORD (less secure but works for dev)
        log_warn "Using PGPASSWORD environment variable (less secure)"
        log_warn "Consider setting STAREXEC_DB_PASSWORD_FILE for better security"
        local db_pass="${STAREXEC_DB_PASSWORD:-starexec_password}"
        ${PODMAN_CMD:-podman} exec -e PGPASSWORD="$db_pass" "$container_name" \
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
    local vols=(
        "${VOLUME_PREFIX}-${env}-data"
        "${VOLUME_PREFIX}-${env}-sandbox"
        "${VOLUME_PREFIX}-${env}-backend"
        "${VOLUME_PREFIX}-${env}-work"
        "${VOLUME_PREFIX}-${env}-postgres"
    )
    for vol in "${vols[@]}"; do
        if ! ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
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
    for vol in "${vols[@]}"; do
        if ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
            if ${PODMAN_CMD:-podman} run --rm -v "$vol:/test:ro" docker.io/library/alpine:latest test -d /test 2>/dev/null; then
                log_info "✅ Volume mountable: $vol"
                
                # Special check for data volume items
                if [[ "$vol" == *"-data" ]]; then
                    if ${PODMAN_CMD:-podman} run --rm -v "$vol:/test:ro" docker.io/library/alpine:latest test -d /test/Solvers && \
                       ${PODMAN_CMD:-podman} run --rm -v "$vol:/test:ro" docker.io/library/alpine:latest test -d /test/Benchmarks; then
                         log_info "   ✅ Data volume structure verified (Solvers/Benchmarks found)"
                    else
                         log_warn "   ⚠️  Data volume missing Solvers or Benchmarks directories!"
                         log_warn "       This might indicate an incomplete backup or fresh install."
                         warnings=$((warnings + 1))
                    fi
                fi
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

    for vol in "${vols[@]}"; do
        if ${PODMAN_CMD:-podman} volume exists "$vol" 2>/dev/null; then
            local size_bytes=$(${PODMAN_CMD:-podman} run --rm -v "$vol:/data:ro" docker.io/library/alpine:latest \
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

Notes:
    - Empty volumes are recorded using a small placeholder archive and listed in a manifest file
      ("<backup>-manifest.json" contains an "empty_volumes" array). This ensures we can safely
      detect that a volume was intentionally empty at backup time.
    - During restore, placeholder archives that contain an "EMPTY_VOLUME.txt" marker are detected
      and skipped to avoid writing marker files into live volumes.
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
