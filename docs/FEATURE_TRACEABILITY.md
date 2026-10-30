# Feature traceability — research → implementation

Every feature named in the two documents under `research papers/`, mapped to where
it lives and whether it was already in the imported base or added here.

Base: `Darshan8610/Dynamic-Island@a22dccb` (see `docs/IMPORT_PROVENANCE.md`).
Added in this branch: gaps **G1–G9** in `docs/PARITY_GAP_WORKLIST.md`.

Status key: **base** = already present in the import · **added** = new in this
branch · **partial** = wired but with a stated platform limit · **won't do** =
deliberately not implemented, with the reason.

## 1. Interaction and gestures

| Research requirement | Status | Where |
| --- | --- | --- |
| Tap compact/minimal opens the source app (`widgetURL` equivalent) | base | `core/geometry/ExperienceOptions.kt` → `GesturePolicy.resolvePrimaryTap` |
| Long press ~450 ms expands | base | `GesturePolicy.resolvePrimaryHold` |
| Existing tap-to-expand kept as a selectable Android mode | base | `InteractionMode.ANDROID_CLASSIC` |
| Tap outside / swipe up collapses | base | `core/motion/ScrubCollapseController.kt`, `DynamicIslandView.kt` |
| 1:1 finger-scrubbed collapse with release velocity | base | `ScrubCollapseController.interpolate`, `shouldCollapseOnRelease` |
| Pre-expansion elastic press compression (≈0.985) | base | `FixedIslandCapsule.kt` press scale |
| Horizontal swipe cycles focus across 2–3 activities | base | `IslandUserCommand.CycleActivityFocus`, `RelevanceArbitrator.focusOffset` |
| 44×44 dp minimum hit targets | base | `IslandExpandedPanel.kt` close target |
| **Swipe away a transient alert suppresses that instance, not the underlying activity** | **added** (G1) | `core/presentation/TransientAlertPolicy.resolveSwipeDismissal`; gesture branch in `DynamicIslandView.kt`; arbiter `DismissAlertPeek` |
| Region-specific deep links inside the expanded card | base | `ExpandedRegionData.actions` → `IslandAction.pendingIntent` |

**Bug fixed while wiring G1:** `isMediaSwipe` classified `AlertPeek` as a passive
pill that follows the media session, so a horizontal swipe on a transient alert
**skipped the current track** and left the alert on screen. An `AlertPeek` branch
now runs before the media case.

## 2. Presentation states and layout

| Research requirement | Status | Where |
| --- | --- | --- |
| Idle / Compact / Minimal / Expanded states | base | `IslandState` in `engine/core/model/IslandModels.kt` |
| `compactLeading` + `compactTrailing` slots | base | `FixedIslandCapsule.kt` |
| Zero internal padding at the cutout, concentric outer margins | base | `core/geometry/CapsuleAnchor.kt`, `SquircleGeometry.kt` |
| Attached + detached minimal pair (mitosis), 8 dp gap | base | `core/geometry/SecondaryBubblePlacement.kt` |
| Up to 3 concurrent activities | base | `maxConcurrentActivities`, `RelevanceArbitrator.chooseVisible` |
| Minimal droplet shows live telemetry, not a static logo | base | `IslandSlotContent.Progress` / `Chronometer` / `AudioWaveform` |
| Expanded 4-region grid (leading/trailing/center/bottom) | base | `core/geometry/ExpandedRegionLayoutSolver.kt` |
| `.belowIfTooWide` adaptive wrapping + region priority | base | `ExpandedRegionLayoutSolver.VerticalPlacement` |
| 160 dp maximum expanded height | base | `ExpandedRegionLayoutSolver`, `LiquidGlassPolicy.expandedHeightDp` |
| **Transient "alerting update" auto-expansion (1.8–3.0 s)** | **added** (G2) | `TransientAlertPolicy.resolveAutoExpand`; `IslandPriorityArbiter.triggerAlertPeek` |
| Landscape width-limited 28 dp condensed templates | base | `LandscapeBehavior.ADAPTIVE_LIMITED_WIDTH`, `resolveWidthLimitedCompact` |
| Leading-only fallback when both slots cannot fit | base | `WidthLimitedCompactSpec.showTrailing` |
| Foldable / Duo posture, no hard-coded top-centre | base | `DevicePosture`, `IslandPlacement`, `HardwareCutoutDetector` |
| **Immersive mini status bar: live clock, signal bars, battery** | **added** (G4) | `core/system/MiniStatusBarPolicy.kt`, `MiniStatusBarSource.kt`, `Ticker.rememberMiniBarClock`, `FixedIslandCapsule.MiniSignalBars` |
| Quick-control shortcuts + screenshot/lock triggers | partial | `MiniStatusBarPolicy.defaultShortcuts`; screenshot trigger needs the optional accessibility service, lock trigger is not available to an ordinary app |

**Bug fixed while wiring G4:** the clock was built with
`remember { SimpleDateFormat("HH:mm").format(Date()) }`, so it was captured on
first composition and displayed a **frozen minute for the lifetime of the
overlay**. The battery glyph was a constant green icon regardless of charge.
Both now come from observed state, and the clock recomposes on the exact minute
boundary (`millisUntilNextClockTick`) rather than polling — which is what doc 1's
"zero recurring wakeups solely for static island content" requires.

## 3. Arbitration and lifecycle

| Research requirement | Status | Where |
| --- | --- | --- |
| Priority ladder (call 1000 → system transient 150) | base | `core/arbitration/RelevanceArbitrator.kt` |
| `relevanceScore` with FIFO tie-break | base | `RelevanceArbitrator.Candidate`, `IslandActivity.compareTo` |
| Tier 0 critical preemption | base | `IslandPriorityArbiter` call handling |
| Tier 3 stale de-emphasis | base | `STALE_PENALTY`, `IslandActivity.isStale` |
| `staleDate` watchdog | base | `core/activity/ActivityLifecyclePolicy.evaluate` |
| 8 h active ceiling / 4 h post-end retention / 4 KB payload cap | base | `ActivityLifecyclePolicy` constants |
| Dismissal policies (default / immediate / after timestamp) | base | `ActivityLifecyclePolicy.DismissalPolicy` |
| **Update with no new information must not re-expand** | **added** (G2) | `AlertConfiguration.isRepeatOfPreviousPayload`, `payloadSignature` |
| **Quiet hours must not block a critical alert** | **added** (G2) | `resolveAutoExpand` + `isQuietHoursActive` |
| **Auto-expand must never interrupt a user's long press** | **added** (G2) | `expansionIsUserHeld`, `expandByUser`/`collapseByUser` in the arbiter |

## 4. Activity sources and presenters

| Research requirement | Status | Where |
| --- | --- | --- |
| Android 16 `Notification.ProgressStyle` Live Update ingestion | base | `engine/ingestion/notifications/LiveUpdateProgressParser.kt` |
| Navigation presenter (turn icon, distance, road, ETA) | base | `NavigationExpandedContent.kt` |
| Journey/delivery presenter (milestones, ETA) | base | `JourneyExpandedContent.kt` |
| Download presenter (progress ring, throughput) | base | `DownloadExpandedContent.kt` |
| Timer presenter + cancellation | base | `TimerExpandedContent.kt`, `TimerActions.kt` |
| Call presenter: incoming accept/decline, active duration, mute, speaker | base | `CallExpandedContent.kt`, `TelecomCallTracker.kt` |
| Generic notification peek + inbox | base | `AlertPeekContent.kt`, `NotificationInboxContent.kt` |
| Album-art dominant colour → waveform tint + keyline tint | base | `AlbumArtResolver.kt`, `enableKeylineTint` |
| **FFT-driven multi-bar equaliser, flattening on pause** | **added** (G7) | `core/media/WaveformSourcePolicy.kt` (FFT magnitude + log binning + attack/release smoothing), consumed in `FixedIslandCapsule.kt` |
| Waveform labelled honestly when no real spectrum exists | **added** (G7) | `WaveformSourcePolicy.resolve` → `mayBeDescribedAsRealSpectrum`; synthetic bars are driven by playback **position**, so pausing freezes them |
| Media scrub bar with seeking, elapsed/remaining | base | `MediaExpandedContent.kt`, `IslandScrubber` |
| Lyric captions where available | base | `ExpandedRegionData.lyricLine` |
| **Output routing selector (AirPlay/Bluetooth equivalent)** | **added** (G9) | `core/media/AudioRoutePolicy.kt`, rendered by `ui/components/AudioRouteRow.kt` inside `MediaExpandedContent.kt` |
| **SharePlay / Live Captions / Live Translation status** | **added** (G8) | `core/call/CallSessionFeatures.kt`, rendered by `ui/components/CallFeatureRows.kt` inside `CallExpandedContent.kt` |
| Charging / ringer-DND / Bluetooth accessory / hotspot system events | base | `SystemStateObserver.kt`, `ActivityKind.BATTERY_CHARGING` etc. |

## 5. Motion and material

| Research requirement | Status | Where |
| --- | --- | --- |
| Continuous-corner (squircle) morphing | base | `core/geometry/SquircleGeometry.kt`, `engine/ui/squircle/SquircleShape.kt` |
| Interruptible springs, retargeting mid-animation | base | `core/physics/SpringSimulation.kt`, `core/motion/DynamicIslandMotionEngine.kt` |
| Spring catalogue (response/damping per transition) | base | `core/physics/SpringCatalog.kt` |
| 2D metaball ferrofluid detachment bridge | base | `core/motion/MetaballGeometry.kt` |
| Keyline tint 1 dp perimeter border | base | `FixedIslandCapsule.kt` |
| Reduce Motion → cross-fade instead of springs | base | `MotionPreset.NONE`, `reducedMotion` |
| **Liquid Glass as a real material** | **added** (G5) | `core/material/LiquidGlassPolicy.kt`; `IslandExpandedPanel.kt` card + control surfaces |
| **No glass-on-glass stacking** | **added** (G5) | `resolveSurfaces` demotes children to `SOLID_TINT` |
| **8-point spatial grid** | **added** (G5) | `LiquidGlassPolicy.snapToGrid`, `MiniStatusBarPolicy.snapToGrid` |
| **WCAG 4.5:1 body / 3:1 large text enforcement** | **added** (G5) | `contrastRatio`, `meetsContrast`, `ensureContrast` |
| **Dynamic Type scaling bounded by the 160 dp cap** | **added** (G5) | `scaleForDynamicType`, `expandedHeightDp` |

## 6. Privacy, suppression and resilience

| Research requirement | Status | Where |
| --- | --- | --- |
| Lock-screen redaction (generic labels until unlock) | base | `core/privacy/LockStatePrivacyPolicy.kt` |
| Keyguard restricts island to closed-or-media | base | `IslandPriorityArbiter.recomputeIslandState` |
| Retained data cleared when listener access disconnects | base | `NotificationEvent.AccessRevoked` |
| No INTERNET permission; artwork resolved locally | base | `src/main/AndroidManifest.xml` |
| Per-package **notification** filtering | base | `BehaviorOptions.blockedPackages` |
| **Per-app overlay suppression ("Hide Notch in Apps")** | **added** (G3) | `core/privacy/AppSuppressionPolicy.kt`, `hiddenPackages`/`alwaysHiddenPackages` |
| **Auto-hide inside full-screen games/video** | **added** (G3) | `ForegroundAppObserver.kt` + service visibility gate |
| Microphone indicator | base | `MicrophoneRecordingPolicy.kt` (`AudioRecordingCallback`) |
| Foreground service + boot recovery | base | `BootReceiver.kt`, `startOnBoot` |
| Battery-optimisation exemption onboarding | base | `MainActivity.kt` |
| **Foreground-service watchdog (killed/wedged detection)** | **added** (G6) | `core/system/ServiceWatchdogPolicy.kt`, `service/IslandServiceWatchdog.kt` |
| **Restart backoff that stops after refused exemptions** | **added** (G6) | `backoffFor`, `MAX_EXEMPTION_PROMPTS` |

**Fake waveform fixed (G7):** the compact capsule fell back to a hard-coded
`listOf(0.45f, 0.85f, 0.6f, 0.9f)`. `AudioWaveformEqualizer` treats *any*
non-empty list as measured data and renders it verbatim — so four invented
constants were drawn as though they were real audio, contradicting that
component's own KDoc. The fallback is now resolved by `WaveformSourcePolicy`:
genuine upstream amplitudes win, otherwise position-driven synthetic bars, and
otherwise an empty list so the equalizer uses its documented honest "playing"
iconography. No `Visualizer` session is attached, so the policy never resolves
`REAL_FFT` and `mayBeDescribedAsRealSpectrum` stays `false`.

**Dead setting fixed (G3):** `hideInFullscreenApps` was declared in
`BehaviorOptions`, persisted to SharedPreferences, and **read by nothing** — it
looked like a working feature and did nothing. It is now wired through
`AppSuppressionPolicy` into the service's visibility decision.

## 7. Accessibility and localisation

| Research requirement | Status | Where |
| --- | --- | --- |
| `contentDescription` / `paneTitle` on the expanded card | base | `IslandExpandedPanel.kt` semantics |
| Polite live region for meaningful phase changes only | base | `LiveRegionMode` in `FixedIslandCapsule.kt` |
| RTL mirroring of leading/trailing and swipes | base | `latestLayoutDirection` in `DynamicIslandView.kt` |
| Large-font adaptive card height | base | `contentHeight` from `density.fontScale` |
| **Signal bars announce strength, not just presence** | **added** (G4) | `MiniSignalBars` semantics |
| **Routing rows state their limitation to screen readers** | **added** (G9) | `AudioRoutePolicy.accessibilityLabel` |
| **Call pane title as a stable state description** | **added** (G8) | `CallSessionFeatures.paneTitle` |

## 8. Deliberately not implemented

These are in the research but must not be built as specified. The reasoning is
upstream's own (`TODO.md` §8) and doc 1's "Do not fake" row.

| Requirement | Why not |
| --- | --- |
| **Un-spoofable global camera privacy dot** | Camera access is recorded in app-ops owned by the system server; reading another app's records needs `WATCH_APPOPS`, which is signature-level and cannot be granted by the user, by ADB, or through the Play Store. A green dot that cannot actually see camera use would be a **false security signal** — worse than no indicator. Mic *is* real via `AudioRecordingCallback`, so the mic dot stays. |
| **Drawing above the status bar's own icons** | `TYPE_APPLICATION_OVERLAY` composites below the status bar by design. The window types that would do it are signature-gated. |
| **Full default-dialler call control** | Answer/decline/hold need `ROLE_DIALER`. `CallSessionFeatures.isActionable` is false without it, so the island never shows a control that will not respond. |
| **Changing another app's media output device** | Android offers no third-party path. `AudioRoutePolicy` marks media rows non-selectable and hands off to the player's own sheet or system settings instead of faking a toggle. |
| **Fabricated SharePlay / Live Translation state** | No public Android API. Rendered only when the calling app advertises it, and never labelled with Apple product names. |
| **Proprietary "Dynamic Island sound"** | Doc 1: the overlay should normally be silent and rely on the originating notification's sound. |
| **AccessibilityService for extra system visibility** | Used only for the existing optional screenshot/elevation features, not as a scraping channel. |

## Verification status

| Layer | Result |
| --- | --- |
| `:core` JVM unit tests (8 new policy test classes) | **written, not executed here** — no JDK in this sandbox |
| Browser prototype engine tests | **70/70 passing** (`cd ref_project && npm test`) |
| Android compile / lint / assemble | **not run** — no JDK, Android SDK or Gradle, and no access to Google Maven |
| Physical-device interaction, frame timing, OEM matrix | **not run** — inherited gap; upstream's own `docs/TEST_REPORT.md` records the same |

The TypeScript mirror in `ref_project/src/lib/islandGapPolicies.ts` exists so the
new decision logic is *executable* somewhere. It mirrors the Kotlin
constant-for-constant and both suites assert the same behaviour; where they could
drift, the shared constants (`MIN_AUTO_EXPAND_MS`, `MAX_TRACKED_SUPPRESSIONS`,
the WCAG ratios, the RSSI thresholds) are asserted in both.

To verify the Kotlin on a machine with a JDK:

```sh
./gradlew :core:test :testDebugUnitTest :lintDebug :app:lintDebug --max-workers=2
```
