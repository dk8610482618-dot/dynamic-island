package com.dynamicisland.core.motion

import kotlin.math.abs

/**
 * Pure JVM 1:1 finger-scrubbable collapse & pre-expansion press compression controller.
 *
 * Implements:
 * - Subtle pre-expansion elastic scale (`0.985f`) on touch-and-hold initiation
 * - 1:1 vertical finger-delta scrubbing (`progress ∈ [0f, 1f]`) interpolating width, height,
 *   corner radius (`44dp -> 18.5dp`), and expanded content opacity in real time
 * - Velocity-aware release threshold (`shouldCollapseOnRelease`) and spring handoff velocity
 */
object ScrubCollapseController {
    const val PRE_EXPANSION_PRESS_SCALE: Float = 0.985f
    const val COLLAPSE_PROGRESS_THRESHOLD: Float = 0.38f
    const val FLING_UPWARD_VELOCITY_THRESHOLD_PX_PER_SEC: Float = -650f

    data class ScrubInterpolatedState(
        val progress: Float,
        val widthPx: Float,
        val heightPx: Float,
        val cornerRadiusDp: Float,
        val expandedContentAlpha: Float,
        val compactContentAlpha: Float,
        val scale: Float
    )

    /**
     * Computes the real-time 1:1 scrubbed geometry for an upward drag delta [cumulativeDeltaYPx]
     * (negative values represent upward swipe toward the sensor cutout).
     */
    fun interpolate(
        cumulativeDeltaYPx: Float,
        expandedWidthPx: Float,
        expandedHeightPx: Float,
        compactWidthPx: Float,
        compactHeightPx: Float,
        expandedRadiusDp: Float = 44f,
        compactRadiusDp: Float = 18.5f
    ): ScrubInterpolatedState {
        val travelRangePx = (expandedHeightPx - compactHeightPx).coerceAtLeast(64f)
        val upwardPx = (-cumulativeDeltaYPx).coerceAtLeast(0f)
        val progress = (upwardPx / travelRangePx).coerceIn(0f, 1f)

        val currentWidth = lerp(expandedWidthPx, compactWidthPx, progress)
        val currentHeight = lerp(expandedHeightPx, compactHeightPx, progress)
        val currentRadius = lerp(expandedRadiusDp, compactRadiusDp, progress)
        val expandedAlpha = (1f - progress * 1.45f).coerceIn(0f, 1f)
        val compactAlpha = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val scale = lerp(1f, 0.96f, progress * 0.25f)

        return ScrubInterpolatedState(
            progress = progress,
            widthPx = currentWidth,
            heightPx = currentHeight,
            cornerRadiusDp = currentRadius,
            expandedContentAlpha = expandedAlpha,
            compactContentAlpha = compactAlpha,
            scale = scale
        )
    }

    /**
     * Determines whether releasing the finger at [progress] with vertical velocity [velocityYPxPerSec]
     * completes the collapse or springs back to Expanded.
     */
    fun shouldCollapseOnRelease(progress: Float, velocityYPxPerSec: Float): Boolean {
        if (velocityYPxPerSec <= FLING_UPWARD_VELOCITY_THRESHOLD_PX_PER_SEC) return true
        if (velocityYPxPerSec >= abs(FLING_UPWARD_VELOCITY_THRESHOLD_PX_PER_SEC)) return false
        return progress >= COLLAPSE_PROGRESS_THRESHOLD
    }

    private fun lerp(start: Float, stop: Float, fraction: Float): Float =
        start + (stop - start) * fraction.coerceIn(0f, 1f)
}
