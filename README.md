<p align="center"><img src="screenshots/bootanimation.png" width="320" alt="ON FIRE, logo, LINEAGE OS"></p>

# ON-FireLOS

A custom ROM for the **Amazon Fire HD 10 (2017)**, codename `suez`, based on
LineageOS 16 (Android 9).

- **Android 9** instead of Fire OS 5
- **New design:** large titles, cards, light and dark
- **Camera** (photo, video), Bluetooth, 5 GHz Wi-Fi
- **Chromium 138:** ad blocking, web apps, reader, picture in picture
- **YouTube** smooth up to 1080p
- **Clock with weather**, *Today* widget
- **Gallery** with Immich backup
- **No Google needed:** Droid-ify, Aurora Store
- **No Amazon:** no apps, no lock screen ads
- **System updates** built in
- **Fast:** boots in 25 s, 1 GB compressed RAM
- Game controllers, edge swipes, battery protection

<p align="center">
  <img src="screenshots/home.png" width="240" alt="Home screen: the long-press menu with system usage open, the Today widget with weather, time and date">
  <img src="screenshots/settings.png" width="240" alt="Settings with a large title and cards">
  <img src="screenshots/display.png" width="240" alt="Display settings: light, dark or automatic, brightness slider">
</p>
<p align="center">
  <img src="screenshots/quick-settings.png" width="500" alt="Quick settings: tiles, brightness slider and the light/dark button">
</p>
<p align="center">
  <img src="screenshots/tablet-about.png" width="300" alt="About tablet: Android 9, LineageOS 16.0, build of 2026-11-08">
</p>

## Requirements

- Fire HD 10 2017 (KFSUWI), **unlocked with TWRP** via
  [amonet](https://xdaforums.com/t/unlock-root-twrp-unbrick-fire-hd-10-2017-suez.3913639/)
- A computer with [`adb`](https://developer.android.com/tools/releases/platform-tools)

## Install

1. Download the ROM from [Releases](../../releases).
2. TWRP: *Wipe → Advanced Wipe → Data* (erases everything).
3. `adb push ON-FireLOS-<version>-suez.zip /tmp/`, then *Install* it.
4. Reboot. The first start takes 5–10 min.

Apps come from **Droid-ify** (F-Droid catalogue) and **Aurora Store** (Play
catalogue, anonymously), both built in. No Google account needed.

**Updates:** *Settings → System → System updates*. From an older build:
install the new zip in TWRP, no wipe.

<details>
<summary>Optional: Play Store</summary>

Flash [OpenGApps pico](https://sourceforge.net/projects/opengapps/files/arm64/20220503/)
(`open_gapps-arm64-9.0-pico-20220503.zip`) right after the ROM, before the
first start. Push a `gapps-config.txt` containing `GoogleTTS` to `/tmp/`
first, otherwise it doesn't fit. Then register the device once on Google's
*Uncertified device* page.
</details>

## Known limits

- No security updates from Google: Android 9, LineageOS 16 and the 3.18 kernel are no longer maintained.
- Chromium 138 is the last WebView for Android 9: no newer engine yet.
- Streaming video may play in SD only (Widevine L1 untested).
- Heavy WebGL pages can freeze the GPU driver;
  large `backdrop-filter: blur` areas make scrolling stutter. Both are limits of
  the PowerVR driver, not fixable in the ROM.
- Untested: USB OTG.

## Build it yourself

Ubuntu 22.04, as root, source tree at `/root/los16`. Create your own signing
keys first (LineageOS wiki: *Signing builds*).

```sh
scripts/setup-buildenv.sh
scripts/sync-sources.sh
scripts/apply-patches.sh
scripts/apply-device-patches.sh
scripts/extract-blobs.sh proprietary-files.txt   # tablet still on Fire OS
device/amazon/suez/extract-files.sh /path/to/stock-dump
scripts/apply-patches.sh
scripts/build-skia-stub.sh
scripts/fetch-androidx-webkit.sh
scripts/fetch-exoplayer.sh
scripts/fetch-store-apps.sh
cd /root/los16 && source build/envsetup.sh && breakfast suez && mka bacon
```

## Credits and licence

[LineageOS](https://github.com/LineageOS), the
[lineage16-suez](https://github.com/lineage16-suez) device tree and kernel,
[amonet](https://github.com/chaosmaster/amonet) (xyzz, chaosmaster and
contributors), TWRP, [OpenGApps](https://opengapps.org). Weather data by
[Open-Meteo.com](https://open-meteo.com) (CC BY 4.0), ad blocking with
[StevenBlack's hosts](https://github.com/StevenBlack/hosts) (MIT). Built in:
[Droid-ify](https://github.com/Droid-ify/client),
[Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore) and
[eSpeak NG](https://github.com/espeak-ng/espeak-ng), each under its own licence
(GPL-3.0), as published on F-Droid.

Own code Apache-2.0 ([LICENSE](LICENSE)), kernel patches GPL-2.0. This
repository holds no proprietary binaries; a build pulls the drivers from your
own tablet. The ROM zip, like any ROM for this device, contains Amazon's and
MediaTek's drivers.
