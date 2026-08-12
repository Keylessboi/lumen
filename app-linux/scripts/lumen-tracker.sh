#!/usr/bin/env bash
# lumen-tracker.sh — launcher for the headless tracking service.
#
# Runs the tracker with plain `java -cp`, NOT the Compose packaged binary:
# the Compose launcher (libapplauncher.so) initialises AWT/Skiko and aborts
# under a headless systemd unit with `__cxa_pure_virtual`. This script loads
# only the tracking pipeline, so the service can run with no display.
#
# Usage (from the app dist root, e.g. .../app/lumen/):
#   bin/lumen-tracker.sh
# Adjust the relative path if the script is installed elsewhere.

set -euo pipefail

# Resolve the app dir: this script lives in <app>/bin, jars are in <app>/lib/app.
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP="$(find "$APP_DIR/lib/app" -name '*.jar' -print | tr '\n' ':')"

exec java -cp "$CP" dev.lumen.app.HeadlessTrackerKt
