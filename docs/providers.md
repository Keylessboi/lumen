# Lumen v1 — Provider Vetting Policy and List

**Owner:** Agent B (`docs/plan.md`, Two-Agent Execution Contract)
**Status:** Draft policy. The concrete provider list requires **human sign-off** — `docs/plan.md` schedules provider vetting as a week-1 human task, and this document does not override that.
**Consumed by:** `transport-xmpp/providers/` (embedded list) and the M4 account picker.

---

## 1. What this has to satisfy

From the locked constraints in `docs/plan.md`:

- In-app account creation via **XEP-0077 In-Band Registration**
- **4–6 hand-vetted, captcha-free providers**, static health-filtered list
- Friendly picker — *"Where should your data live?"* — **never** the words XMPP, JID, or server address
- Ordering: recommended → community → advanced (self-host)
- Max 5 shown; inline CAPTCHA if encountered must be **skippable**
- **"Provider list = security supply chain"**
- Provider death → one-tap export + migrate, first-class

Everything below follows from that last-but-one point, which is the one that constrains the design most and is easiest to under-read.

---

## 2. The provider list is attack surface

A user who taps the first entry in our picker is trusting **us**, not the provider — they have no way to evaluate `yax.im` versus anything else, and by design we never show them enough protocol detail to try. Whatever we put at the top of that list is where the median user's data goes.

That has a concrete consequence for how the list is delivered:

> **The embedded list MUST be a build-time pinned snapshot, reviewed by a human, shipped in the binary. Lumen MUST NOT fetch the provider list from a remote URL at runtime.**

Rationale: a runtime fetch makes whoever serves that file a silently-trusted third party. Anyone who compromises it — or compels its operator — can steer every new Lumen user onto a provider they control. Since §4 of `docs/e2ee.md` establishes that the provider learns sync timing, volume, device count and JID, and since fingerprint verification is advisory in v1 (TOFU), a hostile provider placed at the top of the list is a real attack, not a theoretical one.

A stale list is a much smaller problem than a hijacked one. Staleness is handled by §7.

---

## 3. Upstream source

We do not vet providers from scratch. The [XMPP Providers project](https://providers.xmpp.net/) already maintains a curated, machine-readable list with a published methodology, and re-inventing it would be worse and would rot faster.

Verified properties of the upstream (checked while writing this doc):

- **Category A** is defined as: supports account registration via XMPP apps (explicitly XEP-0077 In-Band Registration) **and** free of charge. That is exactly Lumen's hard requirement.
- Several properties are **updated automatically daily** — a web bot pulls ratings, an XMPP bot probes registration support.
- Unverifiable properties are treated as absent — a conservative default that suits us.
- Machine-readable JSON is published per category, e.g. `https://data.xmpp.net/providers/v2/providers-A.json`, and is explicitly intended for integration into XMPP apps.

The per-provider schema carries the fields we need, including several the plan implicitly requires but never names:

| Upstream field | Why Lumen cares |
|---|---|
| `inBandRegistration` | Hard requirement — no IBR, no in-app signup |
| `inBandRegistrationCaptchaRequired` | Hard requirement — the plan says captcha-free |
| `inBandRegistrationEmailAddressRequired` | **Disqualifying.** An email requirement turns a <60s local-first onboarding into an identity-linking step |
| `maximumMessageArchiveManagementStorageTime` | Sync correctness — see §5. The most under-appreciated field here |
| `busFactor` | Directly models the plan's provider-death risk |
| `serverLocations` | Jurisdiction; surfaced to the user in plain language |
| `freeOfCharge`, `professionalHosting` | Category A implies free; professional hosting informs longevity |
| `ratingXmppComplianceTester`, `ratingImObservatoryClientToServer` / `ServerToServer` | Transport hygiene; TLS posture |
| `passwordReset` | Recovery story we must describe honestly (see §6) |
| `since`, `latestChange` | Operational track record |

---

## 4. Selection criteria

### Hard requirements — a provider failing any of these is excluded, no judgement call

1. `inBandRegistration` = true
2. `inBandRegistrationCaptchaRequired` = false
3. `inBandRegistrationEmailAddressRequired` = false
4. `freeOfCharge` = true
5. Upstream category **A**
6. Client-to-server rating at the upstream's top grade — we carry E2EE payloads, but the JID and all §4-metadata still ride the TLS session
7. MAM retention ≥ 30 days — see §5

### Ranking among survivors

Ordered by how much each reduces user harm, not by convenience:

1. **`maximumMessageArchiveManagementStorageTime`**, longest first (§5)
2. **`busFactor`**, higher first — an operator hit by a bus takes the user's sync with them
3. **`professionalHosting`** and a long `since` — proxies for "still here in two years"
4. **`serverLocations`** — prefer strong data-protection jurisdictions; surfaced as plain-language text, never as a flag emoji ranking
5. **`passwordReset`** availability

### Deliberately not a criterion

**Server software and version.** Tempting as a health signal, but it changes underneath us and would make the pinned list rot faster without materially improving the choice.

---

## 5. MAM retention is a correctness constraint, not a nice-to-have

This does not appear in `docs/plan.md` and it should.

Lumen's sync is not chat. A device publishes records and other devices pull what they missed. The provider's Message Archive Management retention window is therefore **the maximum time a device may be offline before it permanently misses records.**

If a provider expires MAM after 7 days:

- A laptop shut in a drawer over a two-week holiday comes back to a **permanent hole** in its history.
- Nothing surfaces an error. The sync engine sees a gap it cannot fill, `sync_watermark` advances past it, and the data is simply gone.
- Lumen's own `events` prune horizon is ~30 days, so the source device may also have dropped the raw events by then.

Two requirements follow:

1. **Provider selection:** MAM retention ≥ 30 days, matching the events prune horizon. Providers below this are excluded regardless of other qualities.
2. **Engine behaviour (Agent A, `SyncEngine`):** a detected gap that MAM can no longer fill MUST surface as a visible integrity warning, not a silent watermark advance. This mirrors the rule in `docs/e2ee.md` §6 — silent skip is how sync corruption becomes invisible data loss.

Filed as a note for the M1 freeze discussion since it touches `SyncEngine` behaviour, which is Agent A's.

---

## 6. Honest copy in the picker

The locked constraint forbids protocol jargon. It does **not** license vagueness about consequences. Rules for the picker UI:

- Say **"Where should your data live?"** — plan-locked wording.
- Show, per option: plain-language location ("Servers in Germany"), who runs it, and how long they have.
- State once, plainly: **"Your usage data is encrypted before it leaves this device. Whoever runs this server can see when you sync and how much, but not what you did."** This is the §4 metadata reality from `docs/e2ee.md` and it must not be softened.
- **Skip is a peer option, not a footnote.** Local-only is the default posture of the product; the picker must not imply an account is required. A first-run blocker violates the <60s constraint.
- Never render a raw JID. If a support ticket needs one, it lives in an advanced/diagnostics screen.
- **Password reset:** where a provider offers none, say so at the moment of choosing — "If you forget this password, this provider cannot recover your account." Do not bury it. Note that this is *account* recovery, entirely separate from the Argon2id export passphrase, which nobody can recover. The UI must not let those two blur together.

---

## 7. Refresh, staleness, and re-vetting

Because the list is pinned at build time (§2):

- **Re-vet every release**, and at minimum quarterly. Pull the current `providers-A.json`, re-apply §4, diff against the pinned list.
- **Removal is urgent, addition is not.** A provider that drops out of Category A, starts requiring a captcha or an email address, or falls below the MAM floor should be removed in a patch release. Adding a newly-qualifying provider can wait for the next normal release.
- **A provider that disappears entirely triggers the migrate flow**, not a list edit. Existing users on it need the one-tap export + migrate path from decision E; that path is a v1 gate (G4/M5), not a maintenance script.
- Record the upstream snapshot date next to the pinned list so staleness is auditable rather than guessed.

---

## 8. Candidate shortlist — NOT yet vetted

`providers-A.json` at the time of writing contained roughly a dozen and a half Category A entries, including `07f.de`, `chalec.org`, `chatterboxtown.us`, `hookipa.net`, `jabber.fr`, `jabjab.de`, `magicbroccoli.de`, `nixnet.services`, `openim.nl`, `projectsegfau.lt`, `xmpp.party` and `yax.im`.

**This is a candidate pool, not the shipping list, and it must not be copied into `transport-xmpp/providers/` as-is.** Two reasons:

1. I have not verified the per-provider values of the §4 hard requirements — in particular MAM retention and the email-address requirement — against live data for each candidate. Category A membership alone does not establish them.
2. `docs/plan.md` assigns provider vetting to a **human in week 1**, and §2 of this document is the reason that is the right call: the list is a supply chain, and a human should own what we point users at.

**Proposed process to close this out:**

1. Mechanically filter `providers-A.json` by the §4 hard requirements → produces the eligible set. This part is scriptable and belongs in `tools/registry-builder` or a sibling.
2. Rank by §4 ordering → take the top 5.
3. Human reviews each: reads the legal notice and privacy policy, confirms the operator is identifiable, sanity-checks longevity.
4. Pin with the snapshot date; record the rejected-and-why list in this file so the next re-vet does not relitigate.

Step 1 is Agent B's and can land as soon as zone ownership settles. Steps 3–4 need Travis or Linus, not an agent.

---

## Sources

- [XMPP Providers](https://providers.xmpp.net/) — curated provider directory
- [XMPP Providers FAQ](https://providers.xmpp.net/faq/) — category criteria, daily bot verification, XEP-0077 as the app-registration test
- [xmpp-providers repository](https://invent.kde.org/melvo/xmpp-providers) — machine-readable list, schema
- [XEP-0077: In-Band Registration](https://xmpp.org/extensions/xep-0077.html)
- [XEP-0445: Pre-Authenticated In-Band Registration](https://xmpp.org/extensions/xep-0445.html) — worth evaluating post-v1 for invite-based onboarding
