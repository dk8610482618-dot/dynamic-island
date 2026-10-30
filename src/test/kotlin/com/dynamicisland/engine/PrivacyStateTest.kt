package com.dynamicisland.engine

import org.junit.Assert.*
import org.junit.Test

class PrivacyStateTest {
    enum class PrivacyIndicatorColor(val hex: Long) {
        ORANGE(0xFFFF9F0AL), // iOS Dark Mode Orange (Mic)
        GREEN(0xFF30D158L),  // iOS Dark Mode Green (Camera)
        BLUE(0xFF0A84FFL)    // iOS Dark Mode Blue (Both)
    }

    private fun resolvePrivacyColor(micActive: Boolean, cameraActive: Boolean): PrivacyIndicatorColor? = when {
        micActive && cameraActive -> PrivacyIndicatorColor.BLUE
        cameraActive -> PrivacyIndicatorColor.GREEN
        micActive -> PrivacyIndicatorColor.ORANGE
        else -> null
    }

    @Test
    fun `mic only resolves to iOS orange`() {
        val color = resolvePrivacyColor(micActive = true, cameraActive = false)
        assertEquals(PrivacyIndicatorColor.ORANGE, color)
        assertEquals(0xFFFF9F0AL, color?.hex)
    }

    @Test
    fun `camera only resolves to iOS green`() {
        val color = resolvePrivacyColor(micActive = false, cameraActive = true)
        assertEquals(PrivacyIndicatorColor.GREEN, color)
        assertEquals(0xFF30D158L, color?.hex)
    }

    @Test
    fun `concurrent mic and camera resolves to iOS blue`() {
        val color = resolvePrivacyColor(micActive = true, cameraActive = true)
        assertEquals(PrivacyIndicatorColor.BLUE, color)
        assertEquals(0xFF0A84FFL, color?.hex)
    }

    @Test
    fun `neither active resolves to null`() {
        val color = resolvePrivacyColor(micActive = false, cameraActive = false)
        assertNull(color)
    }
}