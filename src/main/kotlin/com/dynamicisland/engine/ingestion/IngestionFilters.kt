package com.dynamicisland.engine.ingestion

import java.util.Locale

/**
 * Pure ingestion predicates.
 *
 * These live outside the Android classes that call them for one reason: they are the
 * rules that decide whether an incoming event is real content, and a rule that can
 * only be exercised on a device is a rule that regresses quietly. There are no
 * `android.*` imports here, so `IngestionFiltersTest` runs as a plain JVM test.
 */
object IngestionFilters {

    /**
     * Channel the system posts "*app* is displaying over other apps" on.
     *
     * In AOSP AlertWindowNotification.java, the channel ID is constructed as
     * "com.android.server.wm.AlertWindowNotification - <package_name>".
     * Any channel ID starting with this prefix represents a system overlay alert.
     */
    const val OVERLAY_ALERT_CHANNEL_ID: String = "com.android.server.wm.AlertWindowNotification"

    /** True for the system's own overlay-alert notice; never user-facing content. */
    fun isOverlayAlertNotice(channelId: String?): Boolean =
        channelId != null && (channelId == OVERLAY_ALERT_CHANNEL_ID || channelId.startsWith(OVERLAY_ALERT_CHANNEL_ID))

    /** Known placeholder titles that represent absent or unpopulated content. */
    private val PLACEHOLDER_TITLES = setOf(
        "unknown title",
        "unknown artist",
        "unknown",
        "untitled",
        "null",
        "none",
        "no title"
    )

    fun isPlaceholderTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        return title.trim().lowercase(Locale.ROOT) in PLACEHOLDER_TITLES
    }

    /**
     * The title to display for a media session, or `null` when the bundle carries
     * nothing displayable or only placeholder text.
     */
    fun displayableTitle(rawTitle: String?, rawDisplayTitle: String?): String? {
        val cleanRaw = rawTitle?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderTitle(it) }
        if (cleanRaw != null) return cleanRaw
        return rawDisplayTitle?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderTitle(it) }
    }

    /** True when the notification was posted by the Dynamic Island application itself. */
    fun isSelfNotification(packageName: String?, hostPackageName: String?): Boolean =
        packageName != null && hostPackageName != null && packageName == hostPackageName

    private val SYSTEM_NOISE_CHANNEL_KEYWORDS = setOf(
        "DEVELOPMENT",
        "USB",
        "FOREGROUND_SERVICE",
        "DEVICE_ADMIN",
        "CARRIER"
    )

    /** Drops internal developer, USB, or background service noise channels. */
    fun isSystemNoiseChannel(channelId: String?): Boolean {
        if (channelId == null) return false
        val normalized = channelId.trim().uppercase(Locale.ROOT)
        return SYSTEM_NOISE_CHANNEL_KEYWORDS.any { normalized.contains(it) }
    }

    /**
     * Sanitizes and selects a notification title, falling back to app name or package.
     */
    fun sanitizeNotificationTitle(
        rawTitle: String?,
        packageName: String,
        appLabelProvider: () -> String?
    ): String {
        val candidate = rawTitle?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderTitle(it) }
        if (candidate != null) return candidate
        val appLabel = appLabelProvider()?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholderTitle(it) }
        return appLabel ?: packageName
    }
}
