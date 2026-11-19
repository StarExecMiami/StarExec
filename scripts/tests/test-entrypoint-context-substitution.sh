#!/bin/bash
# Proves the context.xml placeholder substitution in docker/entrypoint.sh yields
# well-formed XML and round-trips awkward database values, including a password
# holding every character that used to break the sed expression or the XML.
# Extracts substitute_context_placeholders from the real entrypoint.
# Usage: test-entrypoint-context-substitution.sh [entrypoint.sh]
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENTRY="${1:-$ROOT/docker/entrypoint.sh}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

awk '/^# BEGIN substitute_context_placeholders/{p=1} p{print} /^# END substitute_context_placeholders/{exit}' \
    "$ENTRY" > "$WORK/func.sh"
if ! grep -q 'substitute_context_placeholders()' "$WORK/func.sh"; then
    echo "FAIL: substitute_context_placeholders not found in $ENTRY"
    exit 1
fi
# shellcheck disable=SC1091
. "$WORK/func.sh"

cat > "$WORK/context.xml" <<'XEOF'
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Resource name="jdbc/starexec" type="javax.sql.DataSource"
            url="jdbc:postgresql://${STAREXEC_DB_HOST}:${STAREXEC_DB_PORT}/${STAREXEC_DB_NAME}?ssl=false&amp;currentSchema=starexec"
            username="${STAREXEC_DB_USER}"
            password="${STAREXEC_DB_PASSWORD}"/>
</Context>
XEOF

fail=0
# Extract an attribute from the produced XML with python (real XML parser).
attr() {
    python3 - "$1" "$2" <<'PYEOF'
import sys, xml.dom.minidom
d = xml.dom.minidom.parse(sys.argv[1])
sys.stdout.write(d.getElementsByTagName("Resource")[0].getAttribute(sys.argv[2]))
PYEOF
}

check() {
    local label="$1" host="$2" port="$3" name="$4" user="$5" pass="$6"
    local out="$WORK/out.xml"
    if ! substitute_context_placeholders "$WORK/context.xml" "$host" "$port" "$name" "$user" "$pass" > "$out"; then
        echo "FAIL [$label]: substitution returned non-zero"
        fail=1
        return
    fi
    if ! python3 -c 'import sys, xml.dom.minidom; xml.dom.minidom.parse(sys.argv[1])' "$out" 2>/dev/null; then
        echo "FAIL [$label]: result is not well-formed XML"
        fail=1
        return
    fi
    local got_pass got_user got_url
    got_pass=$(attr "$out" password)
    got_user=$(attr "$out" username)
    got_url=$(attr "$out" url)
    [ "$got_pass" = "$pass" ] || { echo "FAIL [$label]: password did not round-trip"; fail=1; }
    [ "$got_user" = "$user" ] || { echo "FAIL [$label]: username did not round-trip"; fail=1; }
    local want_url="jdbc:postgresql://${host}:${port}/${name}?ssl=false&currentSchema=starexec"
    [ "$got_url" = "$want_url" ] || { echo "FAIL [$label]: url did not round-trip"; fail=1; }
    # A value may legitimately contain placeholder text (lookalike case); it must
    # then survive verbatim, which the round-trip checks above already prove.
    if [ "$label" != placeholder-lookalike ] && grep -q '\${STAREXEC_DB_' "$out"; then
        echo "FAIL [$label]: a placeholder survived"
        fail=1
    fi
}

check plain dbhost 5432 starexec starexec 'plainpass'
check special-chars dbhost 5432 starexec starexec 'fake&|\"<>'"'"'end'
check backslash-amp dbhost 5432 starexec 'us&er' 'a\\b\&c\1|d'
check placeholder-lookalike dbhost 5432 starexec starexec '${STAREXEC_DB_USER}'
check dollar-and-percent dbhost 5432 starexec starexec 'pa$$word%s$1${x}'

# Line breaks cannot live in an attribute; refuse rather than corrupt the file.
if substitute_context_placeholders "$WORK/context.xml" h 1 n u "$(printf 'a\nb')" > /dev/null 2>&1; then
    echo "FAIL [newline]: a value containing a line break was accepted"
    fail=1
fi

[ "$fail" -eq 0 ] && echo "PASS: context.xml substitution is well-formed and round-trips"
exit "$fail"
