#!/usr/bin/env bash
# Applies the patches from device/amazon/suez/patches/ to the source tree.
#
# Why not the bundled apply.sh:
#
#   1. It never sets $rootdirectory (only reset.sh does). "cd $rootdirectory"
#      therefore jumps into the home directory, and "cd bionic" fails.
#   2. It calls "git apply <directory>/*.patch". If ONE patch of the glob
#      fails, git applies none at all - and the script does not report it,
#      because the return value is not checked.
#   3. It cannot be repeated: a second run fails on everything that is
#      already applied.
#
# This version applies every patch on its own, checks first, skips what is
# already applied and reports a summary at the end.
#
# Without these patches the build lacks all of the MediaTek adaptations:
# camera, microphone, HEVC, Bluetooth, sdcard permissions, hardware bitmaps.
set -u

ROOT="${1:-/root/los16}"
PATCHDIR="$ROOT/device/amazon/suez/patches"

[ -d "$PATCHDIR" ] || { echo "ERROR: $PATCHDIR is missing"; exit 1; }

# These two are the device tree's broad microG signature-spoofing patches and
# are deliberately NOT applied. With them, any app holding the spoofing
# permission could claim any other app's signature once a developer switch is
# on - a security hole for every app on the device. LineageOS's own restricted
# microG spoofing stays in the tree: it only spoofs for microG-signed
# com.google.android.gms and com.android.vending, and only Google's signature.
SKIPPED="frameworks/base/0003-micro-g-add-enhanced-signature-spoofing.patch
packages/apps/Settings/0001-micro-g-rebased-signature-spoofing-enablement.patch"

APPLIED=0; ALREADY=0; FAILED=0; MISSING=0

for P in $(find "$PATCHDIR" -name '*.patch' | sort); do
    REL="${P#$PATCHDIR/}"
    PROJECT="$(dirname "$REL")"
    TARGET="$ROOT/$PROJECT"
    NAME="$(basename "$REL")"

    if [ ! -d "$TARGET/.git" ]; then
        printf '  %-28s %-52s MISSING (not a git project)\n' "$PROJECT" "$NAME"
        MISSING=$((MISSING+1))
        continue
    fi

    if printf '%s
' "$SKIPPED" | grep -qxF "$REL"; then
        printf '  %-28s %-52s deliberately not applied
' "$PROJECT" "$NAME"
        ALREADY=$((ALREADY+1))
    elif git -C "$TARGET" apply --reverse --check "$P" 2>/dev/null; then
        printf '  %-28s %-52s already applied\n' "$PROJECT" "$NAME"
        ALREADY=$((ALREADY+1))
    elif git -C "$TARGET" apply --check "$P" 2>/dev/null; then
        if git -C "$TARGET" apply "$P" 2>/dev/null; then
            printf '  %-28s %-52s applied\n' "$PROJECT" "$NAME"
            APPLIED=$((APPLIED+1))
        else
            printf '  %-28s %-52s ERROR while applying\n' "$PROJECT" "$NAME"
            FAILED=$((FAILED+1))
        fi
    else
        printf '  %-28s %-52s DOES NOT APPLY\n' "$PROJECT" "$NAME"
        git -C "$TARGET" apply --check "$P" 2>&1 | sed 's/^/        /' | head -4
        FAILED=$((FAILED+1))
    fi
done

printf '\n'
printf '  applied:             %d\n' "$APPLIED"
printf '  already or skipped:  %d\n' "$ALREADY"
printf '  did not apply:       %d\n' "$FAILED"
printf '  project missing:     %d\n' "$MISSING"
printf '\n'

# The camera fix is the reason for this project - check it separately.
CD="$ROOT/hardware/interfaces/camera/device/1.0/default/CameraDevice.cpp"
printf '  Checking the camera fix (sGetMemoryUser in CameraDevice.cpp):\n'
if grep -q "sGetMemoryUser" "$CD" 2>/dev/null; then
    printf '    present - the null pointer fix is in the tree\n'
    grep -n "sGetMemoryUser" "$CD" | head -3 | sed 's/^/      /'
else
    printf '    MISSING - the camera will not work\n'
    FAILED=$((FAILED+1))
fi
printf '\n'

[ "$FAILED" -eq 0 ] && [ "$MISSING" -eq 0 ] && exit 0 || exit 1
