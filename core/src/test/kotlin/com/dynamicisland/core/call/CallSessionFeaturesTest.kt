package com.dynamicisland.core.call

import com.dynamicisland.core.call.CallSessionFeatures.CallFeature
import com.dynamicisland.core.call.CallSessionFeatures.CallObservation
import com.dynamicisland.core.call.CallSessionFeatures.ExtraKeys
import com.dynamicisland.core.call.CallSessionFeatures.FeatureOrigin
import com.dynamicisland.core.call.CallSessionFeatures.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G8**. SharePlay, Live Captions and Live Translation are the
 * features in the research most tempting to fabricate, because none of them is
 * observable for another application's call. These tests assert the honesty rule:
 * a row is only ever drawn as active when its state came from the device or from
 * the source app itself.
 */
class CallSessionFeaturesTest {

    private val activeCall = CallObservation(
        isCallActive = true,
        isMuted = true,
        isSpeakerOn = false,
        isOnHold = false,
        isControllable = true,
        apiLevel = 34
    )

    private fun feature(states: List<CallSessionFeatures.FeatureState>, feature: CallFeature) =
        states.first { it.feature == feature }

    @Test
    fun `mute speaker and hold come from real device state`() {
        val states = CallSessionFeatures.resolve(activeCall)

        val mute = feature(states, CallFeature.MICROPHONE_MUTE)
        assertEquals(FeatureOrigin.DEVICE_STATE, mute.origin)
        assertTrue(mute.isActive)
        assertTrue(mute.mayRenderAsActive)
        assertTrue("Controllable calls may be toggled from the island", mute.isActionable)

        assertFalse(feature(states, CallFeature.SPEAKER_ROUTING).isActive)
        assertFalse(feature(states, CallFeature.HOLD).isActive)
    }

    @Test
    fun `toggles are not actionable without the dialer role`() {
        // Doc 1: full call control needs ROLE_DIALER and must not be assumed.
        val states = CallSessionFeatures.resolve(activeCall.copy(isControllable = false))
        assertFalse(feature(states, CallFeature.MICROPHONE_MUTE).isActionable)
        assertFalse(feature(states, CallFeature.SPEAKER_ROUTING).isActionable)
        assertFalse(feature(states, CallFeature.HOLD).isActionable)
    }

    @Test
    fun `no active call makes every feature unavailable`() {
        val states = CallSessionFeatures.resolve(activeCall.copy(isCallActive = false))
        assertEquals(CallFeature.values().size, states.size)
        assertTrue(states.all { it.origin == FeatureOrigin.UNAVAILABLE })
        assertTrue(states.all { it.unavailableReason == UnavailableReason.NO_ACTIVE_CALL })
        assertTrue(CallSessionFeatures.activeFeatures(states).isEmpty())
    }

    @Test
    fun `shareplay is only shown when the source app advertises it`() {
        val silent = feature(CallSessionFeatures.resolve(activeCall), CallFeature.SHAREPLAY_SESSION)
        assertEquals(FeatureOrigin.UNAVAILABLE, silent.origin)
        assertEquals(UnavailableReason.NOT_ADVERTISED_BY_SOURCE, silent.unavailableReason)
        assertFalse(silent.mayRenderAsActive)
        assertFalse(silent.mayRenderAtAll)

        val advertised = feature(
            CallSessionFeatures.resolve(
                activeCall.copy(advertisedExtraKeys = setOf(ExtraKeys.SHAREPLAY_SESSION_ACTIVE)),
                sessionLabel = "Watching with 2 people"
            ),
            CallFeature.SHAREPLAY_SESSION
        )
        assertEquals(FeatureOrigin.SOURCE_ADVERTISED, advertised.origin)
        assertTrue(advertised.mayRenderAsActive)
        assertEquals("Watching with 2 people", advertised.sourceLabel)
        assertFalse("We can report a session, not control it", advertised.isActionable)
    }

    @Test
    fun `live translation has no public api and is never inferred`() {
        val unavailable = feature(CallSessionFeatures.resolve(activeCall), CallFeature.LIVE_TRANSLATION)
        assertEquals(UnavailableReason.NO_PUBLIC_API, unavailable.unavailableReason)
        assertFalse(unavailable.mayRenderAsActive)

        val advertised = feature(
            CallSessionFeatures.resolve(
                activeCall.copy(advertisedExtraKeys = setOf(ExtraKeys.LIVE_TRANSLATION_ACTIVE))
            ),
            CallFeature.LIVE_TRANSLATION
        )
        assertEquals(FeatureOrigin.SOURCE_ADVERTISED, advertised.origin)
        assertTrue(advertised.mayRenderAsActive)
    }

    @Test
    fun `live captions distinguish a running session from a system preference`() {
        // Source advertised: a real running session.
        val advertised = feature(
            CallSessionFeatures.resolve(
                activeCall.copy(advertisedExtraKeys = setOf(ExtraKeys.LIVE_CAPTIONS_ACTIVE))
            ),
            CallFeature.LIVE_CAPTIONS
        )
        assertEquals(FeatureOrigin.SOURCE_ADVERTISED, advertised.origin)
        assertTrue(advertised.isActive)

        // Only the system preference readable: report it, but NOT as active.
        val preferenceOnly = feature(
            CallSessionFeatures.resolve(activeCall.copy(systemCaptioningEnabled = true)),
            CallFeature.LIVE_CAPTIONS
        )
        assertEquals(FeatureOrigin.DEVICE_STATE, preferenceOnly.origin)
        assertFalse(
            "A system caption preference is not a running caption session",
            preferenceOnly.isActive
        )
        assertFalse(preferenceOnly.mayRenderAsActive)
        assertEquals("System captions on", preferenceOnly.sourceLabel)

        // Below API 30 there is no Live Caption framework at all.
        val oldApi = feature(
            CallSessionFeatures.resolve(
                activeCall.copy(apiLevel = 29, systemCaptioningEnabled = true)
            ),
            CallFeature.LIVE_CAPTIONS
        )
        assertEquals(UnavailableReason.API_LEVEL_TOO_LOW, oldApi.unavailableReason)

        // Nothing known: unavailable rather than a guessed "off".
        val unknown = feature(CallSessionFeatures.resolve(activeCall), CallFeature.LIVE_CAPTIONS)
        assertEquals(FeatureOrigin.UNAVAILABLE, unknown.origin)
    }

    @Test
    fun `screen share and remote control are advertise-gated`() {
        val states = CallSessionFeatures.resolve(
            activeCall.copy(
                advertisedExtraKeys = setOf(
                    ExtraKeys.SCREEN_SHARE_ACTIVE,
                    ExtraKeys.REMOTE_CONTROL_REQUESTED
                )
            )
        )
        assertEquals(FeatureOrigin.SOURCE_ADVERTISED, feature(states, CallFeature.SCREEN_SHARE).origin)
        assertEquals(
            FeatureOrigin.SOURCE_ADVERTISED,
            feature(states, CallFeature.REMOTE_CONTROL_REQUEST).origin
        )

        val plain = CallSessionFeatures.resolve(activeCall)
        assertFalse(feature(plain, CallFeature.SCREEN_SHARE).mayRenderAsActive)
        assertFalse(feature(plain, CallFeature.REMOTE_CONTROL_REQUEST).mayRenderAsActive)
    }

    @Test
    fun `unavailable rows are filtered out of the visible set`() {
        val states = CallSessionFeatures.resolve(activeCall)
        val visible = CallSessionFeatures.visibleFeatures(states)

        // Mute, speaker and hold are always real; the four app-dependent rows
        // are not advertised here, so they must drop out.
        assertEquals(3, visible.size)
        assertTrue(visible.all { it.origin != FeatureOrigin.UNAVAILABLE })
        assertTrue(visible.any { it.feature == CallFeature.MICROPHONE_MUTE })
        assertFalse(visible.any { it.feature == CallFeature.SHAREPLAY_SESSION })
    }

    @Test
    fun `active features never include an unverified row`() {
        val states = CallSessionFeatures.resolve(
            activeCall.copy(
                isMuted = true,
                advertisedExtraKeys = setOf(ExtraKeys.SHAREPLAY_SESSION_ACTIVE)
            )
        )
        val active = CallSessionFeatures.activeFeatures(states).map { it.feature }.toSet()
        assertEquals(setOf(CallFeature.MICROPHONE_MUTE, CallFeature.SHAREPLAY_SESSION), active)
    }

    @Test
    fun `the expanded pane title reads as a stable state description`() {
        // Doc 1: expandedPaneTitle independent of the visual string, and no
        // per-tick announcements — so the duration is not part of the title.
        assertEquals(
            "Active call with Mum",
            CallSessionFeatures.paneTitle("Mum", null)
        )
        assertEquals(
            "Active WhatsApp call with Mum",
            CallSessionFeatures.paneTitle("Mum", "WhatsApp")
        )
        assertEquals(
            "Active call with Unknown caller",
            CallSessionFeatures.paneTitle(null, "  ")
        )
    }
}
