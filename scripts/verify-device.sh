#!/usr/bin/env bash
# Acceptance check after every flash.
#
# Checks function, not the absence of denials. The difference was paid for
# dearly: capabilities are granted in permissive mode AND not logged, because
# the kernel first queries them through ns_capable_noaudit(). A clean log in
# permissive mode therefore says nothing about whether the same thing still
# works in enforcing mode.
#
# Concretely: wmt_loader lost CAP_DAC_OVERRIDE and could no longer reach
# /dev/wmtdetect. With that the whole MediaTek combo chip did not start - no
# Wi-Fi, no Bluetooth - with zero denials in the log.
#
# Git Bash converts arguments that start with "/". Hence
# MSYS_NO_PATHCONV=1.
set -u
export MSYS_NO_PATHCONV=1

ADB="${ADB:-./tools/platform-tools/adb.exe}"
# Without a reachable adb its error message ended up in the checks, and
# two of them reported [ok].
"$ADB" version >/dev/null 2>&1 || { echo "adb not found: $ADB (set ADB=...)"; exit 1; }
FAILED=0

# Without root, "dmesg" and "svc bluetooth" run as shell (uid 2000): the
# first fails on syslog_read, the second on BLUETOOTH_ADMIN - both then look
# like a ROM bug and are not.
"$ADB" root >/dev/null 2>&1
sleep 4

ok()  { printf '  [ok]   %s\n' "$1"; }
bad() { printf '  [FAIL] %s\n' "$1"; FAILED=$((FAILED + 1)); }

echo "=== Basic state ==="
E=$("$ADB" shell getenforce 2>&1 | tr -d '\r')
[ "$E" = "Enforcing" ] && ok "SELinux: $E" || bad "SELinux: $E"

D=$("$ADB" shell "service list | wc -l" 2>&1 | tr -d '\r')
[ "${D:-0}" -ge 140 ] 2>/dev/null && ok "$D services" || bad "only $D services"

K=$("$ADB" shell "dumpsys media.camera 2>/dev/null | grep -c 'Device [0-9] maps'" 2>&1 | tr -d '\r')
[ "$K" = "2" ] && ok "2 cameras" || bad "$K cameras"

echo "=== Combo chip (Wi-Fi and Bluetooth both depend on it) ==="
for N in wmtdetect stpwmt wmtWifi stpbt; do
    if "$ADB" shell "[ -e /dev/$N ]" 2>/dev/null; then
        ok "/dev/$N present"
    else
        bad "/dev/$N MISSING"
    fi
done

W=$("$ADB" shell "dumpsys wifi 2>/dev/null | head -1" 2>&1 | tr -d '\r')
case "$W" in *enabled*) ok "Wi-Fi: $W" ;; *) bad "Wi-Fi: $W" ;; esac

IP=$("$ADB" shell "ip addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+'" 2>&1 | tr -d '\r')
[ -n "$IP" ] && ok "Wi-Fi has an address: $IP" || bad "Wi-Fi without an address"

"$ADB" shell "logcat -c -b all; svc bluetooth enable" >/dev/null 2>&1
sleep 12
BT=$("$ADB" shell "dumpsys bluetooth_manager 2>/dev/null | grep -m1 'state:'" 2>&1 | tr -d '\r')
case "$BT" in *ON*) ok "Bluetooth:$BT" ;; *) bad "Bluetooth:$BT" ;; esac

C=$("$ADB" shell "logcat -b all -d 2>/dev/null | grep -c 'FATAL.*hci_layer'" 2>&1 | tr -d '\r')
[ "$C" = "0" ] && ok "no Bluetooth crashes" || bad "$C Bluetooth crashes"
"$ADB" shell "svc bluetooth disable" >/dev/null 2>&1

echo "=== Log ==="
V=$("$ADB" shell "{ logcat -b all -d 2>/dev/null; dmesg; } | grep -c 'avc: *denied'" 2>&1 | tr -d '\r')
if [ "$V" = "0" ]; then
    ok "0 denials"
else
    bad "$V denials"
    "$ADB" shell "{ logcat -b all -d 2>/dev/null; dmesg; } | grep -o 'avc: *denied.*' \
        | sed -E 's/ (ino|dev|pid|permissive)=[^ ]*//g' | sort -u" 2>&1 | sed 's/^/         /'
fi

T=$("$ADB" shell "ls /data/tombstones/ 2>/dev/null | wc -l" 2>&1 | tr -d '\r')
[ "${T:-0}" = "0" ] && ok "no tombstones" || echo "  [note] $T tombstones in /data/tombstones"

echo
if [ "$FAILED" = "0" ]; then
    echo "=== ALL PASSED ==="
else
    echo "=== $FAILED FAILED ==="
fi
exit "$FAILED"
