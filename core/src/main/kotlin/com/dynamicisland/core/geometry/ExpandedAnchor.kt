package com.dynamicisland.core.geometry

import kotlin.math.roundToInt

/** A user-opened card stays inside the usable display, next to the compact anchor. */
object ExpandedAnchor {
    fun resolve(compact: CapsuleAnchor.Window, screenWidth: Int, screenHeight: Int,
        density: Float, fontScale: Float = 1f, safeTop: Int = 0, safeBottom: Int = 0,
        safeLeft: Int = 0, safeRight: Int = 0): CapsuleAnchor.Window {
        require(screenWidth > 0 && screenHeight > 0 && density.isFinite() && density > 0)
        val margin = (8 * density).roundToInt()
        val left = (safeLeft + margin).coerceIn(0, screenWidth - 1)
        val right = (screenWidth - safeRight - margin).coerceIn(left + 1, screenWidth)
        val top = (safeTop + margin).coerceIn(0, screenHeight - 1)
        val bottom = (screenHeight - safeBottom - margin).coerceIn(top + 1, screenHeight)
        val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f
        val w = (366 * density).roundToInt().coerceIn(1, right - left)
        val h = ((260 + (scale - 1) * 100) * density).roundToInt().coerceIn(1, bottom - top)
        val x = when (compact.rotation) {
            90f -> maxOf(left, compact.left)
            -90f -> compact.left + compact.width - w
            else -> compact.left + (compact.width - w) / 2
        }.coerceIn(left, right - w)
        val y = when (compact.rotation) {
            90f, -90f -> compact.top + (compact.height - h) / 2
            180f -> compact.top + compact.height - h
            else -> compact.top
        }.coerceIn(top, bottom - h)
        // Keep text and controls upright even with a camera on a side edge.
        return CapsuleAnchor.Window(x, y, w, h, 0f)
    }
}
