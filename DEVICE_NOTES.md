> Historical source-archive reference. Claims and counts below are not current validation. See docs/TEST_REPORT.md, docs/FEATURES.md and docs/COMPATIBILITY_MATRIX.md for this build.

# Empirical Device Notes — `island_test` AVD

Measured facts from the primary verification device. These are observations, not
spec-derived values, and several of them **contradict the specification**.
Where they conflict, the device wins — the spec is a description of an iPhone,
and this is an emulator.

## Device profile

| property | value |
|---|---|
| AVD name | `island_test` |
| hardware profile | Pixel 7 (`hw.device.name = pixel_7`) |
| system image | `system-images;android-35;google_apis;x86_64` |
| panel | 1080 × 2400 px |
| `densityDpi` | 420 |
| `xdpi / ydpi` | **420 / 420** |
| `density` (scale) | 2.625 |
| **panel refresh** | **60.000004 Hz — single supported mode, no alternatives** |
| RAM | 1536 MB |

## Finding A — this device cannot exercise physical-density normalization

`xdpi == densityDpi == 420`, so the scale ratio `xdpi / densityDpi` is exactly
`1.000`. The physical-match path in `DensityNormalizer` is therefore
**indistinguishable from the canonical path on this device**.

Consequence: physical normalization must be verified with **synthetic device
profiles** in unit tests. It can never be "verified on device" here. Any claim
that the island matches an iPhone's physical size is unprovable on this
hardware — say so in the Phase 9 report rather than implying otherwise.

## Finding B — the 120 fps gate is unmeasurable here

The panel advertises exactly one mode at 60.000004 Hz. There are no alternative
refresh rates and no `frameRateOverride` capability worth exercising.

Consequence: the plan's frame-budget gate is expressed against the **measured
panel rate** (16.67 ms), not 8.33 ms. A 120 fps claim cannot be made from this
device. Note this in the conformance report.

## Finding A2 — the naive-vs-physical error has both signs across real devices

Correcting the record on Finding 4. The target is **20.707 mm** of glass
(125 pt × 3 px ÷ 460 ppi). A naive `125 dp` island renders:

| device | xdpi | densityDpi | naive 125 dp | direction | correct dp |
|---|---|---|---|---|---|
| Pixel 8 Pro | 489 | 420 | 17.04 mm | **too small** (−3.66) | 151.86 |
| Pixel 8 | 428 | 420 | 19.47 mm | too small (−1.23) | 132.92 |
| Galaxy S24 | 416 | 450 | 21.47 mm | **too big** (+0.76) | 120.58 |
| OnePlus 12 | 510 | 480 | 18.68 mm | too small (−2.03) | 138.59 |
| reference 460 ppi | 460 | 460 | 19.84 mm | too small (−0.86) | 130.43 |

The direction flips because it depends on which of `xdpi` and `densityDpi` is
larger. On the Galaxy S24 the density bucket (450) is *inflated above* the
physical density (416 ppi), so 125 dp is physically larger than intended. Every
other listed device has the bucket *below* the physical density, so the same
code under-sizes the island.

**No fixed correction constant can fix this — only the per-device ratio.**

Also worth stating plainly, because it is counterintuitive: on a panel where
`xdpi == densityDpi`, the physically-correct width is **130.43 dp, not 125 dp**.
An iPhone *point* is not an Android *dp*; treating them as equal is a ~4% error
even in the best case.

## Finding C — the cutout bounding rect is inflated; the circle path is authoritative

`dumpsys display` reports:

```
DisplayCutout{insets=Rect(0, 136 - 0, 0)
  boundingRect={Bounds=[Rect(0, 0 - 0, 0), Rect(480, 0 - 625, 136), ...]}
  cutoutPathParserInfo={... cutoutSpec={M 506,68 a 34,34 0 1 0 68,0 34,34 0 1 0 -68,0 Z @left} ...}}
```

The **circular hole is 68 px diameter** (`a 34,34` arc, radius 34). The
reported bounding rect is **145 × 136 px** — Android pads it generously.

Applying the specification's own Android normalization formula to the padded
rect produces a broken island:

```
height_min = max(136 / 2.625 + 2 * 6, 36)
           = max(63.81, 36)
           = 63.81 dp          <- 1.7x the 37 dp capsule
```

An island 63.8 dp tall is not a Dynamic Island; the capsule's semicircular caps
require `height ≈ 37 dp`, and at 63.8 dp the shape degenerates.

Using the circle path instead:

```
circle dia  = 68 px / 2.625 = 25.90 dp
height_min  = max(25.90 + 12, 36) = 37.90 dp      <- fits the capsule family
```

**Rule:** derive cutout size from the circular path where present, and in all
cases **clamp the resulting island height into the capsule family** rather than
trusting `boundingRect.height`. `boundingRect` is a touch-occlusion rectangle,
not a description of the physical aperture.

## Finding D — the cutout is horizontally off-centre

Bounding rect spans x = 480..625, so its centre is **552.5 px**. Screen centre
is **540.0 px**. Offset: **+12.5 px**.

The spec says the island is "aligned symmetrically along the horizontal axis,
with its center matching the horizontal midpoint of the display." This device
disagrees.

**Rule:** align the island to the **cutout** centre, not the screen centre.
Aligning to the screen would place the island 12.5 px left of the physical
hole, producing a visible misalignment against the camera — exactly the
"no visual boundary visible between camera hole and pill" defect the spec
calls out as CRITICAL (checklist item 18).

## Finding E — baseline reproduction (pre-change state)

Captured to `screenshots/baseline/base_idle.png` at 1080 × 2400. The island is
**live and rendering** in the `Minimal` multi-activity state: a compact media
pill (music glyph leading, 5-bar equalizer trailing) with a detached bell bubble
to its right. Driven by a genuine media session and a genuine notification on
the emulator — no injected content.

Note the pill's equalizer renders **5 bars**. The specification requires
**three**. This is the expected pre-change state; see plan Finding 7 and
Phase 6.

## Finding F — measured geometry the tests now pin

Facts established while building `:core`, each now asserted rather than assumed.

**Superellipse curvature (n = 5 on 125×37).** Curvature is **zero at all four
axis points** and peaks at **0.14449** (radius 6.92) roughly 12 dp of arc from
an axis point, near `(60.96, 12.04)`. The zero at the axes is worth stating
because it is counterintuitive: for a Lamé curve with `n > 2` the `Fxx`/`Fyy`
terms vanish at the axes, so unlike an ellipse (`n = 2`) the axis points are
**not** the curvature extrema. A stadium's cap sits at a flat `1/18.5 = 0.0541`.

So the superellipse's shoulder is **2.7× tighter** than a semicircular cap. That
is precisely what makes it read as Apple's shape rather than a pill — a fact
worth protecting with a test.

**Perimeter** of that outline is ≈299 dp.

**The discrete-curvature estimator needs a `4×` factor.** Differentiating by
finite difference over arc distance `h` gives `x' ≈ dx/(2h)` and
`x'' ≈ ddx/h²`, so `k = |x'y'' − y'x''|/(x'²+y'²)^1.5` evaluates to
`4·|dx·ddy − dy·ddx|/(dx²+dy²)^1.5`. Without the factor every magnitude is
uniformly 4× too small. Verified against two closed forms.

**Curvature continuity is measured by convergence, not by a fixed threshold.** A
genuine G¹ discontinuity — the stadium's cap-to-edge junction — stays at ~0.054
no matter how finely the curve is sampled. A smooth curve sampled coarsely shows
a step that **halves when the sample count doubles**. Measured: 0.0191 → 0.0106
→ 0.0054 → 0.0027 → 0.0014 across 360…5760 samples, first-order, while the peak
converges to 0.14447. Any fixed "step must be below X" threshold either passes a
discontinuous shape or fails a correct one.

**Settle time is bounded by velocity, not position.** The specification's settle
test requires both `|Δx| < 0.05` and `|v| < 0.05`, in absolute units. For a
100 dp travel the position threshold fires at ≈0.56 s but the velocity threshold
not until ≈0.77 s — **the velocity threshold dominates**. A budget expressed as
a multiple of the 2% *position* settling time is far too small. This is a
property of the specification's own constants, not of the implementation.

**Trailing-zero floating point trap.** `0.45f / (1f/60f)` evaluates to
26.999998 in single precision, so `.toInt()` yields 26 and silently drops a
frame. Express durations as whole frame counts rather than dividing.

## Verification setup that works

```bash
A=/d/Android/sdk/platform-tools/adb.exe
$A install -r app/build/outputs/apk/debug/app-debug.apk
$A shell appops set com.dynamicisland.host SYSTEM_ALERT_WINDOW allow
$A shell cmd notification allow_listener \
    com.dynamicisland.host/com.dynamicisland.engine.ingestion.notifications.DynamicIslandNotificationListener
$A shell am start -n com.dynamicisland.host/.MainActivity
```

**Screenshot capture gotcha:** under Git Bash, `adb shell screencap -p /sdcard/x.png`
fails because MSYS rewrites `/sdcard/...` into a Windows path, and redirecting
to `/tmp` writes nowhere. Use `exec-out` streamed to an absolute Windows-style
path instead:

```bash
adb exec-out screencap -p > "D:/dyn island/screenshots/baseline/base_idle.png"
```

## Boot command

```bash
/d/Android/sdk/emulator/emulator.exe -avd island_test \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
```

`-no-window` is required: there is no display attached. `swiftshader_indirect`
is the software GPU, which is **slow** — do not use it for frame-pacing
measurements. Any Perfetto/GPU timing must be taken with hardware
acceleration, or the numbers are meaningless.
