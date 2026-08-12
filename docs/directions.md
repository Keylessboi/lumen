# Lumen Post-v1 Directions (adversarial run)

**Provenance:** 5-lane adversarial ideation run (feasibility, architecture/security, product, design, scope) × 3 rounds (propose → cross-attack → final position). 25 directions proposed; survivors distilled below. Posted to Discussions #4 (2026-08-12). This file is the durable record.

## Killed (converged across lanes — do not build)

- **iOS tracking** — no per-app foreground API for non-parental-control apps (DeviceActivity entitlement wall). Viewer-only shell = dead surface + maintenance tax.
- **Family accounts / per-member metrics** — consent under power asymmetry is theater ("a nicer cage"). Enforcement + key hierarchy = family surveillance mechanism — a red line. Presence-only or nothing.
- **App-blocking / policy enforcement** — coercion is the opposite of calm; a mirror that blocks your hand is a guard. If anything ever ships: self-imposed, one-tap reversible, "dim, don't block."
- **Cloud web dashboard** — judgment surface by construction. Static local HTML covers the need.
- **Windows client, TUI, self-host server software** — no clean public API / scope discipline / ops black hole. Docs for existing XMPP servers instead.

## Ship first (post-M8, in order)

1. **Weekly reflection** (habit anchor, LOW cost): local HTML/PDF, zero-valence — no "vs last week", no percentages, no trend arrows. Plain counts + neutral anomaly phrasing ("browser time was above your recent pattern" — descriptive, not judgment). Ship with data portability (CSV/JSON/self-hosted, open spec). Design framing: render as **Screen Weather** — ambient climate grammar over day-vector rollup features, numberless at entry, never green/red, color-blind-safe. Prerequisite that makes everything else legible.
2. **Sponsorware relay tier** (values-consistent revenue): hosted zero-knowledge E2EE mailbox (server sees only ciphertext + queue token), content-blind by construction. **Never a first-run surface** — appears after first successful sync / first report, as a "patron" donation with the relay as thank-you. Self-hosted users stay first-class; hosted = a config, not a fork.
3. **CLI + headless daemon** (`lumen-ctl`): `lumen top/now/export --json`, feeds waybar/i3status, works over SSH. Constraint: UI is the face, CLI is the hands — no CLI feature ships without its mirror-surface twin. Ships before any HTTP API.
4. **Absorption sessions** (renamed from "Flow" — zero valence): auto-detected uninterrupted blocks, low switch count, boundary-aware nudge. No streaks, no good/bad taxonomy, no "you were in flow" headlines. Detection without judgment.
5. **Browser extension** (Firefox-first, then Chromium): per-site time feeding the local daemon via authenticated localhost WebSocket. The coverage-gap killer (OS trackers see "Firefox" as one blob). Constraint: no counts in the badge, no red badges, no color-coded site lists — on-demand disclosure only; calm pairing ritual, not a pasted token.
6. **Dev-environment integrations** (the moat): projects-not-apps — workspace detection on hyprland/sway/i3, editor/terminal hooks naming the current repo. **Descriptive only** (which repo, which workspace), never "productive vs wasted."

## Architecture work that enables the above

- **CRDT-ize rollups + CORRECTION records** (fixes frozen-contract inconsistency: rollups are "derived, never authoritative" yet sync as `RecordKind.ROLLUP`; events/buckets prune at 30d/6mo but rollups live forever). Corrections need a visual language — annotation on the record, not a rewrite ("invisible plumbing, visible honesty").
- **Metadata-hardening transport**: fixed-interval padded sync, jitter, cover traffic — uses the existing padding field. Cheapest security win. **Measure the Android battery number first** (cover traffic is a tax; a battery-eating tracker gets uninstalled in week one).
- **macOS as Agent B's true parallel lane** — in motion: PR #11 landed the `AppUsageCollector` seam (transitions-not-durations) in core before the M1 freeze. Freeze-gated, NSWorkspace/lsappinfo spike done, M0 green on both machines.

## M0 status

Tagged `M0`. `:core:build` green on Arch + macOS (Agent B confirmed). E2EE freeze-review accepted. Next: M1 contract freeze.
