package com.dynamicisland.engine.ingestion.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.dynamicisland.core.privacy.AppSuppressionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gap **G3** — observes enough foreground state for contextual app suppression.
 *
 * The base declared `hideInFullscreenApps` in `BehaviorOptions`, persisted it to
 * SharedPreferences and never read it again: a setting that appeared to work and
 * did nothing. This is the observation half of the fix.
 *
 * Two signals, with deliberately different permission costs:
 *
 *  1. **Immersive full-screen** — derived from the system-bar insets on our own
 *     overlay window. Needs no permission at all, and is the reliable indicator
 *     doc 2 actually describes ("full-screen games or video players" hide the
 *     system bars). The engine calls [onSystemBarsHidden] from the overlay's
 *     `WindowInsets` listener.
 *
 *  2. **Foreground package** — needed for the per-app matrix. This requires
 *     `PACKAGE_USAGE_STATS`, a user-grantable *special access* under
 *     Settings → Usage access. When it is not granted, [foregroundPackage] stays
 *     null and per-package rules simply do not fire; the global immersive
 *     heuristic still works. Nothing here guesses a package name from the window
 *     title or the notification stream, because a wrong guess would hide the
 *     island inside an app the user never listed.
 */
class ForegroundAppObserver(private val context: Context) {

    private val _state = MutableStateFlow<AppSuppressionPolicy.ForegroundAppState?>(null)
    val state: StateFlow<AppSuppressionPolicy.ForegroundAppState?> = _state.asStateFlow()

    private val usageStats =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Whether the overlay window currently sees the system bars hidden. */
    @Volatile private var systemBarsHidden = false

    /** Whether the foreground app is playing video, from a real signal only. */
    @Volatile private var videoPlaying = false

    /**
     * True when `PACKAGE_USAGE_STATS` is granted. Checked through AppOps rather
     * than `checkSelfPermission`, because usage access is an app-op, not a runtime
     * permission, and `checkSelfPermission` reports granted for it regardless.
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Called by the overlay's insets listener whenever the system bars become
     * hidden or visible again.
     */
    fun onSystemBarsHidden(hidden: Boolean) {
        if (systemBarsHidden == hidden) return
        systemBarsHidden = hidden
        refresh()
    }

    /**
     * Called when a real video-playback signal is observed (a `PlaybackState`
     * with a video surface, or the window reporting a playing `SurfaceView`).
     * Never inferred from the package name.
     */
    fun onVideoPlayback(playing: Boolean) {
        if (videoPlaying == playing) return
        videoPlaying = playing
        refresh()
    }

    /**
     * Re-reads the foreground package. Call this when the island's own state
     * changes or on a coarse cadence — not per frame.
     */
    fun refresh() {
        val pkg = if (hasUsageAccess()) queryForegroundPackage() else null
        _state.value = if (pkg == null && !systemBarsHidden && !videoPlaying) {
            // Nothing observable: report null so the policy keeps the island
            // visible rather than suppressing on a guess.
            null
        } else {
            AppSuppressionPolicy.ForegroundAppState(
                packageName = pkg ?: "",
                isImmersiveFullscreen = systemBarsHidden,
                isPlayingVideo = videoPlaying,
                isScreenOn = true
            )
        }
    }

    /**
     * Most recent `MOVE_TO_FOREGROUND` event. Queried over a short window because
     * `UsageStatsManager` has no push API; this is called on island state changes
     * rather than on a timer, so it does not become a polling loop.
     */
    private fun queryForegroundPackage(): String? {
        val manager = usageStats ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            val events = manager.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            var latestTime = -1L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.timeStamp > latestTime
                ) {
                    latestTime = event.timeStamp
                    latest = event.packageName
                }
            }
            latest?.takeIf { it != context.packageName }
        }.getOrNull()
    }

    private companion object {
        /** Long enough to cover a slow app switch, short enough to stay cheap. */
        const val FOREGROUND_LOOKBACK_MS = 10_000L
    }
}
