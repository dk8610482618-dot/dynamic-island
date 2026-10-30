package com.arena.dynamicisland.island

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hardware privacy sensors that illuminate inside the sensor bridge when idle,
 * or detach laterally (+10dp right) when a Live Activity occupies the island.
 */
enum class PrivacySensor(val label: String, val dotColor: Color) {
    Camera("Camera Active", Color(0xFF34C759)),
    Microphone("Microphone Active", Color(0xFFFF9500)),
    Location("Location Active", Color(0xFF0A84FF)),
}

/**
 * Every live activity or transient system alert that can occupy the Dynamic Island.
 * Ranked by priority descending; deterministic tie-break by [id].
 */
@Immutable
sealed class IslandActivity {
    abstract val id: String
    abstract val priority: Int
    abstract val compactWidth: Dp
    abstract val expandedHeight: Dp
    abstract val deepLinkUri: String
    open val transientMillis: Long? = null

    /** 1. Cellular / VoIP Call — highest priority (100). */
    data class Call(
        val name: String = "Ananya Iyer",
        val isIncoming: Boolean = false,
        val isVideo: Boolean = false,
        val isMuted: Boolean = false,
        val isSpeaker: Boolean = true,
        val connectedSeconds: Int = 84,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 100
        override val compactWidth: Dp = 238.dp
        override val expandedHeight: Dp = 186.dp
        override val deepLinkUri: String = "tel://active-call/ananya-iyer"

        val initials: String
            get() = name.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")

        companion object { const val ID = "call" }
    }

    /** 2. Transient Face ID / TrueDepth Biometric Authentication (priority 95). */
    data class FaceIdUnlock(
        val isAuthenticated: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 95
        override val compactWidth: Dp = 156.dp
        override val expandedHeight: Dp = 148.dp
        override val deepLinkUri: String = "biometrics://faceid-verify"
        override val transientMillis: Long = 1_800L
        companion object { const val ID = "face_id" }
    }

    /** 3. Turn-by-Turn Navigation (priority 85). */
    data class Navigation(
        val maneuver: String = "Turn right",
        val road: String = "onto MG Road",
        val distance: String = "300 m",
        val eta: String = "12 min",
        val arrival: String = "6:42 PM",
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 85
        override val compactWidth: Dp = 224.dp
        override val expandedHeight: Dp = 178.dp
        override val deepLinkUri: String = "maps://navigation/mg-road"
        companion object { const val ID = "navigation" }
    }

    /** 4. AirDrop Proximity File Transfer (priority 82). */
    data class AirDrop(
        val sender: String = "MacBook Pro",
        val fileSummary: String = "4 ProRAW Photos",
        val progressFraction: Float = 0.72f,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 82
        override val compactWidth: Dp = 214.dp
        override val expandedHeight: Dp = 168.dp
        override val deepLinkUri: String = "sharing://airdrop/transfer"
        val progress: Float get() = progressFraction.coerceIn(0f, 1f)
        companion object { const val ID = "airdrop" }
    }

    /** 5. Screen Recording & Broadcast (priority 78). */
    data class ScreenRecord(
        val elapsedSeconds: Int = 37,
        val includeMic: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 78
        override val compactWidth: Dp = 196.dp
        override val expandedHeight: Dp = 162.dp
        override val deepLinkUri: String = "system://screen-recorder"
        companion object { const val ID = "screen_record" }
    }

    /** 6. Voice Memos Live Audio Recording (priority 72). */
    data class VoiceMemo(
        val title: String = "Studio Session #14",
        val elapsedSeconds: Int = 65,
        val isRecording: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 72
        override val compactWidth: Dp = 212.dp
        override val expandedHeight: Dp = 172.dp
        override val deepLinkUri: String = "voicememos://recording/14"
        companion object { const val ID = "voice_memo" }
    }

    /** 7. Now Playing Media Playback with Dynamic Artwork Swatch (priority 65). */
    data class Music(
        val title: String = "Midnight City (Remaster)",
        val artist: String = "M83 • Hurry Up, We're Dreaming",
        val isPlaying: Boolean = true,
        val elapsedSeconds: Int = 74,
        val durationSeconds: Int = 243,
        val dominantAccentHex: Long = 0xFFBF5AF2,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 65
        override val compactWidth: Dp = 244.dp
        override val expandedHeight: Dp = 188.dp
        override val deepLinkUri: String = "music://now-playing"
        val progress: Float
            get() = if (durationSeconds <= 0) 0f else (elapsedSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)
        companion object { const val ID = "music" }
    }

    /** 8. Countdown Timer with Tabular Monospace Figures (priority 60). */
    data class Timer(
        val label: String = "AeroPress Brew",
        val totalSeconds: Int = 180,
        val remainingSeconds: Int = 138,
        val isRunning: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 60
        override val compactWidth: Dp = 186.dp
        override val expandedHeight: Dp = 170.dp
        override val deepLinkUri: String = "clock://timer"
        val progress: Float
            get() = if (totalSeconds <= 0) 0f else (remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
        companion object { const val ID = "timer" }
    }

    /** 9. Live Sports Scoreboard ActivityKit (priority 56). */
    data class Sports(
        val homeTeam: String = "IND",
        val awayTeam: String = "AUS",
        val homeScore: String = "178/4",
        val awayScore: String = "193/6",
        val summary: String = "18.4 OV • Need 16 off 8",
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 56
        override val compactWidth: Dp = 232.dp
        override val expandedHeight: Dp = 174.dp
        override val deepLinkUri: String = "sports://match/ind-vs-aus"
        companion object { const val ID = "sports" }
    }

    /** 10. Live Flight Boarding & Progress Tracker (priority 52). */
    data class Flight(
        val flightCode: String = "AI 173",
        val origin: String = "DEL",
        val destination: String = "SFO",
        val gate: String = "B22",
        val progressFraction: Float = 0.44f,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 52
        override val compactWidth: Dp = 228.dp
        override val expandedHeight: Dp = 176.dp
        override val deepLinkUri: String = "flight://ai173/status"
        val progress: Float get() = progressFraction.coerceIn(0f, 1f)
        companion object { const val ID = "flight" }
    }

    /** 11. AirPods Pro Spatial Audio & Battery Orbital (priority 42, transient 4.2s). */
    data class AirPods(
        val deviceName: String = "Darshan's AirPods Pro",
        val mode: String = "Adaptive Audio",
        val budsPercent: Int = 94,
        val casePercent: Int = 82,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 42
        override val compactWidth: Dp = 210.dp
        override val expandedHeight: Dp = 164.dp
        override val deepLinkUri: String = "bluetooth://airpods-pro"
        override val transientMillis: Long = 4_200L
        companion object { const val ID = "airpods" }
    }

    /** 12. MagSafe / USB-C PD Fast Charging Alert (priority 30, transient 3.2s). */
    data class Charging(
        val levelPercent: Int = 64,
        val wattage: Int = 27,
        val isFastCharging: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 30
        override val compactWidth: Dp = 168.dp
        override val expandedHeight: Dp = 144.dp
        override val deepLinkUri: String = "settings://battery"
        override val transientMillis: Long = 3_200L
        companion object { const val ID = "charging" }
    }

    /** 13. Hardware Silent / Ring Switch Alert (priority 25, transient 2.4s). */
    data class SilentMode(
        val isSilent: Boolean = true,
    ) : IslandActivity() {
        override val id: String = ID
        override val priority: Int = 25
        override val compactWidth: Dp = 184.dp
        override val expandedHeight: Dp = 136.dp
        override val deepLinkUri: String = "settings://sound-haptics"
        override val transientMillis: Long = 2_400L
        companion object { const val ID = "silent_mode" }
    }
}
