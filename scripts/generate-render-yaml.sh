#!/usr/bin/env bash
# StarExec render.yaml generator
# Generates deployment manifest from template with environment variable substitution

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE_FILE="$PROJECT_ROOT/render.yaml.template"
OUTPUT_FILE="$PROJECT_ROOT/render.yaml"

# Set defaults
ENVIRONMENT="${ENV:-dev}"
VOLUME_PREFIX="${VOLUME_PREFIX:-starexec}"
DEFAULT_DATA_VOL="${VOLUME_PREFIX}-${ENVIRONMENT}-data"
DEFAULT_POSTGRES_VOL="${VOLUME_PREFIX}-${ENVIRONMENT}-postgres"

export STAREXEC_DB_USER="${STAREXEC_DB_USER:-starexec}"
if [ -n "${STAREXEC_DB_PASSWORD_FILE:-}" ] && [ -f "${STAREXEC_DB_PASSWORD_FILE}" ]; then
    export STAREXEC_DB_PASSWORD="$(cat "${STAREXEC_DB_PASSWORD_FILE}" | tr -d '\n')"
else
    export STAREXEC_DB_PASSWORD="${STAREXEC_DB_PASSWORD:-starexec_dev_password}"
fi
export STAREXEC_DB_NAME="${STAREXEC_DB_NAME:-starexec}"
export STAREXEC_DB_PORT="${STAREXEC_DB_PORT:-5432}"
export STAREXEC_DATA_VOL="${STAREXEC_DATA_VOL:-$DEFAULT_DATA_VOL}"
export STAREXEC_POSTGRES_VOL="${STAREXEC_POSTGRES_VOL:-$DEFAULT_POSTGRES_VOL}"
export IMAGE_NAME="${IMAGE_NAME:-localhost/local/starexec}"
export IMAGE_TAG="${IMAGE_TAG:-dev}"

# Check if template exists
if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "Error: Template file not found: $TEMPLATE_FILE" >&2
    exit 1
fi

echo "Generating render.yaml from template..."
echo "  Using environment:"
echo "    DB_USER:    $STAREXEC_DB_USER"
echo "    DB_NAME:    $STAREXEC_DB_NAME"
echo "    DATA_VOL:   $STAREXEC_DATA_VOL"
echo "    POSTGRES_VOL: $STAREXEC_POSTGRES_VOL"
echo "    IMAGE:      $IMAGE_NAME:$IMAGE_TAG"

# Use sed to replace placeholders with actual values.
# Note: template must use the corresponding ${VAR:-default} placeholders.
sed -e "s|\${STAREXEC_DB_USER:-starexec}|$STAREXEC_DB_USER|g" \
    -e "s|\${STAREXEC_DB_PASSWORD:-starexec_dev_password}|$STAREXEC_DB_PASSWORD|g" \
    -e "s|\${STAREXEC_DB_NAME:-starexec}|$STAREXEC_DB_NAME|g" \
    -e "s|\${STAREXEC_DB_PORT:-5432}|$STAREXEC_DB_PORT|g" \
    -e "s|\${STAREXEC_DATA_VOL:-starexec-dev-data}|$STAREXEC_DATA_VOL|g" \
    -e "s|\${STAREXEC_POSTGRES_VOL:-starexec-dev-postgres}|$STAREXEC_POSTGRES_VOL|g" \
    -e "s|\${IMAGE_NAME:-localhost/local/starexec}:|$IMAGE_NAME:|g" \
    -e "s|\${IMAGE_TAG:-dev}|$IMAGE_TAG|g" \
    "$TEMPLATE_FILE" > "$OUTPUT_FILE"

echo "✓ Generated: $OUTPUT_FILE"
