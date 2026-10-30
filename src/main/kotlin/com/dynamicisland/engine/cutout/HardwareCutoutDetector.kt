package com.dynamicisland.engine.cutout

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.WindowManager
import com.dynamicisland.core.geometry.DeviceGeometryProfile
import com.dynamicisland.core.geometry.PixelRect
import kotlin.math.abs
import kotlin.math.max

/**
 * High-precision detector that extracts physical display cutout parameters,
 * classifies notch architecture, and applies device-specific calibrations.
 *
 * Fully supports:
 *  - API 28+ [DisplayCutout] through [WindowInsets]
 *  - API 30+ [WindowManager.getCurrentWindowMetrics] bounds and insets
 *  - Dynamic density updates across orientation changes and foldable transitions
 *  - Multi-rect dual punch hole bounding box union
 */
class HardwareCutoutDetector(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Resolves the current display density dynamically.
     *
     * Never cached in an instance property: density can change across orientation
     * changes, display-size configuration changes in system settings, and foldable
     * screen transitions between outer and inner displays.
     */
    private fun getDensity(): Float =
        context.resources.displayMetrics.density.coerceAtLeast(1f)

    /**
     * Extracts physical cutout geometry and applies [calibration].
     *
     * On API 30+, if [insets] is null, this queries the authoritative
     * [WindowManager.getCurrentWindowMetrics].
     */
    fun detectCutout(
        insets: WindowInsets? = null,
        calibration: CalibrationConfig = CalibrationConfig()
    ): CutoutGeometry {
        val displayMetrics = getRealDisplayMetrics()
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density.coerceAtLeast(1f)
        val isLandscape = screenWidth > screenHeight

        // `displayCutout` is API 28 and minSdk is 28.
        // On API 30+, WindowManager.currentWindowMetrics.windowInsets can resolve
        // the cutout even if no View-dispatched WindowInsets were provided.
        val cutout: DisplayCutout? = insets?.displayCutout
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { windowManager.currentWindowMetrics.windowInsets.displayCutout }.getOrNull()
            } else {
                null
            }

        val statusBarHeight = getStatusBarHeight(insets)

        if (cutout == null) {
            return fallbackNoCutout(screenWidth, statusBarHeight, density, calibration, isLandscape)
        }

        val allRects = cutout.boundingRects
        if (allRects.isEmpty()) {
            return fallbackNoCutout(screenWidth, statusBarHeight, density, calibration, isLandscape)
        }

        val safeTop = if (cutout.safeInsetTop > 0) cutout.safeInsetTop else statusBarHeight

        // Group cutouts along the top edge (within safeTop or touching top == 0).
        // Where multiple apertures exist (e.g. dual punch holes for camera + IR sensor),
        // we take the bounding union across them. Inspecting only the first rect would
        // misplace the center point, under-report width, and misclassify dual holes.
        val topCutouts = allRects.filter { it.top <= safeTop || it.top == 0 }
        val primaryRect = if (topCutouts.isNotEmpty()) {
            Rect(
                topCutouts.minOf { it.left },
                topCutouts.minOf { it.top },
                topCutouts.maxOf { it.right },
                topCutouts.maxOf { it.bottom }
            )
        } else {
            allRects.first()
        }

        val cutoutType = classifyCutout(primaryRect, allRects, screenWidth, safeTop)

        val calibratedBounds = Rect(
            primaryRect.left + calibration.offsetX - (calibration.widthPadding / 2),
            primaryRect.top + calibration.offsetY,
            primaryRect.right + calibration.offsetX + (calibration.widthPadding / 2),
            primaryRect.bottom + calibration.offsetY + calibration.heightPadding
        )

        val centerPoint = Point(
            calibratedBounds.centerX(),
            calibratedBounds.centerY()
        )

        val baseWidthDp: Float
        val baseHeightDp: Float
        val cornerRadiusDp: Float

        when (cutoutType) {
            CutoutType.CENTER_PUNCH_HOLE -> {
                baseWidthDp = max(110f, (calibratedBounds.width() / density) + 40f)
                baseHeightDp = max(37f, (calibratedBounds.height() / density) + 10f)
                cornerRadiusDp = baseHeightDp / 2f
            }
            CutoutType.CORNER_PUNCH_HOLE_LEFT, CutoutType.CORNER_PUNCH_HOLE_RIGHT -> {
                baseWidthDp = max(120f, (calibratedBounds.width() / density) + 36f)
                baseHeightDp = max(37f, (calibratedBounds.height() / density) + 10f)
                cornerRadiusDp = baseHeightDp / 2f
            }
            CutoutType.DUAL_PILL -> {
                baseWidthDp = max(135f, (calibratedBounds.width() / density) + 30f)
                baseHeightDp = max(40f, (calibratedBounds.height() / density) + 8f)
                cornerRadiusDp = baseHeightDp / 2f
            }
            CutoutType.WATERDROP_NOTCH -> {
                baseWidthDp = max(140f, (calibratedBounds.width() / density) + 50f)
                baseHeightDp = max(42f, (calibratedBounds.height() / density) + 12f)
                cornerRadiusDp = 20f
            }
            CutoutType.NO_CUTOUT -> {
                baseWidthDp = 120f
                baseHeightDp = 36f
                cornerRadiusDp = 18f
            }
        }

        val finalCornerRadius = calibration.cornerRadiusOverride ?: (cornerRadiusDp * density)

        return CutoutGeometry(
            type = cutoutType,
            hardwareBounds = calibratedBounds,
            centerPoint = centerPoint,
            safeInsetTop = safeTop,
            recommendedCompactWidth = (baseWidthDp * density).toInt(),
            recommendedCompactHeight = (baseHeightDp * density).toInt(),
            recommendedCornerRadius = finalCornerRadius,
            safeInsetLeft = cutout.safeInsetLeft,
            safeInsetRight = cutout.safeInsetRight,
            safeInsetBottom = cutout.safeInsetBottom,
            isLandscape = isLandscape
        )
    }

    /**
     * Builds a [DeviceGeometryProfile] combining live display metrics with
     * extracted hardware cutout geometry.
     */
    fun extractDeviceProfile(insets: WindowInsets? = null): DeviceGeometryProfile {
        val metrics = getRealDisplayMetrics()
        val geometry = detectCutout(insets)

        val cutoutRect = if (geometry.type != CutoutType.NO_CUTOUT) {
            PixelRect(
                left = geometry.hardwareBounds.left,
                top = geometry.hardwareBounds.top,
                right = geometry.hardwareBounds.right,
                bottom = geometry.hardwareBounds.bottom
            )
        } else {
            null
        }

        return DeviceGeometryProfile(
            densityDpi = metrics.densityDpi,
            xdpi = metrics.xdpi,
            ydpi = metrics.ydpi,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            cutoutRectPx = cutoutRect,
            cutoutCircleDiameterPx = null
        )
    }

    private fun fallbackNoCutout(
        screenWidth: Int,
        statusBarHeight: Int,
        density: Float,
        calibration: CalibrationConfig,
        isLandscape: Boolean
    ): CutoutGeometry {
        val defaultWidth = (120 * density).toInt() + calibration.widthPadding
        val defaultHeight = (36 * density).toInt() + calibration.heightPadding
        val centerX = (screenWidth / 2) + calibration.offsetX
        val centerY = (statusBarHeight / 2) + calibration.offsetY

        val fakeBounds = Rect(
            centerX - (defaultWidth / 2),
            centerY - (defaultHeight / 2),
            centerX + (defaultWidth / 2),
            centerY + (defaultHeight / 2)
        )

        return CutoutGeometry(
            type = CutoutType.NO_CUTOUT,
            hardwareBounds = fakeBounds,
            centerPoint = Point(centerX, centerY),
            safeInsetTop = statusBarHeight,
            recommendedCompactWidth = defaultWidth,
            recommendedCompactHeight = defaultHeight,
            recommendedCornerRadius = (defaultHeight / 2f),
            isLandscape = isLandscape
        )
    }

    /**
     * Resolves the current status bar inset in pixels.
     *
     * Prioritizes [WindowInsets.Type.statusBars] on API 30+; falls back to the
     * platform dimension resource on API 28-29.
     */
    fun getStatusBarHeight(insets: WindowInsets? = null): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val resolvedInsets = insets ?: runCatching {
                windowManager.currentWindowMetrics.windowInsets
            }.getOrNull()
            resolvedInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top?.let {
                if (it > 0) return it
            }
        }
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            (24 * getDensity()).toInt()
        }
    }

    /**
     * Resolves real display metrics accurately on both API 28-29 and API 30+.
     */
    fun getRealDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.density = context.resources.displayMetrics.density
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
            metrics.xdpi = context.resources.displayMetrics.xdpi
            metrics.ydpi = context.resources.displayMetrics.ydpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    companion object {
        /**
         * Pure classification algorithm mapping cutout bounding geometry to
         * high-level [CutoutType].
         */
        fun classifyCutout(
            topRect: Rect,
            allRects: List<Rect>,
            screenWidth: Int,
            safeTop: Int
        ): CutoutType {
            val width = topRect.width()
            val height = topRect.height()
            val centerX = topRect.centerX()
            val screenCenterX = screenWidth / 2

            val isHorizontallyCentered = abs(centerX - screenCenterX) <= (screenWidth * 0.05f)

            if (allRects.size > 1 && isHorizontallyCentered) {
                return CutoutType.DUAL_PILL
            }

            if (width > height * 2.2f && isHorizontallyCentered) {
                return CutoutType.DUAL_PILL
            }

            if (topRect.top == 0 && height >= (safeTop * 0.8f) && width < (screenWidth * 0.25f)) {
                if (height > 0 && width > 0 && (width.toFloat() / height.toFloat()) < 1.8f) {
                    return CutoutType.WATERDROP_NOTCH
                }
            }

            if (isHorizontallyCentered) {
                return CutoutType.CENTER_PUNCH_HOLE
            }

            return if (centerX < screenCenterX) {
                CutoutType.CORNER_PUNCH_HOLE_LEFT
            } else {
                CutoutType.CORNER_PUNCH_HOLE_RIGHT
            }
        }
    }
}
