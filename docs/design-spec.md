# Lumen Design Spec (Week-1, FROZEN at M0)

Owner: Agent A. Agent B may read and file issues, never edit.
This spec is normative — "beautiful" is defined here, before code.

## Design language

Lumen is a **mirror, not a judge**. The app tells the user where their
attention went without shame energy. If the overview screen glows red
and says "you're failing", users get learned helplessness and uninstall.

- **Tone**: calm, restrained, honest. Zero guilt-bombing. No streaks,
  no badges, no gamification. Streaks in a screen-time app are a shame
  engine; loss-aversion drives abandonment, not retention.
- **Copy**: plain declarative sentences. "4h 20m Communication" — not
  "You spent way too long on social media". Never "smart", never "AI".
- **Privacy posture**: local-first. Sync is opt-in ("back up / sync
  devices"), never a first-run blocker. First run completes under 60
  seconds with no account.

## Color

- **Background**: ink-near-black `#0E1116` — NOT pure `#000` (OLED
  smearing on Android). Dark mode is the default and primary.
- **Accent**: calm indigo `#7C9CF5` (or teal `#6BBFA8` — pick ONE and
  hold it; the accent is the only saturated color in the app).
- **Category palette**: Okabe-Ito colorblind-safe categorical palette
  (8 hues). Categories never use red as a "bad" state — red only for
  destructive actions (delete, revoke device).
- **Semantic states**: success/limit-hit are expressed with text and
  icon, never color alone (accessibility).

## Theme behavior

- Respect `prefers-color-scheme` / `prefer-dark` on Linux (Hyprland
  users run Catppuccin/Gruvbox/Nord — the app follows the system
  scheme; identity comes from typography and motion, not palette).
- On Android: follow system dark/light toggle. No separate in-app theme
  switcher in v1.

## Typography

- A grotesque sans with **tabular figures** — non-negotiable for time
  data. Numbers that shift width during animation look broken.
- Numerals: tabular (fixed-width) in every time readout: `4h 20m`,
  rollup totals, chart axis labels.
- Full CJK/emoji fallback required on Android (system font fallback).
- Type scale: 4 steps max. Time readouts at largest step, labels
  small-caps or muted secondary.

## Spacing & shape

- Generous spacing; data-dense but not cluttered.
- On Hyprland: **opaque window, let the compositor round the corners.**
  Never double-round (app-drawn rounded corners + compositor rounding
  = garbage). No client-side blur — the compositor owns blur.

## Motion

- 150–250ms ease-out transitions. No bounce.
- Charts animate in; numbers roll.
- Respect `prefers-reduced-motion` on both platforms (disable all
  motion).

## Charts

Exactly three chart types in v1 (no more):
1. **Today donut/bars** — per-category time, single glance.
2. **Day curve** — per-app time across the day (line/area), app detail.
3. **7/30-day bars** — per-day totals, trend view.
- Tooltips on FIRST touch/pointer interaction, not hidden in menus.
- No confusing axes; no hidden aggregations. What the chart shows must
  match the numbers beside it.

## Screens (information hierarchy)

1. **Today** (the only screen that matters for retention): intent line
   ("Focus: 3h design work" — stated goal, not judgment), the big
   number, one donut/bars per category, and a **single one-tap "take a
   break" action** (Android: open home; Linux: close focused window).
2. **Categories**: every category gets a "why is this here" affordance
   behind long-press, plus manual re-tag. Unknown apps land in a neutral
   "Uncategorized" bucket — never a confident wrong guess.
3. **App detail**: day curve, totals, 7/30-day history.
4. **Suggestions**: exactly one nudge in v1 — break reminder. Gentle,
   one-tap, never shaming, rate-limited.
5. **Settings / account**: the E2EE onboarding must be a ~60-second
   flow. Account creation = "Where should your data live?" — friendly
   server list, NEVER the words XMPP/JID/server-address. "Advanced: use
   my own server" behind disclosure.

## Abandonment guardrails (design requirements)

- The setup gauntlet kills apps: Android = Usage Access + battery
  optimization exemption (2 hoops — we do NOT use accessibility
  service). Linux = install + autostart. If v1 couples that with
  account creation, we lose 80% of users in 90 seconds — so first-run
  stays local and account is opt-in.
- The app must exclude ITSELF from its own numbers correctly from day
  one, or every number is suspect.
- Charts that lie = uninstall. Numbers and charts must agree.
