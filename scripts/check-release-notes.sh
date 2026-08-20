#!/usr/bin/env bash
# Verify the curated release notes exist and are cumulative.
#
# Usage: check-release-notes.sh [notes-file]
#   default: docs/release-notes/v<root project.version>.md
#
# The v2.6.0 release is the first public release since v2.4.0 and must carry the
# changes prepared under the unpublished 2.5.0 and 2.5.1 source versions. This
# check fails closed if any expected section marker is absent.
set -euo pipefail

NOTES="${1:-}"
if [ -z "$NOTES" ]; then
  V="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null | tail -n 1 | tr -d '[:space:]')"
  NOTES="docs/release-notes/v${V}.md"
fi

if [ ! -s "$NOTES" ]; then
  echo "check-release-notes: ERROR: missing or empty notes file: $NOTES" >&2
  exit 1
fi

declare -a MARKERS=(
  "first public StarExec application release since v2.4.0"
  "2.5.0"
  "2.5.1"
  "2.6.0"
)

fail=0
for m in "${MARKERS[@]}"; do
  if grep -qF -- "$m" "$NOTES"; then
    echo "check-release-notes: OK marker present: $m"
  else
    echo "check-release-notes: ERROR: missing required marker: $m" >&2
    fail=1
  fi
done

[ "$fail" -eq 0 ] || { echo "check-release-notes: FAILED ($NOTES)" >&2; exit 1; }
echo "check-release-notes: $NOTES is cumulative and complete"
