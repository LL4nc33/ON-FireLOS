#!/usr/bin/env bash
# Acceptance test for the camera on the FireHD10 2017 (suez).
#
# Deliberately multi-stage: "Number of camera devices: 2" had been reached once
# before without a photo being possible. The test only counts as passed when a
# valid JPEG file with plausible dimensions and real image content is on the
# device.
#
# The bundled camera app takes precedence: only it proves that the ROM works
# without installing anything extra. Open Camera is the fallback.
#
# Errors are NOT suppressed. Every stage reports its result separately, so it
# stays visible how far things got.
set -u

# Git Bash converts arguments that start with "/":
#   adb push file /data/rom.zip
# becomes
#   adb push file C:/Program Files/Git/data/rom.zip
# The message "1 file pushed" still appears, the error only comes on the next
# line. This has silently swallowed a transfer here once already.
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
OUT="${OUT:-findings/camera-verify}"

# Order: bundled first, stopgap last
CANDIDATES="com.android.camera2 net.sourceforge.opencamera"

mkdir -p "$OUT"
STAGE=0; PASS=0; FAIL=0

stage() { STAGE=$((STAGE+1)); printf '\n=== Stage %d: %s ===\n' "$STAGE" "$1"; }
ok()    { printf '  [ok]   %s\n' "$1"; PASS=$((PASS+1)); }
bad()   { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL+1)); }
info()  { printf '         %s\n' "$1"; }
r()     { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

# --- Read the UI instead of guessing coordinates -----------------------------
# com.android.camera2 hides its buttons again after a few seconds, and fixed
# coordinates shift with every resolution. So before every tap the actual UI
# tree is read.
ui_tree() {
    "$ADB" shell "uiautomator dump /sdcard/ui-test.xml" >/dev/null 2>&1
    "$ADB" shell "cat /sdcard/ui-test.xml" 2>/dev/null | tr -d '\r' | sed 's/></>\n</g'
}

# Taps the centre of the button with resource id $1; returns 1 = not found
tap_button() {
    BUTTON="$1"
    BOUNDS="$(ui_tree | grep "resource-id=\"[^\"]*$BUTTON\"" \
        | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | head -1)"
    [ -z "$BOUNDS" ] && return 1
    set -- $BOUNDS
    "$ADB" shell "input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))" >/dev/null 2>&1
    return 0
}

# A tap on a paused app lands in the launcher - this is exactly what made
# earlier capture attempts fail without it showing up in the log.
in_foreground() { r "dumpsys activity activities" | grep -q "mResumedActivity.*$1"; }

# ------------------------------------------------------------ 1. Device
stage "Device reachable"
SERIAL="$("$ADB" get-serialno 2>&1 | tr -d '\r')"
if [ "$SERIAL" = "unknown" ] || [ -z "$SERIAL" ]; then
    bad "no device reachable via adb"; exit 1
fi
ok "Serial $SERIAL"
info "ROM:     $(r getprop ro.lineage.version)"
info "Android: $(r getprop ro.build.version.release)"
info "Built:   $(r getprop ro.build.date)"

# ------------------------------------------------- 2. Camera service and HAL
stage "Camera service"
r dumpsys media.camera > "$OUT/dumpsys-media-camera.txt"
N="$(grep -oE 'Number of camera devices: *[0-9]+' "$OUT/dumpsys-media-camera.txt" \
     | grep -oE '[0-9]+$' | head -1)"
N="${N:-0}"
if [ "$N" -ge 2 ]; then
    ok "Number of camera devices: $N"
elif [ "$N" -ge 1 ]; then
    bad "only $N camera - expected 2 (rear GC2355, front GC0312)"
else
    bad "no camera reported"
fi

# Which provider and which API level? Decides which app is suitable.
#
# suez only has a HAL1 camera. Android therefore offers API2 at the LEGACY
# level (emulated on top of API1). The bundled Camera2 app copes with that,
# but only uses the basic feature set. Open Camera can talk to API1 directly
# and is therefore the more robust fallback.
stage "Provider and API level"
grep -iE "Camera Provider HAL|hardware level|Device [0-9]+ information|facing|orientation"     "$OUT/dumpsys-media-camera.txt" | head -10 | sed 's/^/         /'
if grep -q "legacy/0" "$OUT/dumpsys-media-camera.txt"; then
    ok "provider legacy/0 present (that is the right one for suez)"
elif grep -q "external/0" "$OUT/dumpsys-media-camera.txt"; then
    bad "only external/0 - the legacy provider is missing, camera HAL not registered"
else
    bad "no camera provider in dumpsys"
fi

stage "HIDL provider"
PROV="$(r 'ps -A' | grep 'camera' | grep -v grep)"
if [ -n "$PROV" ]; then
    ok "provider process running"
    printf '%s\n' "$PROV" | sed 's/^/         /'
else
    bad "no camera provider process found"
fi

# --------------------------------------------------------- 3. Choose the app
stage "Choose camera app"
PKG=""
for K in $CANDIDATES; do
    if r "pm list packages $K" | grep -q "package:$K"; then
        PKG="$K"
        case "$K" in
            net.sourceforge.opencamera) ORIGIN="stopgap (installed afterwards)" ;;
            *)                          ORIGIN="bundled" ;;
        esac
        ok "$PKG  ($ORIGIN)"
        break
    else
        info "$K not present"
    fi
done
if [ -z "$PKG" ]; then
    bad "no camera app found"; exit 1
fi

# Storage location per app
case "$PKG" in
    net.sourceforge.opencamera) CAM_DIR=/sdcard/DCIM/OpenCamera ;;
    *)                          CAM_DIR=/sdcard/DCIM/Camera ;;
esac
# Determine the launch activity instead of guessing.
# "cmd package resolve-activity --brief <pkg>" only returns "No activity found"
# for com.android.camera2 - the package name alone is not enough for it. The
# reliable way goes through pm dump and the MAIN filters.
ACT="$(r "cmd package resolve-activity --brief $PKG" | grep -E "^$PKG/" | tail -1)"
if [ -z "$ACT" ]; then
    # A letter must follow the slash - otherwise the pattern catches lines
    # like "com.android.camera2/10030" (the UID) from pm dump.
    # Activities with "Activity" in their name take precedence.
    CANDIDATES="$(r "pm dump $PKG" | grep -oE "$PKG/[A-Za-z][A-Za-z0-9_.]*" | sort -u)"
    ACT="$(printf '%s
' "$CANDIDATES" | grep -i "activity" | head -1)"
    [ -z "$ACT" ] && ACT="$(printf '%s
' "$CANDIDATES" | head -1)"
fi
if [ -z "$ACT" ]; then bad "no launch activity found"; exit 1; fi
info "Activity: $ACT"
info "Storage:  $CAM_DIR"

for P in CAMERA RECORD_AUDIO WRITE_EXTERNAL_STORAGE READ_EXTERNAL_STORAGE; do
    OUTPUT="$(r "pm grant $PKG android.permission.$P")"
    [ -n "$OUTPUT" ] && info "Permission $P: $OUTPUT"
done

# -------------------------------------------------------- 4. Preview
stage "Start preview"
r "am force-stop $PKG" >/dev/null
# Otherwise the device falls asleep in the middle of the test and every tap is lost
r "svc power stayon usb" >/dev/null
r "input keyevent KEYCODE_WAKEUP" >/dev/null
sleep 1
r "input keyevent 82" >/dev/null
"$ADB" logcat -c 2>&1 | tr -d '\r'
"$ADB" logcat -b all > "$OUT/logcat-live.txt" 2>&1 &
LOGPID=$!
sleep 1

BEFORE="$(r "ls $CAM_DIR 2>/dev/null | wc -l" | tr -cd '0-9')"
BEFORE="${BEFORE:-0}"
info "Files before: $BEFORE"

# Explicitly in photo mode: otherwise the app remembers the last mode, and
# after a video recording the shutter starts a video.
r "am start -a android.media.action.STILL_IMAGE_CAMERA -n $ACT" | sed 's/^/         /'
sleep 10

if grep -qE "SIGSEGV|Fatal signal|signal 11|signal 6" "$OUT/logcat-live.txt"; then
    bad "crash on start"
    grep -E "SIGSEGV|Fatal signal|signal (6|11)|pid: |#0[0-9]" "$OUT/logcat-live.txt" \
        | head -20 | sed 's/^/         /'
else
    ok "no crash on start"
fi
# "ernsthafter kamerafehler" is the camera app's "serious camera error" message
# as it appears on a device set to German; both languages are matched.
if grep -qiE "ernsthafter kamerafehler|serious camera error|CAMERA_ERROR|Camera is being used|cannot connect" "$OUT/logcat-live.txt"; then
    bad "camera error in the log"
    grep -iE "camera error|CAMERA_ERROR|cannot connect" "$OUT/logcat-live.txt" \
        | head -6 | sed 's/^/         /'
else
    ok "no camera error in the log"
fi
if grep -q "GC2355" "$OUT/logcat-live.txt"; then
    ok "sensor GC2355 in the log"
else
    info "GC2355 not in the log (the log level can hide it)"
fi
# Proof that image data is flowing
if grep -qE "startPreview\] - status\(0\)|onHandleStartPreview\] -" "$OUT/logcat-live.txt"; then
    ok "startPreview returned with status 0"
else
    info "no startPreview success found in the log"
fi

# -------------------------------------------------------- 5. Capture
stage "Take a photo"
SIZE="$(r wm size | grep -oE '[0-9]+x[0-9]+' | head -1)"
W="${SIZE%x*}"; H="${SIZE#*x}"
info "Screen: ${W}x${H}"

TAKEN=0
PKG="${ACT%%/*}"
if ! in_foreground "$PKG"; then
    info "activity not in the foreground - starting it again"
    r "am start -a android.media.action.STILL_IMAGE_CAMERA -n $ACT" >/dev/null
    sleep 8
fi
for V in 1 2 3; do
    case $V in
        1) info "Attempt 1: shutter from the UI tree"
           tap_button shutter_button || info "shutter_button not in the tree" ;;
        2) info "Attempt 2: KEYCODE_CAMERA";      r input keyevent 27 >/dev/null ;;
        3) info "Attempt 3: shutter bottom centre"
           r "input tap $((W/2)) $((H*90/100))" >/dev/null ;;
    esac
    sleep 8
    AFTER="$(r "ls $CAM_DIR 2>/dev/null | wc -l" | tr -cd '0-9')"
    AFTER="${AFTER:-0}"
    if [ "$AFTER" -gt "$BEFORE" ]; then
        ok "new file after attempt $V"
        TAKEN=1; break
    fi
done
[ "$TAKEN" -eq 0 ] && bad "no new file in $CAM_DIR"

# -------------------------------------------------------- 6. Check the capture
# Show the output and keep it for the dimension comparison with the second
# camera further down. check-jpeg.py prints the dimensions as "<w> x <h>".
check_jpeg() {
    python scripts/check-jpeg.py "$1" > "$2"
    local rc=$?
    cat "$2"
    return $rc
}
stage "Check the capture"
NEWEST="$(r "ls -t $CAM_DIR 2>/dev/null" | head -1)"
if [ -z "$NEWEST" ]; then
    bad "no file to check"
else
    info "File: $NEWEST"
    "$ADB" pull "$CAM_DIR/$NEWEST" "$OUT/$NEWEST" 2>&1 | tail -1 | sed 's/^/         /'
    if [ -f "$OUT/$NEWEST" ]; then
        if check_jpeg "$OUT/$NEWEST" "$OUT/jpeg1.txt"; then
            PASS=$((PASS+1))
        else
            FAIL=$((FAIL+1))
        fi
    else
        bad "file could not be pulled"
    fi
fi

# -------------------------------------------------------- 7. Front camera
stage "second camera"
# In com.android.camera2 the switch sits behind the three-dot "Options"
# button and folds itself away again after a few seconds. So unfold first,
# then read the tree and tap without delay.
BEFORE2="$(r "ls $CAM_DIR 2>/dev/null | grep -c ." | tr -cd '0-9')"
BEFORE2="${BEFORE2:-0}"
ACTIVE_BEFORE="$("$ADB" logcat -d -b all 2>/dev/null | grep -oE 'GC2355|GC0312' | tail -1)"
info "active sensor before: ${ACTIVE_BEFORE:-unknown}"

# AOSP Camera2 hides the switch as camera_toggle_button behind three_dots and
# folds it away again after a few seconds.
# Unfolding is animated; if the tree is read too early, the button is not there yet.
SWITCHED=0
if tap_button camera_toggle_button; then
    ok "switch was already visible"; SWITCHED=1
else
    for V in 1 2 3; do
        tap_button three_dots || continue
        sleep 2
        if tap_button camera_toggle_button; then
            ok "switch behind the options menu pressed (attempt $V)"
            SWITCHED=1; break
        fi
    done
    # ON-FireLOS moved the options from the app's three dots to the navigation
    # bar's button, which unfolds them by a broadcast only the system may send
    # (STATUS_BAR) - from adb that is root (adb root, on this userdebug build).
    if [ "$SWITCHED" -eq 0 ]; then
        for V in 1 2 3; do
            r am broadcast -a org.lineageos.camera.action.MODE_OPTIONS \
                -p com.android.camera2 >/dev/null
            sleep 2
            if tap_button camera_toggle_button; then
                ok "switch behind the navigation bar's options pressed (attempt $V)"
                SWITCHED=1; break
            fi
        done
    fi
    if [ "$SWITCHED" -eq 0 ]; then
        bad "camera_toggle_button not in the UI tree"
        [ "$(r id -u)" = 0 ] || info "the options open by a system broadcast: run 'adb root' first"
    fi
fi
sleep 8
tap_button shutter_button || r input keyevent 27 >/dev/null
sleep 8
AFTER2="$(r "ls $CAM_DIR 2>/dev/null | grep -c ." | tr -cd '0-9')"
if [ "${AFTER2:-0}" -gt "${BEFORE2:-0}" ]; then
    NEWEST2="$(r "ls -t $CAM_DIR" | head -1)"
    "$ADB" pull "$CAM_DIR/$NEWEST2" "$OUT/$NEWEST2" >/dev/null 2>&1
    # The same dimensions as the first image mean: the same camera, not switched.
    DIM1="$(grep -oE '[0-9]+ x [0-9]+' "$OUT/jpeg1.txt" 2>/dev/null | head -1)"
    if [ -f "$OUT/$NEWEST2" ] && check_jpeg "$OUT/$NEWEST2" "$OUT/jpeg2.txt"; then
        DIM2="$(grep -oE '[0-9]+ x [0-9]+' "$OUT/jpeg2.txt" | head -1)"
        if [ -n "$DIM1" ] && [ "$DIM1" = "$DIM2" ]; then
            bad "second image has the same dimensions ($DIM2) - the same camera"
        else
            ok "second camera also delivers a valid image ($DIM2 instead of $DIM1)"
            PASS=$((PASS+1))
        fi
    else
        bad "second capture could not be checked"
    fi
else
    bad "second camera did not produce a capture"
fi

kill $LOGPID 2>/dev/null
wait $LOGPID 2>/dev/null

printf '\n===============================\n'
printf '  passed: %d    failed: %d\n' "$PASS" "$FAIL"
printf '  App:    %s\n' "$PKG"
printf '  Logs in %s/\n' "$OUT"
printf '===============================\n'
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
