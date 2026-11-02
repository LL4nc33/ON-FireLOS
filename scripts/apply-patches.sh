#!/usr/bin/env bash
# Applies the project patches to the source tree.
#
# The patches do not belong to suez; they fix defects in LineageOS projects
# themselves. They are idempotent: a patch that is already applied is
# skipped, not reported as an error.
set -u
# A patcher that fails must fail the run, not just print through sed.
set -o pipefail

ROOT="${1:-/root/los16}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

log() { printf '  %s\n' "$1"; }

RC=0
for DIR in "$HERE"/patches/*/; do
    NAME="$(basename "$DIR")"
    TARGET="$ROOT/${NAME//_//}"          # frameworks_av -> frameworks/av
    printf '\n=== %s -> %s ===\n' "$NAME" "$TARGET"
    if [ ! -d "$TARGET" ]; then
        if ls "$DIR"*.patch >/dev/null 2>&1; then
            log "MISSING - skipped"
            RC=1
        else
            # Only blob patchers: the project appears with extract-files.sh.
            log "not extracted yet - run again after extract-files.sh"
        fi
        continue
    fi
    # Build files that go into the project as they are, such as the Soong
    # declaration of a vendor blob (patches/vendor_amazon_suez/Android.bp).
    if [ -f "$DIR"Android.bp ]; then
        cp "$DIR"Android.bp "$TARGET/Android.bp" && log "Android.bp: copied" \
            || { log "Android.bp: ERROR"; RC=1; }
    fi
    for P in "$DIR"*.py; do
        [ -f "$P" ] || continue
        B="$(basename "$P")"
        # The blob patchers are idempotent themselves and report their state.
        python3 "$P" "$TARGET" 2>&1 | sed "s/^/  /" || { log "$B: ERROR"; RC=1; }
    done
    for P in "$DIR"*.patch; do
        [ -f "$P" ] || continue
        B="$(basename "$P")"
        if git -C "$TARGET" apply --reverse --check "$P" 2>/dev/null; then
            log "$B: already applied"
        elif git -C "$TARGET" apply --check "$P" 2>/dev/null; then
            git -C "$TARGET" apply "$P" && log "$B: applied" || { log "$B: ERROR"; RC=1; }
        else
            log "$B: does NOT apply to this tree"
            git -C "$TARGET" apply --check "$P" 2>&1 | sed 's/^/      /'
            RC=1
        fi
    done
done
printf '\n'
exit $RC
