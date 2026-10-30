package com.dynamicisland.engine.ingestion.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.ingestion.IngestionFilters
import com.dynamicisland.engine.util.PendingIntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks active media sessions using MediaSessionManager and MediaController.
 *
 * Ingests PlaybackState, MediaMetadata, and drives equalizer waveform visualization
 * directly to the IslandEventBus.
 */
class MediaSessionTracker(
    private val context: Context,
    private val eventBus: IslandEventBus,
    private val notificationListenerComponent: ComponentName,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MediaSessionTracker"
    }

    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    /**
     * Fills in artwork that the player did not hand over directly.
     *
     * Most streaming apps publish only an artwork URI, and a local file may
     * carry no cover at all — both cases where reading `MediaMetadata` bitmaps
     * alone leaves the island showing a placeholder.
     */
    private val albumArtResolver = AlbumArtResolver(context)

    @Volatile
    private var isStarted = false

    // Active controller callbacks mapped by session token string representation
    private val activeControllers = ConcurrentHashMap<String, MediaController>()
    private val controllerCallbacks = ConcurrentHashMap<String, MediaController.Callback>()
    private val notificationSessionTokens = ConcurrentHashMap.newKeySet<String>()
    private val latestNotificationArt = ConcurrentHashMap<String, Bitmap>()

    // Per-package fallback transport intents to avoid cross-app intent leakage
    private data class FallbackTransport(
        val playPause: PendingIntent? = null,
        val next: PendingIntent? = null,
        val prev: PendingIntent? = null
    )
    private val fallbackIntentsByPackage = ConcurrentHashMap<String, FallbackTransport>()
    @Volatile
    private var mostRecentPackage: String? = null

    // Track active async HTTP art fetch job to cancel stale fetches on fast skipping
    private var currentArtFetchJob: Job? = null
    private val artGeneration = java.util.concurrent.atomic.AtomicLong()

    // Optimistic seek tracking to eliminate scrubber snap-back glitch
    @Volatile private var pendingSeekPositionMs: Long? = null
    @Volatile private var pendingSeekTimestampElapsedRealtime: Long = 0L
    private val PENDING_SEEK_TIMEOUT_MS = 1500L

    // Current primary controller driving the island
    private val _primaryController = MutableStateFlow<MediaController?>(null)
    val primaryController: StateFlow<MediaController?> = _primaryController.asStateFlow()

    // Sessions changed listener
    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateActiveControllers(controllers ?: emptyList())
        }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        if (isStarted) return
        val msm = mediaSessionManager ?: run {
            Log.w(TAG, "MediaSessionManager unavailable on this system")
            return
        }
        isStarted = true

        try {
            runCatching {
                msm.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            }
            msm.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                notificationListenerComponent,
                mainHandler
            )
            // Initial poll
            val initialControllers = runCatching {
                msm.getActiveSessions(notificationListenerComponent)
            }.getOrNull()
            updateActiveControllers(initialControllers ?: emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaSessionTracker: ${e.message}", e)
        }
    }

    /** Re-checks active media sessions (e.g. when NotificationListenerService connects). */
    fun recheckSessions() {
        if (!isStarted) return
        val msm = mediaSessionManager ?: return
        try {
            runCatching {
                msm.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            }
            msm.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                notificationListenerComponent,
                mainHandler
            )
            val controllers = runCatching {
                msm.getActiveSessions(notificationListenerComponent)
            }.getOrNull()
            updateActiveControllers(controllers ?: emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "recheckSessions failed", e)
        }
    }

    /** Direct ingestion of a MediaSession token extracted from a notification extra. */
    fun ingestSessionToken(token: android.media.session.MediaSession.Token, packageName: String) {
        val tokenStr = token.toString()
        notificationSessionTokens.add(tokenStr)
        if (activeControllers.containsKey(tokenStr)) return

        try {
            val controller = MediaController(context, token)
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handlePlaybackStateChanged(controller, state)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    handleMetadataChanged(controller, metadata)
                }

                override fun onSessionDestroyed() {
                    handleSessionDestroyed(controller)
                }
            }

            // Register callback first; only retain in active maps if registration succeeds
            controller.registerCallback(callback, mainHandler)
            activeControllers[tokenStr] = controller
            controllerCallbacks[tokenStr] = callback

            runCatching { handlePlaybackStateChanged(controller, controller.playbackState) }
            runCatching { handleMetadataChanged(controller, controller.metadata) }
            recomputePrimaryController()
            Log.i(TAG, "Ingested media session token from notification for $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ingest MediaSession token for $packageName", e)
        }
    }

    /** Ingests an album art bitmap directly from a notification's LargeIcon or Picture. */
    fun ingestNotificationArt(packageName: String, bitmap: Bitmap) {
        val bounded = AlbumArtResolver.boundBitmap(bitmap) ?: bitmap
        latestNotificationArt[packageName] = bounded
        val controller = _primaryController.value
        val targetController = if (controller != null && controller.packageName == packageName) {
            controller
        } else {
            activeControllers.values.firstOrNull { it.packageName == packageName }
        }

        if (targetController != null && targetController.sessionToken == _primaryController.value?.sessionToken) {
            val metadata = runCatching { targetController.metadata }.getOrNull()
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "Music Playing"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

            albumArtResolver.put(null, mediaIdOf(targetController), bounded)

            eventBus.emitEvent(
                SystemIngestionEvent.Media.MetadataChanged(
                    packageName = packageName,
                    title = title,
                    artist = artist,
                    album = null,
                    albumArtBitmap = bounded,
                    albumArtUri = null
                )
            )
        }
    }

    fun stop() {
        artGeneration.incrementAndGet()
        isStarted = false
        currentArtFetchJob?.cancel()
        currentArtFetchJob = null

        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing active sessions listener", e)
        }

        activeControllers.forEach { (token, controller) ->
            controllerCallbacks[token]?.let { cb ->
                runCatching { controller.unregisterCallback(cb) }
            }
        }
        activeControllers.clear()
        controllerCallbacks.clear()
        notificationSessionTokens.clear()
        latestNotificationArt.clear()
        fallbackIntentsByPackage.clear()
        mostRecentPackage = null
        albumArtResolver.clear()

        _primaryController.value = null
    }

    /** Clears cached artwork to reclaim memory under memory pressure (onTrimMemory / onLowMemory). */
    fun clearArtCache() {
        albumArtResolver.clear()
        latestNotificationArt.clear()
        Log.d(TAG, "Media art cache cleared")
    }

    private fun updateActiveControllers(controllers: List<MediaController>) {
        val currentTokens = controllers.map { it.sessionToken.toString() }.toSet()

        // Tokens ingested from notifications outlive their session: they were only
        // removed in `handleSessionDestroyed`, which does not fire for every death.
        // Without this the set grew for the life of the process and kept a stale
        // controller alive through the "preserve" branch below.
        notificationSessionTokens.retainAll(activeControllers.keys)

        // Remove destroyed or no longer active sessions (preserving sessions ingested from notifications)
        activeControllers.keys.filter { it !in currentTokens && it !in notificationSessionTokens }.forEach { removedToken ->
            val controller = activeControllers.remove(removedToken)
            val cb = controllerCallbacks.remove(removedToken)
            if (controller != null) {
                if (cb != null) {
                    runCatching { controller.unregisterCallback(cb) }
                }
                // Free artwork and fallbacks if no active controller shares this package
                val pkg = controller.packageName
                val stillActive = activeControllers.values.any { it.packageName == pkg }
                if (!stillActive) {
                    latestNotificationArt.remove(pkg)
                    fallbackIntentsByPackage.remove(pkg)
                }
                eventBus.emitEvent(SystemIngestionEvent.Media.SessionDestroyed(controller.packageName))
            }
        }

        // Register new controllers
        controllers.forEach { controller ->
            val token = controller.sessionToken.toString()
            if (!activeControllers.containsKey(token)) {
                try {
                    val callback = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            handlePlaybackStateChanged(controller, state)
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            handleMetadataChanged(controller, metadata)
                        }

                        override fun onSessionDestroyed() {
                            handleSessionDestroyed(controller)
                        }
                    }

                    controller.registerCallback(callback, mainHandler)
                    activeControllers[token] = controller
                    controllerCallbacks[token] = callback

                    // Emit initial snapshot safely
                    runCatching { handlePlaybackStateChanged(controller, controller.playbackState) }
                    runCatching { handleMetadataChanged(controller, controller.metadata) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register callback for controller ${controller.packageName}", e)
                }
            }
        }

        // Select the primary playing controller (or the most recent)
        recomputePrimaryController()
    }

    private fun handlePlaybackStateChanged(controller: MediaController, state: PlaybackState?) {
        // Update primary controller first so repo and arbiter see the active session immediately
        recomputePrimaryController()
        if (_primaryController.value?.sessionToken != controller.sessionToken) return

        publishPlayback(controller, state)
    }

    private fun publishPlayback(controller: MediaController, state: PlaybackState?) {
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val playbackStateVal = state?.state ?: PlaybackState.STATE_NONE
        val rawPosition = state?.position ?: 0L
        val now = SystemClock.elapsedRealtime()
        val pendingSeek = pendingSeekPositionMs
        val position = if (pendingSeek != null) {
            if (kotlin.math.abs(rawPosition - pendingSeek) < 1500L || now - pendingSeekTimestampElapsedRealtime > PENDING_SEEK_TIMEOUT_MS) {
                pendingSeekPositionMs = null
                rawPosition
            } else {
                pendingSeek
            }
        } else {
            rawPosition
        }
        val speed = state?.playbackSpeed ?: 1.0f
        val lastUpdate = state?.lastPositionUpdateTime ?: now

        val duration = runCatching {
            controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }.getOrNull() ?: 0L

        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = controller.packageName,
                isPlaying = isPlaying,
                playbackState = playbackStateVal,
                positionMs = position,
                durationMs = duration,
                playbackSpeed = speed,
                lastUpdateElapsedRealtime = lastUpdate,
                supportedActions = (state?.actions ?: 0L) or fallbackActions(controller.packageName)
            )
        )
    }

    private fun handleMetadataChanged(controller: MediaController, metadata: MediaMetadata?) {
        if (metadata == null || _primaryController.value?.sessionToken != controller.sessionToken) return
        // Track duration can change without a playback-state callback.
        publishPlayback(controller, controller.playbackState)

        // An idle session still carries metadata — the platform keeps one for
        // nearly every installed app, and it publishes metadata while playing
        // nothing at all. Passing those on lets the arbiter raise a media card
        // for a player that is not playing. `null` state is deliberately let
        // through: a session that has not reported yet is not evidence of
        // idleness, only NONE/STOPPED/ERROR are.
        val sessionState = controller.playbackState?.state
        if (sessionState == PlaybackState.STATE_NONE ||
            sessionState == PlaybackState.STATE_STOPPED ||
            sessionState == PlaybackState.STATE_ERROR
        ) {
            Log.d(TAG, "Ignoring metadata from idle session ${controller.packageName} (state=$sessionState)")
            return
        }

        // A bundle with no title at all is not a track with a placeholder name, it is
        // a session with nothing to show — the emulator's Google app holds exactly one
        // of those. Ingesting it produced an expanded card reading "Unknown Title /
        // Unknown Artist" for audio that did not exist, so it is skipped instead.
        val title = IngestionFilters.displayableTitle(
            rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            rawDisplayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        ) ?: run {
            Log.d(TAG, "Ignoring metadata with no displayable title from ${controller.packageName}")
            return
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

        val artUriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        val artUri = artUriStr?.let { Uri.parse(it) }

        // Prefer the player's own bitmap, then fall back to resolving whatever
        // it pointed at. Resolution covers the two cases the bitmaps miss:
        // a player that publishes only a URI, and a track whose tags carry no
        // cover at all but which the platform still has art for.
        //
        // Downscale oversized bitmaps (e.g. 1400x1400+ from Poweramp) using [AlbumArtResolver.boundBitmap]
        // to prevent large heap allocations and excessive GC pressure.
        val rawBitmap = runCatching {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        }.onFailure {
            Log.w(TAG, "album art resolution failed; falling back to placeholder", it)
        }.getOrNull()

        val bitmap = AlbumArtResolver.boundBitmap(rawBitmap)

        eventBus.emitEvent(
            SystemIngestionEvent.Media.MetadataChanged(
                packageName = controller.packageName,
                title = title,
                artist = artist,
                album = album,
                albumArtBitmap = bitmap,
                albumArtUri = artUri,
                launchIntent = controller.sessionActivity
            )
        )

        // If bitmap is not yet resolved but an HTTP/HTTPS URI exists, fetch asynchronously.
        // Cancel any pending fetch job so rapid track skipping does not emit stale artwork.
        currentArtFetchJob?.cancel()
        val generation = artGeneration.incrementAndGet()
        val mediaId = mediaIdOf(controller)
        if (bitmap == null) {
            currentArtFetchJob = scope.launch(Dispatchers.IO) {
                val fetched = albumArtResolver.resolve(artUri, mediaId)
                if (fetched != null) {
                    val boundedFetched = AlbumArtResolver.boundBitmap(fetched) ?: fetched
                    albumArtResolver.put(artUri, mediaIdOf(controller), boundedFetched)
                    // Ensure the controller is still the active primary controller before emitting
                    if (artGeneration.get() == generation && _primaryController.value?.sessionToken == controller.sessionToken) {
                        eventBus.emitEvent(
                            SystemIngestionEvent.Media.MetadataChanged(
                                packageName = controller.packageName,
                                title = title,
                                artist = artist,
                                album = album,
                                albumArtBitmap = boundedFetched,
                                albumArtUri = artUri
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * The playing track's `MediaStore` id, when the player exposes one.
     *
     * `MediaMetadata.METADATA_KEY_MEDIA_ID` is a free-form string. Players that
     * play local files usually set it to the MediaStore row id, which is what
     * lets the album-art fallback find artwork for a file whose tags carry no
     * cover. Players that stream set it to something else entirely, so a
     * non-numeric value is simply ignored rather than guessed at.
     */
    private fun mediaIdOf(controller: MediaController): Long? =
        runCatching {
            controller.metadata
                ?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?.toLongOrNull()
        }.getOrNull()

    private fun handleSessionDestroyed(controller: MediaController) {
        val token = controller.sessionToken.toString()
        notificationSessionTokens.remove(token)
        controllerCallbacks.remove(token)?.let { cb ->
            runCatching { controller.unregisterCallback(cb) }
        }
        activeControllers.remove(token)

        val pkg = controller.packageName
        val hasOtherSession = activeControllers.values.any { it.packageName == pkg }
        if (!hasOtherSession) {
            latestNotificationArt.remove(pkg)
            fallbackIntentsByPackage.remove(pkg)
            if (mostRecentPackage == pkg) {
                mostRecentPackage = activeControllers.values.firstOrNull()?.packageName
            }
        }

        eventBus.emitEvent(SystemIngestionEvent.Media.SessionDestroyed(pkg))
        recomputePrimaryController()
    }

    private fun recomputePrimaryController() {
        val controllers = activeControllers.values.sortedWith(compareBy<MediaController> { it.packageName }.thenBy { it.sessionToken.toString() })
        // Prefer playing controller, then actively paused, then non-idle, then any fallback
        val primary = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PAUSED
        } ?: controllers.firstOrNull {
            val s = it.playbackState?.state
            s != PlaybackState.STATE_NONE && s != PlaybackState.STATE_STOPPED && s != PlaybackState.STATE_ERROR
        } ?: controllers.firstOrNull()

        val changed = _primaryController.value?.sessionToken != primary?.sessionToken
        _primaryController.value = primary
        if (changed && primary != null) {
            handlePlaybackStateChanged(primary, primary.playbackState)
            handleMetadataChanged(primary, primary.metadata)
        }
        if (primary != null) {
            mostRecentPackage = primary.packageName
        }
    }

    // --- Interactive Transport Controls ---

    /**
     * Stores fallback notification pending intents mapped to [packageName] to eliminate
     * cross-app intent leakage when switching between audio sources.
     */
    fun updateFallbackIntents(
        packageName: String?,
        playPause: PendingIntent?,
        next: PendingIntent?,
        prev: PendingIntent?
    ) {
        val pkg = packageName ?: _primaryController.value?.packageName ?: mostRecentPackage ?: return
        fallbackIntentsByPackage[pkg] = FallbackTransport(
            playPause = playPause,
            next = next,
            prev = prev
        )
        mostRecentPackage = pkg
        _primaryController.value?.takeIf { it.packageName == pkg }?.let { publishPlayback(it, it.playbackState) }
    }

    private fun fallbackActions(pkg: String): Long {
        val fallback = fallbackIntentsByPackage[pkg] ?: return 0L
        return (if (fallback.playPause != null) PlaybackState.ACTION_PLAY_PAUSE else 0L) or
            (if (fallback.next != null) PlaybackState.ACTION_SKIP_TO_NEXT else 0L) or
            (if (fallback.prev != null) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L)
    }

    fun updateFallbackIntents(playPause: PendingIntent?, next: PendingIntent?, prev: PendingIntent?) {
        updateFallbackIntents(mostRecentPackage, playPause, next, prev)
    }

    private fun getFallbackFor(controller: MediaController?): FallbackTransport? {
        val pkg = controller?.packageName ?: mostRecentPackage ?: return null
        return fallbackIntentsByPackage[pkg]
    }

    fun play() {
        val controller = _primaryController.value
        if (controller != null && (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY == 0L) {
            if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_PAUSE != 0L) {
                runCatching {
                    controller.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    controller.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }.onFailure { Log.w(TAG,"Player rejected toggle",it) }
            } else getFallbackFor(controller)?.playPause?.let { PendingIntentCompat.send(context, it) }
            return
        }
        if (controller != null) {
            try {
                controller.transportControls.play()
            } catch (e: Exception) {
                Log.w(TAG, "transportControls.play() failed", e)
                getFallbackFor(controller)?.playPause?.let { PendingIntentCompat.send(context, it) }
            }
        } else {
            getFallbackFor(null)?.playPause?.let { PendingIntentCompat.send(context, it) }
        }
    }

    fun pause() {
        val controller = _primaryController.value
        if (controller != null && (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PAUSE == 0L) {
            if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_PAUSE != 0L) {
                runCatching {
                    controller.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    controller.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }.onFailure { Log.w(TAG,"Player rejected toggle",it) }
            } else getFallbackFor(controller)?.playPause?.let { PendingIntentCompat.send(context, it) }
            return
        }
        if (controller != null) {
            try {
                controller.transportControls.pause()
            } catch (e: Exception) {
                Log.w(TAG, "transportControls.pause() failed", e)
                getFallbackFor(controller)?.playPause?.let { PendingIntentCompat.send(context, it) }
            }
        } else {
            getFallbackFor(null)?.playPause?.let { PendingIntentCompat.send(context, it) }
        }
    }

    fun skipToNext() {
        val controller = _primaryController.value
        if (controller != null && (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_NEXT == 0L) {
            getFallbackFor(controller)?.next?.let { PendingIntentCompat.send(context, it) }; return
        }
        if (controller != null) {
            try {
                controller.transportControls.skipToNext()
            } catch (e: Exception) {
                Log.w(TAG, "transportControls.skipToNext() failed", e)
                getFallbackFor(controller)?.next?.let { PendingIntentCompat.send(context, it) }
            }
        } else {
            getFallbackFor(null)?.next?.let { PendingIntentCompat.send(context, it) }
        }
    }

    fun skipToPrevious() {
        val controller = _primaryController.value
        if (controller != null && (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_PREVIOUS == 0L) {
            getFallbackFor(controller)?.prev?.let { PendingIntentCompat.send(context, it) }; return
        }
        if (controller != null) {
            try {
                controller.transportControls.skipToPrevious()
            } catch (e: Exception) {
                Log.w(TAG, "transportControls.skipToPrevious() failed", e)
                getFallbackFor(controller)?.prev?.let { PendingIntentCompat.send(context, it) }
            }
        } else {
            getFallbackFor(null)?.prev?.let { PendingIntentCompat.send(context, it) }
        }
    }

    fun ingestFallbackMediaNotification(
        packageName: String,
        title: String,
        artist: String?,
        artBitmap: Bitmap?,
        isPlaying: Boolean,
        playPause: PendingIntent?,
        next: PendingIntent?,
        prev: PendingIntent?
    ) {
        updateFallbackIntents(packageName, playPause, next, prev)
        if (_primaryController.value != null) return // Session state is authoritative, including position.
        val bounded = AlbumArtResolver.boundBitmap(artBitmap)
        if (bounded != null) {
            latestNotificationArt[packageName] = bounded
        }

        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = packageName,
                isPlaying = isPlaying,
                playbackState = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                positionMs = 0L,
                durationMs = 0L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = SystemClock.elapsedRealtime(),
                supportedActions = (if (playPause != null) PlaybackState.ACTION_PLAY_PAUSE else 0L) or (if(next != null) PlaybackState.ACTION_SKIP_TO_NEXT else 0L) or (if(prev != null) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L)
            )
        )

        eventBus.emitEvent(
            SystemIngestionEvent.Media.MetadataChanged(
                packageName = packageName,
                title = title,
                artist = artist,
                album = null,
                albumArtBitmap = bounded,
                albumArtUri = null
            )
        )
    }

    fun seekTo(positionMs: Long) {
        val controller = _primaryController.value ?: return
        if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO == 0L) return
        try {
            val duration = runCatching {
                controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            }.getOrNull() ?: 0L
            val clamped = if (duration > 0L) {
                positionMs.coerceIn(0L, duration)
            } else {
                positionMs.coerceAtLeast(0L)
            }
            pendingSeekPositionMs = clamped
        pendingSeekTimestampElapsedRealtime = SystemClock.elapsedRealtime()
        controller.transportControls.seekTo(clamped)
        } catch (e: Exception) {
            Log.w(TAG, "transportControls.seekTo($positionMs) failed", e)
        }
    }

    /**
     * Compute real-time extrapolated playback position with duration safety bounds.
     */
    fun getExtrapolatedPositionMs(): Long {
        val controller = _primaryController.value ?: return 0L
        val state = runCatching { controller.playbackState }.getOrNull() ?: return 0L
        val pending = pendingSeekPositionMs
        if (pending != null && SystemClock.elapsedRealtime() - pendingSeekTimestampElapsedRealtime < PENDING_SEEK_TIMEOUT_MS) {
            return pending
        }
        val pos = state.position
        val speed = state.playbackSpeed
        val lastUpdate = state.lastPositionUpdateTime

        val extrapolated = if (state.state == PlaybackState.STATE_PLAYING && speed > 0) {
            val delta = (SystemClock.elapsedRealtime() - lastUpdate).coerceAtLeast(0L)
            pos + (delta * speed).toLong()
        } else {
            pos
        }
        val duration = runCatching {
            controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }.getOrNull() ?: 0L

        return if (duration > 0L) {
            extrapolated.coerceIn(0L, duration)
        } else {
            extrapolated.coerceAtLeast(0L)
        }
    }
}
