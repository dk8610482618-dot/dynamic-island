> Historical source-archive reference. Claims and counts below are not current validation. See docs/TEST_REPORT.md, docs/FEATURES.md and docs/COMPATIBILITY_MATRIX.md for this build.

# Dynamic Island 1.1

This update builds on the supplied native Kotlin/Jetpack Compose project.

## Added

- A scrollable notification inbox with individual swipe dismissal and a dismiss button.
- An Inbox shortcut in the expanded media card when messages are waiting.
- Persistent behaviour settings: start after reboot, notification inbox/alerts, system alerts, hide in landscape, and notification alert duration (1.5–8 seconds).
- Optional call-state and nearby-device permission setup, with live tracker refresh after a grant.
- A live service status indicator and a short gesture guide.

## Fixed

- Stop is remembered after reopening the app, process recreation, reboot and accessibility-service reconnection.
- Clearing the inbox dismisses exactly its dismissible notifications in the Android shade, rather than only deleting the local list or clearing unrelated notifications.
- A fresh service receives existing notifications even when the listener previously cached identical content. Existing notifications restore quietly, without repeated arrival alerts.
- Notification group summaries no longer duplicate their child messages.
- Updated notification actions and launch intents replace cached handles; removed notifications stop their active peek.
- A background media player's metadata cannot overwrite the active player's title or artwork.
- Resuming playback restores the waveform and Playing label even without a metadata change; error states remove stale media cards.
- Media metadata now carries the player session's launch intent for the hold gesture, when the player provides one.
- Non-finite stored placement values are sanitised before rendering.
- Call-state reads use the legacy API on Android 9–11, avoiding an API 31-only method on older phones.
- Engine and host lint run separately to avoid a configuration cycle caused by nesting the host under the root Android library.
- Landscape hiding clears the overlay's old touch region.
- The triple-tap recognition window and delayed tap use the same timeout.
- The Unix Gradle wrapper has executable permissions and LF line endings.
- Unit tests load the Byte Buddy instrumentation agent at startup, avoiding self-attachment failures on constrained build hosts.
- Android Gradle Plugin versions are aligned to 8.8.2, matching the existing version catalog.

## Install and enable

1. Copy `app-debug.apk` to an Android 9 or newer phone and open it.
2. Allow installation from the app that opened the APK if Android asks.
3. Open Dynamic Island and enable Display over other apps and Notification access.
4. Tap Start Dynamic Island.
5. Accessibility is optional for screenshots and a trusted overlay. Enable call/accessory permissions only if you want those alerts.

Tap the island to expand/close it. Hold to open its source app. Swipe media sideways to change tracks. Swipe an inbox row sideways to dismiss that notification, or use Clear for the represented inbox messages. Scroll to older messages. Triple-tap to request a system screenshot with Accessibility enabled.

If an installed copy was signed using a different key, Android will reject an update. Keep your original signing key to update that installation without uninstalling it. Uninstalling an older copy removes its local settings.

## Build on Windows

Use a full JDK 17, plus Android SDK platform 34 and build tools 35.0.0 (Android Studio can install these). Extract this archive and open PowerShell in the project folder:

```powershell
.\gradlew.bat :core:test :testDebugUnitTest :lintDebug :app:lintDebug :app:assembleDebug
```

The generated APK is `app\build\outputs\apk\debug\app-debug.apk`.

On Linux/macOS use `./gradlew` for the same tasks. `local.properties`, build caches and generated output are intentionally excluded from the source archive. Configure the SDK location locally, for example through Android Studio or ANDROID_HOME.

## Practical limits

This is a debug build for direct installation, not a signed Play Store release. Phone and ROM-specific behaviour still needs device testing. Third-party overlays cannot replace Android system privacy dots. Lockscreen visibility and foreground-service survival depend on Android version and manufacturer settings. Call answer/hang-up and media controls depend on the capabilities exposed by the source app or system role; this update does not make the app a default dialer.

The original README/TODO/device notes include historical statements from the supplied project. See VALIDATION.md for checks performed for this update.
