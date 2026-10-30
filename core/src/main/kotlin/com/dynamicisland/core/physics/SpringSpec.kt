package com.dynamicisland.core.physics

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A spring parameterised the way Apple's motion system parameterises it: by
 * **response** (T, the period of one oscillation in seconds) and **damping
 * ratio** (ζ, where 1.0 is critically damped).
 *
 * Stiffness and damping are *derived*, never stored. The specification's tables
 * list all four values, and they agree to within rounding — but storing `k`
 * alongside `(T, ζ)` invites the two drifting apart, and a test that asserts
 * `k` matches a hard-coded constant then proves nothing. Deriving them means
 * the `(T, ζ)` pair is the single source of truth and `k`/`c` are testable
 * *consequences* of it.
 *
 * With unit mass:
 * ```
 * ω₀ = 2π / T
 * k  = ω₀²        = (2π/T)²
 * c  = 2ζω₀       = 2ζ(2π/T)
 * ```
 */
data class SpringSpec(
    /** Response T in seconds: the period of one full oscillation. */
    val response: Float,
    /** Damping ratio ζ. `1.0` is critically damped, `< 1.0` overshoots. */
    val dampingRatio: Float
) {

    /** Natural angular frequency, ω₀ = 2π/T, in rad/s. */
    val omega0: Float get() = (2.0 * PI / response).toFloat()

    /** Stiffness k = ω₀², with unit mass. */
    val stiffness: Float get() = omega0 * omega0

    /** Damping coefficient c = 2ζω₀, with unit mass. */
    val damping: Float get() = 2f * dampingRatio * omega0

    /**
     * Peak overshoot past the target, as a fraction of the total travel.
     *
     * For an underdamped second-order system released from rest this is
     * `exp(−ζπ / √(1−ζ²))`. The specification's prose tables quote larger
     * figures than this formula yields for their own ζ values — for example
     * Compact→Expanded is listed as ≈3.2% overshoot at ζ = 0.80, but ζ = 0.80
     * gives 1.52%. Reaching 3.2% would need ζ ≈ 0.74.
     *
     * The `(T, ζ)` pair is the authoritative contract, so overshoot is derived
     * here rather than read from the prose column. See PLAN.md Finding 1.
     */
    val overshootFraction: Float
        get() = if (dampingRatio >= 1f) 0f
        else {
            val z = dampingRatio
            kotlin.math.exp((-z * PI / sqrt(1f - z * z)).toFloat())
        }

    /**
     * Time to settle within a 2% band, in seconds.
     *
     * The envelope decays as `e^(−ζω₀t)`, so the 2% threshold is reached at
     * `t = −ln(0.02√(1−ζ²)) / (ζω₀)`. The specification's settling column is
     * likewise systematically larger than this; again the `(T, ζ)` pair wins.
     */
    val settlingTime2Percent: Float
        get() = when {
            // Critically damped: the envelope is (a + bt)e^(-ω₀t), so 2% is
            // reached at ≈ 5.8/ω₀ rather than the 3.9/ω₀ a pure exponential gives.
            dampingRatio == 1f -> 5.8f / omega0

            // Overdamped: governed by the slower real root,
            // r = -ω₀(ζ - √(ζ²-1)).
            dampingRatio > 1f -> {
                val z = dampingRatio
                -kotlin.math.ln(0.02f) / (omega0 * (z - sqrt(z * z - 1f)))
            }

            else -> {
                val z = dampingRatio
                -kotlin.math.ln(0.02f * sqrt(1f - z * z)) / (z * omega0)
            }
        }

    /**
     * The spring's response expressed on the specification's alternative
     * parameterisation: `{stiffness, damping, mass}` with `mass = 1`.
     *
     * Used to cross-check against the JavaScript reference implementation,
     * which is authored as `{ stiffness: 420, damping: 32, mass: 0.9 }`.
     */
    fun asStiffnessTriple(mass: Float = 1f): Triple<Float, Float, Float> =
        Triple(stiffness * mass, damping * mass, mass)
}
