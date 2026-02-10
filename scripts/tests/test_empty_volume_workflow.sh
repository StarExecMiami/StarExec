#!/usr/bin/env bash
set -euo pipefail

# Quick functional test: verifies empty-volume placeholder creation & restore behavior
TMPDIR=$(mktemp -d)
export BACKUP_DIR="$TMPDIR"

echo "Running backup-all into: $BACKUP_DIR"
/mnt/steam-games/GitHub/StarExec/scripts/podman-volumes.sh backup-all dev

LATEST_TS=$(ls -1 "$BACKUP_DIR"/starexec-dev-full-* -1 | sed -n '1p' | sed -n 's/.*full-\([0-9]\{8\}-[0-9]\{6\}\).*/\1/p')
if [ -z "$LATEST_TS" ]; then
  echo "No backup timestamp found - aborting test"
  exit 1
fi

echo "Latest timestamp: $LATEST_TS"

echo "Checking for manifest and emptyvols"
if [ ! -f "$BACKUP_DIR/starexec-dev-full-$LATEST_TS-emptyvols.txt" ]; then
  echo "Empty-volumes file missing - test failed"
  exit 1
fi
if [ ! -f "$BACKUP_DIR/starexec-dev-full-$LATEST_TS-manifest.json" ]; then
  echo "Manifest missing - test failed"
  exit 1
fi

# Prepare restore copies for test env
mkdir -p "$TMPDIR/restore"
cd "$TMPDIR"
for f in starexec-dev-full-$LATEST_TS-*.tar.gz; do cp "$f" "${f/starexec-dev-/starexec-test-}"; done
cp starexec-dev-full-$LATEST_TS-emptyvols.txt "starexec-test-full-$LATEST_TS-emptyvols.txt"

# Run restore for test env
echo "Running restore-all test $LATEST_TS"
BACKUP_DIR="$TMPDIR" /mnt/steam-games/GitHub/StarExec/scripts/podman-volumes.sh restore-all test $LATEST_TS

echo "Test completed - check logs above for warnings about skipping empty archives"

echo "Artifacts left in: $TMPDIR"

exit 0
