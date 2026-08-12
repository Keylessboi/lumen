#!/usr/bin/env bash
# agent-watcher.sh — keeps THIS agent alive and responsive to the other agent.
#
# Polls the lumen repo for .agent-inbox/ wake files (written by the
# wake-agents GitHub workflow on every discussion/comment event), then
# invokes the local agent command so the agent actually gets prompted.
#
# Usage:
#   ./tools/agent-watcher.sh                  # one poll cycle (cron/systemd)
#   ./tools/agent-watcher.sh --loop           # poll forever
#
# Config via env:
#   LUMEN_REPO_DIR   repo path (default: script's repo root)
#   LUMEN_AGENT_CMD  command run when a wake file is found.
#                    Default: notify-send + opencode run
#   LUMEN_INBOX_SAW  state file tracking processed wake files

set -euo pipefail

REPO_DIR="${LUMEN_REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
STATE_FILE="${LUMEN_INBOX_SAW:-$REPO_DIR/.agent-inbox/.saw}"
INBOX_DIR="$REPO_DIR/.agent-inbox"

# Default agent poke: a desktop notification + an opencode prompt.
# Override with LUMEN_AGENT_CMD if your agent differs (e.g. claude).
AGENT_CMD="${LUMEN_AGENT_CMD:-notify-send 'lumen: agent discussion' 'New wake file — read the discussion' && cd \"$REPO_DIR\" && opencode run 'A wake file landed in .agent-inbox/. Read it and respond to the other agent if it addresses your zone.'}"

mkdir -p "$INBOX_DIR"
touch "$STATE_FILE"

poll_once() {
  git -C "$REPO_DIR" fetch origin --quiet || true
  git -C "$REPO_DIR" pull --ff-only --quiet || true

  local found=0
  for f in "$INBOX_DIR"/*.md; do
    [ -e "$f" ] || continue
    local name
    name="$(basename "$f")"
    if ! grep -qF "$name" "$STATE_FILE"; then
      echo "$name" >> "$STATE_FILE"
      found=1
    fi
  done

  if [ "$found" -eq 1 ]; then
    echo "wake: new agent discussion activity"
    eval "$AGENT_CMD"
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
