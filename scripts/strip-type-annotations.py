#!/usr/bin/env python3
# Strips Java 8 type annotations and parameter names from the class files in
# a jar. Gallery2 runs jarjar over all of its classes, and jarjar's old ASM
# fails on these attributes, which ExoPlayer and Guava carry. They only serve
# compile-time checkers; nothing reads them at run time.
# Usage: strip-type-annotations.py <jar>...
import os
import struct
import sys
import zipfile

DROP = {b'RuntimeVisibleTypeAnnotations', b'RuntimeInvisibleTypeAnnotations',
        b'MethodParameters'}


def strip(data):
    pos = 10
    count = struct.unpack_from('>H', data, 8)[0]
    utf8 = {}
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack_from('>H', data, pos + 1)[0]
            utf8[i] = data[pos + 3:pos + 3 + n]
            pos += 3 + n
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError('constant tag %d' % tag)
        i += 1
    out = bytearray(data[:pos])

    def copy(n):
        nonlocal pos
        out.extend(data[pos:pos + n])
        pos += n

    def attributes():
        nonlocal pos
        n = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        kept = []
        for _ in range(n):
            name, length = struct.unpack_from('>HI', data, pos)
            body = data[pos + 6:pos + 6 + length]
            pos += 6 + length
            if utf8.get(name) in DROP:
                continue
            if utf8.get(name) == b'Code':
                body = code(body)
            kept.append(struct.pack('>HI', name, len(body)) + body)
        out.extend(struct.pack('>H', len(kept)))
        for a in kept:
            out.extend(a)

    def code(body):
        nonlocal out, pos
        saved_out, saved_pos, saved_data = out, pos, data
        code_length = struct.unpack_from('>I', body, 4)[0]
        head = 8 + code_length
        exceptions = struct.unpack_from('>H', body, head)[0]
        head += 2 + 8 * exceptions
        out = bytearray(body[:head])
        pos = head
        set_data(body)
        attributes()
        result = bytes(out)
        out, pos = saved_out, saved_pos
        set_data(saved_data)
        return result

    def set_data(d):
        nonlocal data
        data = d

    copy(6)
    interfaces = struct.unpack_from('>H', data, pos)[0]
    copy(2 + 2 * interfaces)
    for _ in range(2):
        members = struct.unpack_from('>H', data, pos)[0]
        copy(2)
        for _ in range(members):
            copy(6)
            attributes()
    attributes()
    return bytes(out)


for path in sys.argv[1:]:
    with zipfile.ZipFile(path) as z:
        entries = [(info, z.read(info)) for info in z.infolist()]
    changed = 0
    with zipfile.ZipFile(path + '.tmp', 'w', zipfile.ZIP_DEFLATED) as z:
        for info, body in entries:
            if info.filename.endswith('.class'):
                new = strip(body)
                changed += new != body
                body = new
            z.writestr(info, body)
    os.replace(path + '.tmp', path)
    print('  %s: %d classes stripped' % (os.path.basename(path), changed))
