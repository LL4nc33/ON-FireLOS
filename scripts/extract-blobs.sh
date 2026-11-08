#!/usr/bin/env bash
# Pulls the drivers off your own FireHD10 2017 (suez), exactly the files in
# the device tree's list.
#
# This repo deliberately contains NO proprietary blobs from Amazon/MediaTek.
# Every user extracts them from their own device - the usual approach in the
# LineageOS world.
#
# Usage:   scripts/extract-blobs.sh <proprietary-files.txt> [output]
#          The list is in the source tree under device/amazon/suez/ - run
#          apply-patches.sh first, then copy it, because the patch changes it.
#          The output is stock-dump/system; extract-files.sh then gets
#          stock-dump, i.e. the folder above it.
#
# Requirements: adb in PATH, device connected via USB and authorised.
# Runs on stock Fire OS, without root.

set -u
LIST="${1:-}"
OUT="${2:-stock-dump/system}"

die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

[ -f "$LIST" ] || die "Usage: $0 <proprietary-files.txt> [output]"
command -v adb >/dev/null 2>&1 || die "adb not found. Install the Platform-Tools."

state=$(adb get-state 2>/dev/null || true)
[ "$state" = "device" ] || die "No authorised device (state: ${state:-none}).
  - Unlock the display, confirm the 'Allow USB debugging' dialog
  - Check with: adb devices"

model=$(adb shell getprop ro.product.device | tr -d '\r\n')
[ "$model" = "suez" ] || die "Wrong device: '$model' (expected: suez)"

printf 'Device: %s / Fire OS %s\n' \
  "$(adb shell getprop ro.product.model | tr -d '\r\n')" \
  "$(adb shell getprop ro.build.version.name | tr -d '\r\n')"

# One by one instead of whole folders: some files under /system are root-only,
# and adb pull aborts the whole folder at the first of them.
OK=0; MISSING=0
while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    src="${line%%:*}"; src="${src%%|*}"; src="${src%%;*}"
    src="${src#-}"
    mkdir -p "$(dirname "$OUT/$src")"
    if adb pull "/system/$src" "$OUT/$src" >/dev/null 2>&1; then
        OK=$((OK+1))
    else
        printf '  missing: %s\n' "$src"
        MISSING=$((MISSING+1))
    fi
done < <(tr -d '\r' < "$LIST")
adb pull /system/build.prop "$OUT/build.prop" >/dev/null 2>&1

printf '\n  pulled: %d    missing: %d\n' "$OK" "$MISSING"
[ "$MISSING" -eq 0 ] || die "incomplete - extract-files.sh would abort"

cat <<HINT

Next step: copy the folder above "$OUT" to the build machine and run
    device/amazon/suez/extract-files.sh <that folder>
HINT
