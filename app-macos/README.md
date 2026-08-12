# app-macos

**Owner:** Agent B (`docs/plan.md`, machine split at M0)
**Status:** Runs. Local-only vertical slice: collector → session tracker → local store → Today screen.

```bash
./gradlew :app-macos:run
```

No account, no sync, no network, no permission prompts. `docs/plan.md` locks local-only as the default posture — *"sync additive, never a dependency"*, *"unconfigured transport valid"* — so a local-only build is a complete slice rather than a stub, and it satisfies the v1 criterion *"Local-first: sync never blocks"* on its own.

**Note on the dev-mode self-reading.** Launched via `./gradlew :app-macos:run` the app sees itself as `net.java.openjdk.java` / "MainKt", because a bare JVM process has no bundle identity. Packaged (`./gradlew :app-macos:packageDmg`) it appears correctly as `dev.lumen.macos` / "Lumen". Lumen deliberately does **not** filter itself out of its own totals — time spent reading your screen-time app is still screen time, and hiding it would be exactly the kind of flattering lie the design spec rules out.

---

## Mechanism selection

macOS offers four ways to learn which app is frontmost. They differ mostly in what they cost the user in permission prompts, which is the deciding factor for a privacy-first tracker.

| Mechanism | API surface | Permission required | Push or poll | Verdict |
|---|---|---|---|---|
| **`/usr/bin/lsappinfo`** | System CLI | **None** | Poll | **Chosen for v0** |
| **`NSWorkspace.didActivateApplicationNotification`** | AppKit (ObjC) | **None** | **Push** | **Target for production** — needs a JNI/JNA bridge |
| `CGWindowListCopyWindowInfo` | Core Graphics (C) | None for owner name/PID; **Screen Recording** for window *titles* | Poll | Viable, more plumbing, no advantage over NSWorkspace |
| AppleScript via System Events | osascript | **Accessibility (TCC)** | Poll | **Rejected** |

### Why AppleScript is rejected

`tell application "System Events" to get ... whose frontmost is true` requires the **Accessibility** permission. That is the macOS analogue of an Android accessibility service, and `docs/plan.md` locks the Android decision explicitly: *"UsageStatsManager authoritative (no accessibility service needed)"*. Taking Accessibility on macOS would contradict the same principle for the same reason — it is a broad, scary, and largely unnecessary grant that would sit badly next to Lumen's positioning.

The equivalent finding for macOS is worth stating plainly, because it is not obvious:

> **Lumen needs no TCC permission at all on macOS to track per-app foreground time.** No Accessibility, no Screen Recording, no Automation prompt. Frontmost-application identity is public API.

That holds because Lumen only ever wants the app's identity, never window titles — the rule already set in `docs/e2ee.md` §3. The moment anything wants a window title, Screen Recording enters the picture and the calculus changes completely. That is a good reason to keep the title rule inviolable rather than merely default.

### Why `lsappinfo` first and `NSWorkspace` later

`lsappinfo` needs no native bridge, no build-time toolchain, and no dependency — it is a subprocess and a string parse. That makes it the right way to get a real, testable collector in place while the seam is still being agreed.

It has two real costs, both recorded in `CollectorCapabilities` rather than hidden:

- **It polls.** Measured at ~3.5 ms per poll pair (two `lsappinfo` invocations, process spawn included) on an M-series Mac. At the 1 s default that is roughly 0.35 % of one core. Acceptable for a spike; not what a battery-conscious app should ship.
- **It cannot detect idle.** `lsappinfo` reports the frontmost app even when the screen is locked, so this collector cannot distinguish "using Safari" from "locked, Safari was last frontmost". `capabilities.detectsIdle` is `false` and the engine is expected to treat that honestly rather than accrue phantom usage.

`NSWorkspace.didActivateApplicationNotification` fixes both: it is push-based, so polling disappears entirely, and it pairs naturally with IOKit's `HIDIdleTime` for idle detection. It needs a JNI/JNA bridge into AppKit plus a run loop, which is a bigger chunk of work than the seam discussion should wait on. Both mechanisms produce the identical `FocusChange` stream, so the swap is entirely internal to `LsAppInfoCollector`.

---

## The seam

`AppUsageCollector.kt` in this module is a **proposal for `core/src/commonMain`**, which is Agent A's zone and freezes at M1. It lives here so the proposal ships with a working implementation instead of a sketch. Delete it and import from core once Agent A lands it.

Design note, since it is the load-bearing decision: collectors report **transitions** (`FocusChange`), never durations. The three platforms disagree about what they can observe — Hyprland pushes a compositor event, Android hands back a batch of historical foreground/background records, macOS posts an NSWorkspace notification — but all three can produce a transition, while only some can produce a trustworthy duration. Duration arithmetic across sleep/wake, timezone changes and clock steps is subtle and already locked centrally in `docs/data-model.md`; doing it once in the rollup engine beats doing it three times in three collectors, wrong in three different ways.

`backfill()` exists for Android specifically. `UsageStatsManager` is authoritative and retains events for days, so an app that was killed can recover what it missed. macOS and the Linux compositors cannot, so they declare `canBackfill = false` — which lets the engine distinguish a *permanent* gap from a *pending* one. That is the same distinction `docs/providers.md` §5 draws for MAM gaps the archive can no longer fill, and it matters for the same reason: a gap that will never fill must surface, not silently resolve.

---

## History import (opt-in, off by default)

Live tracking starts blank: it only knows what happened after Lumen launched. macOS itself keeps a record of app focus going back weeks, in `~/Library/Application Support/Knowledge/knowledgeC.db` — the system store behind Screen Time. Lumen can import it once to fill in the past.

This is the macOS counterpart to Android's `UsageStatsManager`, and it is what makes `AppUsageCollector.canBackfill` true here.

**It costs Full Disk Access, and that is a genuinely large ask.** An app holding FDA can read Mail, Messages, Safari history and every other user file. Lumen reads only rows whose `ZSTREAMNAME` is `/app/inFocus` and ignores everything else in the store — but the *grant* is not that narrow, and the UI says so rather than glossing it. Hence: opt-in, off by default, dismissable, and tracking works completely without it.

**There is no API to request Full Disk Access.** Screen Recording and Accessibility have request calls that raise a system prompt; FDA does not. The only supported pattern is detect → explain → deep-link to System Settings → re-check. `FullDiskAccess.openSettingsPane()` opens `x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles`; the user adds the app by hand.

**The grant only applies to a relaunched process.** TCC decisions are cached per process, so an app granted FDA while running keeps seeing denials until it restarts. The banner says this explicitly — it is the step most implementations skip and the most common reason "I granted it and it still doesn't work".

Safety rules the importer follows:

- **Never opens the live database.** `knowledgeC.db` is a running system store in WAL mode; it is copied — with its `-wal`/`-shm` sidecars, or the copy silently omits the most recent writes — and the copy is read.
- **Read-only** (`open_mode=1`). A test asserts the source file is byte-identical afterwards.
- **Verifies the schema before trusting it.** The store is private and undocumented and Apple changes it between releases, so an unrecognised shape yields an explicit `SchemaUnrecognised` result. Importing nothing silently would look identical to "you used no apps".
- **Idempotent** via an import watermark, so a second import cannot double your history.

## Storage

Events are appended as NDJSON, one file per UTC day, under `~/Library/Application Support/Lumen/`. Per-app day totals are **derived on read** via `RollupEngine.bucket()` rather than stored — `RollupEngine`'s own contract says buckets and rollups are derived, never authoritative.

Deliberately not SQLite: the SQLite schema lives in `core` and freezes at M1 (Agent A's zone). Duplicating it here would create a second, divergent definition of the same tables. NDJSON is a local cache this module owns outright and can discard when `core`'s store lands.

Bucket-to-day assignment goes through `UtcDay.dayOf()` on each *bucket's* timestamp, not the event's start, so a session spanning UTC midnight splits across both days. That is the locked UTC-day rule applied where it actually bites, and it has a test.

## UI

`docs/design-spec.md` is binding and every value in `ui/Theme.kt` is transcribed from it, not chosen — `#0E1116` ink-near-black (never pure black), the single indigo `#7C9CF5` accent, the Okabe-Ito colourblind-safe category palette with red reserved for destructive actions only, tabular figures (`tnum`) on every time readout so live numbers don't jitter, and 200 ms ease-out with `prefers-reduced-motion` honoured via `com.apple.universalaccess reduceMotion`.

The Today screen states the number and never evaluates it. No streaks, no badges, no colour-coded verdict. A mirror, not a judge.

## Verification

```
$ ./gradlew :app-macos:desktopTest
BUILD SUCCESSFUL — 35 tests, 0 failures
```

End-to-end on macOS 15 / arm64, running the real app against the live system:

```
$ cat ~/Library/Application\ Support/Lumen/events-2026-08-12.ndjson
{"seq":0,"deviceId":"0af18d53-...","appKey":"net.java.openjdk.java",
 "startedAtMs":1786495295405,"durationMs":8246}

$ cat ~/Library/Application\ Support/Lumen/app-names.tsv
net.java.openjdk.java	MainKt
com.anthropic.claudefordesktop	Claude
```

Real focus transitions, real durations, resolved names, persisted across restart. No permission dialog appeared at any point, confirming the TCC analysis above.

---

## Not done here

- **Categories** — every app currently shows individually; the category registry is M6 and is not in this module.
- `core/src/desktopMacosMain` Keychain — needed only for sync, and gated on the `Keychain` contract question in `docs/e2ee.md` §8.
- The `NSWorkspace` bridge and IOKit idle detection (see the mechanism table above).
- Day curve and 7/30-day charts — the spec allows exactly three chart types; only the Today bars exist so far.
- Sync. Local-only is the point, not a limitation.
