package com.dynamicisland.engine.arbiter

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.ActivityKind
import com.dynamicisland.engine.core.model.CallUiState
import com.dynamicisland.engine.core.model.ExpandedRegionData
import com.dynamicisland.engine.core.model.IslandAction
import com.dynamicisland.engine.core.model.IslandActivity
import com.dynamicisland.engine.core.model.IslandNotification
import com.dynamicisland.engine.core.model.IslandSlotContent
import com.dynamicisland.engine.core.model.IslandState
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.core.model.PriorityRank
import com.dynamicisland.engine.core.model.TransientAlert
import com.dynamicisland.core.activity.ActivityLifecyclePolicy
import com.dynamicisland.core.geometry.InteractionMode
import com.dynamicisland.engine.ingestion.media.AlbumArtResolver
import com.dynamicisland.engine.ingestion.notifications.LiveUpdateProgressParser
import com.dynamicisland.engine.util.PendingIntentCompat
import com.dynamicisland.engine.settings.BehaviorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Priority and Presentation State Arbiter for the Dynamic Island.
 *
 * Implements iOS Jindo priority rules:
 * Emergency/Call (100) > Live Timer/Stopwatch (200) > Navigation (300) > Media Playback (400) > Transient Alert (500)
 *
 * Handles:
 * - Deterministic Priority Queue ordering
 * - Multi-island management (Top 2 activities: Primary attached + Secondary detached)
 * - State machine: Closed -> Compact -> Minimal -> Expanded -> Alert Peek
 * - Interrupted expansion handling & transition routing
 */
class IslandPriorityArbiter(
    private val eventBus: IslandEventBus,
    private val scope: CoroutineScope,
    private val context: Context? = null,
    private val behavior: StateFlow<BehaviorOptions> = MutableStateFlow(BehaviorOptions()),
    private val onDismissNotifications: (List<String>) -> Unit = {},
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val MEDIA_ACTIVITY_ID = "activity_media_session"
        private const val TELECOM_ACTIVITY_ID_PREFIX = "activity_telecom_"

        /**
         * How many notifications the inbox retains.
         *
         * A listener runs for days and sees everything the device posts, so this
         * has to be bounded or it is a slow leak in a component the user never
         * restarts. Beyond a handful nobody reads individual rows anyway; the
         * island shows the newest few and a count of the rest.
         */
        private const val MAX_INBOX_SIZE = 20
    }

    private val mutex = Mutex()

    // Active activities registered in the priority pool
    private val activeActivities = ConcurrentHashMap<String, IslandActivity>()

    // Currently expanded activity ID, if any
    private var expansionPriorityAtSelection: Int? = null
    private var expandedActivityId: String? = null
        set(value) { field = value; expansionPriorityAtSelection = null }

    // Active transient alert peek job and alert instance
    private var activeAlertPeek: TransientAlert? = null

    /**
     * Wall-clock instant the current peek was raised.
     *
     * Kept separately because [recomputeIslandState] runs on every event: using
     * `now + duration` there made the reported expiry slide forward forever, so
     * it never described when the peek actually ends.
     */
    private var activeAlertPeekStartedAt: Long = 0L
    private var alertPeekJob: Job? = null

    /**
     * True while the tracked media session is actually playing.
     *
     * Held here rather than derived from the activity because a paused session
     * and a playing one both have a `MEDIA_SESSION` activity, and only the
     * playing one should render a moving waveform.
     */
    private var isMediaPlaying: Boolean = false

    /**
     * Notifications waiting to be seen, newest first.
     *
     * Bounded by [MAX_INBOX_SIZE]. A notification listener runs for days and
     * receives everything the device posts; an unbounded list here would grow
     * without limit and be a slow leak in a component the user never restarts.
     * Dropping the oldest is the right end to lose: the newest are what anyone
     * actually opens the island to read.
     */
    private val notificationInbox = ArrayDeque<IslandNotification>()

    /** True while the user has the inbox open. */
    private var inboxOpen: Boolean = false
    private var focusCycleOffset: Int = 0

    /** Launch handles for inbox entries, keyed by notification key. */
    private val pendingIntents = mutableMapOf<String, android.app.PendingIntent>()

    /** True when the device is locked on the lock screen (keyguard). */
    private var isKeyguardLocked: Boolean = false

    // Public StateFlow consumed by the UI Overlay window
    private val _islandState = MutableStateFlow<IslandState>(IslandState.Closed)
    val islandState: StateFlow<IslandState> = _islandState.asStateFlow()
    private val _notificationCount = MutableStateFlow(0)
    val notificationCount: StateFlow<Int> = _notificationCount.asStateFlow()
    private val _mediaActivity = MutableStateFlow<IslandActivity?>(null)
    val mediaActivity: StateFlow<IslandActivity?> = _mediaActivity.asStateFlow()
    val actionStatus = MutableStateFlow<String?>(null)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            behavior.collect { options ->
                mutex.withLock {
                    if (!options.notificationsEnabled) {
                        if (notificationInbox.any { it.key == activeAlertPeek?.id }) {
                            alertPeekJob?.cancel()
                            activeAlertPeek = null
                        }
                        activeActivities.keys.filter {
                            it != MEDIA_ACTIVITY_ID && !it.startsWith(TELECOM_ACTIVITY_ID_PREFIX)
                        }.forEach { removeActivity(it) }
                        clearInbox()
                    }
                    if (!options.systemAlertsEnabled && activeAlertPeek?.id !in notificationInbox.map { it.key }) {
                        alertPeekJob?.cancel()
                        activeAlertPeek = null
                    }
                    notificationInbox.filter { it.packageName in options.blockedPackages }.map { it.key }.forEach { forgetNotification(it); removeActivity(it) }
                    if (options.hideNotificationText) {
                        val redacted = notificationInbox.map { it.copy(title = it.appLabel ?: it.packageName, body = null) }
                        notificationInbox.clear(); notificationInbox.addAll(redacted)
                    }
                    recomputeIslandState()
                }
            }
        }
        // UNDISPATCHED so the subscriptions are live before the constructor returns.
        // Otherwise the SharedFlow (replay = 0) silently drops any event emitted in the
        // window between construction and the first dispatch — a real startup race where
        // early notifications / media sessions never reached the island.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventBus.events.collect { event ->
                try {
                    handleSystemEvent(event)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e("IslandPriorityArbiter", "Error handling system event", t)
                }
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventBus.commands.collect { command ->
                try {
                    handleUserCommand(command)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e("IslandPriorityArbiter", "Error handling user command", t)
                }
            }
        }
    }

    // --- System Ingestion Event Handling ---

    private suspend fun handleSystemEvent(event: SystemIngestionEvent) {
        mutex.withLock {
            when (event) {
                is SystemIngestionEvent.Media.PlaybackChanged -> handleMediaPlayback(event)
                is SystemIngestionEvent.Media.MetadataChanged -> handleMediaMetadata(event)
                is SystemIngestionEvent.Media.WaveformVisualization -> handleMediaWaveform(event)
                is SystemIngestionEvent.Media.SessionDestroyed -> {
                    // Only the session that actually owns the media activity may
                    // clear it. Every player session reports its own destruction,
                    // so removing unconditionally let a second app being torn down
                    // delete the card for the one still playing.
                    if (activeActivities[MEDIA_ACTIVITY_ID]?.ownerPackage == event.packageName) {
                        removeActivity(MEDIA_ACTIVITY_ID)
                    }
                }

                is SystemIngestionEvent.NotificationEvent.AccessRevoked -> {
                    activeActivities.keys.filter { !it.startsWith(TELECOM_ACTIVITY_ID_PREFIX) }.forEach { removeActivity(it) }
                    alertPeekJob?.cancel(); activeAlertPeek = null; clearInbox()
                }
                is SystemIngestionEvent.NotificationEvent.Posted -> handleNotificationPosted(event)
                is SystemIngestionEvent.NotificationEvent.Removed -> {
                    removeActivity(event.key)
                    if (activeAlertPeek?.id == event.key) {
                        alertPeekJob?.cancel()
                        activeAlertPeek = null
                    }
                    // Also drop it from the inbox: a notification dismissed from
                    // the shade should not linger in the island, or the two
                    // surfaces would disagree about what is still pending.
                    forgetNotification(event.key)
                }

                is SystemIngestionEvent.Telecom.CallStateChanged -> handleTelecomCallState(event)
                is SystemIngestionEvent.Telecom.CallDurationTick -> handleTelecomDurationTick(event)

                is SystemIngestionEvent.TransientAlertPost -> {
                    if (behavior.value.systemAlertsEnabled) triggerAlertPeek(event.alert)
                }

                is SystemIngestionEvent.SystemState.Battery -> {
                    // Battery alerts are posted directly via TransientAlertPost
                }
                is SystemIngestionEvent.SystemState.RingerDnd -> {
                    // Handled via TransientAlertPost
                }
                is SystemIngestionEvent.SystemState.BluetoothAccessory -> {
                    // Handled via TransientAlertPost
                }
                is SystemIngestionEvent.SystemState.Hotspot -> {
                    // Handled via TransientAlertPost
                }
                is SystemIngestionEvent.SystemState.KeyguardLocked -> {
                    isKeyguardLocked = event.isLocked
                    if (isKeyguardLocked) {
                        // Collapse any expanded cards to compact/minimal and close inbox when locking screen
                        expandedActivityId = null
                        inboxOpen = false
                    }
                }
                is SystemIngestionEvent.SystemState.Microphone -> {
                    // Handled directly via repository.isMicrophoneInUse for live UI privacy dot
                }
            }

            recomputeIslandState()
        }
    }

    // --- Media Activity Ingestion ---

    private fun handleMediaPlayback(event: SystemIngestionEvent.Media.PlaybackChanged) {
        val owner = activeActivities[MEDIA_ACTIVITY_ID]?.ownerPackage

        // The card belongs to the session that opened it. A second player's
        // pause or stop must not repaint it: the arbiter drives one media
        // activity, so on a device with several sessions every PlaybackChanged
        // used to hit the same card and the two layers disagreed — a play button
        // on the expanded card while the compact slot kept a playing waveform.
        // Another session may only take the card over by actually starting.
        if (owner != null && owner != event.packageName && !event.isPlaying) return

        isMediaPlaying = event.isPlaying

        if (!event.isPlaying) {
            // Remove media activity if playback completely stopped
            if (event.playbackState == android.media.session.PlaybackState.STATE_ERROR ||
                event.playbackState == android.media.session.PlaybackState.STATE_STOPPED ||
                event.playbackState == android.media.session.PlaybackState.STATE_NONE) {
                removeActivity(MEDIA_ACTIVITY_ID)
                return
            }

            // In iOS, media remains in island when recently paused, but remove if stopped/none
            val existing = activeActivities[MEDIA_ACTIVITY_ID]
            if (existing != null) {
                // Update to paused state. Both layers have to move together: the
                // expanded card used to keep its "Playing" label while the compact
                // slot flipped to a pause glyph.
                val updated = existing.copy(
                    compactTrailing = IslandSlotContent.Symbol(IslandSymbol.PAUSE, tintHex = "#8E8E93"),
                    expandedData = existing.expandedData.copy(
                        trailing = IslandSlotContent.Text("Paused", colorHex = "#8E8E93")
                    ),
                    lastUpdatedAt = clockMillis()
                )
                activeActivities[MEDIA_ACTIVITY_ID] = updated
            } else if (event.playbackState == android.media.session.PlaybackState.STATE_PAUSED || event.positionMs > 0) {
                val activity = IslandActivity(
                    id = MEDIA_ACTIVITY_ID,
                    ownerPackage = event.packageName,
                    title = "Music Paused",
                    kind = ActivityKind.MEDIA_SESSION,
                    rank = PriorityRank.MEDIA_PLAYBACK,
                    compactLeading = IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF"),
                    compactTrailing = IslandSlotContent.Symbol(IslandSymbol.PAUSE, tintHex = "#8E8E93"),
                    minimalContent = IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF"),
                    expandedData = ExpandedRegionData(
                        leading = IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF"),
                        trailing = IslandSlotContent.Text("Paused", colorHex = "#8E8E93"),
                        center = IslandSlotContent.Text("Music"),
                        bottom = IslandSlotContent.Text("Controls")
                    )
                )
                activeActivities[MEDIA_ACTIVITY_ID] = activity
            }
            return
        }

        // Prune any competing notification activities from the same package so they cannot
        // collide with or override the genuine media session.
        activeActivities.keys.filter { key ->
            key != MEDIA_ACTIVITY_ID && activeActivities[key]?.ownerPackage == event.packageName
        }.forEach { key ->
            activeActivities.remove(key)
        }

        val existing = activeActivities[MEDIA_ACTIVITY_ID]
        if (existing != null && existing.ownerPackage == event.packageName) {
            activeActivities[MEDIA_ACTIVITY_ID] = existing.copy(
                compactTrailing = IslandSlotContent.AudioWaveform(emptyList(), tintHex = "#FFFFFF"),
                expandedData = existing.expandedData.copy(
                    trailing = IslandSlotContent.Text("Playing", colorHex = "#1DB954")
                ),
                lastUpdatedAt = clockMillis()
            )
        } else {
            // Either no card yet, or a newly-playing session taking the card
            // over from a previous owner. Build it fresh: the previous owner's
            // title and artwork must not survive into the new session's card,
            // and the new session's metadata event arrives right behind this one.
            activeActivities[MEDIA_ACTIVITY_ID] = newPlayingActivity(event.packageName)
        }
    }

    private fun newPlayingActivity(packageName: String): IslandActivity =
        IslandActivity(
            id = MEDIA_ACTIVITY_ID,
            ownerPackage = packageName,
            title = "Music Playing",
            kind = ActivityKind.MEDIA_SESSION,
            rank = PriorityRank.MEDIA_PLAYBACK,
            compactLeading = IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF"),
            compactTrailing = IslandSlotContent.AudioWaveform(emptyList(), tintHex = "#FFFFFF"),
            minimalContent = IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF"),
            expandedData = ExpandedRegionData(
                leading = IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF"),
                trailing = IslandSlotContent.Text("Playing", colorHex = "#8E8E93"),
                center = IslandSlotContent.Text("Music"),
                bottom = IslandSlotContent.Text("Controls")
            )
        )

    private fun handleMediaMetadata(event: SystemIngestionEvent.Media.MetadataChanged) {
        val existing = activeActivities[MEDIA_ACTIVITY_ID]

        // A paused/background player's late artwork must not repaint the active player.
        if (existing != null && existing.ownerPackage != event.packageName) return
        // Metadata alone is not a live activity. The platform keeps a media
        // session for nearly every app, and it publishes metadata while idle —
        // creating the card here is what put a permanent "Unknown Title" music
        // card with a playing waveform on a device that had never played
        // anything. Playback state opens the card; metadata only decorates it.
        if (existing == null && !isMediaPlaying) return
        val leadingSlot = if (event.albumArtBitmap != null) {
            IslandSlotContent.Icon(iconBitmap = event.albumArtBitmap)
        } else if (event.albumArtUri != null) {
            IslandSlotContent.Icon(iconUri = event.albumArtUri)
        } else {
            IslandSlotContent.Symbol(IslandSymbol.MUSIC_NOTE, tintHex = "#FFFFFF")
        }

        val expanded = ExpandedRegionData(
            leading = leadingSlot,
            trailing = IslandSlotContent.Text(
                if (isMediaPlaying) "Playing" else "Paused",
                colorHex = if (isMediaPlaying) "#1DB954" else "#8E8E93"
            ),
            center = IslandSlotContent.Text(event.title),
            bottom = IslandSlotContent.Text(event.artist ?: event.album ?: "")
        )

        val activity = existing?.copy(
            title = event.title,
            subtitle = event.artist,
            deepLinkIntent = event.launchIntent ?: existing.deepLinkIntent,
            compactLeading = leadingSlot,
            minimalContent = leadingSlot,
            expandedData = expanded.copy(lyricLine = event.lyricLine, audioRouteLabel = event.audioRouteLabel),
            keylineTintHex = event.dominantColorHex ?: AlbumArtResolver.extractDominantVibrantHex(event.albumArtBitmap),
            relevanceScore = if (isMediaPlaying) 0.75f else 0.45f,
            lastUpdatedAt = clockMillis()
        ) ?: IslandActivity(
            id = MEDIA_ACTIVITY_ID,
            ownerPackage = event.packageName,
            title = event.title,
            subtitle = event.artist,
            deepLinkIntent = event.launchIntent,
            kind = ActivityKind.MEDIA_SESSION,
            rank = PriorityRank.MEDIA_PLAYBACK,
            compactLeading = leadingSlot,
            compactTrailing = IslandSlotContent.AudioWaveform(
                emptyList(),
                tintHex = event.dominantColorHex ?: AlbumArtResolver.extractDominantVibrantHex(event.albumArtBitmap)
            ),
            minimalContent = leadingSlot,
            expandedData = expanded.copy(lyricLine = event.lyricLine, audioRouteLabel = event.audioRouteLabel),
            keylineTintHex = event.dominantColorHex ?: AlbumArtResolver.extractDominantVibrantHex(event.albumArtBitmap),
            relevanceScore = if (isMediaPlaying) 0.75f else 0.45f
        )

        activeActivities[MEDIA_ACTIVITY_ID] = activity
    }

    private fun handleMediaWaveform(event: SystemIngestionEvent.Media.WaveformVisualization) {
        // A paused session must not show a moving equaliser, and a stale waveform
        // must not overwrite the pause glyph after playback stops.
        if (!isMediaPlaying) return
        val existing = activeActivities[MEDIA_ACTIVITY_ID] ?: return
        val tint = existing.keylineTintHex ?: "#1DB954"
        activeActivities[MEDIA_ACTIVITY_ID] = existing.copy(
            compactTrailing = IslandSlotContent.AudioWaveform(event.amplitudes, tintHex = tint)
        )
    }

    // --- Telecom Call Ingestion ---

    private fun handleTelecomCallState(event: SystemIngestionEvent.Telecom.CallStateChanged) {
        val activityId = "$TELECOM_ACTIVITY_ID_PREFIX${event.callId}"

        when (event.status) {
            SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED -> {
                removeActivity(activityId)
                if (expandedActivityId == activityId) {
                    expandedActivityId = null
                }
            }
            SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING -> {
                val callerName = event.callerName ?: "Incoming Call"
                val callActivity = IslandActivity(
                    id = activityId,
                    ownerPackage = "com.android.server.telecom",
                    title = callerName,
                    subtitle = event.callerNumber,
                    kind = ActivityKind.RINGING_CALL,
                    rank = PriorityRank.EMERGENCY_OR_CALL, // Rank 100 - beats everything!
                    callState = CallUiState(
                        isMuted = event.isMicrophoneMuted,
                        isSpeakerOn = event.isSpeakerphoneOn,
                        isControllable = event.isControllable
                    ),
                    compactLeading = IslandSlotContent.Symbol(IslandSymbol.PHONE, tintHex = "#30D158"),
                    compactTrailing = IslandSlotContent.Text("Incoming", colorHex = "#34C759"),
                    minimalContent = IslandSlotContent.Symbol(IslandSymbol.PHONE, tintHex = "#30D158"),
                    expandedData = ExpandedRegionData(
                        leading = IslandSlotContent.Symbol(IslandSymbol.PHONE, tintHex = "#30D158"),
                        trailing = IslandSlotContent.Text("Ringing", colorHex = "#8E8E93"),
                        center = IslandSlotContent.Text(callerName),
                        bottom = IslandSlotContent.Text(event.callerNumber ?: ""),
                        actions = listOfNotNull(
                            event.answerIntent?.let {
                                IslandAction(
                                    id = "answer",
                                    title = "Answer",
                                    pendingIntent = it
                                )
                            },
                            event.disconnectIntent?.let {
                                IslandAction(
                                    id = "decline",
                                    title = "Decline",
                                    isDestructive = true,
                                    pendingIntent = it
                                )
                            }
                        )
                    ),
                    deepLinkIntent = event.answerIntent
                )
                activeActivities[activityId] = callActivity

                // An incoming call forcibly cancels any lower-priority expanded state and active alert peeks
                alertPeekJob?.cancel()
                activeAlertPeek = null
                activeAlertPeekStartedAt = 0L

                if (expandedActivityId != null && expandedActivityId != activityId) {
                    expandedActivityId = null
                }
            }
            SystemIngestionEvent.Telecom.CallStatus.ACTIVE_CONNECTED,
            SystemIngestionEvent.Telecom.CallStatus.ON_HOLD -> {
                // An active connected call preempts lower-priority alert peeks
                if (activeAlertPeek != null && (activeAlertPeek?.rank?.order ?: 999) > PriorityRank.EMERGENCY_OR_CALL.order) {
                    alertPeekJob?.cancel()
                    activeAlertPeek = null
                    activeAlertPeekStartedAt = 0L
                }

                val callerName = event.callerName ?: "Call"
                val isHold = event.status == SystemIngestionEvent.Telecom.CallStatus.ON_HOLD
                // A live ticking chronometer instead of a text snapshot that only refreshed on
                // the telecom callback cadence. Re-emits (e.g. a mute toggle) must not reset
                // the elapsed time, so an existing base is preserved.
                val existingCall = activeActivities[activityId]
                val existingBase = (existingCall?.compactTrailing as? IslandSlotContent.Chronometer)
                    ?.baseTimeMillis ?: existingCall?.startedAt
                val durationSlot = IslandSlotContent.Chronometer(
                    baseTimeMillis = existingBase
                        ?: (clockMillis() - (event.durationSeconds * 1000L)),
                    colorHex = "#30D158"
                )

                val callActivity = IslandActivity(
                    id = activityId,
                    ownerPackage = "com.android.server.telecom",
                    title = callerName,
                    subtitle = event.callerNumber,
                    kind = ActivityKind.LIVE_CALL,
                    rank = PriorityRank.EMERGENCY_OR_CALL,
                    callState = CallUiState(
                        isMuted = event.isMicrophoneMuted,
                        isSpeakerOn = event.isSpeakerphoneOn,
                        isOnHold = isHold,
                        isControllable = event.isControllable
                    ),
                    compactLeading = IslandSlotContent.Symbol(IslandSymbol.PHONE, tintHex = "#30D158"),
                    compactTrailing = if (isHold) IslandSlotContent.Text("Hold", colorHex = "#30D158") else durationSlot,
                    minimalContent = IslandSlotContent.Symbol(IslandSymbol.PHONE, tintHex = "#30D158"),
                    expandedData = ExpandedRegionData(
                        leading = IslandSlotContent.Symbol(IslandSymbol.PHONE, tintHex = "#30D158"),
                        trailing = if (isHold) IslandSlotContent.Text("On Hold", colorHex = "#30D158") else durationSlot,
                        center = IslandSlotContent.Text(callerName),
                        bottom = IslandSlotContent.Text(if (isHold) "On Hold" else "Call Active"),
                        actions = listOfNotNull(
                            event.disconnectIntent?.let {
                                IslandAction(
                                    id = "end_call",
                                    title = "End",
                                    isDestructive = true,
                                    pendingIntent = it
                                )
                            }
                        )
                    ),
                    deepLinkIntent = event.answerIntent
                )
                activeActivities[activityId] = callActivity
            }
        }
    }

    private fun handleTelecomDurationTick(event: SystemIngestionEvent.Telecom.CallDurationTick) {
        val activityId = "$TELECOM_ACTIVITY_ID_PREFIX${event.callId}"
        val existing = activeActivities[activityId] ?: return

        // Paused / on-hold calls show a static label, not a ticking clock.
        if (existing.compactTrailing !is IslandSlotContent.Chronometer) return

        // The chronometer slot ticks off its own base, so the tick only needs to
        // mark the activity as fresh. It must NOT recompute the base: doing that
        // from a ticker that restarted at zero made the call timer jump backwards
        // on every re-established call.
        activeActivities[activityId] = existing.copy(
            lastUpdatedAt = clockMillis()
        )
    }

    // --- Notification Ingestion ---

    private fun handleNotificationPosted(event: SystemIngestionEvent.NotificationEvent.Posted) {
        if (!behavior.value.notificationsEnabled || event.packageName in behavior.value.blockedPackages) return
        // Notifications from an active media player (or media transport notifications)
        // must never create competing pseudo-timer or navigation activities.
        //
        // This guard runs BEFORE inbox ingestion. Media notifications are re-posted
        // on every track change, so ingesting them first filled the inbox with one
        // "Music Playing" row per song.
        if (event.category == android.app.Notification.CATEGORY_TRANSPORT
        ) {
            return
        }

        // ==================================================================
        // INBOX INGESTION
        //
        // Every remaining notification enters the inbox, including ones that also
        // produce a heads-up peek. The peek is the "something just happened"
        // moment; the inbox entry is what survives it, so a user who was not
        // looking at the screen still finds it later.
        // ==================================================================
        rememberNotification(event)

        if (!event.isOngoing) {
            // A completed timer/navigation notification may reuse its ongoing key.
            removeActivity(event.key)
            // Restoring existing notifications is a snapshot, not a burst of new arrivals.
            val time = java.time.LocalTime.now()
            val prefs = behavior.value
            if (!event.shouldAlert || (prefs.quietHours && com.dynamicisland.core.geometry.QuietHours.contains(time.hour * 60 + time.minute, prefs.quietStart, prefs.quietEnd))) return
            // Transient heads-up alert peek
            val alert = TransientAlert(
                id = event.key,
                title = event.title,
                message = event.text,
                leadingSlot = IslandSlotContent.Symbol(IslandSymbol.BELL),
                // Only show a trailing label when the notification really provides one;
                // a hard-coded "Alert" fallback was pure filler.
                trailingSlot = event.subText?.takeIf { it.isNotBlank() }
                    ?.let { IslandSlotContent.Text(it) },
                durationMillis = behavior.value.peekDurationMillis,
                rank = PriorityRank.TRANSIENT_SYSTEM_ALERT,
                deepLinkIntent = event.contentIntent
            )
            triggerAlertPeek(alert)
            return
        }

        val estimatedBytes = ActivityLifecyclePolicy.estimatePayloadBytes(
            title = event.title,
            subtitle = event.text,
            subText = event.subText,
            actionTitles = event.actions.map { it.title }
        )
        if (estimatedBytes > ActivityLifecyclePolicy.MAX_PAYLOAD_BYTES) return

        val parsedLiveUpdate = LiveUpdateProgressParser.classifyAndParse(
            packageName = event.packageName,
            category = event.category,
            title = event.title,
            text = event.text,
            subText = event.subText,
            progressCurrent = event.progressCurrent,
            progressMax = event.progressMax,
            isIndeterminate = event.isProgressIndeterminate,
            showChronometer = event.showChronometer,
            isCountDown = event.isChronometerCountDown,
            isProgressStyle = event.isProgressStyle
        )

        // Live Ongoing Activity: categorize by rank
        val (rank, kind) = when {
            event.category == "call" -> PriorityRank.EMERGENCY_OR_CALL to ActivityKind.LIVE_CALL
            event.category == "alarm" -> PriorityRank.LIVE_TIMER_STOPWATCH to ActivityKind.LIVE_TIMER
            event.category == "stopwatch" -> PriorityRank.LIVE_TIMER_STOPWATCH to ActivityKind.LIVE_STOPWATCH
            event.category == "workout" -> PriorityRank.LIVE_TIMER_STOPWATCH to ActivityKind.LIVE_STOPWATCH
            parsedLiveUpdate != null -> parsedLiveUpdate.rank to parsedLiveUpdate.kind
            event.category == "navigation" -> PriorityRank.NAVIGATION_TURN_BY_TURN to ActivityKind.NAVIGATION
            else -> {
                val isClockOrTimerPackage = event.packageName.contains("clock", ignoreCase = true) ||
                        event.packageName.contains("timer", ignoreCase = true) ||
                        event.packageName.contains("stopwatch", ignoreCase = true)
                val isNavPackage = event.packageName.contains("maps", ignoreCase = true) ||
                        event.packageName.contains("navigation", ignoreCase = true) ||
                        event.packageName.contains("waze", ignoreCase = true)
                if (isNavPackage) {
                    PriorityRank.NAVIGATION_TURN_BY_TURN to ActivityKind.NAVIGATION
                } else if (event.showChronometer && isClockOrTimerPackage) {
                    PriorityRank.LIVE_TIMER_STOPWATCH to ActivityKind.LIVE_STOPWATCH
                } else if (event.progressMax != null && event.progressMax > 0 && isClockOrTimerPackage) {
                    PriorityRank.LIVE_TIMER_STOPWATCH to ActivityKind.LIVE_TIMER
                } else {
                    PriorityRank.LOW_PRIORITY_NOTIFICATION to ActivityKind.TRANSIENT_NOTIFICATION
                }
            }
        }

        // --------------------------------------------------------------
        // A low-priority ongoing notification — no category, no progress,
        // no chronometer — is not a Live-Activity shape. It already
        // entered the inbox above, and that is where it stays: it must
        // not take the island.
        //
        // Every ongoing notification used to be promoted, and on a dev
        // device the shade always has some ("Serial console enabled",
        // USB debugging, sync notices). Each pinned a card with no
        // dismiss affordance, so the island was never quiet — and since
        // opening the inbox requires a quiet island, the inbox itself
        // became unreachable.
        // --------------------------------------------------------------
        if (kind == ActivityKind.TRANSIENT_NOTIFICATION) return

        val trailingSlot: IslandSlotContent = when {
            event.progressMax != null && event.progressMax > 0 -> {
                IslandSlotContent.Progress(
                    current = event.progressCurrent ?: 0,
                    max = event.progressMax,
                    isIndeterminate = event.isProgressIndeterminate
                )
            }
            event.showChronometer && event.chronometerBase != null -> {
                IslandSlotContent.Chronometer(
                    baseTimeMillis = event.chronometerBase,
                    isCountDown = event.isChronometerCountDown
                )
            }
            else -> IslandSlotContent.Text(event.text?.take(8) ?: "")
        }

        val (symbol, tint) = when (kind) {
            ActivityKind.LIVE_TIMER, ActivityKind.LIVE_STOPWATCH -> IslandSymbol.TIMER to "#FF9F0A"
            ActivityKind.NAVIGATION -> IslandSymbol.NAVIGATION to (parsedLiveUpdate?.keylineTintHex ?: "#0A84FF")
            ActivityKind.LIVE_JOURNEY -> IslandSymbol.DELIVERY to (parsedLiveUpdate?.keylineTintHex ?: "#30D158")
            ActivityKind.FILE_DOWNLOAD -> IslandSymbol.DOWNLOAD to (parsedLiveUpdate?.keylineTintHex ?: "#64D2FF")
            ActivityKind.LIVE_CALL, ActivityKind.RINGING_CALL, ActivityKind.EMERGENCY_CALL -> IslandSymbol.PHONE to "#30D158"
            else -> IslandSymbol.BELL to "#FFFFFF"
        }

        val progressFraction = if (event.progressCurrent != null && event.progressMax != null && event.progressMax > 0) {
            (event.progressCurrent.toFloat() / event.progressMax.toFloat()).coerceIn(0f, 1f)
        } else null

        val minimalSlot: IslandSlotContent = when {
            kind == ActivityKind.FILE_DOWNLOAD && event.progressMax != null && event.progressMax > 0 ->
                IslandSlotContent.Progress(event.progressCurrent ?: 0, event.progressMax, event.isProgressIndeterminate, tintHex = tint)
            (kind == ActivityKind.LIVE_TIMER || kind == ActivityKind.LIVE_STOPWATCH) && event.showChronometer && event.chronometerBase != null ->
                IslandSlotContent.Chronometer(event.chronometerBase, event.isChronometerCountDown, colorHex = tint)
            else -> IslandSlotContent.Symbol(symbol, tintHex = tint)
        }

        val existing = activeActivities[event.key]
        val nowMs = clockMillis()
        val activity = IslandActivity(
            id = event.key,
            ownerPackage = event.packageName,
            title = event.title,
            subtitle = event.text,
            kind = kind,
            rank = rank,
            startedAt = existing?.startedAt ?: nowMs,
            lastUpdatedAt = nowMs,
            compactLeading = IslandSlotContent.Symbol(symbol, tintHex = tint),
            compactTrailing = trailingSlot,
            minimalContent = minimalSlot,
            expandedData = ExpandedRegionData(
                leading = IslandSlotContent.Symbol(symbol, tintHex = tint),
                trailing = trailingSlot,
                center = IslandSlotContent.Text(event.title),
                bottom = event.text?.let { IslandSlotContent.Text(it) },
                actions = event.actions,
                progressFraction = progressFraction,
                progressLabel = parsedLiveUpdate?.compactTrailingBadge ?: event.subText,
                milestones = parsedLiveUpdate?.milestones ?: emptyList(),
                currentMilestoneIndex = parsedLiveUpdate?.currentMilestoneIndex ?: 0,
                etaLabel = parsedLiveUpdate?.etaLabel ?: event.subText
            ),
            deepLinkIntent = event.contentIntent,
            actions = event.actions,
            relevanceScore = if (event.relevanceScore > 0f) event.relevanceScore else (parsedLiveUpdate?.relevanceScore ?: 0.5f),
            interruptBoost = parsedLiveUpdate?.interruptBoost ?: 0,
            staleAtMillis = event.staleAtMillis,
            keylineTintHex = tint,
            payloadBytes = estimatedBytes
        )
        activeActivities[event.key] = activity

        if (parsedLiveUpdate?.shouldAutoExpandAlert == true && event.shouldAlert && behavior.value.systemAlertsEnabled) {
            val alert = TransientAlert(
                id = "${event.key}_live_update",
                title = event.title,
                message = event.text,
                leadingSlot = IslandSlotContent.Symbol(symbol, tintHex = tint),
                trailingSlot = parsedLiveUpdate.etaLabel?.let { IslandSlotContent.Text(it, colorHex = tint) } ?: trailingSlot,
                durationMillis = 2400L,
                rank = rank,
                deepLinkIntent = event.contentIntent
            )
            triggerAlertPeek(alert)
        }

        // An emergency or call notification cancels any lower-priority alert peeks
        if (rank == PriorityRank.EMERGENCY_OR_CALL) {
            if (activeAlertPeek != null && (activeAlertPeek?.rank?.order ?: 999) > PriorityRank.EMERGENCY_OR_CALL.order) {
                alertPeekJob?.cancel()
                activeAlertPeek = null
                activeAlertPeekStartedAt = 0L
            }
        }
    }

    private fun removeActivity(activityId: String) {
        activeActivities.remove(activityId)
        // Removing the media card also clears the playing flag, or a later
        // metadata event would see "still playing" and resurrect the card.
        if (activityId == MEDIA_ACTIVITY_ID) {
            isMediaPlaying = false
        }
        if (expandedActivityId == activityId) {
            expandedActivityId = null
        }
    }

    // --- Transient Alert Peek Handling ---

    private fun triggerAlertPeek(alert: TransientAlert) {
        // High-priority check: If a Call is already active in expanded or compact, don't let lower priority alerts block it
        val highestActiveRank = activeActivities.values.minOfOrNull { it.rank.order } ?: 999
        if (alert.rank.order > highestActiveRank && highestActiveRank <= PriorityRank.EMERGENCY_OR_CALL.order) {
            // Suppress non-call alert peek if active call is taking the island
            return
        }

        // Priority ladder check: If an alert is already peeking, a lower-priority alert cannot displace it
        val currentAlert = activeAlertPeek
        if (currentAlert != null && alert.rank.order > currentAlert.rank.order) {
            return
        }

        alertPeekJob?.cancel()
        activeAlertPeek = alert
        activeAlertPeekStartedAt = clockMillis()

        alertPeekJob = scope.launch {
            delay(alert.durationMillis)
            mutex.withLock {
                if (activeAlertPeek?.id == alert.id) {
                    activeAlertPeek = null
                    activeAlertPeekStartedAt = 0L
                    recomputeIslandState()
                }
            }
        }
    }

    // --- Notification Inbox ---

    /**
     * Records a posted notification, replacing any earlier entry with the same key.
     *
     * Replacement is by key, and the entry moves to the front: an app that
     * updates a notification has made it new again, and it should sort as such.
     */
    private fun rememberNotification(event: SystemIngestionEvent.NotificationEvent.Posted) {
        notificationInbox.removeAll { it.key == event.key }
        notificationInbox.addFirst(
            IslandNotification(
                key = event.key,
                packageName = event.packageName,
                title = if (behavior.value.hideNotificationText) (event.appLabel ?: event.packageName) else event.title,
                body = if (behavior.value.hideNotificationText) null else event.text,
                postedAtMillis = event.postedAtMillis ?: clockMillis(),
                isOngoing = event.isOngoing,
                appLabel = event.appLabel,
                groupKey = event.groupKey, importance = event.importance
            )
        )

        // The launch handle is refreshed with the entry. A re-post may carry a
        // new intent, and keeping a stale one would open the wrong thing.
        if (event.contentIntent != null) pendingIntents[event.key] = event.contentIntent
        else pendingIntents.remove(event.key)

        while (notificationInbox.size > MAX_INBOX_SIZE) {
            val evicted = notificationInbox.removeLast()
            pendingIntents.remove(evicted.key)
        }
    }

    /** Removes a notification, e.g. because the user cleared it on the shade. */
    private fun forgetNotification(key: String) {
        if (activeAlertPeek?.id == key) {
            alertPeekJob?.cancel()
            activeAlertPeek = null
        }
        val removed = notificationInbox.removeAll { it.key == key }
        pendingIntents.remove(key)
        if (notificationInbox.isEmpty()) {
            inboxOpen = false
        }
        // Only recompute if something actually changed; `onNotificationRemoved`
        // fires for keys this app never ingested.
        if (removed && inboxOpen) recomputeIslandState()
    }

    /** Empties the inbox. */
    private fun clearInbox() {
        if (notificationInbox.isEmpty() && !inboxOpen) return
        if (notificationInbox.any { it.key == activeAlertPeek?.id }) {
            alertPeekJob?.cancel()
            activeAlertPeek = null
        }
        notificationInbox.clear()
        pendingIntents.clear()
        inboxOpen = false
        recomputeIslandState()
    }

    private fun openInbox() {
        if (notificationInbox.isEmpty()) return
        alertPeekJob?.cancel()
        activeAlertPeek = null
        inboxOpen = true
        recomputeIslandState()
    }

    private fun closeInbox() {
        if (!inboxOpen) return
        inboxOpen = false
        recomputeIslandState()
    }

    // --- User Touch and Command Handling ---

    private suspend fun handleUserCommand(command: IslandUserCommand) {
        var intentToTrigger: PendingIntent? = null
        var callbackToTrigger: (() -> Unit)? = null
        var notificationsToDismiss = emptyList<String>()
        var timerCancellation = false
        mutex.withLock {
            when (command) {
                // --- Notification inbox ---
                is IslandUserCommand.OpenInbox -> {
                    if (!isKeyguardLocked) {
                        openInbox()
                    }
                }
                is IslandUserCommand.CloseInbox -> closeInbox()
                is IslandUserCommand.ClearInbox -> {
                    if (!isKeyguardLocked) {
                        notificationsToDismiss = notificationInbox.filterNot { it.isOngoing }.map { it.key }
                        closeInbox() // Listener removal events are authoritative.
                    }
                }
                is IslandUserCommand.DismissNotification -> {
                    if (!isKeyguardLocked) {
                        notificationInbox.firstOrNull { it.key == command.key }?.let {
                            if (!it.isOngoing) notificationsToDismiss = listOf(it.key)
                            // Retain the entry until Android confirms removal.
                        }
                    }
                }
                is IslandUserCommand.OpenNotification -> {
                    if (!isKeyguardLocked) {
                        intentToTrigger = pendingIntents[command.key]
                        if (intentToTrigger != null) {
                            closeInbox()
                        }
                    }
                }

                is IslandUserCommand.TapPrimary -> {
                    val current = _islandState.value
                    // Opening the inbox is a special case of "the island was
                    // tapped": with nothing else on screen, a tap should reveal
                    // what is waiting rather than do nothing.
                    //
                    // A tap on an *open* inbox closes it. It used to call
                    // clearInbox(), which deleted every notification on a single
                    // stray tap — the most destructive possible mapping for the
                    // most common gesture.
                    if (current is IslandState.NotificationInbox) {
                        closeInbox()
                        return@withLock
                    }
                    if (current is IslandState.Closed && notificationInbox.isNotEmpty()) {
                        if (!isKeyguardLocked) {
                            openInbox()
                        }
                        return@withLock
                    }
                    val appleParity = behavior.value.interactionMode == InteractionMode.APPLE_PARITY
                    when (current) {
                        is IslandState.Compact -> {
                            if (appleParity) {
                                intentToTrigger = current.activity.deepLinkIntent
                            } else {
                                expandedActivityId = current.activity.id
                            }
                        }
                        is IslandState.Minimal -> {
                            if (appleParity) {
                                intentToTrigger = current.primary.deepLinkIntent
                            } else {
                                expandedActivityId = current.primary.id
                            }
                        }
                        is IslandState.AlertPeek -> {
                            alertPeekJob?.cancel()
                            activeAlertPeek = null
                            activeAlertPeekStartedAt = 0L
                            if (notificationInbox.any { it.key == current.alert.id }) {
                                openInbox()
                            } else when (val underlying = current.underlyingState) {
                                is IslandState.Compact -> expandedActivityId = underlying.activity.id
                                is IslandState.Minimal -> expandedActivityId = underlying.primary.id
                                is IslandState.Expanded -> expandedActivityId = underlying.activity.id
                                else -> Unit
                            }
                        }
                        is IslandState.Expanded -> {
                            expandedActivityId = null
                        }
                        else -> Unit
                    }
                }
                is IslandUserCommand.TapSecondary -> {
                    val current = _islandState.value
                    val appleParity = behavior.value.interactionMode == InteractionMode.APPLE_PARITY
                    if (current is IslandState.Minimal && current.primary.rank != PriorityRank.EMERGENCY_OR_CALL) {
                        if (appleParity && current.secondary.deepLinkIntent != null) {
                            intentToTrigger = current.secondary.deepLinkIntent
                        } else {
                            expandedActivityId = current.secondary.id
                            expansionPriorityAtSelection = priorityOrder(current.primary)
                        }
                    }
                }
                is IslandUserCommand.TapTertiary -> {
                    val current = _islandState.value
                    val appleParity = behavior.value.interactionMode == InteractionMode.APPLE_PARITY
                    val tertiary = (current as? IslandState.Minimal)?.tertiary
                    if (current is IslandState.Minimal && tertiary != null && current.primary.rank != PriorityRank.EMERGENCY_OR_CALL) {
                        if (appleParity && tertiary.deepLinkIntent != null) {
                            intentToTrigger = tertiary.deepLinkIntent
                        } else {
                            expandedActivityId = tertiary.id
                            expansionPriorityAtSelection = priorityOrder(current.primary)
                        }
                    }
                }
                is IslandUserCommand.LongPressPrimary -> {
                    val current = _islandState.value
                    val appleParity = behavior.value.interactionMode == InteractionMode.APPLE_PARITY
                    val target = when (current) {
                        is IslandState.Compact -> current.activity
                        is IslandState.Minimal -> current.primary
                        is IslandState.Expanded -> current.activity
                        else -> null
                    }
                    if (target != null) {
                        if (appleParity && current !is IslandState.Expanded) {
                            expandedActivityId = target.id
                        } else {
                            intentToTrigger = target.deepLinkIntent
                        }
                    }
                }
                is IslandUserCommand.LongPressSecondary -> {
                    val current = _islandState.value
                    val appleParity = behavior.value.interactionMode == InteractionMode.APPLE_PARITY
                    if (current is IslandState.Minimal) {
                        if (appleParity && current.primary.rank != PriorityRank.EMERGENCY_OR_CALL) {
                            expandedActivityId = current.secondary.id
                            expansionPriorityAtSelection = priorityOrder(current.primary)
                        } else {
                            intentToTrigger = current.secondary.deepLinkIntent
                        }
                    }
                }
                is IslandUserCommand.LongPressTertiary -> {
                    val current = _islandState.value
                    val appleParity = behavior.value.interactionMode == InteractionMode.APPLE_PARITY
                    val tertiary = (current as? IslandState.Minimal)?.tertiary
                    if (current is IslandState.Minimal && tertiary != null) {
                        if (appleParity && current.primary.rank != PriorityRank.EMERGENCY_OR_CALL) {
                            expandedActivityId = tertiary.id
                            expansionPriorityAtSelection = priorityOrder(current.primary)
                        } else {
                            intentToTrigger = tertiary.deepLinkIntent
                        }
                    }
                }
                is IslandUserCommand.CycleActivityFocus -> {
                    if (activeActivities.size >= 2) {
                        focusCycleOffset += if (command.direction == 0) 1 else command.direction
                    }
                }
                is IslandUserCommand.CollapseExpanded -> {
                    expandedActivityId = null
                }
                is IslandUserCommand.DismissAlertPeek -> {
                    alertPeekJob?.cancel()
                    activeAlertPeek = null
                    activeAlertPeekStartedAt = 0L
                }
                is IslandUserCommand.DismissActivity -> {
                    removeActivity(command.activityId)
                }
                is IslandUserCommand.CancelTimer -> {
                    if (!isKeyguardLocked) {
                        intentToTrigger = com.dynamicisland.engine.core.model.TimerActions.cancellation(activeActivities[command.activityId])?.pendingIntent
                        timerCancellation = intentToTrigger != null
                        if (!timerCancellation) actionStatus.value = "The timer app does not provide a supported cancellation action. Open it to cancel."
                    }
                }
                is IslandUserCommand.ExecuteAction -> {
                    val activity = activeActivities[command.activityId]
                    val action = activity?.actions?.firstOrNull { it.id == command.actionId }
                    action?.let {
                        intentToTrigger = it.pendingIntent
                        callbackToTrigger = it.actionCallback
                    }
                }
                is IslandUserCommand.SeekMedia -> {
                    // Handled directly via MediaSessionTracker
                }
            }
            recomputeIslandState()
        }
        intentToTrigger?.let {
            val sent = PendingIntentCompat.send(context,it)
            if (timerCancellation) actionStatus.value = if (sent) "Cancellation requested. Waiting for the timer app to confirm." else "The timer action expired or was refused. Open the timer app."
        }
        runCatching { callbackToTrigger?.invoke() }
        if (notificationsToDismiss.isNotEmpty()) onDismissNotifications(notificationsToDismiss)
    }

    private fun triggerPendingIntent(intent: PendingIntent?) {
        if (intent == null) return
        PendingIntentCompat.send(context, intent)
    }

    // --- Priority Recomputation & Presentation State Model ---

    /**
     * Total priority resolution reproducing iOS Jindo:
     * - AlertPeek takes precedence if transient alert is active.
     * - If no activities -> Closed.
     * - If an activity is expanded and still active -> Expanded.
     * - If exactly 1 activity -> Compact (Leading + Trailing).
     * - If >= 2 activities -> Minimal (Top 2: primary attached + secondary detached).
     */
    private fun priorityOrder(activity: IslandActivity): Int {
        if (activity.rank == PriorityRank.EMERGENCY_OR_CALL) return 100
        val base = when {
            behavior.value.priority == com.dynamicisland.core.geometry.ActivityPriority.MEDIA_FIRST && activity.kind == ActivityKind.MEDIA_SESSION -> 150
            behavior.value.priority == com.dynamicisland.core.geometry.ActivityPriority.NAVIGATION_FIRST && activity.kind == ActivityKind.NAVIGATION -> 150
            else -> activity.rank.order
        }
        val stalePenalty = if (activity.isStale) 250 else 0
        return (base - activity.interruptBoost + stalePenalty).coerceAtLeast(101)
    }

    private fun recomputeIslandState() {
        val nowMs = clockMillis()
        val expiredIds = mutableListOf<String>()
        for ((id, act) in activeActivities) {
            val lifecycle = ActivityLifecyclePolicy.evaluate(
                startedAtMillis = act.startedAt,
                nowMillis = nowMs,
                staleAtMillis = act.staleAtMillis,
                endedAtMillis = act.endedAtMillis,
                dismissalPolicy = act.dismissalPolicy,
                payloadBytes = act.payloadBytes
            )
            if (lifecycle.shouldVacateIsland) {
                expiredIds.add(id)
            } else if (lifecycle.isStale != act.isStale) {
                activeActivities[id] = act.copy(isStale = lifecycle.isStale)
            }
        }
        expiredIds.forEach { removeActivity(it) }

        notificationInbox.removeAll { entry ->
            entry.endedAtMillis != null && (nowMs - entry.endedAtMillis) >= ActivityLifecyclePolicy.POST_END_RETENTION_MS
        }

        _mediaActivity.value = if (isKeyguardLocked) null else activeActivities[MEDIA_ACTIVITY_ID]
        _notificationCount.value = if (isKeyguardLocked) 0 else notificationInbox.size
        val rawSorted = activeActivities.values.sortedWith(
            compareBy<IslandActivity> { priorityOrder(it) }
                .thenByDescending { it.relevanceScore }
                .thenBy { it.startedAt }
                .thenBy { it.id }
        )
        val sortedActivities = if (rawSorted.size >= 2 && focusCycleOffset != 0 && rawSorted.first().rank != PriorityRank.EMERGENCY_OR_CALL) {
            val maxSlots = behavior.value.maxConcurrentActivities.coerceIn(2, 3).coerceAtMost(rawSorted.size)
            val topPool = rawSorted.take(maxSlots)
            val rest = rawSorted.drop(maxSlots)
            val shift = ((focusCycleOffset % topPool.size) + topPool.size) % topPool.size
            topPool.drop(shift) + topPool.take(shift) + rest
        } else {
            rawSorted
        }

        val baseState: IslandState = when {
            sortedActivities.isEmpty() -> {
                expandedActivityId = null
                IslandState.Closed
            }
            expandedActivityId != null -> {
                val expandedActivity = activeActivities[expandedActivityId]
                // If a strictly higher-priority activity has arrived (e.g. Call preempting Media),
                // collapse the lower-priority expanded activity so the higher-priority event is visible.
                val highestRankOrder = sortedActivities.firstOrNull()?.let { priorityOrder(it) } ?: Int.MAX_VALUE
                if (expandedActivity != null && (expansionPriorityAtSelection ?: priorityOrder(expandedActivity)) <= highestRankOrder) {
                    IslandState.Expanded(
                        activity = expandedActivity,
                        regionData = expandedActivity.expandedData
                    )
                } else {
                    // Expanded activity expired, dismissed, or preempted by higher-priority activity
                    expandedActivityId = null
                    resolveCompactOrMinimal(sortedActivities)
                }
            }
            else -> resolveCompactOrMinimal(sortedActivities)
        }

        if (isKeyguardLocked) {
            _islandState.value = IslandState.Closed
            return
        }

        // Check if a Transient Alert Peek is active
        val alert = activeAlertPeek
        _islandState.value = when {
            // An ordinary notification must not hide controls during a user interaction.
            baseState is IslandState.Expanded && !inboxOpen -> baseState
            alert != null -> IslandState.AlertPeek(
                alert = alert,
                expiresAtMillis = activeAlertPeekStartedAt + alert.durationMillis,
                underlyingState = baseState
            )

            // The open inbox sits above ordinary activity, unless keyguard is locked
            inboxOpen && notificationInbox.isNotEmpty() && !isKeyguardLocked ->
                IslandState.NotificationInbox(notifications = notificationInbox.toList())

            else -> baseState
        }
    }

    private fun resolveCompactOrMinimal(sortedActivities: List<IslandActivity>): IslandState {
        return if (sortedActivities.size == 1) {
            IslandState.Compact(sortedActivities[0])
        } else {
            val maxSlots = behavior.value.maxConcurrentActivities.coerceIn(2, 3)
            IslandState.Minimal(
                primary = sortedActivities[0],
                secondary = sortedActivities[1],
                totalActiveActivitiesCount = sortedActivities.size,
                tertiary = if (maxSlots >= 3) sortedActivities.getOrNull(2) else null
            )
        }
    }
}
