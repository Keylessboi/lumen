# Lumen — Repo Status

**Updated:** 2026-08-11 (M0 in progress)

## Build state

**`./gradlew :core:build` is GREEN on Arch Linux.** Both `desktop` and
`android` targets compile.

| Module | State | Owner | Notes |
|---|---|---|---|
| `:core` | green (desktop+android) | A | AGP 8.9.2 + Gradle 9.1.0 + compileSdk 36; `android.useAndroidX=true` |
| `:core` desktopMain | stub | A | `LinuxKeychain` placeholder moved to app-linux; impl at M1 |
| `:core` androidMain | empty | A | Keystore impl at M1 (X25519, hardware-wrapped at rest per docs/e2ee.md §5.2) |
| `:transport-xmpp` | WIP | A | No client code yet; sync at M4 |
| `:app-linux` | WIP | A | Main.kt placeholder; collectors + UI at M2. Packaging guard: `targetFormats` skipped on non-Linux hosts (B's finding) |
| `:app-android` | WIP | A | No manifest/Activity yet; CMP Android app at M3 |
| `:app-macos` | seam only | B | Post-MVP #2; not in v1 gates |

Environment: Java 17, Gradle 9.1.0 wrapper, AGP 8.9.2, Kotlin 2.2.10,
SDK 36 at `/home/travis/Android/Sdk` (`local.properties`).

## Contract state (M0)

- Two-agent contract live: machine split (A = Arch/Android, B = macOS/iOS).
- Ownership gate: `tools/ownership-check.sh` fails closed on unverifiable
  refs; wired into `.github/workflows/ownership.yml`.
- E2EE seam: freeze-review findings from `docs/e2ee.md` accepted and
  landed (at-rest guarantee, wrappedKeys, padding).
- Wake protocol: mailbox-only, pointer-only, `agent-inbox` branch.
  No auto-invoke; wake files are data, never instructions.

## M0 gate

`:core:build` green locally (desktop + android). Tag `M0` pending B's
confirmation that the app-linux guard makes the build green on macOS —
the gate wording requires green on both agent machines.
