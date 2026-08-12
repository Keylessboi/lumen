# lumen

A privacy-first screen time tracker for Linux desktop and Android, with encrypted sync you control.

Lumen tells you where your attention goes. It does not sell your data. Usage data stays on your devices, syncs between them with end-to-end encryption, and never touches a server you do not trust.

## Why

Screen time trackers fall into three groups: the built-in trackers from Apple and Google, paid tracking services like RescueTime, and open-source trackers with no sync, like ActivityWatch. Lumen is the honest tracker for people who run Hyprland, self-host, and want to keep their behavior data off someone else's server.

## Features

- **Linux + Android tracking** — per-app foreground time via Hyprland/Wayland compositor events on desktop, UsageStats on Android (no invasive accessibility service for v1)
- **E2EE sync** — usage history syncs between your devices, encrypted so the sync server never sees it
- **Account creation in-app** — create an account right in the app from a list of public providers (just servers, no protocol jargon), or self-host your own sync server, or run fully local
- **Automatic categories** — a curated, human-reviewed app taxonomy instead of ML guesswork; unknown apps land in a neutral "Uncategorized" bucket, never a confident wrong guess
- **Sparse, useful nudges** — a break reminder and pattern-based suggestions, capped at a couple per day. No guilt-bombing, no streaks, no gamification
- **Calm UI** — dark-first, follows your system theme, tabular numerals, no guilt-tripping

## Status

Active development. The architecture plan has landed: see `docs/plan.md` for milestones and ownership, `docs/STATUS.md` for the current repo state, and the [Discussions](https://github.com/Keylessboi/lumen/discussions) page for coordination. The build is not green yet.

## License

AGPL-3.0

Source: <https://github.com/Keylessboi/lumen>
