# v1.5 validation

JDK 21, Gradle 8.13, AGP 8.10.1, Kotlin 1.9.24, Android SDK 36, build tools 35.0.0.

```
./gradlew :core:test :testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :lintDebug :app:lintDebug :app:assembleRelease :app:bundleRelease --max-workers=2
```

- 120 core and 117 engine unit tests passed, with no failures.
- Engine and host lint, debug APK/instrumentation compilation, and release
  APK/AAB builds passed. See validation.zip for raw reports.
- APK zip alignment and v3 signature verification passed. Its development
  certificate matches v1.4, allowing upgrades from that build.
- Release manifest contains no INTERNET permission, accessibility service,
  debug fixture activity, or debuggable flag.
- Common credential-pattern scan and staged whitespace checks passed.

Coverage added for user offsets and touch-safe clearance on top, side and bottom
cutouts, tiny displays, one-time upgrade preferences, and microphone policy for
silenced, unknown and identifiable audio input devices.

No physical device is attached and no KVM is available. Prior API 28 and API 36
software-emulator attempts terminated in Android system watchdog failures, so
this release has no passed device interaction, frame timing, visual, or Realme
ColorOS tests. The Android system controls display refresh rate and overlay
precedence, and no claim of exact 120 Hz, absence of jank, or visibility/touch
above protected system UI is made. Public recording observations can omit
system/vendor sources; camera and combined privacy state remain unavailable.
