# lumen

A privacy-first screen time tracker for Linux desktop and Android, with encrypted sync you control.

Lumen tells you where your attention goes — without selling the answer. Usage data stays on your devices, syncs between them with end-to-end encryption, and never touches a server you don't trust.

## Why

Screen time trackers are either OS-walled gardens (Apple/Google), SaaS surveillance (RescueTime et al.), or rough-edged FOSS with no sync story (ActivityWatch). Lumen is the gap: a beautiful, honest tracker for people who run Hyprland, self-host things, and don't want their behavioral data on someone else's server.

## Features

- **Linux + Android tracking** — per-app foreground time via Hyprland/Wayland compositor events on desktop, UsageStats on Android (no invasive accessibility service for v1)
- **E2EE sync** — usage history syncs between your devices, encrypted so the sync server never sees it
- **Account creation in-app** — create an account right in the app from a list of public providers (no protocol jargon, just servers), or self-host your own sync server, or run fully local
- **Automatic categories** — a curated, human-reviewed app taxonomy instead of ML guesswork; unknown apps land in a neutral "Uncategorized" bucket, never a confident wrong guess
- **Sparse, useful nudges** — a break reminder and pattern-based suggestions, capped at a couple per day. No guilt-bombing, no streaks, no gamification
- **Beautiful, calm UI** — dark-first, respects your system theme, tabular numerals, zero shame energy

## Status

Under active design. The adversarial planning process is running; the architecture plan will land in `docs/plan.md` shortly.

## License

AGPL-3.0
