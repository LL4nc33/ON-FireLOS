#!/usr/bin/env bash
# Writes the update list the system updater reads for a ROM zip.
#
#   scripts/make-ota-json.sh <ON-FireLOS-...-suez.zip> <release-tag> [release-notes.md] > suez.json
#
# With the release notes, the list also carries the release's own name (the
# tag without its "v": "1.02") and its notes as short points (the "- " lines),
# which the updater shows on the update's card ("What's new").
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

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
    echo "usage: $0 <rom.zip> <release-tag> [release-notes.md]" >&2
    exit 2
fi
ZIP=$1
TAG=$2
NOTES=${3:-}
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

# The release's name and notes as JSON strings ("" without notes).
EXTRA=""
if [ -n "$NOTES" ]; then
    PY=$(command -v python3 || command -v python)
    EXTRA=$("$PY" - "$NOTES" "${TAG#v}" <<'PYEOF'
import json, sys
notes = open(sys.argv[1], encoding='utf-8').read().splitlines()
points = [line.strip()[2:].strip() for line in notes if line.strip().startswith('- ')]
print('"changelog": %s,' % json.dumps('\n'.join('\u2022 ' + p for p in points)))
print('      "version_name": %s,' % json.dumps(sys.argv[2]))
PYEOF
)
fi

cat <<EOF
{
  "response": [
    {
      ${EXTRA:+$EXTRA
      }"datetime": $TIMESTAMP,
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
