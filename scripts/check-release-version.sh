#!/usr/bin/env bash
# Verify that a release tag agrees with the effective Maven version.
#
# Usage: check-release-version.sh <tag>          (default: $GITHUB_REF_NAME)
#
# Requires, and fails closed unless:
#   tag                          == "v" + <root project.version>
#   <root project.version>       == <starexec-app effective project.version>
#   <starexec-app parent version> == <root project.version>
#
# tomcat-credential-handler is deliberately NOT considered: it is independently
# versioned and is not a module of the root reactor, so including it would fail
# every release.
set -euo pipefail

TAG="${1:-${GITHUB_REF_NAME:-}}"
if [ -z "$TAG" ]; then
  echo "check-release-version: no tag given and GITHUB_REF_NAME is unset" >&2
  exit 1
fi

if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "check-release-version: tag '$TAG' is not a vX.Y.Z application release tag" >&2
  exit 1
fi

# Evaluate a project version through Maven's model. Never let a Maven failure
# abort this script silently: a broken reactor is itself a release blocker and
# must be reported, not swallowed by `set -e`.
mvn_version() { # $1 = label, $2 = extra -pl args (may be empty)
  local label="$1" plargs="$2" out rc
  # shellcheck disable=SC2086
  out="$(mvn $plargs help:evaluate -Dexpression=project.version -q -DforceStdout 2>&1)" && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "check-release-version: ERROR: Maven could not evaluate $label version (exit $rc)." >&2
    echo "check-release-version: this usually means the reactor is inconsistent," >&2
    echo "check-release-version: e.g. starexec-app's <parent> version does not match the root POM." >&2
    printf '%s\n' "$out" | tail -n 15 | sed 's/^/    | /' >&2
    return 1
  fi
  printf '%s' "$out" | tail -n 1 | tr -d '[:space:]'
}

ROOT_VERSION="$(mvn_version root "" )" || exit 1
APP_VERSION="$(mvn_version starexec-app "-pl starexec-app")" || exit 1

# parent version declared inside starexec-app/pom.xml's <parent> block
PARENT_VERSION="$(python3 - <<'PY'
import re, sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
t = ET.parse("starexec-app/pom.xml")
p = t.getroot().find("m:parent", ns)
if p is None:
    sys.exit("starexec-app/pom.xml has no <parent> element")
v = p.find("m:version", ns)
print("" if v is None else (v.text or "").strip())
PY
)"

fail=0
echo "check-release-version:"
echo "  tag                       : $TAG"
echo "  root project.version      : $ROOT_VERSION"
echo "  starexec-app effective    : $APP_VERSION"
echo "  starexec-app parent ref   : $PARENT_VERSION"

if [ -z "$ROOT_VERSION" ]; then
  echo "  ERROR: could not evaluate root project.version" >&2; fail=1
fi
if [ "v$ROOT_VERSION" != "$TAG" ]; then
  echo "  ERROR: tag '$TAG' != 'v$ROOT_VERSION' (root Maven version)" >&2; fail=1
fi
if [ "$ROOT_VERSION" != "$APP_VERSION" ]; then
  echo "  ERROR: root version '$ROOT_VERSION' != starexec-app effective '$APP_VERSION'" >&2; fail=1
fi
if [ "$PARENT_VERSION" != "$ROOT_VERSION" ]; then
  echo "  ERROR: starexec-app parent '$PARENT_VERSION' != root '$ROOT_VERSION'" >&2; fail=1
fi

[ "$fail" -eq 0 ] || { echo "check-release-version: FAILED" >&2; exit 1; }
echo "  OK: tag, root, module, and parent versions all agree"
