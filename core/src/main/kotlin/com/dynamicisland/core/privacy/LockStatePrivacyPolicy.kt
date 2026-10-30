package com.dynamicisland.core.privacy

/**
 * Pure JVM presenter-level Smart Lock Screen Privacy Redaction policy.
 *
 * When the device keyguard is locked (or when sensitive redaction is active),
 * replaces personal/financial/message text with innocuous semantic summaries
 * ("Playing", "Active call", "Timer", "Navigation active", "Delivery update", "Download active")
 * and reveals full telemetry immediately upon authentication/unlock.
 */
object LockStatePrivacyPolicy {
    enum class PrivacySensitivity {
        PUBLIC,
        SENSITIVE_WHEN_LOCKED,
        CONFIDENTIAL_ALWAYS_REDACTED_ON_LOCK
    }

    data class RedactedPresentation(
        val title: String,
        val subtitle: String?,
        val isRedacted: Boolean,
        val allowDirectControlActions: Boolean
    )

    fun resolveSummaryForKind(
        kindName: String,
        originalTitle: String,
        originalSubtitle: String?,
        isKeyguardLocked: Boolean,
        redactOnLockScreen: Boolean = true,
        sensitivity: PrivacySensitivity = PrivacySensitivity.SENSITIVE_WHEN_LOCKED
    ): RedactedPresentation {
        if (!isKeyguardLocked) {
            return RedactedPresentation(
                title = originalTitle,
                subtitle = originalSubtitle,
                isRedacted = false,
                allowDirectControlActions = true
            )
        }
        val shouldRedact = redactOnLockScreen || sensitivity != PrivacySensitivity.PUBLIC
        if (!shouldRedact) {
            return RedactedPresentation(
                title = originalTitle,
                subtitle = originalSubtitle,
                isRedacted = false,
                allowDirectControlActions = false
            )
        }
        val genericTitle = when (kindName) {
            "MEDIA_SESSION" -> "Playing"
            "RINGING_CALL", "Incoming" -> "Incoming call"
            "LIVE_CALL", "EMERGENCY_CALL" -> "Active call"
            "LIVE_TIMER" -> "Timer"
            "LIVE_STOPWATCH" -> "Stopwatch"
            "NAVIGATION" -> "Navigation active"
            "LIVE_JOURNEY" -> "Live delivery update"
            "FILE_DOWNLOAD" -> "Download in progress"
            "BATTERY_CHARGING" -> "Charging"
            else -> "Notification"
        }
        return RedactedPresentation(
            title = genericTitle,
            subtitle = "Unlock to view details",
            isRedacted = true,
            allowDirectControlActions = false
        )
    }
}
