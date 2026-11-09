# Building it yourself


The scripts expect the source tree at `/root/los16` on an Ubuntu 22.04 build
host and run as root there. `apply-patches.sh` and `apply-device-patches.sh`
take another tree as their first argument; `sync-sources.sh` and
`build-skia-stub.sh` have the path written in. `scripts/` is this repository,
`device/` is the source tree.

```sh
# build host, as root
scripts/setup-buildenv.sh        # packages, ccache, git-lfs
scripts/sync-sources.sh          # shallow, saves about 20 GB
scripts/apply-patches.sh         # this project's patches - first, because the device
                                 # tree gets its list of drivers and one more patch here
scripts/apply-device-patches.sh  # the device tree's own patches, one by one, idempotent

# PC with the tablet on USB, still on Fire OS; the list comes from the patched tree
scripts/extract-blobs.sh proprietary-files.txt   # into stock-dump/system

# build host again, with stock-dump copied over
device/amazon/suez/extract-files.sh /path/to/stock-dump   # into vendor/amazon/suez
scripts/apply-patches.sh         # once more: now the blob patchers find the drivers
scripts/build-skia-stub.sh       # the two libskia stubs, not shipped as binaries
scripts/fetch-androidx-webkit.sh # androidx.webkit 1.10.0 for the browser, checksum-verified
scripts/fetch-exoplayer.sh       # ExoPlayer 2.19.1 and Guava for the gallery's video player
scripts/fetch-store-apps.sh      # Droid-ify, Aurora Store, eSpeak from F-Droid, checksum-verified
scripts/fetch-onfireai-kws.sh    # ON-FireAI's wake word and speech recognition: sherpa-onnx library and models, checksum-verified
scripts/onfireai-into-tree.sh /root/los16   # ON-FireAI (onfireai/) as packages/apps/ONFireAI
```

Then create the signing keys (see [Signing keys](#signing-keys)) - before the
first build, not after - and build:

```sh
cd /root/los16 && source build/envsetup.sh && breakfast suez && mka bacon
```

The order matters twice. The device tree's list of drivers to extract
(`proprietary-files.txt`) is changed by this project's patch - Amazon's copy of
an audio effect library is left out in favour of the one built from source -
so the patch comes before the extraction. And the blob patchers need the
extracted drivers, so `apply-patches.sh` runs again after it. On the first run
they say so and leave everything alone; on the second, the text patches report
"bereits angewendet".

`apply-patches.sh` runs every `.py` it finds next to the `.patch` files in a
directory. That is how `patches/vendor_amazon_suez/fix-gralloc-subchannel.py`
gets at the extracted blob — it rewrites four branch bytes and refuses to touch
a file whose contents do not match. `fix-graphicbuffer-size.py`,
`fix-vcodec-paths.py` and `fix-guiext-pool.py` beside it work the same way.
Patchers and `.patch` files are idempotent; running the script twice is safe,
and any of them failing fails the run. An `Android.bp` in a patch directory is
copied into the project as it is (the Soong declaration of one vendor blob).

Expect around 1.2GiB of system content, an OTA zip of about 490MiB (516MB
for the build of 2026-11-08) and several hours on
a machine with enough cores. The build needs roughly 250GB of disk.

On a tight disk, about 10GB can go without touching the build: the macOS
toolchains (`prebuilts/clang/host/darwin-x86`, `prebuilts/gcc/darwin-x86/*`,
`prebuilts/gdb`, `go` and `python` for darwin), `prebuilts/android-emulator`
and `prebuilts/qemu-kernel`, removed through a `remove-project` entry in
`.repo/local_manifests/` so that a sync does not bring them back. Not removable:
`cts` and `test/suite_harness` (the VTS and tradefed makefiles read from them)
and `prebuilts/gradle-plugin` (SystemUI's manifest merger comes from there).
Of the gradle plugin, 15 files are read; the rest of its 3.5GB can be marked
`skip-worktree` and deleted. The packaging step at the end needs about 6GB
free on its own.

## The device tree does not build as shipped

Seven defects had to be fixed before anything came out. All of them are
reproducible and all are candidates for upstream pull requests.

| What | Effect | Project |
|---|---|---|
| `patches/apply.sh` never sets `$rootdirectory`, passes all patches to a single `git apply` and checks no return value | **28 patches are silently not applied.** Camera, microphone, HEVC, Bluetooth and sdcard permissions all missing | `lineage16-suez` |
| `tools/patch_camera_client.py` hashes the **whole** file | rejects the Fire OS 5.3.6.4 blob and takes `setup-makefiles.sh` down with it. Replaced in this project's device patch by a check of the function and the two bytes it changes | `lineage16-suez` |
| `libnvram` and `libdpframework` are not build modules | `ninja: error: ... missing and no known rule` | `lineage16-suez` |
| `DrmPlugin.h` declares `DeviceFiles`, a type that does not exist on the branch | build stops at `android.hardware.drm@1.1-service.clearkey` | **LineageOS `frameworks/av`** |
| `media_profiles.xml` sits under a name Android 9 does not look for | camera app crashes at startup: *Could not find supported video qualities* | `lineage16-suez` |
| `libn3d3a.so` missing from `proprietary-files.txt` | camera HAL reports `No Platform`, opening fails with `ENOSYS` | `lineage16-suez` |
| `DisableCameraReceiver` permanently disables the camera launcher icon when no camera is reported at first boot, then disables itself | the camera app is installed and working but has **no icon**, and no reboot heals it. The state lives in `/data` and survives a flash without wiping | **AOSP `packages/apps/Camera2`** |

The `clearkey` defect is not a suez problem. It hits anybody building
`lineage-16.0`.

The missing camera icon only became visible after the camera fix was
applied — before that, the camera never gets that far.

## Patches in this repository

Each directory name maps to its project: `frameworks_av` to `frameworks/av`,
and so on. Together they are about 96,000 changed lines across some 2,450
files; about 10,000 of those lines are the root certificates, much of the
`frameworks` patches is the device tree's own MediaTek port, carried here in
its final form.

| | |
|---|---|
| `patches/device_amazon_suez/` | device tree: build flags, `init.mt8173.rc`, overlays, f2fs, ZRAM, cpuset masks, the `GraphicBuffer` shim. `0002` holds the camera video profiles apart: that file has CRLF line endings, and `.gitattributes` keeps them in its patch |
| `patches/kernel_amazon_suez/` | clock tables, defconfig, the cpuset hotplug fixes, serial console off, a checkpoint at umount for f2fs, the Wi-Fi driver's 5GHz radar channels and country list, the PS4 controller and five HID drivers' `input_configured`, battery protection in the charger driver, charging while switched off (USB wait, power key), a GPU that survives lockups instead of leaking its memory, and 268 upstream security fixes for 246 CVEs ([KERNEL-CVES.md](KERNEL-CVES.md)). **GPL-2.0** |
| `patches/frameworks_av/` | the camera and media stack — MediaTek camera parameters, colour conversion, `CameraSource`, the `clearkey` build fix, bass boost and virtualizer allowed on the speaker. The largest single patch |
| `patches/frameworks_base/` | framework side: bitmaps, `PackageManagerService`, `AppOpsService`, `KeyguardBouncer`, notification text colours that follow the background (dark mode); in SystemUI the edge gestures, the capture tile (screenshot or screen recording, with the microphone, and the recording chip), the standby tile, the picture-in-picture menu layout, and notifications recoloured when light and dark switch |
| `patches/system_bt/` | HID GET_REPORT and SET_REPORT from the kernel forwarded to the device, output reports on the interrupt channel (both as Android 10) |
| `patches/frameworks_opt_net_wifi/` | the Wi-Fi country from the time zone (then the language's region) on a tablet without SIM; Wi-Fi power save only while the screen is off |
| `patches/frameworks_native/` | MediaTek colour formats, `ro.sf.hwrotation`, the `BufferQueueConsumer` null check |
| `patches/packages_apps_Gallery2/` | a navigation rail instead of the bottom bar and a flat top bar; Immich as its own section: albums and photos from the server, camera backup, share-to-Immich, settings; the selection bar readable in dark mode; album counts measured and set flush right with a thousands separator (a fixed slot held two digits); the navigation bar slot opens the settings |
| `patches/packages_apps_Camera2/` | removes `DisableCameraReceiver`, keeps the app on the plain camera module (`useCaptureModule = false`), and opens its mode options (timer, grid, HDR, camera switch) from the navigation bar's context button, the mode list on a long press; the round options button stays out of the picture |
| `patches/packages_apps_Jelly/` | installable web apps, ad and tracker blocking, renderer loss handling, pull to refresh, camera and microphone for sites (asked once per site) |
| `patches/packages_apps_Trebuchet/` | installed web apps as apps: no badge, in all apps, uninstallable; a fix for icons of unpinned shortcuts left on the home screen; the system usage widget; a *Style* entry in the home screen's long-press menu |
| `patches/packages_apps_LineageParts/` | "Hide in apps" for the status bar: one switch, the home screen keeps its bar; the *Default* accent swatch in the logo's green; the lock screen rotation switch on by default |
| `patches/packages_apps_AudioFX/` | effects rebuilt after an audioserver restart; bass and virtualizer usable on the built-in speaker |
| `patches/packages_apps_DeskClock/` | the clock's accent in the system colour instead of red, the dark theme's grey instead of navy, notifications in the accent; the weather and world clock cards, the landscape stage, the tab rail and the actions at the foot, lists at the foot with the alarm header, the timer's keypad, the standby screen saver |
| `patches/packages_inputmethods_LatinIME/` | the keyboard follows light and dark mode while no theme is picked in its settings |
| `patches/packages_providers_DownloadProvider/` | a cookie or login a download was started with is not handed to another host the download redirects to |
| `patches/packages_apps_Settings/` | the project's design: entries on rounded cards, large titles, the search bar as a pill aligned with the list, pages in the home screen's column; on the Display page the brightness slider and the light, dark or automatic look; battery protection under Battery; the edge gesture settings under System, Gestures - which also makes that page show on a device without any of Android's own gestures |
| `patches/packages_apps_SetupWizard/` | the first start: welcome with the language, Wi-Fi, the time zone on a world map, location, screen lock, a last page; no SIM pages and no LineageOS features page; light and dark |
| `patches/packages_apps_Updater/` | the updater's header and list name ON-FireLOS, in the design's colours; the install through `/cache/recovery/command` and a block map, as this device has no `/misc` and TWRP cannot mount `/data` |
| `patches/packages_apps_Backgrounds/`, `Calendar`, `Contacts`, `DocumentsUI`, `Eleven`, `ExactCalculator`, `Recorder` | the project's design ([DESIGN.md](DESIGN.md)): flat bars, the surface tones, cards, large titles where a list has one |
| `patches/packages_overlays_Lineage/` | the dark theme's floating surfaces in the design's tone instead of grey |
| `patches/vendor_amazon_suez/` | `Android.bp` for the blobs, and four blob patchers: the gralloc loop, the `GraphicBuffer` allocation size in the camera and encoder blobs, the library paths in MediaTek's codec driver, and guiext-server's buffer queue pool |
| `patches/vendor_lineage/` | branding, the boot animation step that composites the device logo, the Updater pointed at this project's releases, the accent colour taken from the logo, the design's corner radii, the ringtones, LockClock and Exchange left out |
| `patches/build_make/` | telephony packages removed from the core product lists; Email and Traceur left out |
| `patches/external_noto-fonts/` | the serif CJK font left out ([DEFECTS.md](DEFECTS.md), the largest single file under `/system/fonts`) |
| `patches/system_ca-certificates/` | the root certificates brought up to Mozilla's list of October 2026 |
| `patches/system_sepolicy/` | `adbd` on the `dac_override` allowlist |
| `patches/lineage-sdk/` | a default for the status bar battery percentage, so the device overlay can turn it on |

The only binaries in any patch are small images: the boot animation's logo
(338 bytes) and lettering, the charger screen's digits, the keyboard's
recoloured enter-key backgrounds and two corner nine-patches of the gallery, none
larger than 25KB. The two
`libskia` stubs are built straight into the device tree by
`scripts/build-skia-stub.sh`, the vendor blobs come off your own device, and
the browser's `androidx.webkit` (Apache 2.0) is fetched from Google's Maven
repository by `scripts/fetch-androidx-webkit.sh` and checked against its
SHA-256. The gallery's video player takes ExoPlayer 2.19.1 and Guava (both
Apache 2.0) the same way, by `scripts/fetch-exoplayer.sh`; it also strips the
Java 8 type annotations out of their class files, which the build's jarjar
cannot read and which nothing uses at run time
(`scripts/strip-type-annotations.py`). The apps that stand in for Google's -
Droid-ify, Aurora Store and eSpeak NG (all GPL-3.0, built by F-Droid from
their published sources) - come from F-Droid's repository the same way, by
`scripts/fetch-store-apps.sh`, each checked against a SHA-256 pinned in the
script, into `device/amazon/suez/prebuilt-apps/`. Droid-ify and Aurora keep
F-Droid's signature, so Droid-ify can update them; eSpeak's library has to be
stored uncompressed, which breaks that signature, so it is signed with the
ROM's key and updated with the ROM.

ON-FireAI, the assistant, is source in this repository (`onfireai/`, see
[ON-FireAI](ONFIREAI.md)); `scripts/onfireai-into-tree.sh` places it in the tree and the
device's makefile adds it when it is there. Its wake word needs sherpa-onnx's
Android library (Apache-2.0, with onnxruntime, MIT) and a keyword spotting model
(Apache-2.0); `scripts/fetch-onfireai-kws.sh` fetches both, checked against pinned
SHA-256, into `onfireai/jni/` and `onfireai/assets/kws/` (not in the repository). The
same script fetches the speech recognition model, NVIDIA's
`stt_de_fastconformer_hybrid_large_pc` (CC-BY-4.0, about 130MB), into
`onfireai/assets/asr/` with a NOTICE naming its source and licence.
Without them ON-FireAI builds and works, without a wake word.

## Signing keys

The build signs with its own keys, not with AOSP's public test key. Generate
your own before the first build — they are **not** in this repository and
must not be:

```sh
mkdir -p vendor/lineage-priv/keys && cd vendor/lineage-priv/keys
SUBJ='/C=../ST=../L=../O=ON-FireLOS/OU=ON-FireLOS/CN=ON-FireLOS'
for k in releasekey platform shared media; do
    echo "" | ../../../development/tools/make_key "$k" "$SUBJ"
done
cp releasekey.pk8 testkey.pk8 && cp releasekey.x509.pem testkey.x509.pem
```

`testkey` is needed because `system/sepolicy/private/keys.conf` points the
`@RELEASE` tag at `$DEFAULT_SYSTEM_DEV_CERTIFICATE/testkey.x509.pem`. The
name is just a filename — the contents are your own key, not AOSP's.

Then `keys.mk` next to them:

```make
PRODUCT_DEFAULT_DEV_CERTIFICATE := vendor/lineage-priv/keys/releasekey
PRODUCT_OTA_PUBLIC_KEYS := vendor/lineage-priv/keys/releasekey
```

**Keep them, and never commit them** - to this repository or any other.
Lose them and you can never sign an update for a device running this build
again: the system updater on those devices refuses every zip not signed with
the key they carry. Changing the keys later forces a `/data` wipe on every
installed device, so decide before you hand the ROM to anybody.

The fingerprint then reads `dev-keys` instead of `test-keys`, releases
included: they are built with `mka bacon` on these keys, not re-signed
through `sign_target_files_apks`, which is what `release-keys` stands for.

## Releases and the system updater

Settings > System > System updates (LineageOS's Updater) reads
`https://github.com/LL4nc33/ON-FireLOS/releases/latest/download/suez.json`
(`lineage.updater.uri` in `device/amazon/suez/device.mk`).
Until the GitHub repository is public it answers 404. Every GitHub release
carries three files: the ROM zip, its `.sha256`, and that list:

```sh
scripts/make-ota-json.sh ON-FireLOS-16.0-YYYYMMDD-UNOFFICIAL-suez.zip vYYYYMMDD > suez.json
```

It takes the build time from the zip's `META-INF/com/android/metadata`, so
the updater offers the release only to devices running an older build. The
zip must be signed with the same release key the installed build carries
(see above): the updater checks it against `/system/etc/security/otacerts.zip`
before it hands it to the recovery. Full zips only: incremental updates do not
work on amonet devices.

The install itself goes through amonet's TWRP, by
`patches/packages_apps_Updater/`: this device has no `/misc` in any fstab, so
Android's way of passing the recovery its command fails ("Setup BCB failed");
the updater writes `/cache/recovery/command` itself. TWRP cannot mount the
f2fs `/data` the download lies on, so the command names the block map that
uncrypt writes at shutdown, and TWRP reads the zip straight from the
partition.

## Blobs

This repository contains **no** proprietary libraries from Amazon or MediaTek.
The camera stack (`libcam*.so`, `libcamalgo.so`, `libcameracustom.so` and the
rest) is copyrighted and must not be redistributed.

`scripts/extract-blobs.sh` pulls them from your own device — the device they
are licensed on. That is the usual approach in the LineageOS world.

**Set up `git-lfs` before the first `repo sync`.** The WebView prebuilt is an
LFS object. Without the filter it checks out as a 133-byte pointer instead of
232MB, the build completes without error, and the first web page is the first
sign that anything is wrong. Check it:

```sh
ls -l vendor/.../webview.apk     # must be ~232 MB, not ~133 bytes
```

## More

Each obstacle above was found the same way: the error message named a
symptom, and the cause sat one layer below it. The patches in `patches/`
carry the result.
