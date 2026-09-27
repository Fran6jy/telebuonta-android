# Telebuonta for Android

A teleprompter that records at the same time. The script scrolls in a panel near the top of the screen, right beside the lens, with the camera preview running underneath — so your eyes stay on camera instead of drifting to a second device.

Native Android, so it records through CameraX rather than a browser: real resolution selection up to 4K, hardware encoding, and files saved straight to your gallery.

There is a web version of the same idea at [telebuonta.vercel.app](https://telebuonta.vercel.app), built first. It works, but Chrome and Safari cap what a web page can pull from the camera. This is the version without that ceiling.

## Features

- **Camera preview with the script overlaid**, recorded together in one take
- **The script is not burned into the video** — it's an overlay you can see, not something your viewers will
- **Resizable, movable panel** — drag the arrows to change its height, the cross to move it down the screen
- **Set the speed from a target time** — say the take should run 75 seconds and it works out the scroll rate from the actual length of your script
- **Timing against an estimate** — counts up against the script's spoken length at 140 words per minute, turning amber if you overrun
- **Progress bar** along the panel so you can see how much is left
- **Multiple named scripts**, with word count and spoken-time estimate while you edit
- **Pause and resume** mid-recording without splitting the take
- **Microphone level meter** while you set up, so you find out the mic is dead before the take rather than after
- **Countdown** before recording starts
- **Capture quality** — 720p, 1080p or 4K, using whatever the device actually supports
- **Front and rear camera**
- Screen stays awake throughout
- Recordings land in `Movies/Telebuonta` and appear in your gallery

## Installing it

Every push builds a debug APK in CI.

1. Open the [Actions tab](../../actions), pick the most recent successful run
2. Download the `telebuonta-apk` artifact and unzip it
3. Copy the APK to your phone and open it
4. Android will ask you to allow installing from that source — this is expected for an app that isn't from the Play Store

It's a debug build, so it isn't signed with a release key. That's fine for installing on your own device.

## Building it yourself

Requires JDK 17 and the Android SDK (both come with Android Studio).

```bash
./gradlew assembleDebug
```

Or open the folder in Android Studio and press Run.

## Requirements

- Android 10 (API 29) or newer
- Camera and microphone permissions

## How the scroll speed works

Speed is expressed as density-independent pixels per second: speed 1 moves 20dp/s, speed 5 moves 100dp/s. "Set scroll speed to match" measures the rendered height of your script, divides by your target duration, and picks the nearest whole speed. That means it adapts to font size and panel width rather than assuming anything.

The spoken-time estimate uses 140 words per minute, which is an unhurried presenting pace. If you naturally speak faster, the target will read slightly long.

## Stack

Kotlin, CameraX, view binding. No third-party libraries beyond AndroidX.

## Licence

MIT
