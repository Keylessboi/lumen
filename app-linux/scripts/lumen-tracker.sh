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
# paste -sd: joins without a trailing ':' (an empty classpath entry means
# "the current directory", which under systemd is $HOME).
CP="$(find "$APP_DIR/lib/app" -name '*.jar' -print | paste -sd: -)"

# Prefer the runtime jpackage bundled next to the app: it is the Java the app
# was built and tested with, and the host may have none (or an older one —
# the build targets 17). Fall back to JAVA_HOME, then PATH.
if [ -x "$APP_DIR/lib/runtime/bin/java" ]; then
    JAVA="$APP_DIR/lib/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

exec "$JAVA" -cp "$CP" dev.lumen.app.HeadlessTrackerKt
