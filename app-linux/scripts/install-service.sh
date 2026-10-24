#!/usr/bin/env bash
# install-service.sh — install and start the Lumen background service.
#
# Usage: app-linux/scripts/install-service.sh [path/to/app/lumen]
#   Defaults to the Gradle distributable: app-linux/build/compose/binaries/main/app/lumen
#   (build it first with ./gradlew :app-linux:createDistributable).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${1:-$HERE/../build/compose/binaries/main/app/lumen}" && pwd)"

if [ ! -d "$APP_DIR/lib/app" ]; then
    echo "No Lumen app found at $APP_DIR (expected lib/app/*.jar). Build it first:" >&2
    echo "  ./gradlew :app-linux:createDistributable" >&2
    exit 1
fi

install -Dm755 "$HERE/lumen-tracker.sh" "$APP_DIR/bin/lumen-tracker.sh"

UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"
sed "s|@LUMEN_TRACKER@|$APP_DIR/bin/lumen-tracker.sh|" "$HERE/../systemd/lumen-tracker.service" > "$UNIT_DIR/lumen-tracker.service"

systemctl --user daemon-reload
systemctl --user enable --now lumen-tracker.service
echo "Lumen background service installed: $UNIT_DIR/lumen-tracker.service"
systemctl --user --no-pager status lumen-tracker.service | head -5 || true
