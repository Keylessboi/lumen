# Lumen v1 — Build Plan

**Provenance:** Produced by the plan agent from the distilled adversarial bundle (5 hostile lanes × 3 rounds + 5 addenda: platform feasibility, security/systems, product/data, design/UX, scope/execution). Decision-complete; a builder can execute without further interviews.

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

Parallel: Spike 1 ∥ Spike 2; registry dataset (wk2+) ∥ core; provider vetting (wk1, human) ∥ everything; categories module ∥ sync; Sway ∥ X11 after Hyprland proven; docs ∥ final phases.
Sequential: spikes → collectors; schema freeze → slices; transport → engine → E2EE; G3 → export/migrate; G1→G6.

## Repo Layout

```
lumen/
├── README.md, LICENSE (AGPL-3.0), .gitignore
├── docs/  design-spec.md · data-model.md · e2ee.md · providers.md · non-goals.md
├── core/  Kotlin commonMain: model/ store/ rollup/ clock/ category/ sync/ (SyncTransport.kt, SyncEngine.kt, envelope.kt) crypto/ (E2EE.kt, keychain.kt) + androidMain keystore + desktopMain libsecret + commonTest
├── transport-xmpp/  JVM module (android+desktop): IBR (XEP-0077), providers/ static list, mam/
├── app-linux/  CMP desktop: collector/{hyprland,sway,x11}/ ui/ charts/
├── app-android/  CMP android: collector/usagestats/ ui/ hardening/
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
