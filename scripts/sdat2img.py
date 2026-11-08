#!/usr/bin/env python3
"""
Builds a raw ext4 image from a block-based Android OTA.

Turns system.transfer.list + system.new.dat (already brotli-decompressed)
into system.img. Standard library only.

Supports the commands 'new', 'zero' and 'erase' - that is what a full
(non-incremental) OTA contains. Incremental commands (move/bsdiff/
imgdiff/stash) need the source image and are deliberately rejected here.

Usage:
    python sdat2img.py <transfer.list> <system.new.dat> <system.img>
"""
import os
import sys

BLOCK = 4096
CHUNK = 8 * 1024 * 1024


def parse_rangeset(text):
    """'4,0,1,2,3' -> [(0,1),(2,3)]  (halves each [start, end) in blocks)"""
    nums = [int(x) for x in text.split(",")]
    count, rest = nums[0], nums[1:]
    if count != len(rest) or count % 2 != 0:
        raise ValueError(f"invalid rangeset: {text}")
    return [(rest[i], rest[i + 1]) for i in range(0, len(rest), 2)]


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 1
    tl_path, dat_path, out_path = sys.argv[1:4]

    with open(tl_path, encoding="utf-8") as fh:
        lines = [l.rstrip("\n") for l in fh]

    version = int(lines[0])
    # Careful: line 1 is the number of blocks WRITTEN (for the progress bar),
    # NOT the image size. That follows from the highest block index of all
    # rangesets.
    written_blocks = int(lines[1])
    cmd_start = 4 if version >= 2 else 2
    print(f"transfer.list v{version}, {written_blocks} blocks to write")

    unsupported = {"move", "bsdiff", "imgdiff", "stash", "free"}
    commands = []
    for line in lines[cmd_start:]:
        if not line.strip():
            continue
        op, _, arg = line.partition(" ")
        if op in unsupported:
            print(f"ERROR: '{op}' requires a source image (incremental OTA).",
                  file=sys.stderr)
            return 2
        if op in ("new", "zero", "erase"):
            commands.append((op, arg))

    total_blocks = max((end for _op, arg in commands
                        for _start, end in parse_rangeset(arg)), default=0)
    print(f"Image size from the highest block index: {total_blocks} blocks "
          f"({total_blocks * BLOCK / 2**30:.2f} GiB)")

    dat_size = os.path.getsize(dat_path)
    written = 0
    with open(dat_path, "rb") as dat, open(out_path, "wb") as out:
        out.truncate(total_blocks * BLOCK)
        for op, arg in commands:
            for start, end in parse_rangeset(arg):
                nbytes = (end - start) * BLOCK
                out.seek(start * BLOCK)
                if op == "new":
                    left = nbytes
                    while left:
                        buf = dat.read(min(CHUNK, left))
                        if not buf:
                            print("ERROR: .dat ended early - file incomplete?",
                                  file=sys.stderr)
                            return 3
                        out.write(buf)
                        left -= len(buf)
                    written += nbytes
                else:                      # zero / erase -> zeros
                    left = nbytes
                    zeros = bytes(min(CHUNK, left))
                    while left:
                        n = min(len(zeros), left)
                        out.write(zeros[:n])
                        left -= n

    print(f"written: {written / 2**30:.2f} GiB from .dat ({dat_size / 2**30:.2f} GiB)")
    print(f"Image: {out_path}  ({os.path.getsize(out_path) / 2**30:.2f} GiB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
