# Dynamic Island

Native Kotlin / Compose camera-adjacent activity capsule for Android 9 and later.

## Research parity work

The two documents in [`research papers/`](research%20papers) specify Apple Dynamic
Island behaviour to reach parity on Android. This branch closes the nine gaps that
remained after the imported base — see
[`docs/FEATURE_TRACEABILITY.md`](docs/FEATURE_TRACEABILITY.md) for every research
requirement mapped to its implementation, and
[`docs/PARITY_GAP_WORKLIST.md`](docs/PARITY_GAP_WORKLIST.md) for what was missing
and why.

| Gap | Added |
| --- | --- |
| G1 | Swiping a transient alert away suppresses **that instance**, never the activity underneath it |
| G2 | Alerting updates auto-expand for 1.8–3.0 s, then settle back — without interrupting a long press |
| G3 | Per-app overlay suppression and full-screen auto-hide (`hideInFullscreenApps` was a dead setting) |
| G4 | Mini status bar with a **ticking** clock, real battery and signal bars (the clock was frozen) |
| G5 | Liquid Glass as a real material, with no glass-on-glass stacking, WCAG contrast and the 8 dp grid |
| G6 | Foreground-service watchdog with restart backoff that stops after refused exemptions |
| G7 | FFT-driven waveform, capability-gated, with an honestly labelled synthetic fallback |
| G8 | SharePlay / Live Captions / Live Translation state, shown only when genuinely known |
| G9 | Output routing selector that never offers a toggle Android will not honour |

## Browser prototype

`ref_project/` holds a runnable prototype of the same decision logic, so the
behaviour can be seen without an Android build:

```sh
cd ref_project
npm install
npm test     # 70 engine tests, mirrors the :core policies
npm run dev  # interactive simulator
```

`src/lib/islandGapPolicies.ts` mirrors the new Kotlin `:core` policies
constant-for-constant; both suites assert the same behaviour.

## Honesty limits

Some research items must not be built as specified — a global camera privacy dot,
drawing above the status bar's icons, full dialler call control, rerouting another
app's media output, and fabricated SharePlay state. Each is listed with its
platform reason in [`docs/FEATURE_TRACEABILITY.md`](docs/FEATURE_TRACEABILITY.md)
§8 rather than silently omitted or faked.

## Original notes


[Download v1.5 APK](https://github.com/Darshan8610/Dynamic-Island/raw/refs/heads/main/deliverables/v1.5/dynamic-island-v1.5.apk)
· [Source and reports](deliverables/v1.5)

Tap the capsule to expand music controls in place: artwork, track/artist,
play/pause, previous/next and seeking when the player supports them. Close with
the chevron, an upward swipe on the header or a tap outside. The compact capsule
keeps the same opaque black size across music and notifications.

The island uses display-over-other-apps permission and a foreground service.
Notification access supplies media and alerts. Accessibility access is not used.
Android retains priority for system bars, protected screens and apps that hide
overlays. On ColorOS/realme UI, allow background activity in the app's battery
settings if the service is stopped by the phone.

Build with JDK 17/21 and Android SDK 36:

```sh
./gradlew :core:test :testDebugUnitTest :app:assembleRelease :app:bundleRelease :lintDebug :app:lintDebug --max-workers=2
```

The downloadable APK uses the existing development signing identity. Publisher
signing can be supplied through the environment variables documented in
[release checklist](docs/RELEASE_CHECKLIST.md). The unsigned AAB is also included.

See [v1.5 changes](docs/V1_5_CHANGES.md), [validation](docs/TEST_REPORT.md) and
[compatibility](docs/COMPATIBILITY_MATRIX.md) for verification and device limits.
