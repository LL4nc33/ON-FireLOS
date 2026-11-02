#!/usr/bin/env bash
# Reproducible usage round for measuring memory pressure.
#
# Static readings (free memory shortly after boot) say little about whether a
# device kills apps in everyday use. This script creates the pressure on
# purpose: it starts apps one after another, leaves them open, switches
# between them - and then counts how many processes the system has killed.
#
# Read-only apart from starting apps. Nothing is installed or permanently
# changed.
#
# Usage:   bash scripts/memory-stress.sh <name> [rounds]
set -u

# Git Bash converts arguments that start with "/" - see scripts/flash-rom.sh
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
NAME="${1:-run}"
ROUNDS="${2:-2}"
OUT="findings/measurements"
mkdir -p "$OUT"
FILE="$OUT/stress-${NAME}-$(date +%Y%m%d-%H%M%S).txt"

r() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
info() { printf '  %s\n' "$1"; }

SERIAL="$("$ADB" get-serialno 2>&1 | tr -d '\r')"
[ "$SERIAL" = "unknown" ] || [ -z "$SERIAL" ] && { echo "no device"; exit 1; }

# Find the launchable apps, so that the round works on any device
APPS="$(r 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER' \
        | grep -oE '[a-z][a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+/\.?[A-Za-z][A-Za-z0-9_.]*' | sort -u)"
COUNT="$(printf '%s\n' "$APPS" | grep -c .)"

printf '\n=== Usage round: %s ===\n' "$NAME"
info "launchable apps found: $COUNT"
if [ "$COUNT" -lt 3 ]; then
    echo "  too few apps for a meaningful round"
    exit 1
fi

# Initial state
r 'svc power stayon usb' >/dev/null
r 'input keyevent KEYCODE_WAKEUP' >/dev/null
"$ADB" logcat -c 2>&1 | tr -d '\r'
START_FREE="$(r 'grep MemAvailable /proc/meminfo' | grep -oE '[0-9]+')"
info "MemAvailable at the start: $START_FREE kB"

printf '\n  Rounds: %s\n' "$ROUNDS"
STARTED=0
ROUND=1
while [ "$ROUND" -le "$ROUNDS" ]; do
    printf '  --- Round %s ---\n' "$ROUND"
    for ACT in $APPS; do
        r "am start -n $ACT" >/dev/null 2>&1
        STARTED=$((STARTED + 1))
        sleep 3
    done
    ROUND=$((ROUND + 1))
done


# Browser tabs: the most realistic memory load on a tablet. Fourteen light
# system apps create hardly any pressure on 1.8 GiB - only several loaded
# web pages bring the device to its limit.
# URLs without brackets and special characters, otherwise the shell breaks on
# them. The pages stay the same so that runs remain comparable.
PAGES="https://de.wikipedia.org/wiki/Hauptseite
https://de.wikipedia.org/wiki/MediaTek
https://de.wikipedia.org/wiki/Bildsensor
https://de.wikipedia.org/wiki/Arbeitsspeicher
https://de.wikipedia.org/wiki/Datenkompression
https://de.wikipedia.org/wiki/Tablet-Computer"
# Do not search for "browser" in the activity name - that also matches
# com.android.vending/.AssetBrowserActivity. The system itself knows who
# opens http links.
BROWSER="$(r 'cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://example.com' | grep -E '^[a-z][a-zA-Z0-9_.]+/' | tail -1)"
if [ -n "$BROWSER" ]; then
    printf '  --- Browser tabs (%s) ---\n' "${BROWSER%%/*}"
    printf '%s\n' "$PAGES" | while read -r U; do
        [ -z "$U" ] && continue
        r "am start -a android.intent.action.VIEW -d $U" >/dev/null 2>&1
        sleep 6
    done
else
    info "no browser found - tab phase skipped"
fi

sleep 10

# Evaluation
#
# The most meaningful number is not how often the system ended something -
# am_kill also counts normal clean-up of cached processes. What matters is how
# many of the started apps are still alive at the end. That is exactly what
# the user notices.
END_FREE="$(r 'grep MemAvailable /proc/meminfo' | grep -oE '[0-9]+')"
SWAP_FREE="$(r 'grep SwapFree /proc/meminfo' | grep -oE '[0-9]+')"
SWAP_TOTAL="$(r 'grep SwapTotal /proc/meminfo' | grep -oE '[0-9]+')"

ALIVE=0
DEAD=0
DEAD_LIST=""
PROCESSES="$(r 'ps -A -o NAME' )"
for ACT in $APPS; do
    PKG="${ACT%%/*}"
    if printf '%s
' "$PROCESSES" | grep -qxF "$PKG"; then
        ALIVE=$((ALIVE + 1))
    else
        DEAD=$((DEAD + 1))
        DEAD_LIST="$DEAD_LIST $PKG"
    fi
done

KILL_RAW="$("$ADB" logcat -d -b events 2>/dev/null | grep am_kill)"
AM_KILL="$(printf '%s
' "$KILL_RAW" | grep -c .)"
# The last field of the line is the reason, e.g. "empty #17" or "low on memory"
REASONS="$(printf '%s
' "$KILL_RAW" | sed -E 's/.*,([a-z][^]]*)\]$/\1/' | sed -E 's/#[0-9]+//' | sort | uniq -c | sort -rn | head -6)"
LMK="$("$ADB" logcat -d -b all 2>/dev/null | grep -ci lowmemorykiller)"
ANR="$("$ADB" logcat -d -b events 2>/dev/null | grep -c am_anr)"

{
printf '# Usage round: %s\n' "$NAME"
printf '# %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
printf '# ROM: %s\n\n' "$(r getprop ro.lineage.version)"
printf 'Apps found             %s\n' "$COUNT"
printf 'App starts in total    %s\n' "$STARTED"
printf 'still alive at the end %s of %s\n' "$ALIVE" "$COUNT"
printf 'ended                  %s\n' "$DEAD"
printf 'MemAvailable start     %s kB\n' "$START_FREE"
printf 'MemAvailable end       %s kB\n' "$END_FREE"
printf 'SwapTotal              %s kB\n' "${SWAP_TOTAL:-0}"
printf 'Swap used              %s kB\n' "$(( ${SWAP_TOTAL:-0} - ${SWAP_FREE:-0} ))"
printf 'am_kill in total       %s\n' "$AM_KILL"
printf 'lowmemorykiller        %s\n' "$LMK"
printf 'am_anr                 %s\n' "$ANR"
printf '\n## Ended apps\n%s\n' "${DEAD_LIST:- none}"
printf '\n## Reasons for ending\n%s\n' "$REASONS"
printf '\n## ZRAM compression\n'
r 'cat /sys/block/zram0/mm_stat 2>/dev/null' | awk '{
  if (NF >= 3 && $1 > 65536)
    printf "uncompressed  %d bytes\ncompressed    %d bytes\nused          %d bytes\nratio         %.2f:1\n", $1, $2, $3, $1/$2
  else print "no ZRAM active or practically unused"
}'
printf '\n## Largest processes\n'
r 'ps -A -o RSS,NAME --sort=-RSS 2>/dev/null | head -16'
} > "$FILE" 2>&1

r 'svc power stayon false' >/dev/null

printf '\n  written: %s\n\n' "$FILE"
cat "$FILE" | grep -vE '^#' | head -22 | sed 's/^/  /'
