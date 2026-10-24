# MassCut AI — Android native prototype v0.2.0

A native Android Studio project that moves beyond the original browser edit-plan prototype. This build is designed to:

- Pick multiple local videos using Android's system document picker.
- Read video durations locally and show selected clips.
- Reorder and remove clips.
- Set start/end trim points per clip.
- Concatenate trimmed clips and export an MP4 on-device using AndroidX Media3 Transformer.
- Save the finished MP4 to `Movies/MassCut AI` and share it.
- Avoid uploading user videos or requesting broad storage permissions.

## Honest scope

This is an early native source project, not a verified APK. This execution environment has no Android SDK/Gradle installation and cannot reach external package repositories, so I could not compile or test an APK here. The project must be built and tested in Android Studio or CI before installation.

The first renderer merges and trims clips. It does **not yet** burn in title text, add music, beat-sync cuts, apply transitions, or crop to 9:16/1:1. The style selector is currently a preset placeholder. Different input formats/codecs may fail to export on some phones; start with standard MP4/H.264 footage and short clips.

## Build an APK in Android Studio

1. Install Android Studio (free) on a computer and open this project folder.
2. Let Gradle sync and install Android SDK Platform 35 if prompted.
3. Select **Build → Build APK(s)**.
4. The debug APK should appear at `app/build/outputs/apk/debug/app-debug.apk`.
5. Install that APK on your Android phone and test with short, ordinary MP4 clips first.

## Free CI build

A GitHub Actions workflow is included. Push the project to a GitHub repository and run the workflow; it will attempt a debug build and upload the APK as a downloadable workflow artifact. The workflow also needs internet access to fetch Gradle dependencies.

## Tech

- Kotlin / Android native UI
- AndroidX Media3 Transformer 1.5.1
- Minimum Android API 26
- No paid APIs or cloud rendering
