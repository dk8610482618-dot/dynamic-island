package com.dynamicisland.engine.touch

import com.dynamicisland.engine.touch.TouchRegionGeometry.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the input-region expansion rule.
 *
 * The overlay window is fullscreen, so this region is the only thing standing
 * between the island and a device that ignores every touch. Both failure modes
 * are covered here: a region that is too small (the pill stops responding) and
 * one that is too large (taps meant for the app underneath are swallowed).
 */
class TouchRegionGeometryTest {

    private val pill = Bounds(left = 228, top = 479, right = 878, bottom = 603)

    @Test
    fun `a lone pill expands by the slop on every side`() {
        val result = TouchRegionGeometry.expand(pill, bubble = null, slopPx = 16)

        assertEquals(1, result.size)
        assertEquals(Bounds(212, 463, 894, 619), result[0])
    }

    @Test
    fun `a zero slop leaves the pill unchanged`() {
        val result = TouchRegionGeometry.expand(pill, bubble = null, slopPx = 0)

        assertEquals(listOf(pill), result)
    }

    @Test
    fun `an empty pill produces no region at all`() {
        val empty = Bounds(0, 0, 0, 0)

        assertTrue(TouchRegionGeometry.expand(empty, bubble = null, slopPx = 16).isEmpty())
    }

    @Test
    fun `a detached bubble contributes its own component with non-interactive gap`() {
        // 36 dp bubble 8 dp to the right of the pill, at 2.625 density.
        val bubble = Bounds(left = 899, top = 494, right = 993, bottom = 588)

        val result = TouchRegionGeometry.expand(pill, bubble, slopPx = 16)

        assertEquals(2, result.size)
        // Outer edges expand by slop; facing edges do not bridge the gap.
        assertEquals(Bounds(212, 463, 878, 619), result[0])
        assertEquals(Bounds(899, 478, 1009, 604), result[1])
        assertTrue("gap between pill and bubble must be preserved", result[0].right < result[1].left)
    }

    /**
     * Non-interactive gap pass-through:
     *
     * In the real layout, the bubble sits 8 dp to the right of the pill.
     * The facing edges between them do not expand into the gap, so touches in the
     * gap pass through to whatever is underneath rather than being swallowed by
     * a continuous bounding box.
     */
    @Test
    fun `gap between pill and bubble remains non-interactive and pass-through`() {
        val bubble = Bounds(left = 899, top = 494, right = 993, bottom = 588)

        val result = TouchRegionGeometry.expand(pill, bubble, slopPx = 16)

        assertEquals(2, result.size)
        assertTrue("pill right must be strictly less than bubble left", result[0].right < result[1].left)
        // The gap from 878 to 899 (21 px) is completely unmapped by the touch region
        assertEquals(878, result[0].right)
        assertEquals(899, result[1].left)
    }

    /**
     * The case where the two formulations genuinely differ: a bubble far enough
     * below the pill that the expanded rects do not overlap.
     */
    @Test
    fun `vertically detached components are not merged into a bounding box`() {
        val farBubble = Bounds(left = 899, top = 1200, right = 993, bottom = 1294)

        val result = TouchRegionGeometry.expand(pill, farBubble, slopPx = 16)

        assertEquals(2, result.size)
        // With no vertical overlap, facing horizontal edge suppression is not applied
        assertEquals(894, result[0].right)
        // The expanded pill still stops at its own bottom edge, not the bubble's.
        assertEquals(619, result[0].bottom)
        // And the bubble's own top edge is well below it.
        assertEquals(1184, result[1].top)
        assertTrue("components must not overlap", result[0].bottom < result[1].top)
    }

    @Test
    fun `pill anchored at screen origin clamps negative coordinates`() {
        val originPill = Bounds(left = 0, top = 0, right = 400, bottom = 100)
        val result = TouchRegionGeometry.expand(originPill, bubble = null, slopPx = 16)

        assertEquals(1, result.size)
        assertTrue("left boundary cannot be negative", result[0].left >= 0)
        assertTrue("top boundary cannot be negative", result[0].top >= 0)
    }

    @Test
    fun `an empty bubble is ignored rather than producing a degenerate component`() {
        val emptyBubble = Bounds(500, 500, 500, 500)

        val result = TouchRegionGeometry.expand(pill, emptyBubble, slopPx = 16)

        assertEquals(1, result.size)
    }

    @Test
    fun `a bubble overlapping the pill still yields both components`() {
        // Mid-animation the bubble can overlap the pill; overlapping components
        // are harmless (their union is what matters) but must not be dropped.
        val overlap = Bounds(left = 800, top = 490, right = 894, bottom = 584)

        val result = TouchRegionGeometry.expand(pill, overlap, slopPx = 16)

        assertEquals(2, result.size)
    }

    @Test
    fun `pill extending beyond screen boundaries is strictly clamped`() {
        // Pill extending past right (1080) and bottom (2400) edges
        val borderPill = Bounds(left = 900, top = 2300, right = 1150, bottom = 2450)
        val result = TouchRegionGeometry.expand(
            pill = borderPill,
            bubble = null,
            slopPx = 16,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, result.size)
        assertEquals(Bounds(884, 2284, 1080, 2400), result[0])
    }

    @Test
    fun `slop expansion extending beyond screen boundaries is clamped to display edges`() {
        // Pill is within screen, but slop padding would cross screen boundary
        val nearEdgePill = Bounds(left = 10, top = 10, right = 1075, bottom = 2395)
        val result = TouchRegionGeometry.expand(
            pill = nearEdgePill,
            bubble = null,
            slopPx = 16,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(1, result.size)
        // left and top clamped to 0, right clamped to 1080, bottom clamped to 2400
        assertEquals(Bounds(0, 0, 1080, 2400), result[0])
    }

    @Test
    fun `pill completely offscreen returns no region at all`() {
        val offscreenLeft = Bounds(-200, 100, -10, 200)
        val offscreenRight = Bounds(1200, 100, 1400, 200)
        val offscreenTop = Bounds(100, -200, 200, -50)
        val offscreenBottom = Bounds(100, 2500, 200, 2600)

        assertTrue(TouchRegionGeometry.expand(offscreenLeft, null, 16, 1080, 2400).isEmpty())
        assertTrue(TouchRegionGeometry.expand(offscreenRight, null, 16, 1080, 2400).isEmpty())
        assertTrue(TouchRegionGeometry.expand(offscreenTop, null, 16, 1080, 2400).isEmpty())
        assertTrue(TouchRegionGeometry.expand(offscreenBottom, null, 16, 1080, 2400).isEmpty())
    }

    @Test
    fun `negative slop is sanitized to zero without shrinking or inverting bounds`() {
        val result = TouchRegionGeometry.expand(pill, bubble = null, slopPx = -20)

        // Negative slop must not shrink or invert; treated as 0 slop
        assertEquals(listOf(pill), result)
    }

    @Test
    fun `bubble to the left of the pill preserves non-interactive pass-through gap`() {
        // Bubble placed 26 px to the left of the pill
        val leftBubble = Bounds(left = 100, top = 494, right = 194, bottom = 588)
        val result = TouchRegionGeometry.expand(pill, leftBubble, slopPx = 16)

        assertEquals(2, result.size)
        // Pill is result[0], bubble is result[1]
        // Facing edges: pill left (228) and bubble right (194) do not expand into the gap
        assertEquals(228, result[0].left)
        assertEquals(194, result[1].right)
        assertTrue("bubble right must be strictly less than pill left", result[1].right < result[0].left)
        // Outer edges expand normally
        assertEquals(894, result[0].right) // pill right expands
        assertEquals(84, result[1].left)   // bubble left expands
    }

    @Test
    fun `completely offscreen bubble is ignored and does not suppress pill slop`() {
        val offscreenBubble = Bounds(-100, 494, -10, 588)
        val result = TouchRegionGeometry.expand(
            pill = pill,
            bubble = offscreenBubble,
            slopPx = 16,
            screenWidth = 1080,
            screenHeight = 2400
        )

        // Pill should expand on all four sides as a lone pill, not suppressed by phantom bubble
        assertEquals(1, result.size)
        assertEquals(Bounds(212, 463, 894, 619), result[0])
    }

    @Test
    fun `partially offscreen bubble is clamped without negative coordinates`() {
        val leftBubble = Bounds(-10, 494, 100, 588)
        val result = TouchRegionGeometry.expand(
            pill = pill,
            bubble = leftBubble,
            slopPx = 16,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(2, result.size)
        assertTrue("bubble left coordinate must not be negative", result[1].left >= 0)
        assertEquals(0, result[1].left)
    }

    @Test
    fun `degenerate screen dimensions produce no region`() {
        assertTrue(TouchRegionGeometry.expand(pill, null, 16, screenWidth = 0, screenHeight = 2400).isEmpty())
        assertTrue(TouchRegionGeometry.expand(pill, null, 16, screenWidth = 1080, screenHeight = 0).isEmpty())
        assertTrue(TouchRegionGeometry.expand(pill, null, 16, screenWidth = -50, screenHeight = -50).isEmpty())
    }

    @Test
    fun `degenerate or inverted pill bounds produce no region`() {
        val invertedX = Bounds(left = 500, top = 100, right = 400, bottom = 200)
        val invertedY = Bounds(left = 100, top = 500, right = 200, bottom = 400)
        val zeroWidth = Bounds(left = 100, top = 100, right = 100, bottom = 200)
        val zeroHeight = Bounds(left = 100, top = 100, right = 200, bottom = 100)

        assertTrue(TouchRegionGeometry.expand(invertedX, null, 16).isEmpty())
        assertTrue(TouchRegionGeometry.expand(invertedY, null, 16).isEmpty())
        assertTrue(TouchRegionGeometry.expand(zeroWidth, null, 16).isEmpty())
        assertTrue(TouchRegionGeometry.expand(zeroHeight, null, 16).isEmpty())
    }

    @Test
    fun `bounds clamp with inverted or invalid clamp boundaries returns empty bounds`() {
        val rect = Bounds(10, 10, 50, 50)
        val clampedInverted = rect.clamp(minX = 100, maxX = 50)
        assertTrue(clampedInverted.isEmpty)
    }

    @Test
    fun `adjacent bubble touching pill edge-to-edge does not crash and produces valid union`() {
        // Bubble touching pill edge directly (pill.right == bubble.left == 878)
        val adjacentBubble = Bounds(left = 878, top = 494, right = 950, bottom = 588)
        val result = TouchRegionGeometry.expand(pill, adjacentBubble, slopPx = 16)

        assertEquals(2, result.size)
        assertTrue(!result[0].isEmpty)
        assertTrue(!result[1].isEmpty)
    }
}
