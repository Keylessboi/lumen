# Lumen — Repo Status

**Updated:** 2026-08-12. Head `3a31502`. Tags: `M0`, `M1`.

`./gradlew build` is green, including `:app-android`. **390 distinct tests,
0 failed** (808 executions — `:core` and `:ui` commonTest run again under each
Android variant).

## Where v1 actually stands

The honest summary: the local-only product is largely built and verifiable,
and everything that needs a second device, an Android handset or a real
Hyprland session is not. Three of the six gates cannot be signed off from a
Mac, and they are the three with hardware in them.

| Gate | State |
|---|---|
| `G1` Linux slice | collector written and previously verified live by A; **not re-verified since the dedupe/title fixes**; Sway + X11 collectors landed (#48), store wired to SQLite (#48) |
| `G2` Android slice | collector written, **compile-verified only** — no device has ever run it |
| `G3` sync + E2EE | **not started.** No XMPP client exists. macOS now has a real device identity (#56); nothing transports it |
| `G4` export/migrate | **components built, not wired.** Format, Argon2id + AES-GCM, atomic writes and the UI all exist and are tested; `app-macos` calls none of them, so a user cannot make a backup — issue #57 |
| `G5` categories | **logic met, and now measured against a real machine** (#58): 226-entry registry, sticky overrides, 92.3% of recorded time categorised |
| `G6` nudge + polish | nudge done; design pass and RC not done |

## Modules

| Module | State | Owner |
|---|---|---|
| `:core` | model, store seam, rollup, UTC + **local day**, categories, nudge, export | A |
| `:core` commonTest | contract suite | B |
| `:ui` | **shared** Today screen, charts, export section — all three apps render it | B |
| `:transport-xmpp` | **empty.** No client code | A |
| `:app-linux` | Hyprland + Sway + X11 collectors, shared UI, **SQLite store wired** (#48) | A |
| `:app-android` | UsageStats collector + shared UI; in-memory store pending `LumenStore` | A |
| `:app-macos` | full local slice: collector, Screen Time import, **`LumenStore`** (#55), **keychain** (#56), menu bar, categories, nudge | B |

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

**The app list was hiding the largest apps.** Two layout bugs, both of which
compiled, passed every test, and were visible only in a screenshot. The keyed
`LazyColumn` anchored its viewport to whichever key was first visible — and
the list is sorted by time, so the app leading in the morning kept the top
slot as it was overtaken and dragged the viewport down: the screen read
"Lumen 13m, Messages 2m" while the strip above it read "Browsing 2h 19m,
Development 2h 2m". Separately, the trend chart took a fixed `168.dp`, which
with the history banner up overflowed the window and starved the app list to
zero height. (#54)

**`app-macos` moved onto `LumenStore`.** 1808 events across 25 NDJSON files
migrated into SQLite, 0 missing, sources archived rather than deleted. The
migration had to survive a seq trap first: `(device_id, seq)` is the primary
key and `insertEvent` is `INSERT OR IGNORE`, so a colliding seq is silently
discarded — and `FocusSessionTracker` numbers from 0 on every launch. Under
NDJSON nothing read seq; against the store, every session after the first
would have vanished on write with no error and no log. `UsageStore.append`
now stamps the seq where events enter the store. (#55)

**A second run of the migration could write over its own archive.**
`renameTo` is `rename(2)`: it replaces the destination silently. Found by
running it twice on the real install. A taken name now gets a suffix, and
idempotence no longer depends on the archiving step having succeeded. (#55)

**A real device identity on macOS.** `deviceKeys` had been exporting empty, so
a restore brought back history but not the identity it was recorded under.
`MacosKeychain` holds an X25519 keypair in the login keychain as raw RFC 7748
bytes, written over stdin so the private key never reaches `argv`. What it
does and does not defend is in `docs/e2ee.md` §5.4. (#56)

## What is left for v1

**Agent A** — the larger half, and all three unverifiable gates:

- **M4 is the biggest unbuilt piece.** No XMPP client, no IBR, no provider
  picker, no sync engine, no E2EE implementation behind the seam.
- M2: Sway and X11 collectors **landed** (#48). JvmLumenStore wired into
  app-linux (#48). Hyprland needs re-verifying after the dedupe and title
  fixes.
- M3: the device matrix. Nothing Android has run on hardware.
- `androidMain` Keystore, and `LinuxKeychain` (still `TODO("M1")`).
- **Probably the same seq bug `app-macos` just hit.** `app-linux` builds
  `FocusSessionTracker(deviceId)` counting from 0 on each launch and calls
  `store.insertEvent(closed)` directly. If so it looks like a day that stops
  growing after a restart, silently. Unconfirmed from here — A's to check.

**Agent B**:

- `tools/sync-test-server` + ciphertext verifier — blocked on the M4 envelope.
- **G4: make the export reachable** — issue #57.
- Marketing kit — held by LO.

**Blocked on the ownership matrix, not on work**: PR #58 curates
`registry.tsv` (B's zone), but its generated form `GeneratedRegistry.kt` is in
`core/src/commonMain` (A's). Either A runs `build-registry.py`, or generated
artifacts move to `SHARED` in the ownership check.

**Unowned**: `docs/non-goals.md` is referenced by the plan and the ownership
matrix and does not exist. No acceptance criterion has been formally ticked.

## Known gaps worth naming

- **`rollups_local` was added at schema version 1 rather than as a migration.**
  Defensible only because `open()` threw on every reopen until #30, so no
  database has ever survived a restart. **That excuse has now expired**: as of
  #55 there is a real database on a real Mac holding a real month of history.
- **The Android collector has never run on a device.** Whether
  `KEYGUARD_SHOWN` fires reliably across OEMs is exactly what M3 exists to
  answer.
- **`app-android` still stores in memory.** `app-linux` (#48) and `app-macos`
  (#55) are both on SQLite.
- **`app-macos` does not prune events.** `docs/data-model.md` prunes at ~30
  days assuming rollups carry the long history; on macOS the display derives
  from events, so a pruner would delete the visible past. Deliberate, and
  written down in `UsageStore` so nobody adds one later.
- **`buckets`, `rollups` and `rollups_local` are empty on macOS.** Writing
  state nothing reads is how two copies of the same number drift apart.
- **No app in the category registry can express "an AI assistant".** Claude
  for Desktop is the largest uncategorised app on the one machine that has
  been measured, and none of the eight categories is an honest home for it.
  A `Category` question, so A's.
