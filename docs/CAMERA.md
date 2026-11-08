# The camera

On every published custom ROM for `suez` the camera does not record. On stock
Fire OS it does. That gap is the reason this project exists, and closing it
took six independent fixes.

Each one on its own was enough to make the camera completely unusable, and
each one hid the next. Three of them were previously undocumented, because
they only become visible once the first three are fixed.

## The six

| # | Layer | Symptom | Fix |
|---|---|---|---|
| 1 | linker shims missing | `Number of camera devices: 0` | `shims.mk`, 20 mappings |
| 2 | HIDL provider disabled | no `legacy/0` | `hidl.mk` plus `manifest.xml` |
| 3 | null pointer in AOSP | `ENOSYS` when opening | patch to `hardware/interfaces`, **only possible by building** |
| 4 | `media_profiles` under the wrong name | app crashes at startup | rename to `media_profiles_V1_0.xml` |
| 5 | `libskia.so` no longer exists | `No Platform` | empty stub |
| 6 | `libn3d3a.so` not shipped | `No Platform` | add the blob to `proprietary-files.txt` |

Numbers 1 and 2 come from an upstream pull request merged on 2026-10-03. It
exists in source and in **no built ROM** — the last build predates it by more
than three years. That alone makes building unavoidable.

## Why a prebuilt ROM could never have worked

Defect 3 sits in `CameraDevice::sGetMemory` in AOSP itself. It casts the
`user` pointer and dereferences it immediately, while MediaTek's HAL passes
`nullptr`. The fix has to be compiled into `camera.device@1.0-impl.so`, and
no prebuilt of that file exists anywhere. No amount of file swapping on a
running device gets past this one.

## The three that were new

**4 — the profile file.** Android 9 looks for `media_profiles_V1_0.xml`. The
device tree ships `media_profiles.xml`. The camera app does not fail quietly;
it dies at startup with *Could not find supported video qualities*.

**5 — the missing Skia.** MediaTek's camera stack links against
`libskia.so`, which Android 9 no longer ships as a shared library. The loader
fails, the HAL reports `No Platform`. An empty stub that satisfies the symbol
is enough — nothing actually calls into it.

**6 — the unshipped blob.** `libn3d3a.so` is missing from
`proprietary-files.txt`, so `extract-files.sh` never copies it. Same symptom
as 5, different cause, and the second one only becomes visible after the
first is fixed.

## Defect 7: the camera had no icon

After all six were fixed the camera worked but had **no launcher icon**, and
no reboot healed it.

`DisableCameraReceiver` in AOSP's `packages/apps/Camera2` runs once at
`BOOT_COMPLETED`. If no camera is reported at that moment, it disables
`CameraLauncher` permanently and then disables itself. There is no path back:
the state lives in `/data` and survives a flash that does not wipe.

Any device whose camera was broken on an earlier ROM carries this. The
receiver is removed from the manifest in this build.

## Proof, not claims

The acceptance test only passes on a **real photograph**, not on a camera
app that opens:

```
IMG_20261026_001948.jpg   1200 x 1600   rear  (GC2355)
IMG_20261026_002052.jpg    480 x 640    front (GC0312)
```

`scripts/verify-camera.sh` reads the live UI tree with `uiautomator dump` and
taps the shutter by its real bounds, never by fixed coordinates: a tap at a
hardcoded position lands wherever the current layout happens to put it, and a
test that misses the shutter reports a broken camera on a working one.
`scripts/check-jpeg.py` then confirms the result is a real camera image and
not a placeholder.

The second photo has to come from the other camera, so the test switches
between them. In this ROM the camera's options - timer, grid, HDR and the
front/back switch - open from the navigation bar's context button, not from
a button in the picture. That button sends a broadcast only the system may
send (`STATUS_BAR`); the script sends the same broadcast, which from adb
needs `adb root` first (the build is userdebug). Without it the script says
so and the second photo fails. The switch is confirmed by the image size:
a second image of the same dimensions as the first came from the same
camera.

Result on the build of 2026-11-08: 14 of 14 checks passed, both cameras.

## A note on camera apps

The ROM ships AOSP Camera2. LineageOS' own Snap is not included: it takes the
screen size from `getRealSize()`, which is the panel size **including** the
navigation bar, so the shutter button is clipped by 72px on this device. The
same pattern appears in `VideoUI`, `WideAnglePanoramaUI`, `FocusOverlayManager`
and `ZoomRenderer`. A patch for it was dropped again: Snap is not built, and
an untested patch for an app that is not shipped is only weight. The fix is a
one-word change in each of those five places, `getRealSize` to `getSize`.

A Google Camera port is not possible here: it needs Camera2 API level `FULL`
or at least `LIMITED`, and this hardware only offers `LEGACY`.
