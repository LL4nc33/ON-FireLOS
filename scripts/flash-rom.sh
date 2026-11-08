#!/usr/bin/env bash
# Flashes an ON-FireLOS ZIP onto the Fire HD 10 2017 (suez) through TWRP.
#
# Sequence: check, into recovery, install, boot, report the result.
#
# By default a "dirty" flash - /data stays. That keeps the Play Store and
# set-up apps. The ROM's own backuptool also saves the GApps via
# /system/addon.d/ and restores them.
#
# With --wipe-data, /data is wiped. That was necessary on the first flash in
# October, because Fire OS's packages.xml sent system_server into a crash loop
# (PackageSignatures.readXml, null pointer). Between two LineageOS 16 builds it
# is normally not needed - but if the device does not boot all the way after
# the flash, it is the first thing to try.
#
# With --f2fs, /data is switched to f2fs - that also wipes /data completely,
# without asking again. The options may follow the ZIP in any order.
#
# TWRP 3.2.3 knows "wipe data", NOT "format data".
set -u

# Git Bash converts arguments that start with "/":
#   adb push file /data/rom.zip
# becomes
#   adb push file C:/Program Files/Git/data/rom.zip
# The message "1 file pushed" still appears, the error only comes on the next
# line. This has silently swallowed a transfer here once already.
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
ZIP="${1:-}"
# Options in any order after the zip.
WIPE=0
F2FS=0
for A in "$@"; do
    [ "$A" = "--wipe-data" ] && WIPE=1
    [ "$A" = "--f2fs" ] && F2FS=1
done
MIN_BATTERY=30

log()  { printf '\n=== %s ===\n' "$1"; }
ok()   { printf '  [ok]   %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; }
info() { printf '         %s\n' "$1"; }
r()    { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

case "$ZIP" in
    ""|--*) echo "Usage: $0 <rom.zip> [--wipe-data] [--f2fs]"; exit 1 ;;
esac

# ------------------------------------------------------------- 1. Check the ZIP
log "Check the ZIP"
if [ ! -f "$ZIP" ]; then bad "$ZIP does not exist"; exit 1; fi
SIZE=$(stat -c%s "$ZIP")
info "$ZIP"
info "$(printf "%'d" "$SIZE") bytes"
if [ "$SIZE" -lt 300000000 ]; then
    bad "under 300 MB - a suez ROM is about 500 MB. Aborting."
    exit 1
fi
python - "$ZIP" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
broken = z.testzip()
if broken:
    print(f"  [FAIL] damaged entry: {broken}"); sys.exit(1)
names = z.namelist()
print(f"         {len(names)} entries, table of contents OK")
for required in ("META-INF/com/google/android/update-binary",
                 "META-INF/com/google/android/updater-script"):
    print(f"         {'present' if required in names else 'MISSING'}: {required}")
# Read the build properties from the image, if available
for n in names:
    if n.endswith("build.prop") or n == "system/build.prop":
        txt = z.read(n).decode("utf-8", "replace")
        for line in txt.splitlines():
            if line.startswith(("ro.lineage.version", "ro.build.date=",
                                "ro.build.version.release", "ro.product.device")):
                print(f"         {line}")
        break
PY
[ $? -ne 0 ] && { bad "ZIP check failed"; exit 1; }
ok "ZIP usable"

# ------------------------------------------------------------- 2. Device
log "Check the device"
SERIAL="$("$ADB" get-serialno 2>&1 | tr -d '\r')"
if [ "$SERIAL" = "unknown" ] || [ -z "$SERIAL" ]; then
    bad "no device via adb"; exit 1
fi
ok "Serial $SERIAL"
DEVICE="$(r getprop ro.product.device)"
info "Device:  $DEVICE"
info "ROM:     $(r getprop ro.lineage.version)"
if [ "$DEVICE" != "suez" ]; then
    bad "device reports '$DEVICE', expected 'suez'. Aborting."
    exit 1
fi
BATTERY="$(r 'dumpsys battery' | grep -E '^ *level:' | grep -oE '[0-9]+')"
BATTERY="${BATTERY:-0}"
info "Battery: $BATTERY %"
if [ "$BATTERY" -lt "$MIN_BATTERY" ]; then
    bad "under $MIN_BATTERY % - an abort in the middle of the flash makes the device unusable"
    exit 1
fi
ok "battery sufficient"

# ---------------------------------------------- 3. Back up the device details
log "Back up the state"
mkdir -p findings/pre-flash
{
    echo "# State before the flash - $(date '+%Y-%m-%d %H:%M:%S')"
    echo "## getprop"; r getprop
    echo "## mount";   r mount
    echo "## packages"; r 'pm list packages'
} > "findings/pre-flash/state-$(date +%Y%m%d-%H%M%S).txt" 2>&1
ok "properties, mounts and package list saved"
info "(a /data image would be several GB - deliberately not saved)"

# ------------------------------------------------------------- 4. Recovery
log "Into recovery"
"$ADB" reboot recovery 2>&1 | tr -d '\r' | sed 's/^/         /'
info "waiting for sideload readiness (up to 180 s)"
FOUND=0
for i in $(seq 1 60); do
    STATE="$("$ADB" get-state 2>&1 | tr -d '\r')"
    if [ "$STATE" = "recovery" ] || [ "$STATE" = "sideload" ]; then
        FOUND=1; break
    fi
    sleep 3
done
if [ "$FOUND" -eq 0 ]; then
    bad "recovery not reachable (state: ${STATE:-unknown})"
    info "Check on the device: TWRP 3.2.3 should come up via adb reboot recovery."
    info "If need be by hand: Volume Up + Power while switching on."
    exit 1
fi
ok "recovery reached ($STATE)"
# TWRP restarts its adbd shortly after starting up. A command at that moment
# aborts with "Broken pipe" and the device is briefly gone.
# In pure sideload mode wait-for-recovery never returns.
sleep 15
[ "$STATE" = "recovery" ] && "$ADB" wait-for-recovery 2>/dev/null

# --------------------------------------------------------- 5. Wipe /data
if [ "$WIPE" -eq 1 ]; then
    log "Wipe /data"
    info "TWRP 3.2.3 only knows 'wipe data', not 'format data'"
    "$ADB" shell twrp wipe data 2>&1 | tr -d '\r' | sed 's/^/         /'
    sleep 5
    ok "/data wiped"
    info "After the wipe adb often asks for a new authorisation."
fi

# --------------------------------------------------------- 6. Install
# Two ways. Preferred: "push to /tmp" + "twrp install":
#   - twrp install returns a real exit code and a log
#   - adb sideload often reports exit!=0 even on success
# To /tmp, not to /data: TWRP 3.2.3 cannot mount an f2fs /data (its kernel
# does not know f2fs), and a file under /data then lands in the root file
# system in RAM. /tmp is a tmpfs with a good 900 MB.
# Sideload remains the fallback if space is short or the push fails.
log "Install the ROM"
TARGET=/tmp/on-firelos.zip
START=$(date +%s)
METHOD=""

FREE="$(r "df -k /tmp | tail -1" | awk '{print $4}' | tr -cd '0-9')"
if [ $(( ${FREE:-0} * 1024 )) -lt $(( SIZE + 52428800 )) ]; then
    info "/tmp only has $(( ${FREE:-0} / 1024 )) MiB free - no push"
else
    info "transferring the ZIP to $TARGET ($(( SIZE / 1048576 )) MiB)"
    "$ADB" push "$ZIP" "$TARGET" 2>&1 | tr -d '\r' | tail -2 | sed 's/^/         /'
    # Cross-check the size on the device - otherwise an aborted push goes unnoticed.
    # TWRP's busybox does not know "stat -c%s" and returns nothing; "wc -c" works everywhere.
    REMOTE_SIZE="$(r "wc -c < $TARGET 2>/dev/null" | tr -cd '0-9')"
    if [ "${REMOTE_SIZE:-0}" = "$SIZE" ]; then
        ok "transferred, size matches ($REMOTE_SIZE bytes)"
        METHOD=install
    else
        bad "size differs: ${REMOTE_SIZE:-0} instead of $SIZE"
    fi
fi

if [ "$METHOD" = "install" ]; then
    info "twrp install $TARGET"
    "$ADB" shell "twrp install $TARGET" 2>&1 | tr -d '\r' | tail -15 | sed 's/^/         /'
    RC=${PIPESTATUS[0]}
    info "TWRP log:"
    "$ADB" shell "tail -25 /tmp/recovery.log" 2>&1 | tr -d '\r' | sed 's/^/           /'
    "$ADB" shell "rm -f $TARGET" >/dev/null 2>&1
else
    info "push not usable - falling back to sideload"
    # A leftover in the RAM-backed /tmp can take the space sideload needs.
    "$ADB" shell "rm -f $TARGET" >/dev/null 2>&1
    "$ADB" shell twrp sideload >/dev/null 2>&1 &
    sleep 4
    "$ADB" sideload "$ZIP" 2>&1 | tr -d '\r' | tail -20 | sed 's/^/         /'
    RC=${PIPESTATUS[0]}
    METHOD=sideload
fi

DURATION=$(( $(date +%s) - START ))
info "Method: $METHOD, duration: ${DURATION} s, exit=$RC"
info "The exit code alone proves nothing - the boot decides."

# ------------------------------------------- 6b. Prepare /data for f2fs
#
# fs_mgr creates /data itself with make_f2fs on the first start - but only if
# the fstab line carries "formattable" AND the partition counts as empty.
# partition_wiped() in system/core/libcutils/partition_utils.cpp reads exactly
# the first 4096 bytes for that and checks for all zeros. An /data "wiped" in
# TWRP is not enough: afterwards a fresh ext4 superblock sits there, and that
# is not zero.
#
# Two traps, both sprung on the first attempt:
#
# 1. TWRP does not mount the userdata partition under /data, but under
#    /sdcard. An "umount /data" therefore goes nowhere, the file system stays
#    mounted - and on write-back it writes its cached superblock over the
#    freshly written zeros again. dd still reports success.
#
# 2. TWRP's busybox od knows neither -A nor -t. The call prints the help,
#    and that contains non-zero characters - so the check also failed when
#    everything was right. The check is now done by md5sum against 4096 real
#    zero bytes.
if [ "$F2FS" -eq 1 ]; then
    log "Prepare /data for f2fs"
    USERDATA=/dev/block/platform/mtk-msdc.0/by-name/userdata

    for M in /sdcard /data /external_sd; do
        r "umount $M" >/dev/null 2>&1
    done
    # The block device behind the name, not a hard-coded partition number.
    BLK="$(r "readlink -f $USERDATA")"
    STILL_MOUNTED="$(r "mount | grep -c '^$BLK '" | tr -cd '0-9')"
    if [ -z "$BLK" ] || [ "${STILL_MOUNTED:-1}" != "0" ]; then
        bad "userdata is still mounted - the zeros would be overwritten again"
        r "mount | grep '^$BLK '" | sed 's/^/         /'
        exit 1
    fi
    ok "userdata unmounted"

    info "zeroing the first 1 MB"
    r "dd if=/dev/zero of=$USERDATA bs=4096 count=256; sync" | sed 's/^/         /'

    ACTUAL="$(r "dd if=$USERDATA bs=4096 count=1 2>/dev/null | md5sum" | awk '{print $1}')"
    EXPECTED="$(r "dd if=/dev/zero bs=4096 count=1 2>/dev/null | md5sum" | awk '{print $1}')"
    if [ -n "$ACTUAL" ] && [ "$ACTUAL" = "$EXPECTED" ]; then
        ok "first 4096 bytes are zero - fs_mgr will create f2fs"
    else
        bad "partition is not zeroed - fs_mgr would NOT format"
        info "read: $ACTUAL   expected: $EXPECTED"
        exit 1
    fi
fi

# ------------------------------------------------------------- 7. Boot
#
# "adb reboot" is not enough: if TWRP hangs in the state "Running Recovery
# Commands" - and it does after "twrp install" - it silently swallows every
# reboot request. Proven by the kernel uptime: it kept running unchanged
# after several attempts.
#
# So it is measured, and if need be rebooted past the recovery via sysrq.
# That is safe here, because at this point nothing is mounted writable any
# more.
log "Reboot and boot"
BEFORE="$(r 'cat /proc/uptime' | awk '{printf "%d", $1}')"
"$ADB" reboot 2>&1 | tr -d '\r' | sed 's/^/         /'
sleep 8
AFTER="$(r 'cat /proc/uptime' 2>/dev/null | awk '{printf "%d", $1}')"
if [ -n "$AFTER" ] && [ -n "$BEFORE" ] && [ "$AFTER" -ge "$BEFORE" ] 2>/dev/null; then
    info "recovery swallowed the reboot (uptime ${BEFORE}s -> ${AFTER}s)"
    info "forcing it via sysrq"
    r 'sync; echo 1 > /proc/sys/kernel/sysrq' >/dev/null 2>&1
    r 'echo b > /proc/sysrq-trigger' >/dev/null 2>&1
    sleep 10
fi

# After a fresh /data USB debugging is off - Android resets it during initial
# setup. So the flash can have fully succeeded without adb ever answering
# again. That is not an error and must not be reported as one.
if [ "$F2FS" -eq 1 ] || [ "$WIPE" -eq 1 ]; then
    log "Note on the first start"
    info "/data was created anew. Android starts with the setup wizard,"
    info "and USB debugging is switched off during it."
    info "adb therefore stays silent until it is switched on again by hand:"
    info "  Settings -> About tablet -> tap Build number 7 times"
    info "  Settings -> System -> Advanced -> Developer options"
    info "Whether the device has booted, Windows shows as a portable device:"
    info "  Get-PnpDevice -PresentOnly | Where-Object FriendlyName -like '*Fire*'"
    info "If it shows up there and adb does not, Android is running - in TWRP"
    info "adb would always be reachable."
    exit 0
fi

info "waiting for the system (up to 5 min)"
BOOTED=0
for i in $(seq 1 100); do
    DONE="$(r getprop sys.boot_completed 2>/dev/null | tr -cd '01')"
    if [ "$DONE" = "1" ]; then BOOTED=1; break; fi
    sleep 3
done
if [ "$BOOTED" -eq 0 ]; then
    bad "system did not come up"
    info "Next step: flash again with --wipe-data."
    info "Way back: sideload downloads/lineage-16.0-20220705-UNOFFICIAL-suez.zip."
    exit 1
fi
ok "system booted after ~$((i*3)) s"

# TWRP creates /cache/recovery without an SELinux label; system_server may then
# not write there (one denial after every flash). A vendor init.rc may not
# relabel /cache, so it is done here, as root.
"$ADB" root >/dev/null 2>&1; sleep 3; "$ADB" wait-for-device
r "restorecon -RF /cache" >/dev/null 2>&1
info "/cache relabelled: $(r 'ls -Zd /cache/recovery' | awk '{print $1}')"

log "Result"
info "ROM:     $(r getprop ro.lineage.version)"
info "Built:   $(r getprop ro.build.date)"
info "Android: $(r getprop ro.build.version.release)"
info "Cameras: $(r 'dumpsys media.camera' | grep -m1 'Number of camera devices')"
printf '\n  Next step:  bash scripts/verify-camera.sh\n\n'
