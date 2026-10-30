package com.dynamicisland.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * These tests run against the real geometry, not a stubbed `Path`.
 *
 * The previous suite lived in the Android module and could only execute because
 * the build set `unitTests.isReturnDefaultValues = true` — every `Path` call
 * returned a default and every assertion passed regardless of the math. The
 * math now lives in `:core`, where `android.graphics` is not on the classpath at
 * all, so these assertions are about numbers.
 */
class SquircleGeometryTest {

    private fun points(segments: List<SquircleGeometry.Segment>): List<Pair<Float, Float>> =
        segments.flatMap { segment ->
            when (segment) {
                is SquircleGeometry.Segment.MoveTo -> listOf(segment.x to segment.y)
                is SquircleGeometry.Segment.LineTo -> listOf(segment.x to segment.y)
                is SquircleGeometry.Segment.CubicTo -> listOf(
                    segment.cp1x to segment.cp1y,
                    segment.cp2x to segment.cp2y,
                    segment.x to segment.y
                )
                SquircleGeometry.Segment.Close -> emptyList()
            }
        }

    /** Tolerance, in px, for "the same point": far below any real asymmetry. */
    private val tolerance = 0.02f

    private fun contains(pts: List<Pair<Float, Float>>, x: Float, y: Float): Boolean =
        pts.any { (px, py) -> abs(px - x) <= tolerance && abs(py - y) <= tolerance }

    @Test
    fun `degenerate sizes produce no path`() {
        assertTrue(SquircleGeometry.path(0f, 40f, 10f).isEmpty())
        assertTrue(SquircleGeometry.path(40f, 0f, 10f).isEmpty())
        assertTrue(SquircleGeometry.path(-10f, 40f, 10f).isEmpty())
        assertTrue(SquircleGeometry.path(40f, 40f, 0f).isEmpty())
    }

    @Test
    fun `radius is clamped so shoulders cannot overlap`() {
        val width = 200f
        val height = 40f
        val requested = 100f

        val expected = min(width, height) / (2f * SquircleGeometry.SHOULDER_FACTOR)
        assertEquals(expected, SquircleGeometry.clampedRadius(width, height, requested), 1e-4f)

        // A radius that already fits is returned untouched.
        assertEquals(8f, SquircleGeometry.clampedRadius(width, height, 8f), 1e-4f)
    }

    @Test
    fun `every point stays inside the enclosing rectangle`() {
        val cases = listOf(
            Triple(130f, 16f, 8f),
            Triple(130f, 38f, 19f),
            Triple(370f, 176f, 44f),
            Triple(48f, 48f, 24f)
        )

        for ((w, h, r) in cases) {
            val path = SquircleGeometry.path(w, h, r, left = 0f, top = 0f)
            assertTrue("path must not be empty for ${w}x$h", path.isNotEmpty())

            for ((x, y) in points(path)) {
                assertTrue("x=$x outside 0..$w (case $w x $h, r=$r)", x >= -0.01f && x <= w + 0.01f)
                assertTrue("y=$y outside 0..$h (case $w x $h, r=$r)", y >= -0.01f && y <= h + 0.01f)
            }
        }
    }

    @Test
    fun `left and top translate the outline without changing its shape`() {
        val origin = points(SquircleGeometry.path(120f, 40f, 12f))
        val shifted = points(SquircleGeometry.path(120f, 40f, 12f, left = 37f, top = 91f))

        assertEquals(origin.size, shifted.size)
        origin.zip(shifted).forEach { (a, b) ->
            assertEquals(a.first + 37f, b.first, 1e-3f)
            assertEquals(a.second + 91f, b.second, 1e-3f)
        }
    }

    /**
     * The four corners are built from one spline table in per-corner frames, so a
     * mixed-up axis or a missing reversal shows up here as an asymmetric outline
     * rather than as a subtly wrong curve on a device.
     */
    @Test
    fun `a square outline is symmetric about both centre lines`() {
        val size = 100f
        val pts = points(SquircleGeometry.path(size, size, 20f))

        for ((x, y) in pts) {
            assertTrue("missing x-mirror of ($x, $y)", contains(pts, size - x, y))
            assertTrue("missing y-mirror of ($x, $y)", contains(pts, x, size - y))
        }
    }

    @Test
    fun `a rectangle outline is symmetric about the vertical centre line`() {
        val width = 300f
        val height = 120f
        val pts = points(SquircleGeometry.path(width, height, 24f))

        for ((x, y) in pts) {
            assertTrue("missing mirror of ($x, $y)", contains(pts, width - x, y))
        }
    }

    @Test
    fun `outline starts on the top edge and closes`() {
        val segments = SquircleGeometry.path(130f, 38f, 10f)
        val first = segments.first()
        val last = segments.last()

        assertTrue(first is SquircleGeometry.Segment.MoveTo)
        first as SquircleGeometry.Segment.MoveTo
        val shoulder = SquircleGeometry.SHOULDER_FACTOR * SquircleGeometry.clampedRadius(130f, 38f, 10f)
        assertEquals(shoulder, first.x, 1e-3f)
        assertEquals(0f, first.y, 1e-3f)
        assertEquals(SquircleGeometry.Segment.Close, last)
    }

    @Test
    fun `the outline is a closed loop of three cubics per corner`() {
        val segments = SquircleGeometry.path(130f, 38f, 10f)
        assertEquals(12, segments.count { it is SquircleGeometry.Segment.CubicTo })
        // One move onto the top edge, then four straight runs into the corners.
        assertEquals(1, segments.count { it is SquircleGeometry.Segment.MoveTo })
        assertEquals(4, segments.count { it is SquircleGeometry.Segment.LineTo })
    }

    /** Sanity check that the walk actually traces a rounded shape, not a line. */
    @Test
    fun `corner curvature pulls the outline away from the bounding box`() {
        val width = 200f
        val height = 100f
        val path = SquircleGeometry.path(width, height, 20f)

        val hasInsetPoint = points(path).any { (x, y) ->
            x > 0.1f && x < width - 0.1f && y > 0.1f && y < height - 0.1f
        }
        assertTrue("outline never leaves the rectangle edges", hasInsetPoint)
    }
}
