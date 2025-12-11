#!/bin/bash
set -euo pipefail

# =====================================================================
# Signal Handling for Graceful Shutdown
# =====================================================================
# When Docker sends SIGTERM (e.g., docker compose down), this script
# must forward the signal to the Java process and exit cleanly.
# Without this, the shell would ignore SIGTERM and Docker would wait
# for the timeout (30s) before force-killing with SIGKILL.
# =====================================================================

java_pid=""

# Cleanup function - called on signal or exit
cleanup() {
  local exit_code=$?

  if [ -n "$java_pid" ]; then
    echo "[MIGRATION][SIGNAL] Received termination signal, shutting down Java process (PID: $java_pid)..."
    # Send SIGTERM to Java process to allow graceful shutdown
    kill -TERM "$java_pid" 2>/dev/null || true

    # Wait up to 10 seconds for graceful shutdown
    local wait_count=0
    while kill -0 "$java_pid" 2>/dev/null && [ $wait_count -lt 50 ]; do
      sleep 0.2
      wait_count=$((wait_count + 1))
    done

    # If still running, force kill
    if kill -0 "$java_pid" 2>/dev/null; then
      echo "[MIGRATION][SIGNAL] Java process did not exit gracefully, force killing..."
      kill -9 "$java_pid" 2>/dev/null || true
    fi

    echo "[MIGRATION][SIGNAL] Java process terminated"
  fi

  exit $exit_code
}

# Register cleanup function to be called on signals and normal exit
trap cleanup EXIT
trap 'exit 143' SIGTERM  # 128 + 15 (SIGTERM) = 143
trap 'exit 130' SIGINT   # 128 + 2 (SIGINT) = 130

echo "[MIGRATION][INIT] Starting database migration service..."
echo "[MIGRATION][INIT] PID: $$"
echo "[MIGRATION][INIT] Signal handling: SIGTERM (graceful) and SIGINT (forceful) enabled"
echo "========================================================================"

# Database connection configuration
export STAREXEC_DB_HOST="${STAREXEC_DB_HOST:-postgres}"
export STAREXEC_DB_PORT="${STAREXEC_DB_PORT:-5432}"
export STAREXEC_DB_NAME="${STAREXEC_DB_NAME:-starexec}"
export STAREXEC_DB_USER="${STAREXEC_DB_USER:-starexec}"
export STAREXEC_DB_PASSWORD="${STAREXEC_DB_PASSWORD}"
export STAREXEC_DB_SCHEMA="${STAREXEC_DB_SCHEMA:-starexec}"

CATALINA_HOME="/opt/tomcat"
WEBAPP_DIR="${CATALINA_HOME}/webapps/starexec"
MIGRATIONS_DIR="${WEBAPP_DIR}/WEB-INF/classes/db/migration"
CLASSPATH="${WEBAPP_DIR}/WEB-INF/classes:${WEBAPP_DIR}/WEB-INF/lib/*"

# =====================================================================
# STEP 1: Validate Configuration
# =====================================================================
echo "[MIGRATION][CONFIG] Validating database configuration..."
if [ -z "${STAREXEC_DB_PASSWORD:-}" ]; then
  echo "[MIGRATION][ERROR] ❌ STAREXEC_DB_PASSWORD environment variable is not set"
  exit 1
fi
echo "[MIGRATION][CONFIG] ✅ Configuration validated"
echo ""

# =====================================================================
# STEP 2: Wait for PostgreSQL to be Ready
# =====================================================================
echo "[MIGRATION][CONNECT] Waiting for PostgreSQL to become available..."
retry_count=0
max_retries=60
wait_time=2

while [ $retry_count -lt $max_retries ]; do
  if PGPASSWORD="$STAREXEC_DB_PASSWORD" pg_isready -h "$STAREXEC_DB_HOST" -p "$STAREXEC_DB_PORT" -U "$STAREXEC_DB_USER" -d "$STAREXEC_DB_NAME" >/dev/null 2>&1; then
    echo "[MIGRATION][CONNECT] ✅ PostgreSQL is accepting connections"

    # Perform authenticated SQL probe to ensure DB truly ready
    if PGPASSWORD="$STAREXEC_DB_PASSWORD" psql -h "$STAREXEC_DB_HOST" -p "$STAREXEC_DB_PORT" -U "$STAREXEC_DB_USER" -d "$STAREXEC_DB_NAME" -c "SELECT 1;" >/dev/null 2>&1; then
      echo "[MIGRATION][CONNECT] ✅ Authenticated SQL probe succeeded"
      break
    fi
  fi

  retry_count=$((retry_count + 1))
  if [ $retry_count -eq $max_retries ]; then
    echo "[MIGRATION][ERROR] ❌ PostgreSQL not available after $((max_retries * wait_time)) seconds"
    echo "[MIGRATION][ERROR]    Host: $STAREXEC_DB_HOST:$STAREXEC_DB_PORT"
    exit 2
  fi

  printf "[MIGRATION][CONNECT] Waiting... (attempt %d/%d)\n" "$retry_count" "$max_retries"
  sleep $wait_time
done
echo ""

# =====================================================================
# STEP 3: Verify Migration Files Exist
# =====================================================================
echo "[MIGRATION][FILES] Verifying migration files..."
if [ ! -d "$MIGRATIONS_DIR" ]; then
  echo "[MIGRATION][ERROR] ❌ Migration directory not found: $MIGRATIONS_DIR"
  echo "[MIGRATION][ERROR]    Ensure WAR file includes WEB-INF/classes/db/migration/"
  exit 1
fi

migration_count=$(find "$MIGRATIONS_DIR" -maxdepth 1 -type f -name "*.sql" 2>/dev/null | wc -l)
echo "[MIGRATION][FILES] ✅ Found $migration_count migration SQL files"
echo ""

# =====================================================================
# STEP 4: Execute Flyway Migrations
# =====================================================================
echo "[MIGRATION][EXECUTE] Executing Flyway migrations..."
echo "[MIGRATION][EXECUTE] Target: jdbc:postgresql://${STAREXEC_DB_HOST}:${STAREXEC_DB_PORT}/${STAREXEC_DB_NAME}"
echo "[MIGRATION][EXECUTE] Schema: $STAREXEC_DB_SCHEMA"
echo "[MIGRATION][EXECUTE] User: $STAREXEC_DB_USER"
echo "[MIGRATION][EXECUTE] Password: ***REDACTED***"
echo ""

# Execute EmbeddedFlywayLauncher
# IMPORTANT: Credentials are passed ONLY via environment variables
# NEVER via system properties (-D flags)
# This prevents credential exposure via process inspection
#
# Run as background process so we can handle signals
java -cp "$CLASSPATH" \
     org.starexec.migration.EmbeddedFlywayLauncher &
java_pid=$!

# Wait for the Java process and capture exit code
if wait "$java_pid"; then
  migration_exit_code=0
else
  migration_exit_code=$?
fi

echo ""
echo "========================================================================"

# =====================================================================
# STEP 5: Report Results
# =====================================================================
if [ $migration_exit_code -eq 0 ]; then
  echo "[MIGRATION][SUCCESS] ✅ All database migrations applied successfully"
  echo "[MIGRATION][SUCCESS] Database schema is now up-to-date"
  echo "[MIGRATION][SUCCESS] Exit code: 0"
  exit 0
else
  echo "[MIGRATION][FAILURE] ❌ Database migration failed with exit code $migration_exit_code"
  echo "[MIGRATION][FAILURE] Check logs above for details"
  echo "[MIGRATION][FAILURE] Database may be in an inconsistent state"
  echo "[MIGRATION][FAILURE] Exit code: $migration_exit_code"
  exit "$migration_exit_code"
fi
