#!/usr/bin/env bash
# lumen-applet.sh — waybar custom module for Lumen.
#
# Shows today's screen time and the currently focused app in the bar:
#   "1h 23m · kitty"
#
# Data sources:
#   - today's total: this device's minute buckets since LOCAL midnight,
#     excluding the idle key (AppKey ""). Same numbers the Today screen shows.
#     (It summed the UTC day's rollups, which reset the bar at 8 pm for a
#     UTC-4 user — the bug the Today screen fixed in #29 — and, once sync
#     landed, would also have counted other devices.)
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

# Local midnight as epoch ms: date('now','localtime') is today's local date;
# strftime('%s', <that>, 'utc') converts that local midnight to UTC seconds.
total_ms=$(sqlite3 "$DB" \
    "SELECT COALESCE(SUM(active_ms), 0) FROM buckets
     WHERE app_key != ''
       AND bucket_ts >= CAST(strftime('%s', date('now', 'localtime'), 'utc') AS INTEGER) * 1000
       AND device_id = (SELECT CAST(value AS TEXT) FROM settings WHERE key = 'device_id');" 2>/dev/null || echo 0)

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
