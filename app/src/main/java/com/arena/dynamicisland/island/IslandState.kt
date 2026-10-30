package com.arena.dynamicisland.island

import androidx.compose.runtime.Immutable

/**
 * Pure state for the island: which activities are live, and which one (if any) is opened.
 *
 * Kept free of Compose and Android so the whole interaction model is unit-testable, and so the
 * reducer can be driven from anywhere (a ViewModel, a service, a WebSocket, a demo script).
 *
 * Ordering is stable and total: priority descending, then [IslandActivity.id] as a tiebreaker.
 * That matters because the split layout picks its two occupants from the top of this list.
 */
@Immutable
data class IslandState(
    val activities: List<IslandActivity> = emptyList(),
    val expandedId: String? = null,
) {

    /** Activities in the order the island would prefer to show them. */
    val ordered: List<IslandActivity>
        get() = activities.sortedWith(
            compareByDescending<IslandActivity> { it.priority }.thenBy { it.id },
        )

    val primary: IslandActivity? get() = ordered.firstOrNull()

    val secondary: IslandActivity? get() = ordered.getOrNull(1)

    /** Derives what the screen shows. Never stored, so it can never drift from the activities. */
    fun presentation(): IslandPresentation {
        val orderedNow = ordered

        orderedNow.firstOrNull { it.id == expandedId }?.let { return IslandPresentation.Expanded(it) }

        val first = orderedNow.firstOrNull() ?: return IslandPresentation.Collapsed
        val second = orderedNow.getOrNull(1)

        // Only peer-level activities share the pill; a charging bolt never shoulders into a call.
        return if (second != null && second.priority >= SPLIT_MIN_PRIORITY) {
            IslandPresentation.Split(leading = first, trailing = second)
        } else {
            IslandPresentation.Compact(first)
        }
    }

    /**
     * Adds an activity, or replaces one with the same [IslandActivity.id].
     *
     * Replacement is how live data flows in: playback progress, countdown ticks, a call that
     * stops ringing. It must never reset [expandedId], otherwise the card would slam shut every
     * second while a timer runs.
     */
    fun post(activity: IslandActivity): IslandState {
        val exists = activities.any { it.id == activity.id }
        val next = if (exists) {
            activities.map { if (it.id == activity.id) activity else it }
        } else {
            activities + activity
        }
        return copy(activities = next)
    }

    /** Removes an activity, and closes the card if it was the one being shown. */
    fun dismiss(id: String): IslandState = copy(
        activities = activities.filterNot { it.id == id },
        expandedId = expandedId.takeIf { it != id },
    )

    /** Opens the given activity, or the highest priority one when [id] is null. */
    fun expand(id: String? = null): IslandState {
        val target = id ?: primary?.id ?: return this
        if (activities.none { it.id == target }) return this
        return copy(expandedId = target)
    }

    /** Collapses back to the pill (or the cutout, when nothing is running). */
    fun collapse(): IslandState = copy(expandedId = null)

    /** Tap behaviour: collapsed/compact opens, expanded closes. */
    fun toggle(presentation: IslandPresentation): IslandState = when (presentation) {
        IslandPresentation.Collapsed -> expand()
        is IslandPresentation.Compact -> expand(presentation.activity.id)
        is IslandPresentation.Expanded -> collapse()
        is IslandPresentation.Split -> expand(presentation.leading.id)
    }

    companion object {
        /** Activities at or above this priority are allowed to share the pill with a peer. */
        const val SPLIT_MIN_PRIORITY: Int = 50
    }
}
