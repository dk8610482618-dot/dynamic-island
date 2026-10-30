package com.dynamicisland.core.system

import com.dynamicisland.core.system.MiniStatusBarPolicy.SignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G4**. The imported base captured the clock once with
 * `remember { SimpleDateFormat("HH:mm").format(Date()) }`, so the mini status bar
 * showed a frozen time for the lifetime of the overlay. These tests pin the
 * replacement: exact minute-boundary scheduling (no per-second polling) and real
 * signal/battery mapping.
 */
class MiniStatusBarPolicyTest {

    // --------------------------------------------------------- clock

    @Test
    fun `the clock tick never returns a zero or negative delay`() {
        // A zero delay would make the engine reschedule in a tight loop, which is
        // exactly the polling doc 1 forbids.
        for (offset in 0L..120_000L step 7L) {
            val delay = MiniStatusBarPolicy.millisUntilNextClockTick(offset)
            assertTrue("delay at $offset was $delay", delay in 1L..MiniStatusBarPolicy.MINUTE_MS)
        }
    }

    @Test
    fun `the tick delay lands exactly on the minute boundary`() {
        assertEquals(30_000L, MiniStatusBarPolicy.millisUntilNextClockTick(30_000L))
        assertEquals(1L, MiniStatusBarPolicy.millisUntilNextClockTick(59_999L))
        assertEquals(60_000L, MiniStatusBarPolicy.millisUntilNextClockTick(60_000L))
        assertEquals(60_000L, MiniStatusBarPolicy.millisUntilNextClockTick(0L))
    }

    @Test
    fun `waiting the returned delay advances the displayed minute`() {
        val start = 1_700_000_030_123L
        val delay = MiniStatusBarPolicy.millisUntilNextClockTick(start)
        val before = MiniStatusBarPolicy.formatClock(start)
        val after = MiniStatusBarPolicy.formatClock(start + delay)
        assertTrue("clock did not advance: $before -> $after", before != after)
    }

    @Test
    fun `half hour and forty five minute timezones format correctly`() {
        // 1970-01-01T00:00Z
        assertEquals("00:00", MiniStatusBarPolicy.formatClock(0L, timezoneOffsetMinutes = 0))
        assertEquals("05:30", MiniStatusBarPolicy.formatClock(0L, timezoneOffsetMinutes = 330))   // IST
        assertEquals("05:45", MiniStatusBarPolicy.formatClock(0L, timezoneOffsetMinutes = 345))   // Nepal
        assertEquals("19:00", MiniStatusBarPolicy.formatClock(0L, timezoneOffsetMinutes = -300))  // US Eastern
    }

    @Test
    fun `twelve hour format uses twelve for midnight and noon`() {
        assertEquals("12:00 AM", MiniStatusBarPolicy.formatClock(0L, is24Hour = false))
        // 12 hours and 13h05 in millis — not 12 and 13 *minutes*.
        assertEquals(
            "12:00 PM",
            MiniStatusBarPolicy.formatClock(12L * 60L * 60_000L, is24Hour = false)
        )
        assertEquals(
            "1:05 PM",
            MiniStatusBarPolicy.formatClock((13L * 60L + 5L) * 60_000L, is24Hour = false)
        )
    }

    // -------------------------------------------------------- signal

    @Test
    fun `wifi rssi maps to the android level thresholds`() {
        assertEquals(SignalLevel.EXCELLENT, MiniStatusBarPolicy.wifiLevel(-40))
        assertEquals(SignalLevel.EXCELLENT, MiniStatusBarPolicy.wifiLevel(-55))
        assertEquals(SignalLevel.GOOD, MiniStatusBarPolicy.wifiLevel(-60))
        assertEquals(SignalLevel.MODERATE, MiniStatusBarPolicy.wifiLevel(-75))
        assertEquals(SignalLevel.POOR, MiniStatusBarPolicy.wifiLevel(-95))
        assertEquals(SignalLevel.NONE, MiniStatusBarPolicy.wifiLevel(-120))
    }

    @Test
    fun `cellular rsrp maps to the lte level thresholds`() {
        assertEquals(SignalLevel.EXCELLENT, MiniStatusBarPolicy.cellularLevel(-80))
        assertEquals(SignalLevel.GOOD, MiniStatusBarPolicy.cellularLevel(-95))
        assertEquals(SignalLevel.MODERATE, MiniStatusBarPolicy.cellularLevel(-105))
        assertEquals(SignalLevel.POOR, MiniStatusBarPolicy.cellularLevel(-115))
        assertEquals(SignalLevel.NONE, MiniStatusBarPolicy.cellularLevel(-140))
    }

    @Test
    fun `legacy asu maps levels and treats 99 as unknown`() {
        assertEquals(SignalLevel.EXCELLENT, MiniStatusBarPolicy.cellularLevelFromAsu(20))
        assertEquals(SignalLevel.GOOD, MiniStatusBarPolicy.cellularLevelFromAsu(10))
        assertEquals(SignalLevel.MODERATE, MiniStatusBarPolicy.cellularLevelFromAsu(6))
        assertEquals(SignalLevel.POOR, MiniStatusBarPolicy.cellularLevelFromAsu(2))
        assertEquals(SignalLevel.NONE, MiniStatusBarPolicy.cellularLevelFromAsu(0))
        assertEquals(SignalLevel.NONE, MiniStatusBarPolicy.cellularLevelFromAsu(99))
    }

    @Test
    fun `wifi wins over cellular when connected`() {
        val (level, isWifi) = MiniStatusBarPolicy.resolveSignal(
            wifiConnected = true,
            wifiRssiDbm = -50,
            cellularRsrpDbm = -130,
            cellularAsu = null
        )
        assertEquals(SignalLevel.EXCELLENT, level)
        assertTrue(isWifi)
    }

    @Test
    fun `cellular is used when wifi is off and prefers rsrp over asu`() {
        val (rsrpLevel, rsrpWifi) = MiniStatusBarPolicy.resolveSignal(false, -50, -90, 1)
        assertEquals(SignalLevel.GOOD, rsrpLevel)
        assertEquals(false, rsrpWifi)

        val (asuLevel, _) = MiniStatusBarPolicy.resolveSignal(false, -50, null, 20)
        assertEquals(SignalLevel.EXCELLENT, asuLevel)

        val (none, _) = MiniStatusBarPolicy.resolveSignal(false, -50, null, null)
        assertEquals(SignalLevel.NONE, none)
    }

    @Test
    fun `bar counts stay within the drawn range`() {
        SignalLevel.values().forEach { level ->
            assertTrue(level.bars in 0..MiniStatusBarPolicy.SIGNAL_LEVELS)
        }
    }

    // ------------------------------------------------------- battery

    @Test
    fun `battery tint follows charge state and level`() {
        assertEquals(0xFF30D158.toInt(), MiniStatusBarPolicy.batteryPresentation(80, true, false, true).tintArgb)
        assertEquals(0xFFFFD60A.toInt(), MiniStatusBarPolicy.batteryPresentation(80, false, true, true).tintArgb)
        assertEquals(0xFFFF453A.toInt(), MiniStatusBarPolicy.batteryPresentation(8, false, false, true).tintArgb)
        assertEquals(0xFFFF9F0A.toInt(), MiniStatusBarPolicy.batteryPresentation(18, false, false, true).tintArgb)
        assertEquals(0xFFFFFFFF.toInt(), MiniStatusBarPolicy.batteryPresentation(60, false, false, true).tintArgb)
    }

    @Test
    fun `charging hides the percentage text and low power does not`() {
        assertNull(MiniStatusBarPolicy.batteryPresentation(60, true, false, true).text)
        assertEquals("60", MiniStatusBarPolicy.batteryPresentation(60, false, true, true).text)
        assertNull(MiniStatusBarPolicy.batteryPresentation(60, false, false, false).text)
    }

    @Test
    fun `battery percentage is clamped and the fill fraction tracks it`() {
        val over = MiniStatusBarPolicy.batteryPresentation(150, false, false, true)
        assertEquals(100, over.percent)
        assertEquals(1.0f, over.fillFraction, 0.0001f)

        val under = MiniStatusBarPolicy.batteryPresentation(-20, false, false, true)
        assertEquals(0, under.percent)
        assertEquals(0.0f, under.fillFraction, 0.0001f)
    }

    // ------------------------------------------------------------ grid

    @Test
    fun `grid snapping keeps zero reachable for the cutout padding rule`() {
        // Doc 2 mandates *zero* internal padding at the camera cutout, so a
        // snapping helper that forced a non-zero minimum would break it.
        assertEquals(0f, MiniStatusBarPolicy.snapToGrid(0f), 0.0001f)
        assertEquals(0f, MiniStatusBarPolicy.snapToGrid(3f), 0.0001f)
        assertEquals(8f, MiniStatusBarPolicy.snapToGrid(5f), 0.0001f)
        assertEquals(8f, MiniStatusBarPolicy.snapToGrid(8f), 0.0001f)
        assertEquals(16f, MiniStatusBarPolicy.snapToGrid(12f), 0.0001f)
    }

    @Test
    fun `required width grows with each enabled element so narrow pills can refuse`() {
        val clockOnly = MiniStatusBarPolicy.requiredWidthDp(true, false, false, false)
        val everything = MiniStatusBarPolicy.requiredWidthDp(true, true, true, true)
        assertTrue(everything > clockOnly)
        assertEquals(0f, MiniStatusBarPolicy.requiredWidthDp(false, false, false, false), 0.0001f)
    }

    @Test
    fun `default shortcuts gate the accessibility-only triggers`() {
        val without = MiniStatusBarPolicy.defaultShortcuts(accessibilityConnected = false)
        val screenshot = without.first { it.launchId == MiniStatusBarPolicy.Triggers.SCREENSHOT }
        assertTrue(screenshot.requiresAccessibility)
        assertEquals(false, screenshot.isEnabled)

        val with = MiniStatusBarPolicy.defaultShortcuts(accessibilityConnected = true)
        assertTrue(with.first { it.launchId == MiniStatusBarPolicy.Triggers.SCREENSHOT }.isEnabled)
        assertTrue(with.first { it.launchId == MiniStatusBarPolicy.Triggers.SCREEN_LOCK }.isEnabled)
        // The plain app shortcuts never depend on accessibility.
        assertTrue(with.first { it.launchId == "app.camera" }.isEnabled)
        assertFalse(with.first { it.launchId == "app.camera" }.requiresAccessibility)
    }

}
