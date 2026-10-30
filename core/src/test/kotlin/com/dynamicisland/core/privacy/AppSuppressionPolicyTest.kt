package com.dynamicisland.core.privacy

import com.dynamicisland.core.privacy.AppSuppressionPolicy.ForegroundAppState
import com.dynamicisland.core.privacy.AppSuppressionPolicy.PackageRule
import com.dynamicisland.core.privacy.AppSuppressionPolicy.SuppressionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G3**: the previously dead `hideInFullscreenApps` setting, and the
 * per-app "Hide Notch in Apps" matrix that is distinct from notification
 * filtering.
 */
class AppSuppressionPolicyTest {

    private val game = ForegroundAppState(packageName = "com.example.game", isImmersiveFullscreen = true)
    private val player = ForegroundAppState(packageName = "com.example.player", isPlayingVideo = true)
    private val messenger = ForegroundAppState(packageName = "com.example.chat")

    @Test
    fun `full screen auto-hide now suppresses the overlay`() {
        val decision = AppSuppressionPolicy.resolve(
            foreground = game,
            packageRules = emptyMap(),
            autoHideInFullscreen = true
        )
        assertTrue(decision.suppressesOverlay)
        assertEquals(SuppressionReason.GLOBAL_FULLSCREEN_IMMERSIVE, decision.reason)
        assertFalse(AppSuppressionPolicy.mayShowIdlePill(decision))
        assertFalse(AppSuppressionPolicy.mayShowRoutineContent(decision))
    }

    @Test
    fun `video playback is its own suppression signal`() {
        val decision = AppSuppressionPolicy.resolve(player, emptyMap(), autoHideInFullscreen = true)
        assertEquals(SuppressionReason.GLOBAL_FULLSCREEN_VIDEO, decision.reason)
        assertTrue(decision.hideNonCritical)
    }

    @Test
    fun `an ordinary foreground app is never suppressed`() {
        val decision = AppSuppressionPolicy.resolve(messenger, emptyMap(), autoHideInFullscreen = true)
        assertEquals(AppSuppressionPolicy.Decision.Visible, decision)
        assertTrue(AppSuppressionPolicy.mayShowIdlePill(decision))
    }

    @Test
    fun `the switch being off leaves full screen apps alone`() {
        val decision = AppSuppressionPolicy.resolve(game, emptyMap(), autoHideInFullscreen = false)
        assertFalse(decision.suppressesOverlay)
        assertEquals(null, decision.reason)
    }

    @Test
    fun `a critical alert survives full screen auto-hide`() {
        // Hiding the pill in a game must not swallow an incoming call.
        val decision = AppSuppressionPolicy.resolve(
            foreground = game,
            packageRules = emptyMap(),
            autoHideInFullscreen = true,
            pendingAlertIsCritical = true
        )
        assertFalse(decision.suppressesOverlay)
        assertTrue(AppSuppressionPolicy.mayShowCriticalAlert(decision))
    }

    @Test
    fun `hide always beats even a critical alert`() {
        val decision = AppSuppressionPolicy.resolve(
            foreground = game,
            packageRules = mapOf("com.example.game" to PackageRule.HIDE_ALWAYS),
            autoHideInFullscreen = false,
            pendingAlertIsCritical = true
        )
        assertTrue(decision.hideNothing)
        assertEquals(SuppressionReason.USER_RULE_HIDE_ALWAYS, decision.reason)
        assertFalse("An explicit user rule must win", AppSuppressionPolicy.mayShowCriticalAlert(decision))
    }

    @Test
    fun `hide non critical keeps calls but drops routine content`() {
        val rules = mapOf("com.example.game" to PackageRule.HIDE_NON_CRITICAL)

        val routine = AppSuppressionPolicy.resolve(game, rules, autoHideInFullscreen = false)
        assertTrue(routine.hideNonCritical)
        assertFalse(routine.hideNothing)
        assertFalse(AppSuppressionPolicy.mayShowRoutineContent(routine))
        assertTrue(AppSuppressionPolicy.mayShowCriticalAlert(routine))

        val critical = AppSuppressionPolicy.resolve(
            game,
            rules,
            autoHideInFullscreen = false,
            pendingAlertIsCritical = true
        )
        assertFalse(critical.suppressesOverlay)
    }

    @Test
    fun `an explicit rule wins over the global heuristic`() {
        // ALLOW means "normal behaviour", so the global switch still applies...
        val allowed = AppSuppressionPolicy.resolve(
            game,
            mapOf("com.example.game" to PackageRule.ALLOW),
            autoHideInFullscreen = true
        )
        assertEquals(SuppressionReason.GLOBAL_FULLSCREEN_IMMERSIVE, allowed.reason)

        // ...but HIDE_ALWAYS applies even with the global switch off.
        val always = AppSuppressionPolicy.resolve(
            messenger,
            mapOf("com.example.chat" to PackageRule.HIDE_ALWAYS),
            autoHideInFullscreen = false
        )
        assertTrue(always.suppressesOverlay)
    }

    @Test
    fun `a disabled island or dark screen is never evaluated`() {
        val disabled = AppSuppressionPolicy.resolve(game, emptyMap(), autoHideInFullscreen = true, islandEnabled = false)
        assertFalse(disabled.suppressesOverlay)

        val screenOff = AppSuppressionPolicy.resolve(
            game.copy(isScreenOn = false),
            emptyMap(),
            autoHideInFullscreen = true
        )
        assertFalse(screenOff.suppressesOverlay)

        val noForeground = AppSuppressionPolicy.resolve(null, emptyMap(), autoHideInFullscreen = true)
        assertFalse(noForeground.suppressesOverlay)
    }

    @Test
    fun `sanitize drops blank package keys that would silently match nothing`() {
        val sanitized = AppSuppressionPolicy.sanitize(
            mapOf(
                "" to PackageRule.HIDE_ALWAYS,
                "   " to PackageRule.HIDE_ALWAYS,
                " com.example.game " to PackageRule.HIDE_NON_CRITICAL
            )
        )
        assertEquals(1, sanitized.size)
        assertEquals(PackageRule.HIDE_NON_CRITICAL, sanitized["com.example.game"])
    }
}
