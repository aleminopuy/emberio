#!/usr/bin/env bash
# Takes emulator screenshots of the watchapp in its demo states, for every platform.
#
#   watch/tools/screenshots.sh [out_dir] [platforms...]
#
# Needs the Pebble SDK (pebble-tool). Demo builds (EMBERIO_DEMO=n) show canned data and don't
# talk to the phone; the normal build is restored at the end.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${1:-../docs/screenshots}"
shift || true
PLATFORMS=("${@:-basalt chalk diorite emery flint gabbro aplite}")
read -r -a PLATFORMS <<<"${PLATFORMS[*]}"
mkdir -p "$OUT"

# States: 1 heating, 2 perfect, 3 charging (°C), 4 searching (cached), 5 no mug.
STATES=(${EMBERIO_STATES:-1 2 3 4 5})

shot() { # platform name
  pebble screenshot --emulator "$1" --no-open "$OUT/$2.png" >/dev/null
}

# One platform at a time, so only one emulator runs.
for platform in "${PLATFORMS[@]}"; do
  # Boot the emulator headless (--vnc). The first install after a boot doesn't launch the app,
  # and passing --vnc again would reboot it, so later installs leave it off.
  EMBERIO_DEMO=1 pebble build >/dev/null
  pebble install --emulator "$platform" --vnc >/dev/null
  for state in "${STATES[@]}"; do
    EMBERIO_DEMO=$state pebble build >/dev/null
    pebble install --emulator "$platform" >/dev/null
    sleep 2
    shot "$platform" "$platform-$state"
    if [[ $state == 1 ]]; then
      # The menu, and its alerts section.
      pebble emu-button --emulator "$platform" click select >/dev/null
      sleep 1
      shot "$platform" "$platform-menu"
      for _ in 1 2 3 4; do pebble emu-button --emulator "$platform" click down >/dev/null; done
      sleep 1
      shot "$platform" "$platform-menu-alerts"
    fi
  done
  pebble kill >/dev/null 2>&1 || true
done

pebble build >/dev/null
echo "Screenshots in $OUT"
