#!/usr/bin/env python3
"""Measure the category registry against a real machine, not a wish list.

The G5 acceptance criterion is "popular apps ~100% correct", and until now the
only thing checking it was a hand-written list of eighteen apps in
`CategoryEngineTest`. A list written by the same person who wrote the registry
tests that the registry contains what they remembered putting in it.

This measures two different things, because they answer different questions:

  installed  — breadth. Every app on the machine, whether or not it is used.
               Tells you what the registry would face on a fresh install.

  recorded   — what actually matters. The apps in the user's own history,
               weighted by TIME. An app used four hours a day and an app
               opened once are not equally important, and a coverage number
               that counts them equally is the wrong number.

System UI is excluded from the recorded figures. `com.apple.loginwindow` is
8 hours of this machine's history and is filtered before anything reaches the
category engine; counting it as "uncategorised" would report a gap that does
not exist. The ids come from `MacSystemUi`.

Usage:
    python3 tools/registry-builder/corpus-report.py [--db PATH] [--json]
"""

import argparse
import collections
import json
import os
import plistlib
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REGISTRY_TSV = os.path.join(HERE, "registry.tsv")

DEFAULT_DB = os.path.expanduser("~/Library/Application Support/Lumen/lumen.db")

APP_DIRS = [
    "/Applications",
    "/Applications/Utilities",
    "/System/Applications",
    "/System/Applications/Utilities",
    os.path.expanduser("~/Applications"),
]

# Kept in step with app-macos MacSystemUi.IDS. Duplicated rather than parsed
# out of the Kotlin: a report that silently stopped excluding these would
# quietly overstate the gap, which is worse than a list to keep in step.
SYSTEM_UI = {
    "com.apple.loginwindow",
    "com.apple.SecurityAgent",
    "com.apple.UserNotificationCenter",
    "com.apple.accessibility.universalAccessAuthWarn",
    "com.apple.ScreenSaver.Engine",
    "com.apple.screensaver",
}


def load_registry():
    keys = {}
    with open(REGISTRY_TSV) as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) == 2:
                keys[parts[0].strip().lower()] = parts[1].strip()
    return keys


def installed_apps():
    """Bundle id -> display name, for every .app on this Mac."""
    found = {}
    for directory in APP_DIRS:
        if not os.path.isdir(directory):
            continue
        for entry in sorted(os.listdir(directory)):
            if not entry.endswith(".app"):
                continue
            plist_path = os.path.join(directory, entry, "Contents", "Info.plist")
            try:
                with open(plist_path, "rb") as handle:
                    info = plistlib.load(handle)
            except Exception:
                continue
            bundle_id = info.get("CFBundleIdentifier")
            if bundle_id:
                found[bundle_id] = entry[: -len(".app")]
    return found


def recorded_time(db_path):
    """app_key -> milliseconds, from the user's own history."""
    if not os.path.exists(db_path):
        return {}
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = con.execute(
            "SELECT app_key, SUM(duration_ms) FROM events GROUP BY app_key"
        ).fetchall()
    finally:
        con.close()
    return {app: ms or 0 for app, ms in rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    registry = load_registry()
    installed = installed_apps()
    recorded = {
        app: ms for app, ms in recorded_time(args.db).items() if app not in SYSTEM_UI
    }

    def known(key):
        return key.strip().lower() in registry

    installed_hits = [k for k in installed if known(k)]
    installed_misses = sorted(k for k in installed if not known(k))

    total_ms = sum(recorded.values())
    covered_ms = sum(ms for app, ms in recorded.items() if known(app))
    recorded_misses = sorted(
        ((ms, app) for app, ms in recorded.items() if not known(app)), reverse=True
    )

    if args.json:
        print(
            json.dumps(
                {
                    "registry_entries": len(registry),
                    "installed": {
                        "total": len(installed),
                        "covered": len(installed_hits),
                        "misses": installed_misses,
                    },
                    "recorded": {
                        "apps": len(recorded),
                        "total_ms": total_ms,
                        "covered_ms": covered_ms,
                        "misses": [
                            {"app_key": app, "ms": ms} for ms, app in recorded_misses
                        ],
                    },
                },
                indent=2,
            )
        )
        return 0

    print(f"registry entries: {len(registry)}")
    print()
    print("INSTALLED — breadth")
    if installed:
        pct = 100.0 * len(installed_hits) / len(installed)
        print(f"  {len(installed_hits)}/{len(installed)} apps categorised ({pct:.0f}%)")
        print("  not in the registry:")
        for key in installed_misses:
            print(f"    {key}  ({installed[key]})")
    else:
        print("  no applications found")

    print()
    print("RECORDED — what this person actually does, by time")
    if total_ms:
        print(f"  {total_ms / 3_600_000:.1f}h across {len(recorded)} apps")
        print(f"  {100.0 * covered_ms / total_ms:.1f}% of that time is categorised")
        print("  uncategorised, largest first:")
        for ms, app in recorded_misses:
            print(f"    {ms / 3_600_000:7.2f}h  {app}")
    else:
        print(f"  no history at {args.db}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
