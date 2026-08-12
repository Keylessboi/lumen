# Lumen — context and handover

**Written 2026-08-12 by Agent B (Claude, macOS) at `7bbb7a2`.**
For whoever picks this up next: a human, a fresh session, or Agent A.

`docs/STATUS.md` says what state the repo is in. This says **why it is in that
state** — the decisions, the reasoning behind them, and the things that will
bite you if nobody tells you. Everything here is either verifiable in the repo
or explicitly marked as unverified.

---

## 1. What Lumen is

A privacy-first screen-time tracker for Linux, Android and macOS. Local-first,
FOSS, AGPL-3.0. Not a business.

The product is one sentence from `docs/plan.md`: *automatic categories and one
honest nudge, on a beautiful, local-first, privately-synced screen-time log.*

Three constraints drive almost every decision in the codebase:

- **A mirror, not a judge.** No streaks, no badges, no "productive vs wasted",
  no red-as-bad. The app reports; the user decides.
- **Never a confident wrong guess.** An unknown app is Uncategorized, visibly,
  with its time counted. Silence beats a plausible lie.
- **Numbers and charts must agree.** `docs/design-spec.md` treats a misleading
  chart as an uninstall-grade bug, and that is not rhetoric — it is the
  reasoning behind a third of the fixes below.

---

## 2. Who builds it

Two agents, one repo, ownership enforced by `tools/ownership-check.sh` in CI.

| | |
|---|---|
| **Agent A** (`loki-desktop`, Arch Linux) | `core`, `transport-xmpp`, `app-linux`, `app-android`, `docs/design-spec.md`, `docs/data-model.md` |
| **Agent B** (`agent-b`, macOS) | `ui`, `app-macos`, `core/src/commonTest`, `tools/*`, `docs/e2ee.md`, `docs/providers.md`, `marketing` |

**`ui/**` moved to B by LO's decision**, reversing a pin A had written. A's pin
was the better contract — `Theme.kt` is the executable copy of the A-owned
design spec, so one authority was right — and A was overruled rather than
refuted. `docs/design-spec.md` stays A's: the spec is the authority, `:ui` is
B's implementation of it. If `:ui` renders something the spec does not say,
that is a B bug for A to file.

### Comms

`Keylessboi/lumen-talk` — a git-backed agent bus, wired as an MCP server.
GitHub Discussions still work and are better for anything needing a durable
record; the bus is better for fast exchange.

- Mailboxes are branches: `comms/agent-b`, `comms/loki-desktop`.
- **Delivery filters on `to === agentId`.** Mail addressed to a retired id is
  silently undeliverable — sender succeeds, receiver never sees it, no error
  either side. A's docs still say the peer is `claude-laptop`; **it is
  `agent-b`**. This already cost two unacked messages.
- `scripts/smoke.sh` cannot pass on macOS: it isolates into a temp `HOME`, and
  git auth here is `osxkeychain`, which is bound to the real one. 9 of 10
  assertions fail on auth, none on logic. Not a regression.
- Run the tool's tests as bare `node --test`, **not** `node --test test/` —
  the latter makes Node treat the directory as one phantom test and report a
  failure that is not real. 87 tests pass.

---

## 3. The pattern that explains most of the bugs

**Roughly a third of everything found tonight was one shape: each platform
doing its own version of something that should exist once.**

The seam usually already existed. What was missing was a single implementation
behind it.

| Symptom | What it actually was |
|---|---|
| Hyprland leaked window titles into `displayName` | macOS did it right, Linux did it wrong, no shared rule |
| Chart axis read `06 07 08` | each caller formatted its own labels |
| Android re-emitted the same app forever | dedupe implemented on one platform only |
| Hyprland re-emitted on workspace switch | same bug, third instance |
| Reduced motion honoured on macOS, hardcoded `false` elsewhere | spec requires it everywhere |
| `FocusSessionTracker` sat in `app-macos` | pure logic every platform needs |
| App names resolved only on macOS | every backfill on every platform has this gap |
| The UI was "universal" but only macOS fed it data | same component, three completeness levels |

**If you are about to write something a second time, put it in `core` instead.**
That is the single most useful heuristic in this codebase. `AppNameResolver`,
`SystemUiFilter`, `DayView`, `LocalDay`, `FocusSessionTracker`,
`liveMsWithinDay` all exist because of this.

The second heuristic: **for UI, the render is the test.** Three real bugs
compiled cleanly, passed every test, and were only visible in a screenshot —
bars hanging from different baselines, a `LazyColumn` inside a scroll parent
collapsing to zero height, and two apps sharing a colour.

---

## 4. Decisions worth not relitigating

Each of these was argued once and is easy to accidentally undo.

**Lumen counts its own window.** It was "fixed" as a bug, shipped, and reverted
by LO. Reading your screen-time app *is* screen time, and hiding it is the
flattering lie the spec forbids. There are tests pinning the inclusion so the
"obvious fix" cannot come back.

**The lock screen is not screen time.** Superficially the opposite of the
above, and it is not. The distinction is whether the user *chose* to be there.
Recorded history held **478 minutes of `com.apple.loginwindow`** — 10% of
everything ever recorded, one overnight. `SystemUiFilter` excludes lock
screens, password prompts, screensavers; System Settings and Finder stay
counted, because opening them is using the computer.

**UTC for reconciliation, local day for display.** The locked rule that "today
is a UTC day" is right *and* it was being applied to the display layer, so at
23:50 in New York the screen showed tomorrow and the day's number reset at
20:00 local. `LocalDay` + `rollups_local` separate them. **The display zone is
a `display.timezone` setting, not each device's OS zone** — otherwise two
devices disagree about which day a session belongs to and their totals cannot
be summed, reintroducing at the display layer exactly what the UTC rule
prevents.

**No passphrase strength meter.** `docs/e2ee.md` §7 asks for an honest estimate
that does not fabricate a guarantee. `correct horse battery staple` and a
sentence from a popular book are indistinguishable to anything a client can
compute. The UI counts characters and words and says nothing about strength.

**Colour is identity.** App rows are coloured by category, not by list
position — an app that changes colour as its ranking shifts is the chart lying
about which row is which. Where hues collide, the set is resolved by key so it
survives reordering.

**The export refuses weak KDF parameters.** They travel *in the file*, so a
tampered header could ask for a derivation cheap enough to brute-force. Floors
are enforced and the header is validated *before* any key derivation runs —
asserted with a counting fake.

---

## 5. Where v1 actually stands

**373 tests, `./gradlew build` green.** 35 PRs merged.

| Gate | State |
|---|---|
| `G1` Linux slice | collector written; A verified live before the dedupe and title fixes |
| `G2` Android slice | **compile-verified only** — no device has run it |
| `G3` sync + E2EE | **not started.** No XMPP client exists at all |
| `G4` export/migrate | met in code: format, Argon2id + AES-GCM, UI, atomic writes |
| `G5` categories | logic met: 185-entry registry, sticky overrides, corpus test |
| `G6` nudge + polish | nudge done; design pass and RC not done |

**M4 is the largest unbuilt piece of v1** — no XMPP client, no IBR, no provider
picker, no sync engine, no E2EE behind the seam. Everything else is closer to
done than that is to started.

### Three gates cannot be signed off from a Mac

G1 needs a real Hyprland session, G2 needs Android hardware, G3 needs two
devices and a live provider. That is not a scheduling problem; it is why
Agent A exists.

---

## 6. Things that will bite you

- **`rollups_local` was added at schema version 1 rather than as a migration.**
  Defensible only because `JvmLumenStore.open()` threw on every launch after
  the first until it was fixed, so no database has ever survived a restart.
  **That excuse expires the moment anyone ships.**
- **`app-macos` is still on an NDJSON cache**, not `LumenStore`. A migration
  exists (`NdjsonMigration`, 9 tests, archives rather than deletes) but is not
  wired into the app.
- **`deviceKeys` exports empty.** macOS has no keychain, so a restore cannot
  resume a sync identity. Honest, incomplete.
- **Android and Linux store in memory.** The surface is the real shared screen;
  the persistence behind it is not. Linux has `JvmLumenStore` for rollups but
  the trend chart is the only thing reading it.
- **The Android collector has never run on a device.** Whether
  `KEYGUARD_SHOWN` fires reliably across OEMs is exactly what M3 exists to
  answer.
- **`docs/non-goals.md` is referenced by the plan and the ownership matrix and
  does not exist.**
- **Linux has no `SystemUiFilter`.** swaylock / gtklock / i3lock are being
  recorded as screen time right now, the same bug that cost macOS 8 hours.

### Environment

Java 17 via Homebrew (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`), Gradle 9.1.0,
AGP 8.9.2, Kotlin 2.2.10, compileSdk 36. No Android SDK images and no emulator
on this machine.

**Read the build result, do not grep it.** `./gradlew build` failures in
`:app-android:lintDebug` and `:app-android:mergeReleaseJavaResource` print
neither `^e:` nor `FAIL`. This produced three commit messages claiming
"BUILD SUCCESSFUL" when it was not.

**Do not merge in the same command that opens a PR.** Doing so merged a
pre-fix commit and left the fix on a closed branch — `main` was red for
`:app-android` for about twenty minutes.

---

## 7. What to do next

**Unblocked, and mine by zone:**

1. Wire `NdjsonMigration` into `app-macos` so it moves onto `LumenStore`. The
   last structural inconsistency, and there is a real user with a month of
   irreplaceable imported history — the migration archives rather than
   deletes for that reason.
2. `MacosKeychain`, so exports carry a real device identity.
3. A real corpus pass on the category registry against an actual app
   inventory, rather than the hand-written test list.

**Blocked:**

- `tools/sync-test-server` and the ciphertext verifier — needs A's M4 envelope.
- Marketing kit — held by LO. Art direction approved: *instrument, not
  advertisement*; the product's own visual language, no people, no stock desks.

**Agent A's, and the critical path:** M4 transport and sync, M3 device matrix,
G1 re-verification after the collector fixes.

---

## 8. Mistakes made tonight, so they are not repeated

Recorded because a handover that only lists wins is not a handover.

- **Claimed "BUILD SUCCESSFUL" three times before checking.** Grep filtered out
  real failures. Fixed by reading build output.
- **Broke `main` for ~20 minutes** by merging a PR in the same breath as
  opening it, stranding the fix on a closed branch.
- **A fix that reintroduced its own bug.** The import double-count guard pinned
  `until` to the first event ever recorded — correct for the initial backfill,
  wrong forever after, so a user who quit for a week could never recover it.
  Exactly the silent-nothing failure it had just fixed, one layer up.
- **A test double that faked a pass, twice.** A key-sum tag collided across
  passphrases; the `h*31+b` replacement also collided, because keys differing
  by a constant delta produce tags differing by `delta * (31^n + … + 1)`, which
  is 0 mod 256 at those sizes. And it tagged only the key, not the ciphertext,
  so a tampered file authenticated cleanly.
- **Proposed the wrong fix in a filed issue.** For the JVM-identity bug I said
  use the executable path; every JVM app shares the same `bin/java`. Recorded
  in the PR so nobody retries it.
- **Two self-inflicted UI regressions**, both caught only by screenshotting:
  colour collisions after keying colour off the app id, and a full-page scroll
  that fixed clipping by making the window content taller than the window.
