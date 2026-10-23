# Lannister

A minimal, ad-free home screen for the **Fire TV Stick 4K** (Fire OS 6, Android 7.1), with an iMac-style screensaver.
Lannister is built from [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher) by aradhyas8 and keeps all of
its features. Licence: GPL-3.0 (see `LICENSE` and `NOTICE.md`).

> **Status:** the Kotlin code compiles against the Android 36 framework API, but it has not run on a real Fire TV yet.
> Build the APK with GitHub Actions (below), then check the points under "Known unknowns".

## What it adds to Plume

- **Screensaver:** slow Ken Burns photos with a large light clock, your city, and a **now playing** card (title, artist,
  cover) while music plays. Burn-in safe: the overlay drifts every minute. Your own photo folder works too.
- **Live sky:** an animated day-and-night sky, as the home-screen wallpaper (*Light/Dark wallpaper → Live sky*) and as a
  screensaver. It follows your real sunrise and sunset and shows live weather: clouds, rain, snow, fog, storms.
  *Sky speed → Fast* plays a whole day in two minutes. It is drawn with code, so it adds no image size.
- **Replaces the Amazon home screen:** Fire OS 6 does not let apps take the Home key, so Lannister watches the system
  log for the line Fire OS writes when it starts the Amazon home screen, and opens Lannister over it (the same method as
  the Launcher Manager app). The Amazon home screen may flash for a moment first. To see the Amazon home on purpose, use
  *Lannister settings → Open Amazon home screen*. The older accessibility-service route is kept for other devices.
- **Fire OS 6 support:** minSdk 25, screen-size fit, apps found under both launcher categories, no features that need
  Android 8 or newer (Continue Watching and HDMI tiles stay empty on a Fire Stick).
- No self-update, no paintings download, no network use except weather, poster images and the location lookup.

Everything else is Plume: apps row, edit/sort/hide, light/dark themes, wallpapers (phone upload, folders), PIN lock,
weather.

## Build

1. Push this folder to a new GitHub repository.
2. Open **Actions → Build APK → Run workflow**. Download `Lannister-apk` when it finishes.
3. The APK is signed with the bundled sideload key in `signing/`. Change it if you share the APK.

## Install on the Fire TV Stick

1. Settings → My Fire TV → About: select the device name seven times. Then Developer Options: turn on **ADB debugging**
   and **Apps from unknown sources**.
2. Install the APK with the *Downloader* app or `adb install Lannister-1.0.apk`.
3. From a computer on the same network:

   ```sh
   adb connect <fire-tv-ip>:5555
   adb shell pm grant dev.lannister android.permission.WRITE_SECURE_SETTINGS
   adb shell pm grant dev.lannister android.permission.READ_LOGS
   ```

   Then force-stop and reopen Lannister (`adb shell am force-stop dev.lannister`, then open it), so it picks up READ_LOGS.

4. Open Lannister, then in **Lannister settings**:
   - **Home button:** turns on the Home service. Press Home to test it.
   - **Screensaver:** choose *Lannister photos*. Use **Test screensaver** to preview.
   - **Now playing:** turns on notification access for the media card.
   - **Show location:** shows or hides the city.

The grant lets Lannister switch these on by itself. Without it, Fire OS may hide them; use the adb grant.

## Known unknowns (check these first)

- Whether the log line Lannister watches for looks the same on your software version. If Home still opens only Amazon's
  screen after the READ_LOGS grant, send the output of `adb logcat -d | grep category.HOME | tail`.
- Whether Fire OS accepts the screensaver component through the adb grant on your build.
- Amazon can change launcher behaviour in any update.
- The Live sky animation (about 30 frames a second) is untested on a Fire Stick 4K; if it stutters, tell me and I will lower the detail.
- The sky uses the TV's clock for the time of day. Set the TV's time zone to match your city.

## Credits and licence

Plume Launcher © 2026 aradhyas8, GPL-3.0. Screensaver photos: Unsplash, see
`app/src/main/assets/screensaver/CREDITS.txt`. Logos: Simple Icons (CC0). HDMI icons: Material Symbols (Apache 2.0).
