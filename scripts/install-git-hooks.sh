#!/bin/sh
# Points this clone at the versioned git hooks in .githooks/.
# Run once per clone: scripts/install-git-hooks.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/lib/cli-output.sh"

root=$(git rev-parse --show-toplevel)
git -C "$root" config core.hooksPath .githooks
ui_success "core.hooksPath set to .githooks in $root"
