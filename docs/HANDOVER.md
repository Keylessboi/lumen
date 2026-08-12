# Lumen — context and handover

**Written 2026-08-12 by Agent B (Claude, macOS) at `7bbb7a2`; extended the
same day at `3a31502` — see §7 and §9.**
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

**390 distinct tests, `./gradlew build` green.** 39 PRs merged. (808
executions — `:core` and `:ui` commonTest run again under each Android
variant. Count distinct tests or the number inflates without new coverage.)

| Gate | State |
|---|---|
| `G1` Linux slice | collector written; A verified live before the dedupe and title fixes |
| `G2` Android slice | **compile-verified only** — no device has run it |
| `G3` sync + E2EE | **not started.** No XMPP client exists at all |
| `G4` export/migrate | components built and tested — **and not wired to anything**, so no user can make a backup (issue #57). "Met in code" was true component by component and false end to end |
| `G5` categories | logic met, and now measured against a real machine (#58): 226 entries, 92.3% of recorded time |
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
- ~~`app-macos` is still on an NDJSON cache~~ **Done (#55)**, and it found
  the seq trap in §9 on the way.
- ~~`deviceKeys` exports empty~~ **Done (#56)** — `MacosKeychain`.
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

**The three items this section used to list are done** — see `docs/STATUS.md`
for the state, and PRs #54, #55, #56, #58 for the reasoning.

Still mine:

1. **G4 is not reachable.** `ExportSection` is in `:ui`, `exportPayload()` is
   in `UsageStore`, `BackupFiles.write()` writes atomically — and `Main.kt`
   calls none of them, so a user cannot make a backup. `ExportSection` also
   has no `onPassphraseChange`, so it has no input field. Issue #57. The gate
   table said "met in code", which was true component by component and not
   true end to end; that is the failure mode to watch for in the other gate
   rows too.
2. `tools/sync-test-server` and the ciphertext verifier — still needs A's M4
   envelope.
3. Marketing kit — still held by LO. Art direction approved: *instrument, not
   advertisement*; the product's own visual language, no people, no stock
   desks.

**Waiting on A, not on work:** PR #58 curates `registry.tsv` (mine) but its
generated form `GeneratedRegistry.kt` is in `core/src/commonMain` (theirs), so
the PR carries the curation and cannot carry its effect. Either A runs
`build-registry.py`, or generated artifacts move to `SHARED` in the ownership
check. A generated file has no independent authorship, so the second seems
right — but the matrix is a two-agent contract and not one agent's to edit.

**Agent A's, and the critical path:** M4 transport and sync, M3 device matrix,
G1 re-verification after the collector fixes. Plus one thing found from here:
`app-linux` builds `FocusSessionTracker(deviceId)` counting from 0 on every
launch and inserts straight into the store, which is the seq trap in §9 below.
Unconfirmed from a Mac; it would look like a day that stops growing after a
restart.

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

---

## 9. What the second night added

Four things worth carrying forward, all of them found the same way — by
running the thing and looking at it, rather than by reading it.

**The seq trap, and why it was invisible.** `(device_id, seq)` is the primary
key and `insertEvent` is `INSERT OR IGNORE`, so a colliding seq is **not an
error** — the row is silently discarded. `FocusSessionTracker` numbers from 0
on every launch, and the real NDJSON on this Mac shows exactly that: seq 12,
13, 14, then 0, 1 where the app was restarted. Under NDJSON nothing read seq,
so it cost nothing. Against the store, every session after the first would
have vanished on write with no error and no log. The general shape: **a field
that nothing reads is not a field that is correct**, and moving to a store
that reads it is the moment you find out.

**"Archives rather than deletes" was not true.** `File.renameTo` is
`rename(2)` on macOS and replaces the destination silently. Running the
migration a second time — which an older build still running is enough to
cause — archived a 2-line file over the 223-line archive of the same day.
Found only by running it twice for real. And the deeper bug underneath it: the
old idempotence test passed because the archiving step always succeeded, so
nothing ever exercised the path where a source is read again. **A guarantee
that depends on a step that always works in tests is not yet a guarantee.**

**The corpus test tested the author's memory.** Eighteen apps, hand-written,
by the same person who wrote the registry. Measured against a real machine it
was 26% of installed apps and 81% of recorded time — and, more usefully, it
surfaced a class of bug a longer hand-written list would never have found:
`blender` was in the registry under the Linux WM class only, so the same app
was categorised on one platform and Uncategorized on the other. §3's pattern
again, in the data rather than the code. **Weight coverage by time**: an app
used four hours a day and an app opened once are not the same fact.

**The render is still the test.** Both UI bugs in #54 compiled, passed, and
were visible only in a screenshot — and the worse of the two only appeared in
the state a *new* user sees, with the history banner up. So screenshot the
first-run state too, not just the state your own machine happens to be in.

### Still true, and worth repeating

The heuristic from §3 has not stopped paying: `RollupEngine`, `LocalDay`,
`SystemUiFilter`, `AppNameResolver` are all in `core` because a second
implementation was about to be written. The registry gap above is the same
pattern one level down, in data rather than code.
