package com.arena.dynamicisland.island

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A single Live Activity that can occupy the Dynamic Island.
 *
 * Every activity declares the three facts the island needs in order to lay itself out:
 *
 *  - [compactWidth] — how wide the pill grows when this activity is the only one running.
 *  - [expandedHeight] — how tall the island becomes when this activity is opened.
 *  - [priority] — who wins when several activities run at once. The island shows at most two:
 *    the highest priority one gets the leading slot, the next one may join in the split layout.
 *
 * [transientMillis] marks activities that retire themselves (a Face ID confirmation, a
 * "connected" toast). `null` means the activity stays until something removes it.
 *
 * Activities are immutable values: the host re-posts an updated copy to animate new data
 * (playback progress, countdown ticks) without the island losing its expanded state.
 */
sealed class IslandActivity {

    abstract val id: String

    abstract val priority: Int

    abstract val compactWidth: Dp

    abstract val expandedHeight: Dp

    open val transientMillis: Long? = null

    // region Activities

    /** Music / podcast playback. Compact shows artwork + title, expanded adds scrubber and controls. */
    data class Music(
        val title: String = "Kaam 25",
        val artist: String = "Divine • Gully Gang",
        val isPlaying: Boolean = true,
        val elapsedSeconds: Int = 42,
        val durationSeconds: Int = 214,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 60
        override val compactWidth: Dp = 246.dp
        override val expandedHeight: Dp = 174.dp

        val progress: Float
            get() = if (durationSeconds <= 0) 0f else (elapsedSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)

        companion object {
            const val ID: String = "music"
        }
    }

    /** An in-progress or incoming phone call. Highest priority: nothing may displace a ringing call. */
    data class Call(
        val name: String = "Ananya Iyer",
        val isIncoming: Boolean = true,
        val isVideo: Boolean = false,
        val connectedSeconds: Int = 0,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 100
        override val compactWidth: Dp = 238.dp
        override val expandedHeight: Dp = 196.dp

        val initials: String
            get() = name.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")

        companion object {
            const val ID: String = "call"
        }
    }

    /** Countdown timer. The ring is the whole compact presentation. */
    data class Timer(
        val totalSeconds: Int = 600,
        val remainingSeconds: Int = 412,
        val isRunning: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 55
        override val compactWidth: Dp = 152.dp
        override val expandedHeight: Dp = 184.dp

        val progress: Float
            get() = if (totalSeconds <= 0) 0f else (remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)

        companion object {
            const val ID: String = "timer"
        }
    }

    /** Battery / charging. Deliberately the lowest priority: it yields to everything. */
    data class Charging(
        val levelPercent: Int = 24,
        val isFastCharging: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 20
        override val compactWidth: Dp = 138.dp
        override val expandedHeight: Dp = 140.dp

        companion object {
            const val ID: String = "charging"
        }
    }

    /** Turn-by-turn navigation. */
    data class Navigation(
        val maneuver: String = "Turn right",
        val road: String = "onto MG Road",
        val distance: String = "300 m",
        val eta: String = "12 min",
        val arrival: String = "6:42 PM",
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 80
        override val compactWidth: Dp = 216.dp
        override val expandedHeight: Dp = 184.dp

        companion object {
            const val ID: String = "navigation"
        }
    }

    /** Transient Face ID / unlock confirmation. Retires on its own after 1.8s. */
    data object Unlock : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 10
        override val compactWidth: Dp = 132.dp
        override val expandedHeight: Dp = 128.dp
        override val transientMillis: Long = 1_800L

        const val ID: String = "unlock"
    }

    // endregion
}
