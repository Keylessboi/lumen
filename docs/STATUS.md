# Lumen — Repo Status

**Updated:** 2026-08-12 (M1 tagged — contract freeze)

## Build state

**`./gradlew :core:build` GREEN on Arch + macOS, all four targets compile.**

| Module | State | Owner | Notes |
|---|---|---|---|
| `:core` | green | A | AGP 8.9.2 + Gradle 9.1.0 + compileSdk 36; `android.useAndroidX=true` |
| `:core` schema | **frozen at M1** | A | `LumenDatabase.sq` matches `docs/data-model.md`; compound PK on events; 98 green tests |
| `:core` desktopMain | driver done | A | `JvmLumenStore` + `JvmLumenStoreContractTest` (B's abstract kit) |
| `:core` androidMain | empty | A | Keystore impl at M4/E2EE |
| `:core` commonTest | **done (B)** | B | PR #17: 64 tests — RollupEngine, UtcDay, value semantics, serialization, EncryptedPayload, LumenStoreContract |
| `:transport-xmpp` | WIP | A | No client code yet; sync at M4 |
| `:app-linux` | collector verified | A | HyprlandCollector live-tested; full-path integration test green |
| `:app-android` | M3 scaffold | A | UsageStatsCollector + MainActivity + manifest; emulator live-tested |
| `:app-macos` | vertical slice | B | PR #13: local-only slice — lsappinfo + KnowledgeCImporter + Today screen |

## M0 — DONE

## M1 — DONE (tagged)

- **Contract freeze**: schema, core public API, reconciliation contract, serialization format, collector seam, E2EE seam.
- **98 green tests** (0 failures, 2 skipped in RollupEngine per B's PR description).
- **B's contract suite caught a real schema bug** (seq PK was global, not per-device — fixed to compound PK).
- **Gate**: schema generates + LumenStore compiles + B's contract tests pass on both machines.

## M2 (G1 Linux slice) — next

- Hyprland collector verified live — remaining: Sway + X11 collectors, CMP read UI.

## Post-v1 directions

`docs/directions.md` — distilled from the adversarial run.
