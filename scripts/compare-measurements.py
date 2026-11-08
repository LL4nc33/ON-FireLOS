#!/usr/bin/env python3
"""Compares two snapshots from findings/measurements/.

Shows only what has changed - everything that stayed the same is dropped.
Without this, 150-line files pile up that nobody ever puts side by side.

Usage:
    python scripts/compare-measurements.py <before.txt> <after.txt>
    python scripts/compare-measurements.py --last 2
"""

import re
import sys
from pathlib import Path

MEASUREMENTS = Path("findings/measurements")

# Lines that change on every run and say nothing about the optimisation
NOISE = re.compile(
    r"^(#|uptime|\s*$)|"
    r"(MemFree|MemAvailable|Cached|Dirty|RSS|temperature|level:|"
    # "online" fluctuates from second to second through cluster hotplug and
    # says nothing about the configuration
    r"ro\.build\.date|scaling_cur|^online|mtkts|uptime|"
    # The GPU voltages are adjusted by the SVS/PTP unit according to chip
    # quality and temperature - they drift by a few millivolts on their own
    r"volt = |g_cur_gpu|mm_stat)", re.I)


def read_sections(path):
    """Reads a measurement file as {section: [lines]}."""
    sections, current = {}, "(header)"
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("## "):
            current = line[3:].strip()
            sections.setdefault(current, [])
        else:
            sections.setdefault(current, []).append(line.rstrip())
    return sections


def shorten(s, n=58):
    return s if len(s) <= n else s[: n - 1] + "..."


def main():
    # "--letzte" is the option's earlier name, still accepted.
    if len(sys.argv) == 3 and sys.argv[1] in ("--last", "--letzte"):
        files = sorted(MEASUREMENTS.glob("*.txt"))[-int(sys.argv[2]):]
        if len(files) < 2:
            raise SystemExit("fewer than two measurements available")
        before, after = files[0], files[-1]
    elif len(sys.argv) == 3:
        before, after = Path(sys.argv[1]), Path(sys.argv[2])
    else:
        raise SystemExit(__doc__)

    a, b = read_sections(before), read_sections(after)
    print(f"  before: {before.name}")
    print(f"  after:  {after.name}")

    changes = 0
    for section in dict.fromkeys(list(a) + list(b)):
        lines_a = [z for z in a.get(section, []) if not NOISE.search(z)]
        lines_b = [z for z in b.get(section, []) if not NOISE.search(z)]
        if lines_a == lines_b:
            continue

        # Set them side by side line by line, where the structure stayed the same
        print(f"\n  === {section} ===")
        only_a = [z for z in lines_a if z not in lines_b]
        only_b = [z for z in lines_b if z not in lines_a]
        for z in only_a:
            print(f"    - {shorten(z.strip())}")
            changes += 1
        for z in only_b:
            print(f"    + {shorten(z.strip())}")
            changes += 1

    if changes == 0:
        print("\n  no differences outside the noise")
    else:
        print(f"\n  {changes} changed lines")
        print("  (-) was like this before, (+) is like this now")


if __name__ == "__main__":
    main()
