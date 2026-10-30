package com.dynamicisland.core.system

import com.dynamicisland.core.system.ServiceWatchdogPolicy.HealthSignal
import com.dynamicisland.core.system.ServiceWatchdogPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G6**: a foreground-service watchdog that notices a killed or
 * wedged overlay service, without violating doc 1's "zero recurring wakeups
 * solely for static island content".
 */
class ServiceWatchdogPolicyTest {

    private val healthy = HealthSignal(
        isServiceRunning = true,
        isOverlayAttached = true,
        isListenerConnected = true,
        millisSinceLastHeartbeat = 1_000L,
        hasBatteryExemption = true
    )

    @Test
    fun `a healthy service does nothing and reports a coarse interval`() {
        val decision = ServiceWatchdogPolicy.evaluate(healthy)
        assertEquals(Verdict.HEALTHY, decision.verdict)
        assertEquals(0, decision.consecutiveFailures)
        assertEquals(ServiceWatchdogPolicy.DEFAULT_CHECK_INTERVAL_MS, decision.delayMs)
        assertFalse(decision.requiresUserAction)
    }

    @Test
    fun `backoff doubles from fifteen seconds and caps at one hour`() {
        assertEquals(0L, ServiceWatchdogPolicy.backoffFor(0))
        assertEquals(15_000L, ServiceWatchdogPolicy.backoffFor(1))
        assertEquals(30_000L, ServiceWatchdogPolicy.backoffFor(2))
        assertEquals(60_000L, ServiceWatchdogPolicy.backoffFor(3))
        assertEquals(120_000L, ServiceWatchdogPolicy.backoffFor(4))
        assertEquals(ServiceWatchdogPolicy.MAX_BACKOFF_MS, ServiceWatchdogPolicy.backoffFor(40))
        // Negative input must not produce a negative delay.
        assertEquals(0L, ServiceWatchdogPolicy.backoffFor(-5))
    }

    @Test
    fun `a missing service triggers a restart with growing backoff`() {
        val first = ServiceWatchdogPolicy.evaluate(healthy.copy(isServiceRunning = false))
        assertEquals(Verdict.RESTART_SERVICE, first.verdict)
        assertEquals(1, first.consecutiveFailures)
        assertEquals(15_000L, first.delayMs)

        val second = ServiceWatchdogPolicy.evaluate(
            healthy.copy(isServiceRunning = false),
            consecutiveFailures = first.consecutiveFailures
        )
        assertEquals(2, second.consecutiveFailures)
        assertEquals(30_000L, second.delayMs)
    }

    @Test
    fun `a wedged service is detected from missed heartbeats, not one slow frame`() {
        val interval = ServiceWatchdogPolicy.DEFAULT_CHECK_INTERVAL_MS
        assertFalse(ServiceWatchdogPolicy.isWedged(interval, interval))
        assertFalse(ServiceWatchdogPolicy.isWedged(interval * 2, interval))
        assertTrue(ServiceWatchdogPolicy.isWedged((interval * 2) + 1, interval))

        val wedged = ServiceWatchdogPolicy.evaluate(
            healthy.copy(isServiceRunning = true, millisSinceLastHeartbeat = interval * 5)
        )
        assertEquals(Verdict.RESTART_SERVICE, wedged.verdict)
    }

    @Test
    fun `recovery resets the failure counter`() {
        val recovered = ServiceWatchdogPolicy.evaluate(healthy, consecutiveFailures = 4)
        assertEquals(Verdict.HEALTHY, recovered.verdict)
        assertEquals(0, recovered.consecutiveFailures)
    }

    @Test
    fun `a detached overlay reattaches instead of restarting the whole service`() {
        val decision = ServiceWatchdogPolicy.evaluate(healthy.copy(isOverlayAttached = false))
        assertEquals(Verdict.REATTACH_OVERLAY, decision.verdict)
        assertEquals(0, decision.consecutiveFailures)
        assertFalse(decision.requiresUserAction)
    }

    @Test
    fun `revoked notification access asks the user rather than retrying`() {
        // Doc 1: retained notification data must be cleared when listener access
        // disconnects, and nothing can restore it programmatically.
        val decision = ServiceWatchdogPolicy.evaluate(healthy.copy(isListenerConnected = false))
        assertEquals(Verdict.LISTENER_DISCONNECTED, decision.verdict)
        assertTrue(decision.requiresUserAction)
    }

    @Test
    fun `a user-disabled island is never restarted`() {
        val decision = ServiceWatchdogPolicy.evaluate(
            healthy.copy(isUserEnabled = false, isServiceRunning = false)
        )
        assertEquals(Verdict.DISABLED, decision.verdict)
        assertEquals(0L, decision.delayMs)
        assertFalse(ServiceWatchdogPolicy.isArmed(healthy.copy(isUserEnabled = false)))
    }

    @Test
    fun `doze defers the restart instead of fighting the platform`() {
        val decision = ServiceWatchdogPolicy.evaluate(
            healthy.copy(isDeviceIdle = true, isServiceRunning = false)
        )
        assertEquals(Verdict.BLOCKED, decision.verdict)
        assertFalse(decision.requiresUserAction)
    }

    @Test
    fun `retries stop after the exemption has been refused twice`() {
        val refused = healthy.copy(
            isServiceRunning = false,
            hasBatteryExemption = false,
            exemptionDeclineCount = ServiceWatchdogPolicy.MAX_EXEMPTION_PROMPTS
        )
        val decision = ServiceWatchdogPolicy.evaluate(refused)
        assertEquals(Verdict.BLOCKED, decision.verdict)
        assertTrue("The UI must explain this, not retry silently", decision.requiresUserAction)
        assertFalse(ServiceWatchdogPolicy.mayPromptForBatteryExemption(refused))
    }

    @Test
    fun `exemption prompting is allowed only while the island is on and unrefused`() {
        assertTrue(ServiceWatchdogPolicy.mayPromptForBatteryExemption(healthy.copy(hasBatteryExemption = false)))
        assertFalse(ServiceWatchdogPolicy.mayPromptForBatteryExemption(healthy))
        assertFalse(
            ServiceWatchdogPolicy.mayPromptForBatteryExemption(healthy.copy(isUserEnabled = false))
        )
        assertFalse(
            ServiceWatchdogPolicy.mayPromptForBatteryExemption(
                healthy.copy(hasBatteryExemption = false, exemptionDeclineCount = 2)
            )
        )
    }

    @Test
    fun `healthy reschedules align to the interval so cadence cannot drift`() {
        val interval = ServiceWatchdogPolicy.DEFAULT_CHECK_INTERVAL_MS
        assertEquals(interval, ServiceWatchdogPolicy.nextHealthyDelayMs(0L, interval))
        assertEquals(30_000L, ServiceWatchdogPolicy.nextHealthyDelayMs(30_000L, interval))
        assertEquals(1L, ServiceWatchdogPolicy.nextHealthyDelayMs(interval - 1L, interval))
        // A non-positive interval must fall back rather than schedule a zero delay.
        assertEquals(interval, ServiceWatchdogPolicy.nextHealthyDelayMs(12_345L, 0L))
    }
}
