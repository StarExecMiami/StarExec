#!/bin/sh
# Points this clone at the versioned git hooks in .githooks/.
# Run once per clone: scripts/install-git-hooks.sh
set -eu

root=$(git rev-parse --show-toplevel)
git -C "$root" config core.hooksPath .githooks
printf 'core.hooksPath set to .githooks in %s\n' "$root"
