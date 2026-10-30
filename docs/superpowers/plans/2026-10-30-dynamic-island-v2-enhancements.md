# Dynamic Island v2.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overhaul the Dynamic Island engine for true 120Hz fluid motion, tri-state iOS-accurate privacy indicators (Orange/Green/Blue), multi-tier luminous music scrubber bloom, camera notch touch elevation via `TYPE_ACCESSIBILITY_OVERLAY`, and a verified cloud CI emulator/APK pipeline.

**Architecture:** 
1. Replace per-tick WindowManager IPC resizing with unified hardware-synced Compose spring transforms and 120Hz refresh rate requests.
2. Integrate `CameraManager.AvailabilityCallback` alongside `AudioManager.AudioRecordingCallback` into a unified hardware privacy monitor driving iOS hex-accurate indicators.
3. Introduce an optional `TYPE_ACCESSIBILITY_OVERLAY` service to render above status bar icons and capture touches inside the notch cutout.
4. Upgrade GitHub Actions cloud CI to run KVM emulator UI tests and build release APKs with zero local PC storage footprint.

**Tech Stack:** Kotlin 1.9, Jetpack Compose, Android SDK 36 (AGP 8.10), Kotlin Coroutines & StateFlow, Android CameraManager & AudioManager, GitHub Actions KVM runner.

**Spec:** `docs/superpowers/specs/2026-10-30-fluid-120hz-privacy-notch-elevation.md`

## Global Constraints
- **Zero Local Disk Footprint**: No project source code, Gradle caches, or emulator system images may be retained on the local PC. All development is committed directly to GitHub via ephemeral workspaces that are deleted immediately after push.
- **Pure Core Isolation**: `:core` must remain 100% Android-free (JVM only; `import android.*` fails compilation).
- **iOS Color Accuracy**:
  - Mic: `Color(0xFFFF9F0A)`
  - Camera: `Color(0xFF30D158)`
  - Both: `Color(0xFF0A84FF)`
- **Overlay Window Safety**: Must fail open and gracefully fall back to `TYPE_APPLICATION_OVERLAY` if Accessibility permission is not granted.

## Review Focus
1. Rapid camera open/close oscillations must not cause state flicker or missed availability transitions.
2. Concurrent mic and camera usage must properly resolve to the unified Blue indicator without race conditions.
3. Status bar notch touch events must pass down cleanly when the island is collapsed and capture reliably when tapped.
4. Compose animations during window expansion must not trigger recomposition storms or `BoxWithConstraints` subcomposition stalls.
5. GitHub Actions workflow must pass without secret leakage and output a signed/downloadable release APK.

---

### Task 1: GitHub Actions Cloud CI & Cloud Emulator Pipeline Repair

**Files:**
- Modify: `.github/workflows/android.yml`
- Test: GitHub Actions Workflow Execution

**Interfaces:**
- Consumes: Push to `main` branch
- Produces: Verified Gradle build, KVM Android emulator test report, and release APK artifact in GitHub Actions.

- [ ] **Step 1: Inspect and update `.github/workflows/android.yml`**
Remove failing `android-actions/setup-android@v3` (which breaks on Ubuntu latest) and leverage GitHub's native pre-installed Android SDK. Configure `reactivecircus/android-emulator-runner@v2` with API 34/35 x86_64 and hardware KVM acceleration.

- [ ] **Step 2: Commit and push CI workflow update**
Push changes to `main` and trigger GitHub Actions run.

- [ ] **Step 3: Verify cloud workflow passes**
Verify that Gradle compilation and packaging execute cleanly in the GitHub cloud environment.

---

### Task 2: Tri-State iOS-Accurate Privacy Hardware Sensor Tracker

**Files:**
- Create: `src/main/kotlin/com/dynamicisland/engine/ingestion/system/CameraAvailabilityTracker.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/ingestion/system/SystemStateObserver.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/repository/DynamicIslandRepository.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/ui/FixedIslandCapsule.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/service/DynamicIslandService.kt`
- Test: `src/test/kotlin/com/dynamicisland/engine/PrivacyStateTest.kt`

**Interfaces:**
- Consumes: `CameraManager.registerAvailabilityCallback`, `AudioManager.AudioRecordingCallback`
- Produces: `StateFlow<PrivacyIndicatorState>` with `NONE`, `MIC_ONLY`, `CAMERA_ONLY`, `BOTH` and exact iOS hex colors.

- [ ] **Step 1: Write unit test for privacy state resolution**
Create `PrivacyStateTest.kt` asserting that:
- Mic only -> Orange (`#FF9F0A`)
- Camera only -> Green (`#30D158`)
- Both active -> Blue (`#0A84FF`)
- Neither -> null

- [ ] **Step 2: Implement `CameraAvailabilityTracker.kt`**
Register `CameraManager.AvailabilityCallback` to track active hardware camera sessions (`onCameraUnavailable` / `onCameraAvailable`).

- [ ] **Step 3: Update `DynamicIslandRepository` and `SystemStateObserver`**
Expose unified `privacyState: StateFlow<PrivacyIndicatorState>`.

- [ ] **Step 4: Update `FixedIslandCapsule.kt` and `DynamicIslandService.kt`**
Render a smooth 6dp circle vertically centered on the right margin (`Alignment.CenterEnd`, padding `end = 4.dp`) with animated color transitions.

- [ ] **Step 5: Run unit tests and commit**
Run `:testDebugUnitTest` and push to GitHub.

---

### Task 3: Music Scrubber Luminous Comet Trail & Neon Bloom Glow

**Files:**
- Modify: `src/main/kotlin/com/dynamicisland/engine/ui/components/MediaExpandedContent.kt`
- Test: `src/test/kotlin/com/dynamicisland/engine/MediaCapabilitiesTest.kt`

**Interfaces:**
- Consumes: Playback state, track progress fraction, scrubbing gestures
- Produces: `IslandScrubber` with multi-tier radial bloom and dynamic gradient trail.

- [ ] **Step 1: Enhance `IslandScrubber` in `MediaExpandedContent.kt`**
Implement:
- Multi-tier radial bloom: Solid core (`#FFFFFF`), mid-bloom (`0x66FFFFFF`), outer neon glow (`0x1FFFFFFF`).
- Dynamic trailing comet gradient behind the scrubber knob along the played track.
- Tactile spring scaling on scrub interaction.

- [ ] **Step 2: Run tests and commit**
Run `:testDebugUnitTest` and push to GitHub.

---

### Task 4: 120Hz Fluid Motion & Elimination of Recomposition Jank

**Files:**
- Modify: `src/main/kotlin/com/dynamicisland/engine/service/DynamicIslandService.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/window/OverlayWindowPolicy.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/ui/FixedIslandCapsule.kt`
- Modify: `core/src/main/kotlin/com/dynamicisland/core/physics/SpringCatalog.kt`
- Test: `core/src/test/kotlin/com/dynamicisland/core/motion/DynamicIslandMotionEngineTest.kt`

**Interfaces:**
- Consumes: `SpringPhysicsSpecs.ReferenceIslandSpringSpec`
- Produces: Jitter-free 120Hz expansion/collapse with `preferredRefreshRate = 120f` and `requestedFrameRate = 120f`.

- [ ] **Step 1: Remove `BoxWithConstraints` from `FixedIslandCapsule.kt`**
Replace `BoxWithConstraints` (which introduces costly subcomposition stalls) with `Modifier.graphicsLayer` transforms and direct bounds alignment.

- [ ] **Step 2: Streamline `DynamicIslandService.kt` window updates**
Set `preferredRefreshRate = 120f` on window params and request 120Hz on View (API 35+). Synchronize Compose content expansion alongside the window transition to eliminate the two-stage pop-in delay.

- [ ] **Step 3: Verify physics and motion tests**
Run `:core:test` and commit to GitHub.

---

### Task 5: Camera Notch Touch Elevation & "Always on Top" Accessibility Overlay

**Files:**
- Create: `src/main/kotlin/com/dynamicisland/engine/service/DynamicIslandAccessibilityService.kt`
- Modify: `src/main/AndroidManifest.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/dynamicisland/host/MainActivity.kt`
- Modify: `src/main/kotlin/com/dynamicisland/engine/window/OverlayWindowPolicy.kt`
- Test: `src/test/kotlin/com/dynamicisland/engine/window/OverlayWindowPolicyTest.kt`

**Interfaces:**
- Consumes: Android Accessibility Service Framework (`TYPE_ACCESSIBILITY_OVERLAY`)
- Produces: Topmost window elevation rendering above status bar icons and capturing notch touches.

- [ ] **Step 1: Define `DynamicIslandAccessibilityService.kt`**
Declare service binding with `android.permission.BIND_ACCESSIBILITY_SERVICE` enabling `TYPE_ACCESSIBILITY_OVERLAY`.

- [ ] **Step 2: Update manifests and permissions**
Register the service in `src/main/AndroidManifest.xml` and `app/src/main/AndroidManifest.xml`.

- [ ] **Step 3: Update `OverlayWindowPolicy.kt` and `MainActivity.kt`**
Support `TYPE_ACCESSIBILITY_OVERLAY` when accessibility is enabled, and provide a clear 1-tap onboarding toggle in `MainActivity.kt`.

- [ ] **Step 4: Run unit tests and commit**
Run `:core:test :testDebugUnitTest` and push to GitHub.

---

### Task 6: Cloud Emulator Verification & Release APK Generation

**Files:**
- Trigger: Push all changes to GitHub `main` branch
- Verify: GitHub Actions Cloud KVM Emulator Test Results
- Output: `app-release.apk` artifact and verification report

- [ ] **Step 1: Trigger full GitHub Actions CI run**
Push all integrated changes to `Darshan8610/Dynamic-Island` on `main`.

- [ ] **Step 2: Monitor and verify cloud tests and emulator execution**
Verify that all unit tests, Android lint checks, and connected emulator tests pass in GitHub Actions.

- [ ] **Step 3: Publish release APK and verification report**
Provide the user with the direct GitHub Actions / Release download link for the newly built v2.0 APK.