#!/usr/bin/env python3
"""
ELF dependency analysis for the MediaTek camera stack.

Reads DT_NEEDED/DT_SONAME straight from the ELF headers (no external tools,
runs on Windows as on Linux) and builds the transitive closure from a root set.
Reports which libraries are referenced but not present in the dump.

Usage:
    python analyze-deps.py <lib-directory> [--root camera.mt8173.so] [--json out.json]
"""
import json
import os
import struct
import sys

DT_NULL, DT_NEEDED, DT_STRTAB, DT_SONAME = 0, 1, 5, 14
PT_LOAD, PT_DYNAMIC = 1, 2


class ElfError(Exception):
    pass


def _u(data, off, size):
    fmt = {2: "<H", 4: "<I", 8: "<Q"}[size]
    return struct.unpack_from(fmt, data, off)[0]


def parse_elf(path):
    """Returns (soname, [needed...], bits), or None if not an ELF file."""
    with open(path, "rb") as fh:
        data = fh.read()
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    ei_class = data[4]
    if ei_class == 1:
        bits, phoff, phes, phnum = 32, _u(data, 0x1C, 4), _u(data, 0x2A, 2), _u(data, 0x2C, 2)
        dyn_entry, tag_sz = 8, 4
    elif ei_class == 2:
        bits, phoff, phes, phnum = 64, _u(data, 0x20, 8), _u(data, 0x36, 2), _u(data, 0x38, 2)
        dyn_entry, tag_sz = 16, 8
    else:
        raise ElfError(f"unknown ELF class {ei_class}")

    loads, dynamic = [], None
    for i in range(phnum):
        base = phoff + i * phes
        if base + phes > len(data):
            break
        p_type = _u(data, base, 4)
        if bits == 32:
            p_offset, p_vaddr, p_filesz = (_u(data, base + 4, 4), _u(data, base + 8, 4),
                                           _u(data, base + 16, 4))
        else:
            p_offset, p_vaddr, p_filesz = (_u(data, base + 8, 8), _u(data, base + 16, 8),
                                           _u(data, base + 32, 8))
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)

    if dynamic is None:
        return ("", [], bits)

    def vaddr_to_off(vaddr):
        for v, o, sz in loads:
            if v <= vaddr < v + sz:
                return o + (vaddr - v)
        return None

    # First pass: find DT_STRTAB
    strtab_off, entries = None, []
    off, end = dynamic[0], dynamic[0] + dynamic[1]
    while off + dyn_entry <= min(end, len(data)):
        d_tag = _u(data, off, tag_sz)
        d_val = _u(data, off + tag_sz, tag_sz)
        if d_tag == DT_NULL:
            break
        entries.append((d_tag, d_val))
        if d_tag == DT_STRTAB:
            strtab_off = vaddr_to_off(d_val)
        off += dyn_entry

    if strtab_off is None:
        return ("", [], bits)

    def getstr(idx):
        start = strtab_off + idx
        nul = data.find(b"\x00", start)
        return data[start:nul].decode("utf-8", "replace") if nul > start else ""

    soname, needed = "", []
    for d_tag, d_val in entries:
        if d_tag == DT_NEEDED:
            needed.append(getstr(d_val))
        elif d_tag == DT_SONAME:
            soname = getstr(d_val)
    return (soname, needed, bits)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    libdir = sys.argv[1]
    roots = [a for i, a in enumerate(sys.argv) if sys.argv[i - 1] == "--root"]
    json_out = next((sys.argv[i + 1] for i, a in enumerate(sys.argv) if a == "--json"), None)

    index, failed = {}, {}
    for root, _dirs, files in os.walk(libdir):
        for name in files:
            full = os.path.join(root, name)
            try:
                res = parse_elf(full)
            except Exception as exc:          # noqa: BLE001 - diagnostics, not control flow
                failed[name] = str(exc)
                continue
            if res is None:
                continue
            soname, needed, bits = res
            index[name] = {"soname": soname, "needed": needed, "bits": bits,
                           "path": os.path.relpath(full, libdir)}

    print(f"ELF files analysed: {len(index)}  (errors: {len(failed)})")

    if not roots:
        roots = [n for n in index if n.startswith(("camera.", "libcam", "lib3a", "libfeatureio",
                                                   "libimageio", "libmtkcamera", "libn3d3a"))]
    roots = [r for r in roots if r in index] or list(index)

    # Transitive closure
    seen, missing, queue = set(), {}, list(roots)
    while queue:
        cur = queue.pop()
        if cur in seen:
            continue
        seen.add(cur)
        for dep in index.get(cur, {}).get("needed", []):
            if dep in index:
                queue.append(dep)
            else:
                missing.setdefault(dep, []).append(cur)

    print(f"Roots: {len(roots)}   reachable libs: {len(seen)}")
    print(f"\n=== MISSING dependencies ({len(missing)}) ===")
    if missing:
        for dep in sorted(missing):
            refs = sorted(set(missing[dep]))
            shown = ", ".join(refs[:6]) + (f" (+{len(refs) - 6})" if len(refs) > 6 else "")
            print(f"  {dep:<40} <- {shown}")
    else:
        print("  none - the dependency graph is fully closed within the dump.")

    if json_out:
        with open(json_out, "w", encoding="utf-8") as fh:
            json.dump({"index": index, "roots": sorted(roots),
                       "closure": sorted(seen), "missing": missing}, fh, indent=2)
        print(f"\nJSON: {json_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
