#!/usr/bin/env bash
# Verify a proposed Helm repository index still offers previously published
# chart versions.
#
# Usage: check-helm-retention.sh <repo-dir> <version> [<version> ...]
#
# helm-repo.yml merges the published index before regenerating it and publishes
# with keep_files, so previously published .tgz files and their index entries
# survive. This check fails closed if any required version is absent from the
# proposed index or its package file is missing.
set -euo pipefail

REPO_DIR="${1:-}"
shift || true
if [ -z "$REPO_DIR" ] || [ "$#" -eq 0 ]; then
  echo "usage: check-helm-retention.sh <repo-dir> <version> [<version> ...]" >&2
  exit 1
fi

INDEX="$REPO_DIR/index.yaml"
if [ ! -s "$INDEX" ]; then
  echo "check-helm-retention: ERROR: missing or empty index: $INDEX" >&2
  exit 1
fi

fail=0
for v in "$@"; do
  if python3 - "$INDEX" "$v" <<'PY'
import sys, yaml
idx, want = sys.argv[1], sys.argv[2]
with open(idx) as fh:
    d = yaml.safe_load(fh) or {}
versions = [e.get("version") for e in (d.get("entries", {}).get("starexec") or [])]
sys.exit(0 if want in versions else 1)
PY
  then
    echo "check-helm-retention: OK index lists starexec $v"
  else
    echo "check-helm-retention: ERROR: index does not list starexec $v" >&2
    fail=1
  fi

  pkg="$REPO_DIR/starexec-$v.tgz"
  if [ -s "$pkg" ]; then
    echo "check-helm-retention: OK package present: $(basename "$pkg")"
  else
    echo "check-helm-retention: ERROR: missing package: $pkg" >&2
    fail=1
  fi
done

[ "$fail" -eq 0 ] || { echo "check-helm-retention: FAILED" >&2; exit 1; }
echo "check-helm-retention: all required chart versions retained"
