# v1.3 validation

Toolchain: Gradle 8.13 (distribution SHA-256 verified), JDK 21, AGP 8.10.1,
Kotlin 1.9.24, Compose compiler 1.5.14/BOM 2024.06, SDK 36, build-tools 35.0.0.

Executed:
```
./gradlew :core:test :testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :benchmark:assembleBenchmark :app:assembleRelease :app:bundleRelease :lintDebug :app:lintDebug
```
- Core: 113 passed, 0 failed, 0 skipped.
- Engine: 107 passed, 0 failed, 0 skipped. Total: 220 passed.
- Engine and host Android lint: passed with nonfatal warnings (see retained reports).
- Debug app and instrumentation APKs: compiled.
- Benchmark instrumentation APK: compiled; profile/benchmark producer app compiled separately.
- Release: R8 code/resource shrinking, non-debuggable APK and unsigned AAB compilation passed.
- Common-credential-pattern scan: passed. This is not an exhaustive penetration audit.
- Screenshot comparator smoke: equal images accepted and changed pixels rejected.

Coverage includes spring/motion math, single/secondary placement, collision/RTL/rotation,
visible-activity gesture routing, lock redaction/unlock restoration, listener access-loss
clearing, media capabilities, preferences, custom priority with call protection,
notification grouping/reconciliation and strict timer-cancel eligibility.

Runtime attempt (BLOCKED):
- Installed checksum-verified emulator 37.3.6 and API 36 x86_64 system image.
- No /dev/kvm was available. Used the emulator's explicit TCG software CPU mode.
- Android eventually reported boot_completed=1 and package/activity services present.
- Streamed debug APK install failed with package-service Broken pipe (32).
- A non-streaming ~2 MB release APK transferred successfully, but installation failed
  because the Android package service had disappeared.
- Crash log showed DeadSystemException in launcher, media, permission controller and
  other system apps. The app was not installed and no instrumentation test executed.
- Stopped the failed emulator. This is environment evidence, not an app-test failure or pass.

Not run: UI cases, reviewed golden captures, physical touch/lockscreen/foldable/OEM
checks, real-player/multi-SIM checks, Macrobenchmark collection and baseline-profile
generation. No APK is called production-ready solely because build/unit checks passed.
GitHub CI was configured but not pushed or executed here.

Relevant code/automation: app/src/androidTest, app/src/debug, benchmark/, tools/compare-screenshots.py,
tools/generate-baseline-profile.sh, tools/check-secrets.py, .github/workflows/android.yml.

Artifact verification: zipalign passed; apksigner v3 verification passed; packaged version 1.3 (4), min SDK 28/target 36, non-debuggable, no Internet/accessibility service or debug-test activity in the release manifest.
