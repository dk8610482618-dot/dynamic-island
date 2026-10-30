package com.arena.dynamicisland.island

import androidx.compose.runtime.Immutable

enum class PrivacyPlacement {
    Hidden,
    InsideSensorBridge,
    DetachedRight,
}

@Immutable
data class IslandState(
    val activities: List<IslandActivity> = emptyList(),
    val expandedId: String? = null,
    val isStowed: Boolean = false,
    val privacySensors: Set<PrivacySensor> = emptySet(),
    val lastDeepLinkUri: String? = null,
    val isLandscape: Boolean = false,
) {
    /** Total deterministic ordering: priority descending, id ascending. */
    val ordered: List<IslandActivity>
        get() = activities.sortedWith(
            compareByDescending<IslandActivity> { it.priority }.thenBy { it.id },
        )

    val primary: IslandActivity? get() = ordered.firstOrNull()
    val secondary: IslandActivity? get() = ordered.getOrNull(1)

    /**
     * Derives where the green/orange/blue privacy indicators sit:
     * - InsideSensorBridge when the island is Collapsed or Stowed into the cutout.
     * - DetachedRight (+10dp to the right of the active capsule) when occupied.
     */
    val privacyPlacement: PrivacyPlacement
        get() {
            if (privacySensors.isEmpty()) return PrivacyPlacement.Hidden
            return when (presentation()) {
                IslandPresentation.Collapsed, is IslandPresentation.Stowed ->
                    PrivacyPlacement.InsideSensorBridge
                else -> PrivacyPlacement.DetachedRight
            }
        }

    /** Pure function deriving the current IslandPresentation shape. Never stored. */
    fun presentation(): IslandPresentation {
        val orderedNow = ordered
        if (!isLandscape) {
            orderedNow.firstOrNull { it.id == expandedId }?.let {
                return IslandPresentation.Expanded(it)
            }
        }
        val first = orderedNow.firstOrNull() ?: return IslandPresentation.Collapsed
        if (isStowed) {
            return IslandPresentation.Stowed(primary = first, backgroundCount = orderedNow.size)
        }
        val second = orderedNow.getOrNull(1)
        return if (second != null && second.priority >= SPLIT_MIN_PRIORITY) {
            IslandPresentation.SplitDroplet(leading = first, droplet = second)
        } else {
            IslandPresentation.Compact(first)
        }
    }

    /** Posts a new activity (unstowing the island) or updates an existing activity in place. */
    fun post(activity: IslandActivity): IslandState {
        val exists = activities.any { it.id == activity.id }
        val next = if (exists) {
            activities.map { if (it.id == activity.id) activity else it }
        } else {
            activities + activity
        }
        return copy(
            activities = next,
            isStowed = if (exists) isStowed else false,
        )
    }

    fun dismiss(id: String): IslandState {
        val remaining = activities.filterNot { it.id == id }
        return copy(
            activities = remaining,
            expandedId = expandedId.takeIf { it != id },
            isStowed = if (remaining.isEmpty()) false else isStowed,
        )
    }

    /** Long-press (>=200ms) expands the card (unless locked in landscape). */
    fun expand(id: String? = null): IslandState {
        if (isLandscape) return this
        val target = id ?: primary?.id ?: return this
        if (activities.none { it.id == target }) return this
        return copy(expandedId = target, isStowed = false)
    }

    fun collapse(): IslandState = copy(expandedId = null)

    /**
     * Single-tap (<200ms) per Apple HIG:
     * - Restores the pill if currently stowed in the cutout.
     * - Fires the target activity's deepLinkUri to open the owning app.
     */
    fun onSingleTap(targetId: String? = null): IslandState {
        if (isStowed && activities.isNotEmpty()) {
            return copy(isStowed = false)
        }
        val target = (targetId?.let { id -> ordered.firstOrNull { it.id == id } } ?: primary)
            ?: return this
        return copy(lastDeepLinkUri = target.deepLinkUri)
    }

    /**
     * Horizontal swipe inward / upward flick:
     * - Collapses Expanded card to Compact/Split pill.
     * - Stows Compact/Split pill into the physical cutout while keeping activities alive.
     * - Restores Stowed cutout back to visible pill.
     */
    fun onSwipeStowOrRestore(): IslandState {
        if (expandedId != null) return collapse()
        if (activities.isEmpty()) return this
        return copy(isStowed = !isStowed)
    }

    fun togglePrivacySensor(sensor: PrivacySensor): IslandState {
        val next = if (sensor in privacySensors) privacySensors - sensor else privacySensors + sensor
        return copy(privacySensors = next)
    }

    companion object {
        const val SPLIT_MIN_PRIORITY: Int = 50
    }
}
