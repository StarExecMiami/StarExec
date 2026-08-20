#!/usr/bin/env bash
# Behavioural proof that a hostile Git ref is treated as data.
#
# `git tag` refuses most metacharacters, but the ref namespace does not:
#   git update-ref 'refs/tags/v9.9.9";touch /tmp/pwned;"' <sha>
#   git push origin 'refs/tags/v9.9.9";touch /tmp/pwned;"'
# both succeed, and GitHub then hands that string to the workflow as
# github.ref_name. This test drives validate-release-tag.sh with exactly such a
# value and proves three things:
#   1. the validator rejects it (non-zero exit);
#   2. no embedded command executed (sentinel file absent);
#   3. legitimate tags still pass.
#
# It never creates or pushes a real hostile tag anywhere.
set -uo pipefail

VALIDATOR="$(dirname "$0")/validate-release-tag.sh"
SENTINEL="${TMPDIR:-/tmp}/release-ref-executed"
fail=0

rm -f "$SENTINEL"

run_case() { # $1 = expected rc (0|1), $2 = label, $3 = ref value
  local want="$1" label="$2" ref="$3" rc
  RELEASE_TAG="$ref" GITHUB_OUTPUT="" "$VALIDATOR" >/dev/null 2>&1 && rc=0 || rc=1
  if [ "$rc" -eq "$want" ]; then
    printf '  OK   %-38s %s\n' "$label" "$([ "$want" -eq 0 ] && echo accepted || echo rejected)"
  else
    printf '  FAIL %-38s expected rc=%s got rc=%s\n' "$label" "$want" "$rc" >&2
    fail=1
  fi
}

echo "check-release-tag-safety: hostile refs must be rejected as data"
# The sentinel payload. If any layer evaluates this string as shell, the file
# appears and the assertion below fails.
run_case 1 "command substitution"      'v9.9.9";touch${IFS}'"$SENTINEL"';"'
run_case 1 "backtick substitution"     'v1.0.0`touch '"$SENTINEL"'`'
run_case 1 "dollar-paren substitution" 'v1.0.0$(touch '"$SENTINEL"')'
run_case 1 "path traversal"            'v1.0.0/../../etc/passwd'
run_case 1 "newline injection"         'v1.0.0
malicious'
run_case 1 "release- prefix"           'release-2.6.0'
run_case 1 "incomplete version"        'v2.6'
run_case 1 "pre-release suffix"        'v2.6.0-rc1'
run_case 1 "empty ref"                 ''

echo "check-release-tag-safety: legitimate refs must be accepted"
run_case 0 "canonical release tag"     'v2.6.0'
run_case 0 "multi-digit components"    'v10.20.30'

echo "check-release-tag-safety: sentinel assertion"
if [ -e "$SENTINEL" ]; then
  echo "  FAIL sentinel exists -- a ref was EXECUTED, not treated as data: $SENTINEL" >&2
  rm -f "$SENTINEL"
  fail=1
else
  echo "  OK   no sentinel created: refs were never executed"
fi

[ "$fail" -eq 0 ] || { echo "check-release-tag-safety: FAILED" >&2; exit 1; }
echo "check-release-tag-safety: PASSED"
