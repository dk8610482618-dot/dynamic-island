package com.dynamicisland.engine.ingestion.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.IslandAction
import com.dynamicisland.engine.ingestion.IngestionFilters
import com.dynamicisland.engine.util.PendingIntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.Locale

/**
 * Intercepts, parses, and categorizes system notifications into either
 * Live Activities (ongoing navigation, timers, deliveries) or Transient Alert Peeks.
 */
class DynamicIslandNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "IslandNotificationListener"

        /** Bounded ceiling for initial drainage to prevent event bus flooding and heap pressure. */
        private const val MAX_ACTIVE_DRAIN = 20

        /** Bounded LRU cache size for suppressing high-frequency no-op notification duplicate emissions. */
        private const val MAX_DIGEST_CACHE_SIZE = 64

        @Volatile
        private var sharedEventBus: IslandEventBus? = null

        @Volatile
        private var instance: DynamicIslandNotificationListener? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        /** System extra written by `Notification.Builder.setChronometer` (no public constant). */
        private const val EXTRA_CHRONOMETER_BASE = "android.chronometerBase"

        private val LIVE_ACTIVITY_CATEGORIES = setOf(
            "call",
            "navigation",
            "alarm",
            "stopwatch",
            "progress",
            "workout",
            "transport_journey",
            "status",
            "service"
        )

        @Volatile
        var onListenerConnectedCallback: (() -> Unit)? = null
        @Volatile
        var onMediaSessionTokenFound: ((android.media.session.MediaSession.Token, String) -> Unit)? = null
        @Volatile
        var onMediaNotificationArtFound: ((String, android.graphics.Bitmap) -> Unit)? = null
        @Volatile
        var onMediaTransportIntentsFound: ((packageName: String, playPause: PendingIntent?, next: PendingIntent?, prev: PendingIntent?) -> Unit)? = null
        @Volatile
        var onMediaFallbackNotificationFound: ((packageName: String, title: String, artist: String?, art: android.graphics.Bitmap?, isPlaying: Boolean, playPause: PendingIntent?, next: PendingIntent?, prev: PendingIntent?) -> Unit)? = null

        /**
         * Checks whether notification listener permission is granted for this app.
         */
        fun isPermissionGranted(context: Context): Boolean {
            return runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }.getOrElse {
                val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                flat != null && flat.contains(context.packageName)
            }
        }

        /**
         * Points this listener at the bus owned by the running island service.
         */
        fun bindEventBus(bus: IslandEventBus?) {
            sharedEventBus = bus
            // A fresh repository needs the full snapshot even if content is unchanged.
            // Framework listener APIs must only be called after connection, on main.
            Handler(Looper.getMainLooper()).post {
                instance?.let { listener ->
                    listener.recentNotificationDigests.clear()
                    if (bus != null && sharedEventBus === bus && _isConnected.value) {
                        listener.drainActiveNotifications()
                    }
                }
            }
        }

        fun refreshNotifications() {
            Handler(Looper.getMainLooper()).post {
                instance?.let {
                    it.recentNotificationDigests.clear()
                    if (_isConnected.value) it.drainActiveNotifications()
                }
            }
        }

        /** Cancels exactly the represented keys, leaving unrelated shade items intact. */
        fun dismissNotifications(keys: List<String>) {
            if (keys.isEmpty()) return
            Handler(Looper.getMainLooper()).post {
                val listener = instance ?: return@post
                if (_isConnected.value) runCatching { listener.cancelNotifications(keys.toTypedArray()) }
                    .onFailure { Log.w(TAG, "Unable to dismiss notifications", it) }
            }
        }

        /**
         * Rebinds the notification listener service if disconnected or stale.
         * Leverages requestRebind with a component toggle fallback for robust reconnection.
         */
        fun ensureBound(context: Context) {
            if (!isPermissionGranted(context)) {
                Log.d(TAG, "Notification listener permission not granted; skipping ensureBound")
                return
            }

            if ((instance == null || !_isConnected.value) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val cn = ComponentName(context, DynamicIslandNotificationListener::class.java)
                try {
                    requestRebind(cn)
                } catch (e: Exception) {
                    Log.w(TAG, "Notification rebind refused; reopen notification access settings", e)
                }
            }
        }

        /**
         * Dismisses an individual notification from the system shade by its key.
         */
        fun dismissNotification(key: String): Boolean {
            val inst = instance ?: return false
            return try {
                inst.cancelNotification(key)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel notification $key", e)
                false
            }
        }

        /**
         * Clears all active notifications from the system notification shade.
         */
        fun dismissAllNotifications(): Boolean {
            val inst = instance ?: return false
            return try {
                inst.cancelAllNotifications()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel all notifications", e)
                false
            }
        }

        /**
         * Executes an action's PendingIntent through the active notification listener service.
         */
        fun executePendingAction(action: IslandAction): Boolean {
            val inst = instance ?: return false
            return inst.executeAction(action)
        }
    }

    /**
     * Digest representation of notification visual/structural state used to deduplicate
     * rapid identical post events.
     */
    private data class NotificationDigest(
        val title: String,
        val text: String?,
        val subText: String?,
        val progressCurrent: Int?,
        val progressMax: Int?,
        val isProgressIndeterminate: Boolean,
        val chronometerBase: Long?,
        val actionCount: Int,
        val contentIntent: PendingIntent?,
        val actionIntents: List<PendingIntent?>,
        val isOngoing: Boolean,
        val category: String?
    )

    private val recentNotificationDigests: MutableMap<String, NotificationDigest> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, NotificationDigest>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NotificationDigest>?): Boolean {
                    return size > MAX_DIGEST_CACHE_SIZE
                }
            }
        )

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _isConnected.value = false
        }
        recentNotificationDigests.clear()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "Dynamic Island Notification Listener Connected")
        onListenerConnectedCallback?.invoke()

        drainActiveNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
        _isConnected.value = false
        Log.i(TAG, "Dynamic Island Notification Listener Disconnected. Requesting rebind...")
        try {
            requestRebind(ComponentName(this, DynamicIslandNotificationListener::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request rebind after disconnection, falling back to ensureBound", e)
            ensureBound(this)
        }
        super.onListenerDisconnected()
        sharedEventBus?.emitEvent(SystemIngestionEvent.NotificationEvent.AccessRevoked)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        try {
            processStatusBarNotification(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error processing notification from ${sbn.packageName}", t)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        recentNotificationDigests.remove(sbn.key)

        try {
            sharedEventBus?.emitEvent(
                SystemIngestionEvent.NotificationEvent.Removed(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    notificationId = sbn.id,
                    reason = reason
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification removal for ${sbn.key}", e)
        }
    }

    private fun drainActiveNotifications() {
        try {
            val notifications = activeNotifications ?: return
            notifications
                .asSequence()
                .filterNotNull()
                .filterNot { IngestionFilters.isSelfNotification(it.packageName, packageName) }
                .sortedBy { it.postTime }
                .toList()
                .takeLast(MAX_ACTIVE_DRAIN)
                .forEach { sbn ->
                    processStatusBarNotification(sbn, isSnapshot = true)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error draining active notifications", e)
        }
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification, isSnapshot: Boolean = false) {
        if (sharedEventBus == null) return
        // Drop self-notifications from Dynamic Island host application
        if (IngestionFilters.isSelfNotification(sbn.packageName, packageName)) {
            return
        }

        val notification = sbn.notification ?: return
        // Summary cards duplicate their child messages; retain the individual messages.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // Drop system overlay alert notices and system noise channels
        if (IngestionFilters.isOverlayAlertNotice(notification.channelId) ||
            IngestionFilters.isSystemNoiseChannel(notification.channelId)
        ) {
            return
        }

        val extras = runCatching { notification.extras }.getOrNull() ?: Bundle()

        // 1. Identify ongoing vs transient alert
        val isOngoingFlag = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isForegroundServiceFlag = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val category = notification.category

        // Media notifications handling
        val isMediaNotification = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                category == Notification.CATEGORY_TRANSPORT ||
                category == "transport"

        if (isMediaNotification) {
            val rawToken = runCatching { extras.get(Notification.EXTRA_MEDIA_SESSION) }.getOrNull()
            val token: android.media.session.MediaSession.Token? = when (rawToken) {
                is android.media.session.MediaSession.Token -> rawToken
                else -> {
                    rawToken?.let { obj ->
                        runCatching {
                            val m = obj.javaClass.getMethod("getToken")
                            m.invoke(obj) as? android.media.session.MediaSession.Token
                        }.getOrNull() ?: runCatching {
                            val f = obj.javaClass.getDeclaredField("mInner")
                            f.isAccessible = true
                            f.get(obj) as? android.media.session.MediaSession.Token
                        }.getOrNull()
                    }
                }
            }

            var playPauseIntent: PendingIntent? = null
            var nextIntent: PendingIntent? = null
            var prevIntent: PendingIntent? = null
            var isPlayingInferred = false

            notification.actions?.forEach { action ->
                if (action == null) return@forEach
                val actionTitle = action.title?.toString()?.lowercase(Locale.ROOT) ?: ""
                when {
                    actionTitle.contains("pause") -> {
                        playPauseIntent = action.actionIntent
                        isPlayingInferred = true
                    }
                    actionTitle.contains("play") -> {
                        playPauseIntent = action.actionIntent
                    }
                    actionTitle.contains("next") || actionTitle.contains("skip") || actionTitle.contains("forward") -> {
                        nextIntent = action.actionIntent
                    }
                    actionTitle.contains("prev") || actionTitle.contains("back") || actionTitle.contains("previous") -> {
                        prevIntent = action.actionIntent
                    }
                }
            }

            if (playPauseIntent != null || nextIntent != null || prevIntent != null) {
                onMediaTransportIntentsFound?.invoke(sbn.packageName, playPauseIntent, nextIntent, prevIntent)
            }

            if (token != null) {
                onMediaSessionTokenFound?.invoke(token, sbn.packageName)
            }

            val largeIconBitmap: android.graphics.Bitmap? = runCatching {
                val icon = notification.getLargeIcon()
                val drawable = icon?.loadDrawable(this)
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    drawable.bitmap
                } else if (drawable != null) {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
                    val targetW = width.coerceIn(1, 256)
                    val targetH = height.coerceIn(1, 256)
                    val bitmap = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, targetW, targetH)
                    drawable.draw(canvas)
                    bitmap
                } else {
                    null
                }
            }.getOrNull()

            if (largeIconBitmap != null) {
                onMediaNotificationArtFound?.invoke(sbn.packageName, largeIconBitmap)
            }

            if (token == null) {
                val rawMediaTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                val mediaTitle = IngestionFilters.displayableTitle(rawMediaTitle, null) ?: "Music"

                val mediaArtist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

                onMediaFallbackNotificationFound?.invoke(
                    sbn.packageName,
                    mediaTitle,
                    mediaArtist,
                    largeIconBitmap,
                    isPlayingInferred,
                    playPauseIntent,
                    nextIntent,
                    prevIntent
                )
            }
            return
        }

        val isLiveActivityCategory = category in LIVE_ACTIVITY_CATEGORIES

        // Progress bar extraction
        val progressCurrent = if (extras.containsKey(Notification.EXTRA_PROGRESS)) {
            runCatching { extras.getInt(Notification.EXTRA_PROGRESS) }.getOrNull()
        } else null

        val progressMax = if (extras.containsKey(Notification.EXTRA_PROGRESS_MAX)) {
            runCatching { extras.getInt(Notification.EXTRA_PROGRESS_MAX) }.getOrNull()
        } else null

        val isIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        // Chronometer extraction with clock-domain normalization
        val showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val isChronometerCountDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)

        val chronometerBase = if (showChronometer) {
            val explicitBase = extras.getLong(EXTRA_CHRONOMETER_BASE, 0L)
            when {
                explicitBase > 0L -> {
                    // Normalize elapsedRealtime to wall-clock epoch millis if needed (< 10 billion ms)
                    if (explicitBase < 10_000_000_000L) {
                        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - explicitBase)
                    } else {
                        explicitBase
                    }
                }
                notification.`when` > 0L -> notification.`when`
                else -> null
            }
        } else null

        // True ongoing qualification requires real ongoing flags
        val hasOngoingFlags = isOngoingFlag || isForegroundServiceFlag
        val hasLiveIndicators = (progressMax != null && (progressMax > 0 || isIndeterminate)) ||
                (showChronometer && chronometerBase != null)

        val isOngoing = hasOngoingFlags && (isLiveActivityCategory || hasLiveIndicators || isOngoingFlag)

        // Title and text extraction with app name fallback
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val title = IngestionFilters.sanitizeNotificationTitle(rawTitle, sbn.packageName) {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrNull()
        }

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()

        // Action buttons extraction
        val actions = mutableListOf<IslandAction>()
        notification.actions?.forEachIndexed { index, action ->
            if (action != null) {
                val actionTitle = action.title?.toString()?.trim()?.ifEmpty { null } ?: "Action ${index + 1}"
                actions.add(
                    IslandAction(
                        id = "${sbn.key}_action_$index",
                        title = actionTitle,
                        iconResId = null,
                        isDestructive = actionTitle.contains("Cancel", ignoreCase = true) ||
                                actionTitle.contains("Stop", ignoreCase = true) ||
                                actionTitle.contains("Dismiss", ignoreCase = true) ||
                                actionTitle.contains("End", ignoreCase = true) ||
                                actionTitle.contains("Delete", ignoreCase = true) ||
                                actionTitle.contains("Remove", ignoreCase = true),
                        semantics = com.dynamicisland.engine.core.model.TimerActions.classify(actionTitle),
                        pendingIntent = action.actionIntent
                    )
                )
            }
        }

        // Deduplication digest check to prevent redundant event emission and GC/render thrashing
        val ranking = Ranking()
        val importance = if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else 3
        val digest = NotificationDigest(
            title = title,
            text = text,
            subText = subText,
            progressCurrent = progressCurrent,
            progressMax = progressMax,
            isProgressIndeterminate = isIndeterminate,
            chronometerBase = chronometerBase,
            actionCount = actions.size,
            contentIntent = notification.contentIntent,
            actionIntents = actions.map { it.pendingIntent },
            isOngoing = isOngoing,
            category = category
        )

        if (recentNotificationDigests[sbn.key] == digest) {
            return
        }
        recentNotificationDigests[sbn.key] = digest

        sharedEventBus?.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = sbn.key,
                packageName = sbn.packageName,
                notificationId = sbn.id,
                category = category,
                isOngoing = isOngoing,
                title = title.take(200),
                text = text?.take(2000),
                subText = subText?.take(300),
                progressCurrent = progressCurrent,
                progressMax = progressMax,
                isProgressIndeterminate = isIndeterminate,
                chronometerBase = chronometerBase,
                showChronometer = showChronometer,
                isChronometerCountDown = isChronometerCountDown,
                actions = actions,
                contentIntent = notification.contentIntent,
                statusBarNotification = sbn,
                shouldAlert = !isSnapshot,
                postedAtMillis = sbn.postTime,
                groupKey = sbn.groupKey.takeIf { sbn.isGroup },
                importance = importance,
                isProgressStyle = LiveUpdateProgressParser.isAndroid16ProgressStyle(notification),
                appLabel = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrNull()
            )
        )
    }

    /**
     * Programmatically execute an action's PendingIntent.
     */
    fun executeAction(action: IslandAction): Boolean {
        val intent = action.pendingIntent ?: return false
        return try {
            val success = PendingIntentCompat.send(this, intent)
            if (success) {
                action.actionCallback?.invoke()
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "PendingIntent execution failed for action ${action.id}", e)
            false
        }
    }
}

