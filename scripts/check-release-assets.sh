#!/usr/bin/env bash
# Verify the release artifacts exist and are non-empty, then emit SHA256SUMS.
#
# Usage: check-release-assets.sh [output-dir]
#   output-dir defaults to the directory holding the artifacts.
#
# Fails closed if either artifact is missing, not a regular file, or empty.
# SHA256SUMS contains basenames only, in deterministic order, so it carries no
# machine-specific absolute paths.
set -euo pipefail

WAR="starexec-app/target/starexec.war"
ZIP="starexec-app/target/starexeccommand.zip"
OUT_DIR="${1:-starexec-app/target}"

fail=0
for f in "$WAR" "$ZIP"; do
  if [ ! -e "$f" ]; then
    echo "check-release-assets: ERROR: missing artifact: $f" >&2; fail=1; continue
  fi
  if [ ! -f "$f" ]; then
    echo "check-release-assets: ERROR: not a regular file: $f" >&2; fail=1; continue
  fi
  if [ ! -s "$f" ]; then
    echo "check-release-assets: ERROR: artifact is empty: $f" >&2; fail=1; continue
  fi
  echo "check-release-assets: OK $f ($(wc -c <"$f") bytes)"
done
[ "$fail" -eq 0 ] || { echo "check-release-assets: FAILED" >&2; exit 1; }

mkdir -p "$OUT_DIR"
SUMS="$OUT_DIR/SHA256SUMS"
# deterministic order: sorted by basename, basenames only
( cd "$(dirname "$WAR")" && sha256sum "$(basename "$WAR")" "$(basename "$ZIP")" | LC_ALL=C sort -k2 ) > "$SUMS"

if grep -q '/' "$SUMS"; then
  echo "check-release-assets: ERROR: SHA256SUMS contains a path separator" >&2
  cat "$SUMS" >&2
  exit 1
fi

echo "check-release-assets: wrote $SUMS"
cat "$SUMS"
