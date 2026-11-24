#!/bin/sh
# StarExec SZS post-processor entrypoint (POSIX sh; zero Python/Perl dependencies).
# Designed for starexec-job-runner (Alpine Linux: sh, coreutils, grep, sed, tr).
#
# Formal SZS Status & Multi-Line Policy:
#   1. Scans solver output lines matching '% SZS status <Token>...' (CR stripped).
#   2. Validates <Token> against the canonical SZS status ontology.
#   3. 0 SZS status lines       -> starexec-result=starexec-unknown,
#                                  szs-multi-status-policy=consistent
#   4. >=1 identical valid lines -> starexec-result=<Status>,
#                                  szs-multi-status-policy=consistent
#   5. Distinct SZS statuses    -> starexec-result=Error,
#                                  szs-multi-status-policy=conflict
#   6. Invalid/empty SZS token  -> starexec-result=Error,
#                                  szs-multi-status-policy=invalid
#   7. Records '% SZS output start/end' metadata via szs-has-output and
#      szs-output-balanced (plus szs-output-types when start tags carry a type).

set -eu
LC_ALL=C
export LC_ALL

is_valid_szs_status() {
    case "$1" in
        Success|Unsatisfiability|Satisfiability|Theorem|CounterTheorem|\
        Equivalent|TautologousConclusion|WeakerConclusion|EquivalentTheorem|\
        Tautology|WeakerTautologousConclusion|WeakerTheorem|\
        ContradictoryAxioms|Satisfiable|Unsatisfiable|NoConsequence|\
        CounterSatisfiable|CounterEquivalent|UnsatisfiableConclusion|\
        WeakerCounterTheorem|WeakerUnsatisfiableConclusion|CounterTautology|\
        WeakerCounterSatisfiable|SatisfiabilityBijection|FiniteTheorem|\
        FiniteCounterTheorem|NoSuccess|Open|Unknown|Assumed|Stopped|\
        InProgress|NotTried|NotTriedYet|GaveUp|Appropriate|Inappropriate|\
        Timeout|Forced|User|ResourceOut|MemoryOut|InputError|SyntaxError|\
        SemanticError|TypeError|Error|OSError|CantWait|Incomplete)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

append_unique() {
    existing=$1
    item=$2
    if [ -z "$item" ]; then
        printf '%s' "$existing"
        return 0
    fi
    if [ -z "$existing" ]; then
        printf '%s' "$item"
        return 0
    fi
    case ",$existing," in
        *",$item,"*)
            printf '%s' "$existing"
            ;;
        *)
            printf '%s,%s' "$existing" "$item"
            ;;
    esac
}

if [ "$#" -lt 1 ] || [ -z "$1" ] || [ ! -f "$1" ] || [ ! -r "$1" ]; then
    printf 'starexec-result=Error\n'
    printf 'szs-status-count=0\n'
    printf 'szs-multi-status-policy=invalid\n'
    printf 'szs-observed-statuses=none\n'
    printf 'szs-has-output=false\n'
    printf 'szs-output-balanced=true\n'
    printf 'szs-error=missing-solver-output\n'
    exit 0
fi

OUT_FILE="$1"

STATUS_LINES=$(tr -d '\r' < "$OUT_FILE" | grep -E '^[[:space:]]*%[[:space:]]*SZS[[:space:]]+status([[:space:]]|$)' || true)

STATUS_COUNT=0
FIRST_VALID=""
HAS_CONFLICT=0
HAS_INVALID=0
OBSERVED_UNIQUE=""

if [ -n "$STATUS_LINES" ]; then
    old_ifs=$IFS
    IFS='
'
    for raw_line in $STATUS_LINES; do
        STATUS_COUNT=$((STATUS_COUNT + 1))
        token=$(printf '%s\n' "$raw_line" | sed -e 's/^[[:space:]]*%[[:space:]]*SZS[[:space:]]\{1,\}status[[:space:]]*//' -e 's/[[:space:]:].*$//')
        clean_tok=$(printf '%s' "$token" | tr -cd 'A-Za-z0-9_-')
        if [ -z "$token" ] || [ "$token" != "$clean_tok" ] || ! is_valid_szs_status "$token"; then
            HAS_INVALID=1
            if [ -n "$clean_tok" ]; then
                OBSERVED_UNIQUE=$(append_unique "$OBSERVED_UNIQUE" "$clean_tok")
            else
                OBSERVED_UNIQUE=$(append_unique "$OBSERVED_UNIQUE" "empty")
            fi
        else
            OBSERVED_UNIQUE=$(append_unique "$OBSERVED_UNIQUE" "$token")
            if [ -z "$FIRST_VALID" ]; then
                FIRST_VALID="$token"
            elif [ "$token" != "$FIRST_VALID" ]; then
                HAS_CONFLICT=1
            fi
        fi
    done
    IFS=$old_ifs
fi

OUTPUT_START_LINES=$(tr -d '\r' < "$OUT_FILE" | grep -E '^[[:space:]]*%[[:space:]]*SZS[[:space:]]+output[[:space:]]+start([[:space:]]|$)' || true)
OUTPUT_END_LINES=$(tr -d '\r' < "$OUT_FILE" | grep -E '^[[:space:]]*%[[:space:]]*SZS[[:space:]]+output[[:space:]]+end([[:space:]]|$)' || true)

START_COUNT=0
END_COUNT=0
OUTPUT_TYPES=""

if [ -n "$OUTPUT_START_LINES" ]; then
    old_ifs=$IFS
    IFS='
'
    for oline in $OUTPUT_START_LINES; do
        START_COUNT=$((START_COUNT + 1))
        otype=$(printf '%s\n' "$oline" | sed -e 's/^[[:space:]]*%[[:space:]]*SZS[[:space:]]\{1,\}output[[:space:]]\{1,\}start[[:space:]]*//' -e 's/[[:space:]:].*$//' | tr -cd 'A-Za-z0-9_-')
        if [ -n "$otype" ]; then
            OUTPUT_TYPES=$(append_unique "$OUTPUT_TYPES" "$otype")
        fi
    done
    IFS=$old_ifs
fi

if [ -n "$OUTPUT_END_LINES" ]; then
    old_ifs=$IFS
    IFS='
'
    for _eline in $OUTPUT_END_LINES; do
        END_COUNT=$((END_COUNT + 1))
    done
    IFS=$old_ifs
fi

if [ "$START_COUNT" -gt 0 ]; then
    HAS_OUTPUT="true"
else
    HAS_OUTPUT="false"
fi

if [ "$START_COUNT" -eq "$END_COUNT" ]; then
    OUTPUT_BALANCED="true"
else
    OUTPUT_BALANCED="false"
fi

if [ "$HAS_INVALID" -eq 1 ]; then
    RESULT="Error"
    POLICY="invalid"
elif [ "$STATUS_COUNT" -eq 0 ]; then
    RESULT="starexec-unknown"
    POLICY="consistent"
elif [ "$HAS_CONFLICT" -eq 1 ]; then
    RESULT="Error"
    POLICY="conflict"
else
    RESULT="$FIRST_VALID"
    POLICY="consistent"
fi

if [ -z "$OBSERVED_UNIQUE" ]; then
    OBSERVED_UNIQUE="none"
fi

printf 'starexec-result=%s\n' "$RESULT"
printf 'szs-status-count=%s\n' "$STATUS_COUNT"
printf 'szs-multi-status-policy=%s\n' "$POLICY"
printf 'szs-observed-statuses=%s\n' "$OBSERVED_UNIQUE"
printf 'szs-has-output=%s\n' "$HAS_OUTPUT"
printf 'szs-output-balanced=%s\n' "$OUTPUT_BALANCED"
if [ -n "$OUTPUT_TYPES" ]; then
    printf 'szs-output-types=%s\n' "$OUTPUT_TYPES"
fi
