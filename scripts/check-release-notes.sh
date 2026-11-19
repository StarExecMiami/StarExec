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
  # Image publication must be described prospectively. release.yml and
  # container-publish.yml both fire on the tag but neither waits for the other,
  # so notes that assert images "are published" state as fact something this
  # workflow cannot observe -- and that a failed image build would make false.
  "triggers publication of application-image tags"
  "Post-publication checklist"
)

# Wordings that assert image publication as accomplished fact. The release body
# is written before either publishing workflow has finished, so these can only
# ever be a guess presented as a result.
declare -a FORBIDDEN=(
  "images for this release are published as"
  "Container images for this release are published"
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

for f in "${FORBIDDEN[@]}"; do
  if grep -qF -- "$f" "$NOTES"; then
    echo "check-release-notes: ERROR: prospective-wording violation: notes assert image" >&2
    echo "check-release-notes:        publication as fact via: \"$f\"" >&2
    echo "check-release-notes:        describe what the tag TRIGGERS, and require verification." >&2
    fail=1
  else
    echo "check-release-notes: OK no unconditional publication claim: $f"
  fi
done

[ "$fail" -eq 0 ] || { echo "check-release-notes: FAILED ($NOTES)" >&2; exit 1; }
echo "check-release-notes: $NOTES is cumulative and complete"
