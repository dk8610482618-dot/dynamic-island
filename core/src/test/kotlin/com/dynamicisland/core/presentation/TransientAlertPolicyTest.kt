package com.dynamicisland.core.presentation

import com.dynamicisland.core.presentation.TransientAlertPolicy.AlertConfiguration
import com.dynamicisland.core.presentation.TransientAlertPolicy.AlertImportance
import com.dynamicisland.core.presentation.TransientAlertPolicy.PresentationAfterDismissal
import com.dynamicisland.core.presentation.TransientAlertPolicy.SuppressReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gaps **G1** (swipe-away suppresses the instance, never the underlying
 * activity) and **G2** (an alerting update auto-expands for ~2 s and then
 * settles back).
 */
class TransientAlertPolicyTest {

    private val now = 1_700_000_000_000L

    private fun config(
        importance: AlertImportance = AlertImportance.TIME_SENSITIVE,
        autoExpandMs: Long = TransientAlertPolicy.DEFAULT_AUTO_EXPAND_MS,
        isRepeat: Boolean = false,
        id: String = "alert-1",
        suppressionKey: String = id
    ) = AlertConfiguration(
        alertId = id,
        importance = importance,
        autoExpandMs = autoExpandMs,
        isRepeatOfPreviousPayload = isRepeat,
        suppressionKey = suppressionKey
    )

    // --------------------------------------------------------------- G2

    @Test
    fun `time sensitive update auto-expands for the documented window`() {
        val decision = TransientAlertPolicy.resolveAutoExpand(config(), now)
        assertTrue("A navigation turn should expand the island", decision.shouldAutoExpand)
        assertEquals(TransientAlertPolicy.DEFAULT_AUTO_EXPAND_MS, decision.expandDurationMs)
        assertEquals(null, decision.reason)
    }

    @Test
    fun `requested expand window is clamped into the apple range`() {
        assertEquals(
            TransientAlertPolicy.MIN_AUTO_EXPAND_MS,
            TransientAlertPolicy.clampAutoExpand(100L)
        )
        assertEquals(
            TransientAlertPolicy.MAX_AUTO_EXPAND_MS,
            TransientAlertPolicy.clampAutoExpand(60_000L)
        )

        val tooShort = TransientAlertPolicy.resolveAutoExpand(config(autoExpandMs = 250L), now)
        assertEquals(TransientAlertPolicy.MIN_AUTO_EXPAND_MS, tooShort.expandDurationMs)

        val tooLong = TransientAlertPolicy.resolveAutoExpand(config(autoExpandMs = 9_000L), now)
        assertEquals(TransientAlertPolicy.MAX_AUTO_EXPAND_MS, tooLong.expandDurationMs)
    }

    @Test
    fun `critical importance expands even during quiet hours`() {
        val blocked = TransientAlertPolicy.resolveAutoExpand(
            config(importance = AlertImportance.TIME_SENSITIVE),
            now,
            quietHoursActive = true
        )
        assertFalse(blocked.shouldAutoExpand)
        assertEquals(SuppressReason.QUIET_HOURS, blocked.reason)

        val allowed = TransientAlertPolicy.resolveAutoExpand(
            config(importance = AlertImportance.CRITICAL),
            now,
            quietHoursActive = true
        )
        assertTrue("An incoming call must break through quiet hours", allowed.shouldAutoExpand)
    }

    @Test
    fun `passive and default importance never interrupt the presentation`() {
        val passive = TransientAlertPolicy.resolveAutoExpand(config(importance = AlertImportance.PASSIVE), now)
        assertFalse(passive.shouldAutoExpand)
        assertEquals(SuppressReason.NOT_ALERTING, passive.reason)

        val ordinary = TransientAlertPolicy.resolveAutoExpand(config(importance = AlertImportance.DEFAULT), now)
        assertFalse(ordinary.shouldAutoExpand)
        assertEquals(SuppressReason.NOT_ALERTING, ordinary.reason)
    }

    @Test
    fun `a repeated payload does not re-expand`() {
        // Doc 1: "updating Live Activity content only when new information exists".
        val decision = TransientAlertPolicy.resolveAutoExpand(config(isRepeat = true), now)
        assertFalse(decision.shouldAutoExpand)
        assertEquals(SuppressReason.REPEATED_PAYLOAD, decision.reason)
    }

    @Test
    fun `locked device does not auto-expand`() {
        val decision = TransientAlertPolicy.resolveAutoExpand(config(), now, isLocked = true)
        assertFalse(decision.shouldAutoExpand)
        assertEquals(SuppressReason.LOCKED, decision.reason)
    }

    @Test
    fun `an open long press is never yanked away by an auto-expand`() {
        val decision = TransientAlertPolicy.resolveAutoExpand(config(), now, userIsHoldingExpanded = true)
        assertFalse(decision.shouldAutoExpand)
        assertEquals(SuppressReason.USER_ALREADY_EXPANDED, decision.reason)
    }

    @Test
    fun `reduce motion cross-fades but still expands`() {
        // Reduce Motion changes how the alert appears, never whether it does.
        val decision = TransientAlertPolicy.resolveAutoExpand(config(), now, reduceMotion = true)
        assertTrue(decision.shouldAutoExpand)
        assertTrue(decision.useCrossfadeInsteadOfSpring)

        val spring = TransientAlertPolicy.resolveAutoExpand(config(), now, reduceMotion = false)
        assertFalse(spring.useCrossfadeInsteadOfSpring)
    }

    // --------------------------------------------------------------- G1

    @Test
    fun `swiping away an alert keeps the activity that produced it`() {
        val dismissal = TransientAlertPolicy.resolveSwipeDismissal(
            suppressionLedger = emptyMap(),
            alert = config(id = "nav-turn-7", suppressionKey = "navigation:turn"),
            underlyingActivityIds = listOf("journey-42"),
            alertProducingActivityId = "journey-42",
            nowMillis = now
        )

        assertEquals("nav-turn-7", dismissal.alertIdToRemove)
        assertEquals(
            "The journey must survive its own dismissed alert",
            listOf("journey-42"),
            dismissal.activityIdsToRetain
        )
        assertEquals(PresentationAfterDismissal.RESTORE_UNDERLYING, dismissal.nextPresentation)
        assertTrue(
            TransientAlertPolicy.isSuppressed(dismissal.suppressionLedger, "navigation:turn", now)
        )
    }

    @Test
    fun `swiping away a stand-alone system event with nothing underneath returns to idle`() {
        val dismissal = TransientAlertPolicy.resolveSwipeDismissal(
            suppressionLedger = emptyMap(),
            alert = config(id = "charging", suppressionKey = "system:charging"),
            underlyingActivityIds = emptyList(),
            alertProducingActivityId = null,
            nowMillis = now
        )

        assertEquals(PresentationAfterDismissal.IDLE, dismissal.nextPresentation)
        assertTrue(dismissal.activityIdsToRetain.isEmpty())
    }

    @Test
    fun `a suppressed key blocks repeats until the cooldown elapses`() {
        val ledger = TransientAlertPolicy.suppressInstance(emptyMap(), "navigation:turn", now)

        val blocked = TransientAlertPolicy.resolveAutoExpand(
            config(suppressionKey = "navigation:turn"),
            now + 10_000L,
            suppressionLedger = ledger
        )
        assertFalse(blocked.shouldAutoExpand)
        assertEquals(SuppressReason.USER_SUPPRESSED, blocked.reason)

        val afterCooldown = TransientAlertPolicy.resolveAutoExpand(
            config(suppressionKey = "navigation:turn"),
            now + TransientAlertPolicy.DEFAULT_SUPPRESSION_COOLDOWN_MS + 1L,
            suppressionLedger = ledger
        )
        assertTrue(
            "A genuinely new turn after the cooldown must still get through",
            afterCooldown.shouldAutoExpand
        )
    }

    @Test
    fun `suppressing one key does not silence a different one`() {
        val ledger = TransientAlertPolicy.suppressInstance(emptyMap(), "navigation:turn", now)
        val other = TransientAlertPolicy.resolveAutoExpand(
            config(suppressionKey = "timer:complete"),
            now,
            suppressionLedger = ledger
        )
        assertTrue(other.shouldAutoExpand)
    }

    @Test
    fun `the suppression ledger is bounded so a long-lived service cannot leak`() {
        var ledger: Map<String, Long> = emptyMap()
        repeat(200) { index ->
            ledger = TransientAlertPolicy.suppressInstance(ledger, "key-$index", now + index)
        }
        assertEquals(TransientAlertPolicy.MAX_TRACKED_SUPPRESSIONS, ledger.size)
        // The freshest keys survive; the oldest are pruned.
        assertTrue(ledger.containsKey("key-199"))
        assertFalse(ledger.containsKey("key-0"))
    }

    @Test
    fun `expired ledger entries are pruned on the next write`() {
        val stale = mapOf("old" to now - 1L)
        val ledger = TransientAlertPolicy.suppressInstance(stale, "new", now)
        assertFalse(ledger.containsKey("old"))
        assertTrue(ledger.containsKey("new"))
    }
}
