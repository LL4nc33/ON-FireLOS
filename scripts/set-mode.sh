#!/usr/bin/env bash
# Switches a performance mode on the FireHD10 2017 - at runtime, without
# reflashing.
#
# Background: battery life and peak performance cannot both be maximised at
# the same time. Hence modes instead of fixed values. The overclock itself
# comes from this project's kernel patches; without them only the display and
# I/O settings change.
#
# The most effective lever is the resolution, not the clock:
#   1200x1920 -> 800x1280   is 2.25x fewer pixels
#   GPU 445 -> 698 MHz      is +57 %
# And the resolution lowers the waste heat, while the clock raises it.
#
# The ratio of width to density must be kept, otherwise the UI becomes
# unusable:  1200/240 = 5,  800/160 = 5.
#
# Everything here is reversible. "standard" restores the factory state.
#
# Usage:   bash scripts/set-mode.sh <mode>
#   standard | performance | maximum | status
#   (Saving power is left to battery saver: it takes away the second big core.)
set -u

# Git Bash converts arguments that start with "/" - see scripts/flash-rom.sh
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
MODE="${1:-status}"

WIDTH_STANDARD=1200
HEIGHT_STANDARD=1920
DENSITY_STANDARD=240

info() { printf '  %s\n' "$1"; }
ok()   { printf '  [ok]   %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; }

r() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

# Writes a value and checks back what is actually in there.
# Deliberately with a read-back: a silent "echo > /sys/..." says nothing about
# whether the kernel accepted the value.
write_checked() {
    local VALUE="$1" NODE="$2" NAME="$3"
    local OUTPUT ACTUAL
    OUTPUT="$(r "echo $VALUE > $NODE 2>&1")"
    ACTUAL="$(r "cat $NODE 2>/dev/null")"
    case "$ACTUAL" in
        *"$VALUE"*) ok "$NAME = $ACTUAL" ;;
        "")         bad "$NAME: $NODE not readable${OUTPUT:+ ($OUTPUT)}" ;;
        *)          bad "$NAME: is set to '$ACTUAL', expected '$VALUE'${OUTPUT:+ - $OUTPUT}" ;;
    esac
}

SERIAL="$("$ADB" get-serialno 2>&1 | tr -d '\r')"
if [ "$SERIAL" = "unknown" ] || [ -z "$SERIAL" ]; then
    echo "no device reachable via adb"
    exit 1
fi

# Writing to /sys and /proc needs root. On userdebug builds "adb root" is the
# right way; this ROM's su swallows arguments in compound commands.
if [ "$MODE" != "status" ] && [ "$(r 'id -u')" != "0" ]; then
    info "requesting root"
    "$ADB" root >/dev/null 2>&1
    "$ADB" wait-for-device
    if [ "$(r 'id -u')" != "0" ]; then
        bad "no root via adb - clocks and I/O stay unchanged"
        info "In the developer options: Root access -> ADB"
    else
        ok "adbd running as root"
    fi
fi

# "wm size" shows the physical size and, if set, the override.
# It is the override that must be read.
current_display() {
    local OUTPUT
    OUTPUT="$(r wm size)"
    if printf '%s' "$OUTPUT" | grep -q "Override size"; then
        printf '%s' "$OUTPUT" | grep "Override size" | grep -oE '[0-9]+x[0-9]+'
    else
        printf '%s' "$OUTPUT" | grep "Physical size" | grep -oE '[0-9]+x[0-9]+'
    fi
}
current_density() {
    local OUTPUT
    OUTPUT="$(r wm density)"
    if printf '%s' "$OUTPUT" | grep -q "Override density"; then
        printf '%s' "$OUTPUT" | grep "Override density" | grep -oE '[0-9]+'
    else
        printf '%s' "$OUTPUT" | grep "Physical density" | grep -oE '[0-9]+'
    fi
}

show_status() {
    printf '\n=== State ===\n'
    info "ROM:          $(r getprop ro.lineage.version)"
    info "Display:      $(current_display)   density $(current_density)"
    info "Governor:     $(r 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor')"
    info "CPU little:   max $(r 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq') kHz"
    info "CPU big:      max $(r 'cat /sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq') kHz"
    info "GPU:          $(r 'cat /proc/gpufreq/gpufreq_var_dump 2>/dev/null' | grep -oE 'g_cur_gpu_freq = [0-9]+' | head -1)"
    # The kernel limits are module parameters; the module name can change
    # with the kernel, so it is searched for instead of hard-coded.
    info "Limit little: $(r 'cat /sys/module/*/parameters/onfirelos_max_little 2>/dev/null' | head -1) kHz"
    info "Limit big:    $(r 'cat /sys/module/*/parameters/onfirelos_max_big 2>/dev/null' | head -1) kHz"
    info "Limit GPU:    $(r 'cat /sys/module/*/parameters/onfirelos_gpu_max_khz 2>/dev/null' | head -1) kHz"
    info "Scheduler:    $(r 'cat /sys/block/mmcblk0/queue/scheduler 2>/dev/null')"
    info "Readahead:    $(r 'cat /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null') kB"
    info "swappiness:   $(r 'cat /proc/sys/vm/swappiness')"
    info "page-cluster: $(r 'cat /proc/sys/vm/page-cluster')"
    # /proc/swaps has a header line - it does not count
    info "Swap:         $(r 'tail -n +2 /proc/swaps | grep -c .' | tr -cd '0-9') entries"
    info "ZRAM:         $(r 'cat /sys/block/zram0/disksize 2>/dev/null' | tr -cd '0-9') bytes"
    printf '\n'
}

set_display() {
    local W="$1" H="$2" D="$3"
    if [ "$W" = "$WIDTH_STANDARD" ] && [ "$H" = "$HEIGHT_STANDARD" ]; then
        r wm size reset    >/dev/null
        r wm density reset >/dev/null
    else
        r "wm size ${W}x${H}" >/dev/null
        r "wm density $D"     >/dev/null
    fi
    local ACTUAL_SIZE ACTUAL_DENSITY
    ACTUAL_SIZE="$(current_display)"
    ACTUAL_DENSITY="$(current_density)"
    if [ "$ACTUAL_SIZE" = "${W}x${H}" ] && [ "$ACTUAL_DENSITY" = "$D" ]; then
        ok "display $ACTUAL_SIZE, density $ACTUAL_DENSITY"
    else
        bad "display is set to $ACTUAL_SIZE / $ACTUAL_DENSITY, expected ${W}x${H} / $D"
    fi
}

set_governor() {
    local G="$1"
    for C in 0 1 2 3; do
        r "echo $G > /sys/devices/system/cpu/cpu$C/cpufreq/scaling_governor 2>&1" >/dev/null
    done
    local ACTUAL
    ACTUAL="$(r 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor')"
    if [ "$ACTUAL" = "$G" ]; then
        ok "governor $ACTUAL"
    else
        bad "governor is set to '$ACTUAL', expected '$G'"
    fi
}

# Clock limits per mode.
#
# The little cluster stays at the stock clock in ALL modes. That is measured,
# not guessed: with little = 1573000 the big cluster drops to 1043 MHz on
# average under sustained load, instead of holding a constant 1807.
# MediaTek's thermal governor shares out a power budget, and whatever the
# little cluster draws extra is missing from the big one - exactly the cluster
# the felt speed depends on. (Measured with scripts/measure-overclock.sh.)
#
# The kernel parameters take effect when the driver builds a cpufreq policy -
# that is, the next time a cluster is brought online. So that the change
# applies immediately, scaling_max_freq of the cores currently running is set
# as well. Both together hold even when the big cluster is switched off
# completely while idle - measured: without the parameter the limit was back
# at the table value after one hotplug cycle.
#
# The GPU limit takes effect immediately; it is read on every frequency request.
set_clocks() {
    local LITTLE="$1" BIG="$2" GPU="$3"
    local P_LITTLE P_BIG P_GPU D

    P_LITTLE="$(r 'ls /sys/module/*/parameters/onfirelos_max_little 2>/dev/null | head -1')"
    P_BIG="$(r 'ls /sys/module/*/parameters/onfirelos_max_big 2>/dev/null | head -1')"
    P_GPU="$(r 'ls /sys/module/*/parameters/onfirelos_gpu_max_khz 2>/dev/null | head -1')"

    if [ -z "$P_LITTLE" ] || [ -z "$P_BIG" ]; then
        info "kernel without clock control - clocks stay unchanged"
        return
    fi
    write_checked "$LITTLE" "$P_LITTLE" "CPU little max"
    write_checked "$BIG" "$P_BIG" "CPU big max"
    if [ -n "$P_GPU" ]; then
        write_checked "$GPU" "$P_GPU" "GPU max"
    else
        info "kernel without GPU clock control"
    fi

    for C in 0 1 2 3; do
        D="/sys/devices/system/cpu/cpu$C/cpufreq/scaling_max_freq"
        case $C in
            0|1) r "test -w $D && echo $LITTLE > $D" >/dev/null 2>&1 ;;
            *)   r "test -w $D && echo $BIG > $D" >/dev/null 2>&1 ;;
        esac
    done
}

set_io_and_vm() {
    local RA="$1"
    write_checked deadline /sys/block/mmcblk0/queue/scheduler     "Scheduler"
    write_checked "$RA"    /sys/block/mmcblk0/queue/read_ahead_kb "Readahead"
    write_checked 100      /proc/sys/vm/swappiness                "swappiness"
    write_checked 0        /proc/sys/vm/page-cluster              "page-cluster"
}

case "$MODE" in
  status)
    show_status
    exit 0
    ;;

  standard)
    printf '\n=== Mode: standard ===\n'
    info 'Factory state of the display, sensible I/O settings.'
    set_display "$WIDTH_STANDARD" "$HEIGHT_STANDARD" "$DENSITY_STANDARD"
    set_governor interactive
    set_clocks 1404000 1807000 445000
    set_io_and_vm 128
    ;;

  # "leistung" is the mode's earlier name, still accepted.
  performance|leistung)
    printf '\n=== Mode: performance ===\n'
    info '900x1440 at density 180 - 1.78x fewer pixels, less waste heat.'
    set_display 900 1440 180
    set_governor interactive
    set_clocks 1404000 1989000 598000
    set_io_and_vm 256
    ;;

  maximum)
    printf '\n=== Mode: maximum ===\n'
    info '800x1280 at density 160 - 2.25x fewer pixels.'
    info 'More than the whole GPU overclock gains, and without extra heat.'
    set_display 800 1280 160
    set_governor performance
    set_clocks 1404000 1989000 698000
    set_io_and_vm 256
    ;;

  *)
    printf 'unknown mode: %s\n\n' "$MODE"
    printf 'possible: standard | performance | maximum | status\n'
    exit 1
    ;;
esac

show_status
printf '  Note: some apps only read the screen size at start-up.\n'
printf '        After a resolution change a reboot is cleaner.\n'
printf '  Back: bash scripts/set-mode.sh standard\n\n'
