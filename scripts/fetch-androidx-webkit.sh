#!/bin/bash
# Fetches androidx.webkit 1.10.0 for Jelly.
#
# Why: the tree only has 1.0.0-alpha1. Only newer versions pass through the
# WebView 138 features the browser needs: scripts that run before the page's
# own code (ad blocker), separate profiles (private tab) and forced dark mode.
# 1.10.0 is the last version that only requires androidx.annotation and
# androidx.core 1.1; of core it uses only WebViewAssetLoader, which Jelly does
# not use.
#
# Apache 2.0, from Google's Maven repository. The archive does not go into the
# repo; it is verified by its checksum.
set -eu
W=${W:-/root/los16}
VERSION=1.10.0
SHA256=fdc05992036d0665090b77d7050f9babce74dce95b5364c79026d1df4dc46aba
TARGET=$W/packages/apps/Jelly/app/libs/androidx-webkit-$VERSION.aar

mkdir -p "$(dirname "$TARGET")"
if [ -f "$TARGET" ] && echo "$SHA256  $TARGET" | sha256sum -c --quiet 2>/dev/null; then
    echo "  present: $TARGET"
    exit 0
fi
curl -fsSL -o "$TARGET.tmp" \
    "https://dl.google.com/android/maven2/androidx/webkit/webkit/$VERSION/webkit-$VERSION.aar"
echo "$SHA256  $TARGET.tmp" | sha256sum -c --quiet || { rm -f "$TARGET.tmp"; echo "  checksum wrong"; exit 1; }
mv "$TARGET.tmp" "$TARGET"
echo "  fetched: $TARGET"
