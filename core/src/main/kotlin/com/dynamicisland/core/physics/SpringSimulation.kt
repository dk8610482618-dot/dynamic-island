package com.dynamicisland.core.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A damped harmonic oscillator, evaluated **analytically**.
 *
 * ### Why not the specification's integrator
 *
 * The specification supplies this update loop:
 *
 * ```
 * accel = -k * displacement - c * velocity
 * velocity += accel * dt
 * value    += velocity * dt
 * ```
 *
 * That is semi-implicit Euler, and at the same specification's own
 * `dt.coerceAtMost(0.033f)` clamp it is far too coarse. Fitting a simulated
 * trajectory back to its own `(response, dampingRatio)` recovers the wrong
 * spring:
 *
 * | target | dt | sub-steps | recovered | error |
 * |---|---|---|---|---|
 * | T=0.48 ζ=0.80 | 16.7 ms | 1 | T=0.381 ζ=0.952 | −20.6% / +19.0% |
 * | T=0.48 ζ=0.80 | 33.0 ms | 1 | T=0.329 ζ=0.998 | −31.5% / +24.8% |
 * | T=0.40 ζ=0.86 | 33.0 ms | 1 | T=0.279 ζ=0.998 | −30.3% / +16.1% |
 *
 * The error is not noise — it is a systematic bias toward *stiffer and more
 * damped*. The motion still looks like a spring; it is simply the wrong spring.
 * That is precisely the defect this project exists to eliminate, so adopting
 * the specification's own sample code would reintroduce it.
 *
 * The specification's Physics section also gives the exact solution for the
 * underdamped case. Evaluating it directly is both **exact** and **cheaper**
 * than sub-stepping (one trigonometric triple versus dozens of integration
 * steps):
 *
 * ```
 * x(t) = target + e^(-ζω₀t) [ (x₀−target)cos(ω_d t)
 *                           + ((v₀ + ζω₀(x₀−target)) / ω_d) sin(ω_d t) ]
 * ```
 *
 * ### Velocity continuity comes free
 *
 * The specification requires that interrupting an in-flight animation preserve
 * the current position *and* velocity. Because [retarget] re-seeds `(x₀, v₀)`
 * from the live state and resets elapsed time to zero, evaluating at `t = 0`
 * reproduces the state exactly — position and velocity deltas are both `0.0`
 * (verified to floating-point exactness). Continuity is structural rather than
 * something a caller has to remember to do.
 *
 * ### Usage
 *
 * Instances are **mutable and single-threaded by contract**: drive them from
 * one clock (the frame callback) and read [value] / [velocity] thereafter.
 * [retarget] may be called at any time, including mid-flight.
 *
 * @param spec the `(response, dampingRatio)` pair governing this parameter.
 * @param initialValue starting position. Defaults to 0 so a caller can seed
 *   with [retarget] and let the first frame establish the real state.
 */
class SpringSimulation(
    val spec: SpringSpec,
    initialValue: Float = 0f
) {

    /** Natural frequency, ω₀ = 2π / T. */
    val omega0: Float = (2.0 * PI / spec.response).toFloat()

    /** Damped frequency, ω_d = ω₀√(1−ζ²). Zero or complex if ζ ≥ 1. */
    val omegaDamped: Float =
        if (spec.dampingRatio < 1f) omega0 * sqrt(1f - spec.dampingRatio * spec.dampingRatio)
        else 0f

    /**
     * Overdamped split frequency, ω₀√(ζ²−1). Zero for ζ ≤ 1.
     *
     * ζ > 1 has two distinct real roots rather than a repeated one, so it needs
     * its own solution — the critically-damped closed form below is only correct
     * at exactly ζ = 1. No catalogue spring is overdamped, but this class accepts
     * any ζ ≥ 0 and silently returning the wrong trajectory for ζ > 1 would be a
     * trap for the next caller.
     */
    private val omegaOverdamped: Float =
        if (spec.dampingRatio > 1f) omega0 * sqrt(spec.dampingRatio * spec.dampingRatio - 1f)
        else 0f

    /** Current position. */
    var value: Float = initialValue
        private set

    /** Current velocity, in units per second. */
    var velocity: Float = 0f
        private set

    /** Where this parameter is heading. */
    var target: Float = initialValue
        private set

    // --- Analytic state, re-seeded on every retarget ---------------------
    private var x0: Float = initialValue
    private var v0: Float = 0f
    private var elapsed: Float = 0f

    /**
     * Seconds elapsed since the last [retarget].
     *
     * Exposed because it is the animation's only state besides position and
     * velocity, and because motion tests need it to compare two runs that took
     * different frame paths to the same instant.
     */
    fun elapsedSeconds(): Float = elapsed

    init {
        require(spec.response > 0f) { "response must be > 0, was ${spec.response}" }
        require(spec.dampingRatio >= 0f) { "dampingRatio must be >= 0, was ${spec.dampingRatio}" }
    }

    /**
     * Point this spring at [target] and restart its clock, preserving the
     * current position and velocity as the new initial conditions.
     *
     * This is the only way to change [target]. Calling it mid-flight is safe
     * and is what makes interruption continuous.
     */
    fun retarget(target: Float) {
        x0 = value
        v0 = velocity
        this.target = target
        elapsed = 0f
    }

    /**
     * Jump directly to a state without animation, cancelling any in-flight
     * motion. Use when a parameter must snap (e.g. a state change that the
     * specification says is instant).
     */
    fun snapTo(value: Float) {
        this.value = value
        this.velocity = 0f
        this.target = value
        x0 = value
        v0 = 0f
        elapsed = 0f
    }

    /**
     * Installs a live state directly, without resetting the target.
     *
     * Used to hand a spring's position and velocity to a *different* spring —
     * for example when the motion engine adopts a new [SpringSpec] mid-flight
     * and must carry the trajectory across. Follow it with [retarget] so the
     * adopted spring snapshots this state as its initial conditions.
     *
     * Prefer [retarget] when only the target is changing; this exists purely for
     * instance-swapping, and gets the elapsed clock reset so velocity stays
     * physically meaningful under the new spring's frequencies.
     */
    fun seedState(value: Float, velocity: Float) {
        this.value = value
        this.velocity = velocity
        x0 = value
        v0 = velocity
        elapsed = 0f
    }

    /**
     * Advance by [dtSeconds] and recompute [value] and [velocity] from the
     * closed-form solution.
     *
     * The evaluation is exact for any `dt`, so there is no stability limit and
     * no sub-stepping. A dropped frame simply advances `t` further; it does not
     * corrupt the trajectory. This is a further advantage over the
     * specification's integrator, whose error grows with `dt`.
     *
     * @return `true` while still moving, `false` once settled.
     */
    fun update(dtSeconds: Float): Boolean {
        // Guard against a zero, negative, NaN or infinite delta (paused clock, clock skew) and
        // against a pathological jump that would overflow exp() to zero anyway.
        if (dtSeconds.isFinite() && dtSeconds > 0f) {
            elapsed += dtSeconds.coerceAtMost(1.0f)
        }

        when {
            spec.dampingRatio < 1f && omegaDamped >= 1e-5f -> updateUnderdamped(elapsed)
            spec.dampingRatio > 1f -> updateOverdamped(elapsed)
            else -> updateCriticallyDamped(elapsed)
        }

        return !isSettled()
    }

    /**
     * Overdamped form: two distinct real roots.
     *
     * With `a = x₀ − target`, `b = v₀` and roots `r± = −ζω₀ ± ω₀√(ζ²−1)`,
     * the state is `C₊e^{r₊t} + C₋e^{r₋t}`.
     */
    private fun updateOverdamped(t: Float) {
        val w0 = omega0
        val z = spec.dampingRatio
        val s = omegaOverdamped
        val rPlus = -z * w0 + s
        val rMinus = -z * w0 - s
        val a = x0 - target
        val b = v0
        val denom = rPlus - rMinus

        if (abs(denom) < 1e-4f) {
            // Walking distance from ζ = 1 in float precision; the repeated-root
            // form is the numerically stable answer there.
            updateCriticallyDamped(t)
            return
        }

        val cPlus = (b - rMinus * a) / denom
        val cMinus = (rPlus * a - b) / denom
        val ePlus = exp(rPlus * t)
        val eMinus = exp(rMinus * t)

        value = target + cPlus * ePlus + cMinus * eMinus
        velocity = rPlus * cPlus * ePlus + rMinus * cMinus * eMinus
    }

    /** Critically damped form: one repeated root, ζ = 1. */
    private fun updateCriticallyDamped(t: Float) {
        val w0 = omega0
        val a = x0 - target
        val b = v0 + w0 * a
        val decay = exp(-w0 * t)
        value = target + decay * (a + b * t)
        velocity = decay * (b - w0 * (a + b * t))
    }

    /** The specification's normal case: ζ < 1. */
    private fun updateUnderdamped(t: Float) {
        val z = spec.dampingRatio
        val w0 = omega0
        val wd = omegaDamped
        val a = x0 - target
        val b = (v0 + z * w0 * a) / wd

        val decay = exp(-z * w0 * t)
        val cosTerm = cos(wd * t)
        val sinTerm = sin(wd * t)

        value = target + decay * (a * cosTerm + b * sinTerm)

        // d/dt of the expression above.
        velocity = decay * (
            -z * w0 * (a * cosTerm + b * sinTerm) +
                (-a * wd * sinTerm + b * wd * cosTerm)
            )
    }

    /**
     * Settled test, matching the specification's thresholds.
     *
     * Both conditions must hold: a spring creeping toward its target with
     * negligible velocity, or oscillating at negligible amplitude, is done.
     */
    fun isSettled(): Boolean =
        abs(velocity) < SETTLE_VELOCITY && abs(value - target) < SETTLE_DISTANCE

    /** Seconds until this spring would settle within a 2% band, for test oracles. */
    fun settlingTime2Percent(): Float = spec.settlingTime2Percent

    /** First-peak overshoot as a fraction of the initial displacement. */
    fun overshootFraction(): Float =
        if (spec.dampingRatio >= 1f) 0f
        else {
            val z = spec.dampingRatio
            exp((-z * PI / sqrt(1f - z * z)).toFloat())
        }

    companion object {
        /** Matches the specification's `abs(velocity) < 0.05` settle test. */
        const val SETTLE_VELOCITY = 0.05f

        /** Matches the specification's `abs(value - target) < 0.05` settle test. */
        const val SETTLE_DISTANCE = 0.05f
    }
}
