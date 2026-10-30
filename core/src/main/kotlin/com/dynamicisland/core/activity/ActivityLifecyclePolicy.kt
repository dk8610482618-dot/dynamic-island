package com.dynamicisland.core.activity

/**
 * Enforces Apple ActivityKit lifecycle guardrails in pure JVM core:
 * 1. `staleDate` evaluation (`isStale = true` when `nowMillis >= staleAtMillis`)
 * 2. 8-hour active session ceiling (`MAX_ACTIVE_DURATION_MS`)
 * 3. 4-hour post-end Lock Screen / Inbox retention (`POST_END_RETENTION_MS`)
 * 4. 4 KB (4,096 bytes) serialized payload size cap (`MAX_PAYLOAD_BYTES`)
 */
object ActivityLifecyclePolicy {
    const val MAX_PAYLOAD_BYTES: Int = 4096
    const val MAX_ACTIVE_DURATION_MS: Long = 8L * 60L * 60L * 1000L
    const val POST_END_RETENTION_MS: Long = 4L * 60L * 60L * 1000L
    const val DEFAULT_STALE_INTERVAL_MS: Long = 30L * 60L * 1000L

    enum class DismissalPolicy {
        /** Vacates Dynamic Island immediately upon end; retained in Lock Screen/Inbox up to 4 hours. */
        DEFAULT,
        /** Vacates both Dynamic Island and Lock Screen/Inbox immediately. */
        IMMEDIATE,
        /** Retained until explicit custom timestamp (clamped to 4-hour ceiling). */
        AFTER_TIMESTAMP
    }

    data class LifecycleSnapshot(
        val isStale: Boolean,
        val isExpiredActiveCeiling: Boolean,
        val isEnded: Boolean,
        val shouldVacateIsland: Boolean,
        val shouldRetainInInbox: Boolean,
        val isPayloadWithinLimit: Boolean,
        val estimatedPayloadBytes: Int
    )

    fun estimatePayloadBytes(
        title: String,
        subtitle: String? = null,
        subText: String? = null,
        actionTitles: List<String> = emptyList(),
        extraMetadataBytes: Int = 0
    ): Int {
        val stringBytes = title.encodeToByteArray().size +
            (subtitle?.encodeToByteArray()?.size ?: 0) +
            (subText?.encodeToByteArray()?.size ?: 0) +
            actionTitles.sumOf { it.encodeToByteArray().size + 16 }
        val fixedHeaderBytes = 64
        return (fixedHeaderBytes + stringBytes + extraMetadataBytes.coerceAtLeast(0))
    }

    fun evaluate(
        startedAtMillis: Long,
        nowMillis: Long,
        staleAtMillis: Long? = null,
        endedAtMillis: Long? = null,
        dismissalPolicy: DismissalPolicy = DismissalPolicy.DEFAULT,
        customRetentionUntilMillis: Long? = null,
        payloadBytes: Int = 64
    ): LifecycleSnapshot {
        val elapsedActive = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        val isExpiredActiveCeiling = elapsedActive >= MAX_ACTIVE_DURATION_MS
        val isStale = staleAtMillis != null && nowMillis >= staleAtMillis
        val isEnded = endedAtMillis != null && nowMillis >= endedAtMillis
        val isPayloadWithinLimit = payloadBytes in 0..MAX_PAYLOAD_BYTES

        val shouldVacateIsland = isEnded || isExpiredActiveCeiling || !isPayloadWithinLimit

        val shouldRetainInInbox = when {
            !isPayloadWithinLimit -> false
            !isEnded -> !isExpiredActiveCeiling
            else -> when (dismissalPolicy) {
                DismissalPolicy.IMMEDIATE -> false
                DismissalPolicy.DEFAULT -> (nowMillis - endedAtMillis!!) < POST_END_RETENTION_MS
                DismissalPolicy.AFTER_TIMESTAMP -> {
                    val maxAllowedEnd = endedAtMillis!! + POST_END_RETENTION_MS
                    val targetEnd = (customRetentionUntilMillis ?: maxAllowedEnd).coerceAtMost(maxAllowedEnd)
                    nowMillis < targetEnd
                }
            }
        }

        return LifecycleSnapshot(
            isStale = isStale,
            isExpiredActiveCeiling = isExpiredActiveCeiling,
            isEnded = isEnded,
            shouldVacateIsland = shouldVacateIsland,
            shouldRetainInInbox = shouldRetainInInbox,
            isPayloadWithinLimit = isPayloadWithinLimit,
            estimatedPayloadBytes = payloadBytes
        )
    }
}
