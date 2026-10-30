package com.arena.dynamicisland.island

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arena.dynamicisland.island.content.IslandActivityContent
import kotlinx.coroutines.delay

/** Remembers an [IslandController] across recompositions. */
@Composable
fun rememberIslandController(): IslandController = remember { IslandController() }

/**
 * Wires an [IslandController] to the presentation and to the transient-activity lifecycle.
 *
 * Transient activities (Face ID, "connected" confirmations) remove themselves here rather than in
 * the reducer, so the reducer stays a pure function of its inputs.
 */
@Composable
fun IslandHost(
    controller: IslandController,
    modifier: Modifier = Modifier,
    activityContent: @Composable (activity: IslandActivity, expanded: Boolean) -> Unit = { activity, expanded ->
        IslandActivityContent(activity = activity, expanded = expanded)
    },
) {
    val state = controller.state
    val presentation = state.presentation()

    val transient = state.activities.firstOrNull { it.transientMillis != null }
    LaunchedEffect(transient?.id) {
        val activity = transient ?: return@LaunchedEffect
        val ttl = activity.transientMillis ?: return@LaunchedEffect
        delay(ttl)
        controller.dismiss(activity.id)
    }

    DynamicIsland(
        presentation = presentation,
        modifier = modifier,
        activityContent = activityContent,
        onTap = { controller.toggle() },
        onSwipeDown = { controller.collapse() },
        onSwipeUp = { controller.expand() },
    )
}
