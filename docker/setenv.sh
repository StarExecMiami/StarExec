#!/bin/sh
# docker/setenv.sh — safe, parameterized setenv for Tomcat

# Build metadata may be written at build-time; leave empty if not set
: "${STAREXEC_BUILD_VERSION:=unknown}"
: "${STAREXEC_BUILD_USER:=builder}"
: "${STAREXEC_BUILD_DATE:=unknown}"
export STAREXEC_BUILD_VERSION STAREXEC_BUILD_USER STAREXEC_BUILD_DATE

# Default config
: "${STAREXEC_DB_HOST:=postgres}"
: "${STAREXEC_DB_PORT:=5432}"
: "${STAREXEC_DB_USER:=starexec}"
: "${STAREXEC_DB_NAME:=starexec}"
# Optional: allow explicit DB type / driver hints
: "${STAREXEC_DB_TYPE:=postgresql}"
: "${STAREXEC_DB_SSLMODE:=disable}"
export STAREXEC_DB_TYPE STAREXEC_DB_SSLMODE

# Prefer reading password from a mounted secret file (if present), then fallback to env var.
# Common mount locations: /run/secrets/starexec_db_password or /etc/secrets/starexec/db_password
if [ -r "/run/secrets/STAREXEC_DB_PASSWORD" ]; then
  STAREXEC_DB_PASSWORD="$(cat /run/secrets/STAREXEC_DB_PASSWORD)"
elif [ -r "/etc/secrets/starexec_db_password" ]; then
  STAREXEC_DB_PASSWORD="$(cat /etc/secrets/starexec_db_password)"
fi
# Fallback to env var if file not present
: "${STAREXEC_DB_PASSWORD:=${STAREXEC_DB_PASSWORD:-}}"

# Build JVM properties string (avoid exposing password in logs; but -D will appear in process list)
JVM_PROPS="-DSTAREXEC_DB_HOST=${STAREXEC_DB_HOST} -DSTAREXEC_DB_PORT=${STAREXEC_DB_PORT} -DSTAREXEC_DB_USER=${STAREXEC_DB_USER} -DSTAREXEC_DB_NAME=${STAREXEC_DB_NAME} -DSTAREXEC_DB_TYPE=${STAREXEC_DB_TYPE} -DSTAREXEC_DB_SSLMODE=${STAREXEC_DB_SSLMODE}"
# Only append password property if non-empty
[ -n "${STAREXEC_DB_PASSWORD}" ] && JVM_PROPS="${JVM_PROPS} -DSTAREXEC_DB_PASSWORD=${STAREXEC_DB_PASSWORD}"

# Preserve any existing JAVA_OPTS, append our props
JAVA_OPTS="${JAVA_OPTS:-} ${JVM_PROPS}"
export JAVA_OPTS

# Optionally export CATALINA_OPTS if you prefer
CATALINA_OPTS="${CATALINA_OPTS:-} ${JVM_PROPS}"
export CATALINA_OPTS

# Any other app-specific environment defaults go here...
export STAREXEC_CONFIG_PATH="${STAREXEC_CONFIG_PATH:-/config}"