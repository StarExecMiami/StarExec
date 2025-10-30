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

configure_context() {
    # Determine DB values (explicit env or defaults)
    DB_HOST="${STAREXEC_DB_HOST:-localhost}"
    DB_PORT="${STAREXEC_DB_PORT:-5432}"
    DB_NAME="${STAREXEC_DB_NAME:-starexec}"
    DB_USER="${STAREXEC_DB_USER:-root}"
    DB_PASSWORD="${STAREXEC_DB_PASSWORD:-}"

    if [ -f "$TEMPLATE_FILE" ]; then
        echo "Generating context.xml from template ($TEMPLATE_FILE) ..."
        cp "$TEMPLATE_FILE" "$CONTEXT_FILE"
    elif [ ! -f "$CONTEXT_FILE" ]; then
        echo "⚠️  Warning: neither $TEMPLATE_FILE nor $CONTEXT_FILE exist. Skipping DB substitution."
        return
    else
        echo "Found existing context.xml at $CONTEXT_FILE — will perform safe in-place substitution."
    fi

    # Replace placeholders in the copied or existing context.xml
    sed -i "s|\${STAREXEC_DB_HOST}|${DB_HOST}|g" "$CONTEXT_FILE" || true
    sed -i "s|\${STAREXEC_DB_PORT}|${DB_PORT}|g" "$CONTEXT_FILE" || true
    sed -i "s|\${STAREXEC_DB_NAME}|${DB_NAME}|g" "$CONTEXT_FILE" || true
    sed -i "s|\${STAREXEC_DB_USER}|${DB_USER}|g" "$CONTEXT_FILE" || true
    sed -i "s|\${STAREXEC_DB_PASSWORD}|${DB_PASSWORD}|g" "$CONTEXT_FILE" || true

    # Show the configured JDBC URL for quick debugging
    echo "Configured JDBC URL in $CONTEXT_FILE:"
    grep -n "jdbc:postgresql" "$CONTEXT_FILE" || true

    # If DB_HOST is localhost (or empty) warn the operator — common misconfiguration
    if [ -z "${STAREXEC_DB_HOST+x}" ] || [ "$DB_HOST" = "localhost" ]; then
        cat <<-WARN
        ⚠️  Notice: STAREXEC_DB_HOST is set to '${DB_HOST}'.
        This commonly causes Tomcat to try to connect to a database inside the
        application container rather than the dedicated Postgres service.

        Recommended fixes:
         - Start the container with STAREXEC_DB_HOST set to your Postgres service name
           (example: 'postgres' or 'starexec-postgres' when using docker-compose).
         - Or set environment variable STRICT_DB_HOST=true to make the container exit
           when the host is 'localhost' to avoid silent misconfiguration.

        To override and continue anyway, set STAREXEC_DB_HOST explicitly.
WARN

        if [ "${STRICT_DB_HOST:-false}" = "true" ]; then
            echo "STRICT_DB_HOST=true and DB host is '${DB_HOST}' — exiting with error."
            exit 1
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
