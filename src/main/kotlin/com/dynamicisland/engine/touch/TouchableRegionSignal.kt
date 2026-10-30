package com.dynamicisland.engine.touch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cross-layer signal asking the overlay to recompute and republish its input
 * region.
 *
 * The overlay window spans the whole screen and carves an input "hole" for the
 * pill via `setTouchableRegion`. The geometry for that hole is computed in the
 * Compose layer, but the events that invalidate it — a window rebind onto a
 * different `WindowManager`, most of all — happen in the service layer, which
 * cannot reach the composition directly.
 *
 * The view deliberately keeps its composition alive across a rebind (see
 * `DisposeOnLifecycleDestroyed`), so a rebind produces no recomposition and no
 * new layout callback. Without an explicit nudge the view's cached rects still
 * match the (unchanged) geometry, its publish is suppressed as redundant, and
 * the freshly added window is left with no region at all — at which point a
 * fullscreen overlay swallows every touch on the device.
 *
 * Lives in this package rather than on the service so the UI layer does not
 * have to depend on the service to hear about it.
 */
object TouchableRegionSignal {

    private val _generation = MutableStateFlow(0L)

    /** Monotonic counter; every change requests one forced republish. */
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /** Current generation value, useful for testing and diagnostics. */
    val currentGeneration: Long get() = _generation.value

    /**
     * Forces the overlay to republish its touchable region on the next
     * composition, even if the measured geometry is unchanged.
     */
    fun requestRepublish() {
        _generation.update { current ->
            if (current == Long.MAX_VALUE) 0L else current + 1L
        }
    }

    /** Resets counter for testing isolation. */
    internal fun resetForTesting() {
        _generation.value = 0L
    }
}
