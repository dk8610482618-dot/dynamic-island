package com.dynamicisland.core.physics

import com.dynamicisland.core.motion.DynamicIslandMotionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Motion quality gates.
 *
 * "Premium, not laggy, not sloppy" is a testable property, not a matter of
 * taste. Slop in an animation comes from a small number of specific defects, and
 * each one gets an assertion here so it cannot reappear:
 *
 *  1. **Wrong spring** — motion that looks springy but recovers the wrong
 *     parameters. The specification's own Euler integrator fails this by 20–31%.
 *  2. **Frame-rate dependence** — the same animation visibly differing between a
 *     60 Hz and a 120 Hz panel. Immediate slop.
 *  3. **Position or velocity discontinuity** — a jump when an animation is
 *     interrupted, which reads as a glitch.
 *  4. **Allocation in the frame path** — GC pauses produce dropped frames.
 *  5. **Overshoot wrong for the state** — a collapse that bounces looks cheap; a
 *     card that arrives without settling looks unfinished.
 *  6. **Outlines that change at the wrong rate** — geometry desynchronised from
 *     the spring.
 */
class MotionQualityTest {

    // =====================================================================
    // 1. The motion must be the *specified* spring
    // =====================================================================

    /**
     * Fit a simulated animation back to `(response, dampingRatio)` and require
     * the recovered values to match the target within ±5%.
     *
     * This is the specification's own acceptance criterion, and it is the test
     * its sample integrator fails. Fitting rather than comparing parameters is
     * the point: it measures the motion a viewer actually sees, not the numbers
     * a developer intended.
     */
    @Test
    fun `simulated motion recovers its specified spring within 5 percent`() {
        val cases = listOf(
            SpringCatalog.CompactToExpanded,
            SpringCatalog.ExpandedToCompact,
            SpringCatalog.TouchPunch,
            SpringCatalog.BifurcationSplit,
            SpringCatalog.SwipeConceal,
        )

        for (spec in cases) {
            val samples = simulate(spec, from = 0f, to = 100f, dt = 1f / 120f, seconds = 1.5f)
            val (fittedT, fittedZ) = fitSpring(samples, 0f, 100f)

            val tError = abs(fittedT - spec.response) / spec.response * 100f
            val zError = abs(fittedZ - spec.dampingRatio) / spec.dampingRatio * 100f

            assertTrue(
                "T=${spec.response} ζ=${spec.dampingRatio}: recovered T=${
                    "%.4f".format(fittedT)
                } (${"%.2f".format(tError)}% off) must be within 5%",
                tError <= 5f
            )
            assertTrue(
                "T=${spec.response} ζ=${spec.dampingRatio}: recovered ζ=${
                    "%.4f".format(fittedZ)
                } (${"%.2f".format(zError)}% off) must be within 5%",
                zError <= 5f
            )
        }
    }

    /**
     * Documents that the specification's own integrator would fail the test
     * above, so nobody re-adopts it as an "optimisation".
     *
     * Semi-implicit Euler at the specification's 33 ms clamp recovers a
     * materially different spring — stiffer and more damped. The motion still
     * *looks* like a spring, which is what makes it dangerous.
     */
    @Test
    fun `the specification's euler integrator would fail the fidelity gate`() {
        val spec = SpringCatalog.CompactToExpanded
        val dt = 0.033f

        val samples = ArrayList<Pair<Float, Float>>()
        var x = 0f
        var v = 0f
        val k = spec.stiffness
        val c = spec.damping
        var t = 0f
        repeat(40) {
            val a = -k * (x - 100f) - c * v
            v += a * dt
            x += v * dt
            t += dt
            samples.add(t to x)
        }

        val (fittedT, _) = fitSpring(samples, 0f, 100f)
        val tError = abs(fittedT - spec.response) / spec.response * 100f

        assertTrue(
            "with Euler at 33ms the recovered response is ${"%.4f".format(fittedT)} " +
                "against a specified ${spec.response} — a ${"%.1f".format(tError)}% error, " +
                "which is exactly the slop this project must not ship",
            tError > 5f
        )
    }

    // =====================================================================
    // 2. Frame-rate independence
    // =====================================================================

    /**
     * The same animation must look identical on a 60 Hz and a 120 Hz panel.
     *
     * Frame-rate dependence is the most visible kind of animation slop: the
     * motion feels different on different hardware, and no amount of tuning
     * fixes both.
     */
    @Test
    fun `animation is identical across 60 90 and 120 Hz panels`() {
        val spec = SpringCatalog.CompactToExpanded

        fun valueAt(elapsed: Float, fps: Int): Float {
            val sim = SpringSimulation(spec, 0f)
            sim.retarget(100f)
            var t = 0f
            val dt = 1f / fps
            while (t < elapsed - 1e-6f) {
                sim.update(minOf(dt, elapsed - t))
                t += dt
            }
            return sim.value
        }

        for (instant in listOf(0.05f, 0.10f, 0.20f, 0.35f, 0.50f)) {
            val at60 = valueAt(instant, 60)
            val at90 = valueAt(instant, 90)
            val at120 = valueAt(instant, 120)

            assertEquals(
                "at t=$instant s a 60 Hz panel must match a 120 Hz panel",
                at120, at60, 0.01f
            )
            assertEquals(
                "at t=$instant s a 90 Hz panel must match a 120 Hz panel",
                at120, at90, 0.01f
            )
        }
    }

    /**
     * A dropped frame must not corrupt the trajectory — it should simply arrive
     * later at the correct place.
     *
     * With a numerical integrator, one 3-frame stall changes the result
     * permanently. With the closed form it does not, because elapsed time is the
     * only state.
     */
    @Test
    fun `a dropped frame does not permanently corrupt the trajectory`() {
        val spec = SpringCatalog.CompactToExpanded
        val frame = 1f / 60f

        // Both runs must cover exactly the same elapsed time for the comparison
        // to mean anything.
        //
        // The stall is expressed as a whole number of frames, NOT as `0.05f`
        // divided by the frame time. That division is a trap: `0.45f / (1f/60f)`
        // evaluates to 26.999998 in single precision, so `.toInt()` yields 26 and
        // silently drops a frame — making the two runs differ by 1/60 s and
        // failing the equivalence check for reasons that have nothing to do with
        // the spring.
        val totalFrames = 30
        val framesBeforeStall = 10
        val stallFrames = 3
        val framesAfterStall = totalFrames - framesBeforeStall - stallFrames

        val smooth = SpringSimulation(spec, 0f).apply { retarget(100f) }
        repeat(totalFrames) { smooth.update(frame) }

        val stalled = SpringSimulation(spec, 0f).apply { retarget(100f) }
        repeat(framesBeforeStall) { stalled.update(frame) }
        stalled.update(stallFrames * frame)   // one long frame, three frames' worth
        repeat(framesAfterStall) { stalled.update(frame) }

        assertEquals(
            "test arithmetic: both runs must span the same elapsed time",
            smooth.elapsedSeconds(), stalled.elapsedSeconds(), 1e-4f
        )

        assertEquals(
            "a stall must not change where the animation ends up at a given time",
            smooth.value, stalled.value, 0.05f
        )
    }

    // =====================================================================
    // 3. Continuity — no jumps, ever
    // =====================================================================

    /**
     * Interrupting an animation at any point must produce no positional jump.
     *
     * Swept across the whole animation rather than sampled at a few points,
     * because a discontinuity can hide anywhere.
     */
    @Test
    fun `interruption is continuous at every point in the animation`() {
        val spec = SpringCatalog.CompactToExpanded
        val dt = 1f / 120f

        var worstPositionJump = 0f
        var worstVelocityJump = 0f

        // Sweep the interruption instant across the animation.
        for (frames in 1..60) {
            val sim = SpringSimulation(spec, 0f)
            sim.retarget(100f)
            repeat(frames) { sim.update(dt) }

            val p = sim.value
            val v = sim.velocity
            sim.retarget(40f)

            worstPositionJump = maxOf(worstPositionJump, abs(sim.value - p))
            worstVelocityJump = maxOf(worstVelocityJump, abs(sim.velocity - v))
        }

        assertEquals("no position jump anywhere", 0f, worstPositionJump, 1e-4f)
        assertEquals("no velocity jump anywhere", 0f, worstVelocityJump, 1e-3f)
    }

    /**
     * The position curve must be smooth frame to frame — no sudden acceleration
     * spikes that read as a stutter.
     *
     * ### The threshold is derived, not invented
     *
     * A spring's acceleration at displacement `d` from target is `k·d`. The
     * largest displacement is the full travel at `t = 0`, so the peak
     * acceleration is `k · travel`. A per-frame second difference is that
     * acceleration times `dt²`, so the analytic ceiling is:
     *
     * ```
     * max second difference = k · travel · dt²
     * ```
     *
     * For Compact→Expanded (k = 171.35, travel = 100 dp, dt = 1/120) that is
     * **1.19 dp**. An invented threshold such as "under 0.5 dp" is below the
     * mathematically necessary peak and would fail a perfectly smooth spring —
     * which is exactly what the first version of this test did.
     *
     * The assertion is therefore that the observed peak does not *exceed* the
     * analytic bound, which is a real statement: a kink or a discrete jump would
     * blow past it.
     */
    @Test
    fun `position advances smoothly with no acceleration spikes`() {
        val travel = 100f
        val dt = 1f / 120f

        for (spec in listOf(
            SpringCatalog.CompactToExpanded,
            SpringCatalog.ExpandedToCompact,
            SpringCatalog.TouchPunch,
        )) {
            val sim = SpringSimulation(spec, 0f)
            sim.retarget(travel)

            val positions = ArrayList<Float>()
            repeat(150) { sim.update(dt); positions.add(sim.value) }

            var observed = 0f
            for (i in 1 until positions.size - 1) {
                observed = maxOf(
                    observed,
                    abs(positions[i + 1] - 2 * positions[i] + positions[i - 1])
                )
            }

            val analyticCeiling = spec.stiffness * travel * dt * dt

            assertTrue(
                "T=${spec.response} ζ=${spec.dampingRatio}: peak per-frame second " +
                    "difference was ${"%.4f".format(observed)} dp against an analytic " +
                    "ceiling of ${"%.4f".format(analyticCeiling)} dp; exceeding it would " +
                    "mean a discontinuity, not a spring",
                observed <= analyticCeiling * 1.02f
            )
        }
    }

    /**
     * A discontinuity would show as a second difference far above the analytic
     * ceiling. Confirms the check above has teeth by measuring one.
     */
    @Test
    fun `the smoothness ceiling would catch an actual jump`() {
        val spec = SpringCatalog.CompactToExpanded
        val travel = 100f
        val dt = 1f / 120f
        val analyticCeiling = spec.stiffness * travel * dt * dt

        // A deliberately corrupted trajectory: one frame jumps 5 dp.
        val positions = ArrayList<Float>()
        val sim = SpringSimulation(spec, 0f)
        sim.retarget(travel)
        repeat(30) {
            sim.update(dt)
            positions.add(if (it == 15) sim.value + 5f else sim.value)
        }

        var observed = 0f
        for (i in 1 until positions.size - 1) {
            observed = maxOf(
                observed,
                abs(positions[i + 1] - 2 * positions[i] + positions[i - 1])
            )
        }

        assertTrue(
            "a 5 dp jump should produce a second difference (${"%.3f".format(observed)}) " +
                "well above the ${"%.3f".format(analyticCeiling)} ceiling",
            observed > analyticCeiling * 2f
        )
    }

    // =====================================================================
    // 4. Overshoot is appropriate per state
    // =====================================================================

    /**
     * Collapses must not bounce; arrivals may.
     *
     * A collapse that overshoots looks cheap and unfinished — the island should
     * snap shut decisively. This is a real perceptual rule, and it is why the
     * specification gives collapses high damping ratios (0.86–0.95) and
     * arrivals lower ones.
     */
    @Test
    fun `collapses do not bounce while arrivals may`() {
        val collapses = listOf(
            "EXPANDED_TO_COMPACT" to SpringCatalog.ExpandedToCompact,
            "SWIPE_CONCEAL" to SpringCatalog.SwipeConceal,
            "SWIPE_DISMISS" to SpringCatalog.SwipeDismiss,
            "ALERT_DISMISS" to SpringCatalog.AlertDismiss,
            "COMPACT_TO_IDLE" to SpringCatalog.CompactToIdle,
        )

        for ((name, spec) in collapses) {
            val overshoot = overshootPercent(spec, from = 100f, to = 0f)
            assertTrue(
                "$name: a collapse must not overshoot perceptibly, was " +
                    "${"%.2f".format(overshoot)}%",
                overshoot < 1.0f
            )
        }

        // An arrival is allowed — and expected — to overshoot a little.
        val arrivalOvershoot = overshootPercent(
            SpringCatalog.CompactToExpanded, from = 0f, to = 100f
        )
        assertTrue(
            "Compact→Expanded should overshoot slightly to feel alive, was " +
                "${"%.2f".format(arrivalOvershoot)}%",
            arrivalOvershoot in 0.5f..4f
        )
    }

    /** No animation may take absurdly long; anything past ~1.5 s feels broken. */
    @Test
    fun `no motion runs longer than a second and a half`() {
        for (entry in SpringCatalog.all) {
            val sim = SpringSimulation(entry.spec, 0f)
            sim.retarget(100f)
            var t = 0f
            val dt = 1f / 240f
            while (!sim.isSettled() && t < 2f) {
                sim.update(dt)
                t += dt
            }
            assertTrue(
                "${entry.name} took ${"%.2f".format(t)} s to settle",
                t < 1.5f
            )
        }
    }

    // =====================================================================
    // 5. No allocation in the frame path
    // =====================================================================

    /**
     * Stepping the simulation must not allocate.
     *
     * The specification requires "zero memory allocations inside draw and layout
     * passes" (checklist item 89) — GC pauses in the frame path are the single
     * most common cause of dropped frames, and a dropped frame is exactly the
     * "laggy" failure this must avoid.
     *
     * The check is a heap-size delta across many steps. It is coarse by design:
     * a per-frame allocation of even 100 bytes over 200 000 steps would be 20 MB
     * and is comfortably above the noise floor.
     */
    @Test
    fun `stepping the simulation does not allocate`() {
        val sim = SpringSimulation(SpringCatalog.CompactToExpanded, 0f)
        sim.retarget(100f)

        // Warm up so JIT and any first-call caches settle before measuring.
        repeat(50_000) { sim.update(1f / 120f) }

        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        repeat(200_000) { sim.update(1f / 120f) }
        val after = runtime.totalMemory() - runtime.freeMemory()

        val growth = after - before
        assertTrue(
            "200k simulation steps grew the heap by $growth bytes; the frame path " +
                "must not allocate, or GC will drop frames",
            growth < 1_000_000L
        )
    }

    // =====================================================================
    // 6. Content must be visible in every resting state
    // =====================================================================

    /**
     * Every settled presentation must actually show its content.
     *
     * This exists because it was broken. Choreography is driven by normalised
     * area, and the span was originally the *expanded card* — 370x160. The alert
     * peek is 300x52, only 15 600 dp², which reaches 19.5% progress against that
     * span: below the 70% gate that turns `expandedAlpha` on. The alert therefore
     * animated open and then rendered **invisible**.
     *
     * It is a quiet failure — the island is on screen, the animation runs, and
     * nothing appears — so it gets a test rather than a comment.
     */
    @Test
    fun `every transition settles with visible content and never blanks`() {
        // The four presentations, by area. `bottomless` is false for the pill.
        data class Presentation(val name: String, val w: Float, val h: Float, val bottomless: Boolean)

        val pill = Presentation(
            "compact pill",
            DynamicIslandMotionEngine.INITIAL_WIDTH_DP,
            DynamicIslandMotionEngine.INITIAL_HEIGHT_DP,
            bottomless = false
        )
        val alert = Presentation(
            "alert peek",
            DynamicIslandMotionEngine.ALERT_WIDTH_DP,
            DynamicIslandMotionEngine.ALERT_HEIGHT_DP,
            bottomless = true
        )
        val card = Presentation(
            "expanded card",
            DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            bottomless = true
        )

        val all = listOf(pill, alert, card)

        for (from in all) {
            for (to in all) {
                if (from === to) continue

                val engine = DynamicIslandMotionEngine()
                engine.snapTo(from.w, from.h, radiusDp = 18f, secondaryVisible = false, isWide = from.bottomless)
                engine.moveTo(
                    targetWidthDp = to.w,
                    targetHeightDp = to.h,
                    targetRadiusDp = 18f,
                    spec = SpringCatalog.CompactToExpanded,
                    secondaryVisible = false,
                    targetIsWide = to.bottomless
                )

                var settled = false
                var blankFrames = 0
                var sawContentAtStart = false
                repeat(600) {
                    val frame = engine.advance(1f / 120f)
                    if (it > 2 &&
                        frame.compactAlpha < 0.02f &&
                        frame.expandedAlpha < 0.02f
                    ) {
                        blankFrames++
                    }
                    if (it == 0 &&
                        maxOf(frame.compactAlpha, frame.expandedAlpha) > 0.98f
                    ) {
                        sawContentAtStart = true
                    }
                    if (frame.settled) settled = true
                }

                assertTrue("${from.name} -> ${to.name} never settled", settled)

                val end = engine.currentFrame
                assertTrue(
                    "${from.name} -> ${to.name} settles with everything at " +
                        "compact=${"%.3f".format(end.compactAlpha)} " +
                        "expanded=${"%.3f".format(end.expandedAlpha)} — " +
                        "the island would end up showing nothing",
                    maxOf(end.compactAlpha, end.expandedAlpha) >= 0.99f
                )

                assertTrue(
                    "${from.name} -> ${to.name} has $blankFrames frames with no " +
                        "visible content — the island blinks mid-transition",
                    blankFrames == 0
                )

                assertTrue(
                    "${from.name} -> ${to.name} starts with nothing visible",
                    sawContentAtStart
                )
            }
        }
    }

    /**
     * A snapped resting state shows the right layer.
     *
     * A resting pill is fully compact; a resting card is fully expanded. The
     * original span-based model reported *every* snapped state as fully
     * progressed, which left the compact pill rendering no content at all.
     */
    @Test
    fun `a snapped resting state shows the layer that belongs to it`() {
        val engine = DynamicIslandMotionEngine()

        engine.snapTo(
            DynamicIslandMotionEngine.INITIAL_WIDTH_DP,
            DynamicIslandMotionEngine.INITIAL_HEIGHT_DP,
            radiusDp = DynamicIslandMotionEngine.INITIAL_RADIUS_DP,
            secondaryVisible = false,
            isWide = false
        )
        assertTrue("a resting pill must show compact content",
            engine.currentFrame.compactAlpha >= 0.99f)
        assertTrue("a resting pill must not show card content",
            engine.currentFrame.expandedAlpha <= 0.01f)

        engine.snapTo(
            DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            radiusDp = 44f,
            secondaryVisible = false,
            isWide = true
        )
        assertTrue("a resting card must show its content",
            engine.currentFrame.expandedAlpha >= 0.99f)
    }

    /**
     * The alert peek must be *dimmer* than the expanded card while growing, so
     * card content never appears inside a pill too small to hold it. This is the
     * other half of the gate above: fixing visibility must not remove the
     * staging.
     */
    @Test
    fun `card content is withheld until the pill is large enough to hold it`() {
        val engine = DynamicIslandMotionEngine()
        val dt = 1f / 120f

        // Compact content is visible in the *starting* frame. This is checked
        // before any advance because the alpha falls monotonically from the
        // moment the pill starts growing — it is only ever 1.0 at rest.
        assertTrue(
            "compact content must be fully visible before the pill starts growing",
            engine.currentFrame.compactAlpha >= 0.99f
        )

        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            targetRadiusDp = 44f,
            spec = SpringCatalog.CompactToExpanded,
            secondaryVisible = false,
            targetIsWide = true
        )

        var expandedAppearedAt = -1f
        var compactGoneAt = -1f
        var t = 0f
        repeat(240) {
            val frame = engine.advance(dt)
            t += dt
            if (expandedAppearedAt < 0f && frame.expandedAlpha > 0.01f) {
                expandedAppearedAt = t
            }
            if (compactGoneAt < 0f && frame.compactAlpha <= 0.01f) {
                compactGoneAt = t
            }
        }

        assertTrue(
            "expanded content should not appear before the pill has grown " +
                "(appeared at ${"%.3f".format(expandedAppearedAt)}s)",
            expandedAppearedAt > 0.10f
        )

        // The two layers must OVERLAP. An earlier version of this test asserted
        // the opposite — that the compact layer clears before the card arrives —
        // and that assertion was simply wrong: it encoded a blank gap between
        // the ramps, which renders as the island blinking mid-transition.
        assertTrue(
            "compact content (gone at ${"%.3f".format(compactGoneAt)}s) must still be " +
                "on screen when card content arrives at " +
                "${"%.3f".format(expandedAppearedAt)}s — otherwise the island blanks",
            compactGoneAt > expandedAppearedAt
        )
    }

    /**
     * Collapse reversion must maintain a smooth, continuous crossfade as the
     * expanded card contracts back to the compact pill, without cutting content or
     * jumping to zero opacity prematurely.
     */
    @Test
    fun `collapse reversion maintains continuous crossfade without cutting content`() {
        val engine = DynamicIslandMotionEngine()
        engine.snapTo(
            DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            radiusDp = 44f,
            secondaryVisible = false,
            isWide = true
        )

        // Collapsing back to compact pill
        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.INITIAL_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.INITIAL_HEIGHT_DP,
            targetRadiusDp = DynamicIslandMotionEngine.INITIAL_RADIUS_DP,
            spec = SpringCatalog.ExpandedToCompact,
            secondaryVisible = false,
            targetIsWide = false
        )

        val dt = 1f / 120f
        var sawExpandedFade = false
        var sawCompactFadeIn = false
        repeat(120) {
            val frame = engine.advance(dt)
            if (frame.expandedAlpha in 0.05f..0.95f) sawExpandedFade = true
            if (frame.compactAlpha in 0.05f..0.95f) sawCompactFadeIn = true
        }

        assertTrue("Expanded layer must fade smoothly during collapse", sawExpandedFade)
        assertTrue("Compact layer must fade in smoothly during collapse", sawCompactFadeIn)
        assertTrue("After collapse, compact content must be fully visible", engine.currentFrame.compactAlpha >= 0.99f)
        assertTrue("After collapse, expanded content must be hidden", engine.currentFrame.expandedAlpha <= 0.01f)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun simulate(
        spec: SpringSpec, from: Float, to: Float, dt: Float, seconds: Float
    ): List<Pair<Float, Float>> {
        val sim = SpringSimulation(spec, from)
        sim.retarget(to)
        val out = ArrayList<Pair<Float, Float>>()
        var t = 0f
        while (t < seconds) {
            sim.update(dt)
            t += dt
            out.add(t to sim.value)
        }
        return out
    }

    private fun overshootPercent(spec: SpringSpec, from: Float, to: Float): Float {
        val sim = SpringSimulation(spec, from)
        sim.retarget(to)
        val direction = if (to >= from) 1f else -1f
        val travel = abs(to - from)
        var peak = 0f
        repeat(2000) {
            sim.update(1f / 1000f)
            val past = (sim.value - to) * direction
            if (past > peak) peak = past
        }
        return peak / travel * 100f
    }

    /**
     * Recover `(response, dampingRatio)` from a trajectory by coarse grid search
     * followed by refinement. Stands in for a proper least-squares fit; the
     * search range comfortably contains every catalogue spring.
     */
    private fun fitSpring(
        samples: List<Pair<Float, Float>>, from: Float, to: Float
    ): Pair<Float, Float> {
        fun sse(t: Float, z: Float): Float {
            val w0 = (2.0 * Math.PI / t).toFloat()
            val wd = w0 * kotlin.math.sqrt(1f - z * z)
            val a = from - to
            val b = if (wd > 1e-6f) (z * w0 * a) / wd else 0f
            var sum = 0f
            for ((time, observed) in samples) {
                val decay = kotlin.math.exp(-z * w0 * time)
                val predicted = to + decay * (
                    a * kotlin.math.cos(wd * time) + b * kotlin.math.sin(wd * time)
                    )
                val e = observed - predicted
                sum += e * e
            }
            return sum
        }

        var bestT = 0.4f
        var bestZ = 0.8f
        var bestSse = Float.MAX_VALUE

        var t = 0.15f
        while (t <= 0.70f) {
            var z = 0.40f
            while (z <= 0.999f) {
                val s = sse(t, z)
                if (s < bestSse) { bestSse = s; bestT = t; bestZ = z }
                z += 0.02f
            }
            t += 0.01f
        }

        // Refine.
        var refineT = bestT
        var refineZ = bestZ
        var stepT = 0.02f
        var stepZ = 0.02f
        repeat(12) {
            var improved = false
            for (dt in listOf(-stepT, 0f, stepT)) {
                for (dz in listOf(-stepZ, 0f, stepZ)) {
                    val tt = refineT + dt
                    val zz = (refineZ + dz).coerceIn(0.05f, 0.999f)
                    if (tt <= 0f) continue
                    val s = sse(tt, zz)
                    if (s < bestSse) {
                        bestSse = s; refineT = tt; refineZ = zz; improved = true
                    }
                }
            }
            if (!improved) { stepT *= 0.5f; stepZ *= 0.5f }
        }

        return refineT to refineZ
    }
}
