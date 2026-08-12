#!/usr/bin/env bash
# lumen-applet.sh — waybar custom module for Lumen.
#
# Shows today's screen time and the currently focused app in the bar:
#   "1h 23m · kitty"
#
# Data sources:
#   - today's total: SQLite rollups for the current UTC day, excluding the
#     idle key (AppKey ""), summed. Same numbers the Today screen shows.
#   - live app: `hyprctl activewindow` class, matching what the Hyprland
#     collector records.
#
# Waybar config (JSON), typically ~/.config/waybar/config:
#   "custom/lumen": {
#     "exec": "/path/to/lumen-applet.sh",
#     "interval": 5,
#     "return-type": "json",
#     "format": "{}"
#   }
#
# Output is a single-line JSON object per the waybar custom-module contract:
# {"text": "1h 23m · kitty", "tooltip": "..."}

set -euo pipefail

DB="${LUMEN_DB:-$HOME/.local/share/lumen/lumen.db}"

format_ms() {
    local ms=$1
    local s=$((ms / 1000))
    local h=$((s / 3600))
    local m=$(((s % 3600) / 60))
    if ((h > 0)); then
        printf "%dh %dm" "$h" "$m"
    elif ((m > 0)); then
        printf "%dm" "$m"
    else
        printf "%ds" "$s"
    fi
}

# Today's rollups, excluding the idle key (blank app_key).
total_ms=$(sqlite3 "$DB" \
    "SELECT COALESCE(SUM(total_ms), 0) FROM rollups
     WHERE day_utc = date('now') AND app_key != '';" 2>/dev/null || echo 0)

# Live app from the compositor, class only (never a window title).
live_app=$(hyprctl activewindow -j 2>/dev/null \
    | sed -n 's/.*"class": *"\([^"]*\)".*/\1/p' | head -1)

if [ -n "$live_app" ]; then
    text="$(format_ms "$total_ms") · $live_app"
    tooltip="Lumen: $(format_ms "$total_ms") today — $live_app focused"
else
    text="$(format_ms "$total_ms")"
    tooltip="Lumen: $(format_ms "$total_ms") today"
fi

printf '{"text": "%s", "tooltip": "%s", "class": "lumen"}\n' "$text" "$tooltip"
