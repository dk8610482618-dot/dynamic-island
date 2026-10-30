> v1.4 update: tap now expands controls in the overlay. Earlier references to
> host-only expansion are superseded by [v1.4 changes](V1_4_CHANGES.md).

# Dynamic Island 1.2

## Changes
- Replaced the fullscreen overlay and hidden/reflected input-region handling with a
  bounded, touchable application window. Outside-window input is handled by Android.
- Window alpha is 1; capsule fill is #000000. PixelFormat.TRANSLUCENT preserves
  rounded corners, not translucent black. No simulated refresh-rate claims.
- Removed accessibility service registration, screenshot/global actions, and
  background activity-launch privilege overrides. No instructions to disable security.
- A fixed 130 × 38 dp capsule is used in every state, with user width/height scaling.
  Tap opens dashboard controls; play/pause is available on the capsule while unlocked.
- Placement uses real cutout geometry in all rotations, including side cutouts.
  System-bar clearance is enabled by default because application overlays cannot
  take precedence over status-bar input. Exact overlap is optional and device-dependent.
- Locked capsule shows generic status only; actions require unlock. Screen-off mode
  does not wake the display. Lock-screen visibility is a best-effort platform request.
- Added live now-playing and inbox dashboard panels, restrained surfaces and ColorOS
  compatibility settings. Notification access is optional for starting the basic island.
- Local artwork resolution runs off main; superseded artwork is rejected using request
  generation and session identity. Remote artwork/network permission removed.
- Session state takes precedence over notification fallback so progress is not reset.
  Media fallback actions cannot leak across packages. Removed duplicate action retry.
- compile/target SDK 36, AGP 8.10.1, Gradle 8.13, Kotlin 1.9.24; min SDK remains 28.

## Device and distribution limits
No physical Realme device is attached. The reported ColorOS/Android 17 warning has
not been reproduced. Reduced capabilities may help, but OEM risk detection, install
reputation, Play Protect and signing history cannot be guaranteed by source edits.
Application overlays remain below status bar, keyboard and protected system surfaces.
Android/OEM may suppress them on keyguard. This app does not bypass those decisions.
120 Hz is an optional window hint, subject to panel, battery and OS policy.

The generated APK is a non-debuggable release-mode build signed with a development key, not the
original publisher's signing key. An existing build signed with a different key cannot
be updated in place. Never distribute this development key as a release credential.

## Source for platform constraints
Official AOSP WindowManager.LayoutParams documentation, inspected during this task:
https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/WindowManager.java
TYPE_APPLICATION_OVERLAY: below critical system windows. FLAG_NOT_TOUCHABLE: obscuring
opacity constraints on pass-through windows. This implementation uses a bounded
TOUCHABLE window instead of a fullscreen pass-through overlay.

## Required device checks (not run)
Install/scan on the exact Realme firmware; initial Start and intentional Stop; revoke
permissions; outside-window taps; status shade and gesture navigation; portrait and
both landscape rotations; lock/unlock and screen off/on; rapid track skips; two media
players; headset controls; background battery management; 60/120 Hz frame traces.

The later feature additions are described in FEATURES.md; final automated results are in TEST_REPORT.md.
