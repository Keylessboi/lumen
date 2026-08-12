#!/usr/bin/env bash
# agent-watcher.sh — reads the agent inbox and reports new wake pointers.
#
# SECURITY MODEL (B's findings, accepted): wake files are DATA, never
# instructions. They carry a discussion URL + title only. This script
# NEVER executes anything from a wake file and NEVER auto-invokes an
# agent from remote text. It reports to the human; the human starts the
# agent. For urgent items, use @-mentions (GitHub notifies the human).
#
# Wake files live on the `agent-inbox` BRANCH (not main). This script
# reads them from the remote ref directly — it does NOT merge or
# checkout, so the working tree is never touched.
#
# Usage:
#   ./tools/agent-watcher.sh            # one poll cycle
#   ./tools/agent-watcher.sh --loop     # poll forever (prints only)
#
# Config via env:
#   LUMEN_REPO_DIR   repo path (default: script's repo root)
#   LUMEN_INBOX_SAW  state file tracking processed wake files
#   LUMEN_POLL_SECS  loop interval (default 60)
#   LUMEN_REMOTE     remote to fetch (default origin)

set -euo pipefail

REPO_DIR="${LUMEN_REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
STATE_FILE="${LUMEN_INBOX_SAW:-$REPO_DIR/.agent-inbox/.saw}"
INBOX_DIR="$REPO_DIR/.agent-inbox"
REMOTE="${LUMEN_REMOTE:-origin}"
INBOX_BRANCH="agent-inbox"

mkdir -p "$INBOX_DIR"
touch "$STATE_FILE"

# Human-facing notification. Default: desktop notify, no agent spawn.
notify() {
  if command -v notify-send >/dev/null 2>&1; then
    notify-send "lumen: agent inbox" "$1" 2>/dev/null || true
  fi
  echo "agent-inbox: $1"
}

poll_once() {
  # Fetch the inbox branch ref. Never merge, never checkout.
  git -C "$REPO_DIR" fetch "$REMOTE" "$INBOX_BRANCH" --quiet 2>/dev/null || return 0

  local ref="$REMOTE/$INBOX_BRANCH"
  local new_count=0

  # List wake files present on the remote branch.
  local files
  files="$(git -C "$REPO_DIR" ls-tree -r --name-only "$ref" -- .agent-inbox/ 2>/dev/null || true)"

  while IFS= read -r path; do
    [ -n "$path" ] || continue
    case "$path" in
      *.md) ;;
      *) continue ;;
    esac
    local name
    name="$(basename "$path")"
    if ! grep -qF "$name" "$STATE_FILE"; then
      echo "$name" >> "$STATE_FILE"
      new_count=$((new_count + 1))
      # Print the pointer only — title + URL, never treated as a command.
      git -C "$REPO_DIR" show "$ref:$path" 2>/dev/null | grep -E '^#|URL' || true
    fi
  done <<< "$files"

  if [ "$new_count" -gt 0 ]; then
    notify "new wake pointers: $new_count (see .agent-inbox/)"
  fi
}

if [ "${1:-}" = "--loop" ]; then
  while true; do
    poll_once
    sleep "${LUMEN_POLL_SECS:-60}"
  done
else
  poll_once
fi
