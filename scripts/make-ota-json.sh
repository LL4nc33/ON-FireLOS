#!/usr/bin/env bash
# Writes the update list the system updater reads for a ROM zip.
#
#   scripts/make-ota-json.sh <ON-FireLOS-...-suez.zip> <release-tag> > suez.json
#
# The updater (Settings > System > System updates) asks
# https://github.com/LL4nc33/ON-FireLOS/releases/latest/download/suez.json
# (lineage.updater.uri, device/amazon/suez/device.mk): attach suez.json to
# the GitHub release next to the zip. The updater offers a build when its
# version matches the installed Android version (16.0), its type the
# installed one (UNOFFICIAL) and it is newer than the installed build; it
# checks the zip's signature against the system's release key before it
# hands it to the recovery.
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "usage: $0 <rom.zip> <release-tag>" >&2
    exit 2
fi
ZIP=$1
TAG=$2
NAME=$(basename "$ZIP")

# The build time the updater compares with ro.build.date.utc.
TIMESTAMP=$(unzip -p "$ZIP" META-INF/com/android/metadata | sed -n 's/^post-timestamp=//p')
SIZE=$(stat -c %s "$ZIP")
ID=$(sha256sum "$ZIP" | cut -d' ' -f1)
# ON-FireLOS-16.0-20261104-UNOFFICIAL-suez.zip
VERSION=$(printf '%s\n' "$NAME" | sed -n 's/^ON-FireLOS-\([0-9.]*\)-.*/\1/p')
TYPE=$(printf '%s\n' "$NAME" | sed -n 's/^ON-FireLOS-[0-9.]*-[0-9]*-\([A-Z]*\)-.*/\1/p')

if [ -z "$TIMESTAMP" ] || [ -z "$VERSION" ] || [ -z "$TYPE" ]; then
    echo "$NAME: no build time in META-INF/com/android/metadata or an unexpected name" >&2
    exit 1
fi

cat <<EOF
{
  "response": [
    {
      "datetime": $TIMESTAMP,
      "filename": "$NAME",
      "id": "$ID",
      "romtype": "$TYPE",
      "size": $SIZE,
      "url": "https://github.com/LL4nc33/ON-FireLOS/releases/download/$TAG/$NAME",
      "version": "$VERSION"
    }
  ]
}
EOF
