#!/usr/bin/env bash
# Takes a snapshot of the device state.
#
# Purpose: check optimisations against numbers instead of against feel. Every
# call writes a file to findings/measurements/ that can be compared with
# others (scripts/compare-measurements.py).
#
# Read-only - changes nothing on the device.
#
# Usage:   bash scripts/measure-performance.sh <name>
#   e.g.   bash scripts/measure-performance.sh baseline-2022-rom
set -u

# Git Bash converts arguments that start with "/" - see scripts/flash-rom.sh
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
NAME="${1:-measurement}"
OUT="findings/measurements"
mkdir -p "$OUT"
FILE="$OUT/${NAME}-$(date +%Y%m%d-%H%M%S).txt"

r() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

SERIAL="$("$ADB" get-serialno 2>&1 | tr -d '\r')"
if [ "$SERIAL" = "unknown" ] || [ -z "$SERIAL" ]; then
    echo "no device reachable via adb"
    exit 1
fi

{
printf '# Snapshot: %s\n' "$NAME"
printf '# %s\n\n' "$(date '+%Y-%m-%d %H:%M:%S')"

printf '## ROM\n'
for P in ro.lineage.version ro.build.date ro.build.version.release \
         ro.build.version.security_patch ro.product.device; do
    printf '%-36s %s\n' "$P" "$(r getprop $P)"
done

printf '\n## Memory\n'
r 'grep -E "MemTotal|MemFree|MemAvailable|Cached:|SwapTotal|SwapFree|Dirty:" /proc/meminfo'

printf '\n## Swap / ZRAM\n'
r 'cat /proc/swaps'
printf 'zram0 mm_stat: %s\n' "$(r 'cat /sys/block/zram0/mm_stat 2>/dev/null || echo "not active"')"
printf 'zram0 disksize: %s\n' "$(r 'cat /sys/block/zram0/disksize 2>/dev/null || echo "-"')"
printf 'zram0 comp_algorithm: %s\n' "$(r 'cat /sys/block/zram0/comp_algorithm 2>/dev/null || echo "-"')"

printf '\n## VM tunables\n'
for F in swappiness vfs_cache_pressure dirty_ratio dirty_background_ratio \
         min_free_kbytes extra_free_kbytes page-cluster; do
    printf '%-24s %s\n' "$F" "$(r "cat /proc/sys/vm/$F 2>/dev/null")"
done

printf '\n## Low memory killer\n'
printf 'minfree  %s\n' "$(r 'cat /sys/module/lowmemorykiller/parameters/minfree 2>/dev/null')"
printf 'adj      %s\n' "$(r 'cat /sys/module/lowmemorykiller/parameters/adj 2>/dev/null')"

printf '\n## Dalvik / ART\n'
r 'getprop | grep -E "dalvik.vm.heap|dalvik.vm.image|pm.dexopt|ro.config.low_ram"' | sort

printf '\n## CPU\n'
for C in 0 2; do
    printf 'cpu%s governor      %s\n' "$C" "$(r "cat /sys/devices/system/cpu/cpu$C/cpufreq/scaling_governor 2>/dev/null")"
    # scaling_max_freq is not readable on the little cluster
    # ("Permission denied"); cpuinfo_max_freq returns the same value.
    MAXF="$(r "cat /sys/devices/system/cpu/cpu$C/cpufreq/scaling_max_freq 2>/dev/null")"
    case "$MAXF" in ""|*enied*) MAXF="$(r "cat /sys/devices/system/cpu/cpu$C/cpufreq/cpuinfo_max_freq 2>/dev/null") (from cpuinfo)";; esac
    printf 'cpu%s max           %s
' "$C" "$MAXF"
    printf 'cpu%s available     %s\n' "$C" "$(r "cat /sys/devices/system/cpu/cpu$C/cpufreq/scaling_available_frequencies 2>/dev/null")"
done
printf 'online             %s\n' "$(r 'cat /sys/devices/system/cpu/online')"

printf '\n## GPU\n'
r 'cat /proc/gpufreq/gpufreq_opp_dump 2>/dev/null'
r 'cat /proc/gpufreq/gpufreq_var_dump 2>/dev/null | head -4'

printf '\n## Display\n'
r 'wm size; wm density'

printf '\n## Graphics driver\n'
r 'dumpsys SurfaceFlinger' | grep -iE "^(GLES|EGL)" | head -4
printf 'Vulkan: %s\n' "$(r 'pm list features' | grep -ci vulkan)"

printf '\n## File system and I/O\n'
r 'mount' | grep -E " /data | /cache " | sed 's/^/  /'
printf 'scheduler      %s\n' "$(r 'cat /sys/block/mmcblk0/queue/scheduler 2>/dev/null')"
printf 'read_ahead_kb  %s\n' "$(r 'cat /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null')"
printf 'nr_requests    %s\n' "$(r 'cat /sys/block/mmcblk0/queue/nr_requests 2>/dev/null')"
r 'df -h /data /system' | sed 's/^/  /'

printf '\n## WebView\n'
r 'dumpsys webviewupdate' | grep -iE "current webview package" | sed 's/^/  /'

printf '\n## Temperatures\n'
r 'for z in /sys/class/thermal/thermal_zone*/; do t=$(cat $z/temp 2>/dev/null); n=$(cat $z/type 2>/dev/null); [ -n "$t" ] && echo "  $n = $t"; done'

printf '\n## Battery\n'
r 'dumpsys battery' | grep -E "level|temperature|health|status" | sed 's/^/  /'

printf '\n## Start-up time\n'
printf 'uptime             %s\n' "$(r 'cat /proc/uptime')"
r 'getprop | grep -E "boot.*time|ro.boottime.init"' | head -5 | sed 's/^/  /'

printf '\n## Running processes (top 15 by memory)\n'
r 'ps -A -o RSS,NAME --sort=-RSS 2>/dev/null | head -16' | sed 's/^/  /'

printf '\n## Package count\n'
printf 'installed          %s\n' "$(r 'pm list packages' | grep -c package:)"
printf 'of which system    %s\n' "$(r 'pm list packages -s' | grep -c package:)"

printf '\n## App kills by the LMK (since start)\n'
printf 'lowmemorykiller    %s\n' "$(r 'logcat -d -b all' | grep -ci lowmemorykiller)"
printf 'am_kill            %s\n' "$(r 'logcat -d -b events' | grep -ci am_kill)"
} > "$FILE" 2>&1

echo "written: $FILE"
echo
grep -E "^(ro.lineage.version|MemTotal|SwapTotal)" "$FILE" | sed 's/^/  /'
grep -A1 "^## Swap" "$FILE" | tail -1 | sed 's/^/  /'
printf '  %s lines\n' "$(grep -c "" "$FILE")"
