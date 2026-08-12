# Lumen v1 — Build Plan

**Provenance:** The plan agent produced this plan from the adversarial bundle (5 hostile lanes × 3 rounds + 5 addenda: platform feasibility, security/systems, product/data, design/UX, scope/execution). It is decision-complete. A builder can execute it without further interviews.

---

## Two-Agent Execution Contract

Two agents build Lumen in parallel. This contract stops interference: every file has exactly one owner, every cross-agent dependency flows through a frozen interface, and every milestone is a git-tagged, independently verifiable unit.

### Ownership zones (one owner per path, no exceptions)

**Machine split (as of M0):** Agent A runs **Arch Linux + Android testing**. Agent B runs **macOS + iOS**. Zones follow the machines.

| Path | Owner | Notes |
|---|---|---|
| `core/src/commonMain/**` | **Agent A** | Data model, rollup, clock, category, sync, crypto. **FROZEN at M1 — after the freeze, Agent B codes against it without editing it.** |
| `core/src/commonTest/**` | **Agent B** | Contract tests (M1 re-cut, discussion #12). Written by the consumer of the frozen API, not its author. |
| `core/src/desktopMain/**` | **Agent A** | Single JVM desktop target (KMP forbids two jvm() targets per module). Keychain lives in app modules, not here. |
| `core/src/androidMain/**` | **Agent A** | Android Keystore impl. Smallest possible surface. |
| `transport-xmpp/**` | **Agent A** | XMPP client, IBR, MAM, embedded provider list. |
| `ui/**` | **shared** | Compose Multiplatform UI — design tokens, screens, charts. Renders `docs/design-spec.md` for every platform (discussion #21). Either agent may edit, the other reviews in the PR body. Token changes remain Agent A's call as spec owner. |
| `app-linux/**` | **Agent A** | CMP desktop app, collectors (hyprland/sway/x11), LinuxKeychain. **UI lives in `:ui`.** |
| `app-android/**` | **Agent A** | CMP Android app, UsageStats collector, hardening. **UI lives in `:ui`.** |
| `app-macos/**` | **Agent B** | CMP macOS app (post-MVP #2). lsappinfo collector, Screen Time importer, MacosKeychain, tray/packaging. **UI lives in `:ui`.** |
| `tools/registry-builder/**` | **Agent B** | Category registry dataset tooling (M6 re-cut, discussion #12). |
| `tools/sync-test-server/**` | **Agent B** | Test XMPP server + ciphertext verifier (M4 re-cut, discussion #12). |
| `docs/design-spec.md` | **Agent A** | Week-1 design spec owner. Agent B may *read* and file issues, never edit. |
| `docs/data-model.md` | **Agent A** | Frozen data model doc. |
| `docs/e2ee.md` | **Agent B** | E2EE design + threat model doc. |
| `docs/providers.md` | **Agent B** | Provider vetting policy + list. |
| `docs/non-goals.md` | shared | Both may append, never delete. |

### Interface freeze points (the only things that cross zones)

These are the *only* files Agent B consumes from Agent A (and vice-versa). They freeze at specific gates and never change without a milestone review:

1. **`core/src/commonMain` public API** — `SyncTransport`, `E2EE` seam, data model types, `rollup` engine signature. **Frozen at M1.** Agent B writes androidMain against this API. Changes after freeze require a tag-bump PR; both agents review it.
2. **`E2EE.kt` envelope format** — frozen at M4 (G3). The ciphertext envelope is a wire contract; `docs/e2ee.md` is its normative spec.
3. **SQLite schema** — frozen at M1. Both agents' UI reads it; schema migrations are additive-only and owned by Agent A.

### Git protocol (how they share one repo)

- `main` branch is **always green** — both agents merge only via passing PRs.
- **Branch prefixes are the agent identifier.** Agent A branches: `a/<milestone>/<slug>` (e.g. `a/m2/linux-collector`). Agent B branches: `b/<milestone>/<slug>` (e.g. `b/m3/usagestats-collector`). Unprefixed branches **fail closed** in CI — the workflow derives the agent from the prefix and refuses to run without one.
- **Merge windows:** Agent A merges to main at M1, M2, M4, M6, M7. Agent B merges at M1, M3, M5, M6, M7. Both branches exist simultaneously; they only touch their own zones, so no merge conflicts are possible *unless a freeze point was violated* — which the CI contract test catches.
- **CI gate (non-negotiable):** every PR must pass `./gradlew :core:test :transport-xmpp:test` plus its own module's build. A PR that touches a file outside its owner's zone **fails CI by convention** (enforced by `tools/ownership-check.sh` wired into `.github/workflows/ownership.yml`). Module-level `build.gradle.kts` files are shared infra: either agent may edit them, the other agent reviews in the PR body.

### Async coordination via GitHub Discussions

The two agents are separate processes and never share a terminal. They coordinate through **GitHub Discussions** on this repo (`https://github.com/Keylessboi/lumen/discussions`). Treat Discussions as the shared bulletin board; the issue tracker is for code defects only.

- **Cross-agent questions** (contract ambiguity, interface drift, dependency ordering) go to a Discussion, tagged `agent-a` or `agent-b`, NOT a DM the other agent will never see. Use the pinned thread `agent-coordination` for anything time-sensitive.
- **Freeze-point changes** (anything touching `core/src/commonMain`, the SQLite schema, or the E2EE envelope) require a Discussion announcing the change + a 24h review window before the tag-bump PR. Both agents watch the `freeze-review` label.
- **Milestone handoffs:** the agent exiting a milestone opens a Discussion titled `handoff: M<N> -> M<N+1>` with the tag hash, what was verified, and what the other agent must confirm before starting. The receiving agent replies with their go/no-go.
- **Naming convention:** `[A]` / `[B]` prefix on the subject line so the other agent can filter. One topic per thread. Close threads when resolved.
- **GitHub issue tracker:** use it only for build failures, CI breaks, security findings, and acceptance-criteria failures. Open an issue with the milestone tag and gate ID (e.g., `M4 / G3`).

### Wake protocol (keeps both agents alive)

Agents sleep between turns. A discussion alone does not wake them. The wake chain fixes this:

1. **GitHub workflow** `.github/workflows/wake-agents.yml` fires on every `discussion` and `discussion_comment` creation and commits a **wake pointer** into `.agent-inbox/` on the `agent-inbox` branch. Pointer = URL + title ONLY, never the body.
2. **Each machine runs a watcher** (`tools/agent-watcher.sh --loop`) that polls the `agent-inbox` branch and reports new pointers to the human. **No auto-invoke: wake files are data, never instructions.** A stranger's comment must never trigger an agent with shell access.
3. **The human starts the agent** when a pointer looks relevant; the agent fetches the discussion itself. For urgent items, @-mention the human (GitHub notifies).

Security rules (from Zone B review, accepted):
- Wake files carry URL + title only. The agent fetches the body itself.
- Wake commits go to `agent-inbox` branch, never `main`. `main` advances only through reviewed PRs.
- The workflow passes event data via `env:`, never shell interpolation (`${{ }}` textual expansion was a remote code execution vector).
- `main` history stays reviewed; the bot's `[skip ci]` commits live on `agent-inbox`.

Setup per machine:
- Arch (this agent): `systemctl --user enable --now lumen-agent-watcher.service` (installed).
- macOS (other agent): `launchd` plist or cron line running `tools/agent-watcher.sh --loop`. See the script header.

Rules: never commit wake files manually (the workflow owns `.agent-inbox/`). The `.saw` state file dedupes — each wake is reported exactly once.

### Addressable milestones

Every milestone is a **git tag** (`M0`..`M8`) on main, with a one-commit-audit trail. "Addressable code" = you can check out the tag and verify the gate independently.

| Tag | Gate | Addressable evidence | Owner |
|---|---|---|---|
| `M0` | Phase 0 exit | `docs/design-spec.md`, `docs/data-model.md`, green `:core:build` | A |
| `M1` | Contract freeze | frozen `core` public API + schema; `ownership-check.sh` green | A (+B review) |
| `M2` | G1 (Linux slice) | `app-linux` collector + UI on real Hyprland | A |
| `M3` | G2 (Android slice) | `app-android` UsageStats + UI on device | A |
| `M4` | G3 (sync+E2EE) | `transport-xmpp` + `core` sync, ciphertext-only verified | A |
| `M5` | G4 (export/migrate) | Argon2id export/import round-trip | A |
| `M6` | G5 (categories) | registry + override behavior | A |
| `M7` | G6 (nudge+polish) | break reminder, design pass, RC | shared |
| `M8` | v1.0 release | full acceptance checklist | shared |

**macOS/iOS (Agent B):** post-MVP #2 (macOS) and #3 (iOS) — no v1 gate. The `desktopMacosMain` source set and `app-macos` module exist as seams from M0 but carry no v1 acceptance criteria. B merges to main only for seam/maintenance work until post-MVP milestones are scheduled.

**Interference rule:** an agent may not start a milestone whose *entry* gate is the other agent's exit gate without confirming that tag exists on main. Work only ever forks from a tagged main.

---

## Adjudicated Decisions

| Fight | Decision | One-line rationale |
|---|---|---|
| A. UI toolkit | **Compose Multiplatform** | Kotlin collapses collector + core + UI into one language/build; Flutter desktop IPC is rough; Qt/QML means two native shells (maintenance multiplier). |
| B. E2EE | **Staged: secretbox v1 (X25519 + libsodium) now; OMEMO 2 hard-pinned first post-MVP** | OMEMO 2 at 6-8wk is ~40% of budget; its new-device recovery problem is solved by the export (E). secretbox v1 is honest E2EE (content confidential), lacks FS/PCS. |
| C. Timeline | **19 weeks (17 core + 2 buffer); export IN v1** | Export is the same Argon2id mechanism as E — one code path retires two top risks. |
| D. Sequencing | **Linux-first vertical slice after both spikes** | Linux capture is highest-risk and persona/repo are Linux-first; Android ports against a proven core. |
| E. New-device history | **(a) passphrase/Argon2id encrypted export** | Provider-death migration (locked) needs it; same file solves OMEMO new-device recovery. |

## Locked Constraints (non-negotiable)

- **Product**: automatic categories + one honest nudge on a beautiful, local-first, privately-synced screen-time log. FOSS, not a business. Buyer: privacy-conscious Linux developer.
- **Local-only default**: sync additive, never a dependency. First run <60s. Account creation = opt-in ("back up / sync devices"), never a first-run blocker.
- **Data model**: event → 1-min bucket → app-day rollup. Reconciliation: LWW + UTC-day for settings/limits; append-merge by (device_id, monotonic seq) for events. NO CRDT, NO wall-clock LWW. Prune events ~30d, buckets ~6mo, rollups forever. SQLite.
- **Categories**: top-500 human-reviewed registry + sticky overrides + neutral Uncategorized. Never a confident wrong guess. "Smart" is a dead word → "automatic categories".
- **Cut from v1**: public API (post-1.0, aggregates-only), profiles, parental controls, suggestions engine, on-device ML/LLM.
- **One nudge**: break-reminder (timer + notification, ~2 days). In v1 if budget survives; else first post-MVP commit, hard-pinned.
- **Transport**: XMPP-first, one client / three configurations (public provider via in-app IBR, self-hosted advanced, local-only). Go HTTPS relay KILLED. SyncTransport seam. E2EE non-optional headline.
- **In-app account creation**: XEP-0077 IBR; 4-6 hand-vetted captcha-free providers, static health-filtered list; friendly picker ("Where should your data live?"), never the words XMPP/JID/server-address; recommended → community → advanced (self-host); max 5; skippable inline CAPTCHA; provider list = security supply chain; provider death → one-tap export + migrate flow FIRST-CLASS.
- **Platforms**: Linux = Hyprland + Sway + X11 (GNOME/KWin structurally unsupported — document). Android = UsageStatsManager authoritative (no accessibility service needed); events kept ~days → poll + persist; FGS only for realtime display.
- **Android hardening**: allowBackup=false; hardware-keystore keys only; no raw window titles synced; honest copy ("content encrypted; the server sees when and how much you sync").

## Phases (entry/exit = verification gates)

| Phase | Weeks | Work | Gate |
|---|---|---|---|
| 0 Foundation | 1 | Repo, design spec (tokens/charts/motion), sync model FREEZE, toolchain green | spec committed, contract frozen, builds green |
| 1 Spikes | ≤5d | Hyprland socket2 tracking; Android UsageStats fidelity (2-3 device matrix) | measured evidence, go/no-go |
| 2 Core + Linux slice | 2-5 | SQLite schema, rollup engine, storage; Linux collector (Hyprland/Sway/X11); CMP read UI | **G1**: real Hyprland, 2-day capture matches reality, local-only <60s |
| 3 Android slice | 5-7 | UsageStats collector; CMP UI port; hardening | **G2**: matches system usage, no Play flags, battery <2%/day |
| 4 Sync + E2EE | 7-11 | XMPP client (3 configs), IBR + picker, secretbox v1, sync engine | **G3**: 2-device E2E via real provider, ciphertext-only server, no loss/dup |
| 5 Export/migrate | 11-13 | Argon2id export/import, provider-death migrate | **G4**: provider death → full history restored on fresh device |
| 6 Categories | 13-15 | Top-500 registry, sticky overrides | **G5**: corpus test, unknown→Uncategorized, overrides sticky |
| 7 Nudge + polish | 15-17 | Break reminder; design pass; edge cases; packaging | **G6**: full acceptance checklist, RC |
| 8 Buffer + release | 17-19 | Absorb overruns; ship when G1-G6 green | release |

## Parallelization

Run these in parallel: Spike 1 ∥ Spike 2; registry dataset (wk2+) ∥ core; provider vetting (wk1, human) ∥ everything; categories module ∥ sync; Sway ∥ X11 after Hyprland proven; docs ∥ final phases.

Run these in sequence: spikes → collectors; schema freeze → slices; transport → engine → E2EE; G3 → export/migrate; G1→G6.

## Repo Layout

```
lumen/
├── README.md, LICENSE (AGPL-3.0), .gitignore
├── docs/  design-spec.md · data-model.md · e2ee.md · providers.md · non-goals.md
├── core/  Kotlin commonMain: model/ store/ rollup/ clock/ category/ sync/ (SyncTransport.kt, SyncEngine.kt, envelope.kt) crypto/ (E2EE.kt, keychain.kt) + androidMain keystore + desktopMain libsecret + commonTest
├── transport-xmpp/  JVM module (android+desktop): IBR (XEP-0077), providers/ static list, mam/
├── ui/  shared Compose Multiplatform: Theme.kt (executable design-spec) · TodayScreen.kt · charts/
├── app-linux/  CMP desktop: collector/{hyprland,sway,x11}/ · window host · LinuxKeychain
├── app-android/  CMP android: collector/usagestats/ · Activity host · hardening/
├── app-macos/  CMP desktop: collector/ · importer/ · store/ · tray + packaging
└── tools/  registry-builder/ · sync-test-server/
```

## SQLite Schema

- **devices**: device_id (uuid PK), display_name, public_key_x25519, created_at_ms, last_seen_ms
- **events** (~30d): seq (per-device monotonic PK), device_id, app_key, title_hash (never synced), started_at_ms, duration_ms, category (snapshot), sync_state (0/1/2), UNIQUE(device_id, seq)
- **buckets** (~6mo): device_id, bucket_ts (UTC minute), app_key, active_ms — PK(device_id, bucket_ts, app_key)
- **rollups** (forever): device_id, day_utc, app_key, total_ms, category — PK(device_id, day_utc, app_key)
- **settings** (LWW+UTC-day): key PK, value, updated_at_ms, updated_day_utc, device_id (last writer)
- **category_registry**: app_key PK, category, source ('registry'|'manual')
- **manual_overrides**: app_key PK, category, created_at_ms, sticky DEFAULT 1
- **sync_watermark**: device_id PK, last_acked_seq

## Top 5 Risks

1. Linux capture reliability → Spike 1, X11 fallback (retired by D)
2. Sync/E2EE correctness → wk1 freeze, G3 ciphertext verifier, keystore (retired by B+C)
3. XMPP/IBR provider dependency → vetted health-filtered list, local-only always, export/migrate (retired by locked #8)
4. CMP desktop maturity → thin UI + Skia canvas, Phase 0 validation (retired by A)
5. Android OEM/battery/Play variance → Spike 2 matrix, no a11y service, battery target (retired by locked #9 + D)

## v1 Acceptance Criteria

- [ ] Linux Hyprland+Sway+X11 capture; local-only first run <60s
- [ ] Android UsageStats matches system; allowBackup=false; no a11y; keystore keys
- [ ] Local-first: sync never blocks; unconfigured transport valid
- [ ] 3-layer data model with locked reconciliation; pruning enforced
- [ ] E2EE content encrypted pre-upload; ciphertext-only verified; honest copy
- [ ] In-app account: friendly picker, max 5, no XMPP jargon, health-filtered
- [ ] Categories: top-500; popular ~100% correct; unknown→Uncategorized; sticky overrides
- [ ] One break-reminder nudge
- [ ] One-tap Argon2id export/import; provider-death migrate
- [ ] Two-device sync: LWW settings; no loss/dup events
- [ ] UI meets week-1 design spec (dark-first, Okabe-Ito, tabular figures, no gamification)
- [ ] AGPL-3.0, public repo

## Non-Goals (v1)

Public API; parental controls; profiles; suggestions engine; ML/LLM; GNOME/KWin; accessibility-service tracking; web/iOS; paid tier; OMEMO 2 (post-MVP #1); team/family sharing; cross-tracker import.
