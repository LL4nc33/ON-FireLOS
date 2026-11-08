#!/usr/bin/env python3
"""
Checks which dependencies of the camera stack an unpacked ROM does NOT satisfy.

Goes over every camera library in the ROM, resolves its DT_NEEDED against
everything the ROM provides, and reports the gaps. For every gap the stock dump
is used to determine how many symbols from it are actually needed:

  0 symbols  -> an empty stub with a matching SONAME is enough
  >0 symbols -> it needs a real shim that supplies these symbols

Usage:
    python rom-gap-check.py --rom work/los16 --stock stock-dump/system \
                            [--json findings/deps/rom-gaps.json]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib.machinery import SourceFileLoader

_here = os.path.dirname(os.path.abspath(__file__))
ad = SourceFileLoader("ad", os.path.join(_here, "analyze-deps.py")).load_module()
sd = SourceFileLoader("sd", os.path.join(_here, "symbol-diff.py")).load_module()

CAM_PREFIXES = ("libcam", "lib3a", "libfeatureio", "libimageio", "libmtkcamera",
                "libn3d3a", "camera.", "libshim_camera", "libJpgEncPipe",
                "libdpframework", "libcamalgo", "libcameracustom")

ARCHS = [
    ("64-bit", ["lib64", "lib64/hw", "vendor/lib64", "vendor/lib64/hw"], "lib64"),
    ("32-bit", ["lib", "lib/hw", "vendor/lib", "vendor/lib/hw"], "lib"),
]


def collect(root, subdirs):
    found = {}
    for sub in subdirs:
        p = os.path.join(root, sub.replace("/", os.sep))
        if not os.path.isdir(p):
            continue
        for f in sorted(os.listdir(p)):
            if f.endswith(".so"):
                found.setdefault(f, os.path.join(p, f))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rom", required=True, help="unpacked ROM (contains lib64/, vendor/)")
    ap.add_argument("--stock", required=True, help="stock dump (contains lib/, lib64/)")
    ap.add_argument("--json")
    a = ap.parse_args()

    report = {}
    for arch, subdirs, stocklib in ARCHS:
        print(f"\n{'=' * 64}\n  {arch}\n{'=' * 64}")
        avail = collect(a.rom, subdirs)
        cams = {n: p for n, p in avail.items() if n.startswith(CAM_PREFIXES)}
        print(f"ROM provides: {len(avail)} .so   |   camera libs: {len(cams)}")

        gaps = {}
        for name, path in cams.items():
            try:
                _soname, needed, _bits = ad.parse_elf(path)
            except Exception as exc:                      # noqa: BLE001
                print(f"  (skipped: {name}: {exc})")
                continue
            for dep in needed:
                if dep not in avail:
                    gaps.setdefault(dep, []).append(name)

        print(f"\n>>> UNRESOLVABLE DT_NEEDED: {len(gaps)}")
        detail = {}
        for dep in sorted(gaps):
            users = sorted(set(gaps[dep]))
            stock_path = os.path.join(a.stock, stocklib, dep)
            needed_syms, have_stock = set(), os.path.exists(stock_path)
            if have_stock:
                _i, exported = sd.elf_symbols(stock_path)
                for u in users:
                    imported, _e = sd.elf_symbols(cams[u])
                    needed_syms |= (imported & exported)
            kind = ("stub" if not needed_syms else "shim") if have_stock else "unknown"
            detail[dep] = {"users": users, "symbols": sorted(needed_syms), "kind": kind}
            verdict = {
                "stub": "STUB is enough (0 symbols needed)",
                "shim": f"REAL SHIM needed ({len(needed_syms)} symbols)",
                "unknown": "not in the stock dump - not assessed",
            }[kind]
            print(f"  {dep:<30} <- {', '.join(users)}")
            print(f"       {verdict}")
            for s in sorted(needed_syms)[:10]:
                print(f"         {sd.pretty(s)}")
        report[arch] = detail

    if a.json:
        os.makedirs(os.path.dirname(a.json) or ".", exist_ok=True)
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
        print(f"\nJSON: {a.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
