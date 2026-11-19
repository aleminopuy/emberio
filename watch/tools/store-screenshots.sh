#!/usr/bin/env bash
# Takes the appstore screenshots: five per platform, at the display's own size, named
# <platform>_<n>.png as `pebble publish` and the Developer Dashboard expect.
#
#   watch/tools/store-screenshots.sh
#
# Writes store/screenshots/ at the repository root. Needs the Pebble SDK.
set -euo pipefail

cd "$(dirname "$0")"
OUT="$(cd ../.. && pwd)/store/screenshots"
RAW="$(mktemp -d)"
trap 'rm -rf "$RAW"' EXIT

./screenshots.sh "$RAW" aplite basalt chalk diorite emery flint gabbro

mkdir -p "$OUT"
rm -f "$OUT"/*.png
# Heating, perfect, on the coaster, the menu (presets), the menu (alerts).
for platform in aplite basalt chalk diorite emery flint gabbro; do
  n=1
  for shot in 1 2 3 menu menu-alerts; do
    cp "$RAW/$platform-$shot.png" "$OUT/${platform}_$n.png"
    n=$((n + 1))
  done
done
echo "Store screenshots in $OUT"
