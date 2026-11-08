#!/usr/bin/env bash
# Syncs the LineageOS 16.0 sources for suez.
#
# Shallow clone (--depth=1, without tags): we want to build once, not keep
# history. Saves about 20 GB compared with a full sync.
set -u

WORK=/root/los16
BRANCH=lineage-16.0
JOBS="$(nproc)"

log() { printf '\n=== %s ===\n' "$1"; }

log "Working directory"
mkdir -p "$WORK" && cd "$WORK" || exit 1
echo "  $WORK"
df -h "$WORK" | awk 'NR==2{print "  "$4" free of "$2}'

log "repo init ($BRANCH)"
repo init -u https://github.com/LineageOS/android.git -b "$BRANCH" \
    --depth=1 --no-clone-bundle 2>&1 | tail -5
if [ ! -d "$WORK/.repo" ]; then
    echo "  ERROR: repo init failed" >&2
    exit 1
fi

log "Local manifest for suez"
mkdir -p "$WORK/.repo/local_manifests"
cat > "$WORK/.repo/local_manifests/suez.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <project name="lineage16-suez/device_amazon_suez"
           path="device/amazon/suez"
           remote="github"
           revision="lineage-16.0" />
  <project name="lineage16-suez/kernel_amazon_suez"
           path="kernel/amazon/suez"
           remote="github"
           revision="lineage-16.0" />
</manifest>
XML
echo "  device/amazon/suez  + kernel/amazon/suez added"

log "repo sync (this takes a while)"
echo "  Start: $(date '+%H:%M:%S')"
repo sync -c --no-clone-bundle --no-tags --force-sync -j"$JOBS" 2>&1 \
    | grep -vE '^(Fetching|remote:)' | tail -25
RC=${PIPESTATUS[0]}
echo "  End:   $(date '+%H:%M:%S')   exit=$RC"

log "Result"
if [ -d "$WORK/device/amazon/suez" ]; then
    echo "  device/amazon/suez   present"
    git -C "$WORK/device/amazon/suez" log --oneline -3 2>/dev/null | sed 's/^/    /'
else
    echo "  device/amazon/suez   MISSING"
fi
if [ -d "$WORK/kernel/amazon/suez" ]; then
    echo "  kernel/amazon/suez   present"
else
    echo "  kernel/amazon/suez   MISSING"
fi
du -sh "$WORK" 2>/dev/null | sed 's/^/  Size:  /'
df -h "$WORK" | awk 'NR==2{print "  Disk:  "$4" free"}'
exit "$RC"
