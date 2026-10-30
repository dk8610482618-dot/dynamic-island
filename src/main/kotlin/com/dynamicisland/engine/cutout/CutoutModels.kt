package com.dynamicisland.engine.cutout

import android.graphics.Point
import android.graphics.Rect

/**
 * Hardware classification of the physical display cutout.
 */
enum class CutoutType {
    CENTER_PUNCH_HOLE,
    CORNER_PUNCH_HOLE_LEFT,
    CORNER_PUNCH_HOLE_RIGHT,
    DUAL_PILL,
    WATERDROP_NOTCH,
    NO_CUTOUT
}

/**
 * Geometric parameters derived from hardware extraction and calibration.
 */
data class CutoutGeometry(
    val type: CutoutType,
    val hardwareBounds: Rect,
    val centerPoint: Point,
    val safeInsetTop: Int,
    val recommendedCompactWidth: Int,
    val recommendedCompactHeight: Int,
    val recommendedCornerRadius: Float,
    val safeInsetLeft: Int = 0,
    val safeInsetRight: Int = 0,
    val safeInsetBottom: Int = 0,
    val isLandscape: Boolean = false
)

/**
 * Calibration profile for fine-tuning positioning across device variants.
 */
data class CalibrationConfig(
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val widthPadding: Int = 0,
    val heightPadding: Int = 0,
    val cornerRadiusOverride: Float? = null
)
