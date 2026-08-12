# Lumen on Linux — applet and service

Two pieces make Lumen a good citizen of a Hyprland desktop rather than just a
window that has to be open:

- a **waybar applet** showing today's total and the focused app at a glance
  (the desktop equivalent of macOS's tray), and
- a **systemd user service** that runs the tracker headless, so closing the
  window stops showing the report — never stops measuring.

Both read the same SQLite store the window app writes
(`~/.local/share/lumen/lumen.db`), so the bar, the service and the window can
never disagree about a day.

## The waybar applet

`app-linux/scripts/lumen-applet.sh` is a waybar custom module. Every poll it:

1. sums today's rollups for the current UTC day, excluding the idle key
   (the lock screen is not screen time), and
2. asks `hyprctl activewindow` for the focused app's class — the same value
   the Hyprland collector records, never a window title (`docs/e2ee.md` §3).

### Install

1. Make it executable and point `LUMEN_DB` at your database if it is not the
   default:

   ```sh
   chmod +x app-linux/scripts/lumen-applet.sh
   # LUMEN_DB=/custom/path/lumen.db  # only if non-default
   ```

2. Add the module to `~/.config/waybar/config` (JSON):

   ```json
   "custom/lumen": {
     "exec": "/abs/path/to/app-linux/scripts/lumen-applet.sh",
     "interval": 5,
     "return-type": "json",
     "format": "{}"
   }
   ```

3. Add `"custom/lumen"` to the bar's `modules-left`/`center`/`right` list.

4. Optionally style it in `~/.config/waybar/style.css`:

   ```css
   #custom-lumen { color: #7C9CF5; font-variant-numeric: tabular-nums; }
   ```

### Output

A single JSON object per the waybar contract:

```json
{"text": "1h 23m · kitty", "tooltip": "Lumen: 1h 23m today — kitty focused", "class": "lumen"}
```

## The systemd service

`app-linux/systemd/lumen-tracker.service` runs `lumen --headless`: the same
collector → session tracker → SQLite pipeline the window uses, with no
Compose window. Two users of the same database (service + window) is expected
and safe — the window reads what the service wrote.

### Install

1. Build the packaged app (the service runs the real binary, not Gradle):

   ```sh
   ./gradlew :app-linux:createDistributable
   cp app-linux/scripts/lumen-tracker.sh \
      app-linux/build/compose/binaries/main/app/lumen/bin/
   ```

2. Point the `ExecStart` line at your installed launcher, then install:

   ```sh
   cp app-linux/systemd/lumen-tracker.service ~/.config/systemd/user/
   systemctl --user daemon-reload
   systemctl --user enable --now lumen-tracker.service
   ```

3. Verify:

   ```sh
   systemctl --user status lumen-tracker.service
   journalctl --user -u lumen-tracker -f
   sqlite3 ~/.local/share/lumen/lumen.db \
     "SELECT app_key, total_ms FROM rollups WHERE day_utc = date('now');"
   ```

### Notes

- The service runs the **plain-JVM launcher** (`bin/lumen-tracker.sh`), not the
  Compose binary. The Compose packaged binary wraps `main` in
  `libapplauncher.so`, which initialises AWT/Skiko and aborts with
  `__cxa_pure_virtual` under a headless unit (no display). The launcher loads
  only the tracking pipeline via `java -cp`.
- The service is a **user** unit, so it runs inside the graphical session and
  inherits `HYPRLAND_INSTANCE_SIGNATURE` — the collector's socket path. If the
  socket is missing, the collector reports `Unsupported` and the service logs
  it rather than dying silently.
- There is deliberately **no `KeepAlive`/auto-restart abuse**: `Restart=on-failure`
  recovers from crashes, but a manual `systemctl --user stop` stays stopped.
- Closing the window was previously the only way to stop tracking; with the
  service, quitting the windowed app is safe and the day keeps accumulating.
  Quit the service to actually stop measuring.
