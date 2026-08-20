#!/usr/bin/env bash
# Build a Helm repository directory that RETAINS previously published charts.
#
# Usage: helm-repo-prepare.sh <base-url> <out-dir>
#
# The previous workflow packaged only the current chart, regenerated index.yaml
# from scratch, and published with force_orphan, which rewrote the gh-pages
# branch. Bumping the chart version that way silently deleted every previously
# published .tgz and its index entry, breaking `helm pull --version <old>` for
# anyone who had pinned one.
#
# This script instead:
#   1. downloads the currently published index.yaml (if any);
#   2. downloads every package that index references;
#   3. packages the charts in this tree alongside them;
#   4. regenerates the index with --merge so old entries survive.
#
# It is a no-op-safe bootstrap: if no index is published yet, it simply builds a
# fresh repository.
set -euo pipefail

BASE_URL="${1:?usage: helm-repo-prepare.sh <base-url> <out-dir>}"
OUT_DIR="${2:?usage: helm-repo-prepare.sh <base-url> <out-dir>}"
BASE_URL="${BASE_URL%/}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT_DIR"

EXISTING_INDEX="$WORK/existing-index.yaml"
HAVE_EXISTING=0
if curl -fsSL --max-time 60 -o "$EXISTING_INDEX" "$BASE_URL/index.yaml"; then
  HAVE_EXISTING=1
  echo "helm-repo-prepare: found published index at $BASE_URL/index.yaml"
else
  echo "helm-repo-prepare: no published index at $BASE_URL/index.yaml (first publication)"
fi

if [ "$HAVE_EXISTING" -eq 1 ]; then
  # Download each referenced package so it is republished rather than dropped.
  python3 - "$EXISTING_INDEX" > "$WORK/urls.txt" <<'PY'
import sys, yaml
with open(sys.argv[1]) as fh:
    doc = yaml.safe_load(fh) or {}
for entries in (doc.get("entries") or {}).values():
    for e in entries or []:
        for u in e.get("urls") or []:
            print(u)
PY
  while IFS= read -r url; do
    [ -n "$url" ] || continue
    fname="$(basename "$url")"
    if curl -fsSL --max-time 120 -o "$OUT_DIR/$fname" "$url"; then
      echo "helm-repo-prepare: retained $fname"
    else
      echo "helm-repo-prepare: ERROR: could not download published package: $url" >&2
      echo "helm-repo-prepare: refusing to publish an index that would drop it" >&2
      exit 1
    fi
  done < "$WORK/urls.txt"
fi

# Package the charts in this tree (overwrites same-version files intentionally).
helm package charts/* --destination "$OUT_DIR"

if [ "$HAVE_EXISTING" -eq 1 ]; then
  helm repo index "$OUT_DIR" --url "$BASE_URL" --merge "$EXISTING_INDEX"
else
  helm repo index "$OUT_DIR" --url "$BASE_URL"
fi

echo "helm-repo-prepare: repository ready in $OUT_DIR"
ls -1 "$OUT_DIR"
