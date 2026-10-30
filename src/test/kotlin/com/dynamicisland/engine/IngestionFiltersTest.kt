package com.dynamicisland.engine

import com.dynamicisland.engine.ingestion.IngestionFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins ingestion rules that keep noise and placeholders out of the island.
 */
class IngestionFiltersTest {

    // --- Overlay-alert notice ---

    @Test
    fun `the system overlay-alert channel is dropped`() {
        assertTrue(
            IngestionFilters.isOverlayAlertNotice("com.android.server.wm.AlertWindowNotification")
        )
    }

    @Test
    fun `the system overlay-alert channel with AOSP package suffix is dropped`() {
        assertTrue(
            IngestionFilters.isOverlayAlertNotice("com.android.server.wm.AlertWindowNotification - com.dynamicisland.host")
        )
        assertTrue(
            IngestionFilters.isOverlayAlertNotice("com.android.server.wm.AlertWindowNotification:com.dynamicisland.host")
        )
    }

    @Test
    fun `an absent channel is not an overlay alert`() {
        assertFalse(IngestionFilters.isOverlayAlertNotice(null))
    }

    @Test
    fun `an ordinary notification channel is kept`() {
        assertFalse(IngestionFilters.isOverlayAlertNotice("messages"))
        assertFalse(IngestionFilters.isOverlayAlertNotice("com.spotify.music.playback"))
    }

    @Test
    fun `a channel that resembles the alert-window id without matching prefix is kept`() {
        assertFalse(IngestionFilters.isOverlayAlertNotice("AlertWindowNotification"))
        assertFalse(IngestionFilters.isOverlayAlertNotice("com.android.server.wm.AlertWindow"))
    }

    // --- Media titles ---

    @Test
    fun `the metadata title wins over the display title`() {
        assertEquals(
            "Nude",
            IngestionFilters.displayableTitle(rawTitle = "Nude", rawDisplayTitle = "Radiohead — Nude")
        )
    }

    @Test
    fun `the display title is used when there is no metadata title`() {
        assertEquals(
            "Radiohead — Nude",
            IngestionFilters.displayableTitle(rawTitle = null, rawDisplayTitle = "Radiohead — Nude")
        )
    }

    @Test
    fun `a session with no title at all has nothing to display`() {
        assertNull(IngestionFilters.displayableTitle(rawTitle = null, rawDisplayTitle = null))
    }

    @Test
    fun `whitespace is not a title`() {
        assertNull(IngestionFilters.displayableTitle(rawTitle = "", rawDisplayTitle = null))
        assertNull(IngestionFilters.displayableTitle(rawTitle = "   ", rawDisplayTitle = null))
        assertNull(IngestionFilters.displayableTitle(rawTitle = null, rawDisplayTitle = "\t\n"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Nude", IngestionFilters.displayableTitle(rawTitle = "  Nude  ", rawDisplayTitle = null))
    }

    @Test
    fun `a blank metadata title falls through to a real display title`() {
        assertEquals(
            "Radiohead — Nude",
            IngestionFilters.displayableTitle(rawTitle = "  ", rawDisplayTitle = "Radiohead — Nude")
        )
    }

    @Test
    fun `placeholder titles are treated as absent`() {
        assertNull(IngestionFilters.displayableTitle(rawTitle = "Unknown Title", rawDisplayTitle = null))
        assertNull(IngestionFilters.displayableTitle(rawTitle = "unknown", rawDisplayTitle = null))
        assertNull(IngestionFilters.displayableTitle(rawTitle = "null", rawDisplayTitle = null))
        assertNull(IngestionFilters.displayableTitle(rawTitle = "Untitled", rawDisplayTitle = null))
        assertNull(IngestionFilters.displayableTitle(rawTitle = "  Unknown Title  ", rawDisplayTitle = "unknown"))
    }

    @Test
    fun `placeholder metadata falls through to a real display title`() {
        assertEquals(
            "Radiohead — Nude",
            IngestionFilters.displayableTitle(rawTitle = "Unknown Title", rawDisplayTitle = "Radiohead — Nude")
        )
    }

    @Test
    fun `no empty or placeholder combination produces a title`() {
        val cases = listOf<Pair<String?, String?>>(
            null to null,
            "" to null,
            " " to null,
            null to "",
            "  " to "   ",
            "\t" to "\n",
            "Unknown Title" to "null",
            "unknown" to "Untitled"
        )
        cases.forEach { (raw, display) ->
            assertNull(
                "expected no title for raw='$raw' display='$display'",
                IngestionFilters.displayableTitle(raw, display)
            )
        }
    }

    // --- Self-Notification and System Noise Filtering ---

    @Test
    fun `self notifications are identified correctly`() {
        assertTrue(IngestionFilters.isSelfNotification("com.dynamicisland.host", "com.dynamicisland.host"))
        assertFalse(IngestionFilters.isSelfNotification("com.spotify.music", "com.dynamicisland.host"))
        assertFalse(IngestionFilters.isSelfNotification(null, "com.dynamicisland.host"))
    }

    @Test
    fun `system noise channels are dropped`() {
        assertTrue(IngestionFilters.isSystemNoiseChannel("DEVELOPMENT"))
        assertTrue(IngestionFilters.isSystemNoiseChannel("usb_connection_status"))
        assertTrue(IngestionFilters.isSystemNoiseChannel("FOREGROUND_SERVICE"))
        assertFalse(IngestionFilters.isSystemNoiseChannel("messages"))
        assertFalse(IngestionFilters.isSystemNoiseChannel("calls"))
        assertFalse(IngestionFilters.isSystemNoiseChannel(null))
    }

    // --- Title Sanitization with App Label Fallback ---

    @Test
    fun `title sanitization falls back to app label or package`() {
        assertEquals(
            "Valid Title",
            IngestionFilters.sanitizeNotificationTitle("Valid Title", "com.pkg") { "App Label" }
        )
        assertEquals(
            "App Label",
            IngestionFilters.sanitizeNotificationTitle("Unknown Title", "com.pkg") { "App Label" }
        )
        assertEquals(
            "com.pkg",
            IngestionFilters.sanitizeNotificationTitle("Unknown Title", "com.pkg") { null }
        )
    }
}
