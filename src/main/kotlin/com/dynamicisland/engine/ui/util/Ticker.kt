package com.dynamicisland.engine.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
