#!/usr/bin/env python3
"""
Determines the shim symbols needed for the camera port - offline.

The proprietary MT8173 camera blobs are linked against the Android 5.1 ABI.
On LineageOS 16 (Android 9) the AOSP libraries come in newer variants. This
script reads the symbols the blobs import and checks which of them are NO
longer exported on the Android 9 side. The difference is the shim list.

Every missing symbol is assigned to the Android 5.1 library that provided
it - which directly tells which shim has to be built.

Usage:
    python symbol-diff.py --stock <stock/lib64> --rom <los16/lib64> \
                          [--deps findings/deps/dlopen-deps-lib64.json] \
                          [--json findings/deps/shims-lib64.json]
"""
import argparse
import json
import os
import re
import struct
import sys

SHT_DYNSYM = 11
SHN_UNDEF = 0
STB_GLOBAL, STB_WEAK = 1, 2
STV_DEFAULT, STV_PROTECTED = 0, 3


def _u(d, off, size):
    return struct.unpack_from({2: "<H", 4: "<I", 8: "<Q"}[size], d, off)[0]


def elf_symbols(path):
    """-> (imported:set, exported:set), or None if not an ELF file."""
    try:
        with open(path, "rb") as fh:
            d = fh.read()
    except OSError:
        return None
    if len(d) < 64 or d[:4] != b"\x7fELF":
        return None
    cls = d[4]
    if cls == 1:
        shoff, shes, shnum = _u(d, 0x20, 4), _u(d, 0x2E, 2), _u(d, 0x30, 2)
        sym_sz = 16
    elif cls == 2:
        shoff, shes, shnum = _u(d, 0x28, 8), _u(d, 0x3A, 2), _u(d, 0x3C, 2)
        sym_sz = 24
    else:
        return None
    if not shoff or not shnum:
        return (set(), set())

    sections = []
    for i in range(shnum):
        b = shoff + i * shes
        if b + shes > len(d):
            break
        sh_type = _u(d, b + 4, 4)
        if cls == 1:
            sh_offset, sh_size, sh_link = _u(d, b + 16, 4), _u(d, b + 20, 4), _u(d, b + 24, 4)
        else:
            sh_offset, sh_size, sh_link = _u(d, b + 24, 8), _u(d, b + 32, 8), _u(d, b + 40, 4)
        sections.append((sh_type, sh_offset, sh_size, sh_link))

    imported, exported = set(), set()
    for sh_type, sh_offset, sh_size, sh_link in sections:
        if sh_type != SHT_DYNSYM or sh_link >= len(sections):
            continue
        str_off, str_sz = sections[sh_link][1], sections[sh_link][2]

        def name_at(idx):
            s = str_off + idx
            if s >= str_off + str_sz or s >= len(d):
                return ""
            e = d.find(b"\x00", s)
            return d[s:e].decode("utf-8", "replace") if e > s else ""

        for off in range(sh_offset, min(sh_offset + sh_size, len(d)) - sym_sz + 1, sym_sz):
            st_name = _u(d, off, 4)
            if cls == 1:
                st_info, st_other, st_shndx = d[off + 12], d[off + 13], _u(d, off + 14, 2)
            else:
                st_info, st_other, st_shndx = d[off + 4], d[off + 5], _u(d, off + 6, 2)
            bind = st_info >> 4
            if bind not in (STB_GLOBAL, STB_WEAK) or not st_name:
                continue
            nm = name_at(st_name)
            if not nm:
                continue
            if st_shndx == SHN_UNDEF:
                imported.add(nm)
            elif (st_other & 0x3) in (STV_DEFAULT, STV_PROTECTED):
                exported.add(nm)
    return (imported, exported)


# --- rough Itanium ABI demangler, only for readability in the report ---
_NEST = re.compile(r"^_ZN(.+)$")


def pretty(sym):
    m = _NEST.match(sym)
    if not m:
        return sym
    rest, parts = m.group(1), []
    while rest and rest[0].isdigit():
        i = 0
        while i < len(rest) and rest[i].isdigit():
            i += 1
        ln = int(rest[:i])
        parts.append(rest[i:i + ln])
        rest = rest[i + ln:]
    if not parts:
        return sym
    tail = {"C1": "ctor", "C2": "ctor", "D0": "dtor", "D1": "dtor", "D2": "dtor"}
    if rest[:2] in tail:
        parts.append(f"<{tail[rest[:2]]}>")
    return "::".join(parts)


def scan_dir(path):
    out = {}
    for root, _dirs, files in os.walk(path):
        for n in files:
            res = elf_symbols(os.path.join(root, n))
            if res is not None:
                out[n] = res
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stock", required=True)
    ap.add_argument("--rom", required=True)
    ap.add_argument("--deps")
    ap.add_argument("--json")
    a = ap.parse_args()

    stock, rom = scan_dir(a.stock), scan_dir(a.rom)
    print(f"Stock-Libs: {len(stock)}   ROM-Libs: {len(rom)}")
    if not rom:
        print("ERROR: no ELF files found in the ROM directory.", file=sys.stderr)
        return 1

    if a.deps and os.path.exists(a.deps):
        with open(a.deps, encoding="utf-8") as fh:
            prop = [n for n in json.load(fh)["proprietary"] if n in stock]
    else:
        prop = sorted(stock)
    print(f"Proprietary libs considered: {len(prop)}")

    # What LineageOS 16 provides, plus the bundled blobs themselves.
    rom_exports = set()
    for _n, (_i, e) in rom.items():
        rom_exports |= e
    blob_exports = set()
    for n in prop:
        blob_exports |= stock[n][1]
    available = rom_exports | blob_exports

    # Which 5.1 AOSP lib provided a symbol?
    provider = {}
    for n, (_i, e) in stock.items():
        if n in prop:
            continue
        for s in e:
            provider.setdefault(s, n)

    missing, by_lib = {}, {}
    for n in prop:
        gaps = sorted(s for s in stock[n][0] if s not in available)
        if gaps:
            by_lib[n] = gaps
            for s in gaps:
                missing.setdefault(s, {"from": provider.get(s, "?"), "used_by": []})
                missing[s]["used_by"].append(n)

    print(f"\nROM exports {len(rom_exports)} symbols, blobs {len(blob_exports)}")
    print(f"=== MISSING symbols in total: {len(missing)} ===")
    print(f"=== affected blobs: {len(by_lib)} of {len(prop)} ===")

    per_src = {}
    for s, meta in missing.items():
        per_src.setdefault(meta["from"], []).append(s)
    print("\n--- grouped by Android 5.1 source library (= shims to build) ---")
    for src in sorted(per_src, key=lambda k: -len(per_src[k])):
        print(f"  {src:<34} {len(per_src[src]):5d} symbols")

    print("\n--- Examples (max. 6 per shim) ---")
    for src in sorted(per_src, key=lambda k: -len(per_src[k])):
        print(f"\n  [{src}]")
        for s in sorted(per_src[src])[:6]:
            print(f"    {pretty(s)}")
            if pretty(s) != s:
                print(f"        {s}")

    if a.json:
        os.makedirs(os.path.dirname(a.json) or ".", exist_ok=True)
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump({"missing": missing, "by_lib": by_lib,
                       "per_source_lib": {k: sorted(v) for k, v in per_src.items()}},
                      fh, indent=2)
        print(f"\nJSON: {a.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
