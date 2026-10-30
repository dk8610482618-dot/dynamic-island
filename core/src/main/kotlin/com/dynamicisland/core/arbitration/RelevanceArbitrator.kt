package com.dynamicisland.core.arbitration

/**
 * Pure JVM ActivityKit-grade Priority + Relevance Score Arbiter.
 *
 * Implements:
 * - Tiered base priority + `relevanceScore` + `interruptBoost` sorting
 * - Stale activity de-emphasis (Tier 3 demotion)
 * - FIFO (`startedAtMillis`) and deterministic `id` tie-breaking
 * - Multi-activity mitosis up to 3 concurrent Live Activities (iPhone 18 Pro standard)
 * - Horizontal swipe focus cycling (`focusOffset`)
 */
object RelevanceArbitrator {
    const val PRIORITY_INCOMING_CALL = 1000
    const val PRIORITY_NAVIGATION_MANEUVER_IMMINENT = 900
    const val PRIORITY_TIMER_COMPLETING = 850
    const val PRIORITY_ACTIVE_CALL = 800
    const val PRIORITY_NAVIGATION_ONGOING = 700
    const val PRIORITY_ACTIVE_RECORDING = 650
    const val PRIORITY_MEDIA_PLAYING = 500
    const val PRIORITY_RIDESHARE_DELIVERY_JOURNEY = 450
    const val PRIORITY_FILE_DOWNLOAD = 300
    const val PRIORITY_NOTIFICATION_PEEK = 200
    const val PRIORITY_SYSTEM_TRANSIENT = 150

    private const val STALE_PENALTY = 400

    data class Candidate<T>(
        val id: String,
        val item: T,
        val basePriority: Int,
        val relevanceScore: Float = 0f,
        val interruptBoost: Int = 0,
        val startedAtMillis: Long = 0L,
        val isStale: Boolean = false
    ) {
        val effectivePriority: Int
            get() = (basePriority + interruptBoost) - if (isStale) STALE_PENALTY else 0
    }

    /**
     * Ranks all candidates deterministically and returns up to [maxConcurrent] visible items
     * (default 3 for iPhone 18 Pro multi-activity mitosis), rotated by [focusOffset] when the
     * user cycles focus horizontally across active sessions.
     */
    fun <T> chooseVisible(
        candidates: List<Candidate<T>>,
        maxConcurrent: Int = 3,
        focusOffset: Int = 0
    ): List<T> {
        if (candidates.isEmpty() || maxConcurrent <= 0) return emptyList()
        val sorted = candidates.sortedWith(
            compareByDescending<Candidate<T>> { it.effectivePriority }
                .thenByDescending { it.relevanceScore }
                .thenBy { it.startedAtMillis }
                .thenBy { it.id }
        )
        val top = sorted.take(maxConcurrent)
        if (top.size <= 1 || focusOffset == 0) return top.map { it.item }
        // Do not rotate away an incoming/ringing emergency call at priority >= 1000
        if (top.first().effectivePriority >= PRIORITY_INCOMING_CALL) {
            return top.map { it.item }
        }
        val normalizedOffset = ((focusOffset % top.size) + top.size) % top.size
        return (top.drop(normalizedOffset) + top.take(normalizedOffset)).map { it.item }
    }
}
