#!/bin/bash
set -e

echo "StarExec Container Starting..."
echo "=========================================="

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

# Configure context.xml from template if present and do safety checks
configure_context
echo ""

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

# Copy SGE scripts to data directory if they do not exist
if [ ! -f /app/data/sge_scripts/functions.bash ]; then
    echo "Initializing SGE scripts in /app/data/sge_scripts..."
    mkdir -p /app/data/sge_scripts
    cp -r /config/sge/* /app/data/sge_scripts/ 2>/dev/null || true
    chmod -R 755 /app/data/sge_scripts || true
    echo "✓ SGE scripts initialized successfully"
fi
echo ""

# Start Tomcat using full path
echo "Starting Tomcat..."
echo "=========================================="
exec "${CATALINA_HOME}/bin/catalina.sh" run
