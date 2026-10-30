> Historical source-archive reference. Claims and counts below are not current validation. See docs/TEST_REPORT.md, docs/FEATURES.md and docs/COMPATIBILITY_MATRIX.md for this build.

# Version 1.1 validation

Validated on 29 October 2026.

## Build

A full JDK 17, Gradle 8.13, Android Gradle Plugin 8.8.2, Android SDK platform 34 and build tools 35.0.0 were used.

The final command completed successfully:

```text
./gradlew :core:test :testDebugUnitTest :app:assembleDebug :lintDebug :app:lintDebug
```

The final invocation reused previously successful unchanged task outputs. The plugin upgrade was preceded by a clean rebuild. Engine and host lint are deliberately run as separate tasks because recursively reporting the ancestor engine from the nested app causes an internal lint configuration cycle. Both modules are checked. The Android-free core is covered by its JVM tests rather than Android lint.

## Unit tests

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Core | 99 | 0 | 0 | 0 |
| Engine | 108 | 0 | 0 | 0 |

Total: **207 tests passed**. Nine regression tests were added for scoped notification clearing/dismissal, snapshot restoration, media ownership/resume, notification disabling and preference persistence/bounds.

## Android lint

| Module | Errors | Warnings | Information |
| --- | ---: | ---: | ---: |
| Engine | 0 | 31 | 3 |
| Host | 0 | 22 | 0 |

Lint completed without internal exceptions. Remaining warnings include style recommendations, deprecated APIs and the existing reflection fallback used for touch pass-through on Android 9–12. A lint-detected unguarded API 31 call was fixed with a legacy fallback for older phones.

## APK

- Package: `com.dynamicisland.host`
- Version: `1.1` / version code `2`
- Minimum SDK: `28` (Android 9)
- Target SDK: `34`
- Launcher: `com.dynamicisland.host.MainActivity`
- APK signature: verified; APK Signature Scheme v2
- Size: 21,641,818 bytes
- SHA-256: `2e054eadc980ad03301a075830b9946defa63acf027488f55b7454fd53ac9321`

## Scope of verification

This verifies compilation, automated JVM tests, lint, manifest metadata and APK signing. No physical phone or Android emulator was available for this update. Overlay touch pass-through, camera alignment, notification access, gestures, media-app integrations, Bluetooth, phone-call alerts and OEM background/lockscreen behaviour still require device testing. Historical device-test statements in the supplied README/TODO are not new test results for this build.

This is a debug build for direct installation. A production release requires your own release signing configuration.
