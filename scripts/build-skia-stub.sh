#!/bin/bash
# Builds an empty placeholder libskia.so for 32 and 64 bit.
#
# Why: libcam.camadapter.so lists libskia.so in DT_NEEDED, but imports ZERO
# symbols from it (measured: the intersection of its undefined symbols and
# libskia's exports is empty). Android 9 links Skia statically into libhwui -
# external/skia is cc_library_static, a libskia.so no longer exists.
# Without it dlopen fails and the camera HAL reports "No Platform".
set -u
W=/root/los16

GCC32=$(ls -d $W/prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-*/bin/arm-linux-androideabi-gcc 2>/dev/null | head -1)
GCC64=$(ls -d $W/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-*/bin/aarch64-linux-android-gcc 2>/dev/null | head -1)
echo "  32-bit compiler: ${GCC32:-MISSING}"
echo "  64-bit compiler: ${GCC64:-MISSING}"

# Straight into the device tree: device.mk copies them from there to
# /vendor/lib{,64}/libskia.so.
TARGET=$W/device/amazon/suez/libskia_stub
# The source comes with the device tree patch; it is empty on purpose.
SOURCE=$TARGET/skia_stub.c
[ -f "$SOURCE" ] || { echo "  $SOURCE is missing - run apply-patches.sh first"; exit 1; }
for PAIR in "32:$GCC32" "64:$GCC64"; do
    BITS="${PAIR%%:*}"; CC="${PAIR#*:}"
    [ -x "$CC" ] || { echo "  $BITS bit: compiler missing"; continue; }
    OUT=$TARGET/libskia-$BITS.so
    "$CC" -shared -nostdlib -fPIC -Wl,-soname,libskia.so -o "$OUT" "$SOURCE" 2>&1 | head -3
    if [ -f "$OUT" ]; then
        printf '  %s bit: %s bytes  ' "$BITS" "$(stat -c%s $OUT)"
        python3 - "$OUT" <<'PY'
import sys, struct, pathlib
d = pathlib.Path(sys.argv[1]).read_bytes()
elf_class = "64" if d[4]==2 else "32"
elf_type, = struct.unpack_from("<H", d, 16)
machine, = struct.unpack_from("<H", d, 18)
names = {40: "ARM", 183: "AArch64"}
print(f"ELF{elf_class}, type={'DYN' if elf_type==3 else elf_type}, machine={names.get(machine, machine)}")
PY
    else
        echo "  $BITS bit: build failed"
    fi
done
