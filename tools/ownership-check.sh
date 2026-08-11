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

# Ownership zones (paths are prefix-matched)
ZONE_A=(
  "core/src/commonMain"
  "core/src/commonTest"
  "core/src/desktopMain"
  "transport-xmpp"
  "app-linux"
  "tools/registry-builder"
  "docs/design-spec.md"
  "docs/data-model.md"
  "docs/non-goals.md"
)
ZONE_B=(
  "core/src/androidMain"
  "app-android"
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
)

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
  if in_zone "$file" "${SHARED[@]}"; then
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
