#!/usr/bin/env bash
# Measures what the overclock really gains under sustained load.
#
# The deciding question is not which clock is in the table, but which one is
# still applied after three minutes of load. A clock the device leaves again
# after 90 seconds is no gain.
#
# Measured per pass:
#   - clock of both clusters every second
#   - temperature (mtktscpu, the zone MediaTek throttles on)
#   - a compute-heavy run as a number to compare
#
# Read-only except for the three clock limits; at the end the stock values
# are back in place.
#
# Usage:   bash scripts/measure-overclock.sh [seconds]
set -u
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
DURATION="${1:-180}"
OUT="findings/measurements"
mkdir -p "$OUT"
FILE="$OUT/overclock-$(date +%Y%m%d-%H%M%S).txt"

r() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
info() { printf '         %s\n' "$1"; }

P_LITTLE="$(r 'ls /sys/module/*/parameters/onfirelos_max_little 2>/dev/null | head -1')"
P_BIG="$(r 'ls /sys/module/*/parameters/onfirelos_max_big 2>/dev/null | head -1')"
if [ -z "$P_LITTLE" ] || [ -z "$P_BIG" ]; then
    echo "Kernel without clock control - this ROM cannot overclock."
    exit 1
fi

# Load that really keeps all cores busy. Without nohup the processes die with
# the adb shell - that has falsified a whole series of measurements here once.
load_on()  { r 'nohup sh -c "for i in 1 2 3 4 5 6; do (yes > /dev/null &) ; done" >/dev/null 2>&1 &' >/dev/null 2>&1; }
load_off() { r 'pkill yes' >/dev/null 2>&1; }

# Read back every write.
#
# The first run of this measurement was worthless, because setting the limit
# failed with "Permission denied" and the failure went to /dev/null: the
# parameters belong to system:system, and the adb shell is neither root nor
# system. The result looked like "overclocking gains nothing" - in fact it was
# never switched on.
set_limit() {
    local NODE="$1" VALUE="$2" NAME="$3" ACTUAL
    r "echo $VALUE > $NODE" >/dev/null 2>&1
    ACTUAL="$(r "cat $NODE" | tr -cd '0-9')"
    if [ "$ACTUAL" != "$VALUE" ]; then
        printf '  [FAIL] %s is set to %s, expected %s\n' "$NAME" "${ACTUAL:-empty}" "$VALUE"
        printf '         Missing "adb root"? The parameters belong to system:system.\n'
        printf '         Root access: setprop persist.sys.root_access 2 && adb root\n'
        exit 1
    fi
    printf '  [ok]   %s = %s\n' "$NAME" "$ACTUAL"
}

cool_down() {
    load_off
    # The screen heats too. Without switching it off the device did not get
    # below 55 C in 200 s, and every pass started warmer than the previous one
    # - pass 3 began 23 C above pass 1. That measures the residual heat of the
    # previous pass, not the setting.
    r 'svc power stayon false' >/dev/null 2>&1
    r 'input keyevent KEYCODE_SLEEP' >/dev/null 2>&1
    printf '         cooling down to below %s C' "$1"
    for i in $(seq 1 120); do
        T="$(r 'cat /sys/class/thermal/thermal_zone0/temp' | tr -cd '0-9')"
        [ -n "$T" ] && [ "$((T/1000))" -lt "$1" ] && { printf ' -> %s C\n' "$((T/1000))"; return 0; }
        printf '.'
        sleep 5
    done
    printf ' timed out at %s C - the comparison is NOT clean\n' "$((T/1000))"
}

# Compute load with a comparable number: md5 over 200 MB out of nothing.
# Pure computation, no memory access, runs on one core.
benchmark_run() {
    r 'S=$(date +%s%N); dd if=/dev/zero bs=1M count=200 2>/dev/null | md5sum > /dev/null; E=$(date +%s%N); echo $(( (E-S)/1000000 ))' | tr -cd '0-9'
}

run_pass() {
    NAME="$1"; LITTLE="$2"; BIG="$3"
    printf '\n=== %s (little %s, big %s kHz) ===\n' "$NAME" "$LITTLE" "$BIG"
    set_limit "$P_LITTLE" "$LITTLE" "Limit little"
    set_limit "$P_BIG" "$BIG" "Limit big"
    # The parameters only take effect the next time a policy is built. To get
    # there, briefly send the big cluster idle so that it is brought online again.
    cool_down 55

    # The module parameter only takes effect when the driver rebuilds a policy.
    # Set the running cores as well, otherwise the old limit is measured.
    for C in 0 1 2 3; do
        D="/sys/devices/system/cpu/cpu$C/cpufreq/scaling_max_freq"
        case $C in
            0|1) r "test -w $D && echo $LITTLE > $D" >/dev/null 2>&1 ;;
            *)   r "test -w $D && echo $BIG > $D" >/dev/null 2>&1 ;;
        esac
    done
    info "scaling_max_freq big: $(r 'cat /sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq 2>/dev/null' | tr -cd '0-9')"
    r 'input keyevent KEYCODE_WAKEUP' >/dev/null 2>&1
    load_on
    sleep 5

    SUM_BIG=0; N=0; MAXT=0; MINF=9999999; MAXF=0
    HISTORY=""
    for i in $(seq 1 $((DURATION/10))); do
        FB="$(r 'cat /proc/cpufreq/MT_CPU_DVFS_BIG/cpufreq_freq' | tr -cd '0-9')"
        FL="$(r 'cat /proc/cpufreq/MT_CPU_DVFS_LITTLE/cpufreq_freq' | tr -cd '0-9')"
        T="$(r 'cat /sys/class/thermal/thermal_zone0/temp' | tr -cd '0-9')"
        [ -z "$FB" ] && FB=0
        [ -z "$T" ] && T=0
        SUM_BIG=$((SUM_BIG + FB)); N=$((N+1))
        [ "$FB" -gt "$MAXF" ] && MAXF=$FB
        [ "$FB" -lt "$MINF" ] && MINF=$FB
        [ "$T" -gt "$MAXT" ] && MAXT=$T
        HISTORY="$HISTORY$(printf '  %4ds  big %7s  little %7s  %3s C\n' "$((i*10))" "$FB" "${FL:-?}" "$((T/1000))")"
        sleep 10
    done

    info "Benchmark run under load..."
    MS="$(benchmark_run)"
    load_off

    {
    printf '\n## %s\n' "$NAME"
    printf 'Limit little        %s kHz\n' "$LITTLE"
    printf 'Limit big           %s kHz\n' "$BIG"
    printf 'Big clock mean      %s kHz\n' "$((SUM_BIG / N))"
    printf 'Big clock minimum   %s kHz\n' "$MINF"
    printf 'Big clock maximum   %s kHz\n' "$MAXF"
    printf 'Temperature maximum %s C\n' "$((MAXT/1000))"
    printf 'Compute run         %s ms (lower is better)\n' "${MS:-?}"
    printf '\n### History\n%s\n' "$HISTORY"
    } >> "$FILE"

    printf '  mean %s kHz   peak %s kHz   low %s kHz   max %s C   compute run %s ms\n' \
        "$((SUM_BIG / N))" "$MAXF" "$MINF" "$((MAXT/1000))" "${MS:-?}"
}

{
printf '# Overclocking under sustained load\n'
printf '# %s\n' "$(date '+%Y-%m-%d %H:%M:%S')"
printf '# ROM: %s\n' "$(r getprop ro.lineage.version)"
printf '# Load duration per pass: %s s\n' "$DURATION"
} > "$FILE"

r 'svc power stayon usb' >/dev/null 2>&1
# Interleaved order.
#
# Three passes in a row were not comparable: despite a cool-down phase each
# started warmer than the previous one, pass 3 even 23 C above pass 1. Hence
# A-B-A-B now. If the device drifts anyway, it shows in the two A runs being
# apart - and then the result simply is not reliable, instead of only looking
# that way.
#
# The little cluster stays at the stock clock everywhere. Raising it took the
# thermal budget away from the big one in the preliminary measurement: mean
# 1043 instead of 1807 MHz.
run_pass "Stock clock A"  1404000 1807000
run_pass "Big only A"     1404000 1989000
run_pass "Stock clock B"  1404000 1807000
run_pass "Big only B"     1404000 1989000

# Always back to the stock values - a measurement must leave nothing behind
set_limit "$P_LITTLE" 1404000 "Limit little reset"
set_limit "$P_BIG" 1807000 "Limit big reset"
r 'svc power stayon false' >/dev/null 2>&1
cool_down 50

printf '\n  written: %s\n\n' "$FILE"
grep -E "^(Big clock|Temperature|Compute run|Limit big)" "$FILE" | sed 's/^/  /'
