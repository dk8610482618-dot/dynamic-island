package com.dynamicisland.core.geometry

import kotlin.math.min

/**
 * Apple G2 curvature-continuous superellipse ("squircle") geometry.
 *
 * Each corner is three cubic Bézier segments built from the polynomial spline
 * constants used by QuartzCore's `CALayerCornerCurve.continuous`. The spline is
 * curvature-continuous at the seam with the straight edge (`dκ/ds = 0`), which
 * is what removes the optical "pinch" of a circular fillet.
 *
 * This lives in :core — not next to the Compose `Shape` that draws it — because
 * it is pure arithmetic. In the Android module it could only be exercised under
 * Robolectric or with `unitTests.isReturnDefaultValues = true`, where every
 * `Path` call silently returns a default and the assertions prove nothing.
 *
 * The builder works in a per-corner frame so the four corners cannot drift
 * apart: `u` points back along the edge the path arrives on, `v` points out
 * along the edge it leaves on, and the stored points are `vertex + u*U + v*V`.
 */
object SquircleGeometry {

    /** Shoulder extension beyond the nominal radius: `L = 1.52866483 * R`. */
    const val SHOULDER_FACTOR: Float = 1.52866483f

    // Segment 1 — straight edge folding into the shoulder.
    private const val P1_CP1 = 1.088493f
    private const val P1_CP2 = 0.868407f
    private const val P1_END_U = 0.631494f
    private const val P1_END_V = 0.074911f

    // Segment 2 — shoulder turning into the arc.
    private const val P2_CP1_U = 0.372824f
    private const val P2_CP1_V = 0.169060f
    private const val P2_CP2_U = 0.169060f
    private const val P2_CP2_V = 0.372824f
    private const val P2_END_U = 0.074911f
    private const val P2_END_V = 0.631494f

    // Segment 3 — arc relaxing into the next straight edge.
    private const val P3_CP1_V = 0.868407f
    private const val P3_CP2_V = 1.088493f
    private const val P3_END_V = 1.528665f

    /** A single path command. Consumed by [SquircleShape] to fill a `Path`. */
    sealed interface Segment {
        data class MoveTo(val x: Float, val y: Float) : Segment
        data class LineTo(val x: Float, val y: Float) : Segment
        data class CubicTo(
            val cp1x: Float,
            val cp1y: Float,
            val cp2x: Float,
            val cp2y: Float,
            val x: Float,
            val y: Float
        ) : Segment

        data object Close : Segment
    }

    /**
     * Effective corner radius after clamping.
     *
     * The shoulder consumes `L * R` of each straight edge, so a radius larger
     * than `min(width, height) / (2L)` would make the shoulders overlap and the
     * outline self-intersect. Clamping here — rather than at each call site —
     * keeps every corner radius identical, which a per-corner clamp would not.
     */
    fun clampedRadius(width: Float, height: Float, radius: Float): Float {
        if (!width.isFinite() || !height.isFinite() || !radius.isFinite() ||
            width <= 0f || height <= 0f || radius <= 0f
        ) return 0f
        return min(radius, min(width, height) / (2f * SHOULDER_FACTOR))
    }

    /**
     * Interface sink for streaming Bézier path segments without object allocations.
     */
    interface PathSink {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun cubicTo(cp1x: Float, cp1y: Float, cp2x: Float, cp2y: Float, x: Float, y: Float)
        fun close()
    }

    /**
     * Streams the full rounded-rectangle outline into [sink] without allocating Segment objects.
     * Returns true if path was emitted, false if dimensions were degenerate.
     */
    fun emitPath(
        width: Float,
        height: Float,
        radius: Float,
        left: Float = 0f,
        top: Float = 0f,
        sink: PathSink
    ): Boolean {
        if (!left.isFinite() || !top.isFinite()) return false
        val r = clampedRadius(width, height, radius)
        if (r <= 0f) return false

        val shoulder = SHOULDER_FACTOR * r
        val right = left + width
        val bottom = top + height

        // Top-right: arrives along the horizontal top edge.
        sink.moveTo(left + shoulder, top)
        sink.lineTo(right - shoulder, top)
        emitCorner(
            sink,
            vertexX = right, vertexY = top,
            ux = -1f, uy = 0f,
            vx = 0f, vy = 1f,
            r = r, verticalArrival = false
        )

        // Bottom-right: arrives down the right edge.
        sink.lineTo(right, bottom - shoulder)
        emitCorner(
            sink,
            vertexX = right, vertexY = bottom,
            ux = 0f, uy = -1f,
            vx = -1f, vy = 0f,
            r = r, verticalArrival = true
        )

        // Bottom-left: arrives along the horizontal bottom edge.
        sink.lineTo(left + shoulder, bottom)
        emitCorner(
            sink,
            vertexX = left, vertexY = bottom,
            ux = 1f, uy = 0f,
            vx = 0f, vy = -1f,
            r = r, verticalArrival = false
        )

        // Top-left: arrives up the left edge.
        sink.lineTo(left, top + shoulder)
        emitCorner(
            sink,
            vertexX = left, vertexY = top,
            ux = 0f, uy = 1f,
            vx = 1f, vy = 0f,
            r = r, verticalArrival = true
        )

        sink.close()
        return true
    }

    /**
     * The full rounded-rectangle outline, in order, ready to replay into a path.
     *
     * Returns an empty list for degenerate sizes so callers have nothing to draw.
     */
    fun path(
        width: Float,
        height: Float,
        radius: Float,
        left: Float = 0f,
        top: Float = 0f
    ): List<Segment> {
        val segments = ArrayList<Segment>(18)
        val success = emitPath(width, height, radius, left, top, object : PathSink {
            override fun moveTo(x: Float, y: Float) { segments += Segment.MoveTo(x, y) }
            override fun lineTo(x: Float, y: Float) { segments += Segment.LineTo(x, y) }
            override fun cubicTo(cp1x: Float, cp1y: Float, cp2x: Float, cp2y: Float, x: Float, y: Float) {
                segments += Segment.CubicTo(cp1x, cp1y, cp2x, cp2y, x, y)
            }
            override fun close() { segments += Segment.Close }
        })
        return if (success) segments else emptyList()
    }

    private fun emitCorner(
        sink: PathSink,
        vertexX: Float,
        vertexY: Float,
        ux: Float,
        uy: Float,
        vx: Float,
        vy: Float,
        r: Float,
        verticalArrival: Boolean
    ) {
        if (!verticalArrival) {
            sink.cubicTo(
                vertexX + ux * P1_CP1 * r + vx * 0f * r, vertexY + uy * P1_CP1 * r + vy * 0f * r,
                vertexX + ux * P1_CP2 * r + vx * 0f * r, vertexY + uy * P1_CP2 * r + vy * 0f * r,
                vertexX + ux * P1_END_U * r + vx * P1_END_V * r, vertexY + uy * P1_END_U * r + vy * P1_END_V * r
            )
            sink.cubicTo(
                vertexX + ux * P2_CP1_U * r + vx * P2_CP1_V * r, vertexY + uy * P2_CP1_U * r + vy * P2_CP1_V * r,
                vertexX + ux * P2_CP2_U * r + vx * P2_CP2_V * r, vertexY + uy * P2_CP2_U * r + vy * P2_CP2_V * r,
                vertexX + ux * P2_END_U * r + vx * P2_END_V * r, vertexY + uy * P2_END_U * r + vy * P2_END_V * r
            )
            sink.cubicTo(
                vertexX + ux * 0f * r + vx * P3_CP1_V * r, vertexY + uy * 0f * r + vy * P3_CP1_V * r,
                vertexX + ux * 0f * r + vx * P3_CP2_V * r, vertexY + uy * 0f * r + vy * P3_CP2_V * r,
                vertexX + ux * 0f * r + vx * SHOULDER_FACTOR * r, vertexY + uy * 0f * r + vy * SHOULDER_FACTOR * r
            )
        } else {
            sink.cubicTo(
                vertexX + ux * P3_CP2_V * r + vx * 0f * r, vertexY + uy * P3_CP2_V * r + vy * 0f * r,
                vertexX + ux * P3_CP1_V * r + vx * 0f * r, vertexY + uy * P3_CP1_V * r + vy * 0f * r,
                vertexX + ux * P2_END_V * r + vx * P2_END_U * r, vertexY + uy * P2_END_V * r + vy * P2_END_U * r
            )
            sink.cubicTo(
                vertexX + ux * P2_CP2_V * r + vx * P2_CP2_U * r, vertexY + uy * P2_CP2_V * r + vy * P2_CP2_U * r,
                vertexX + ux * P2_CP1_V * r + vx * P2_CP1_U * r, vertexY + uy * P2_CP1_V * r + vy * P2_CP1_U * r,
                vertexX + ux * P1_END_V * r + vx * P1_END_U * r, vertexY + uy * P1_END_V * r + vy * P1_END_U * r
            )
            sink.cubicTo(
                vertexX + ux * 0f * r + vx * P1_CP2 * r, vertexY + uy * 0f * r + vy * P1_CP2 * r,
                vertexX + ux * 0f * r + vx * P1_CP1 * r, vertexY + uy * 0f * r + vy * P1_CP1 * r,
                vertexX + ux * 0f * r + vx * SHOULDER_FACTOR * r, vertexY + uy * 0f * r + vy * SHOULDER_FACTOR * r
            )
        }
    }
}
