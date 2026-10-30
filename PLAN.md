> Historical source-archive reference. Claims and counts below are not current validation. See docs/TEST_REPORT.md, docs/FEATURES.md and docs/COMPATIBILITY_MATRIX.md for this build.

# Android Dynamic Island — Full Spec Implementation

## Context

`D:\dyn island` is a working but partial Android recreation of Apple's Dynamic Island. It builds green (debug 7.8 MB, release 5.7 MB), passes 11/11 unit tests, and already implements a sound event-driven core: a `NotificationListenerService`, a `MediaSessionManager` tracker, a Telecom tracker, a `BroadcastReceiver` observer, a typed `IslandEventBus`, a priority arbiter with correct tiering, and a Compose overlay in a foreground service.

It does **not** match the reference specification. `Dynamic Island Reverse Engineering Specification.md` (781 lines) is the contract, and the gap is large:

- **Geometry is wrong at the shape level.** `IslandShape` draws pills as *stadiums* (semicircular caps), which have a curvature **step** of `1/R` at each junction — G¹, not the spec's G². Verified numerically.
- **Springs are duplicated and unreconciled.** Two parallel spec sets exist in `SpringPhysicsSpecs.kt` (one from the README's CoreAnimation table, one derived from the JS reference). Neither is the spec's own animation-parameter table; 6 members are dead code, and the integration method is numerically inadequate (Finding 11).
- **Roughly half the specified feature set is absent**: the keyline stroke, the dual shadow pair, album-art colour extraction, the AGSL metaball detachment, three of the seven state-machine states, all haptics, privacy dots, the Face ID HUD, rotation handling, TalkBack semantics, and the Reduce-Motion path.
- **Several subsystems are dead code** — the entire `touch/` package, and `cutout/` + `window/` are reachable only from each other. The rich `InCallService` call path is declared but wired to nothing.

The goal is a complete, specification-faithful implementation: the same animations, functionality, features and colours as the spec describes, restructured around the spec's own architecture.

### Decisions taken

| Decision | Resolution |
|---|---|
| Source of truth | **Match iOS pixels exactly.** Dimensions derive from the spec's @3× physical table, converted through real device metrics so the island occupies the same *physical* size as on an iPhone. |
| Scope | **All four tiers**: core geometry/physics/visuals; states/gestures/haptics; advanced visual (AGSL + colour extraction); extended activities. |
| Delivery mode | **Both, auto-selected** at service start: OEM insets path when available, else public-API dynamic window sizing. |
| Architecture | **Restructure from the spec** — adopt its class names (`SpringSimulation`, `DynamicIslandMotionEngine`, `MorphFrame`, `ResponsiveGeometryEngine`, `DeviceGeometryProfile`, `IslandPriorityResolver`). |
| Compact geometry | Idle/mask = 125 dp (spec §B); content widths from the JS reference (music 236, timer 228, call 224, charging 252), density-converted; floor = `max(cutoutW + 32, 120)`. |
| Device for verification | **Emulator AVD, automated** — SDK already has the binary and an android-35 image. |
| Data policy | **Stay live-only.** No injector. All verification drives *genuine* system events via ADB (see Verification). |

---

## Findings that shape the plan

These were verified numerically during planning, not assumed. Each one changes a design decision.

### 1. The spec's spring table is internally consistent; its descriptive columns are not

`k = (2π/T)²` holds to within 0.15 across all 11 entries and `c = 2ζ√k` matches throughout — so **the (T, ζ) pairs and their derived k, c are the authoritative contract.**

But the stated overshoot and settling columns are inflated relative to their own ζ. Compact→Expanded claims 3.2% overshoot; ζ=0.80 actually gives **1.52%**. Reaching 3.2% would need ζ≈0.74.

Worse, the spec's own frame-by-frame trace is **not** the trajectory of the stated spring:

| t (ms) | spec W | spring W (T=0.48, ζ=0.80) |
|---|---|---|
| 0.0 | 125 | 125.0 |
| 49.8 | 178 | 161.6 |
| 99.6 | 270 | 227.6 |
| 149.4 | 338 | 287.2 |
| 215.8 | 374 | 339.3 |
| 481.4 | 370 | 372.5 |

**Rule for implementation and tests: assert against (T, ζ) → k, c. Treat the overshoot %, settling-time and frame-trace columns as descriptive prose, never as assertions.**

### 2. A G² corner at r = 18.5 dp on a 37 dp pill is geometrically impossible

A continuous corner consumes a shoulder of `L·r` where `L = 1.52866483`. Both corners share the height, so the requirement is `2·L·r ≤ H`:

- `r = 12.10 dp` → `2Lr = 36.99` — the maximum that fits in `H = 37`.
- `r = 18.5 dp` → `2Lr = 56.56` — **does not fit.**

The spec's own wording resolves this: *"the shape is fully continuous: the corner radius is 18.5 dp (H/2), producing a continuous capsule."* A continuous capsule at `R = H/2` is **a full Lamé superellipse** `|x/a|ⁿ + |y/b|ⁿ = 1` with `a = w/2, b = h/2`, which has **no straight edge and no corner-radius parameter** — 18.5 is nominal, not a Bezier corner.

Curvature at the two axis points for `n = 5` on 125×37: `k_top = 0.913`, `k_right = 0.024` — varying smoothly with **no step**. G² confirmed. A stadium's flat `1/b = 0.054` everywhere *with* a step to zero at the junctions is what we must stop drawing.

**This unifies the shape rule**: a full superellipse *is* the `r → min(w,h)/2` limit of a corner squircle. One rule covers every state with no special-casing:

```
r_eff = min(r, min(w,h) / (2L))
if (r_eff >= min(w,h)/2 − ε) → full superellipse      // no straight edge
else                          → corner squircle + straight edges
```

`SquircleShape.kt` already clamps `r_eff` correctly. **The bug is only in which branch `IslandShape` picks.**

### 3. `IslandShape` currently picks the wrong branch for four of five states

| state | size | r | cap radius | current pick | correct pick |
|---|---|---|---|---|---|
| Compact | 236×36 | 18.0 | 18.0 | **STADIUM (G¹)** | full superellipse |
| Closed | 122×36 | 18.0 | 18.0 | **STADIUM (G¹)** | full superellipse |
| Expanded (media) | 366×176 | 44.0 | 88.0 | SQUIRCLE ✓ | corner squircle |
| Expanded (timer) | 366×84 | 42.0 | 42.0 | **STADIUM (G¹)** | full superellipse |
| AlertPeek | 300×52 | 26.0 | 26.0 | **STADIUM (G¹)** | full superellipse |

Four of five states ship a curvature discontinuity today.

### 4. Android's `densityDpi` is not the physical density

`dp` is defined off `densityDpi`, but physical size follows `xdpi`. The two routinely disagree. Target physical width is **20.707 mm** (125 pt × 3 px ÷ 460 ppi):

| device | xdpi | densityDpi | ratio | naive 125 dp renders | error | correct dp |
|---|---|---|---|---|---|---|
| Pixel 8 Pro | 489 | 420 | 1.164 | **17.04 mm** | −3.66 mm (too small) | 151.86 |
| Pixel 8 | 428 | 420 | 1.019 | 19.47 mm | −1.23 mm | 132.92 |
| Galaxy S24 | 416 | 450 | 0.924 | **21.47 mm** | +0.76 mm (too **big**) | 120.58 |
| OnePlus 12 | 510 | 480 | 1.063 | 18.68 mm | −2.03 mm | 138.59 |
| reference 460 ppi | 460 | 460 | 1.000 | 19.84 mm | −0.86 mm | 130.43 |

**The error has both signs.** Where `xdpi` exceeds the density bucket the island comes out too small; where the bucket is inflated above the physical density (Galaxy S24: 450 against a 416 ppi panel) it comes out too large. No fixed correction constant can work — only the per-device ratio.

Matching physical size requires:

```
mm       = 125 pt × 3 px/pt ÷ 460 ppi × 25.4   = 20.707 mm   (and 6.129 mm tall)
dpPerMm  = 160 / 25.4
scale    = xdpi / densityDpi
dp       = mm × dpPerMm × scale
```

Note the consequence: on a panel where `xdpi == densityDpi`, the correct value is **130.43 dp, not 125 dp**. A point is not a dp — treating them as equal is a 4% error even on a well-behaved device.

with a **guard band**: accept `scale` only in `[0.5, 2.0]` and `xdpi > 0`; otherwise fall back to canonical dp (125 × 37, r 18.5). Devices reporting `xdpi = 0` or absurd values are common — the fallback is the normal path, not an error path.

### 5. The metaball `k` must be density-scaled

The spec gives `k ≈ 32.0` for the exponential smooth-min, and a *polynomial* smooth-min in its shader code block. These are not equivalent — at separation 14 the polynomial gives `smin = −0.949` while the exponential gives `−0.000`:

| separation | poly smin | exp smin |
|---|---|---|
| 0 | −5.333 | −0.0217 |
| 8 | −2.250 | −0.000 |
| 14 | −0.949 | −0.000 |
| 40 | 0.000 | −0.000 |

**The polynomial form in the code block is authoritative** (it is what the shader actually implements; the prose formula is the rationale). And because `k` is in *absolute* units, `k = 32` behaves differently at 3× density than at 1× — it must be scaled: `k_px = 32 × densityScale`.

### 6. The album-art saturation clamp has a degeneracy

Spec: isolate the dominant vibrant swatch, clamp `S ≥ 0.45` and `0.40 ≤ V ≤ 0.85`. But for a neutral source the hue is undefined, so raising `S` **invents** a colour:

| swatch | before | naive clamp produces |
|---|---|---|
| mid grey (128,128,128) | H 0.0°, S 0.00, V 0.50 | H 0.0° → **red** |
| near-black (12,12,14) | H 240°, S 0.14, V 0.05 | H 240° → **blue** |
| near-white (250,250,252) | H 240°, S 0.01, V 0.99 | H 240° → **blue** |

**Required rule: if `S_before < 0.45` the swatch is non-vibrant — fall back to `#FFFFFF`, never amplify the hue.** Similarly `V` must be clamped at the source swatch, not after.

### 7. Icon stroke cannot be one global constant

`IslandGlyphs.kt` draws on a 24-unit grid with a single `val stroke = 2f`. The spec's iconography table gives *per-glyph* strokes in dp:

| glyph | box | current stroke | spec | required grid stroke |
|---|---|---|---|---|
| Timer clock | 16 dp | 1.33 dp | **1.75 dp** | 2.62 |
| Navigation arrow | 18 dp | 1.50 dp | **2.25 dp** | 3.00 |
| Face ID | 36 dp | 3.00 dp | **2.50 dp** | 1.67 |
| Battery | 22 dp | 1.83 dp | **1.50 dp** | 1.64 |

One constant cannot satisfy four different targets. **Stroke becomes a per-glyph dp parameter**, converted at draw time: `strokeGrid = strokeDp × 24 / boxDp`.

### 8. Typography is missing every tracking value

| style | spec size | spec tracking | current |
|---|---|---|---|
| Compact label | 12 | −0.20 sp | 12 sp, no tracking |
| Compact mono timer | 13 | 0.00 + tnum | **12 sp**, tnum |
| Expanded title | 15 | −0.24 sp | 15 sp, no tracking |
| Expanded subtitle | 13 | @60% | 13 sp @ **52%** |
| Expanded action label | 14 | −0.15 sp | absent |

### 9. The multi-activity bifurcation trace is also not its own spring

Same defect as Finding 1, in the second frame-by-frame table. Contracting the primary pill 125 → 80 dp on `(T=0.52, ζ=0.72)`:

| t (ms) | spec W | spring W |
|---|---|---|
| 0.0 | 125 | 125.0 |
| 83.0 | 95 | 111.3 |
| 132.8 | 82 | 99.5 |
| 415.0 | 80 | 78.4 |

So the same rule applies: **(T, ζ) wins, frame tables are descriptive.**

### 10. The transition matrix names one spring that the animation table never tabulates

Cross-checking all 13 rows against the 10-entry animation parameter table:

- 8 rows match a table entry exactly (0.48, 0.40, 0.32, 0.52, 0.44, 0.30, 0.38, 0.22) — consistent.
- **`COMPACT → IDLE, "Collapse to cutout", T = 0.36` has no counterpart**, and 0.36 conflicts with `EXPANDED_TO_COMPACT`'s 0.40.
- `IDLE → COMPACT` (T = 0.44) is named only in the state-machine table, never in the animation table.

**Both need to be added as explicit entries.** For `COMPACT → IDLE` use `T = 0.36` with `ζ = 0.86` (the spec's snappy-collapse family); for `IDLE → COMPACT` use the spec's `T = 0.44, ζ = 0.82`. Record both as *inferred* entries in the spring catalogue so the provenance is auditable.

### 11. The spec's Euler integrator fails the ±5% gate; use the closed form instead

The spec's `SpringSimulation.update` advances with a single explicit step:

```
accel = -k*disp - c*vel ;  vel += accel*dt ;  value += vel*dt
```

At the same spec's own `dt.coerceAtMost(0.033f)` clamp, that is far too coarse. Fitting a simulated trajectory back to `(T, ζ)` recovers:

| target | dt | sub-steps | recovered | error |
|---|---|---|---|---|
| T=0.48 ζ=0.80 | 16.7 ms | 1 | T=0.381, ζ=0.952 | **−20.6% / +19.0%** |
| T=0.48 ζ=0.80 | 33.0 ms | 1 | T=0.329, ζ=0.998 | **−31.5% / +24.8%** |
| T=0.40 ζ=0.86 | 33.0 ms | 1 | T=0.279, ζ=0.998 | **−30.3% / +16.1%** |
| T=0.48 ζ=0.80 | 16.7 ms | 4 | T=0.459, ζ=0.826 | −4.4% / +3.3% ✓ |
| T=0.22 ζ=0.62 | 16.7 ms | 16 | T=0.219, ζ=0.618 | −0.5% / −0.3% ✓ |

Critically, the naive step **biases the spring stiffer and more damped** — it *looks* like a spring but is the wrong spring, which is exactly the subtly-wrong-motion failure this project must avoid.

The spec also supplies the exact solution for the underdamped case in its own Physics section. Evaluating it directly is **both exact and cheaper than sub-stepping** (one trig triple versus 32 integration steps):

| dt | analytic | Euler sub=32 | error |
|---|---|---|---|
| 16.7 ms, T=0.22 | (147.6811, 2425.4251) | (148.4869, 2441.2448) | 0.81 dp |
| 33.0 ms, T=0.48 | (143.1188, 969.6225) | (143.7755, 976.6676) | 0.66 dp |

**Decision: implement `SpringSimulation` as the analytic closed form**, re-seeded from live state on every retarget. This also satisfies the spec's velocity-continuity requirement (checklist item 60) *by construction*: re-seeding `(x₀, v₀)` from the current state and evaluating at `t = 0` reproduces position and velocity to `0.00e+00` — a retarget cannot jump. All 12 springs have `ζ < 1`, so the underdamped form covers everything; still guard `ζ ≥ 1` for robustness. Keep a sub-stepped Euler implementation in the test source set purely as a cross-check oracle.

### 12. Missing `VIBRATE` permission breaks the entire haptics tier

Neither `src/main/AndroidManifest.xml` nor `app/src/main/AndroidManifest.xml` declares `android.permission.VIBRATE`. Without it every `VibrationEffect` call is a silent no-op — Phase 5 would pass its unit tests (which assert the composition, not the buzz) while producing nothing on device, for both the primitive path and the fallback. **Add it in Phase 0**, and add an on-device assertion so the tier cannot regress silently.

### 13. Strict physical matching makes the expanded card unusable on some devices

Scaling *every* dimension by `xdpi/densityDpi` breaks at the extremes. At 420 dpi with a 360 dp-wide screen, a 370 pt card becomes `370 × 3 × 160/420 = 423 dp` — **wider than the display**.

**Resolution: physically-match the internals, screen-fit the outer card.** Icons, glyph strokes, keylines, shadows, paddings, corner radii and text all take the physical scale; the two *outer* card dimensions keep the spec's own responsive rule (`min(screenWidth − 32, 410)`). This preserves the design intent — the island's optical weight matches an iPhone — without ever overflowing the screen.

### 14. The OEM insets path is hidden API and may not be selectable at all

The spec's OEM mode uses `ViewTreeObserver.OnComputeInternalInsetsListener`, which is hidden. The existing service already reaches it **only by reflection**, and its reflection block is gated to `P ≤ SDK < TIRAMISU` — above 33 the code takes the public `setTouchableRegion` path. On modern devices the OEM branch may be unreachable entirely.

**Resolution: three strategies, not two** — OEM reflective insets, public `setTouchableRegion`, and public dynamic window sizing — behind one interface, chosen by a **fail-closed runtime probe** that logs `mode=<X> reason=<...>`. This still honours "both, auto-selected", it just refuses to claim a capability it cannot verify.

### 15. Content choreography: the spec's code and its frame table disagree

The spec's `DynamicIslandMotionEngine` drives content opacity from **width** travel:

```
progress = |width - 125| / |370 - 125|
```

but its frame table asserts the 70% threshold is crossed at frame 9, where `W = 338, H = 134`. Evaluating both formulations at that state:

| driver | progress at frame 9 |
|---|---|
| width-travel | **86.9%** — already well past the threshold |
| **area** `(w·h − A₀)/(A₁ − A₀)` | **74.5%** — consistent with "crosses at frame 9" |

Yet simulating the actual spring, width-travel crosses 70% at 159 ms and area at 185 ms, against the table's claimed ~149 ms — so **neither matches the trace exactly**, and the trace is the column we already know is unreliable (Finding 1).

**Decision: drive choreography from area progress.** It is the only formulation under which the spec's own frame-9 state is consistent with its own 70% claim, and it is the more physically sensible basis for a shape that grows in two dimensions. Record it as a deliberate, documented divergence from the spec's sample code — one of only two places this plan knowingly departs from the literal text.

### 16. The render pipeline must be constant-size, or 120 fps is unreachable

This is the single most important performance decision, and the current code gets it wrong. `DynamicIslandView` today applies `Modifier.width(currentW)` / `.height(currentH)` to the pill, so **every animation frame triggers a Compose layout pass** — measure, layout, and re-place the subtree, 120 times a second.

**Target pipeline:** a host composable of **constant size** (the expanded card's maximum bounds, allocated once). The pill itself is drawn per frame from a **reused `Path`** — the superellipse/squircle outline is regenerated in-place with no allocation — and *all* content is positioned exclusively via `Modifier.graphicsLayer { translationX/translationY/alpha/scaleX/scaleY }`, which is a GPU-only transform that never touches layout. The window rect itself snaps once at transition start.

This is what makes both the 120 fps target and the spec's "zero memory allocations inside draw and layout passes" rule (checklist item 89) achievable rather than aspirational. It also means the expansion animation is a *draw-phase* activity, so `MorphFrame` values must be consumed inside `Canvas`/`drawBehind` — reading them from a composable would trigger recomposition instead and defeat the whole design.

### 17. The API<33 fallback should be tested on a real old platform, not a debug flag

The Bézier fallback and the one-shot haptic path only execute below API 33, and the only installed system image is `android-35`. The tempting shortcut is a debug-only override flag forcing the legacy path — but that tests a simulation of the old platform, not the old platform.

**Decision: download an older system image** (API 30 or 32) and run the fallback gates on a genuine second AVD. This keeps the shipping code free of test-only branches — consistent with the live-only policy — and verifies the real `VibrationEffect` capability probe and the real `setTouchableRegion` path rather than a mocked verdict.

---

## Target architecture

Three Gradle modules. The split is the load-bearing decision: **`:core` uses `kotlin("jvm")`, not `com.android.library`**, so "no `android.*` imports" is enforced by the *compiler*, not by a lint rule or convention. Every numeric claim in the spec becomes a real JUnit assertion instead of the current `isReturnDefaultValues` stub.

```
:core          kotlin("jvm")        pure Kotlin, ZERO android/androidx deps
├── physics/SpringSimulation.kt          m·x'' + c·x' + k(x−target) = 0
├── physics/SpringCatalog.kt             all spec (T, ζ) entries + the 2 inferred
├── physics/RubberBand.kt                f(x,d,c) = x·d·c/(d+c·x)
├── motion/DynamicIslandMotionEngine.kt  one clock, one MorphFrame
├── motion/MorphFrame.kt                 w/h/r/alphaCompact/alphaExpanded
├── motion/Choreography.kt               the 0–80 / 70–100% alpha mapping
├── geometry/DeviceGeometryProfile.kt    screen, cutout, density
├── geometry/DensityNormalizer.kt        xdpi/densityDpi + guard band
├── geometry/ResponsiveGeometryEngine.kt compact/expanded bounds
├── geometry/SuperellipsePath.kt         Lamé sampler
├── geometry/SquirclePath.kt             Bezier corner constants
├── geometry/ShapeSelector.kt            the unified r_eff rule
├── geometry/MetaballField.kt            SDF + polynomial smin
├── geometry/HitRect.kt                  the spec's −6/+10/−12/+12 math
├── color/ColorTokens.kt                 every hex in the spec
├── color/VibrantExtractor.kt            dominance + S/V clamps + fallback
├── type/TypeTokens.kt                   sizes, tracking, weights
├── state/IslandState.kt                 all 7 states
├── state/TransitionTable.kt             the spec's 13 (from,event) rows
├── activity/ActivityRecord.kt           priorityClass, relevanceScore, startTime
├── activity/IslandPriorityResolver.kt   the spec's descending sort
├── gesture/GestureClassifier.kt         pure event-stream classifier
└── delivery/DeliveryModePolicy.kt       chooses OEM vs public

:engine        com.android.library   adapters + Compose
├── platform/window/OverlayWindowHost.kt
├── platform/window/OemInsetsWindowStrategy.kt
├── platform/window/PublicWindowStrategy.kt
├── platform/window/CutoutReader.kt      WindowInsets.displayCutout
├── platform/motion/ChoreographerFrameClock.kt
├── platform/haptics/HapticEngine.kt     VibrationEffect primitives
├── platform/a11y/MotionPreference.kt    Reduce Motion observer
├── platform/graphics/IslandShape.kt     Compose Shape over :core paths
├── platform/graphics/MetaballShader.kt  AGSL + Bezier fallback
├── ingestion/…                          the four existing trackers, migrated
├── service/DynamicIslandService.kt
└── ui/                                  the Compose layer

:app           com.android.application  the permission host, unchanged in role
```

**Retained from the current codebase** (working assets, migrated not rewritten):
- `IslandPriorityArbiter`'s tiering, dedup, multi-slot allocation and its **7 passing tests** — the logic moves into `:core/activity` behind `ActivityRecord`
- `IslandEventBus` — the `replay=0` + `UNDISPATCHED` startup-race fix is real and must be preserved
- `DynamicIslandRepository` wiring; the four ingestion trackers
- `IslandGlyphs.kt`'s **18 real vector paths** (all 18 verified genuine — no placeholders)
- `SquircleShape.kt`'s 3-cubic Bézier constants and its `r_eff` clamp
- `IslandShape`'s stadium-vs-squircle *decision point* (the rule generalises; the branch is fixed)
- `Ticker.kt`'s second-aligned ticker

**Removed / absorbed**: `touch/` (both files, absorbed into `platform/window/`), `window/DynamicIslandWindowManager`, `SpringPhysicsSpecs`' 6 dead members.

**One boundary to get right**: `:core` must not know about `PendingIntent`, `Bitmap` or `StatusBarNotification`, which the current `IslandActivity` carries directly. Introduce an opaque-reference signal boundary — the adapters hold the Android objects and `:core` holds only stable string handles. This is the one place the module split forces a real refactor rather than a move, and getting it wrong leaks `android.*` into `:core` and breaks the whole point.

---

## Phases

Nine phases. Each ends with `./gradlew test` green and an installable build, so the app stays demoable throughout.

### Requirement — user-positionable island (added mid-build)

The specification locks the island to the cutout. That is the right *default* —
the illusion depends on the black surface blending into the camera aperture —
but it is wrong in several real cases, and there is no good argument against
letting a user move it.

It is resolved in **three layers**, most specific winning:

```
AUTO  →  cutout-derived position, or the specification's canonical figure
NUDGE →  AUTO plus a device-specific calibration correction
MANUAL →  an explicit user offset in dp, on x and y independently
```

Manual placement is expressed as an **offset from AUTO**, never as an absolute
position. That matters: a user who moved the island before rotating the device
should still get correct cutout-tracking afterwards, and a user who resets the
offset must get the correct default back rather than wherever the last absolute
value pointed.

Implemented in `core/geometry/IslandPlacement.kt`, with 15 tests. The behaviours
worth knowing:

- **Follows the cutout, not the screen.** On the measured AVD the two differ by
  4.8 dp; centring on the screen would leave the island visibly off the camera.
- **Anchor is reported, not just applied.** `FallbackReason` distinguishes a
  centred cutout, an off-centre cutout, a corner cutout, no cutout, and an
  unknown status bar — so the host UI can *explain* the position.
- **Cannot be lost.** Two independent clamps: a smooth user-facing envelope
  computed from the automatic position, plus a hard screen-bounds clamp that
  also holds when the island changes size (a 370 dp card must still fit after
  the user dragged the 130 dp pill to the edge).
- **Envelope measured from the automatic position, not the screen top.** Getting
  this wrong double-counts the status-bar clearance and let a downward drag push
  the island 27 dp past the navigation bar. Caught by a test and fixed.

Host UI: a placement section in `MainActivity` with two sliders (−X/+X, −Y/+Y),
a live preview, a **Reset to default** action, and a line stating why the
automatic position is where it is. The offset persists across restarts.

### Requirement — premium motion (added mid-build)

"Must look premium, not sloppy or laggy" is not a matter of taste; it decomposes
into specific defects, and `core/physics/MotionQualityTest.kt` asserts against
each one so none can reappear:

| Defect | Assertion |
|---|---|
| Motion recovers the wrong spring | Fit a simulated trajectory back to `(T, ζ)`; require ±5%. The specification's own Euler integrator fails this by 20–31% — a dedicated test pins that it *would* fail, so it cannot be quietly re-adopted |
| Frame-rate dependence | The same animation must be identical at 60, 90 and 120 Hz |
| Jumps on interruption | Swept across 60 interruption points; position and velocity jump must be exactly zero |
| Acceleration spikes | Peak per-frame second difference must stay under the **derived** ceiling `k·travel·dt²` — and a companion test proves the ceiling catches an injected 5 dp jump |
| Inappropriate bounce | Collapses must not overshoot perceptibly (<1%); arrivals should (0.5–4%) |
| Motion that drags | No spring may take over 1.5 s to settle |
| GC pauses | 200 000 simulation steps must not grow the heap |
| Kinks mid-transition | The outline must stay G² continuous *throughout* a size change, not just at rest |

Plus the architectural guarantees from Findings 11 and 16: the closed-form
spring (exact, allocation-free, frame-rate independent) and the constant-size
render pipeline with `graphicsLayer`-only content (no per-frame layout pass).

### Phase 0 — Prerequisites and baseline

No source changes. Make the repo safe to change, and get a device.

- `git init` + first commit of the current state. **There is no version control at all today** — the single highest-value prerequisite, because every later phase needs to be diffable and revertible.
- **Add `android.permission.VIBRATE`** to `src/main/AndroidManifest.xml` (Finding 12). Without it the whole haptics tier is a silent no-op.
- Create the two `.pro` files both `build.gradle.kts` files already reference but which **do not exist** (`consumer-rules.pro`, `proguard-rules.pro`). The build survives only because `isMinifyEnabled = false`; enabling minification for a release will fail until these exist.
- Create and boot the primary AVD headlessly from the installed `android-35` image, **and a second AVD on an older system image** (API 30 or 32) so the API<33 fallback gates run on a real old platform rather than a simulated one (Finding 17).
- Capture a **baseline still set** of the current island in every reachable state. These become the "before" for the diff in Phase 9 — and they will legitimately all change, because Phase 2 removes the stadium branch from four states.
- Freeze the 11-test baseline.

**Exit gate:** `git log` shows a commit; `./gradlew :app:assembleDebug :app:assembleRelease testDebugUnitTest` exits 0; the JUnit XML contains exactly 11 `<testcase>` elements; `adb devices` lists **both** AVDs; baseline stills saved outside the repo; `aapt dump permissions` on the debug APK shows `VIBRATE`.

---

### Phase 1 — Module split and the pure core

Create the `:core` module (`kotlin("jvm")`) and move every numeric primitive into it. This phase is what makes every later numeric claim testable.

- `SpringSimulation` as the **analytic closed form** from the spec's own Physics section, not the spec's sample Euler step (Finding 11). Re-seed `(x₀, v₀)` from live state on every retarget; expose `value` and `velocity`; `isSettled` per the spec's `|v| < 0.05 && |x − target| < 0.05`. Guard `ζ ≥ 1` even though no spec spring needs it.
- `SpringCatalog` — the spec's 10 tabulated entries **plus the 2 inferred ones from Finding 10**, all constructed from `(T, ζ)` so `k` and `c` are *derived*, never hard-coded. Tag each entry with its provenance (`spec-table` vs `inferred`).
- `DensityNormalizer` — the physical conversion with the `[0.5, 2.0]` guard band and canonical fallback (Finding 4).
- `ResponsiveGeometryEngine` — the spec's `computeCompactBounds()` / `computeExpandedBounds()`, plus the per-state content widths (idle 125; music 236, timer 228, call 224, charging 252; floor `max(cutoutW + 32, 120)`).
- `SuperellipsePath` (Lamé, `n = 5`), `SquirclePath` (the existing Bézier constants), `ShapeSelector` (the unified rule from Finding 2).
- `MetaballField` — SDF + **polynomial** `smin`, density-scaled `k` (Finding 5).
- `VibrantExtractor` — with the non-vibrant fallback (Finding 6).
- `ColorTokens`, `TypeTokens` — every hex and every tracking value from the spec.

**Exit gate:** `./gradlew :core:test` green, and critically `grep -rE 'import (android|androidx)\.' core/src` returns **nothing** — compiler-enforced, not convention. The suite asserts:
- for every catalogue entry, `|k − (2π/T)²| < 0.2` and `|c − 2ζ√k| < 0.1`
- simulated overshoot from each `(T, ζ)` matches the closed form `exp(−ζπ/√(1−ζ²))` within 1%
- `ShapeSelector` returns *superellipse* for the five capsule states and *squircle* only where a straight edge exists
- discretised curvature along a generated capsule has **no step** greater than 1e-3
- `DensityNormalizer` returns canonical dp for `xdpi ∈ {0, 1e6, −1}`, and the tabulated dp for each of the five real device profiles
- `VibrantExtractor` returns white for grey, near-black and near-white inputs

---

### Phase 2 — Shape, surface optics, typography

Wire `:core` geometry into Compose and fix the visual tokens.

- Rewrite `IslandShape.createOutline` to delegate to `ShapeSelector` — **removes the stadium branch**, correcting four of five states (Finding 3).
- Add the `rgba(255,255,255,0.11)` keyline at 0.5 dp as a stroke *inside* the clip boundary.
- Replace the single 18 dp shadow with the spec's dual pair: ambient `(0, 2 dp)` blur 6 α 0.35 **plus** elevation `(0, 14 dp)` blur 28 α 0.22.
- Apply `TypeTokens` with real tracking, and per-glyph stroke in `IslandGlyphs.kt` (Finding 7).
- Bundle Inter, or Roboto Flex with `opsz` pinned 12 compact / 16 expanded. Tabular numerals are already enforced — keep them.

**Exit gate:** Compose screenshot tests of all five shapes at 1× and 3× density; a curvature-continuity check run against the **rendered** outline, not the source model; keyline and shadow layer assertions.

---

### Phase 3 — Motion engine and the dual delivery mode

- `DynamicIslandMotionEngine` driving one `MorphFrame` per frame so width, height, radius and both content alphas cannot desynchronise. Velocity continuity is automatic from Finding 11's re-seeding (checklist item 60).
- The spec's choreography from **area** progress (Finding 15): `alphaCompact = (1 − progress/0.30).coerceIn(0,1)`, `alphaExpanded = ((progress − 0.70)/0.30).coerceIn(0,1)`.
- **Constant-size render pipeline** (Finding 16): the host composable is allocated once at the expanded card's maximum bounds; the pill outline is a **reused `Path`** regenerated per frame; all content moves via `graphicsLayer` only. `MorphFrame` is consumed inside `Canvas`/`drawBehind`, never read from a composable — otherwise it triggers recomposition and defeats the design.
- Emit a per-frame **trace file** (`t, w, h, r, alphaCompact, alphaExpanded`) so motion is verifiable by fitting rather than by eye. This is the mechanism behind the exit gate.
- `OverlayWindowHost` with **three strategies** behind one interface, chosen by a fail-closed runtime probe logging `mode=<X> reason=<...>` (Finding 14). In the public strategies the window rect **snaps to target size at t = 0 ms** and the growing pill renders inside a transparent canvas — `updateViewLayout` must never be called during animation.
- `CutoutReader` on `WindowInsets.displayCutout`, finally giving the cutout detection a live call site (it has none today).

**Exit gate:** a fitted trace. For a full expand, the emitted `MorphFrame` sequence must fit `x(t) = target + e^{−ζω₀t}[A cos(ω_d t) + B sin(ω_d t)]` with recovered `T` and `ζ` within **±5%** of `(0.48, 0.80)`. Plus a Perfetto trace showing **zero `updateViewLayout` calls during animation** and every frame inside the 8.33 ms budget at 120 Hz.

---

### Phase 4 — State machine, gestures, hit rects

- Extend to the spec's seven states: add `COMPACT_PRESSED`, `CONCEALED`, `TRANSITIONING` alongside the existing five.
- `TransitionTable` encoding all 13 spec rows as data, with `IDLE → COMPACT` and `COMPACT → IDLE` filled in from Finding 10.
- Gestures per the spec's model: tap releasing in < 200 ms **and** travelling < 8 dp → open app (does **not** expand); hold ≥ 200 ms → expand; horizontal inward swipe → conceal; upward fling or outside tap → collapse.
- Scale punch to **0.96** on touch-down, on `(T = 0.22, ζ = 0.62)`.
- Hit rect per the spec: `top −6 dp, bottom +10 dp, left −12 dp, right +12 dp`.
- Route resolution through `IslandPriorityResolver` with `ActivityRecord(priorityClass, relevanceScore, startTime)` and the spec's descending sort — **preserving** the existing tiering constants and the 7 passing tests.

**Exit gate:** `TransitionTableTest` asserts exactly 13 `(from, event)` keys with no duplicates, each producing the spec's target state. Gesture tests sweep the boundary region (190–210 ms, 7–9 dp) over a synthetic event stream. The 7 existing arbiter tests still pass.

---

### Phase 5 — Haptics

- `HapticEngine` on `VibrationEffect.Composition`: `PRIMITIVE_CLICK` 0.7 on hold, `PRIMITIVE_TICK` 0.4 at the 70% expansion milestone, `PRIMITIVE_LOW_TICK` 1.0 on action tap, and the spec's dual-pulse envelope (180 Hz, 80 ms on / 40 off / 80 on) for timer-zero.
- Probe `Vibrator.areAllPrimitivesSupported()` and fall back to `VibrationEffect.createOneShot(18, 180)`.

**Exit gate:** capability-probe unit test; on the AVD a logged primitive-composition trace whose offsets match the spec's table (0 ms / 140 ms / 0 ms).

---

### Phase 6 — Activity content fidelity

- **Media** — 3-bar equalizer (spec: 2 dp bars, 2 dp apart, in a 16×14 box; the current widget draws **5** bars at 3 dp); artwork 18×18 r4 continuous compact, 48×48 r10 expanded; title/artist at the corrected alphas.
- **Timer** — add the circular countdown ring the spec requires (H = 130 dp); it is **absent entirely today**. Digits swap in fixed-width slots, never crossfade or slide.
- **Call** — ringing banner 370×82 with circular Decline `#FF453A` / Accept `#30D158`; expanded 160 dp card with route, mute and end; compact green phone glyph + 13 sp tnum duration + voice waveform.
- **Navigation** — turn arrow rotated by a `(T = 0.35, ζ = 0.72)` spring. **Morph the vector, never crossfade assets.**
- **Privacy dots** — 4 dp camera/mic indicator that **detaches outward** when a Live Activity occupies the pill, on `(T = 0.35, ζ = 0.75)`.
- **Face ID HUD** — 145×145 dp square, r 40 dp, path-trim stroke 0→1 over 220 ms with 1.15× overshoot, shrinking back on `(T = 0.30, ζ = 0.90)`.
- **Screen recording** — 1.0 Hz sine pulse.
- **Wire the dead `InCallService` path.** `DynamicIslandInCallService.onCallAdded/onCallRemoved` currently forward nothing and `TelecomCallTracker.onInCallServiceCallAdded/Removed` are never invoked, so rich caller data and in-island call control are non-functional despite being fully written.

**Exit gate:** the spec's iconography table asserted as a parameter test (box, stroke, optical bounds per glyph); per-activity geometry asserted against `ResponsiveGeometryEngine`; on the AVD, `adb emu gsm call` produces a ringing island and `adb emu gsm cancel` returns it to idle.

---

### Phase 7 — Advanced visual

- `MetaballShader` AGSL behind `Build.VERSION.SDK_INT >= 33`, with the **parametric Bézier bridge** as a first-class fallback below it — not a stub. Break distance 14 dp.
- Album-art dominant-vibrant extraction driving the equalizer tint and media accent.
- OLED handling: the 16 ms alpha ramp for content emerging from black; the 180 s four-step pixel shift `(0,0) → (+1,0) → (+1,+1) → (0,+1)`, driven by an injectable clock so the sequence is testable.

**Exit gate:** GPU Inspector confirms under 2 ms/frame; a shader-vs-Bézier side-by-side capture at identical separation; the pixel-shift sequence asserted by advancing a test clock, not by waiting 12 minutes.

---

### Phase 8 — Accessibility, resilience, edge cases

- TalkBack: one unified node for the compact island with `contentDescription` + `stateDescription`; focus moves into the expanded card; a custom `ACTION_DISMISS`.
- Reduce Motion (`Settings.Global.ANIMATOR_DURATION_SCALE`): springs → ζ = 1.0 or a 150 ms crossfade; scale punch bypassed; metaball replaced by an instant alpha.
- Rotation → landscape: tear down or dock the overlay.
- `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`: release album-art bitmaps and shader render targets, keep vector primitives.
- Telephony preempts navigation; navigation collapses to the 37 dp secondary circle and restores on call end.
- Crash recovery: restore active state within 500 ms.

**Exit gate:** `uiautomator dump` shows exactly one island node carrying the active activity name and a dismiss action; a Reduce-Motion run produces **zero** spring oscillation in the trace file; an orientation-change capture set; a kill-and-restart shows `restoreMs ≤ 500`.

---

### Phase 9 — Conformance audit

Drive every reachable checklist item against the real-event table below and publish evidence.

**Exit gate:** a conformance report marking all 100 items pass / partial / not-applicable, each with the capture or trace that proves it. The unprovable items from the section below are named explicitly with their reason — **not** silently marked pass.

---

## Verification

### The device problem, solved without injection

The app is deliberately live-only and **no injector will be added**. Fortunately the AVD can produce *genuine* system events, so live-only verification is achievable:

| island state | real trigger | command |
|---|---|---|
| Media compact / expanded | real media session | `adb shell cmd media_session dispatch play` (or start a real player app) |
| Timer compact / expanded | real chronometer notification | `adb shell cmd notification post -t Timer -c alarm <tag> "Timer"` |
| Notification peek | real notification post | `adb shell cmd notification post -t Title <tag> "body"` |
| Charging peek | real battery broadcast | `adb shell dumpsys battery set ac 1` then `set level 68` |
| Ringer / silent switch | real ringer broadcast | `adb shell cmd audio set-ringer-mode silent` |
| Incoming call | **real telephony call on the emulator** | `adb emu gsm call 5551234` |
| Active call | real call answer | `adb emu gsm accept 5551234` |
| Call ended | real call teardown | `adb emu gsm cancel 5551234` |
| Screen recording | real MediaProjection | `adb shell screenrecord /sdcard/cap.mp4` |
| Landscape | real rotation | `adb shell settings put system user_rotation 1` |
| Reduce Motion | **real accessibility setting** | `adb shell settings put global animator_duration_scale 0` |
| Multi-activity | two genuine sources at once | start a real media session, then post a real timer notification |

`cmd notification post`, `dumpsys battery`, `emu gsm`, `cmd audio`, `screenrecord` and the accessibility settings all drive the **real framework** through the same broadcast and binder paths a physical device uses. The island receives exactly the events it would in the field. This is live data, not injection, so the live-only policy is preserved in full.

One honest caveat to record: the emulator's telephony stack synthesises the *call*, so `Call.Details` carries a number and no contact name. That exercises the ringing → active → disconnected lifecycle and the InCallService bridge correctly, but **caller-name resolution and the monogram avatar cannot be verified here** — they need a real SIM and a real contact. Say so in the Phase 9 report rather than marking those items green.

### Layer-by-layer

1. **Core logic** — pure JVM unit tests, no Robolectric. Spring constants, overshoot, settling, curvature continuity, density normalisation, colour clamps, geometry formulas, the transition matrix, gesture boundaries. Fast, deterministic, runs in CI without a device.
2. **Rendering** — Compose screenshot tests at 1×, 2× and 3× density for every state, diffed against the spec's geometry.
3. **Integration** — the AVD, driven by the real-event table above.
4. **Motion** — Perfetto traces; fitted `(T, ζ)` within ±5%; zero dropped frames; zero `updateViewLayout` during animation.
5. **Checklist** — all 100 spec items, each with evidence.

### Explicitly not provable in this environment

To be recorded honestly in the Phase 9 report rather than glossed — each of these is a genuine gap, not a technicality:

- **Cutout alignment** (items 18, 82) — an emulator's `DisplayCutout` is synthetic. Full fidelity needs a real punch-hole device.
- **Optical items** (items 12, 13, 14) — true `0.0 cd/m²` emission, the 1.5 px hairline and the keyline alpha need a colorimeter and a dark room.
- **SSIM ≥ 0.985** against iPhone captures — requires iPhone 15 Pro captures that were never made. This is the spec's headline acceptance metric and it is simply unmeasurable here.
- **Physical input latency ≤ 35 ms** — needs the optoelectronic probe and oscilloscope the spec describes.
- **120 fps** — cannot be measured if the emulator's panel is capped at 60 Hz. Express the gate against the *measured* panel rate so the claim stays honest, and confirm the real refresh rate during Phase 0.
- **Caller name / monogram avatar** — the emulator synthesises calls without contact data (see above).
- **Bluetooth accessory telemetry** — no real earbuds. Note that the AirPods beacon decoder (`SystemStateObserver.parseAppleAirPodsData`) is **dead code that is never called**, so it would need wiring *and* hardware before it could be claimed at all.
- **Mic / camera hardware state** — genuinely unreachable from a Play-Store app: `WATCH_APPOPS` is signature-level. The spec's privacy-dot items are implementable only as *notification-inferred* state, and the plan should say so rather than claim hardware truth.
- **True navigation bearing** — Maps exposes no reliable bearing to a third party. The turn-arrow animation is implementable; its *data source* is inferred, not authoritative.

---

## Open questions

Three places where the spec is genuinely ambiguous. Each has a default below so implementation is not blocked, but the choice is a judgement call worth confirming as the relevant phase starts.

1. **Choreography driver.** Area progress is chosen (Finding 15) because it is the only formulation under which the spec's own frame-9 state is self-consistent. Width progress crosses the threshold closer to the spec's stated ~149 ms. If the visual result reads early or late, this is the knob.
2. **`COMPACT → IDLE` spring.** `T = 0.36` is specified with no `ζ`. `0.86` is inferred from the snappy-collapse family (Finding 10). 
3. **Superellipse exponent.** The spec gives `n ≈ 4.4–5.0` and separately says the Bezier smoothing ratio is `p ≈ 0.618`. These describe different curves. Plan assumes `n = 5` (the spec's most-repeated value, and the one its `L = 1.528665` shoulder constant is derived from).

---

## Risks

| risk | why it matters | mitigation |
|---|---|---|
| Physical density on real devices | `xdpi` is unreliable on many ROMs; a bad read makes the island visibly the wrong size | Guard band `[0.5, 2.0]` + canonical fallback; the fallback is the common path, not an error path |
| The spec contradicts itself in five places | Implementing it literally in two places at once produces bugs | Findings 1, 2, 5, 11, 15 each fix a rule with a stated winner |
| Naive spring integration | Biases the spring stiffer *and* more damped — it looks like a spring but is the wrong spring | Analytic closed form (Finding 11); sub-stepped Euler kept only as a test oracle |
| Per-frame layout in Compose | `Modifier.width/height` per frame triggers layout 120×/s; the 120 fps goal becomes unreachable | Constant-size host + reused `Path` + `graphicsLayer`-only content (Finding 16) |
| OEM insets path is hidden API | May be unselectable on modern devices, so "auto-select" could never pick it | Three strategies with a fail-closed probe that logs its reason (Finding 14) |
| AGSL is API 33+ | Below that, detachment must not silently degrade | Bézier fallback is a first-class path with its own gate, verified on a **real** older AVD (Finding 17) |
| Nine phases is a long run | The app must stay installable and demoable throughout | Every phase ends with `./gradlew test` green and an installable build |
| Removing the stadium branch changes every capture | The existing 22 screenshots become stale | Expected — re-capture in Phase 9; the Phase 0 baseline set is the "before" |

## Out of scope

- OEM firmware / SystemUI integration. The spec's second delivery mode is implemented as an insets *strategy* behind a probe, not as a SystemUI fork.
- Real APNs / FCM Live Activity push. The spec's push pipeline needs a server and an app ecosystem.
- Shipping a third-party Live Activity SDK. The spec implies one; this plan covers the system surface only.
- A state injector. The app stays live-only; every verification gate drives genuine framework events (see Verification).
