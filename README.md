# Dynamic Island

A working recreation of the iPhone Dynamic Island in **Kotlin + Jetpack Compose**: a black capsule
that springs between four shapes and hosts six live activities — music, a call, a timer, charging,
turn-by-turn navigation and a transient Face ID unlock.

Plus a **browser preview** of the same state machine and geometry, so the motion can be judged
without building an APK: open [`preview/index.html`](preview/index.html).

```
preview/index.html   ← click the island, or fire activities from the panel
app/                 ← the Android implementation
docs/                ← behaviour spec + the Compose skills audit
```

---

## The state machine

Everything hangs off one idea: the island's shape is a **pure function of the live activities**.
There is no stored "isExpanded" boolean to drift out of sync — `IslandState.presentation()` derives
it every time it is asked.

| Shape | When | Size (dp) |
| --- | --- | --- |
| `Collapsed` | nothing is running — this is the physical cutout | 126 × 37, r 18.5 |
| `Compact` | one activity | activity width × 37, r 18.5 |
| `Expanded` | the user opened an activity | 371 × activity height, r 44 |
| `Split` | two peer activities share the pill | 340 × 37, r 18.5 |

Activities are ranked, and rank decides who owns the island:

| Activity | id | Priority | Compact width | Expanded height | Notes |
| --- | --- | --- | --- | --- | --- |
| Call | `call` | 100 | 238 | 196 | nothing displaces a ringing call |
| Navigation | `nav` | 80 | 216 | 184 | |
| Music | `music` | 60 | 246 | 174 | |
| Timer | `timer` | 55 | 152 | 184 | |
| Charging | `charging` | 20 | 138 | 140 | yields to everything |
| Face ID | `unlock` | 10 | 132 | 128 | transient, retires after 1.8s |

Two activities only **split** the pill when the lower-ranked one is at or above
`IslandState.SPLIT_MIN_PRIORITY` (50) — so a call and a song share the island, but a charging bolt
never shoulders into a navigation prompt.

Re-posting an activity with the same `id` is an **update**, not an add. That is how live data flows:
a countdown tick, playback progress, a call connecting. The expanded card stays open and the
content does *not* re-animate, because content crossfades are keyed on the state's shape, not on its
data.

---

## Architecture

```
app/src/main/java/com/arena/dynamicisland/
├── MainActivity.kt                 edge-to-edge host
├── island/
│   ├── IslandActivity.kt           sealed model: six activities + their sizes and priorities
│   ├── IslandState.kt              pure reducer — the whole interaction model, unit-testable
│   ├── IslandPresentation.kt       the four shapes + geometry + content key
│   ├── IslandController.kt         @Stable holder wrapping the reducer in Compose state
│   ├── IslandMotion.kt             spring specs and fade timing, in one place
│   ├── IslandHost.kt               controller → presentation, plus transient lifetime
│   ├── DynamicIsland.kt            the capsule: size/radius springs, gestures, crossfade
│   ├── IslandTheme.kt              colours and type
│   └── content/                    one file per activity, plus glyphs and widgets
├── demo/DemoScreen.kt              wallpaper, status bar, control panel, scripted tour
└── ui/theme/Theme.kt
```

Three deliberate boundaries:

- **Model knows nothing about Compose.** `IslandState` is a data class of pure functions; the tests
  run on the JVM with plain JUnit.
- **Container knows nothing about activities.** `DynamicIsland` renders whatever presentation it is
  handed; a caller can swap the whole per-activity UI through `activityContent`.
- **Activities know nothing about each other.** Each receives only its own data and
  `expanded: Boolean`.

### Adding an activity

1. Add a `data class` to `IslandActivity` with `id`, `priority`, `compactWidth`, `expandedHeight`.
2. Write `YourContent(activity, expanded)` in `island/content/` — a compact and an expanded layout.
3. Add one branch to `IslandActivityContent`.
4. Post it: `controller.post(IslandActivity.Your(...))`.

No other file needs to change — the split layout, gestures and priority arbitration all follow.

---

## Motion

- **Size**: `spring(dampingRatio = 0.74, stiffness = 420)` — under-damped on purpose; the pill
  overshoots a hair and settles, which is what makes the morph read as a bubble rather than a resize.
- **Corner radius**: `spring(0.9, 520)` — damped harder so the shape does not wobble mid-stretch.
- **Content**: 190ms crossfade, with the recessed layer scaling `0.96 → 1`. Content only swaps when
  the *shape* changes, so ticking data stays put.
- **Press**: 0.975 scale while the finger is down, driven by `PressInteraction` and released properly
  on drag-away.
- **Gestures**: tap toggles, drag down collapses, drag up opens (`±22dp` threshold).

---

## Build and run

Requirements: JDK 17, Android SDK 35, Gradle 8.7+ (Android Studio Ladybug or newer is easiest).

```bash
# open the project in Android Studio, or:
gradle :app:installDebug
gradle :app:testDebugUnitTest
```

The Gradle wrapper jar is not committed, so run `gradle wrapper` once (or let Android Studio
generate it).

### Preview (no build needed)

```bash
cd preview && python3 -m http.server 8000
# then open http://localhost:8000
```

The preview is a hand-written port: same geometry, same priorities, same split rule, same springs
expressed as an overshooting `cubic-bezier`. It exists so the choreography can be reviewed in a
browser; the Kotlin is the implementation.

---

## Status, honestly

The Android code in this repo has **not been compiled here**: this sandbox has no JDK, no Android
SDK, and no network access to Maven Central or Google's Maven repo. The project is written to build
as-is with the pinned versions in `gradle/libs.versions.toml` (AGP 8.7.3, Kotlin 2.0.21, Compose BOM
2024.12.01), and the reducer is covered by JVM tests — but "it compiles" is a claim only a real
Gradle run can make. Expect to fix a stray import if anything.

Compile-safety was instead attacked from the other side: every source file was swept for missing
imports, and the implementation was audited against Compose/Kotlin agent skills
(`chrisbanes/skills`, `aldefy/compose-skill`, `skydoves/compose-performance-skills`,
`hamen/compose_skill`, `maxrave-dev/kotlin-footguns`). That audit found and fixed a real
content-identity bug in the crossfade, a composition-phase animated read, a paused animation that
kept ticking, and a missing `Modifier` import — see [`docs/skills-audit.md`](docs/skills-audit.md).

What is *approximate* rather than measured: the exact dp values. They are modelled on the iPhone's
Dynamic Island and tuned by eye in the preview, not extracted from Apple's binaries. See
[`docs/behavior-spec.md`](docs/behavior-spec.md) for the full list of assumptions and for how this
would be verified properly against a real device.
