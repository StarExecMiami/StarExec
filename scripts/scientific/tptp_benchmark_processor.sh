#!/bin/sh
# StarExec TPTP benchmark processor entrypoint.
# Performs real syntactic validation with pinned tptp4X, extracts TPTP header
# attributes (Status, starexec-expected-result, SPC), and extracts validated
# include(...) dependencies for StarExec's Properties loader.

set -eu
LC_ALL=C
export LC_ALL

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TPTP4X_BIN="${SCRIPT_DIR}/bin/tptp4X"

TMP_OUT=""
cleanup() {
    if [ -n "$TMP_OUT" ] && [ -f "$TMP_OUT" ]; then
        rm -f "$TMP_OUT"
    fi
}
trap cleanup EXIT INT TERM

# Escape characters that java.util.Properties treats specially inside values.
prop_escape() {
    printf '%s' "$1" | tr -d '\r\n' | sed -e 's/\\/\\\\/g'
}

emit_invalid() {
    reason=$(prop_escape "$1")
    printf 'starexec-valid=false\n'
    printf 'starexec-dependencies=0\n'
    printf 'starexec-validation-error=%s\n' "$reason"
    exit 0
}

# Validate include() relative POSIX path strictly against traversal or escapes.
is_safe_include_path() {
    inc_path=$1
    if [ -z "$inc_path" ]; then
        return 1
    fi
    case "$inc_path" in
        /*|*\\*|*" "*|*"	"*)
            return 1
            ;;
    esac
    old_ifs=$IFS
    IFS='/'
    # Disable pathname expansion while inspecting individual path segments.
    set -f
    for seg in $inc_path; do
        case "$seg" in
            ""|"."|"..")
                set +f
                IFS=$old_ifs
                return 1
                ;;
        esac
    done
    set +f
    IFS=$old_ifs
    return 0
}

if [ "$#" -ne 1 ]; then
    emit_invalid "usage-expected-one-benchmark-argument"
fi

BENCH="$1"
if [ -z "$BENCH" ] || [ ! -f "$BENCH" ] || [ ! -r "$BENCH" ]; then
    emit_invalid "unreadable-or-missing-benchmark-file"
fi

RAW_BYTES=$(wc -c < "$BENCH" | tr -d '[:space:]')
if [ "$RAW_BYTES" -eq 0 ]; then
    emit_invalid "empty-benchmark-file"
fi

CLEAN_BYTES=$(tr -d '\000' < "$BENCH" | wc -c | tr -d '[:space:]')
if [ "$RAW_BYTES" -ne "$CLEAN_BYTES" ]; then
    emit_invalid "binary-nul-bytes-rejected"
fi

if [ ! -x "$TPTP4X_BIN" ]; then
    emit_invalid "missing-tptp4x-syntax-validator"
fi

# tptp4X interprets a leading '-' on non-stdin paths as an option flag; "--" means stdin.
case "$BENCH" in
    -*)
        TPTP4X_ARG="./$BENCH"
        ;;
    *)
        TPTP4X_ARG="$BENCH"
        ;;
esac

TMP_OUT=$(mktemp "${TMPDIR:-/tmp}/tptp-proc-XXXXXX")
TPTP4X_RC=0
"$TPTP4X_BIN" -q2 "$TPTP4X_ARG" > "$TMP_OUT" 2>&1 || TPTP4X_RC=$?

if [ "$TPTP4X_RC" -ne 0 ]; then
    FIRST_ERR=$(tr -d '\r' < "$TMP_OUT" | sed -n '1s/[[:space:]]\{1,\}/ /gp' | cut -c 1-240)
    if [ -z "$FIRST_ERR" ]; then
        FIRST_ERR="tptp4x-exit-${TPTP4X_RC}"
    fi
    emit_invalid "tptp-syntax-error: $FIRST_ERR"
fi

if grep -q '^ERROR:' "$TMP_OUT"; then
    FIRST_ERR=$(grep '^ERROR:' "$TMP_OUT" | head -n 1 | tr -d '\r' | cut -c 1-240)
    emit_invalid "tptp-syntax-error: $FIRST_ERR"
fi

# Reject comment-only or blank inputs where tptp4X parses zero statements/includes.
if ! grep -Eq '^(include|fof|cnf|tff|thf|tcf|tpi)\(' "$TMP_OUT"; then
    emit_invalid "no-tptp-formulas-or-includes"
fi

# Extract header attributes (Status -> also starexec-expected-result, SPC).
STATUS_VAL=$(tr -d '\r' < "$BENCH" | sed -n \
    's/^[[:space:]]*%[[:space:]]*Status[[:space:]]*:[[:space:]]*\([^[:space:]]\{1,\}\).*$/\1/p' | \
    head -n 1)
SPC_VAL=$(tr -d '\r' < "$BENCH" | sed -n \
    's/^[[:space:]]*%[[:space:]]*SPC[[:space:]]*:[[:space:]]*\([^[:space:]]\{1,\}\).*$/\1/p' | \
    head -n 1)

# Extract normalized top-level include(...) targets produced by tptp4X.
RAW_INCLUDES=$(sed -n \
    -e "s/^include('\([^']*\)').*$/\1/p" \
    -e 's/^include("\([^"]*\)").*$/\1/p' \
    "$TMP_OUT")

# Verify that unparsed active-looking include directives in raw input didn't slip past or hide '..'.
DEP_COUNT=0
DEP_LINES=""
if [ -n "$RAW_INCLUDES" ]; then
    old_ifs=$IFS
    IFS='
'
    for inc in $RAW_INCLUDES; do
        if ! is_safe_include_path "$inc"; then
            IFS=$old_ifs
            emit_invalid "unsafe-include-path: $inc"
        fi
        # Deduplicate while preserving first-seen order.
        already=0
        if [ -n "$DEP_LINES" ]; then
            for seen_inc in $DEP_LINES; do
                if [ "$seen_inc" = "$inc" ]; then
                    already=1
                    break
                fi
            done
        fi
        if [ "$already" -eq 0 ]; then
            DEP_COUNT=$((DEP_COUNT + 1))
            if [ -z "$DEP_LINES" ]; then
                DEP_LINES="$inc"
            else
                DEP_LINES="${DEP_LINES}
${inc}"
            fi
        fi
    done
    IFS=$old_ifs
fi

printf 'starexec-valid=true\n'
if [ -n "$STATUS_VAL" ]; then
    SAFE_STATUS=$(prop_escape "$STATUS_VAL")
    printf 'Status=%s\n' "$SAFE_STATUS"
    printf 'starexec-expected-result=%s\n' "$SAFE_STATUS"
fi
if [ -n "$SPC_VAL" ]; then
    printf 'SPC=%s\n' "$(prop_escape "$SPC_VAL")"
fi
printf 'starexec-dependencies=%s\n' "$DEP_COUNT"

if [ "$DEP_COUNT" -gt 0 ]; then
    idx=0
    old_ifs=$IFS
    IFS='
'
    for inc in $DEP_LINES; do
        idx=$((idx + 1))
        printf 'starexec-dependency-%s=%s\n' "$idx" "$(prop_escape "$inc")"
    done
    IFS=$old_ifs
fi
