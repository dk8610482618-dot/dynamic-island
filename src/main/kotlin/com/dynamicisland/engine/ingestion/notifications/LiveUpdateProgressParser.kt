package com.dynamicisland.engine.ingestion.notifications

import android.app.Notification
import android.os.Build
import com.dynamicisland.engine.core.model.ActivityKind
import com.dynamicisland.engine.core.model.PriorityRank
import java.util.Locale

/**
 * Adapter for Android 16+ (`Notification.ProgressStyle`) promoted Live Updates
 * and structured progress/navigation/delivery/download notifications on API 28–36.
 *
 * Classifies ongoing progress notifications into:
 * - [ActivityKind.NAVIGATION] (Turn-by-turn directions, distance, road name, ETA)
 * - [ActivityKind.LIVE_JOURNEY] (Rideshare, food/courier delivery, milestone queue, ETA)
 * - [ActivityKind.FILE_DOWNLOAD] (File transfer / download progress ring & throughput)
 * - [ActivityKind.LIVE_TIMER] / [ActivityKind.LIVE_STOPWATCH]
 */
object LiveUpdateProgressParser {

    data class ParsedLiveUpdate(
        val kind: ActivityKind,
        val rank: PriorityRank,
        val relevanceScore: Float,
        val interruptBoost: Int,
        val shouldAutoExpandAlert: Boolean,
        val compactTrailingBadge: String,
        val etaLabel: String?,
        val milestones: List<String>,
        val currentMilestoneIndex: Int,
        val keylineTintHex: String
    )

    private val NAVIGATION_PACKAGES = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "net.osmand",
        "com.here.app.maps"
    )

    private val JOURNEY_DELIVERY_KEYWORDS = listOf(
        "driver", "arriving", "delivery", "order", "courier", "uber", "lyft",
        "doordash", "zomato", "swiggy", "instacart", "pickup", "en route", "stops away"
    )

    private val DOWNLOAD_KEYWORDS = listOf(
        "downloading", "download", "transferring", "uploading", "mb/s", "kb/s", "gb"
    )

    private val IMMINENT_TURN_KEYWORDS = listOf(
        "turn left now", "turn right now", "in 50 m", "in 100 m", "in 150 ft", "in 200 ft", "exit now"
    )

    /**
     * Checks if an Android [Notification] uses Android 16 (API 36) `Notification.ProgressStyle`
     * or promoted ongoing Live Update extras.
     */
    fun isAndroid16ProgressStyle(notification: Notification, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt < 36) return false
        return runCatching {
            notification.extras?.getString(Notification.EXTRA_TEMPLATE)?.contains("ProgressStyle") == true ||
                notification.extras?.containsKey("android.progressPoints") == true ||
                notification.extras?.containsKey("android.progressSegments") == true
        }.getOrDefault(false)
    }

    /**
     * Parses structured notification metadata into a rich [ParsedLiveUpdate] descriptor.
     */
    fun classifyAndParse(
        packageName: String,
        category: String?,
        title: String,
        text: String?,
        subText: String?,
        progressCurrent: Int?,
        progressMax: Int?,
        isIndeterminate: Boolean,
        showChronometer: Boolean,
        isCountDown: Boolean,
        isProgressStyle: Boolean = false
    ): ParsedLiveUpdate? {
        val combinedLower = "${title.lowercase(Locale.ROOT)} ${text?.lowercase(Locale.ROOT) ?: ""} ${subText?.lowercase(Locale.ROOT) ?: ""}"
        val hasProgress = progressMax != null && (progressMax > 0 || isIndeterminate)
        val fraction = if (progressCurrent != null && progressMax != null && progressMax > 0) {
            (progressCurrent.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f)
        } else null

        // 1. Navigation Turn-by-Turn
        val isNav = category == Notification.CATEGORY_NAVIGATION ||
            packageName.lowercase(Locale.ROOT) in NAVIGATION_PACKAGES ||
            combinedLower.contains("turn left") ||
            combinedLower.contains("turn right") ||
            combinedLower.contains("continue onto")
        if (isNav) {
            val imminent = IMMINENT_TURN_KEYWORDS.any { combinedLower.contains(it) }
            val distanceBadge = extractShortDistance(title, text) ?: subText?.take(8) ?: "Nav"
            val eta = extractEta(text, subText)
            return ParsedLiveUpdate(
                kind = ActivityKind.NAVIGATION,
                rank = PriorityRank.NAVIGATION_TURN_BY_TURN,
                relevanceScore = if (imminent) 0.98f else 0.80f,
                interruptBoost = if (imminent) 200 else 0,
                shouldAutoExpandAlert = imminent,
                compactTrailingBadge = distanceBadge,
                etaLabel = eta,
                milestones = listOf("Start", "Cruising", "Approaching", "Destination"),
                currentMilestoneIndex = ((fraction ?: 0.5f) * 3).toInt().coerceIn(0, 3),
                keylineTintHex = "#0A84FF"
            )
        }

        // 2. Rideshare / Courier / Food Delivery Live Journey (Android 16 ProgressStyle or delivery telemetry)
        val hasDownloadKeyword = DOWNLOAD_KEYWORDS.any { combinedLower.contains(it) }
        val isJourney = (!hasDownloadKeyword && isProgressStyle) ||
            ( (hasProgress || showChronometer || category == Notification.CATEGORY_PROGRESS || category == "transport_journey") &&
                JOURNEY_DELIVERY_KEYWORDS.any { combinedLower.contains(it) } )
        if (isJourney) {
            val arrivingSoon = combinedLower.contains("arriving") || combinedLower.contains("1 min") || combinedLower.contains("2 min")
            val eta = extractEta(text, subText) ?: if (fraction != null) "${(fraction * 100).toInt()}%" else "Live"
            val stageIdx = when {
                arrivingSoon || (fraction != null && fraction >= 0.85f) -> 3
                fraction != null && fraction >= 0.50f -> 2
                fraction != null && fraction >= 0.20f -> 1
                else -> 1
            }
            return ParsedLiveUpdate(
                kind = ActivityKind.LIVE_JOURNEY,
                rank = PriorityRank.LIVE_JOURNEY_DELIVERY,
                relevanceScore = if (arrivingSoon) 0.92f else (0.60f + (fraction ?: 0.2f) * 0.25f),
                interruptBoost = if (arrivingSoon) 150 else 0,
                shouldAutoExpandAlert = arrivingSoon,
                compactTrailingBadge = eta.take(8),
                etaLabel = eta,
                milestones = listOf("Confirmed", "Preparing", "On the way", "Arriving"),
                currentMilestoneIndex = stageIdx,
                keylineTintHex = "#30D158"
            )
        }

        // 3. File Download / Transfer Activity
        val isDownload = hasProgress && (
            DOWNLOAD_KEYWORDS.any { combinedLower.contains(it) } ||
            category == Notification.CATEGORY_PROGRESS
        )
        if (isDownload) {
            val pct = if (fraction != null) "${(fraction * 100).toInt()}%" else "DL"
            return ParsedLiveUpdate(
                kind = ActivityKind.FILE_DOWNLOAD,
                rank = PriorityRank.FILE_DOWNLOAD_TRANSFER,
                relevanceScore = fraction ?: 0.40f,
                interruptBoost = 0,
                shouldAutoExpandAlert = false,
                compactTrailingBadge = pct,
                etaLabel = subText ?: text?.take(20),
                milestones = emptyList(),
                currentMilestoneIndex = 0,
                keylineTintHex = "#64D2FF"
            )
        }

        return null
    }

    private fun extractShortDistance(title: String, text: String?): String? {
        val regex = Regex("""\b(\d+(?:\.\d+)?\s*(?:m|km|ft|mi))\b""", RegexOption.IGNORE_CASE)
        return regex.find(title)?.value ?: text?.let { regex.find(it)?.value }
    }

    private fun extractEta(text: String?, subText: String?): String? {
        val regex = Regex("""\b(\d+\s*(?:min|mins|hr|hrs)|ETA\s*[\d:]+(?:\s*[AP]M)?)\b""", RegexOption.IGNORE_CASE)
        return subText?.let { regex.find(it)?.value }
            ?: text?.let { regex.find(it)?.value }
            ?: subText?.takeIf { it.length <= 16 }
    }
}
