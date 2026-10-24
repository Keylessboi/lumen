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

1. sums this device's minute buckets since **local** midnight, excluding the
   idle key (the lock screen is not screen time) — it used to sum the UTC
   day, so the bar reset at 8 pm for a UTC-4 user — and
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

`app-linux/systemd/lumen-tracker.service` runs the background pipeline —
focus tracking, sync and scrobbling — with no Compose window.

Only **one** process tracks at a time. The window and the service share the
device id and the database, and two trackers numbering events from the same
seq collide on the primary key (one silently loses its events); two
scrobblers send every song twice. A lock in `~/.local/share/lumen/tracker.lock`
decides: whoever holds it tracks, syncs and scrobbles. With the service
running, the window is a viewer — it re-reads the store every 30 s, and
sign-ins made there are used by the service.

The collector is picked for the session: Hyprland, then Sway, then X11
(`LinuxCollectors`). Before, both entry points built the Hyprland collector
unconditionally, which threw on any other desktop.

### Install

```sh
./gradlew :app-linux:createDistributable
app-linux/scripts/install-service.sh
```

`install-service.sh` copies the launcher into the app's `bin/`, writes the
absolute path of your install into the unit (the old unit hardcoded one
developer's home directory), and enables it. Optional argument: another app
directory. The launcher prefers the Java runtime bundled with the app.

Verify:

```sh
systemctl --user status lumen-tracker.service
journalctl --user -u lumen-tracker -f
```

The unit is bound to `graphical-session.target`. Compositors must export the
session environment to systemd at startup (Hyprland:
`exec-once = dbus-update-activation-environment --systemd --all`), or the
service cannot see `HYPRLAND_INSTANCE_SIGNATURE`/`SWAYSOCK`/`DISPLAY` or the
session bus that media players live on.

### Notes

- The service runs the **plain-JVM launcher** (`bin/lumen-tracker.sh`), not the
  Compose binary. The Compose packaged binary wraps `main` in
  `libapplauncher.so`, which initialises AWT/Skiko and aborts with
  `__cxa_pure_virtual` under a headless unit (no display). The launcher loads
  only the tracking pipeline via `java -cp`.
- The service is a **user** unit inside the graphical session. If no supported
  session is found, focus tracking reports why in the journal and the service
  keeps syncing and scrobbling rather than dying.
- There is deliberately **no `KeepAlive`/auto-restart abuse**: `Restart=on-failure`
  recovers from crashes, but a manual `systemctl --user stop` stays stopped.
- Closing the window was previously the only way to stop tracking; with the
  service, quitting the windowed app is safe and the day keeps accumulating.
  Quit the service to actually stop measuring.
