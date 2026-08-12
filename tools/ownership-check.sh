#!/usr/bin/env bash
# ownership-check.sh — enforces the two-agent ownership zones.
# Fails a PR if it touches files outside the owning agent's zone.
#
# Usage:
#   ./tools/ownership-check.sh <agent> <base-ref> <head-ref>
#     agent:    "A" or "B" (see docs/plan.md Two-Agent Execution Contract)
#     base-ref: merge base (e.g. origin/main)
#     head-ref: PR head (e.g. HEAD)
#
# Exit 0 = PR is inside the agent's zone. Exit 1 = violation (prints files).

set -euo pipefail

AGENT="${1:?usage: ownership-check.sh <A|B> <base> <head>}"
BASE="${2:?usage: ownership-check.sh <A|B> <base> <head>}"
HEAD="${3:?usage: ownership-check.sh <A|B> <base> <head>}"

# Fail closed: an unverifiable base means the gate cannot run, so it must
# not pass. A shallow clone without the base ref would otherwise yield an
# empty diff and "pass" the check.
if ! git rev-parse --verify "${BASE}" >/dev/null 2>&1; then
  echo "ownership check cannot run: base ref '${BASE}' does not exist (shallow clone?)" >&2
  exit 1
fi
if ! git rev-parse --verify "${HEAD}" >/dev/null 2>&1; then
  echo "ownership check cannot run: head ref '${HEAD}' does not exist" >&2
  exit 1
fi

# Ownership zones (paths are prefix-matched)
# Machine split (M0): Agent A = Arch Linux + Android testing.
# Agent B = macOS + iOS.
ZONE_A=(
  "core/src/commonMain"
  "core/src/desktopMain"
  "core/src/androidMain"
  "transport-xmpp"
  "app-linux"
  "app-android"
  "docs/design-spec.md"
  "docs/data-model.md"
  "docs/non-goals.md"
)
ZONE_B=(
  # Shared Compose Multiplatform UI. Moved from SHARED to Agent B by LO
  # ("we will take the ui"), reversing the Theme.kt pin from discussion #21.
  # docs/design-spec.md remains Agent A's: the spec is still the authority,
  # :ui is the implementation of it that B owns. A renders something the spec
  # does not say -> that is a B bug, and A should file it.
  "ui"
  "core/src/commonTest"
  "app-macos"
  "marketing"
  "tools/registry-builder"
  "tools/sync-test-server"
  "docs/e2ee.md"
  "docs/providers.md"
  "docs/non-goals.md"
)

# Paths owned by nobody (shared infra — either agent may touch, but changes
# require the other's review in the PR body)
SHARED=(
  "settings.gradle.kts"
  "build.gradle.kts"
  "gradle"
  "gradle.properties"
  "tools/ownership-check.sh"
  ".github"
  "README.md"
  "LICENSE"
  ".gitignore"
  "docs"
)

# Module-level build files are shared infra too (they configure targets for
# both agents — e.g. core/build.gradle.kts configures androidMain, which is
# Agent B's zone, and desktopMain, which is Agent A's). Either agent may edit
# them; the other agent reviews the change in the PR body.
is_module_build_file() {
  case "$1" in
    */build.gradle.kts) return 0 ;;
    */build.gradle)     return 0 ;;
    *)                  return 1 ;;
  esac
}

in_zone() {
  local file="$1"; shift
  for prefix in "$@"; do
    case "$file" in
      "$prefix"|"$prefix"/*) return 0 ;;
    esac
  done
  return 1
}

changed=$(git diff --name-only "${BASE}...${HEAD}")
violations=()

while IFS= read -r file; do
  [ -z "$file" ] && continue
  if in_zone "$file" "${SHARED[@]}" || is_module_build_file "$file"; then
    continue
  fi
  if [ "$AGENT" = "A" ]; then
    if ! in_zone "$file" "${ZONE_A[@]}"; then
      violations+=("$file")
    fi
  elif [ "$AGENT" = "B" ]; then
    if ! in_zone "$file" "${ZONE_B[@]}"; then
      violations+=("$file")
    fi
  else
    echo "unknown agent: $AGENT (must be A or B)" >&2
    exit 2
  fi
done <<< "$changed"

if [ "${#violations[@]}" -gt 0 ]; then
  echo "OWNERSHIP VIOLATION — agent $AGENT touched files outside its zone:" >&2
  printf '  %s\n' "${violations[@]}" >&2
  echo "See docs/plan.md 'Two-Agent Execution Contract' for the ownership matrix." >&2
  exit 1
fi

echo "ownership check passed: agent $AGENT touched only its own zone"
