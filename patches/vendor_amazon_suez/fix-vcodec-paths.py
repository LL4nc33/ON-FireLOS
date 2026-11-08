#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Point MediaTek's codec driver at the vendor partition.

libvcodecdrv.so, the driver behind all of MediaTek's OMX video codecs, loads
the parts that run partly in software by absolute path - Fire OS 5 kept them
in /system/lib:

    /system/lib/libmp4enc_sa.ca7.so      MPEG-4 encoder
    /system/lib/libvp8enc_sa.ca7.so      VP8 encoder
    /system/lib/libvp8dec_sa.ca7.so      VP8 decoder
    /system/lib/libvp9dec_sa.ca7.so      VP9 decoder
    /system/lib/libvc1dec_sa.ca7.so      VC-1 decoder

On Android 9 they live in /vendor/lib, and the codec service may not load
from /system/lib anyway. The dlopen fails, the driver does not check, and
calls through a null function pointer: the whole media.codec service dies.
Found through the front camera, whose profiles record MPEG-4.

"/system/lib/" and "/vendor/lib/" are the same length, so each path is
rewritten in place. The H.264 secure encoder library the driver also names
does not exist on this device; that path is left alone.

Usage:
    fix-vcodec-paths.py <dir>

<dir> may be either the device vendor project (vendor/amazon/suez) or the
proprietary directory inside it. Idempotent; refuses to touch a file whose
bytes do not match.
"""
import io
import os
import sys

LIB = 'vendor/lib/libvcodecdrv.so'
NAMES = ['libmp4enc_sa.ca7.so', 'libvp8enc_sa.ca7.so', 'libvp8dec_sa.ca7.so',
         'libvp9dec_sa.ca7.so', 'libvc1dec_sa.ca7.so']


def patch(data):
    changed = False
    for name in NAMES:
        old = ('/system/lib/' + name + '\0').encode()
        new = ('/vendor/lib/' + name + '\0').encode()
        if data.count(new) == 1 and data.count(old) == 0:
            continue
        if data.count(old) != 1:
            raise SystemExit('%s: expected exactly one "%s", found %d'
                             % (LIB, old[:-1].decode(), data.count(old)))
        at = data.find(old)
        data[at:at + len(new)] = new
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
        raise SystemExit('usage: fix-vcodec-paths.py <dir>')
    root = find_root(sys.argv[1])
    if not blobs_extracted(sys.argv[1]):
        print('  blobs not extracted yet - run again after extract-files.sh')
        return
    path = os.path.join(root, LIB)
    if not os.path.exists(path):
        raise SystemExit('  missing      libvcodecdrv.so')
    data = bytearray(io.open(path, 'rb').read())
    if patch(data):
        io.open(path, 'wb').write(bytes(data))
        print('  patched      libvcodecdrv.so')
    else:
        print('  already done libvcodecdrv.so')


if __name__ == '__main__':
    main()
