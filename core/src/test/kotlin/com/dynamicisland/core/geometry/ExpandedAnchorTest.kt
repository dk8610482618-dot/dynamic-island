package com.dynamicisland.core.geometry

import org.junit.Assert.*
import org.junit.Test

class ExpandedAnchorTest {
    @Test fun `expanded card clears camera status and navigation areas`() {
        val compact = CapsuleAnchor.Window(345, 12, 390, 114, 0f)
        val panel = ExpandedAnchor.resolve(compact, 1080, 2400, 3f, safeTop = 100, safeBottom = 90)
        assertTrue(panel.top >= 124)
        assertTrue(panel.left >= 24)
        assertTrue(panel.left + panel.width <= 1056)
        assertTrue(panel.top + panel.height <= 2310)
        assertTrue(panel.width > compact.width)
    }
    @Test fun `landscape controls stay upright beside either camera edge`() {
        for (rotation in listOf(90f, -90f)) {
            val compact = CapsuleAnchor.Window(if (rotation == 90f) 0 else 2286, 345, 114, 390, rotation)
            val p = ExpandedAnchor.resolve(compact, 2400, 1080, 3f, safeLeft = 120, safeRight = 120)
            assertEquals(0f, p.rotation, 0f)
            assertTrue(p.left >= 144 && p.left + p.width <= 2256)
            assertTrue(p.top >= 24 && p.top + p.height <= 1056)
        }
    }
    @Test fun `large fonts and tiny viewports never produce offscreen or fullscreen sentinel bounds`() {
        for (w in listOf(1, 180, 720)) for (h in listOf(1, 160, 1280)) {
            val p = ExpandedAnchor.resolve(CapsuleAnchor.Window(-100, 900, 390, 114, 0f), w, h, 3f, 2f, 100, 100, 100, 100)
            assertTrue(p.width > 0 && p.height > 0)
            assertTrue(p.left >= 0 && p.top >= 0)
            assertTrue(p.left + p.width <= w && p.top + p.height <= h)
        }
    }
}
