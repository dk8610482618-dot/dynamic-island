package com.arena.dynamicisland.island

import android.graphics.Rect
import android.graphics.Region
import android.view.DisplayCutout
import android.view.WindowManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Normalizes Android DisplayCutout hardware geometry and manages TYPE_APPLICATION_OVERLAY flags.
 */
object IslandCutoutNormalizer {
    private val HorizontalOcclusionPadding = 16.dp
    private val VerticalOcclusionPadding = 6.dp

    /**
     * Computes resting island bounds from DisplayCutout.getBoundingRectTop():
     * IslandWidth_min = max(CutoutRect.width + 2 * 16dp, 125dp)
     * IslandHeight_min = max(CutoutRect.height + 2 * 6dp, 37dp)
     */
    fun normalizeCutoutBounds(
        cutoutWidthDp: Float,
        cutoutHeightDp: Float,
    ): DpSize {
        val safeW = if (cutoutWidthDp.isFinite() && cutoutWidthDp > 0f) cutoutWidthDp else 0f
        val safeH = if (cutoutHeightDp.isFinite() && cutoutHeightDp > 0f) cutoutHeightDp else 0f
        val width = maxOf((safeW + 32f).dp, IslandSize.CollapsedWidth)
        val height = maxOf((safeH + 12f).dp, IslandSize.Height)
        return DpSize(width, height)
    }

    /**
     * Expands visual bounds into accessibility-compliant touch hit rect:
     * top - 6dp, bottom + 10dp, left - 12dp, right + 12dp.
     */
    fun computeInteractiveHitRect(visualRectPx: Rect, density: Float): Rect {
        val topPad = (6f * density).toInt()
        val bottomPad = (10f * density).toInt()
        val horizPad = (12f * density).toInt()
        return Rect(
            visualRectPx.left - horizPad,
            visualRectPx.top - topPad,
            visualRectPx.right + horizPad,
            visualRectPx.bottom + bottomPad,
        )
    }

    /**
     * Standard WindowManager.LayoutParams flags for non-intrusive system overlay hosting.
     */
    const val OVERLAY_WINDOW_FLAGS: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
}
