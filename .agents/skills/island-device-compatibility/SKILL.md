---
name: island-device-compatibility
description: Edit display coordinates, rotation, permission lifecycle or OEM settings.
---

Read docs/COMPATIBILITY_MATRIX.md and island-overlay-safety. Use one coordinate space. Test portrait/both landscape sides, RTL, narrow/folding displays and font/display scale. Use public settings intents and maintain Stop.

```sh
./gradlew :core:test :app:connectedDebugAndroidTest
```

Verify actual inside/outside/gap taps, shade/navigation, revoke permissions and lock/unlock. Record firmware. Missing hardware means BLOCKED, never passed.
