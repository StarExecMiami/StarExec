#!/usr/bin/env bash
# Build a Helm repository directory that RETAINS previously published charts.
#
# Usage: helm-repo-prepare.sh <base-url> <out-dir> [<save-old-index-path>]
#                            [--allow-missing-index]
#
# The original workflow packaged only the current chart, regenerated index.yaml
# from scratch, and published with force_orphan, which rewrote gh-pages. Bumping
# the chart version that way silently deleted every previously published .tgz
# and its index entry, breaking `helm pull --version <old>` for anyone who had
# pinned one.
#
# This script instead:
#   1. downloads the currently published index.yaml;
#   2. downloads every package that index references;
#   3. packages the charts in this tree alongside them;
#   4. regenerates the index with --merge so old entries survive;
#   5. optionally saves the ORIGINAL published index to <save-old-index-path>.
#
# That saved copy is what makes retention checkable after the fact: once the
# merge has run, the index in <out-dir> no longer records what was published
# before, so a validator has nothing to compare against. It is written OUTSIDE
# <out-dir> so it never becomes a published file itself.
#
# -- Why the fetch fails CLOSED -----------------------------------------------
# This step used to be `if curl -fsSL ...; then ... else "first publication"`.
# Every way a fetch can fail -- 5xx, DNS, refused connection, timeout, a
# truncated body -- took the else branch and was reported as "there is no
# published repository yet". The consequences were silent and permanent:
#
#   transient 500
#     -> "first publication"
#     -> no packages downloaded, no --merge
#     -> the regenerated index advertises only the new version
#     -> keep_files keeps the orphaned .tgz, but clients read index.yaml, so
#        `helm pull --version <old>` fails
#     -> the retention validator compared against the same empty input and
#        reported that nothing had been dropped
#
# and it did not self-heal: the next run's "old index" is whatever is live then,
# which by that point no longer lists the dropped version.
#
# So: a missing repository is now something the CALLER declares with
# --allow-missing-index, never something inferred from a failure. Only HTTP 404
# is treated as "absent", and only when that flag is given. Everything else --
# transport failure, 5xx, an empty 200, malformed YAML, a document that is not a
# Helm index -- is fatal.
#
# The download also never lands directly on its destination. curl -o truncates
# the output file before it knows whether the request will succeed, so a failed
# fetch used to leave a zero-byte file where a good index had been. Everything
# is fetched to a temporary file, validated, and only then moved into place.
set -euo pipefail

# Bounded, overridable so the deterministic tests can run fast.
RETRIES="${HELM_INDEX_RETRIES:-3}"
RETRY_DELAY="${HELM_INDEX_RETRY_DELAY:-2}"
CONNECT_TIMEOUT="${HELM_INDEX_CONNECT_TIMEOUT:-10}"
MAX_TIME="${HELM_INDEX_MAX_TIME:-60}"

ALLOW_MISSING_INDEX=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --allow-missing-index) ALLOW_MISSING_INDEX=1 ;;
    --*) echo "helm-repo-prepare: unknown option: $arg" >&2; exit 2 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
set -- "${POSITIONAL[@]:-}"

BASE_URL="${1:?usage: helm-repo-prepare.sh <base-url> <out-dir> [save-old-index] [--allow-missing-index]}"
OUT_DIR="${2:?usage: helm-repo-prepare.sh <base-url> <out-dir> [save-old-index] [--allow-missing-index]}"
SAVE_OLD_INDEX="${3:-}"
BASE_URL="${BASE_URL%/}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT_DIR"

# Private prefix for the temporary files this script creates inside a
# destination directory. Defined once because the residue assertion at the end
# looks for exactly this pattern.
ATOMIC_TMP_PREFIX=".helm-index."

# Write $2 to $1 atomically, leaving no residue on ANY exit path.
#
# Same directory as the destination, so the rename cannot cross a filesystem
# boundary and degrade into a copy that can itself be interrupted half-written.
#
# A subshell rather than a plain function: the traps below must not disturb the
# script-level `trap ... EXIT` that removes $WORK, and a subshell's traps are
# scoped to the subshell.
#
# mktemp creates the temporary file before cat writes into it, so anything
# landing in that window -- disk full, OOM, a SIGTERM from a cancelled job --
# used to leave a .helm-index.* file behind. The destination survived intact,
# but peaceiris/actions-gh-pages publishes publish_dir wholesale, dotfiles
# included, so the orphan could reach gh-pages.
#
# Cleanup removes only THIS invocation's file, by its exact path: it never
# globs, so a concurrent run's temporary file and any unrelated hidden file are
# both untouched, and it can never remove the destination.
install_atomic() (
  set -euo pipefail
  dest="$1"; src="$2"
  tmp="$(mktemp "$(dirname "$dest")/${ATOMIC_TMP_PREFIX}XXXXXX")"

  # shellcheck disable=SC2329  # invoked from the traps below
  cleanup() { rm -f -- "$tmp"; }
  # Signals exit 128+signum so a cancelled job still reports as cancelled
  # rather than as an ordinary failure.
  # shellcheck disable=SC2329  # invoked from the traps below
  on_signal() { cleanup; trap - EXIT; exit "$((128 + $1))"; }
  trap cleanup EXIT
  trap 'on_signal 1' HUP
  trap 'on_signal 2' INT
  trap 'on_signal 15' TERM

  # An ordinary failure here (cat cannot read, no space left) exits non-zero
  # under set -e; the EXIT trap removes the temporary file and the original
  # status is preserved.
  cat -- "$src" > "$tmp"
  mv -f -- "$tmp" "$dest"

  # After the rename $tmp no longer exists, so the EXIT trap would be a
  # harmless no-op; clearing it makes that explicit rather than incidental.
  trap - EXIT HUP INT TERM
)

# Classify the response body. Exit status names the problem so the caller can
# report which of several very different failures actually happened.
validate_index() {
  python3 - "$1" <<'PY'
import sys
try:
    import yaml
except ImportError:
    print("PyYAML is not available to validate the index", file=sys.stderr)
    sys.exit(9)
path = sys.argv[1]
raw = open(path, "rb").read()
if not raw.strip():
    sys.exit(3)                      # empty
try:
    doc = yaml.safe_load(raw)
except Exception as exc:
    print(str(exc).splitlines()[0], file=sys.stderr)
    sys.exit(4)                      # malformed YAML
if not isinstance(doc, dict) or not isinstance(doc.get("entries"), dict):
    sys.exit(5)                      # parses, but is not a Helm repository index
print(len(doc["entries"]))
PY
}

EXISTING_INDEX="$WORK/existing-index.yaml"
DOWNLOAD="$WORK/index.download"
HAVE_EXISTING=0

INDEX_URL="$BASE_URL/index.yaml"
echo "helm-repo-prepare: fetching $INDEX_URL"

http_code=""
curl_rc=0
http_code="$(
  curl --silent --show-error --fail-with-body --location \
       --retry "$RETRIES" --retry-delay "$RETRY_DELAY" --retry-all-errors \
       --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
       --write-out '%{http_code}' \
       --output "$DOWNLOAD" \
       "$INDEX_URL" 2>"$WORK/curl.err"
)" || curl_rc=$?

if [ "$curl_rc" -ne 0 ]; then
  # 404 is the only failure that can legitimately mean "no repository yet", and
  # only the caller may say so. Everything else is a fetch that must not be
  # confused with an empty repository.
  if [ "$http_code" = "404" ]; then
    if [ "$ALLOW_MISSING_INDEX" -eq 1 ]; then
      echo "helm-repo-prepare: no published index (HTTP 404) and --allow-missing-index was given;"
      echo "helm-repo-prepare: treating this as the first publication"
    else
      echo "helm-repo-prepare: ERROR: published index missing (HTTP 404) at $INDEX_URL" >&2
      echo "helm-repo-prepare: refusing to publish a repository that would drop every" >&2
      echo "helm-repo-prepare: previously published version. If this really is the first" >&2
      echo "helm-repo-prepare: publication, pass --allow-missing-index explicitly." >&2
      exit 1
    fi
  else
    case "$curl_rc" in
      6)  reason="DNS resolution failed" ;;
      7)  reason="could not connect to the host" ;;
      28) reason="timed out after ${MAX_TIME}s" ;;
      22) reason="HTTP ${http_code:-error}" ;;
      *)  reason="transport failure (curl exit $curl_rc)" ;;
    esac
    echo "helm-repo-prepare: ERROR: could not fetch $INDEX_URL: $reason" >&2
    sed 's/^/helm-repo-prepare:   /' "$WORK/curl.err" >&2 || true
    echo "helm-repo-prepare: a failed fetch is NOT an empty repository." >&2
    echo "helm-repo-prepare: publishing now would drop every previously published" >&2
    echo "helm-repo-prepare: chart version from the index, so this is fatal." >&2
    exit 1
  fi
else
  if [ "$http_code" != "200" ]; then
    echo "helm-repo-prepare: ERROR: unexpected HTTP $http_code from $INDEX_URL" >&2
    exit 1
  fi
  vrc=0
  entry_count="$(validate_index "$DOWNLOAD" 2>"$WORK/validate.err")" || vrc=$?
  case "$vrc" in
    0) : ;;
    3) echo "helm-repo-prepare: ERROR: $INDEX_URL returned HTTP 200 but an empty body." >&2
       echo "helm-repo-prepare: an empty response is not an empty repository." >&2
       exit 1 ;;
    4) echo "helm-repo-prepare: ERROR: $INDEX_URL returned malformed YAML:" >&2
       sed 's/^/helm-repo-prepare:   /' "$WORK/validate.err" >&2 || true
       exit 1 ;;
    5) echo "helm-repo-prepare: ERROR: $INDEX_URL parsed as YAML but is not a Helm" >&2
       echo "helm-repo-prepare: repository index (no 'entries' mapping)." >&2
       exit 1 ;;
    *) echo "helm-repo-prepare: ERROR: could not validate $INDEX_URL" >&2
       sed 's/^/helm-repo-prepare:   /' "$WORK/validate.err" >&2 || true
       exit 1 ;;
  esac
  install_atomic "$EXISTING_INDEX" "$DOWNLOAD"
  HAVE_EXISTING=1
  echo "helm-repo-prepare: found published index ($entry_count chart(s)) at $INDEX_URL"
fi

if [ -n "$SAVE_OLD_INDEX" ]; then
  case "$SAVE_OLD_INDEX" in
    "$OUT_DIR"/*) echo "helm-repo-prepare: refusing to save the old index inside $OUT_DIR" >&2; exit 1 ;;
  esac
  if [ "$HAVE_EXISTING" -eq 1 ]; then
    install_atomic "$SAVE_OLD_INDEX" "$EXISTING_INDEX"
    echo "helm-repo-prepare: saved published index to $SAVE_OLD_INDEX"
  else
    # Explicit first publication: a well-formed EMPTY index, never a zero-byte
    # file. The validator can then tell "declared empty" apart from "we failed
    # to find out", which a truncated file cannot express.
    printf 'apiVersion: v1\nentries: {}\n' > "$WORK/empty-index.yaml"
    install_atomic "$SAVE_OLD_INDEX" "$WORK/empty-index.yaml"
    echo "helm-repo-prepare: wrote a valid empty index to $SAVE_OLD_INDEX (first publication)"
  fi
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
    tmp="$WORK/pkg.download"
    if curl --silent --show-error --fail --location \
            --retry "$RETRIES" --retry-delay "$RETRY_DELAY" --retry-all-errors \
            --connect-timeout "$CONNECT_TIMEOUT" --max-time 300 \
            --output "$tmp" "$url"; then
      install_atomic "$OUT_DIR/$fname" "$tmp"
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

# The traps above are the guarantee; this is the belt-and-braces check that the
# directory about to be published holds no internal temporary file. Scoped to
# this script's own private prefix, and it reports rather than deletes -- a
# residue here means the guarantee failed and that is worth failing loudly for.
leaked="$(find "$OUT_DIR" -maxdepth 1 -type f -name "${ATOMIC_TMP_PREFIX}*" 2>/dev/null || true)"
if [ -n "$leaked" ]; then
  echo "helm-repo-prepare: ERROR: internal temporary file(s) left in $OUT_DIR:" >&2
  printf '%s\n' "$leaked" | sed 's/^/helm-repo-prepare:   /' >&2
  echo "helm-repo-prepare: publishing this directory would copy them to gh-pages." >&2
  exit 1
fi

echo "helm-repo-prepare: repository ready in $OUT_DIR"
ls -1 "$OUT_DIR"
