package com.dynamicisland.core.system

import kotlin.math.min
import kotlin.math.pow

/**
 * Gap **G6** — foreground-service watchdog and crash resilience.
 *
 * Doc 2: "because overlay services on Android are frequently terminated by
 * aggressive background battery management or crash during rapid transitions
 * between calls, charging, and music, the architecture must incorporate a
 * foreground service watchdog with explicit battery optimization exemption
 * onboarding and BOOT_COMPLETED receivers so the island survives reboots and
 * stays active reliably across concurrent system events."
 *
 * The base already had the boot receiver and the exemption screen. What it did
 * not have was any way to notice that the service had been killed or had wedged
 * itself — the failure mode OEM ROMs actually produce. This policy supplies the
 * decision logic; the engine supplies the alarm and the observations.
 *
 * Doc 1 constrains the shape of the solution: "zero recurring wakeups solely for
 * static island content" and "no continuous CPU work in idle state". A watchdog
 * that polls every second would violate both, so the interval is coarse
 * ([DEFAULT_CHECK_INTERVAL_MS]), it is only armed while the user has the island
 * enabled, and it disarms entirely once the battery exemption is refused twice —
 * at that point retrying is harassment, not resilience, and the UI should say so
 * instead.
 *
 * Pure JVM and deterministic: no jitter, no clock. Backoff is a pure function of
 * the consecutive-failure count so a test can assert the exact schedule.
 */
object ServiceWatchdogPolicy {

    /** Coarse heartbeat. Aligned to a minute so it can share a system slot. */
    const val DEFAULT_CHECK_INTERVAL_MS: Long = 60_000L

    /** Longest backoff before a restart attempt. One hour. */
    const val MAX_BACKOFF_MS: Long = 60L * 60_000L

    /** First retry after a detected failure: fifteen seconds. */
    const val BASE_RETRY_MS: Long = 15_000L

    /**
     * How many missed heartbeats count as "wedged" rather than "briefly busy".
     * Two is enough to avoid reacting to a single slow frame or a GC pause.
     */
    const val FAILURE_THRESHOLD: Int = 2

    /**
     * After this many consecutive *refused* exemptions, stop asking. Doc 1's
     * privacy section requires truthful, non-nagging permission behaviour.
     */
    const val MAX_EXEMPTION_PROMPTS: Int = 2

    /** What the engine observed at one heartbeat. */
    data class HealthSignal(
        /** Service reports itself running. */
        val isServiceRunning: Boolean,
        /** Overlay window is attached to the window manager. */
        val isOverlayAttached: Boolean,
        /** Notification listener is still granted and connected. */
        val isListenerConnected: Boolean,
        /** Milliseconds since the service last published a heartbeat. */
        val millisSinceLastHeartbeat: Long,
        /** The user has granted the battery-optimisation exemption. */
        val hasBatteryExemption: Boolean,
        /** How many times the exemption prompt was declined. */
        val exemptionDeclineCount: Int = 0,
        /** The user has switched the island off; nothing should restart. */
        val isUserEnabled: Boolean = true,
        /** Device is in a doze/idle maintenance window. */
        val isDeviceIdle: Boolean = false
    )

    /** What the watchdog concluded. */
    enum class Verdict {
        /** Everything is fine. Do nothing. */
        HEALTHY,

        /** Service is alive but the overlay window detached — re-attach only. */
        REATTACH_OVERLAY,

        /** Listener access was revoked or dropped — surface the settings path. */
        LISTENER_DISCONNECTED,

        /** No heartbeat, or the service is gone — restart it. */
        RESTART_SERVICE,

        /**
         * Restart is warranted but blocked: the user disabled the island, the
         * device is idle, or the exemption was refused too many times. The
         * engine must report this in the UI rather than retry silently.
         */
        BLOCKED,

        /** Not armed at all. */
        DISABLED
    }

    data class Decision(
        val verdict: Verdict,
        /** Delay before the next check or restart attempt. */
        val delayMs: Long,
        val consecutiveFailures: Int,
        /** True when the engine should show an explanatory notice, not retry. */
        val requiresUserAction: Boolean = false
    )

    /**
     * Exponential backoff with a hard ceiling: 15 s, 30 s, 60 s, … capped at
     * one hour. Deterministic so the schedule can be asserted in a test.
     */
    fun backoffFor(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val exponential = BASE_RETRY_MS * 2.0.pow((consecutiveFailures - 1).coerceAtMost(20))
        return min(exponential.toLong(), MAX_BACKOFF_MS)
    }

    /** True once enough heartbeats have been missed to call the service wedged. */
    fun isWedged(millisSinceLastHeartbeat: Long, checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS): Boolean =
        millisSinceLastHeartbeat > checkIntervalMs * FAILURE_THRESHOLD

    /** Whether the watchdog should be armed at all. */
    fun isArmed(signal: HealthSignal): Boolean = signal.isUserEnabled

    /**
     * Evaluates one heartbeat.
     *
     * @param consecutiveFailures carried in from the previous decision so the
     *   backoff grows across repeated failures and resets on recovery.
     */
    fun evaluate(
        signal: HealthSignal,
        consecutiveFailures: Int = 0,
        checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS
    ): Decision {
        if (!signal.isUserEnabled) {
            return Decision(Verdict.DISABLED, 0L, 0)
        }

        // Doze defers everything. Restarting a foreground service inside an idle
        // maintenance window is exactly what gets an app flagged by the OEM.
        if (signal.isDeviceIdle) {
            return Decision(Verdict.BLOCKED, checkIntervalMs, consecutiveFailures)
        }

        val serviceDead = !signal.isServiceRunning || isWedged(signal.millisSinceLastHeartbeat, checkIntervalMs)

        if (serviceDead) {
            val failures = consecutiveFailures + 1
            // Refused exemption twice: stop retrying behind the user's back.
            val blockedByPolicy = !signal.hasBatteryExemption &&
                signal.exemptionDeclineCount >= MAX_EXEMPTION_PROMPTS
            return if (blockedByPolicy) {
                Decision(
                    verdict = Verdict.BLOCKED,
                    delayMs = 0L,
                    consecutiveFailures = failures,
                    requiresUserAction = true
                )
            } else {
                Decision(
                    verdict = Verdict.RESTART_SERVICE,
                    delayMs = backoffFor(failures),
                    consecutiveFailures = failures
                )
            }
        }

        // Service is alive; check its two dependencies before declaring health.
        if (!signal.isOverlayAttached) {
            return Decision(
                verdict = Verdict.REATTACH_OVERLAY,
                delayMs = checkIntervalMs,
                consecutiveFailures = 0
            )
        }

        if (!signal.isListenerConnected) {
            // Revoked notification access cannot be restored programmatically.
            // Doc 1 requires retained notification data to be cleared on
            // disconnect; surfacing the settings path is the honest response.
            return Decision(
                verdict = Verdict.LISTENER_DISCONNECTED,
                delayMs = checkIntervalMs,
                consecutiveFailures = 0,
                requiresUserAction = true
            )
        }

        return Decision(Verdict.HEALTHY, checkIntervalMs, 0)
    }

    /**
     * Whether the exemption prompt may be shown again. Returning false here is
     * what keeps onboarding from becoming a nag loop.
     */
    fun mayPromptForBatteryExemption(signal: HealthSignal): Boolean =
        signal.isUserEnabled && !signal.hasBatteryExemption && signal.exemptionDeclineCount < MAX_EXEMPTION_PROMPTS

    /**
     * Next heartbeat delay when healthy, aligned to the minute boundary so the
     * alarm does not drift into a per-second cadence after many reschedules.
     */
    fun nextHealthyDelayMs(nowMillis: Long, checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS): Long {
        if (checkIntervalMs <= 0L) return DEFAULT_CHECK_INTERVAL_MS
        val remainder = nowMillis % checkIntervalMs
        return if (remainder == 0L) checkIntervalMs else checkIntervalMs - remainder
    }
}
