---
name: island-overlay-safety
description: Use when editing the bounded overlay window, camera placement, capsule input, permission lifecycle or lock-screen presentation.
---

Read `src/main/kotlin/com/dynamicisland/engine/service/DynamicIslandService.kt`,
`window/OverlayWindowPolicy.kt`, `ui/FixedIslandCapsule.kt` (the latter two under
`src/main/kotlin/com/dynamicisland/engine/`), and
`core/src/main/kotlin/com/dynamicisland/core/geometry/CapsuleAnchor.kt`.

1. Keep a bounded, touchable TYPE_APPLICATION_OVERLAY window. Never replace it with
   a fullscreen pass-through window, hidden input APIs or accessibility elevation.
2. Compute bounds from the same physical display coordinate space as the cutout.
   Test portrait, both side cutouts, tiny screens and large manual offsets.
3. Keep compact dimensions independent of activity state. The user now requests
   explicit expansion on tap. Expand only for user interaction, stay within usable
   display bounds, and collapse on outside touch, close control, lock and screen-off.
4. Never claim precedence over protected system UI. Keyguard suppresses private text
   and actions; screen-off must not wake the phone. Permission failures remove the window.
5. Run `./gradlew :core:test :testDebugUnitTest :app:assembleRelease :lintDebug :app:lintDebug`.
   Then perform device taps inside and outside the capsule, shade/navigation gestures,
   permission revocation, rotation and lock/unlock checks. JVM mocks do not prove input delivery.
6. Preserve the notification Stop action. If a window fails to attach or update,
   remove it, stop the service and expose a recoverable host error. Never loop a refused start.
7. Record tested device/firmware, commands and unrun checks in docs/TEST_REPORT.md.
   OEM scanning, keyguard visibility and refresh rate require device evidence.
