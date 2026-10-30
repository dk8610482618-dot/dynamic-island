package com.dynamicisland.core.motion

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure JVM 2D Metaball (Smooth-Minimum Signed Distance Field & Ferrofluid Neck) geometry engine.
 *
 * Renders the organic optical ferrofluid stretch and snap between the primary attached island pill
 * and detached minimal circular droplet(s) during mitosis detachment and merge transitions.
 */
object MetaballGeometry {
    data class BridgeNeck(
        val isConnected: Boolean,
        val neckThicknessRatio: Float,
        val leftAnchorX: Float,
        val rightAnchorX: Float,
        val centerY: Float,
        val pinchHeight: Float
    )

    /**
     * Polynomial smooth-minimum (`smin`) combining two signed distances [d1] and [d2]
     * with smoothness radius [k] (> 0).
     */
    fun smoothMin(d1: Float, d2: Float, k: Float = 12f): Float {
        if (k <= 0f) return min(d1, d2)
        val h = max(k - kotlin.math.abs(d1 - d2), 0f) / k
        return min(d1, d2) - h * h * k * 0.25f
    }

    /**
     * Exponential smooth-minimum for continuous ferrofluid field evaluation.
     */
    fun expSmoothMin(d1: Float, d2: Float, k: Float = 0.18f): Float {
        if (k <= 0f) return min(d1, d2)
        val res = exp(-k * d1) + exp(-k * d2)
        return -ln(max(res, 1e-9f)) / k
    }

    /**
     * Signed distance to a capsule / circle centered at ([cx], [cy]) with half-width [halfW],
     * half-height [halfH], and corner radius [radius].
     */
    fun sdRoundedBox(px: Float, py: Float, cx: Float, cy: Float, halfW: Float, halfH: Float, radius: Float): Float {
        val r = min(radius, min(halfW, halfH))
        val qx = kotlin.math.abs(px - cx) - (halfW - r)
        val qy = kotlin.math.abs(py - cy) - (halfH - r)
        val outX = max(qx, 0f)
        val outY = max(qy, 0f)
        return sqrt(outX * outX + outY * outY) + min(max(qx, qy), 0f) - r
    }

    /**
     * Evaluates the ferrofluid bridge neck between the right edge of the primary capsule
     * and the left edge of the detached minimal droplet separated by [edgeGapPx].
     *
     * As [edgeGapPx] increases from 0 to [snapThresholdPx], the neck stretches thin and
     * snaps cleanly apart at [snapThresholdPx].
     */
    fun computeBridgeNeck(
        primaryRightX: Float,
        dropletLeftX: Float,
        centerY: Float,
        dropletDiameterPx: Float,
        snapThresholdPx: Float
    ): BridgeNeck {
        val gap = (dropletLeftX - primaryRightX).coerceAtLeast(0f)
        if (snapThresholdPx <= 0f || gap >= snapThresholdPx) {
            return BridgeNeck(
                isConnected = false,
                neckThicknessRatio = 0f,
                leftAnchorX = primaryRightX,
                rightAnchorX = dropletLeftX,
                centerY = centerY,
                pinchHeight = 0f
            )
        }
        val t = (gap / snapThresholdPx).coerceIn(0f, 1f)
        // Smooth nonlinear ferrofluid thinning curve: thick when close, stretches thin before snap
        val ratio = (1f - t * t) * (1f - t)
        val maxNeckHeight = dropletDiameterPx * 0.72f
        return BridgeNeck(
            isConnected = ratio > 0.02f,
            neckThicknessRatio = ratio,
            leftAnchorX = primaryRightX - dropletDiameterPx * 0.12f,
            rightAnchorX = dropletLeftX + dropletDiameterPx * 0.12f,
            centerY = centerY,
            pinchHeight = maxNeckHeight * ratio
        )
    }
}
