package com.dynamicisland.core.geometry

/**
 * Millimetres per inch, the international standard conversion constant.
 */
const val MM_PER_INCH = 25.4f

/**
 * A rectangle in integer pixel coordinates.
 *
 * Stands in for Android's `android.graphics.Rect` inside the pure-JVM :core
 * module so geometry math remains testable without Android platform stubs.
 */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** Returns true if this rectangle intersects [other]. */
    fun intersects(other: PixelRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    /** Returns the bounding union enclosing this rectangle and [other]. */
    fun union(other: PixelRect): PixelRect = PixelRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )

    /** Returns true if the given pixel point falls within this rectangle. */
    fun contains(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom

    /** Returns true if the given float point falls within this rectangle. */
    fun contains(x: Float, y: Float): Boolean =
        x >= left.toFloat() && x < right.toFloat() && y >= top.toFloat() && y < bottom.toFloat()
}

/**
 * The display facts needed to place and size the island correctly.
 *
 * Deliberately mirrors Android's `DisplayMetrics` and `WindowMetrics` without
 * depending on them, so this module stays compilable on a plain JVM.
 *
 * @param densityDpi Android's density bucket, which defines what 1 dp means for
 *   layout. This is a **UI tuning value**, not a measurement of the panel.
 * @param xdpi physical horizontal dots-per-inch as the panel reports it. On many
 *   devices this is accurate; on many others it is `0`, wrong, or simply a copy
 *   of [densityDpi].
 * @param ydpi physical vertical dots-per-inch.
 * @param widthPx current display width in pixels.
 * @param heightPx current display height in pixels.
 * @param cutoutRectPx Physical cutout bounds in pixels, if the display has one.
 * @param cutoutCircleDiameterPx The cutout's true circular aperture in pixels,
 *   where the platform exposes one.
 */
data class DeviceGeometryProfile(
    val densityDpi: Int,
    val xdpi: Float,
    val ydpi: Float,
    val widthPx: Int,
    val heightPx: Int,
    /** Physical cutout bounds in pixels, if the display has one. */
    val cutoutRectPx: PixelRect? = null,
    /**
     * The cutout's true circular aperture in pixels, where the platform exposes
     * one. Preferred over [cutoutRectPx] for sizing — Android's bounding rect is
     * a touch-occlusion rectangle and is routinely much larger than the hole.
     */
    val cutoutCircleDiameterPx: Float? = null,
) {
    /** Android's density scale factor: `densityDpi / 160`. */
    val densityScale: Float get() = if (densityDpi > 0) densityDpi / 160f else 1f

    /** Screen width in Android dp, as the layout system understands it. */
    val widthDp: Float get() = if (densityScale > 0f) widthPx / densityScale else 0f

    /** Screen height in Android dp. */
    val heightDp: Float get() = if (densityScale > 0f) heightPx / densityScale else 0f

    /** Screen width in physical millimetres, using the real panel density. */
    val widthMm: Float get() = if (xdpi > 0f) widthPx / xdpi * MM_PER_INCH else 0f

    /** Screen height in physical millimetres, using the real panel density. */
    val heightMm: Float get() = if (ydpi > 0f) heightPx / ydpi * MM_PER_INCH else 0f

    /** Whether the current display metrics represent landscape orientation. */
    val isLandscape: Boolean get() = widthPx > heightPx

    /** Whether the current display metrics represent portrait orientation. */
    val isPortrait: Boolean get() = heightPx >= widthPx

    /** Display aspect ratio (width / height). Safe against division by zero. */
    val aspectRatio: Float get() = if (heightPx > 0) widthPx.toFloat() / heightPx.toFloat() else 0f
}
