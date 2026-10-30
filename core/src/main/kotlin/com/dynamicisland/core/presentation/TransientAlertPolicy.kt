package com.dynamicisland.core.presentation

/**
 * Gap **G1 + G2** — the transient "alerting update" state.
 *
 * Both research papers require the same behaviour and the imported base had
 * neither half of it:
 *
 *  * *Displaying live data with Live Activities* — an update carrying an alert
 *    configuration does **not** fire a banner; the island expands the activity
 *    for "a couple of seconds" and then collapses back into its compact or
 *    minimal footprint. The deep-research report puts that window at
 *    **1.8–3.0 s**.
 *  * The gesture table requires **swiping away a transient alert to suppress
 *    that instance only, never the underlying ongoing activity**.
 *
 * The second rule is the one naive implementations get wrong: dismissing the
 * "Driver arriving" peek must not end the ride-share journey that produced it.
 * [SwipeDismissal] therefore returns the alerts to drop and the activities to
 * retain as separate lists, so a caller cannot accidentally conflate them.
 *
 * Pure JVM by design: no clock, no Android, no randomness. Every function takes
 * `nowMillis` explicitly so arbitration stays deterministic and unit-testable,
 * matching the rest of `:core`.
 */
object TransientAlertPolicy {

    /** Lower bound of the Apple alerting-update window. */
    const val MIN_AUTO_EXPAND_MS: Long = 1_800L

    /** Upper bound of the Apple alerting-update window. */
    const val MAX_AUTO_EXPAND_MS: Long = 3_000L

    /** "A couple of seconds" — the default used when a source does not specify. */
    const val DEFAULT_AUTO_EXPAND_MS: Long = 2_000L

    /**
     * How long a user's swipe-away silences repeats of the same alert key.
     * Long enough to stop a chatty source re-popping, short enough that a
     * genuinely new turn instruction still gets through.
     */
    const val DEFAULT_SUPPRESSION_COOLDOWN_MS: Long = 60_000L

    /**
     * Bound on the suppression ledger. This object lives inside a foreground
     * service that can run for days; an unbounded map keyed by alert id would
     * grow forever. Oldest entries are pruned first.
     */
    const val MAX_TRACKED_SUPPRESSIONS: Int = 32

    /**
     * Mirrors ActivityKit's alert importance. Only [TIME_SENSITIVE] and
     * [CRITICAL] are allowed to interrupt the current presentation.
     */
    enum class AlertImportance {
        /** Routine content refresh. Never auto-expands. */
        PASSIVE,

        /** Ordinary update. Auto-expands only when nothing better is showing. */
        DEFAULT,

        /** The normal case for navigation turns, arrivals and timer completion. */
        TIME_SENSITIVE,

        /** Incoming call, alarm, low battery. Ignores quiet hours. */
        CRITICAL
    }

    /** The update payload's own request, analogous to `AlertConfiguration`. */
    data class AlertConfiguration(
        val alertId: String,
        val importance: AlertImportance = AlertImportance.DEFAULT,
        /** Requested window; clamped into [MIN_AUTO_EXPAND_MS]..[MAX_AUTO_EXPAND_MS]. */
        val autoExpandMs: Long = DEFAULT_AUTO_EXPAND_MS,
        /**
         * True when the new payload carries no user-visible change (same ETA,
         * same progress bucket). Repeats must not re-expand — doc 1 lists
         * "update only when new information exists" as a battery requirement.
         */
        val isRepeatOfPreviousPayload: Boolean = false,
        /** Stable key for swipe suppression, e.g. `"navigation:turn"`. */
        val suppressionKey: String = alertId
    )

    /** Why an alerting update was *not* allowed to expand the island. */
    enum class SuppressReason {
        /** Importance below [AlertImportance.TIME_SENSITIVE]. */
        NOT_ALERTING,

        /** Payload identical to what is already on screen. */
        REPEATED_PAYLOAD,

        /** User swiped this key away and the cooldown has not elapsed. */
        USER_SUPPRESSED,

        /** Device is on the keyguard; content is redacted instead. */
        LOCKED,

        /** Quiet hours are active and this is not [AlertImportance.CRITICAL]. */
        QUIET_HOURS,

        /**
         * The user is already holding the island open. Interrupting an explicit
         * interaction to auto-expand would fight them; the update is merged into
         * the open card instead.
         */
        USER_ALREADY_EXPANDED
    }

    /** Result of arbitrating one alerting update. */
    data class AutoExpandDecision(
        val shouldAutoExpand: Boolean,
        /** Clamped duration; 0 when [shouldAutoExpand] is false. */
        val expandDurationMs: Long,
        val reason: SuppressReason? = null,
        /**
         * Set when the system Reduce Motion flag is on: cross-fade into the
         * expanded card rather than springing. Reduce Motion changes *how* the
         * alert appears, never *whether* it does.
         */
        val useCrossfadeInsteadOfSpring: Boolean = false
    ) {
        companion object {
            val NoExpansion = AutoExpandDecision(
                shouldAutoExpand = false,
                expandDurationMs = 0L,
                reason = SuppressReason.NOT_ALERTING
            )
        }
    }

    /** Clamps a requested window into the documented Apple range. */
    fun clampAutoExpand(requestedMs: Long): Long =
        requestedMs.coerceIn(MIN_AUTO_EXPAND_MS, MAX_AUTO_EXPAND_MS)

    /**
     * Decides whether an incoming update should transiently expand the island.
     *
     * @param suppressionLedger map of suppression key to expiry epoch millis,
     *   as produced by [suppressInstance].
     */
    fun resolveAutoExpand(
        configuration: AlertConfiguration,
        nowMillis: Long,
        suppressionLedger: Map<String, Long> = emptyMap(),
        isLocked: Boolean = false,
        quietHoursActive: Boolean = false,
        userIsHoldingExpanded: Boolean = false,
        reduceMotion: Boolean = false
    ): AutoExpandDecision {
        // PASSIVE is a routine content refresh and must never interrupt.
        // DEFAULT is handled by the `alerting` gate at the bottom of this
        // function, so only TIME_SENSITIVE and CRITICAL reach an expansion.
        if (configuration.importance == AlertImportance.PASSIVE) {
            return AutoExpandDecision(false, 0L, SuppressReason.NOT_ALERTING, reduceMotion)
        }

        if (configuration.isRepeatOfPreviousPayload) {
            return AutoExpandDecision(false, 0L, SuppressReason.REPEATED_PAYLOAD, reduceMotion)
        }

        if (isSuppressed(suppressionLedger, configuration.suppressionKey, nowMillis)) {
            return AutoExpandDecision(false, 0L, SuppressReason.USER_SUPPRESSED, reduceMotion)
        }

        // A locked device shows redacted content only; expanding would expose
        // the payload to a bystander. Falls back to the Lock Screen banner path.
        if (isLocked) {
            return AutoExpandDecision(false, 0L, SuppressReason.LOCKED, reduceMotion)
        }

        if (quietHoursActive && configuration.importance != AlertImportance.CRITICAL) {
            return AutoExpandDecision(false, 0L, SuppressReason.QUIET_HOURS, reduceMotion)
        }

        // Never yank the island out from under an active long-press. The update
        // is merged into the already-open card by the caller.
        if (userIsHoldingExpanded) {
            return AutoExpandDecision(false, 0L, SuppressReason.USER_ALREADY_EXPANDED, reduceMotion)
        }

        val alerting = configuration.importance == AlertImportance.TIME_SENSITIVE ||
            configuration.importance == AlertImportance.CRITICAL

        if (!alerting) {
            return AutoExpandDecision(false, 0L, SuppressReason.NOT_ALERTING, reduceMotion)
        }

        return AutoExpandDecision(
            shouldAutoExpand = true,
            expandDurationMs = clampAutoExpand(configuration.autoExpandMs),
            reason = null,
            useCrossfadeInsteadOfSpring = reduceMotion
        )
    }

    /** True while a swipe-away cooldown for [suppressionKey] is still running. */
    fun isSuppressed(
        suppressionLedger: Map<String, Long>,
        suppressionKey: String,
        nowMillis: Long
    ): Boolean = (suppressionLedger[suppressionKey] ?: return false) > nowMillis

    /**
     * Records a user swipe-away. Expired entries are dropped and the ledger is
     * capped at [MAX_TRACKED_SUPPRESSIONS], keeping the oldest surviving keys.
     */
    fun suppressInstance(
        suppressionLedger: Map<String, Long>,
        suppressionKey: String,
        nowMillis: Long,
        cooldownMs: Long = DEFAULT_SUPPRESSION_COOLDOWN_MS
    ): Map<String, Long> {
        val live = suppressionLedger.filterValues { it > nowMillis }.toMutableMap()
        live[suppressionKey] = nowMillis + cooldownMs.coerceAtLeast(0L)
        if (live.size <= MAX_TRACKED_SUPPRESSIONS) return live
        return live.entries
            .sortedByDescending { it.value }
            .take(MAX_TRACKED_SUPPRESSIONS)
            .associate { it.key to it.value }
    }

    /** How the island should settle after an alert is dismissed. */
    enum class PresentationAfterDismissal {
        /** Return to Compact or Minimal for the activities that remain. */
        RESTORE_UNDERLYING,

        /** Nothing ongoing was underneath; go back to the idle pill. */
        IDLE
    }

    /**
     * The explicit separation demanded by the gesture table: **the alert goes,
     * the activity that produced it stays.**
     *
     * @param underlyingActivityIds ongoing activities present when the alert
     *   popped, in arbitration order.
     * @param alertProducingActivityId the activity that emitted the alert, if it
     *   is itself an ongoing activity (a navigation turn) rather than a
     *   stand-alone system event (charging connected).
     */
    fun resolveSwipeDismissal(
        suppressionLedger: Map<String, Long>,
        alert: AlertConfiguration,
        underlyingActivityIds: List<String>,
        alertProducingActivityId: String?,
        nowMillis: Long,
        cooldownMs: Long = DEFAULT_SUPPRESSION_COOLDOWN_MS
    ): SwipeDismissal {
        // The producing activity is retained even though its alert is silenced.
        val retained = underlyingActivityIds.toMutableList()
        if (alertProducingActivityId != null && alertProducingActivityId !in retained) {
            retained.add(alertProducingActivityId)
        }
        return SwipeDismissal(
            alertIdToRemove = alert.alertId,
            activityIdsToRetain = retained,
            nextPresentation = if (retained.isEmpty()) {
                PresentationAfterDismissal.IDLE
            } else {
                PresentationAfterDismissal.RESTORE_UNDERLYING
            },
            suppressionLedger = suppressInstance(suppressionLedger, alert.suppressionKey, nowMillis, cooldownMs)
        )
    }

    /** Outcome of a swipe-away on a transient alert. */
    data class SwipeDismissal(
        val alertIdToRemove: String,
        val activityIdsToRetain: List<String>,
        val nextPresentation: PresentationAfterDismissal,
        val suppressionLedger: Map<String, Long>
    )
}
