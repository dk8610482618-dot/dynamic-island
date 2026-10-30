# v1.5 — motion, microphone indication and touch clearance

- The overlay requests a 120 Hz window refresh hint by default and, on Android
  15 and later, requests the view frame rate too. The preference is enabled once
  on upgrade; later user choices are retained. The system, display and power
  policy choose the actual rate.
- The expanded panel has a restrained fade/scale entrance; reduced motion and
  the system animation setting are respected. Duplicate animation-frame window
  updates that round to identical bounds are skipped.
- The media progress track has a soft glow and short luminous trail. When an
  active seekable player is running, the drawn position updates with display
  frames; reduced-motion mode uses a slower ticker.
- An orange (#FF9F0A) dot appears on the right when Android reports an
  unsilenced, recognizable microphone input. The public recording callback is
  anonymized and may omit OEM or system-only sources. This dot is best effort
  and does not replace Android's privacy indicator.
- Android does not expose reliable global camera-use or combined microphone and
  camera status to ordinary third-party applications. Green and blue dots are
  therefore not available; camera resource availability is not proof of use.
- With system-bar touch clearance enabled, position offsets can no longer move
  the capsule back under the protected system input region. Top and bottom
  cutout safe insets are included. The setting is restored on upgrade and can
  be changed afterward; “Restore touch-safe position” resets both placement
  and clearance.

Android controls system bars, the notification shade, secure screens and apps
that suppress overlays. A normal application overlay cannot force itself above
those surfaces or receive touches in their protected regions. A 120 Hz request
is not a guarantee of 120 Hz output or absence of dropped frames. ColorOS/Realme
behavior and performance require physical-device verification.

Sources: [Android AudioManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/media/java/android/media/AudioManager.java),
[AOSP recording monitor](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/audio/RecordingActivityMonitor.java),
and [Android AppOpsManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/AppOpsManager.java).
