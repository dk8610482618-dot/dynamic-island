package com.dynamicisland.engine.window
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test

class OverlayWindowPolicyTest {
    @Test fun `window is bounded opaque and does not request accessibility`() {
        for (api in listOf(28, 31, 34, 36, 37)) {
            org.mockito.Mockito.mockConstruction(WindowManager.LayoutParams::class.java).use { _ ->
                val p = OverlayWindowPolicy.createLayoutParams(390, 114, api)
                assertEquals(390, p.width)
                assertEquals(114, p.height)
                assertEquals(1f, p.alpha, 0f)
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, p.type)
                assertEquals(0, p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertEquals(0, p.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            }
        }
    }

    @Test fun `accessibility overlay requested sets TYPE_ACCESSIBILITY_OVERLAY`() {
        org.mockito.Mockito.mockConstruction(WindowManager.LayoutParams::class.java).use { _ ->
            val p = OverlayWindowPolicy.createLayoutParams(390, 114, 35, useAccessibilityOverlay = true)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, p.type)
            assertEquals(120f, p.preferredRefreshRate, 0f)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun `fullscreen dimensions rejected`() {
        OverlayWindowPolicy.createLayoutParams(-1, -1, 36)
    }
}
