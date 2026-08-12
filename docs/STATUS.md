# Lumen — Repo Status

**Updated:** 2026-08-12. Head `b35e582`. Tags: `M0`, `M1`.

`./gradlew build` is green, including `:app-android`. **326 tests, 0 failed.**

## Where v1 actually stands

The honest summary: the local-only product is largely built and verifiable,
and everything that needs a second device, an Android handset or a real
Hyprland session is not. Three of the six gates cannot be signed off from a
Mac, and they are the three with hardware in them.

| Gate | State |
|---|---|
| `G1` Linux slice | collector written and previously verified live by A; **not re-verified since the dedupe/title fixes** |
| `G2` Android slice | collector written, **compile-verified only** — no device has ever run it |
| `G3` sync + E2EE | **not started.** No XMPP client exists |
| `G4` export/migrate | **met in code**: format, Argon2id + AES-GCM, UI, atomic writes, round-trip tested |
| `G5` categories | **logic met**: 185-entry registry, sticky overrides, corpus test. Needs a real-world app-list pass |
| `G6` nudge + polish | nudge done; design pass and RC not done |

## Modules

| Module | State | Owner |
|---|---|---|
| `:core` | model, store seam, rollup, UTC + **local day**, categories, nudge, export | A |
| `:core` commonTest | contract suite, 209 tests | B |
| `:ui` | **shared** Today screen, charts, export section — all three apps render it | B |
| `:transport-xmpp` | **empty.** No client code | A |
| `:app-linux` | Hyprland collector + shared UI; in-memory store pending `LumenStore` | A |
| `:app-android` | UsageStats collector + shared UI; in-memory store pending `LumenStore` | A |
| `:app-macos` | full local slice: collector, Screen Time import, NDJSON store, menu bar, categories, nudge, export | B |

Environment: Java 17, Gradle 9.1.0, AGP 8.9.2, Kotlin 2.2.10, compileSdk 36.

## Landed since `M1`

**The day boundary was wrong for everyone outside UTC.** The Today screen
showed the UTC day, so at 23:50 in New York it displayed tomorrow and reset
the day's number at 20:00 local. `LocalDay` + `rollups_local` now separate the
reconciliation key (UTC, unchanged, still what syncs) from the display
boundary (a `display.timezone` setting, so devices agree). Midnight rollover
moves the finished day into the trend chart and restarts at zero.
(discussion #29, #31)

**A window-title leak.** `HyprlandCollector` put the window title into
`displayName`, which reaches the UI and the on-disk name cache — `docs/e2ee.md`
§3 forbids titles leaving the device "in any form". The class comment
documented the leak as intended, which is why it survived review. (#30)

**The database threw on every launch after the first.** `Schema.create()` was
called unconditionally, so persistence worked until the first restart. Invisible
because every test used the in-memory driver. (#30)

**Android**: dropped events on every poll (sliding window, no cursor),
`detectsIdle = true` while never emitting one, and a startup crash on API < 29.
(#32)

**`RollupEngine.bucket()` produced a 90-second "one-minute bucket"** for
pre-1970 timestamps — Kotlin's `%` keeps the sign of the dividend. (#36)

**The UI became genuinely shared.** Linux and Android were each drawing their
own screen with hex colours hand-copied from the design spec. All three now
render `:ui`. (#35)

**M5 export** (#40, #42, #45), **M6 categories** (#43), **M7 break reminder**
(#44). Details in the PRs.

## What is left for v1

**Agent A** — the larger half, and all three unverifiable gates:

- **M4 is the biggest unbuilt piece.** No XMPP client, no IBR, no provider
  picker, no sync engine, no E2EE implementation behind the seam.
- M2: Sway and X11 collectors. Hyprland needs re-verifying after the dedupe
  and title fixes.
- M3: the device matrix. Nothing Android has run on hardware.
- `androidMain` Keystore.

**Agent B**:

- `tools/sync-test-server` + ciphertext verifier — blocked on the M4 envelope.
- `app-macos` onto `LumenStore` (still NDJSON).
- `MacosKeychain`, so exports can carry a real device identity. `deviceKeys`
  currently exports empty, which is honest but incomplete.
- Marketing kit — held by LO.

**Unowned**: `docs/non-goals.md` is referenced by the plan and the ownership
matrix and does not exist. No acceptance criterion has been formally ticked.

## Known gaps worth naming

- **`rollups_local` was added at schema version 1 rather than as a migration.**
  Defensible only because `open()` threw on every reopen until #30, so no
  database has ever survived a restart. That excuse expires at first ship.
- **The Android collector has never run on a device.** Whether
  `KEYGUARD_SHOWN` fires reliably across OEMs is exactly what M3 exists to
  answer.
- **`app-linux` and `app-android` store in memory.** The surface is real; the
  persistence behind it is not.
