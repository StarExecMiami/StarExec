#!/bin/bash
# Proves docker/entrypoint.sh hands database credentials to the embedded Flyway
# launcher through the environment and never on the java command line.
# Extracts the launch region from the real entrypoint and runs it against a
# fake `java` that records its argv and environment. No database is contacted.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENTRY="${1:-$ROOT/docker/entrypoint.sh}"   # optional: entrypoint to test (mutation runs)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAKE_PASS='fake-Pa$$w0rd-&-|-not-real'

# Region: from the classpath assignment through capture of the exit code.
awk '/local EMBED_CLASSPATH=/{p=1} p{print} /local migration_exit_code=\$\?/{exit}' \
    "$ENTRY" > "$WORK/region.sh"
if ! grep -q 'EmbeddedFlywayLauncher' "$WORK/region.sh"; then
    echo "FAIL: could not extract the launch region from entrypoint.sh"
    exit 1
fi

mkdir "$WORK/bin" "$WORK/out"
cat > "$WORK/bin/java" <<'JEOF'
#!/bin/sh
printf '%s\n' "$@" > "$FAKE_OUT/argv"
env > "$FAKE_OUT/env"
JEOF
chmod +x "$WORK/bin/java"

(
    export FAKE_OUT="$WORK/out" PATH="$WORK/bin:$PATH"
    unset STAREXEC_DB_HOST STAREXEC_DB_PORT STAREXEC_DB_NAME STAREXEC_DB_USER STAREXEC_DB_PASSWORD
    run() {
        local WEBAPP_DIR=/x JDBC_URL=jdbc:postgresql://h:1/n
        local DB_HOST=dbhost DB_PORT=5433 DB_NAME=dbname DB_USER=dbuser DB_PASSWORD="$FAKE_PASS"
        # shellcheck disable=SC1090
        . "$WORK/region.sh"
    }
    run
) > /dev/null 2>&1

fail=0
if [ ! -s "$WORK/out/argv" ]; then
    echo "FAIL: fake java was not invoked"
    exit 1
fi
if grep -qF -e "$FAKE_PASS" -e "flyway.password" -e "flyway.user" "$WORK/out/argv"; then
    echo "FAIL: credentials or -Dflyway.* present in java argv"
    fail=1
fi
for kv in STAREXEC_DB_HOST=dbhost STAREXEC_DB_PORT=5433 STAREXEC_DB_NAME=dbname \
          STAREXEC_DB_USER=dbuser "STAREXEC_DB_PASSWORD=$FAKE_PASS"; do
    if ! grep -qxF -- "$kv" "$WORK/out/env"; then
        echo "FAIL: launcher environment lacks ${kv%%=*}"
        fail=1
    fi
done
if ! grep -qx 'org.starexec.migration.EmbeddedFlywayLauncher' "$WORK/out/argv"; then
    echo "FAIL: launcher class missing from argv"
    fail=1
fi
[ "$fail" -eq 0 ] && echo "PASS: credentials reach the launcher via environment only"
exit "$fail"
