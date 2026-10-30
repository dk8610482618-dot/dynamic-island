package com.arena.dynamicisland.island

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State holder the UI (or a service) talks to.
 *
 * Every mutation goes through [IslandState], so the presentation is always a pure function of the
 * live activities. Any caller can own one of these: a composable via [rememberIslandController],
 * a ViewModel, a foreground service, an MCP tool bridge.
 */
@Stable
class IslandController(initial: IslandState = IslandState()) {

    var state: IslandState by mutableStateOf(initial)
        private set

    val presentation: IslandPresentation get() = state.presentation()

    /** Posts an activity, or refreshes one that is already live. */
    fun post(activity: IslandActivity) {
        state = state.post(activity)
    }

    /** Convenience for pushing new data into an activity that is already on screen. */
    fun update(activity: IslandActivity) = post(activity)

    fun dismiss(id: String) {
        state = state.dismiss(id)
    }

    fun dismissAll() {
        state = IslandState()
    }

    fun expand(id: String? = null) {
        state = state.expand(id)
    }

    fun collapse() {
        state = state.collapse()
    }

    fun toggle() {
        state = state.toggle(state.presentation())
    }

    fun isActive(id: String): Boolean = state.activities.any { it.id == id }

    fun activity(id: String): IslandActivity? = state.activities.firstOrNull { it.id == id }
}
