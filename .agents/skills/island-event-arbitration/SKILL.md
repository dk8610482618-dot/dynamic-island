---
name: island-event-arbitration
description: Edit event priorities, notification ownership, timer actions or media races.
---

Read engine/arbiter/IslandPriorityArbiter.kt, core/bus/, core/model/ and repository/ under src/main/kotlin/com/dynamicisland/. Keep state under the arbiter mutex and execute external actions outside it. Android removals confirm dismissal; TimerActions typed semantics confirm eligible cancellation.

```sh
./gradlew :testDebugUnitTest
```

Check calls always win, listener access loss clears content, rapid tracks reject stale artwork and failed actions keep state. Add regression coverage before changing assertions.
