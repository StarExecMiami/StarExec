#!/bin/bash
set -e

echo "StarExec Container Starting..."
echo "=========================================="

# Display environment for debugging
echo "Environment Configuration:"
echo "  STAREXEC_DB_HOST=${STAREXEC_DB_HOST:-localhost}"
echo "  STAREXEC_DB_PORT=${STAREXEC_DB_PORT:-3306}"
echo "  STAREXEC_DB_NAME=${STAREXEC_DB_NAME:-starexec}"
echo "  STAREXEC_DB_USER=${STAREXEC_DB_USER:-root}"
echo "  CATALINA_HOME=${CATALINA_HOME}"
echo ""

# Substitute environment variables in context.xml
CONTEXT_FILE="${CATALINA_HOME}/webapps/starexec/META-INF/context.xml"

if [ -f "$CONTEXT_FILE" ]; then
    echo "Configuring database connection in context.xml..."
    
    # Set defaults for all database variables
    DB_HOST="${STAREXEC_DB_HOST:-localhost}"
    DB_PORT="${STAREXEC_DB_PORT:-3306}"
    DB_NAME="${STAREXEC_DB_NAME:-starexec}"
    DB_USER="${STAREXEC_DB_USER:-root}"
    DB_PASSWORD="${STAREXEC_DB_PASSWORD}"
    
    # Use sed to replace template placeholders (more efficient than envsubst)
    # These match the format in context.xml.template: ${VARIABLE_NAME}
    sed -i "s|\${STAREXEC_DB_HOST}|${DB_HOST}|g" "$CONTEXT_FILE"
    sed -i "s|\${STAREXEC_DB_PORT}|${DB_PORT}|g" "$CONTEXT_FILE"
    sed -i "s|\${STAREXEC_DB_NAME}|${DB_NAME}|g" "$CONTEXT_FILE"
    sed -i "s|\${STAREXEC_DB_USER}|${DB_USER}|g" "$CONTEXT_FILE"
    sed -i "s|\${STAREXEC_DB_PASSWORD}|${DB_PASSWORD}|g" "$CONTEXT_FILE"
    
    echo "✓ Database configuration complete"
else
    echo "⚠️  Warning: context.xml not found at $CONTEXT_FILE"
fi
echo ""

# Fix logback log file paths for container environment
LOGBACK_XML="${CATALINA_HOME}/webapps/starexec/WEB-INF/classes/logback.xml"
if [ -f "${LOGBACK_XML}" ]; then
    echo "Fixing logback log paths for container..."
    # Ensure log directory exists with proper permissions
    mkdir -p ${CATALINA_HOME}/logs
    chown starexec:starexec ${CATALINA_HOME}/logs
    echo "✓ Log directory configured"
fi
echo ""

# Copy SGE scripts to data directory if they do not exist
if [ ! -f /app/data/sge_scripts/functions.bash ]; then
    echo "Initializing SGE scripts in /app/data/sge_scripts..."
    mkdir -p /app/data/sge_scripts
    cp -r /config/sge/* /app/data/sge_scripts/
    chmod -R 755 /app/data/sge_scripts
    echo "✓ SGE scripts initialized successfully"
fi
echo ""

# Start Tomcat using full path
echo "Starting Tomcat..."
echo "=========================================="
exec "${CATALINA_HOME}/bin/catalina.sh" run
