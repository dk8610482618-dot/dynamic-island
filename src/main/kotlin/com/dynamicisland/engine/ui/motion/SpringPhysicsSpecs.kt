package com.dynamicisland.engine.ui.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * Spring physics and mechanical oscillator specifications reverse-engineered from
 * iOS QuartzCore (CASpringAnimation) and SwiftUI motion engines.
 */
object SpringPhysicsSpecs {

    // --- Exact Motion Specs by Phase ---

    /**
     * Idle -> Compact presentation spring:
     * T0 = 0.40s, zeta = 0.72 -> k = 246.74 N/m, c = 22.62 N*s/m
     */
    val IdleToCompactSpec: SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = 246.74f
    )

    /**
     * Compact -> Expanded card expansion spring:
     * T0 = 0.50s, zeta = 0.80 -> k = 157.91 N/m, c = 20.11 N*s/m
     */
    val CompactToExpandedSpec: SpringSpec<Float> = spring(
        dampingRatio = 0.823f,
        stiffness = 466.67f
    )

    /**
     * Expanded -> Compact card collapse spring:
     * T0 = 0.36s, zeta = 0.85 -> k = 304.47 N/m, c = 29.67 N*s/m
     */
    val ExpandedToCompactSpec: SpringSpec<Float> = spring(
        dampingRatio = 0.88f,
        stiffness = 385.53f
    )

    /**
     * Pill -> Bubble Split (Metaball gooey detachment):
     * T0 = 0.42s, zeta = 0.65 -> k = 223.80 N/m, c = 19.45 N*s/m
     */
    val PillToBubbleSplitSpec: SpringSpec<Float> = spring(
        dampingRatio = 0.65f,
        stiffness = 223.80f
    )

    /**
     * Rubber-band over-drag snapback spring:
     * T0 = 0.32s, zeta = 0.75 -> k = 385.53 N/m, c = 29.45 N*s/m
     */
    val RubberBandSnapbackSpec: SpringSpec<Float> = spring(
        dampingRatio = 0.75f,
        stiffness = 385.53f
    )

    /**
     * Fluid 120fps iOS-equivalent interruptible spring from reverse-engineer-ios-dynamic-island:
     * stiffness: 420, damping: 32, mass: 0.9 -> dampingRatio = 0.823f, stiffness = 466.67f
     */
    val ReferenceIslandSpringSpec: SpringSpec<Float> = spring(
        dampingRatio = 0.823f,
        stiffness = 466.67f
    )

    val ReferenceIslandDpSpringSpec: SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.823f,
        stiffness = 466.67f
    )

    /**
     * Converts SwiftUI response (T0) and dampingRatio (zeta) to Compose SpringSpec.
     */
    fun createFromSwiftUI(responseSeconds: Float, dampingRatio: Float): SpringSpec<Float> {
        val omega0 = (2f * PI.toFloat()) / responseSeconds
        val stiffness = omega0 * omega0
        return spring(dampingRatio = dampingRatio, stiffness = stiffness)
    }

    /**
     * Apple Non-linear Rubber-Banding Resistance Formula.
     *
     * f(x, d, c) = (1.0 - 1.0 / ((x * c / d) + 1.0)) * d
     *
     * @param rawDelta Finger displacement past the boundary constraint (px).
     * @param dimension Reference dimension along travel axis (e.g., island height or width).
     * @param c Friction coefficient (Apple standard constant = 0.55f).
     */
    fun calculateRubberBandOffset(rawDelta: Float, dimension: Float, c: Float = 0.55f): Float {
        if (rawDelta <= 0f || dimension <= 0f) return 0f
        return (1.0f - (1.0f / ((rawDelta * c / dimension) + 1.0f))) * dimension
    }
}
