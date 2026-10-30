package com.dynamicisland.engine.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dynamicisland.core.system.MiniStatusBarPolicy
import kotlinx.coroutines.delay

/**
 * Re-emits on a fixed cadence while [active].
 *
 * Returns a monotonically increasing tick counter intended to be used as a `remember` key
 * next to a time-dependent source, e.g. `remember(snapshot, tick) { snapshot.positionAt(now) }`.
 * Timers in the island previously showed a hard-coded "02:45" forever because nothing
 * invalidated the composition.
 */
@Composable
fun rememberElapsedTicker(active: Boolean, intervalMillis: Long = 500L): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active, intervalMillis) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            tick++
        }
    }
    return tick
}

/**
 * Whole seconds elapsed since the epoch, aligned to second boundaries while [active].
 */
@Composable
fun rememberCurrentSecond(active: Boolean = true): Long {
    var second by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            second = now / 1000L
            delay(1000L - (now % 1000L))
        }
    }
    return second
}

/**
 * Wall-clock millis, re-emitted exactly when the displayed **minute** changes.
 *
 * Gap G4. The mini status bar used to build its clock string with
 * `remember { SimpleDateFormat("HH:mm").format(Date()) }`, which captured the
 * time on first composition and then showed that same frozen minute for the
 * entire lifetime of the overlay.
 *
 * The fix is not a faster poll. Doc 1 requires "zero recurring wakeups solely for
 * static island content", so the delay comes from
 * [com.dynamicisland.core.system.MiniStatusBarPolicy.millisUntilNextClockTick],
 * which computes the exact distance to the next minute boundary in the given
 * timezone. One wakeup per displayed minute, aligned to the boundary so the
 * cadence cannot drift.
 *
 * @param timezoneOffsetMinutes offset of the zone the clock is formatted in, so
 *   half-hour and 45-minute zones land on their own boundaries.
 */
@Composable
fun rememberCurrentMinute(active: Boolean = true, timezoneOffsetMinutes: Int = 0): Long {
    var millis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, timezoneOffsetMinutes) {
        if (!active) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            millis = now
            delay(MiniStatusBarPolicy.millisUntilNextClockTick(now, timezoneOffsetMinutes))
        }
    }
    return millis
}

/**
 * The formatted `HH:mm` string for the mini status bar, recomposed only when the
 * minute rolls over. Formatting goes through [MiniStatusBarPolicy] rather than
 * `SimpleDateFormat` so the JVM unit tests exercise the same code path the
 * overlay draws.
 *
 * The zone offset is re-read on every recomposition (once per minute) rather than
 * remembered, so a timezone change or a DST transition is picked up without a
 * process restart. `getOffset(instant)` resolves DST for that instant, which
 * `getRawOffset()` alone would not.
 */
@Composable
fun rememberMiniBarClock(active: Boolean = true, is24Hour: Boolean = true): String {
    // Seed the ticker with the current offset so the first boundary is correct.
    val seedOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) /
        MiniStatusBarPolicy.MINUTE_MS.toInt()
    val now = rememberCurrentMinute(active, seedOffset)
    return remember(now, is24Hour) {
        val offsetMinutes = java.util.TimeZone.getDefault().getOffset(now) /
            MiniStatusBarPolicy.MINUTE_MS.toInt()
        MiniStatusBarPolicy.formatClock(now, offsetMinutes, is24Hour)
    }
}

