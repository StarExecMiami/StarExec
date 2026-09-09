#!/usr/bin/env bash
# Validate an application release ref BEFORE it is used for anything.
#
# Usage: validate-release-tag.sh            (reads $RELEASE_TAG)
#
# A Git ref is attacker-influenced data, not a trusted identifier. `git tag`
# refuses most metacharacters, but the underlying ref namespace does not:
# `git update-ref refs/tags/'v9.9.9";id;"' <sha>` followed by `git push` creates
# a ref that GitHub will happily hand to a workflow as github.ref_name. If that
# value is interpolated into a `run:` block, the shell parses it; if it is
# interpolated into an actions/github-script `script:` block, the JavaScript
# engine parses it. Either is remote code execution in a job holding a token.
#
# Two defences, and both are needed:
#   1. every consumer receives the ref through the environment, never through
#      ${{ }} interpolation into an executable context (see release.yml);
#   2. this validator rejects anything that is not exactly a release tag, and it
#      runs before the build, before artifact generation, before issue creation
#      and before publication.
#
# The pattern is deliberately stricter than check-release-version.sh's: no
# pre-release suffixes. A -rc/-beta/-alpha tag has never been published by this
# project, and accepting one here would mean deriving a filesystem path from it.
#
# Outputs (to $GITHUB_OUTPUT when set):
#   tag         the validated ref, unchanged
#   version     the ref without its leading "v"
#   notes_path  docs/release-notes/<tag>.md, safe to open because <tag> matched
set -euo pipefail

STRICT_PATTERN='^v[0-9]+\.[0-9]+\.[0-9]+$'

TAG="${RELEASE_TAG:-}"

# printf %q so a hostile value is shown quoted rather than pasted raw into the
# log, and so an embedded newline cannot forge a second log line.
reject() {
  printf 'validate-release-tag: REJECTED %s\n' "$(printf '%q' "$TAG")" >&2
  printf 'validate-release-tag: %s\n' "$1" >&2
  printf 'validate-release-tag: required pattern: %s\n' "$STRICT_PATTERN" >&2
  printf 'validate-release-tag: expected form: v<major>.<minor>.<patch>, e.g. v2.6.0\n' >&2
  exit 1
}

[ -n "$TAG" ] || {
  echo "validate-release-tag: RELEASE_TAG is empty or unset" >&2
  echo "validate-release-tag: the workflow must pass the ref through env, not \${{ }}" >&2
  exit 1
}

# Reported separately from the pattern failure purely so the log names the real
# problem: these are the shapes a human is most likely to have typed by mistake.
case "$TAG" in
  release-*) reject "'release-*' is not an application release tag; use vX.Y.Z" ;;
  */*)       reject "a ref containing '/' must never become a filesystem path" ;;
  *..*)      reject "a ref containing '..' must never become a filesystem path" ;;
esac

[[ "$TAG" =~ $STRICT_PATTERN ]] || \
  reject "not a vX.Y.Z application release tag (pre-release suffixes are not published by this project)"

VERSION="${TAG#v}"
NOTES_PATH="docs/release-notes/${TAG}.md"

echo "validate-release-tag: OK"
echo "  tag        : $TAG"
echo "  version    : $VERSION"
echo "  notes_path : $NOTES_PATH"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "tag=$TAG"
    echo "version=$VERSION"
    echo "notes_path=$NOTES_PATH"
  } >> "$GITHUB_OUTPUT"
fi
