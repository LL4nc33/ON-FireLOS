#!/usr/bin/env python3
"""Checks whether a file is a real camera image.

A photo can "be there" in four ways and still prove nothing: the file is
empty, it is not a JPEG, it is truncated, or it is a completely black image.
This script rules out all four.

Exit code 0 = valid camera image.
"""

import struct
import sys
from pathlib import Path

# The smallest real capture on this device is the front camera at 480x640
# (GC0312, 0.3 MP). With little image content such a file can be under 13 KB -
# measured: 12,104 bytes. The thresholds are set from that, not by feel.
MIN_EDGE = 480              # shorter image edge
MIN_BYTES = 4_000
MIN_VALUES = 64             # distinct byte values in the middle of the image

# Markers that carry image dimensions (SOF0..SOF15 without the DHT/JPG/DAC gaps)
SOF = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
       0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}


def fail(text):
    print(f"  [FAIL] {text}")
    return 1


def main():
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} image.jpg")

    path = Path(sys.argv[1])
    if not path.is_file():
        return fail(f"{path} does not exist")

    d = path.read_bytes()
    print(f"         Size:       {len(d):,} bytes")

    if len(d) < MIN_BYTES:
        return fail(f"only {len(d)} bytes - too small for a photo")
    if d[:2] != b"\xff\xd8":
        return fail("not a JPEG (SOI marker missing)")
    if d[-2:] != b"\xff\xd9":
        return fail("JPEG truncated (EOI marker missing)")

    # Read the dimensions from the SOF segment
    i, width, height = 2, None, None
    while i < len(d) - 9:
        if d[i] != 0xFF:
            i += 1
            continue
        marker = d[i + 1]
        if marker in SOF:
            height, width = struct.unpack(">HH", d[i + 5:i + 9])
            break
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7 or marker == 0xFF:
            i += 2
            continue
        if marker == 0xDA:          # image data from here on, no more dimensions
            break
        length, = struct.unpack(">H", d[i + 2:i + 4])
        i += 2 + length

    if not width:
        return fail("no image dimensions found")
    # verify-camera.sh greps this line for "<width> x <height>" - keep the format.
    print(f"         Dimensions: {width} x {height}")
    # Treat portrait and landscape the same: the shorter edge counts
    if min(width, height) < MIN_EDGE:
        return fail(f"{width}x{height} is too small for a camera image")

    # A black image compresses to almost nothing and has hardly any variance.
    middle = d[len(d) // 4: len(d) // 4 * 3]
    values = len(set(middle))
    print(f"         Content:    {values} distinct byte values in the middle of the image")
    if values < MIN_VALUES:
        return fail("image looks empty - too little variance")

    print(f"  [ok]   valid camera image: {width}x{height}, {len(d):,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
