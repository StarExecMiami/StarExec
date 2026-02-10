#!/bin/sh
# StarExec Database Migration Script
# Handles Flyway migrations safely

set -e

# Configuration
DB_HOST="${DB_HOST:-localhost}"
DB_USER="${DB_USER:-starexec}"
DB_NAME="${DB_NAME:-starexec}"
DB_PASS="${DB_PASS:-starexec_dev_password}"
DB_PASS_FILE="${DB_PASS_FILE:-/run/secrets/starexec-db-password}"

# Load password from file if available
if [ -n "$DB_PASS_FILE" ] && [ -f "$DB_PASS_FILE" ] && [ -r "$DB_PASS_FILE" ]; then
    DB_PASS=$(tr -d '\n' < "$DB_PASS_FILE")
fi

# Check DB connectivity
if command -v pg_isready >/dev/null 2>&1 && ! pg_isready -h "$DB_HOST" -p 5432 -U "$DB_USER" >/dev/null 2>&1; then
    echo "❌ Unable to reach PostgreSQL at $DB_HOST:5432"
    exit 1
fi

echo "Running Flyway migrations against $DB_HOST:5432/$DB_NAME"
mvn -q -DskipTests \
    -Dflyway.url="jdbc:postgresql://$DB_HOST:5432/$DB_NAME" \
    -Dflyway.user="$DB_USER" \
    -Dflyway.password="$DB_PASS" \
    flyway:migrate

echo "✓ Migrations completed successfully"