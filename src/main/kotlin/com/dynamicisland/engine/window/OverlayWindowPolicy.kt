package com.dynamicisland.engine.window

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/** One bounded, touchable application window. Elevates to TYPE_ACCESSIBILITY_OVERLAY when active. */
object OverlayWindowPolicy {
    @Suppress("DEPRECATION")
    const val STANDARD_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

    fun createLayoutParams(
        width: Int,
        height: Int,
        sdkInt: Int = Build.VERSION.SDK_INT,
        useAccessibilityOverlay: Boolean = false
    ): WindowManager.LayoutParams {
        require(width > 0 && height > 0) { "Overlay must have bounded positive dimensions" }
        val windowType = if (useAccessibilityOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams(
            width, height,
            windowType, STANDARD_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.width = width
            this.height = height
            type = windowType
            flags = STANDARD_FLAGS
            format = PixelFormat.TRANSLUCENT
            alpha = 1f
            gravity = Gravity.TOP or Gravity.LEFT
            preferredRefreshRate = 120f
            layoutInDisplayCutoutMode = if (sdkInt >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
    }
}