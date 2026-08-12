# app-macos

**Owner:** Agent B (`docs/plan.md`, machine split at M0)
**Status:** Post-MVP #2. Not in any v1 gate. This module exists now so the collector seam is designed against three real platforms rather than two, per the accepted decision to design the seams before the module is promoted.

Currently the collector and its seam only — no Compose UI. The UI is a straight reuse of `app-linux`'s Compose screens when macOS is promoted, and pulling the Compose plugins in early would only add build weight and a second place for CMP desktop version drift to bite.

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

## Verification

```
$ ./gradlew :app-macos:desktopTest
BUILD SUCCESSFUL
12 tests, 0 failures
```

End-to-end against the live system on macOS 15 / arm64:

```
available=true
frontmost=FrontmostApp(appKey=AppKey(value=com.anthropic.claudefordesktop), displayName=Claude)
```

No permission dialog appeared at any point, confirming the TCC analysis above.

---

## Not done here

- `core/src/desktopMacosMain` Keychain (macOS Keychain Services) — blocked on the `Keychain` contract question in `docs/e2ee.md` §8, which needs resolving before M1 regardless of platform.
- The `NSWorkspace` bridge and IOKit idle detection.
- Compose UI — deferred until macOS is promoted out of post-MVP.
