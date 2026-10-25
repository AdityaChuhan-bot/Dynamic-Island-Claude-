# Dynamic Island for Android

A native Kotlin Android app that displays an iOS-style floating pill for currently playing media, calls, and timer notifications. The pill supports spring-based collapsed, compact, and expanded states, tap-to-toggle, and drag gestures.

**Build status:** source is uploaded; the Android build has not yet been verified.

## Build a debug APK

1. Open the repository's **Actions** tab.
2. Select **Build Debug APK** and choose **Run workflow** if a run is not already underway.
3. Open the completed run and download the `dynamic-island-debug-apk` artifact.

The workflow uses JDK 17 and Gradle 8.7 and runs `gradle assembleDebug`. No Gradle wrapper JAR is required.

## First launch

1. Open the app and allow **Display over other apps**.
2. Enable **Notification access** for Dynamic Island in Android Settings.
3. Return to the app and tap **Start Island**.

Notification/media information is read locally through Android's notification-listener and media-session APIs. The app does not upload notification content to a server.

## Project structure

- `MainActivity.kt` — permission setup and start/stop controls.
- `OverlayService.kt` — foreground service and top-centered overlay window.
- `IslandView.kt` — custom pill drawing, spring animation, touch and drag gestures.
- `IslandNotificationListener.kt` — media session, call, and timer notification detection.
- `IslandRepository.kt` — in-process state shared between the listener and overlay.
- `.github/workflows/build.yml` — GitHub Actions debug APK build.

## Compatibility

Minimum Android version: Android 8.0 (API 26). Target SDK: 34. Test on your device before relying on the app; behavior may vary with Android version and manufacturer restrictions.
