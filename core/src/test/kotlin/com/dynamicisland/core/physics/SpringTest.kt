package com.dynamicisland.core.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The specification's numeric claims, asserted for real.
 *
 * These tests run on a plain JVM with no Android framework and — critically —
 * **no `isReturnDefaultValues`**. Nothing here can silently pass by returning a
 * stub. (The Android module's old `SquircleShapeTest` did rely on that flag, so
 * its geometry assertions never actually executed; the geometry has since moved
 * into this module and is asserted against real numbers.)
 */
class SpringTest {

    // =====================================================================
    // SpringSpec: derived stiffness and damping
    // =====================================================================

    /**
     * k = (2π/T)² for every entry the specification tabulates.
     *
     * The specification lists both `(T, ζ)` and the derived `k`/`c`. They agree
     * to rounding, which confirms `(T, ζ)` is the intended source. This test
     * pins that relationship so the two can never drift.
     */
    @Test
    fun `stiffness matches the specification's tabulated k for every spec-table entry`() {
        // name -> k stated in the specification's animation parameter table
        val expected = mapOf(
            "COMPACT_TO_EXPANDED" to 171.2f,
            "EXPANDED_TO_COMPACT" to 246.7f,
            "TOUCH_PUNCH" to 815.7f,
            "BIFURCATION_SPLIT" to 145.9f,
            "BIFURCATION_MERGE" to 203.9f,
            "SWIPE_DISMISS" to 385.5f,
            "SWIPE_CONCEAL" to 438.6f,
            "SWIPE_RESTORE" to 273.4f,
            "ALERT_BOUNCE_ARRIVE" to 157.9f,
            "ALERT_DISMISS" to 322.2f,
        )

        for ((name, statedK) in expected) {
            val entry = SpringCatalog.all.first { it.name == name }
            assertEquals(
                "$name: stiffness should be (2π/T)² and match the specification's k",
                statedK, entry.spec.stiffness, 0.2f
            )
        }
    }

    /** c = 2ζ√k = 2ζω₀, against the specification's tabulated damping. */
    @Test
    fun `damping matches the specification's tabulated c for every spec-table entry`() {
        val expected = mapOf(
            "COMPACT_TO_EXPANDED" to 20.94f,
            "EXPANDED_TO_COMPACT" to 27.02f,
            "TOUCH_PUNCH" to 35.41f,
            "BIFURCATION_SPLIT" to 17.41f,
            "BIFURCATION_MERGE" to 23.42f,
            "SWIPE_DISMISS" to 36.91f,
            "SWIPE_CONCEAL" to 39.79f,
            "SWIPE_RESTORE" to 25.79f,
            "ALERT_BOUNCE_ARRIVE" to 17.09f,
            "ALERT_DISMISS" to 32.31f,
        )

        for ((name, statedC) in expected) {
            val entry = SpringCatalog.all.first { it.name == name }
            assertEquals(
                "$name: damping should be 2ζω₀ and match the specification's c",
                statedC, entry.spec.damping, 0.1f
            )
        }
    }

    /**
     * Documents a real inconsistency rather than hiding it.
     *
     * The specification's prose columns claim overshoot figures that its own
     * `(T, ζ)` values cannot produce. Compact→Expanded is listed as ≈3.2%
     * overshoot at ζ = 0.80; the closed form gives ≈1.52%.
     *
     * This test records the discrepancy so the behaviour is a known, measured
     * choice — (T, ζ) wins — rather than an unexplained deviation.
     */
    @Test
    fun `spec prose overshoot figures disagree with spec damping ratios`() {
        val compactToExpanded = SpringCatalog.CompactToExpanded
        val derived = compactToExpanded.overshootFraction * 100f

        assertEquals(
            "ζ=0.80 should yield ~1.52% overshoot by the closed form",
            1.52f, derived, 0.05f
        )

        // The specification's prose says ~3.2%. Assert the gap is real, so this
        // test fails loudly if someone later "fixes" the spring to match prose.
        val specProse = 3.2f
        assertTrue(
            "spec prose (${specProse}%) must remain inconsistent with the " +
                "derived figure (${derived}%); if this changed, the reconciliation " +
                "in PLAN.md Finding 1 needs revisiting",
            abs(specProse - derived) > 1.0f
        )

        // ζ required to actually produce 3.2% overshoot, for the record.
        val zFor3_2 = 0.74f
        val recomputed = SpringSpec(0.48f, zFor3_2).overshootFraction * 100f
        assertTrue(
            "ζ≈0.74 is what 3.2% overshoot would require; got $recomputed%",
            abs(recomputed - 3.2f) < 0.4f
        )
    }

    /** Settling time likewise: derived from (T, ζ), not read from prose. */
    @Test
    fun `settling time is derived from the damping ratio`() {
        val s = SpringCatalog.CompactToExpanded
        // t = -ln(0.02·sqrt(1-ζ²)) / (ζω₀)
        val expected = -kotlin.math.ln(0.02f * sqrt(1f - 0.80f * 0.80f)) / (0.80f * s.omega0)
        assertEquals(expected, s.settlingTime2Percent, 1e-4f)
    }

    // =====================================================================
    // SpringSimulation: the analytic solution
    // =====================================================================

    /**
     * The integrator is exact, so it must reproduce the closed form the
     * specification prints in its Physics section — for the same initial
     * conditions, at any sampled time.
     */
    @Test
    fun `simulation reproduces the specification's closed-form solution`() {
        val cases = listOf(
            SpringCatalog.CompactToExpanded,
            SpringCatalog.ExpandedToCompact,
            SpringCatalog.TouchPunch,
            SpringCatalog.BifurcationSplit,
        )

        for (spec in cases) {
            val x0 = 125f
            val target = 370f
            val sim = SpringSimulation(spec, initialValue = x0)
            sim.retarget(target)

            var t = 0f
            val dt = 1f / 240f
            while (t < 1.0f) {
                // Analytic reference, computed independently here.
                val z = spec.dampingRatio
                val w0 = spec.omega0
                val wd = w0 * sqrt(1f - z * z)
                val a = x0 - target
                val b = (0f + z * w0 * a) / wd
                val e = exp(-z * w0 * t)
                val reference = target + e * (a * cos(wd * t) + b * sin(wd * t))

                assertEquals(
                    "T=${spec.response} ζ=$z at t=$t should track the closed form",
                    reference, sim.value, 0.01f
                )

                sim.update(dt)
                t += dt
            }
        }
    }

    /**
     * Exactness under a coarse frame. The specification's own integrator fails
     * this badly (recovering T=0.329 for a T=0.48 spring, a −31% error); the
     * analytic form passes trivially because `dt` is not a source of error.
     */
    @Test
    fun `simulation is exact even at the specification's 33ms frame clamp`() {
        val spec = SpringCatalog.CompactToExpanded
        val dt = 0.033f

        val sim = SpringSimulation(spec, initialValue = 125f)
        sim.retarget(370f)
        sim.update(dt)

        val z = spec.dampingRatio
        val w0 = spec.omega0
        val wd = w0 * sqrt(1f - z * z)
        val a = 125f - 370f
        val b = (z * w0 * a) / wd
        val e = exp(-z * w0 * dt)
        val reference = 370f + e * (a * cos(wd * dt) + b * sin(wd * dt))

        assertEquals(
            "one 33ms step must land exactly on the closed form",
            reference, sim.value, 1e-3f
        )
    }

    /**
     * Frame-rate independence: integrating in 240 small steps must equal one
     * big step. This is the property the specification's Euler loop lacks.
     */
    @Test
    fun `trajectory is independent of frame rate`() {
        val spec = SpringCatalog.CompactToExpanded

        fun run(totalSeconds: Float, steps: Int): Float {
            val sim = SpringSimulation(spec, initialValue = 125f)
            sim.retarget(370f)
            repeat(steps) { sim.update(totalSeconds / steps) }
            return sim.value
        }

        val coarse = run(0.20f, 6)     // ~33ms frames
        val medium = run(0.20f, 12)    // ~16.7ms frames
        val fine = run(0.20f, 240)     // ~0.8ms frames

        assertEquals("12 steps vs 240 steps must agree", fine, medium, 0.01f)
        assertEquals("6 steps vs 240 steps must agree", fine, coarse, 0.01f)
    }

    // =====================================================================
    // Velocity continuity on interruption (checklist item 60)
    // =====================================================================

    /**
     * Retargeting mid-flight must not jump position or velocity.
     *
     * The specification calls this out as CRITICAL. The analytic formulation
     * satisfies it structurally: re-seeding `(x₀, v₀)` from the live state and
     * evaluating at `t = 0` reproduces the state exactly.
     */
    @Test
    fun `retargeting mid-flight preserves position and velocity exactly`() {
        val sim = SpringSimulation(SpringCatalog.CompactToExpanded, initialValue = 125f)
        sim.retarget(370f)

        // Fly for a bit, then interrupt.
        repeat(20) { sim.update(1f / 120f) }

        val positionBefore = sim.value
        val velocityBefore = sim.velocity
        assertTrue("should be mid-flight, not settled", !sim.isSettled())

        sim.retarget(200f)

        assertEquals("position must not jump on retarget", positionBefore, sim.value, 1e-5f)
        assertEquals("velocity must not jump on retarget", velocityBefore, sim.velocity, 1e-4f)
    }

    /** Repeated interruption must not accumulate error or drift. */
    @Test
    fun `repeated interruption stays continuous`() {
        val sim = SpringSimulation(SpringCatalog.CompactToExpanded, initialValue = 125f)
        sim.retarget(370f)

        var lastPosition = sim.value
        var lastVelocity = sim.velocity

        val targets = listOf(200f, 370f, 150f, 300f, 370f, 90f)
        for (target in targets) {
            repeat(6) { sim.update(1f / 120f) }
            val p = sim.value
            val v = sim.velocity
            sim.retarget(target)
            assertEquals("no position jump toward $target", p, sim.value, 1e-5f)
            assertEquals("no velocity jump toward $target", v, sim.velocity, 1e-4f)
            lastPosition = sim.value
            lastVelocity = sim.velocity
        }
        assertTrue("still a finite number after repeated interruption", lastPosition.isFinite())
        assertTrue("velocity still finite after repeated interruption", lastVelocity.isFinite())
    }

    // =====================================================================
    // Settling
    // =====================================================================

    /**
     * Every spring must actually settle.
     *
     * ### Why the budget is derived rather than a multiple of the 2% time
     *
     * The specification's settle test requires **both** `|Δx| < 0.05` and
     * `|v| < 0.05`. Those are absolute thresholds in the parameter's own units,
     * and for a spring animating a distance of ~100 dp they fire at very
     * different times:
     *
     *  - the position envelope falls below 0.05 dp at `t = ln(travel/0.05)/(ζω₀)`
     *  - the velocity envelope falls below 0.05 dp/s at
     *    `t = ln(travel·ω₀/0.05)/(ζω₀)` — larger, because velocity is the
     *    position envelope multiplied by ω₀
     *
     * For Compact→Expanded that is ≈0.56 s versus ≈0.77 s. **The velocity
     * threshold dominates**, so a budget expressed as a multiple of the 2%
     * *position* settling time (which measures a 2% band, i.e. 2 dp of a 100 dp
     * travel) is far too small.
     *
     * This is a property of the specification's own constants, not of this
     * implementation: a fixed 0.05 dp/s threshold means large-amplitude springs
     * keep the render loop alive longer than the motion is visible. Recorded
     * here so it is a known, measured consequence rather than a mystery.
     */
    @Test
    fun `every catalogue spring settles within its predicted time`() {
        val travel = 100f

        for (entry in SpringCatalog.all) {
            val spec = entry.spec
            val z = spec.dampingRatio
            val w0 = spec.omega0
            val decay = z * w0

            val positionSettle = kotlin.math.ln(travel / SpringSimulation.SETTLE_DISTANCE) / decay
            val velocitySettle =
                kotlin.math.ln(travel * w0 / SpringSimulation.SETTLE_VELOCITY) / decay
            val predicted = maxOf(positionSettle, velocitySettle)
            val budget = predicted * 1.5f

            val sim = SpringSimulation(spec, initialValue = 0f)
            sim.retarget(travel)

            var elapsed = 0f
            val dt = 1f / 240f
            while (elapsed < budget) {
                if (!sim.update(dt)) break
                elapsed += dt
            }

            assertTrue(
                "${entry.name} should settle within ${budget}s (predicted ${predicted}s), " +
                    "still moving with value=${sim.value} target=${sim.target} " +
                    "velocity=${sim.velocity}",
                sim.isSettled()
            )
            assertEquals(
                "${entry.name} should land exactly on target", travel, sim.value, 0.05f
            )
        }
    }

    /**
     * Documents that the velocity threshold is the binding constraint.
     *
     * If this ever stops being true — because the thresholds changed, or
     * because they were made travel-relative — the budget derivation above
     * needs revisiting.
     */
    @Test
    fun `velocity threshold dominates the position threshold at these amplitudes`() {
        val travel = 100f
        for (entry in SpringCatalog.all) {
            val spec = entry.spec
            val decay = spec.dampingRatio * spec.omega0

            val positionSettle = kotlin.math.ln(travel / SpringSimulation.SETTLE_DISTANCE) / decay
            val velocitySettle =
                kotlin.math.ln(travel * spec.omega0 / SpringSimulation.SETTLE_VELOCITY) / decay

            assertTrue(
                "${entry.name}: the fixed 0.05 velocity threshold should bind after the " +
                    "fixed 0.05 position threshold (position ${positionSettle}s, " +
                    "velocity ${velocitySettle}s)",
                velocitySettle > positionSettle
            )
        }
    }

    /** A critically damped spring must not overshoot at all. */
    @Test
    fun `critically damped spring does not overshoot`() {
        val sim = SpringSimulation(SpringSpec(response = 0.40f, dampingRatio = 1.0f), 0f)
        sim.retarget(100f)

        var maxValue = 0f
        repeat(600) { i ->
            sim.update(1f / 240f)
            if (i > 2) maxValue = maxOf(maxValue, sim.value)
        }
        assertTrue("ζ=1.0 must not exceed its target; peaked at $maxValue", maxValue <= 100.01f)
    }

    /** An underdamped spring must overshoot by its predicted fraction. */
    @Test
    fun `underdamped spring overshoots by its predicted fraction`() {
        val spec = SpringCatalog.CompactToExpanded
        val sim = SpringSimulation(spec, initialValue = 0f)
        sim.retarget(100f)

        var maxValue = 0f
        repeat(2000) { sim.update(1f / 1000f); maxValue = maxOf(maxValue, sim.value) }

        val observedFraction = (maxValue - 100f) / 100f
        assertEquals(
            "observed overshoot should match exp(-ζπ/√(1-ζ²))",
            spec.overshootFraction, observedFraction, 0.002f
        )
    }

    // =====================================================================
    // Hardening
    // =====================================================================

    /** A zero or negative delta must be a no-op, not a corruption. */
    @Test
    fun `non-positive delta does not advance the simulation`() {
        val sim = SpringSimulation(SpringCatalog.CompactToExpanded, 0f)
        sim.retarget(100f)
        repeat(10) { sim.update(1f / 120f) }

        val p = sim.value
        val v = sim.velocity
        sim.update(0f)
        sim.update(-0.5f)

        assertEquals("zero delta must not move position", p, sim.value, 0f)
        assertEquals("zero delta must not move velocity", v, sim.velocity, 0f)
    }

    /** A huge delta must converge to the target rather than overflow. */
    @Test
    fun `a very large delta converges instead of overflowing`() {
        val sim = SpringSimulation(SpringCatalog.CompactToExpanded, 0f)
        sim.retarget(100f)
        sim.update(10f)

        assertTrue("value must be finite", sim.value.isFinite())
        assertTrue("velocity must be finite", sim.velocity.isFinite())
        assertEquals("should have converged to target", 100f, sim.value, 0.01f)
    }

    /** [SpringSimulation.snapTo] must bypass animation entirely. */
    @Test
    fun `snapTo jumps immediately and clears motion`() {
        val sim = SpringSimulation(SpringCatalog.CompactToExpanded, 0f)
        sim.retarget(100f)
        repeat(10) { sim.update(1f / 120f) }

        sim.snapTo(42f)
        assertEquals(42f, sim.value, 0f)
        assertEquals(0f, sim.velocity, 0f)
        assertTrue("snapTo must leave the spring settled", sim.isSettled())
    }

    // =====================================================================
    // Catalogue integrity
    // =====================================================================

    /** The catalogue's own accounting must be consistent. */
    @Test
    fun `catalogue provenance partitions cleanly`() {
        // Three provenances, so the partition is over all three — not a binary
        // spec-vs-inferred split. IDLE_TO_COMPACT is neither: the specification
        // names it in the transition matrix but tabulates no numbers for it.
        val byProvenance = SpringCatalog.all.groupingBy { it.provenance }.eachCount()
        val accounted = SpringProvenance.entries.sumOf { byProvenance[it] ?: 0 }

        assertEquals(
            "every entry must carry exactly one provenance and be counted",
            SpringCatalog.all.size, accounted
        )
        assertTrue(
            "should be at least 10 animation-table entries",
            (byProvenance[SpringProvenance.SPEC_TABLE] ?: 0) >= 10
        )
        assertTrue(
            "inferred entries must be disclosed, not silently merged",
            SpringCatalog.inferred.isNotEmpty()
        )
        assertTrue(
            "transition-matrix-only entries must be tracked separately from " +
                "both tabulated and inferred values",
            (byProvenance[SpringProvenance.SPEC_TRANSITION_MATRIX] ?: 0) > 0
        )

        val names = SpringCatalog.all.map { it.name }
        assertEquals("names must be unique", names.size, names.toSet().size)

        // `inferred` must not accidentally include transition-matrix entries:
        // those ARE specified, just in a different table.
        val inferredNames = SpringCatalog.inferred.map { it.name }
        assertTrue(
            "IDLE_TO_COMPACT is specified in the transition matrix and must not " +
                "be reported as inferred",
            "IDLE_TO_COMPACT" !in inferredNames
        )
        assertTrue(
            "COMPACT_TO_IDLE has no damping ratio in the specification and MUST " +
                "be reported as inferred",
            "COMPACT_TO_IDLE" in inferredNames
        )
    }

    /** Every catalogue spring must be constructible and physically sane. */
    @Test
    fun `every catalogue entry is well formed`() {
        for (entry in SpringCatalog.all) {
            assertTrue("${entry.name}: response must be positive", entry.spec.response > 0f)
            assertTrue(
                "${entry.name}: damping ratio must be non-negative",
                entry.spec.dampingRatio >= 0f
            )
            assertTrue(
                "${entry.name}: damping ratio ${entry.spec.dampingRatio} should be below " +
                    "critical damping; the specification uses no overdamped springs",
                entry.spec.dampingRatio < 1f
            )
            assertTrue("${entry.name}: stiffness must be positive", entry.spec.stiffness > 0f)
        }
    }

    /**
     * Cross-check against the JavaScript reference implementation, which is
     * authored as `{ stiffness: 420, damping: 32, mass: 0.9 }`.
     *
     * For a unit-mass equivalent, ζ = c / (2√(k·m)) and T = 2π / √(k/m). This
     * documents where the old `dampingRatio = 0.823, stiffness = 466.67`
     * constants came from, and why they are NOT the specification's spring.
     */
    @Test
    fun `javascript reference spring is a different spring from the specification`() {
        val k = 420f
        val c = 32f
        val m = 0.9f

        val omega0 = sqrt(k / m)
        val zeta = c / (2f * sqrt(k * m))
        val response = (2.0 * PI / omega0).toFloat()

        assertEquals("JS reference damping ratio", 0.823f, zeta, 0.005f)
        assertEquals("JS reference response", 0.291f, response, 0.01f)

        // It must NOT be confused with the specification's expansion spring.
        val specSpring = SpringCatalog.CompactToExpanded
        assertTrue(
            "the JS spring (T≈0.29) is materially faster than the specification's " +
                "Compact→Expanded (T=0.48); they must not be conflated",
            abs(specSpring.response - response) > 0.1f
        )
    }
}
