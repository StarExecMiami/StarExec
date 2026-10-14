#!/bin/bash
# sync-sge-scripts.sh SRC DST -- make the job helpers in DST byte-identical to the image's SRC.
#
# The generated job script is built from the IMAGE's template (R.CONFIG_PATH/sge/jobscript),
# but it sources functions.bash from /app/data/sge_scripts, which lives on the data volume.
# The entrypoint used to copy the helpers there only when functions.bash was absent, so every
# upgrade replaced the template and kept the previous helper. On Quokka the 2026-10-01 image
# ran against the 2026-09-09 helper: the new script called stopDeadlockWatchdog, which the old
# helper does not define, so every job script died after its solver had finished and the EXIT
# trap filed the pair as ERROR_BENCHMARK -- 72,443 pairs of one job (#324). The two halves of
# one program must come from one release, so the image is authoritative.
#
# Guarantees:
#   - every regular file of SRC exists in DST with the same bytes, mode 755;
#   - a DST file that differed is not lost: it is moved to DST/.replaced-<UTC>/ first, so a
#     hand-applied patch can be recovered and re-applied deliberately (in the image);
#   - each file is replaced by rename, so a job starting meanwhile never sources a half-written
#     helper;
#   - files in DST that SRC does not have are left alone;
#   - exit status is non-zero if any of this could not be done, and the caller aborts startup.
set -euo pipefail

src=${1:?usage: sync-sge-scripts.sh SRC DST}
dst=${2:?usage: sync-sge-scripts.sh SRC DST}
[ -d "$src" ] || { echo "ERROR: $src is not a directory" >&2; exit 1; }

backup="$dst/.replaced-$(date -u +%Y%m%dT%H%M%SZ)"
installed=0
replaced=0
mkdir -p "$dst"
while IFS= read -r -d '' rel; do
    rel="${rel#./}"
    if [ -f "$dst/$rel" ] && cmp -s "$src/$rel" "$dst/$rel"; then
        continue
    fi
    if [ -e "$dst/$rel" ]; then
        mkdir -p "$(dirname "$backup/$rel")"
        mv "$dst/$rel" "$backup/$rel"
        echo "  replaced $rel (previous copy kept in $backup/$rel)"
        replaced=$((replaced + 1))
    fi
    mkdir -p "$(dirname "$dst/$rel")"
    cp "$src/$rel" "$dst/$rel.tmp.$$"
    chmod 755 "$dst/$rel.tmp.$$"
    mv -f "$dst/$rel.tmp.$$" "$dst/$rel"
    installed=$((installed + 1))
done < <(cd "$src" && find . -type f -print0)

# Checked once more as a whole, not trusted from the loop: the claim that matters is that
# the helpers the jobs will source are the image's.
while IFS= read -r -d '' rel; do
    if ! cmp -s "$src/$rel" "$dst/$rel"; then
        echo "ERROR: $dst/${rel#./} does not match the image after synchronisation" >&2
        exit 1
    fi
done < <(cd "$src" && find . -type f -print0)
echo "✓ SGE scripts match this image ($installed installed, $replaced replaced)"
