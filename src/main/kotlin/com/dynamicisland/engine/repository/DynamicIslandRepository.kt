package com.dynamicisland.engine.repository

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import com.dynamicisland.engine.arbiter.IslandPriorityArbiter
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.IslandState
import com.dynamicisland.engine.core.model.MediaPlaybackSnapshot
import com.dynamicisland.engine.ingestion.media.MediaSessionTracker
import com.dynamicisland.engine.ingestion.notifications.DynamicIslandNotificationListener
import com.dynamicisland.engine.ingestion.system.SystemStateObserver
import com.dynamicisland.engine.ingestion.telecom.TelecomCallTracker
import com.dynamicisland.engine.settings.BehaviorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified Repository coordinating all ingestion trackers, the priority queue arbiter,
 * and presentation StateFlows for the Dynamic Island UI.
 *
 * @param externalScope an optional caller-owned scope for the trackers and
 *   arbiter. When omitted the repository creates (and on [stop] cancels) its own.
 *   A caller-supplied scope is never cancelled here: it outlives this object and
 *   almost certainly hosts unrelated work.
 */
class DynamicIslandRepository(
    private val context: Context,
    externalScope: CoroutineScope? = null
) {
    /** True when this repository created [scope] and may therefore cancel it. */
    private val ownsScope: Boolean = externalScope == null

    private val scope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val eventBus = IslandEventBus()

    private val notificationListenerComponent = ComponentName(
        context,
        DynamicIslandNotificationListener::class.java
    )

    val mediaSessionTracker = MediaSessionTracker(
        context = context,
        eventBus = eventBus,
        notificationListenerComponent = notificationListenerComponent,
        scope = scope
    )

    val telecomCallTracker = TelecomCallTracker(
        context = context,
        eventBus = eventBus,
        scope = scope
    )

    val systemStateObserver = SystemStateObserver(
        context = context,
        eventBus = eventBus
    )

    val priorityArbiter = IslandPriorityArbiter(
        eventBus = eventBus,
        scope = scope,
        context = context,
        behavior = BehaviorPreferences.get(context).options,
        onDismissNotifications = DynamicIslandNotificationListener::dismissNotifications
    )

    // Public UI Presentation StateFlow
    val islandState: StateFlow<IslandState> = priorityArbiter.islandState
    val notificationCount: StateFlow<Int> = priorityArbiter.notificationCount

    // Live media transport snapshot consumed by the expanded media card.
    private val _mediaPlayback = MutableStateFlow(MediaPlaybackSnapshot())
    val mediaPlayback: StateFlow<MediaPlaybackSnapshot> = _mediaPlayback.asStateFlow()

    // Real-time microphone active state for privacy indicator dot
    val isMicrophoneInUse: StateFlow<Boolean> = systemStateObserver.isMicInUse
    val isCameraInUse: StateFlow<Boolean> = systemStateObserver.isCameraInUse

    init {
        // UNDISPATCHED so the subscription is live before the constructor returns.
        // `MutableSharedFlow` with `replay = 0` drops emissions that have no
        // subscriber, and `Dispatchers.Default` does not run a freshly launched
        // coroutine immediately — so a plain `launch` here loses any media session
        // that reports during startup. The arbiter already does this; this
        // collector was the one place that did not.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventBus.events.collect { event ->
                when (event) {
                    is SystemIngestionEvent.Media.PlaybackChanged -> {
                        // PlaybackChanged is emitted per session. Only the session
                        // that currently owns the island may drive the card, or a
                        // second paused player would overwrite the playing one's
                        // position.
                        val primaryPackage = mediaSessionTracker.primaryController.value?.packageName
                        if (!event.isPlaying && primaryPackage != null && event.packageName != primaryPackage) return@collect

                        _mediaPlayback.value = MediaPlaybackSnapshot(
                            isActive = event.playbackState != android.media.session.PlaybackState.STATE_NONE,
                            isPlaying = event.isPlaying,
                            positionMs = event.positionMs,
                            durationMs = event.durationMs,
                            playbackSpeed = event.playbackSpeed,
                            lastUpdateElapsedRealtime = event.lastUpdateElapsedRealtime,
                            supportedActions = event.supportedActions
                        )
                    }

                    is SystemIngestionEvent.NotificationEvent.AccessRevoked -> {
                        mediaSessionTracker.stop()
                        _mediaPlayback.value = MediaPlaybackSnapshot()
                    }
                    is SystemIngestionEvent.Media.SessionDestroyed -> {
                        if (mediaSessionTracker.primaryController.value?.packageName == event.packageName ||
                            mediaSessionTracker.primaryController.value == null
                        ) {
                            _mediaPlayback.value = MediaPlaybackSnapshot()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    // Sub-trackers exposed states
    val activeMediaController: StateFlow<MediaController?> = mediaSessionTracker.primaryController
    val currentCall: StateFlow<SystemIngestionEvent.Telecom.CallStateChanged?> = telecomCallTracker.currentActiveCall
    val batteryState: StateFlow<SystemIngestionEvent.SystemState.Battery?> = systemStateObserver.batteryState

    fun start() {
        DynamicIslandNotificationListener.onListenerConnectedCallback = {
            mediaSessionTracker.start()
            mediaSessionTracker.recheckSessions()
        }
        DynamicIslandNotificationListener.onMediaSessionTokenFound = { token, pkg ->
            mediaSessionTracker.ingestSessionToken(token, pkg)
        }
        DynamicIslandNotificationListener.onMediaNotificationArtFound = { pkg, bitmap ->
            mediaSessionTracker.ingestNotificationArt(pkg, bitmap)
        }
        DynamicIslandNotificationListener.onMediaTransportIntentsFound = { pkg, playPause, next, prev ->
            mediaSessionTracker.updateFallbackIntents(pkg, playPause, next, prev)
        }
        DynamicIslandNotificationListener.onMediaFallbackNotificationFound = { pkg, title, artist, art, isPlaying, playPause, next, prev ->
            mediaSessionTracker.ingestFallbackMediaNotification(pkg, title, artist, art, isPlaying, playPause, next, prev)
        }
        // Register all adapters before draining any already-posted media notifications.
        DynamicIslandNotificationListener.bindEventBus(eventBus)
        mediaSessionTracker.start()
        telecomCallTracker.start()
        systemStateObserver.start()
    }

    fun refreshPermissions() {
        telecomCallTracker.stop()
        systemStateObserver.stop()
        telecomCallTracker.start()
        systemStateObserver.start()
        mediaSessionTracker.recheckSessions()
        DynamicIslandNotificationListener.refreshNotifications()
    }

    fun stop() {
        DynamicIslandNotificationListener.bindEventBus(null)
        DynamicIslandNotificationListener.onListenerConnectedCallback = null
        DynamicIslandNotificationListener.onMediaSessionTokenFound = null
        DynamicIslandNotificationListener.onMediaNotificationArtFound = null
        DynamicIslandNotificationListener.onMediaTransportIntentsFound = null
        DynamicIslandNotificationListener.onMediaFallbackNotificationFound = null
        mediaSessionTracker.stop()
        telecomCallTracker.stop()
        systemStateObserver.stop()
        if (ownsScope) scope.cancel()
    }

    // --- User Actions & Controls ---

    fun sendUserCommand(command: IslandUserCommand) {
        eventBus.emitCommand(command)
    }

    /** Ends a live activity (e.g. cancels a running timer) from inside the island. */
    fun dismissActivity(activityId: String) {
        eventBus.emitCommand(IslandUserCommand.DismissActivity(activityId))
    }

    // Media Controls
    fun mediaPlay() = mediaSessionTracker.play()
    fun mediaPause() = mediaSessionTracker.pause()
    fun mediaSkipNext() = mediaSessionTracker.skipToNext()
    fun mediaSkipPrevious() = mediaSessionTracker.skipToPrevious()
    fun mediaSeekTo(positionMs: Long) = mediaSessionTracker.seekTo(positionMs)
    fun getEstimatedMediaPositionMs(): Long = mediaSessionTracker.getExtrapolatedPositionMs()

    // Telecom Controls
    fun callAnswer(callId: String) = telecomCallTracker.answerCall(callId)
    fun callHangup(callId: String) = telecomCallTracker.hangupCall(callId)
    fun callToggleMute(): Boolean = telecomCallTracker.toggleMute()
    fun callToggleSpeaker(): Boolean = telecomCallTracker.toggleSpeaker()

    fun onTrimMemory() = mediaSessionTracker.clearArtCache()
}
