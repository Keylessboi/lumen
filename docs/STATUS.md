# Lumen — Repo Status

**Updated:** 2026-08-12 (M1 in progress)

## Build state

**`./gradlew :core:build` GREEN on Arch + macOS.** Desktop, Android, and
macOS targets all compile.

| Module | State | Owner | Notes |
|---|---|---|---|
| `:core` | green | A | AGP 8.9.2 + Gradle 9.1.0 + compileSdk 36; `android.useAndroidX=true` |
| `:core` schema | frozen-at-M1 | A | `LumenDatabase.sq` matches `docs/data-model.md`; generates clean; storage queries added pre-freeze |
| `:core` desktopMain | driver done | A | `JvmLumenStore` binds seam to SQLDelight (file + in-memory); 7 green round-trip tests |
| `:core` androidMain | empty | A | Keystore impl at M4/E2EE (X25519, hardware-wrapped at rest per docs/e2ee.md §5.2) |
| `:core` commonTest | **yours (B)** | B | Contract tests — the other half of the M1 gate |
| `:transport-xmpp` | WIP | A | No client code yet; sync at M4 |
| `:app-linux` | collector verified | A | HyprlandCollector capturing live events on real session (M2 core done); dev harness window runs |
| `:app-android` | WIP | A | No manifest/Activity yet; CMP Android app at M3 |
| `:app-macos` | vertical slice | B | Lumen runs on macOS (PR #13): lsappinfo collector + KnowledgeCImporter (Apple Screen Time DB) + NDJSON store + Today screen |

Environment: Java 17, Gradle 9.1.0 wrapper, AGP 8.9.2, Kotlin 2.2.10,
SDK 36 at `/home/travis/Android/Sdk` (`local.properties`).

## M0 — DONE

- Tagged `M0`, green on both machines.
- Machine split (A = Arch/Android, B = macOS/iOS), ownership zones, branch
  prefixes `a/`/`b/`, CI ownership workflow (fails closed).
- E2EE freeze-review accepted: at-rest guarantee, wrappedKeys, padding.
- Wake protocol: mailbox-only, pointer-only, `agent-inbox` branch. Watcher
  fixed to read branch ref directly (found the merge bug by running it).

## M1 — IN PROGRESS

**My side (A, on main `ebf98e7`):** schema + storage queries, `LumenStore`
seam, `JvmLumenStore` driver, 7 green round-trip tests.

**Your side (B):** `core/src/commonTest` contract tests — written by the
consumer of the frozen API, per the #12 re-cut. This is what makes the
freeze real (my own round-trips test what I meant, not what I said).

**Gate:** schema generates + LumenStore compiles + B's contract tests pass
on both machines → tag `M1`.

## Post-v1 directions

`docs/directions.md` — distilled from the adversarial run. Killed: iOS
tracking, family metrics, app-blocking, cloud dashboard, Windows/TUI/
self-host-server. Ship first: weekly reflection (Screen Weather), sponsorware
relay tier, CLI + daemon, absorption sessions, browser extension, dev
integrations.
