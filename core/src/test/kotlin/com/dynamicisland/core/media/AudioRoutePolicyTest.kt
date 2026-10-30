package com.dynamicisland.core.media

import com.dynamicisland.core.media.AudioRoutePolicy.AudioRouteKind
import com.dynamicisland.core.media.AudioRoutePolicy.FallbackAction
import com.dynamicisland.core.media.AudioRoutePolicy.RouteContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G9**. The base displayed `audioRouteLabel` but offered no way to
 * change it. These tests pin the distinction that matters: Android lets an app
 * route **call** audio on API 31+, but never lets one app reroute **another
 * app's media**. A row that cannot work must not be offered as a control.
 */
class AudioRoutePolicyTest {

    @Test
    fun `call audio is routable on api 31 plus when the call is controllable`() {
        val capability = AudioRoutePolicy.resolveCapability(
            context = RouteContext.CALL,
            apiLevel = 31,
            isControllableCall = true
        )
        assertTrue(capability.canChangeRoute)
        assertEquals(FallbackAction.NONE, capability.fallback)
        assertEquals(null, capability.limitation)
    }

    @Test
    fun `call audio below api 31 falls back to system settings`() {
        val capability = AudioRoutePolicy.resolveCapability(RouteContext.CALL, apiLevel = 30, isControllableCall = true)
        assertFalse(capability.canChangeRoute)
        assertEquals(FallbackAction.SYSTEM_SETTINGS, capability.fallback)
        assertTrue(capability.limitation!!.contains("Android 12"))
    }

    @Test
    fun `a third party call we do not control is never routable`() {
        val capability = AudioRoutePolicy.resolveCapability(RouteContext.CALL, apiLevel = 34, isControllableCall = false)
        assertFalse(capability.canChangeRoute)
        assertEquals(FallbackAction.SYSTEM_SETTINGS, capability.fallback)
        assertTrue(capability.limitation!!.isNotBlank())
    }

    @Test
    fun `media routing is never routable and prefers the player's own sheet`() {
        val withSheet = AudioRoutePolicy.resolveCapability(
            context = RouteContext.MEDIA,
            apiLevel = 34,
            sourceAppExposesRoutingSheet = true
        )
        assertFalse(withSheet.canChangeRoute)
        assertEquals(FallbackAction.SOURCE_APP_SHEET, withSheet.fallback)
        assertTrue(
            withSheet.limitation!!.contains("does not let one app change another app's media output")
        )

        val withoutSheet = AudioRoutePolicy.resolveCapability(RouteContext.MEDIA, apiLevel = 34)
        assertEquals(FallbackAction.SYSTEM_SETTINGS, withoutSheet.fallback)
    }

    @Test
    fun `options follow the system sheet ordering and mark the selection`() {
        val capability = AudioRoutePolicy.resolveCapability(RouteContext.CALL, 34, isControllableCall = true)
        val options = AudioRoutePolicy.buildOptions(
            availableKinds = setOf(
                AudioRouteKind.CAST,
                AudioRouteKind.BLUETOOTH_A2DP,
                AudioRouteKind.BUILTIN_SPEAKER,
                AudioRouteKind.WIRED_HEADPHONES
            ),
            selectedKind = AudioRouteKind.BLUETOOTH_A2DP,
            capability = capability
        )

        assertEquals(
            listOf(
                AudioRouteKind.BUILTIN_SPEAKER,
                AudioRouteKind.WIRED_HEADPHONES,
                AudioRouteKind.BLUETOOTH_A2DP,
                AudioRouteKind.CAST
            ),
            options.map { it.kind }
        )

        val selected = options.first { it.isSelected }
        assertEquals(AudioRouteKind.BLUETOOTH_A2DP, selected.kind)
        assertFalse("Re-selecting the current output is not an action", selected.isSelectable)
        assertTrue(options.filter { !it.isSelected }.all { it.isSelectable })
    }

    @Test
    fun `unavailable devices are omitted and non-routable contexts offer nothing selectable`() {
        val media = AudioRoutePolicy.resolveCapability(RouteContext.MEDIA, 34, sourceAppExposesRoutingSheet = true)
        val options = AudioRoutePolicy.buildOptions(
            availableKinds = setOf(AudioRouteKind.BUILTIN_SPEAKER, AudioRouteKind.BLUETOOTH_A2DP),
            selectedKind = AudioRouteKind.BUILTIN_SPEAKER,
            capability = media
        )
        assertEquals(2, options.size)
        assertTrue(
            "A media row must never look like a working toggle",
            options.none { it.isSelectable }
        )
        assertTrue(options.all { it.isAvailable })
    }

    @Test
    fun `audio device types map onto route kinds`() {
        assertEquals(AudioRouteKind.BUILTIN_SPEAKER, AudioRoutePolicy.fromAudioDeviceType(2))
        assertEquals(AudioRouteKind.BUILTIN_EARPIECE, AudioRoutePolicy.fromAudioDeviceType(1))
        assertEquals(AudioRouteKind.WIRED_HEADSET, AudioRoutePolicy.fromAudioDeviceType(4))
        assertEquals(AudioRouteKind.WIRED_HEADPHONES, AudioRoutePolicy.fromAudioDeviceType(3))
        assertEquals(AudioRouteKind.USB_AUDIO, AudioRoutePolicy.fromAudioDeviceType(11))
        assertEquals(AudioRouteKind.USB_AUDIO, AudioRoutePolicy.fromAudioDeviceType(22))
        assertEquals(AudioRouteKind.BLUETOOTH_A2DP, AudioRoutePolicy.fromAudioDeviceType(8))
        assertEquals(AudioRouteKind.BLUETOOTH_SCO, AudioRoutePolicy.fromAudioDeviceType(7))
        assertEquals(AudioRouteKind.BLUETOOTH_LE_AUDIO, AudioRoutePolicy.fromAudioDeviceType(26))
        assertEquals(AudioRouteKind.HEARING_AID, AudioRoutePolicy.fromAudioDeviceType(23))
        assertEquals(AudioRouteKind.CAST, AudioRoutePolicy.fromAudioDeviceType(9))
        assertEquals(AudioRouteKind.UNKNOWN, AudioRoutePolicy.fromAudioDeviceType(999))
    }

    @Test
    fun `labels avoid apple-only product names`() {
        AudioRouteKind.values().forEach { kind ->
            val label = AudioRoutePolicy.labelFor(kind)
            assertTrue(label.isNotBlank())
            assertFalse("$label leaks AirPlay", label.contains("AirPlay", ignoreCase = true))
        }
    }

    @Test
    fun `accessibility wording states the limitation instead of implying a control`() {
        val routable = AudioRoutePolicy.RouteOption(
            AudioRouteKind.BLUETOOTH_A2DP, "Bluetooth", true, false, true
        )
        assertEquals("Switch output to Bluetooth", AudioRoutePolicy.accessibilityLabel(routable))

        val current = routable.copy(isSelected = true, isSelectable = false)
        assertEquals("Bluetooth, current output", AudioRoutePolicy.accessibilityLabel(current))

        val informational = routable.copy(isSelectable = false)
        assertTrue(AudioRoutePolicy.accessibilityLabel(informational).contains("available"))
    }
}
