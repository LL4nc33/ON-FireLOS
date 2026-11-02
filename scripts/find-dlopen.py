#!/usr/bin/env python3
"""
Finds dlopen targets and classifies the camera stack.

Fire OS's static DT_NEEDED graph is closed - so the library missing on custom
ROMs is loaded at runtime via dlopen. This script searches the blobs for .so
names that are NOT in DT_NEEDED, and separates AOSP libraries (the ROM provides
them itself) from proprietary ones (they have to be brought along).

Usage:
    python find-dlopen.py <deps.json> <lib-directory>
"""
import json
import os
import re
import sys

# Libraries every AOSP ROM brings itself. On LineageOS 16 they are the
# Android 9 variants -> ABI breaks against the 5.1 blobs are possible.
AOSP_EXACT = {
    "libc.so", "libm.so", "libdl.so", "libstdc++.so", "liblog.so", "libz.so",
    "libutils.so", "libcutils.so", "libbinder.so", "libhardware.so", "libsync.so",
    "libui.so", "libgui.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libGLESv3.so", "libjpeg.so", "libskia.so", "libcamera_metadata.so",
    "libcamera_client.so", "libnativehelper.so", "libandroid_runtime.so",
    "libcrypto.so", "libssl.so", "libexpat.so", "libsqlite.so", "libicuuc.so",
    "libicui18n.so", "libmedia.so", "libmedia_jni.so", "libstagefright.so",
    "libstagefright_foundation.so", "libpng.so", "libft2.so", "libharfbuzz_ng.so",
    "libbacktrace.so", "libunwind.so", "libbase.so", "libhwui.so", "libmemtrack.so",
    "libsensor.so", "libinput.so", "libpowermanager.so", "libgralloc_extra.so",
    "libion.so", "libprocessgroup.so", "libselinux.so", "libc++.so", "libsoundtrigger.so",
    "libnetd_client.so", "libwilhelm.so", "libOpenSLES.so", "libvorbisidec.so",
    "libspeexresampler.so", "libaudioutils.so", "libaudioclient.so", "libeffects.so",
    "libdl_android.so", "libnbaio.so", "libcommon_time_client.so", "libvndksupport.so",
}
AOSP_PREFIX = ("libandroid", "libart", "libnative", "libjavacore", "libopenjdk")

SO_RE = re.compile(rb"[A-Za-z0-9_][A-Za-z0-9_.+\-]{1,80}\.so\b")
PATH_RE = re.compile(rb"(?:/system|/vendor)/lib(?:64)?/[A-Za-z0-9_./+\-]{1,100}\.so\b")


def is_aosp(name):
    return name in AOSP_EXACT or name.startswith(AOSP_PREFIX)


def scan(path):
    with open(path, "rb") as fh:
        blob = fh.read()
    names = {m.group(0).decode("ascii", "replace") for m in SO_RE.finditer(blob)}
    paths = {m.group(0).decode("ascii", "replace") for m in PATH_RE.finditer(blob)}
    return names, paths


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    with open(sys.argv[1], encoding="utf-8") as fh:
        data = json.load(fh)
    libdir = sys.argv[2]
    index, closure = data["index"], set(data["closure"])
    present = set(index)

    # --- 1. Classification of the transitive closure ---
    proprietary = sorted(n for n in closure if not is_aosp(n))
    aosp = sorted(n for n in closure if is_aosp(n))
    print(f"=== Transitive closure: {len(closure)} libs ===")
    print(f"  proprietary (must be brought along):     {len(proprietary)}")
    print(f"  AOSP (provided by the ROM, ABI risk):    {len(aosp)}")
    print(f"\n--- AOSP libs the blobs are linked against (ABI break points) ---")
    print("  " + "\n  ".join(aosp))

    # --- 2. dlopen candidates ---
    dlopen_refs, hard_paths = {}, {}
    for name in sorted(closure):
        meta = index.get(name)
        if not meta:
            continue
        full = os.path.join(libdir, meta["path"])
        if not os.path.exists(full):
            continue
        names, paths = scan(full)
        declared = set(meta["needed"]) | {name, meta.get("soname", "")}
        for cand in names - declared:
            if cand.endswith(".so") and cand not in declared:
                dlopen_refs.setdefault(cand, []).append(name)
        for p in paths:
            hard_paths.setdefault(p, []).append(name)

    missing = {k: v for k, v in dlopen_refs.items() if k not in present}
    internal = {k: v for k, v in dlopen_refs.items() if k in present}

    print(f"\n=== dlopen candidates (in the string pool, not in DT_NEEDED) ===")
    print(f"  resolvable in the dump: {len(internal)}")
    print(f"  NOT in the dump:        {len(missing)}")
    print(f"\n--- NOT present in the dump (the hot candidates) ---")
    for cand in sorted(missing):
        refs = sorted(set(missing[cand]))
        tag = "  [AOSP]" if is_aosp(cand) else ""
        shown = ", ".join(refs[:5]) + (f" (+{len(refs) - 5})" if len(refs) > 5 else "")
        print(f"  {cand:<42}{tag:<8} <- {shown}")

    print(f"\n=== Hard-coded paths in the blobs ({len(hard_paths)}) ===")
    for p in sorted(hard_paths):
        refs = sorted(set(hard_paths[p]))
        shown = ", ".join(refs[:4]) + (f" (+{len(refs) - 4})" if len(refs) > 4 else "")
        print(f"  {p:<58} <- {shown}")

    out = os.path.join(os.path.dirname(sys.argv[1]),
                       "dlopen-" + os.path.basename(sys.argv[1]))
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"proprietary": proprietary, "aosp_linked": aosp,
                   "dlopen_missing": missing, "dlopen_internal": internal,
                   "hardcoded_paths": hard_paths}, fh, indent=2)
    print(f"\nJSON: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
