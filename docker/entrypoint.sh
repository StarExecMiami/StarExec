#!/bin/bash
set -euo pipefail

echo "StarExec Container Starting..."
echo "=========================================="

# ============================================================================
# PODMAN SOCKET PERMISSION VALIDATION
# ============================================================================
# This section validates Podman socket permissions when using DooD
# (Docker-outside-of-Docker) backend. Permission issues are the most
# common cause of backend initialization failures.
#
# What this does:
#   1. Checks if the socket exists and is accessible
#   2. If not accessible, detects the root cause (UID mismatch, group membership)
#   3. Provides diagnostic guidance for manual remediation
# ============================================================================

# ANSI Colors
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

validate_podman_socket() {
    local socket_path="${1:-/var/run/docker.sock}"
    
    # Only proceed if socket exists
    if [ ! -e "$socket_path" ]; then
        return 0  # Socket doesn't exist, let backend handle it
    fi
    
    # Check if socket is readable by current user
    if [ -r "$socket_path" ]; then
        echo -e "  ${GREEN}[OK]${RESET} Podman socket is accessible: $socket_path"
        return 0
    fi
    
    # Socket exists but not readable - detect root cause
    echo -e "  ${YELLOW}[WARN]${RESET} Podman socket exists but is not readable: $socket_path"
    echo "         Analyzing socket permissions..."
    
    # For sockets in user runtime directories (e.g., /run/user/1000/...)
    # check for UID mismatches between container user and socket owner
    if [[ "$socket_path" == /run/user/* ]]; then
        # Extract the UID from the socket path (e.g., /run/user/1000/podman/podman.sock)
        socket_uid=$(echo "$socket_path" | awk -F/ '{print $4}')
        current_uid=$(id -u)
        
        if [ "$socket_uid" != "$current_uid" ]; then
            echo "         UID mismatch: container is $current_uid, socket is $socket_uid"
            echo "         This can happen when running in different user contexts."
            echo "         To fix:"
            echo "           1. Find the Podman socket: find /run/user -name 'podman.sock' 2>/dev/null"
            echo "           2. Check owner/permissions: ls -la <socket_path>"
            echo "           3. Update docker-compose to use the correct socket path"
            echo "              and inject the appropriate group via group_add"
            return 1
        fi
    fi
    
    # Check if current user is in the socket's group
    socket_group=$(stat -c '%G' "$socket_path" 2>/dev/null || echo "")
    if [ -n "$socket_group" ]; then
        # Check if user is already in the group
        if [ "$(id -g)" = "$socket_group" ] || id -G | grep -q "\b$socket_group\b"; then
            echo "         Current user is already in socket group: $socket_group"
            return 0
        fi
        
        # User is not in the socket group
        echo -e "         ${RED}[FAIL]${RESET} Current user is not in socket group: $socket_group"
        echo "         Fix: Add the container user to this group via docker-compose"
        echo "              group_add: [\"$socket_group\"]"
        echo "              or ensure the container runs with a user that has access"
    fi
    
    return 0
}

# Determine socket path from environment or use default
PODMAN_SOCKET="${STAREXEC_CONTAINER_SOCKET:-}"
if [ -z "$PODMAN_SOCKET" ]; then
    # Check common socket locations
    if [ -e "/var/run/docker.sock" ]; then
        PODMAN_SOCKET="/var/run/docker.sock"
    elif [ -e "/run/podman/podman.sock" ]; then
        PODMAN_SOCKET="/run/podman/podman.sock"
    elif [ -e "/run/user/1000/podman/podman.sock" ]; then
        PODMAN_SOCKET="/run/user/1000/podman/podman.sock"
    fi
fi

# Validate socket permissions if configured
if [ -n "$PODMAN_SOCKET" ] && [ "${STAREXEC_BACKEND_TYPE:-docker}" = "podman" ]; then
    echo "Validating Podman socket access..."
    validate_podman_socket "$PODMAN_SOCKET" || {
        echo -e "${YELLOW}[WARN]${RESET} Socket permission check failed, but continuing startup."
        echo "         Backend initialization may fail if socket is not accessible."
    }
    echo ""
fi

# Display environment for debugging (show defaults)
echo "Environment Configuration:"
echo "  STAREXEC_DB_HOST=${STAREXEC_DB_HOST:-localhost}"
echo "  STAREXEC_DB_PORT=${STAREXEC_DB_PORT:-5432}"
echo "  STAREXEC_DB_NAME=${STAREXEC_DB_NAME:-starexec}"
echo "  STAREXEC_DB_USER=${STAREXEC_DB_USER:-root}"
echo "  CATALINA_HOME=${CATALINA_HOME}"
echo ""

# Path to context template and target
TEMPLATE_FILE="${CATALINA_HOME}/webapps/starexec/META-INF/context.xml.template"
CONTEXT_FILE="${CATALINA_HOME}/webapps/starexec/META-INF/context.xml"

# Configure context.xml from template if present and do safety checks
configure_context() {
    # Determine DB values (explicit env or defaults)
    DB_HOST="${STAREXEC_DB_HOST:-localhost}"
    DB_PORT="${STAREXEC_DB_PORT:-5432}"
    DB_NAME="${STAREXEC_DB_NAME:-starexec}"
    DB_USER="${STAREXEC_DB_USER:-starexec}"
    DB_PASSWORD="${STAREXEC_DB_PASSWORD:-}"

    # Validate required environment variables before starting Tomcat
    # This ensures fail-fast behavior for misconfigurations
    for var in STAREXEC_DB_HOST STAREXEC_DB_USER STAREXEC_DB_PASSWORD STAREXEC_DB_NAME; do
        if [ -z "${!var}" ]; then
            echo "ERROR: Required environment variable $var is not set"
            echo "Please provide database configuration via environment variables"
            exit 1
        fi
    done

    echo "Database configuration validated:"
    echo "  Host: $DB_HOST"
    echo "  Port: $DB_PORT"
    echo "  Database: $DB_NAME"
    echo "  User: $DB_USER"

    # If DB_HOST is localhost (or empty) this may be OK in Podman local mode but
    # is usually wrong in Kubernetes. Use the presence of KUBERNETES_SERVICE_HOST
    # env var to detect k8s. If running in Kubernetes (or STAREXEC_ENV=prod) warn
    # and optionally exit when STRICT_DB_HOST=true.
    if [ -z "${STAREXEC_DB_HOST+x}" ] || [ "$DB_HOST" = "localhost" ]; then
        if [ -n "${KUBERNETES_SERVICE_HOST:-}" ] || [ "${STAREXEC_ENV:-}" = "prod" ]; then
            cat <<-WARN
            ⚠️  Notice: STAREXEC_DB_HOST is set to '${DB_HOST}' while running in a
            Kubernetes/production environment. This commonly causes Tomcat to try
            to connect to a database inside the application container rather than
            the dedicated Postgres service.

            Recommended fixes:
             - In Kubernetes set STAREXEC_DB_HOST to your Postgres service name
               (example: 'postgres' or 'starexec-postgres' when using Helm).
             - Use Kubernetes Secrets for DB credentials instead of embedding them.

            To override and continue anyway, set STAREXEC_DB_HOST explicitly.
WARN

            if [ "${STRICT_DB_HOST:-false}" = "true" ]; then
                echo "STRICT_DB_HOST=true and DB host is '${DB_HOST}' — exiting with error."
                exit 1
            fi
        else
            echo "Note: STAREXEC_DB_HOST='${DB_HOST}' — running in local/podman mode, localhost may be expected."
        fi
    fi

    # Optional quick TCP connectivity check to the DB host/port. Will retry a few
    # times and will fail the container start if STRICT_DB_CONNECTION=true.
    test_db_connection() {
        local host="$1"; local port="$2"; local tries=5; local i=0
        while [ $i -lt $tries ]; do
            # /dev/tcp is a bash feature; try to open a TCP connection
            if bash -c "</dev/tcp/${host}/${port}" >/dev/null 2>&1; then
                echo "✓ Database TCP connection to ${host}:${port} successful"
                return 0
            fi
            i=$((i+1))
            echo "Waiting for DB ${host}:${port} (attempt $i/$tries)..."
            sleep 1
        done
        return 1
    }

    if [ -n "${STRICT_DB_CONNECTION:-}" ]; then
        if ! test_db_connection "$DB_HOST" "$DB_PORT"; then
            echo "ERROR: Could not establish TCP connection to DB ${DB_HOST}:${DB_PORT}"
            if [ "${STRICT_DB_CONNECTION}" = "true" ]; then
                echo "STRICT_DB_CONNECTION=true — exiting to avoid silent misconfiguration."
                exit 1
            fi
        fi
    fi
}

# If SKIP_MIGRATIONS is set at container start we intentionally skip the
# strict DB validation performed in configure_context. This allows emergency
# starts (when migrations are intentionally skipped) without failing the
# container due to missing DB credentials. The later migration guard will
# still prevent running Flyway when SKIP_MIGRATIONS is not set.
if [ "${SKIP_MIGRATIONS:-false}" = "true" ]; then
    echo ""
    echo "[MIGRATION][WARN] ⚠️  SKIP_MIGRATIONS=true detected at startup"
    echo "[MIGRATION][WARN]    Skipping DB validation and Flyway migrations per configuration"
    echo ""
else
    # Configure context.xml from template if present and do safety checks
    configure_context
    echo ""
fi

# ============================================================================
# TOMCAT CONTEXT.XML PROPERTY SUBSTITUTION
# ============================================================================
# Tomcat 9.0.x does not natively interpolate ${PROPERTY} in context.xml
# Resource definitions (unlike server.xml or web.xml). While setenv.sh passes
# DB config as JVM system properties (-DSTAREXEC_DB_HOST, etc.), Tomcat's
# Digester doesn't substitute them in context.xml without explicitly enabling
# PropertySource or using a custom Context listener.
#
# To avoid adding custom Java code, we perform a simple one-time substitution
# at container startup using sed. This is safe because:
#   1. Runs once before Tomcat starts (idempotent)
#   2. WAR is extracted to a writable location
#   3. Container restarts get fresh WAR extraction
# ============================================================================
CONTEXT_XML_FILE="${CATALINA_HOME}/webapps/starexec/META-INF/context.xml"
if [ -f "$CONTEXT_XML_FILE" ]; then
    # Only substitute if placeholders still exist (idempotent check)
    if grep -q '${STAREXEC_DB_HOST}' "$CONTEXT_XML_FILE"; then
        echo "Substituting DB properties in context.xml..."
        # Create temporary file with substituted values
        TMP_CONTEXT=$(mktemp)
        sed -e "s|\${STAREXEC_DB_HOST}|${STAREXEC_DB_HOST}|g" \
            -e "s|\${STAREXEC_DB_PORT}|${STAREXEC_DB_PORT}|g" \
            -e "s|\${STAREXEC_DB_NAME}|${STAREXEC_DB_NAME}|g" \
            -e "s|\${STAREXEC_DB_USER}|${STAREXEC_DB_USER}|g" \
            -e "s|\${STAREXEC_DB_PASSWORD}|${STAREXEC_DB_PASSWORD}|g" \
            "$CONTEXT_XML_FILE" > "$TMP_CONTEXT"
        # Replace original with substituted version
        mv "$TMP_CONTEXT" "$CONTEXT_XML_FILE"
        echo "✓ DB properties substituted in context.xml"
    fi
    echo ""
fi

# ============================================================================
# DATABASE MIGRATION FUNCTION
# ============================================================================
# Purpose: Execute Flyway migrations before application starts.
# Exit codes returned by this function:
#   0 = Success
#   1 = Configuration error (missing credentials)
#   2 = Database connection timeout / unreachable
#   3 = Migration execution failure (Flyway non-zero)
# ============================================================================

run_database_migrations() {
    local function_start_time
    function_start_time=$(date +%s)

    echo ""
    echo "============================================================================"
    echo "[MIGRATION][INIT] DATABASE MIGRATION INITIALIZATION"
    echo "Timestamp: $(date -Iseconds)"
    echo "============================================================================"
    echo ""

    # -------------------------------------------------------------------------
    # STEP 1: Configuration Extraction & Validation
    # -------------------------------------------------------------------------
    echo "[MIGRATION][1/5] Validating database configuration..."

    # Re-read envs explicitly (do not rely on configure_context function scope)
    local DB_HOST="${STAREXEC_DB_HOST:-localhost}"
    local DB_PORT="${STAREXEC_DB_PORT:-5432}"
    local DB_NAME="${STAREXEC_DB_NAME:-starexec}"
    local DB_USER="${STAREXEC_DB_USER:-starexec}"
    local DB_PASSWORD="${STAREXEC_DB_PASSWORD:-}"

    # Validate required credentials
    if [ -z "$DB_PASSWORD" ]; then
        echo "[MIGRATION][ERROR] ❌ STAREXEC_DB_PASSWORD environment variable is not set"
        echo "[MIGRATION][ERROR]    Cannot proceed with migrations without database credentials"
        echo "[MIGRATION][ERROR]    Exit code: 1"
        return 1
    fi

    if [ -z "$DB_USER" ] || [ -z "$DB_NAME" ]; then
        echo "[MIGRATION][ERROR] ❌ Required database configuration missing"
        echo "[MIGRATION][ERROR]    DB_USER: ${DB_USER:-NOT_SET}"
        echo "[MIGRATION][ERROR]    DB_NAME: ${DB_NAME:-NOT_SET}"
        echo "[MIGRATION][ERROR]    Exit code: 1"
        return 1
    fi

    echo "[MIGRATION][INFO] ✅ Configuration validated:"
    echo "[MIGRATION][INFO]    Host: $DB_HOST"
    echo "[MIGRATION][INFO]    Port: $DB_PORT"
    echo "[MIGRATION][INFO]    Database: $DB_NAME"
    echo "[MIGRATION][INFO]    User: $DB_USER"
    echo "[MIGRATION][INFO]    Password: ***REDACTED***"
    echo ""

    # Construct JDBC URL for Flyway
    local JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

    # -------------------------------------------------------------------------
    # STEP 2: Database Availability Check (pg_isready with retries)
    # -------------------------------------------------------------------------
    echo "[MIGRATION][2/5] Waiting for PostgreSQL to become available..."
    echo "[MIGRATION][INFO]    Target: ${DB_HOST}:${DB_PORT}"

    local retry_count=0
    local max_retries=30
    local wait_time=2

    while [ $retry_count -lt $max_retries ]; do
        if PGPASSWORD="$DB_PASSWORD" pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
            echo "[MIGRATION][INFO] ✅ PostgreSQL is ready and accepting connections"

            # After pg_isready reports the server is accepting connections there
            # can still be a small TOCTOU window where the server is not yet
            # ready to accept authenticated SQL commands. Perform an
            # authenticated SQL probe with psql to ensure the DB accepts the
            # supplied credentials and will respond to queries. Retry a few
            # times before falling back to the outer retry loop.
            echo "[MIGRATION][INFO]    Performing authenticated SQL probe via psql"
            local sql_retries=0
            local sql_max_retries=6
            local sql_ok=0

            while [ $sql_retries -lt $sql_max_retries ]; do
                if PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1;" >/dev/null 2>&1; then
                    sql_ok=1
                    break
                fi
                sql_retries=$((sql_retries + 1))
                echo "[MIGRATION][INFO]    SQL probe attempt $sql_retries/$sql_max_retries failed; retrying in 1s"
                sleep 1
            done

            if [ $sql_ok -eq 1 ]; then
                echo "[MIGRATION][INFO]    ✅ Authenticated SQL probe succeeded"
                echo "[MIGRATION][INFO]    Connected after $((retry_count * wait_time)) seconds"
                break
            else
                echo "[MIGRATION][WARN]    Authenticated SQL probe failed after $sql_max_retries attempts; will retry overall readiness"
                # fall through to increment outer retry_count and sleep
            fi
        fi

        retry_count=$((retry_count + 1))

        if [ $retry_count -eq $max_retries ]; then
            echo "[MIGRATION][ERROR] ❌ PostgreSQL not available after $((max_retries * wait_time)) seconds"
            echo "[MIGRATION][ERROR]    Host: $DB_HOST"
            echo "[MIGRATION][ERROR]    Port: $DB_PORT"
            echo ""
            echo "[MIGRATION][ERROR] Troubleshooting:"
            echo "[MIGRATION][ERROR]    1. Verify PostgreSQL container is running: podman ps"
            echo "[MIGRATION][ERROR]    2. Check PostgreSQL logs: podman logs starexec-postgres"
            echo "[MIGRATION][ERROR]    3. Verify network connectivity: podman exec starexec-app ping $DB_HOST"
            echo "[MIGRATION][ERROR]    4. Check database credentials in values file"
            echo "[MIGRATION][ERROR]    Exit code: 2"
            return 2
        fi

        echo "[MIGRATION][INFO]    Waiting... (attempt $retry_count/$max_retries, next retry in ${wait_time}s)"
        sleep $wait_time
    done
    echo ""

    # -------------------------------------------------------------------------
    # STEP 3: Flyway Classpath and Migration Directory Detection
    # -------------------------------------------------------------------------
    echo "[MIGRATION][3/5] Locating Flyway migration files and classpath..."

    local WEBAPP_DIR="${CATALINA_HOME}/webapps/starexec"
    # Prefer image-bundled migrations to avoid race with WAR expansion
    local MIGRATIONS_DIR="/app/migrations"

    if [ -d "$MIGRATIONS_DIR" ]; then
        local migration_count
        migration_count=$(find "$MIGRATIONS_DIR" -maxdepth 1 -type f -name "*.sql" | wc -l | tr -d ' ')
        echo "[MIGRATION][INFO]    ✅ Found ${migration_count:-0} migration files in $MIGRATIONS_DIR (image-bundled)"
    else
        # Fallback: attempt to use migrations inside the exploded WAR (legacy behavior)
        MIGRATIONS_DIR="${WEBAPP_DIR}/WEB-INF/classes/db/migration"
        # If WAR not expanded, attempt extraction (harmless if already expanded)
        if [ ! -d "${WEBAPP_DIR}/WEB-INF" ]; then
            echo "[MIGRATION][WARN]    WAR not yet expanded at ${WEBAPP_DIR}; attempting extraction..."
            if [ ! -f "${CATALINA_HOME}/webapps/starexec.war" ]; then
                echo "[MIGRATION][ERROR] ❌ WAR file not found at ${CATALINA_HOME}/webapps/starexec.war"
                echo "[MIGRATION][ERROR]    Cannot proceed without application archive or image-bundled migrations"
                echo "[MIGRATION][ERROR]    Exit code: 1"
                return 1
            fi

            mkdir -p "$WEBAPP_DIR"
            cd "$WEBAPP_DIR" || { echo "[MIGRATION][ERROR] Could not cd to $WEBAPP_DIR"; return 1; }
            unzip -q "${CATALINA_HOME}/webapps/starexec.war"
            echo "[MIGRATION][INFO]    ✅ WAR extracted successfully"
        else
            echo "[MIGRATION][INFO]    ✅ WAR already expanded"
        fi

        if [ ! -d "$MIGRATIONS_DIR" ]; then
            echo "[MIGRATION][WARN]    Migration directory not found at $MIGRATIONS_DIR"
            echo "[MIGRATION][WARN]    This may be a new installation; Flyway will baseline if necessary"
        else
            local migration_count
            migration_count=$(find "$MIGRATIONS_DIR" -maxdepth 1 -type f -name "*.sql" | wc -l | tr -d ' ')
            echo "[MIGRATION][INFO]    ✅ Found ${migration_count:-0} migration files in $MIGRATIONS_DIR (from WAR)"
        fi
    fi
    echo ""

    # -------------------------------------------------------------------------
    # STEP 4: Execute Flyway Migration
    # -------------------------------------------------------------------------
    echo "[MIGRATION][4/5] Executing Flyway database migrations..."
    echo "[MIGRATION][INFO]    JDBC URL: $JDBC_URL"
    echo "[MIGRATION][INFO]    Migration Location: $MIGRATIONS_DIR"
    echo ""

    # Run the embedded launcher bundled inside the WAR (uses flyway-core from WEB-INF/lib)
    # The EmbeddedFlywayLauncher is a lightweight wrapper around Flyway that executes
    # all versioned migrations using the flyway-core dependency included in the application.
    # Pass JDBC info via -D properties for clean separation of concerns.
    local EMBED_CLASSPATH="${WEBAPP_DIR}/WEB-INF/classes:${WEBAPP_DIR}/WEB-INF/lib/*"

    # Temporarily disable immediate exit to capture migration exit code
    set +e

    java -cp "$EMBED_CLASSPATH" \
        -Dflyway.url="$JDBC_URL" \
        -Dflyway.user="$DB_USER" \
        -Dflyway.password="$DB_PASSWORD" \
        org.starexec.migration.EmbeddedFlywayLauncher
    local migration_exit_code=$?

    set -e

    # -------------------------------------------------------------------------
    # STEP 5: Result Validation & Reporting
    # -------------------------------------------------------------------------
    echo ""
    echo "[MIGRATION][5/5] Migration execution completed"

    if [ $migration_exit_code -eq 0 ]; then
        local function_end_time
        function_end_time=$(date +%s)
        local execution_time=$((function_end_time - function_start_time))

        echo "[MIGRATION][SUCCESS] ✅ All database migrations applied successfully"
        echo "[MIGRATION][SUCCESS]    Execution time: ${execution_time} seconds"
        echo "[MIGRATION][SUCCESS]    Database schema is now up-to-date"
        echo "============================================================================"
        return 0
    else
        echo "[MIGRATION][FATAL] ❌ Database migration failed with exit code $migration_exit_code"
        echo ""
        echo "[MIGRATION][FATAL] ERROR ANALYSIS:"
        case $migration_exit_code in
            1)
                echo "[MIGRATION][FATAL]    Code 1: General error - Check migration SQL syntax or classpath"
                ;;
            2)
                echo "[MIGRATION][FATAL]    Code 2: Connection error - Database may be unavailable"
                ;;
            3)
                echo "[MIGRATION][FATAL]    Code 3: Validation error - Checksum mismatch detected"
                echo "[MIGRATION][FATAL]    Action: Run 'make migrate-repair' to fix checksums (manual)"
                ;;
            *)
                echo "[MIGRATION][FATAL]    Code $migration_exit_code: Unknown error"
                ;;
        esac
        echo ""
        echo "[MIGRATION][FATAL] RECOVERY OPTIONS:"
        echo "[MIGRATION][FATAL]    1. Check migration files in: $MIGRATIONS_DIR"
        echo "[MIGRATION][FATAL]    2. View Flyway schema history: make db-shell"
        echo "[MIGRATION][FATAL]       SELECT * FROM flyway_schema_history ORDER BY installed_rank;"
        echo "[MIGRATION][FATAL]    3. Manual repair: make migrate-repair ENV=dev"
        echo "[MIGRATION][FATAL]    4. Skip migrations (emergency): SKIP_MIGRATIONS=true in deployment"
        echo ""
        echo "[MIGRATION][FATAL] ⛔ Container startup ABORTED to prevent data corruption"
        echo "============================================================================"
        return 3
    fi
}

# ============================================================================
# MIGRATION EXECUTION CONTROL
# ============================================================================
# Allow skipping migrations for emergency scenarios or rollback procedures
# Usage: podman run -e SKIP_MIGRATIONS=true ...
# ============================================================================
if [ "${SKIP_MIGRATIONS:-false}" = "true" ]; then
    echo ""
    echo "[MIGRATION][WARN] ⚠️  SKIP_MIGRATIONS=true detected"
    echo "[MIGRATION][WARN]    Database migrations will NOT be executed"
    echo "[MIGRATION][WARN]    Ensure migrations are applied manually before using the application"
    echo ""
else
    run_database_migrations || {
        _migration_exit_code=$?
        echo ""
        echo "[MIGRATION][CRITICAL] 🚨 CRITICAL: Migration failure detected"
        echo "[MIGRATION][CRITICAL]    Container will exit to prevent starting with inconsistent database state"
        exit "${_migration_exit_code}"
    }
fi

# Fix logback log file paths for container environment
LOGBACK_XML="${CATALINA_HOME}/webapps/starexec/WEB-INF/classes/logback.xml"
if [ -f "${LOGBACK_XML}" ]; then
    echo "Fixing logback log paths for container..."
    # Ensure log directory exists with proper permissions
    mkdir -p ${CATALINA_HOME}/logs
    chown starexec:starexec ${CATALINA_HOME}/logs || true
    echo "✓ Log directory configured"
fi
echo ""

# Initialize SGE scripts in the persisted data directory on first start only.
# Do not overwrite files that operators may have patched in the data volume.
if [ ! -f /app/data/sge_scripts/functions.bash ]; then
    echo "Initializing SGE scripts in /app/data/sge_scripts..."
    mkdir -p /app/data/sge_scripts
    cp -r /config/sge/* /app/data/sge_scripts/ 2>/dev/null || true
    chmod -R 755 /app/data/sge_scripts || true
    echo "✓ SGE scripts initialized successfully"
fi
echo ""

# Update loading page to redirect to StarExec home page
# This provides visual feedback that startup completed successfully
ROOT_INDEX="${CATALINA_HOME}/webapps/ROOT/index.html"
if [ -f "$ROOT_INDEX" ]; then
    echo "Updating loading page - migrations complete, starting Tomcat..."
    # Replace status message to indicate app is ready
    sed -i 's/StarExec is starting up.../StarExec is ready!/g' "$ROOT_INDEX"
    sed -i 's/Initializing database and loading application/Redirecting to application.../g' "$ROOT_INDEX"
    # Change refresh to redirect to /starexec/ (the actual home page)
    sed -i 's|meta http-equiv="refresh" content="5"|meta http-equiv="refresh" content="2;url=/starexec/"|g' "$ROOT_INDEX"
    echo "✓ Loading page updated - will redirect to /starexec/"
fi
echo ""

# Start Tomcat using full path
echo "Starting Tomcat..."
echo "=========================================="
exec "${CATALINA_HOME}/bin/catalina.sh" run
