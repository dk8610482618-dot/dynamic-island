package com.dynamicisland.engine.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.dynamicisland.core.geometry.IslandPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists the user's island placement and sizing preferences.
 *
 * The offset is stored as a **delta from the automatic position**, never as an
 * absolute coordinate. That is what lets a placement survive a rotation, a
 * folding-screen state change, or a density change without stranding the island
 * somewhere meaningless.
 *
 * Sizing is stored as independent width and height scale multipliers so the
 * user can customize the pill's aspect ratio and footprint independently.
 *
 * Reads and writes go through [StateFlow], so the overlay observes changes live
 * while the user drags a slider.
 */
class IslandSettings internal constructor(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _offset = MutableStateFlow(readOffset())

    /** The live placement offset. Collected by the overlay host. */
    val offset: StateFlow<IslandPlacement.UserOffset> = _offset.asStateFlow()

    private val _widthScale = MutableStateFlow(readWidthScale())

    /** Width multiplier (0.50x to 1.40x). */
    val widthScale: StateFlow<Float> = _widthScale.asStateFlow()

    private val _heightScale = MutableStateFlow(readHeightScale())

    /** Height multiplier (0.50x to 1.40x). */
    val heightScale: StateFlow<Float> = _heightScale.asStateFlow()

    /** Legacy combined scale getter (for backward compatibility). */
    val scale: StateFlow<Float> get() = _widthScale

    /** True while the user is running a non-default placement or size. */
    val isCustomised: Boolean
        get() = !_offset.value.isDefault || _widthScale.value != 1f || _heightScale.value != 1f

    // Tracks whether a debounced persist is pending execution.
    // While pending, memory is strictly newer than SharedPreferences on disk,
    // so OnSharedPreferenceChangeListener MUST NOT overwrite memory.
    private val isPersistPending = AtomicBoolean(false)

    // Tracks last written values so preference change listener can ignore its own echoes.
    @Volatile
    private var lastPersistedOffset = _offset.value

    @Volatile
    private var lastPersistedWidthScale = _widthScale.value

    @Volatile
    private var lastPersistedHeightScale = _heightScale.value

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // If memory has an uncommitted debounced write, disk is stale; do not overwrite memory.
            if (isPersistPending.get()) return@OnSharedPreferenceChangeListener

            when (key) {
                KEY_OFFSET_X, KEY_OFFSET_Y -> {
                    val fresh = readOffset()
                    if (fresh != lastPersistedOffset) {
                        lastPersistedOffset = fresh
                        if (_offset.value != fresh) _offset.value = fresh
                    }
                }
                KEY_WIDTH_SCALE, KEY_SCALE -> {
                    val fresh = readWidthScale()
                    if (fresh != lastPersistedWidthScale) {
                        lastPersistedWidthScale = fresh
                        if (_widthScale.value != fresh) _widthScale.value = fresh
                    }
                }
                KEY_HEIGHT_SCALE -> {
                    val fresh = readHeightScale()
                    if (fresh != lastPersistedHeightScale) {
                        lastPersistedHeightScale = fresh
                        if (_heightScale.value != fresh) _heightScale.value = fresh
                    }
                }
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private val persistRunnable = Runnable {
        isPersistPending.set(false)
        persistToPrefs()
    }

    private fun schedulePersist() {
        isPersistPending.set(true)
        mainHandler.removeCallbacks(persistRunnable)
        mainHandler.postDelayed(persistRunnable, DEBOUNCE_DELAY_MS)
    }

    /** Flushes any pending setting writes to SharedPreferences immediately. */
    fun flush() {
        mainHandler.removeCallbacks(persistRunnable)
        isPersistPending.set(false)
        persistToPrefs()
    }

    private fun persistToPrefs() {
        val o = _offset.value
        val w = _widthScale.value
        val h = _heightScale.value

        lastPersistedOffset = o
        lastPersistedWidthScale = w
        lastPersistedHeightScale = h

        prefs.edit()
            .putFloat(KEY_OFFSET_X, o.offsetXDp)
            .putFloat(KEY_OFFSET_Y, o.offsetYDp)
            .putFloat(KEY_WIDTH_SCALE, w)
            .putFloat(KEY_HEIGHT_SCALE, h)
            .apply()
    }

    fun setOffset(offset: IslandPlacement.UserOffset) {
        val sanitised = IslandPlacement.UserOffset(
            offsetXDp = sanitise(offset.offsetXDp),
            offsetYDp = sanitise(offset.offsetYDp)
        )
        var changed = false
        _offset.update { current ->
            if (current != sanitised) {
                changed = true
                sanitised
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun setOffsetX(dp: Float) {
        val safeDp = sanitise(dp)
        var changed = false
        _offset.update { current ->
            if (current.offsetXDp != safeDp) {
                changed = true
                current.copy(offsetXDp = safeDp)
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun setOffsetY(dp: Float) {
        val safeDp = sanitise(dp)
        var changed = false
        _offset.update { current ->
            if (current.offsetYDp != safeDp) {
                changed = true
                current.copy(offsetYDp = safeDp)
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun setWidthScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (!clamped.isFinite()) return
        var changed = false
        _widthScale.update { current ->
            if (current != clamped) {
                changed = true
                clamped
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun setHeightScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (!clamped.isFinite()) return
        var changed = false
        _heightScale.update { current ->
            if (current != clamped) {
                changed = true
                clamped
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    fun setScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (!clamped.isFinite()) return
        var changed = false
        _widthScale.update { current ->
            if (current != clamped) {
                changed = true
                clamped
            } else {
                current
            }
        }
        _heightScale.update { current ->
            if (current != clamped) {
                changed = true
                clamped
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    /** Returns the island to its cutout-derived position and default size. */
    fun reset() {
        _offset.value = IslandPlacement.UserOffset()
        _widthScale.value = 1f
        _heightScale.value = 1f
        flush()
    }

    /** Nudges by a delta, for fine adjustment controls. */
    fun nudge(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        var changed = false
        _offset.update { current ->
            val newX = sanitise(current.offsetXDp + dx)
            val newY = sanitise(current.offsetYDp + dy)
            if (current.offsetXDp != newX || current.offsetYDp != newY) {
                changed = true
                IslandPlacement.UserOffset(newX, newY)
            } else {
                current
            }
        }
        if (changed) {
            schedulePersist()
        }
    }

    private fun readOffset() = IslandPlacement.UserOffset(
        offsetXDp = sanitise(prefs.getFloat(KEY_OFFSET_X, 0f)),
        offsetYDp = sanitise(prefs.getFloat(KEY_OFFSET_Y, 0f))
    )

    private fun readWidthScale(): Float =
        prefs.getFloat(KEY_WIDTH_SCALE, prefs.getFloat(KEY_SCALE, 1f)).let {
            if (it.isFinite()) it.coerceIn(MIN_SCALE, MAX_SCALE) else 1f
        }

    private fun readHeightScale(): Float =
        prefs.getFloat(KEY_HEIGHT_SCALE, prefs.getFloat(KEY_SCALE, 1f)).let {
            if (it.isFinite()) it.coerceIn(MIN_SCALE, MAX_SCALE) else 1f
        }

    private fun sanitise(value: Float): Float = when {
        !value.isFinite() -> 0f
        value > MAX_OFFSET_DP -> MAX_OFFSET_DP
        value < -MAX_OFFSET_DP -> -MAX_OFFSET_DP
        else -> value
    }

    companion object {
        const val DEBOUNCE_DELAY_MS = 150L

        private const val PREFS_NAME = "island_settings"
        private const val KEY_OFFSET_X = "placement_offset_x_dp"
        private const val KEY_OFFSET_Y = "placement_offset_y_dp"
        private const val KEY_SCALE = "island_scale"
        private const val KEY_WIDTH_SCALE = "island_width_scale"
        private const val KEY_HEIGHT_SCALE = "island_height_scale"

        private const val MAX_OFFSET_DP = 10_000f

        const val MIN_SCALE = 0.50f
        const val MAX_SCALE = 1.40f

        /**
         * Process-wide instance.
         *
         * It holds the application context, never an Activity, so a single instance
         * for the process lifetime is safe — and required: the host UI and the
         * overlay service must observe the same StateFlows.
         */
        @Volatile
        private var instance: IslandSettings? = null

        fun get(context: Context): IslandSettings =
            instance ?: synchronized(this) {
                instance ?: IslandSettings(context.applicationContext).also { instance = it }
            }
    }
}
