---
name: dynamic-island-development
description: Build, test and safely develop this native Android application; use the focused repository skills for overlays, motion, event ownership, device compatibility and releases.
---

Use the existing checkout. Read README.md, docs/ARCHITECTURE.md and the relevant
skills under .agents/skills/. Keep :core Android-free. The browser prototype is
simulated reference content and provides no native runtime evidence.

Run `./gradlew :core:test :testDebugUnitTest :app:assembleRelease :app:bundleRelease :lintDebug :app:lintDebug`
with JDK 17/21 and Android SDK 36. For device work also run
`:app:connectedDebugAndroidTest` on an unlocked device. Preserve raw reports and
separate passed, failed and blocked outcomes. Investigate failures rather than
suppressing assertions or artifact/TLS verification.

Keep the compact capsule fixed and opaque; user-opened controls may expand within bounded windows. Optional secondary presentation uses
its own bounded window. Do not promise precedence over protected system UI,
scanner clearance, physical performance or publisher identity without evidence.
