package com.dynamicisland.engine.touch

/**
 * Pure geometry for the overlay's input region.
 *
 * The overlay window spans the whole screen so the island can be placed
 * anywhere, and this is what carves the *input* hole in it: everything inside
 * the returned bounds is delivered to the island, everything outside passes
 * straight through to whatever is underneath.
 *
 * Kept free of `android.graphics` so the rule can be tested on the JVM.
 */
object TouchRegionGeometry {

    /** An integer rectangle in screen pixels. */
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val isEmpty: Boolean get() = right <= left || bottom <= top

        /** Grows (positive delta) or shrinks (negative) the rect on every side. */
        fun inset(delta: Int): Bounds {
            val l = left - delta
            val t = top - delta
            val r = right + delta
            val b = bottom + delta
            return if (r <= l || b <= t) Bounds(0, 0, 0, 0) else Bounds(l, t, r, b)
        }

        /** Clamps bounds to screen pixel boundaries, eliminating negative coordinates. */
        fun clamp(
            minX: Int = 0,
            minY: Int = 0,
            maxX: Int = Int.MAX_VALUE,
            maxY: Int = Int.MAX_VALUE
        ): Bounds {
            if (isEmpty || maxX <= minX || maxY <= minY) return Bounds(0, 0, 0, 0)
            val cl = left.coerceIn(minX, maxX)
            val ct = top.coerceIn(minY, maxY)
            val cr = right.coerceIn(minX, maxX)
            val cb = bottom.coerceIn(minY, maxY)
            return if (cr <= cl || cb <= ct) Bounds(0, 0, 0, 0) else Bounds(cl, ct, cr, cb)
        }
    }

    /**
     * Expands the pill — and any detached bubble — by [slopPx] on outer sides,
     * while preserving the non-interactive gap between them so taps in the gap
     * pass through to underlying windows.
     *
     * Coordinates are strictly clamped against [0, screenWidth] and [0, screenHeight]
     * to eliminate negative coordinates and out-of-screen bounds, protecting WindowManager
     * from invalid region allocations.
     *
     * @return one entry per non-empty input rect; empty when [pill] is empty or off-screen.
     */
    fun expand(
        pill: Bounds,
        bubble: Bounds? = null,
        slopPx: Int = 0,
        screenWidth: Int = Int.MAX_VALUE,
        screenHeight: Int = Int.MAX_VALUE
    ): List<Bounds> {
        val minX = 0
        val minY = 0
        val maxX = maxOf(0, screenWidth)
        val maxY = maxOf(0, screenHeight)

        if (pill.isEmpty || maxX <= minX || maxY <= minY) return emptyList()

        // If the pill itself does not intersect the screen at all, return empty
        if (pill.clamp(minX, minY, maxX, maxY).isEmpty) return emptyList()

        val slop = maxOf(0, slopPx)

        // Clamp bubble against screen boundaries; an off-screen or empty bubble
        // contributes no touch target and must not suppress pill slop.
        val activeBubble = bubble?.takeIf { !it.isEmpty }
            ?.clamp(minX, minY, maxX, maxY)
            ?.takeIf { !it.isEmpty }

        if (activeBubble == null) {
            val expandedPill = pill.inset(slop).clamp(minX, minY, maxX, maxY)
            return if (expandedPill.isEmpty) emptyList() else listOf(expandedPill)
        }

        val out = ArrayList<Bounds>(2)
        // Two boxes have facing horizontal edges only if their vertical spans overlap
        val hasVerticalOverlap = maxOf(pill.top, activeBubble.top) < minOf(pill.bottom, activeBubble.bottom)

        if (hasVerticalOverlap && pill.right < activeBubble.left) {
            // Bubble is to the right: facing edges do not expand into the gap,
            // keeping the gap non-interactive and pass-through.
            out += Bounds(
                left = pill.left - slop,
                top = pill.top - slop,
                right = pill.right,
                bottom = pill.bottom + slop
            ).clamp(minX, minY, maxX, maxY)

            out += Bounds(
                left = activeBubble.left,
                top = activeBubble.top - slop,
                right = activeBubble.right + slop,
                bottom = activeBubble.bottom + slop
            ).clamp(minX, minY, maxX, maxY)
        } else if (hasVerticalOverlap && activeBubble.right < pill.left) {
            // Bubble is to the left: facing edges do not expand into the gap.
            out += Bounds(
                left = pill.left,
                top = pill.top - slop,
                right = pill.right + slop,
                bottom = pill.bottom + slop
            ).clamp(minX, minY, maxX, maxY)

            out += Bounds(
                left = activeBubble.left - slop,
                top = activeBubble.top - slop,
                right = activeBubble.right,
                bottom = activeBubble.bottom + slop
            ).clamp(minX, minY, maxX, maxY)
        } else {
            // Overlapping or vertically disjoint components
            out += pill.inset(slop).clamp(minX, minY, maxX, maxY)
            out += activeBubble.inset(slop).clamp(minX, minY, maxX, maxY)
        }
        return out.filter { !it.isEmpty }
    }
}
