package com.dynamicisland.core.system

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Gap **G4** — the immersive mini status bar.
 *
 * Doc 2: "for users operating their devices in full-screen immersive mode with
 * the native Android status bar hidden, an optional idle state should render the
 * live clock and cellular/Wi-Fi signal bars directly on the floating pill as a
 * miniature status bar, alongside customizable quick-control app shortcuts and
 * screenshot/screen-lock triggers."
 *
 * The imported base had the setting and a renderer, but the renderer had a real
 * defect: it built the clock string with
 * `remember { SimpleDateFormat("HH:mm").format(Date()) }`, so the time was
 * captured on first composition and **never updated** for the lifetime of the
 * overlay. The trailing slot drew a constant green battery glyph regardless of
 * charge level. There were no signal bars, shortcuts or triggers.
 *
 * The clock defect is fixed here at the policy level rather than by polling:
 * [millisUntilNextClockTick] tells the engine exactly how long to wait before
 * the displayed minute changes, so it can schedule one invalidation per minute
 * boundary instead of recomposing every second. That satisfies doc 1's
 * performance rule — "no polling loop… render local elapsed time from a start
 * timestamp" and "zero recurring wakeups solely for static island content".
 *
 * Pure JVM. No `android.*`, no `java.util.Calendar`: timezone handling takes an
 * explicit offset so the arithmetic is testable and deterministic.
 */
object MiniStatusBarPolicy {

    const val MINUTE_MS: Long = 60_000L

    /** Bars drawn for a signal, matching Android's 0–4 level convention. */
    const val SIGNAL_LEVELS: Int = 4

    /**
     * A quick-control shortcut rendered in the expanded mini bar.
     *
     * @param launchId stable id used by the engine to resolve the target intent.
     * @param requiresAccessibility screenshot and lock triggers only work when
     *   the optional accessibility service is connected; the engine greys these
     *   out rather than silently doing nothing.
     */
    data class QuickShortcut(
        val launchId: String,
        val label: String,
        val symbol: String,
        val requiresAccessibility: Boolean = false,
        val isEnabled: Boolean = true
    )

    /** Triggers doc 2 names explicitly. */
    object Triggers {
        const val SCREENSHOT = "trigger.screenshot"
        const val SCREEN_LOCK = "trigger.screen_lock"
    }

    /** Default shortcut set; the user can reorder or disable entries. */
    fun defaultShortcuts(accessibilityConnected: Boolean): List<QuickShortcut> = listOf(
        QuickShortcut("app.camera", "Camera", "camera"),
        QuickShortcut("app.flashlight", "Torch", "bolt"),
        QuickShortcut(
            Triggers.SCREENSHOT,
            "Screenshot",
            "crop",
            requiresAccessibility = true,
            isEnabled = accessibilityConnected
        ),
        QuickShortcut(
            Triggers.SCREEN_LOCK,
            "Lock",
            "lock",
            requiresAccessibility = true,
            isEnabled = accessibilityConnected
        )
    )

    /** Coarse signal strength, 0 = no service, 4 = full. */
    enum class SignalLevel(val bars: Int) {
        NONE(0),
        POOR(1),
        MODERATE(2),
        GOOD(3),
        EXCELLENT(4)
    }

    /**
     * Milliseconds from [nowMillis] until the displayed `HH:mm` string changes.
     *
     * The clock shows whole minutes, so the engine can sleep for exactly this
     * long. [timezoneOffsetMinutes] is the offset the formatter will use; the
     * boundary depends only on the minute within the hour, so the offset affects
     * half-hour and 45-minute zones correctly without any calendar arithmetic.
     */
    fun millisUntilNextClockTick(nowMillis: Long, timezoneOffsetMinutes: Int = 0): Long {
        if (nowMillis <= 0L) return MINUTE_MS
        val shifted = nowMillis + timezoneOffsetMinutes * MINUTE_MS
        val withinMinute = floorMod(shifted, MINUTE_MS)
        val remaining = MINUTE_MS - withinMinute
        // Never return 0: a zero delay makes the caller reschedule in a tight
        // loop. 1 ms is enough for the boundary to have passed.
        return if (remaining <= 0L) 1L else remaining
    }

    /**
     * Formats `HH:mm` for the pill without `SimpleDateFormat`, so the same
     * code path is used by the JVM tests and the overlay.
     */
    fun formatClock(nowMillis: Long, timezoneOffsetMinutes: Int = 0, is24Hour: Boolean = true): String {
        val shifted = nowMillis + timezoneOffsetMinutes * MINUTE_MS
        val minutesOfDay = floorMod(shifted / MINUTE_MS, 24L * 60L).toInt()
        val hour24 = minutesOfDay / 60
        val minute = minutesOfDay % 60
        if (is24Hour) return "%02d:%02d".format(hour24, minute)
        val hour12 = when (hour24 % 12) { 0 -> 12; else -> hour24 % 12 }
        val suffix = if (hour24 < 12) "AM" else "PM"
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    /**
     * Maps Wi-Fi RSSI in dBm to a 0–4 level using Android's documented
     * thresholds (`WifiManager` uses -100/-88/-66/-55 dBm boundaries for five
     * levels, i.e. four bars above "no signal").
     */
    fun wifiLevel(rssiDbm: Int): SignalLevel = when {
        rssiDbm >= -55 -> SignalLevel.EXCELLENT
        rssiDbm >= -66 -> SignalLevel.GOOD
        rssiDbm >= -88 -> SignalLevel.MODERATE
        rssiDbm >= -100 -> SignalLevel.POOR
        else -> SignalLevel.NONE
    }

    /**
     * Maps cellular RSRP in dBm (LTE/NR reference signal power) to a level.
     * These are the boundaries Android's own `SignalStrength.getLevel` uses for
     * LTE: -128 (none), -118, -108, -98, -88 and above.
     */
    fun cellularLevel(rsrpDbm: Int): SignalLevel = when {
        rsrpDbm >= -88 -> SignalLevel.EXCELLENT
        rsrpDbm >= -98 -> SignalLevel.GOOD
        rsrpDbm >= -108 -> SignalLevel.MODERATE
        rsrpDbm >= -118 -> SignalLevel.POOR
        else -> SignalLevel.NONE
    }

    /**
     * Legacy ASU → level mapping for 2G/3G where RSRP is unavailable.
     * ASU 0–31 for GSM; 99 means unknown.
     */
    fun cellularLevelFromAsu(asu: Int): SignalLevel = when {
        asu == 99 -> SignalLevel.NONE
        asu >= 12 -> SignalLevel.EXCELLENT
        asu >= 8 -> SignalLevel.GOOD
        asu >= 5 -> SignalLevel.MODERATE
        asu >= 1 -> SignalLevel.POOR
        else -> SignalLevel.NONE
    }

    /**
     * Chooses which signal to show when both radios are live. Wi-Fi wins when
     * connected, matching the system status bar.
     */
    fun resolveSignal(
        wifiConnected: Boolean,
        wifiRssiDbm: Int,
        cellularRsrpDbm: Int?,
        cellularAsu: Int?
    ): Pair<SignalLevel, Boolean> {
        if (wifiConnected) return wifiLevel(wifiRssiDbm) to true
        val cellular = when {
            cellularRsrpDbm != null -> cellularLevel(cellularRsrpDbm)
            cellularAsu != null -> cellularLevelFromAsu(cellularAsu)
            else -> SignalLevel.NONE
        }
        return cellular to false
    }

    /**
     * Battery rendering for a 22 dp slot. Percentages are only shown when they
     * fit; below that the glyph alone is used, because doc 1 warns that compact
     * text must truncate before geometry becomes unstable.
     *
     * @return pair of (text or null, tint as 0xAARRGGBB).
     */
    fun batteryPresentation(
        percent: Int,
        isCharging: Boolean,
        isPowerSaveMode: Boolean,
        showPercentText: Boolean
    ): BatteryPresentation {
        val clamped = percent.coerceIn(0, 100)
        val tint = when {
            isCharging -> 0xFF30D158.toInt()          // iOS green while charging
            isPowerSaveMode -> 0xFFFFD60A.toInt()       // amber in low power mode
            clamped <= 10 -> 0xFFFF453A.toInt()         // red at 10% and below
            clamped <= 20 -> 0xFFFF9F0A.toInt()         // amber at 20% and below
            else -> 0xFFFFFFFF.toInt()
        }
        return BatteryPresentation(
            percent = clamped,
            text = if (showPercentText && !isCharging) "$clamped" else null,
            tintArgb = tint,
            fillFraction = clamped / 100f,
            isCharging = isCharging
        )
    }

    data class BatteryPresentation(
        val percent: Int,
        val text: String?,
        val tintArgb: Int,
        val fillFraction: Float,
        val isCharging: Boolean
    )

    /**
     * Snaps a dimension to doc 2's required 8-point spatial grid.
     *
     * Zero is a legal grid value and must stay reachable: doc 2 separately
     * mandates *zero* internal padding between the compact slots and the physical
     * camera cutout, so a snapping helper that forced a non-zero minimum would
     * break the very margin rule it exists to serve. Callers pass 0 where zero
     * padding is required.
     */
    fun snapToGrid(valueDp: Float, gridDp: Float = 8f): Float {
        if (gridDp <= 0f) return valueDp
        return (valueDp / gridDp).roundToInt() * gridDp
    }

    /**
     * Total width the mini bar needs, so the engine can refuse to render it when
     * the pill is too narrow (a landscape width-limited island has no room for a
     * clock *and* signal bars).
     */
    fun requiredWidthDp(
        showClock: Boolean,
        showSignal: Boolean,
        showBatteryPercent: Boolean,
        showBatteryGlyph: Boolean
    ): Float {
        var width = 0f
        if (showClock) width += 30f
        if (showSignal) width += 16f
        if (showBatteryGlyph) width += 14f
        if (showBatteryPercent) width += 16f
        // Inter-item gaps on the 8 dp grid, halved because the pill is dense.
        val items = listOf(showClock, showSignal, showBatteryGlyph, showBatteryPercent).count { it }
        width += (items - 1).coerceAtLeast(0) * 4f
        return ceil(width)
    }

    private fun floorMod(value: Long, modulus: Long): Long {
        val result = value % modulus
        return if (result < 0L) result + modulus else result
    }
}
