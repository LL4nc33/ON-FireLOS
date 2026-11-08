#!/bin/bash
# Fetches the apps ON-FireLOS ships in place of Google's: Droid-ify (free apps
# from the F-Droid catalogue), Aurora Store (apps from the Play catalogue,
# anonymously, without Google's services) and eSpeak (text-to-speech with
# German among its languages, for the browser's read-aloud; the other free
# engines either lack German or need Android 10).
#
# All three are GPL-3.0 and built by F-Droid from their published sources,
# which F-Droid keeps alongside each release. They are taken from F-Droid's
# repository unchanged; Droid-ify and Aurora keep F-Droid's signature, so
# Droid-ify can update them later. eSpeak compresses its native library, which
# the build has to unpack for an app in /system - that breaks F-Droid's
# signature, so eSpeak is signed with the ROM's key and updated with the ROM.
# The APKs do not go into the repo; each is verified by its checksum.
# device/amazon/suez/prebuilt-apps/Android.mk installs them.
set -eu
W=${W:-/root/los16}
DEST=$W/device/amazon/suez/prebuilt-apps
REPO=https://f-droid.org/repo
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

while read -r MODULE APK SHA256; do
    [ -n "$MODULE" ] || continue
    curl -fsSL -o "$TMP/$APK" "$REPO/$APK"
    echo "$SHA256  $TMP/$APK" | sha256sum -c --quiet || { echo "  checksum wrong: $APK"; exit 1; }
    mkdir -p "$DEST/$MODULE"
    cp "$TMP/$APK" "$DEST/$MODULE/$MODULE.apk"
    echo "  $MODULE ($APK)"
done <<EOF
Droidify com.looker.droidify_780.apk bb23d12e8b82a5cba906e61def49dc39c11468d2333633a72e107ca422e8f879
AuroraStore com.aurora.store_76.apk fd9c75d90d0f4a7c132b9b4a5a2cf1992a45e03b8d8ff988b7dcfbc0db2c4d11
ESpeak com.reecedunn.espeak_22.apk 0a7822fec54d7f7ae759ffedc9afeaa77f809587b171fb2101998787821ec790
EOF
