#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stop guiext-server from crashing whenever a video starts.

MediaTek's video decoder asks guiext-server for a buffer queue pool at the
start of every hardware-decoded video (SetupMetaIonHandle). The server is a
Fire OS 5 blob: it builds the pool's BufferQueue through Android 5.1's
virtual table layout. Android 9 has one more entry ahead of acquireBuffer,
so the blob's calls land one slot early - setDefaultBufferSize(1200, 1920)
becomes getReleasedBuffers(outSlotMask = 1200), which writes to address
0x4b0:

    pid: ... >>> /vendor/bin/guiext-server <<<
    fault addr 0x4b0
      #00 libgui.so      BufferQueueConsumer::getReleasedBuffers(unsigned long*)
      #01 libgui_ext.so  GuiExtPoolItem::createBufferQueue(...)

init restarts the service and the decoder takes its own error path
("GuiExt alloc return error"), so videos played on - with a tombstone and a
dead service each time. Fixing the call that crashes would only reach the
next ones: acquire, release and connect are shifted the same way, and would
then run the wrong functions silently instead of crashing.

This patch makes GuiExtPool::alloc answer INVALID_OPERATION at once,

    sub  sp, sp, #0x110     ->   mov  w0, #-38
    stp  x29, x30, [sp,#16] ->   ret

the failure the decoder already handles, without the crash. Only the 64-bit
library: guiext-server is a 64-bit process and the only one that runs the
pool.

Usage:
    fix-guiext-pool.py <dir>

<dir> may be either the device vendor project (vendor/amazon/suez) or the
proprietary directory inside it. Idempotent; refuses to touch a file whose
bytes do not match.
"""
import io
import os
import struct
import sys

LIB = 'vendor/lib64/libgui_ext.so'
ALLOC = 0x17364                     # android::GuiExtPool::alloc
OLD = (0xd10443ff, 0xa9017bfd)      # sub sp, sp, #0x110; stp x29, x30, [sp,#16]
NEW = (0x128004a0, 0xd65f03c0)      # mov w0, #-38 (INVALID_OPERATION); ret


def blobs_extracted(start):
    """Before extract-files.sh has run there is nothing to patch yet."""
    return any(os.path.isdir(os.path.join(start, d))
               for d in ('proprietary/vendor', 'vendor'))


def find_root(start):
    """Accept vendor/amazon/suez or its proprietary/ subdirectory."""
    for candidate in (os.path.join(start, 'proprietary'), start):
        if os.path.isdir(os.path.join(candidate, 'vendor/lib64')):
            return candidate
    return start


def main():
    if len(sys.argv) != 2:
        raise SystemExit('usage: fix-guiext-pool.py <dir>')
    root = find_root(sys.argv[1])
    if not blobs_extracted(sys.argv[1]):
        print('  blobs not extracted yet - run again after extract-files.sh')
        return
    path = os.path.join(root, LIB)
    if not os.path.exists(path):
        raise SystemExit('  missing      libgui_ext.so')
    data = bytearray(io.open(path, 'rb').read())
    is_ = struct.unpack_from('<II', data, ALLOC)
    if is_ == NEW:
        print('  already done libgui_ext.so')
        return
    if is_ != OLD:
        raise SystemExit('libgui_ext.so: 0x%08x 0x%08x at 0x%x, expected 0x%08x 0x%08x'
                         % (is_ + (ALLOC,) + OLD))
    struct.pack_into('<II', data, ALLOC, *NEW)
    io.open(path, 'wb').write(bytes(data))
    print('  patched      libgui_ext.so')


if __name__ == '__main__':
    main()
