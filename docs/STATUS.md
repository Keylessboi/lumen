# Lumen — Repo Status (base commit)

**Date:** 2026-08-11

## What this base contains

- **Two-agent execution contract** — `docs/plan.md`: ownership zones (Agent A: core commonMain/desktop, transport-xmpp, app-linux, registry; Agent B: androidMain, app-android, sync-test-server), interface freeze points, M0–M8 git-tagged milestones, GitHub Discussions coordination protocol.
- **Frozen docs** — `docs/design-spec.md` (week-1 design spec), `docs/data-model.md` (schema + reconciliation contract).
- **Gradle multi-module scaffold** — `core`, `transport-xmpp`, `app-linux`, `app-android`, version catalog, wrapper (Gradle 8.9).
- **Core code sketch** — `core/src/commonMain`: data model (`Models.kt`), sync seam (`SyncTransport.kt`), crypto seam (`E2EE.kt`, `Keychain`), rollup engine, UTC-day clock. These files are the M1 freeze candidates.
- **Ownership gate script** — `tools/ownership-check.sh` (CI-enforced zone violations).
- **AGPL-3.0** LICENSE, README, .gitignore.

## Known-broken / incomplete (documented, not hidden)

The build is **not green yet**. This is deliberate. The base is committed so two agents can start at once. Each agent fixes its own module.

| Module | State | Owner | Notes |
|---|---|---|---|
| `:core` | WIP | A | KMP + AGP 9.0.0-rc03 compatibility (built-in Kotlin flag set; may need `com.android.kotlin.multiplatform.library` or AGP downgrade to 8.x) |
| `:transport-xmpp` | WIP | A | Same AGP/KMP question; no client code yet |
| `:app-linux` | WIP | A | Main.kt placeholder only; collectors + UI at M2 |
| `:app-android` | WIP | B | No manifest/Activity yet; CMP Android app at M3 |
| `:core` androidMain | empty | B | Keystore impl missing (Agent B) |
| `:core` desktopMain | empty | A | libsecret/keyring impl missing (Agent A) |

Known environment facts (from base commit):
- Java 17, Gradle 8.9 wrapper, AGP 9.0.0-rc03 in version catalog, Kotlin 2.2.10.
- AGP 9.0 will not pair with `org.jetbrains.kotlin.multiplatform` unless `android.builtInKotlin=false` and `android.newDsl=false` (already in `gradle.properties`), or unless you use the new `com.android.kotlin.multiplatform.library` plugin.
- `:core` failed earlier with "does not specify compileSdk". This commit fixes that. The remaining failure is plugin compatibility during configuration.
- **Wrapper note:** `gradle wrapper` succeeds. Module configuration fails. Use `./gradlew :core:test` as the M0 gate.

## First moves for the agents

- **Agent A (M0):** make `./gradlew :core:build` green. Choose one fix for the AGP question: pin AGP 8.9.x (proven KMP pairing) or switch library modules to `com.android.kotlin.multiplatform.library`. Update `docs/plan.md` if the choice changes the contract.
- **Agent B:** check the `app-android` module structure against the ownership contract; stub the manifest and Activity; file contract issues to Discussions with the `[B]` prefix.
- **Both:** read `docs/plan.md` in full before touching anything. Run `tools/ownership-check.sh` before every push.

## Communication

All cross-agent coordination happens in **GitHub Discussions** (`/discussions`) on the pinned `agent-coordination` thread. Use `[A]` or `[B]` subject prefixes. Mark contract changes with the `freeze-review` label. GitHub Issues are for code defects only.
