#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Make room for an Android 9 GraphicBuffer in the Fire OS 5 media blobs.

The camera and MediaTek's video encoder come from Fire OS 5, built against
Android 5.1. They create GraphicBuffers themselves:

    movs  r0, #128                 sizeof(GraphicBuffer) on Android 5.1
    blx   operator new(unsigned int)
    ...
    blx   GraphicBuffer::GraphicBuffer(w, h, format, usage)

Android 9 no longer has that constructor; libshim_ui provides it and builds
an Android 9 GraphicBuffer in the memory the blob allocated. On 32 bit that
object is 144 bytes, 16 more than the blob asks for - the constructor would
write past the allocation into the next one on the heap. (Until this patch
the shim's constructor was an empty stub, so the object was never built at
all and the camera HAL crashed on the first video recording.)

This patch raises the size of every such allocation to 144:

    movs  r0, #128   (80 20)   ->   movs  r0, #144   (90 20)

Eight places in five libraries, each found by disassembly as the size
argument of the operator new whose result goes straight into one of the two
old GraphicBuffer constructors. libshim_ui checks the other side: it refuses
to build if sizeof(GraphicBuffer) ever exceeds 144 on 32 bit.

Usage:
    fix-graphicbuffer-size.py <dir>

<dir> may be either the device vendor project (vendor/amazon/suez) or the
proprietary directory inside it. Idempotent; refuses to touch a file whose
bytes do not match.
"""
import io
import os
import struct
import sys

OLD = 0x2080   # movs r0, #128
NEW = 0x2090   # movs r0, #144

SITES = {
    'vendor/lib/libcam.client.so': [0x1e1d0],
    'vendor/lib/libcam_utils.so': [0x14366, 0x155b4],
    'vendor/lib/libcam.camnode.so': [0x3995a, 0x39ede],
    'vendor/lib/libmtk_mmutils.so': [0x2ccc],
    'vendor/lib/libMtkOmxVenc.so': [0x133e4, 0x1367e],
}


def patch(data, sites, name):
    changed = False
    for addr in sites:
        is_ = struct.unpack_from('<H', data, addr)[0]
        if is_ == NEW:
            continue
        if is_ != OLD:
            raise SystemExit('%s: 0x%04x at 0x%x, expected 0x%04x'
                             % (name, is_, addr, OLD))
        struct.pack_into('<H', data, addr, NEW)
        changed = True
    return changed


def blobs_extracted(start):
    """Before extract-files.sh has run there is nothing to patch yet."""
    return any(os.path.isdir(os.path.join(start, d))
               for d in ('proprietary/vendor', 'vendor'))


def find_root(start):
    """Accept vendor/amazon/suez or its proprietary/ subdirectory."""
    for candidate in (os.path.join(start, 'proprietary'), start):
        if os.path.isdir(os.path.join(candidate, 'vendor/lib')):
            return candidate
    return start


def main():
    if len(sys.argv) != 2:
        raise SystemExit('usage: fix-graphicbuffer-size.py <dir>')
    root = find_root(sys.argv[1])
    if not blobs_extracted(sys.argv[1]):
        print('  blobs not extracted yet - run again after extract-files.sh')
        return
    for rel, sites in SITES.items():
        path = os.path.join(root, rel)
        name = os.path.basename(rel)
        if not os.path.exists(path):
            raise SystemExit('  missing      %s' % name)
        data = bytearray(io.open(path, 'rb').read())
        if patch(data, sites, name):
            io.open(path, 'wb').write(bytes(data))
            print('  patched      %s' % name)
        else:
            print('  already done %s' % name)


if __name__ == '__main__':
    main()
