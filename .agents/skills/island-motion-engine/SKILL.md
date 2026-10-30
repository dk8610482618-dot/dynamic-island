---
name: island-motion-engine
description: Edit numeric springs or content motion and reduced-motion behavior.
---

Read core/src/main/kotlin/com/dynamicisland/core/{motion,physics}/ and engine/ui/motion/IslandMotionPreferences.kt. Keep retargeting interruption-safe, park idle clocks and keep compact geometry fixed and user expansion bounded.

```sh
./gradlew :core:test
```

Run debug screenshot states and physical-device FrameTimingMetric traces. Record device/build/scenario and raw results. Never infer 120 Hz from a simulation.
