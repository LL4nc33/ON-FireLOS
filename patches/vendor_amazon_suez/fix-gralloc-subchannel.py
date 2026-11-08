#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stop the endless loop in PowerVR's gralloc subchannel worker.

On roughly every second boot, a thread created by gralloc.mt8173.img.so
spins forever in SystemUI and in Launcher3 - two of four cores, until
reboot. Measured straight from /proc/<pid>/stat on the untouched stock
build: 1576 and 1634 ticks per 10 s on two boots, 8 and 26 on the other two
(CONFIG_HZ=100, so 1000 ticks per 10 s is one core fully busy).

The thread is gralloc_subchannel_client_worker, PowerVR's channel between a
client process and the compositor, carrying buffer format hints. Its loop:

    select()                       wait for the compositor pipe
    tee()                          peek, consuming nothing
    if  < 4 bytes   -> back to select, nothing consumed
    vmsplice()
    if  type != 1   -> back to select, nothing consumed
    if  length != 24 -> back to select, nothing consumed
    handler
    -> back to select

tee() does not consume, and the loop contains no read and no splice. Any
message that is not exactly 24 bytes of type 1 stays in the pipe, select()
reports it readable again immediately, and the thread never sleeps again.

The blob already knows how to give up: on select() or tee() failure it logs
"Client subchannel ... failed. Shutting down subchannel" and takes a clean
exit path. This patch sends the two unparseable-message branches to that
same exit.

The wrong-message-type branch is deliberately left alone. Other types may be
routine; bailing out there would overreach.

Cost when it triggers: that process gets no format hints and gralloc falls
back to default formats - exactly what happens today on a select() failure.
Against a permanently burned core that is a good trade.

Verified: eight boots with the patched blob, all clean (16, 8, 14, 18, 12,
12, 10, 16). UI intact, camera with live preview, no graphics errors.

Usage:
    fix-gralloc-subchannel.py <dir>

<dir> may be either the device vendor project (vendor/amazon/suez) or the
proprietary directory inside it. Idempotent; refuses to touch a file whose
bytes do not match.
"""
import io
import struct
import sys
import os

# address, expected word, condition code
AARCH64 = [(0xb7a0, 0x54fffaad, 0xd),      # b.le  tee result < 4
           (0xb7d0, 0x54fff921, 0x1)]      # b.ne  length != 24
AARCH64_EXIT = 0xb830

THUMB = [(0x7e9e, 0xddb9, 0xd),            # ble.n tee result < 4
         (0x7eba, 0xd1ab, 0x1)]            # bne.n length != 24
THUMB_EXIT = 0x7ef4


def patch_aarch64(data):
    changed = False
    for addr, expected, cond in AARCH64:
        want = 0x54000000 | ((((AARCH64_EXIT - addr) // 4) & 0x7FFFF) << 5) | cond
        is_ = struct.unpack_from('<I', data, addr)[0]
        if is_ == want:
            continue
        if is_ != expected:
            raise SystemExit('64 bit: 0x%08x at 0x%x, expected 0x%08x'
                             % (is_, addr, expected))
        struct.pack_into('<I', data, addr, want)
        changed = True
    return changed


def patch_thumb(data):
    changed = False
    for addr, expected, cond in THUMB:
        offset = (THUMB_EXIT - (addr + 4)) // 2
        if not 0 <= offset <= 127:
            raise SystemExit('32 bit: branch out of range')
        want = 0xD000 | (cond << 8) | (offset & 0xFF)
        is_ = struct.unpack_from('<H', data, addr)[0]
        if is_ == want:
            continue
        if is_ != expected:
            raise SystemExit('32 bit: 0x%04x at 0x%x, expected 0x%04x'
                             % (is_, addr, expected))
        struct.pack_into('<H', data, addr, want)
        changed = True
    return changed


def blobs_extracted(start):
    """Before extract-files.sh has run there is nothing to patch yet."""
    return any(os.path.isdir(os.path.join(start, d))
               for d in ('proprietary/vendor', 'vendor'))


def find_root(start):
    """Accept vendor/amazon/suez or its proprietary/ subdirectory."""
    for candidate in (os.path.join(start, 'proprietary'), start):
        if os.path.isdir(os.path.join(candidate, 'vendor/lib64/hw')):
            return candidate
    return start


def main():
    if len(sys.argv) != 2:
        raise SystemExit('usage: fix-gralloc-subchannel.py <dir>')
    root = find_root(sys.argv[1])
    if not blobs_extracted(sys.argv[1]):
        print('  blobs not extracted yet - run again after extract-files.sh')
        return
    targets = [(os.path.join(root, 'vendor/lib64/hw/gralloc.mt8173.img.so'),
                patch_aarch64, '64 bit'),
               (os.path.join(root, 'vendor/lib/hw/gralloc.mt8173.img.so'),
                patch_thumb, '32 bit')]
    for path, fn, label in targets:
        if not os.path.exists(path):
            raise SystemExit('  missing      %s (%s)' % (path, label))
        data = bytearray(io.open(path, 'rb').read())
        if fn(data):
            io.open(path, 'wb').write(bytes(data))
            print('  patched      %s' % label)
        else:
            print('  already done %s' % label)


if __name__ == '__main__':
    main()
