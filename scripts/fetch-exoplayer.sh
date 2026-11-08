#!/bin/bash
# Fetches ExoPlayer 2.19.1 and Guava for the gallery's video player.
#
# Why 2.19.1: it is Java 8 bytecode, which this tree's toolchain takes, and
# of androidx.core - not in the tree in a usable version - it uses only the
# download notification helper, which the player never touches. Media3, its
# successor, wants androidx.core 1.8 and with it a dozen more libraries.
# Only the modules for local and HTTP playback (MP4, MKV, WebM, MOV, MP3, ...)
# are fetched; ProGuard drops what is unused.
#
# Apache 2.0, from Google's Maven repository and Maven Central. The archives
# do not go into the repo; each is verified by its checksum.
set -eu
W=${W:-/root/los16}
LIBS=$W/packages/apps/Gallery2/libs
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$LIBS"

EXO=https://dl.google.com/android/maven2/com/google/android/exoplayer
CENTRAL=https://repo1.maven.org/maven2
while read -r NAME URL SHA256; do
    [ -n "$NAME" ] || continue
    curl -fsSL -o "$TMP/$NAME" "$URL"
    echo "$SHA256  $TMP/$NAME" | sha256sum -c --quiet || { echo "  checksum wrong: $NAME"; exit 1; }
    case "$NAME" in
        *.aar)
            BASE=${NAME%.aar}
            unzip -p "$TMP/$NAME" classes.jar > "$LIBS/$BASE.jar"
            unzip -p "$TMP/$NAME" proguard.txt >> "$TMP/rules" 2>/dev/null || true ;;
        *) cp "$TMP/$NAME" "$LIBS/$NAME" ;;
    esac
    echo "  $NAME"
done <<EOF
exoplayer-core.aar $EXO/exoplayer-core/2.19.1/exoplayer-core-2.19.1.aar e89b76f0d496d10cef1b5ee0d7606052875fd23a56ae64a8083b3a614f2a530d
exoplayer-common.aar $EXO/exoplayer-common/2.19.1/exoplayer-common-2.19.1.aar 5cc09569ce52810722c979ab73b1820167db88e1bb1cbaaab8b8650c70f35f41
exoplayer-container.aar $EXO/exoplayer-container/2.19.1/exoplayer-container-2.19.1.aar 0a1264dfd494ddc3db0c40b5187bcce7b7c2f959de97162a99a65123a6e679ea
exoplayer-datasource.aar $EXO/exoplayer-datasource/2.19.1/exoplayer-datasource-2.19.1.aar d26a3cbf2a6fca428ef95d944c0a03e3f8c447096c27d5cf53f21d3bb5eef005
exoplayer-decoder.aar $EXO/exoplayer-decoder/2.19.1/exoplayer-decoder-2.19.1.aar be361785dcfb5f96edda6cdfe64d415d3d44d76f255e8f7c269507894a7744cf
exoplayer-extractor.aar $EXO/exoplayer-extractor/2.19.1/exoplayer-extractor-2.19.1.aar a2d344aad193cb4ecdaf2f94e23797d2cca794ead559290729891b0512b0a659
exoplayer-database.aar $EXO/exoplayer-database/2.19.1/exoplayer-database-2.19.1.aar 9f3feb1e7f17c8727fcf55a3cc89354a02eae2d62e9ba437dd1c34d60e953337
guava.jar $CENTRAL/com/google/guava/guava/31.1-android/guava-31.1-android.jar 32ac2ed709d96d278b5d2e3e5cea178fa4939939c525fb647532f013308db309
failureaccess.jar $CENTRAL/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26
EOF
python3 "$(dirname "$0")/strip-type-annotations.py" "$LIBS"/*.jar
cp "$TMP/rules" "$LIBS/exoplayer.flags"
echo "  rules: $LIBS/exoplayer.flags"
