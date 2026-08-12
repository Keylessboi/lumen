# app-linux

**Owner:** Agent A (`docs/plan.md`).

CMP desktop app for Hyprland/Sway/X11. Collectors → session tracker →
`JvmLumenStore` (SQLite) → the SHARED `:ui` TodayScreen, byte-for-byte the
same screen macOS and Android draw.

## Run

```bash
# Windowed app (dev loop)
./gradlew :app-linux:run

# Packaged app
./gradlew :app-linux:createDistributable
build/compose/binaries/main/app/lumen/bin/lumen
```

## Headless tracking (systemd service)

Tracking survives the window being closed: `lumen-tracker.service` runs the
plain-JVM tracking pipeline (no Compose window) so the day keeps
accumulating. See `docs/linux-integration.md` for install.

```bash
# The service uses the plain-JVM launcher, NOT the Compose binary —
# the Compose launcher initialises AWT/Skiko and aborts headless
# (__cxa_pure_virtual in libapplauncher.so).
cp systemd/lumen-tracker.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now lumen-tracker.service
```

## Waybar applet

`scripts/lumen-applet.sh` is a waybar custom module: today's total (SQLite
rollups, idle excluded) + the focused app. See `docs/linux-integration.md`.

## Collectors

| Collector | Source | Status |
|---|---|---|
| `HyprlandCollector` | hyprland socket2 event stream | verified live (Arch, Hyprland) |
| `SwayCollector` | sway i3-ipc socket (`$SWAYSOCK`) | unit-tested; needs a Sway host |
| `X11Collector` | EWMH `_NET_ACTIVE_WINDOW` via `xprop` | unit-tested; needs an X11 host |

All three implement the frozen `AppUsageCollector` seam and never read window
titles (`docs/e2ee.md` §3).

## Tests

```bash
./gradlew :app-linux:desktopTest
```

## Contract

Read the Two-Agent Execution Contract in `docs/plan.md` before touching this
directory. Agent B: do not modify files here.
