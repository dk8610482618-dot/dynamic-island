# v1.4 validation

Build: versionName 1.4, versionCode 5; min SDK 28, compile/target SDK 36.
Toolchain: JDK 21, Gradle 8.13, AGP 8.10.1, Kotlin 1.9.24, Compose compiler
1.5.14 / BOM 2024.06, Android build-tools 35.0.0.

## Passed

Executed on the final source:

```sh
./gradlew :core:test :testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :lintDebug :app:lintDebug :app:assembleRelease :app:bundleRelease --max-workers=2
```

- Core: 116 tests, zero failures/errors/skips.
- Engine: 112 tests, zero failures/errors/skips. Total: 228.
- Engine and host lint passed with nonfatal warnings.
- Debug app and instrumentation test APKs compiled.
- R8/resource-shrunk release APK and unsigned AAB built successfully.
- APK zip alignment and v3 signature verification passed.
- APK signing certificate matches the delivered v1.3 development certificate.
- Packaged manifest has no INTERNET permission, accessibility service, debug
  fixture activity or debuggable flag.
- Common credential-pattern scan and git diff whitespace check passed.

New regression coverage includes expanded geometry on both side cutouts and small
viewports, open/pause/close state transitions, notifications arriving during music
controls, notification-to-inbox routing, deliberate secondary selection and call
preemption, and lock/unlock collapse.

## Device test attempts

A checksum-verified API 28 x86_64 image was installed on emulator 37.3.6. This
workspace has no /dev/kvm, so the emulator used software CPU emulation (TCG),
720 × 1280 display, 240 dpi. Both debug and instrumentation APKs installed.

The full five-test suite started but Android's system watchdog killed system_server
during rotation/font configuration. The instrumentation result was
`INSTRUMENTATION_ABORTED: System has crashed.` The log shows ActivityManager and
WindowManager lock waits and DeadSystemException affecting the app and system apps.
After restarting, the isolated real-overlay/media test also started but was aborted
by another system watchdog failure. Neither run completed an assertion result.
These are blocked runtime checks, not passing device tests.

A third attempt used the ANGLE/SwiftShader (`swangle`) renderer and reinstalled
the final debug APK. The isolated test started, but the emulator displayed
“System UI isn’t responding” and made no assertion progress. The stalled emulator
was stopped. A screen capture of the system error is retained in validation.zip;
it is not an app screenshot or a visual acceptance result.

The test source exercises taps on the actual WindowManager overlay above the
launcher, real MediaSession play/pause/next/previous callbacks, close/outside taps,
compact bounds restoration, permission revocation, and screenshots. Those checks
are not validated merely because the instrumentation APK compiled.

The existing GitHub Actions device job targets an API 35 emulator with KVM. Its
outcome is separate from local results and is not claimed here.

## Still requires device verification

Physical Realme/ColorOS behavior, overlay priority over ordinary apps on that
firmware, notch touch delivery, lock-screen visibility, rotations, actual music
apps, performance/refresh rate, and OEM scanner acceptance remain unverified.
No normal app can force its overlay above protected Android system UI or an app
that intentionally suppresses overlays. No production signing key was supplied;
the downloadable APK remains development-signed and the AAB is unsigned.
