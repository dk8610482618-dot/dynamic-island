package com.dynamicisland.engine.core.model

import android.app.PendingIntent
import android.graphics.Bitmap
import android.net.Uri
import com.dynamicisland.core.activity.ActivityLifecyclePolicy
import com.dynamicisland.core.geometry.ExpandedRegionLayoutSolver

/**
 * Priority rank replicating iOS Jindo & ActivityKit system rules.
 * Lower integer value = higher priority.
 *
 * Rule Hierarchy:
 * Emergency/Call (100) > Live Timer/Stopwatch (200) > Navigation (300) > Media Playback (400) >
 * Live Journey / Delivery (450) > File Download (480) > System/Transient Alert (500) > Low Priority (600)
 */
enum class PriorityRank(val order: Int) {
    EMERGENCY_OR_CALL(100),
    LIVE_TIMER_STOPWATCH(200),
    NAVIGATION_TURN_BY_TURN(300),
    MEDIA_PLAYBACK(400),
    LIVE_JOURNEY_DELIVERY(450),
    FILE_DOWNLOAD_TRANSFER(480),
    TRANSIENT_SYSTEM_ALERT(500),
    LOW_PRIORITY_NOTIFICATION(600)
}

/**
 * Kind of system activity running in the Dynamic Island.
 */
enum class ActivityKind {
    EMERGENCY_CALL,
    RINGING_CALL,
    LIVE_CALL,
    LIVE_TIMER,
    LIVE_STOPWATCH,
    NAVIGATION,
    MEDIA_SESSION,
    LIVE_JOURNEY,
    FILE_DOWNLOAD,
    BATTERY_CHARGING,
    RINGER_DND_STATUS,
    ACCESSORY_BATTERY,
    HOTSPOT_STATUS,
    TRANSIENT_NOTIFICATION
}

/**
 * Universal Action executable from the Dynamic Island.
 */
data class IslandAction(
    val id: String,
    val title: String,
    val iconUri: Uri? = null,
    val iconResId: Int? = null,
    val isDestructive: Boolean = false,
    val pendingIntent: PendingIntent? = null,
    val actionCallback: (() -> Unit)? = null,
    val semantics: ActionSemantics = ActionSemantics.UNKNOWN
)

/**
 * Monochrome vector symbols (SF Symbols equivalents) used by the island slots.
 */
enum class IslandSymbol {
    MUSIC_NOTE,
    BELL,
    BELL_SLASH,
    BOLT,
    HEADSET,
    WIFI,
    PHONE,
    PHONE_END,
    TIMER,
    NAVIGATION,
    DELIVERY,
    DOWNLOAD,
    MIC,
    MIC_OFF,
    SPEAKER,
    AIRPLAY,
    BATTERY,
    CLOSE,
    PLAY,
    PAUSE
}

/**
 * Snapshot of the currently tracked media session, published for the overlay UI.
 */
data class MediaPlaybackSnapshot(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val lastUpdateElapsedRealtime: Long = 0L,
    val supportedActions: Long = 0L
) {
    val canPlayPause: Boolean
        get() {
            val mask = if (isPlaying) {
                android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE
            } else {
                android.media.session.PlaybackState.ACTION_PLAY or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE
            }
            return (supportedActions and mask) != 0L
        }

    val canNext: Boolean
        get() = (supportedActions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT) != 0L

    val canPrevious: Boolean
        get() = (supportedActions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L

    val canSeek: Boolean
        get() = durationMs > 0L &&
            (supportedActions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L

    fun positionAt(nowElapsedRealtime: Long): Long = currentPositionMs(nowElapsedRealtime)

    fun currentPositionMs(nowElapsedRealtime: Long): Long {
        if (!isPlaying || lastUpdateElapsedRealtime <= 0L) {
            return positionMs.coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
        }
        val delta = (nowElapsedRealtime - lastUpdateElapsedRealtime).coerceAtLeast(0L)
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        return (positionMs + (delta * speed).toLong())
            .coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
    }
}

/**
 * Slot content for compact and minimal presentations.
 */
sealed interface IslandSlotContent {
    data class Symbol(
        val symbol: IslandSymbol,
        val tintHex: String? = null
    ) : IslandSlotContent

    data class Text(
        val text: String,
        val colorHex: String? = null,
        val isMonospace: Boolean = false
    ) : IslandSlotContent

    data class Icon(
        val iconResId: Int? = null,
        val iconBitmap: Bitmap? = null,
        val iconUri: Uri? = null,
        val tintHex: String? = null
    ) : IslandSlotContent

    data class Progress(
        val current: Int,
        val max: Int,
        val isIndeterminate: Boolean = false,
        val tintHex: String? = null
    ) : IslandSlotContent

    data class Chronometer(
        val baseTimeMillis: Long,
        val isCountDown: Boolean = false,
        val isRunning: Boolean = true,
        val colorHex: String? = null
    ) : IslandSlotContent

    data class AudioWaveform(
        val amplitudes: List<Float>,
        val tintHex: String = "#1DB954"
    ) : IslandSlotContent

    data class Composite(
        val leadingIcon: Icon? = null,
        val text: Text? = null
    ) : IslandSlotContent
}

/**
 * Rich expanded layout regions modeled after iOS DynamicIslandExpandedRegion:
 * - Leading: Avatar, app icon, or small status
 * - Trailing: Progress, timer, or status text
 * - Center: Title, route, or prominent info
 * - Bottom: Controls, action buttons, progress bar, slider
 *
 * Supports `.dynamicIsland(verticalPlacement: .belowIfTooWide)` and region priority.
 */
data class ExpandedRegionData(
    val leading: IslandSlotContent,
    val trailing: IslandSlotContent,
    val center: IslandSlotContent? = null,
    val bottom: IslandSlotContent? = null,
    val actions: List<IslandAction> = emptyList(),
    val progressFraction: Float? = null,
    val progressLabel: String? = null,
    val customPayload: Map<String, Any> = emptyMap(),
    val leadingVerticalPlacement: ExpandedRegionLayoutSolver.VerticalPlacement =
        ExpandedRegionLayoutSolver.VerticalPlacement.BELOW_IF_TOO_WIDE,
    val trailingVerticalPlacement: ExpandedRegionLayoutSolver.VerticalPlacement =
        ExpandedRegionLayoutSolver.VerticalPlacement.BELOW_IF_TOO_WIDE,
    val leadingPriority: Int = 1,
    val trailingPriority: Int = 0,
    val milestones: List<String> = emptyList(),
    val currentMilestoneIndex: Int = 0,
    val etaLabel: String? = null,
    val lyricLine: String? = null,
    val audioRouteLabel: String? = null
)

/**
 * Real call audio state reported by Telecom / AudioManager (never fabricated).
 */
data class CallUiState(
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isOnHold: Boolean = false,
    val isControllable: Boolean = false
)

/**
 * An active ongoing Island Activity registered with the arbiter.
 * Includes ActivityKit parity fields: `relevanceScore`, `staleAtMillis`, `isStale`,
 * `keylineTintHex`, `payloadBytes`, and `dismissalPolicy`.
 */
data class IslandActivity(
    val id: String,
    val ownerPackage: String,
    val title: String,
    val subtitle: String? = null,
    val kind: ActivityKind,
    val rank: PriorityRank,
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val compactLeading: IslandSlotContent,
    val compactTrailing: IslandSlotContent,
    val minimalContent: IslandSlotContent,
    val expandedData: ExpandedRegionData,
    val deepLinkIntent: PendingIntent? = null,
    val actions: List<IslandAction> = emptyList(),
    val callState: CallUiState? = null,
    val timeoutMillis: Long? = null,
    val relevanceScore: Float = 0f,
    val interruptBoost: Int = 0,
    val staleAtMillis: Long? = null,
    val isStale: Boolean = false,
    val endedAtMillis: Long? = null,
    val dismissalPolicy: ActivityLifecyclePolicy.DismissalPolicy = ActivityLifecyclePolicy.DismissalPolicy.DEFAULT,
    val keylineTintHex: String? = null,
    val payloadBytes: Int = 64
) : Comparable<IslandActivity> {

    override fun compareTo(other: IslandActivity): Int {
        val rankCompare = this.rank.order.compareTo(other.rank.order)
        if (rankCompare != 0) return rankCompare
        val relevanceCompare = other.relevanceScore.compareTo(this.relevanceScore)
        if (relevanceCompare != 0) return relevanceCompare
        val startCompare = this.startedAt.compareTo(other.startedAt)
        if (startCompare != 0) return startCompare
        return this.id.compareTo(other.id)
    }
}

/**
 * Transient Alert data for temporary popouts (charging connected, silent switch, AirPod battery, alerting Live Update).
 */
data class TransientAlert(
    val id: String,
    val title: String,
    val message: String? = null,
    val leadingSlot: IslandSlotContent,
    val trailingSlot: IslandSlotContent? = null,
    val expandedData: ExpandedRegionData? = null,
    val durationMillis: Long = 3200L,
    val rank: PriorityRank = PriorityRank.TRANSIENT_SYSTEM_ALERT,
    val deepLinkIntent: PendingIntent? = null,
    val sound: Boolean = false,
    val haptic: Boolean = true
)

/**
 * One notification held in the island's inbox.
 */
data class IslandNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val body: String? = null,
    val postedAtMillis: Long,
    val isOngoing: Boolean = false,
    val appLabel: String? = null,
    val groupKey: String? = null,
    val importance: Int = 3,
    val endedAtMillis: Long? = null,
    val dismissalPolicy: ActivityLifecyclePolicy.DismissalPolicy = ActivityLifecyclePolicy.DismissalPolicy.DEFAULT
)

/**
 * Presentation states of the Dynamic Island.
 * Closed -> Compact -> Minimal (2 or 3 activities) -> Expanded -> AlertPeek -> NotificationInbox
 */
sealed interface IslandState {
    object Closed : IslandState

    data class Compact(
        val activity: IslandActivity
    ) : IslandState

    /**
     * Multi-island mitosis mode when 2 or 3 Live Activities are active simultaneously:
     * - [primary]: displayed in the attached minimal pill around the cutout.
     * - [secondary]: displayed in the detached minimal droplet beside the cutout.
     * - [tertiary]: optional 3rd concurrent activity (iPhone 18 Pro mitosis standard).
     */
    data class Minimal(
        val primary: IslandActivity,
        val secondary: IslandActivity,
        val totalActiveActivitiesCount: Int,
        val tertiary: IslandActivity? = null
    ) : IslandState

    data class Expanded(
        val activity: IslandActivity,
        val regionData: ExpandedRegionData
    ) : IslandState

    data class AlertPeek(
        val alert: TransientAlert,
        val expiresAtMillis: Long,
        val underlyingState: IslandState
    ) : IslandState

    data class NotificationInbox(
        val notifications: List<IslandNotification>,
        val collapsed: Boolean = false
    ) : IslandState {
        val count: Int get() = notifications.size
        val newest: IslandNotification? get() = notifications.firstOrNull()
    }
}
