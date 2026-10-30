package com.dynamicisland.core.media

import com.dynamicisland.core.media.WaveformSourcePolicy.UnavailableReason
import com.dynamicisland.core.media.WaveformSourcePolicy.WaveformSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G7**. The imported base animated the equaliser from a hard-coded
 * amplitude list, which measured nothing. These tests pin both halves of the
 * replacement: real FFT maths when the platform allows it, and a *labelled*
 * synthetic fallback that never claims to be a spectrum.
 */
class WaveformSourcePolicyTest {

    private val fullCapability = Triple(true, true, true) // permission, support, attached

    private fun resolve(
        isVisible: Boolean = true,
        isPlaying: Boolean = true,
        enabled: Boolean = true,
        hasPermission: Boolean = fullCapability.first,
        deviceSupports: Boolean = fullCapability.second,
        attached: Boolean = fullCapability.third,
        apiLevel: Int = 34,
        reduceMotion: Boolean = false
    ) = WaveformSourcePolicy.resolve(
        isVisible = isVisible,
        isPlaying = isPlaying,
        waveformEnabledInSettings = enabled,
        hasRecordAudioPermission = hasPermission,
        deviceSupportsVisualizer = deviceSupports,
        visualizerSessionAttached = attached,
        apiLevel = apiLevel,
        reduceMotion = reduceMotion
    )

    // ---------------------------------------------------- capability gating

    @Test
    fun `real fft is used only when every precondition holds`() {
        val capability = resolve()
        assertEquals(WaveformSource.REAL_FFT, capability.source)
        assertEquals(null, capability.reason)
        assertTrue(capability.mayBeDescribedAsRealSpectrum)
    }

    @Test
    fun `a missing permission falls back to synthetic and says why`() {
        val capability = resolve(hasPermission = false)
        assertEquals(WaveformSource.SYNTHETIC, capability.source)
        assertEquals(UnavailableReason.NO_RECORD_AUDIO_PERMISSION, capability.reason)
        assertFalse(
            "Synthetic bars must never be described as a real spectrum",
            capability.mayBeDescribedAsRealSpectrum
        )
    }

    @Test
    fun `an unsupported device and a restricted session both fall back`() {
        assertEquals(
            UnavailableReason.VISUALIZER_UNSUPPORTED_BY_DEVICE,
            resolve(deviceSupports = false).reason
        )
        assertEquals(UnavailableReason.SESSION_ZERO_RESTRICTED, resolve(attached = false).reason)
    }

    @Test
    fun `the most fundamental blocker is reported first`() {
        val capability = resolve(hasPermission = false, deviceSupports = false, attached = false, apiLevel = 14)
        assertEquals(UnavailableReason.API_LEVEL_TOO_LOW, capability.reason)
    }

    @Test
    fun `hidden or paused media does flat bars rather than burning cpu`() {
        // Doc 1: "Avoid running waveform analysis unless media is actually playing
        // and the waveform is visible."
        val hidden = resolve(isVisible = false)
        assertEquals(WaveformSource.FLAT, hidden.source)
        assertEquals(UnavailableReason.NOT_VISIBLE, hidden.reason)

        val paused = resolve(isPlaying = false)
        assertEquals(WaveformSource.FLAT, paused.source)
        assertEquals(UnavailableReason.NOT_PLAYING, paused.reason)

        assertFalse(WaveformSourcePolicy.shouldAnimate(paused, isVisible = true, isPlaying = false))
        assertTrue(WaveformSourcePolicy.shouldAnimate(resolve(), isVisible = true, isPlaying = true))
    }

    @Test
    fun `reduce motion rests the bars instead of animating them`() {
        val capability = resolve(reduceMotion = true)
        assertEquals(WaveformSource.FLAT, capability.source)
        assertEquals(UnavailableReason.REDUCED_MOTION, capability.reason)
    }

    @Test
    fun `the settings toggle disables the visualiser entirely`() {
        assertEquals(WaveformSource.FLAT, resolve(enabled = false).source)
    }

    // ------------------------------------------------------------ FFT maths

    @Test
    fun `visualizer fft bytes become normalised magnitudes with dc dropped`() {
        val fft = byteArrayOf(
            10, 10, // bin 0 (DC)
            20, 0,  // bin 1
            0, 30,  // bin 2
            5, 5    // bin 3
        )
        val magnitudes = WaveformSourcePolicy.magnitudesFromVisualizerFft(fft)

        assertEquals(4, magnitudes.size)
        assertEquals("DC carries no musical information", 0f, magnitudes[0], 1e-6f)
        // |20+0i| < |0+30i|
        assertTrue(magnitudes[2] > magnitudes[1])
        magnitudes.forEach { assertTrue("magnitude $it out of range", it in 0f..1f) }
    }

    @Test
    fun `a full-scale fft bin normalises to one`() {
        val fullScale = byteArrayOf(0, 0, 127, 127)
        val magnitudes = WaveformSourcePolicy.magnitudesFromVisualizerFft(fullScale)
        assertEquals(1f, magnitudes[1], 0.01f)
    }

    @Test
    fun `empty and odd-length buffers are handled without crashing`() {
        assertEquals(0, WaveformSourcePolicy.magnitudesFromVisualizerFft(ByteArray(0)).size)
        assertEquals(1, WaveformSourcePolicy.magnitudesFromVisualizerFft(byteArrayOf(7)).size)
    }

    @Test
    fun `binning returns exactly the requested bar count within range`() {
        val magnitudes = FloatArray(64) { index -> (index % 7) / 10f }

        listOf(1, 5, WaveformSourcePolicy.EXPANDED_BAR_COUNT, 40).forEach { bars ->
            val binned = WaveformSourcePolicy.binMagnitudes(magnitudes, barCount = bars)
            assertEquals("bar count $bars", bars, binned.size)
            binned.forEach { assertTrue(it in 0f..1f) }
        }
    }

    @Test
    fun `binning degrades safely on degenerate input`() {
        // Too few bins for the requested bars: padded, not invented.
        val tiny = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(5, WaveformSourcePolicy.binMagnitudes(tiny, barCount = 5).size)

        // A single bin (DC only) cannot be spaced logarithmically.
        assertEquals(5, WaveformSourcePolicy.binMagnitudes(floatArrayOf(0.9f), barCount = 5).size)
        assertTrue(WaveformSourcePolicy.binMagnitudes(floatArrayOf(0.9f), barCount = 5).all { it == 0f })

        assertEquals(0, WaveformSourcePolicy.binMagnitudes(tiny, barCount = 0).size)
        assertTrue(WaveformSourcePolicy.binMagnitudes(FloatArray(0), barCount = 5).all { it == 0f })
    }

    @Test
    fun `smoothing attacks fast and releases slowly`() {
        val previous = listOf(0.1f, 0.1f, 0.1f)
        val rising = WaveformSourcePolicy.smooth(previous, listOf(0.9f, 0.9f, 0.9f))
        val falling = WaveformSourcePolicy.smooth(listOf(0.9f, 0.9f, 0.9f), previous)

        // A transient should be visible almost immediately...
        assertTrue("rising=${rising[0]}", rising[0] > 0.5f)
        // ...while the decay trails off smoothly rather than snapping.
        assertTrue("falling=${falling[0]}", falling[0] > 0.5f)
        assertTrue(rising[0] > falling[0])
    }

    @Test
    fun `flattening reaches exactly zero at full progress`() {
        val bars = listOf(0.4f, 0.8f, 1f)
        assertEquals(bars, WaveformSourcePolicy.flatten(bars, 0f))
        assertTrue(WaveformSourcePolicy.flatten(bars, 1f).all { it == 0f })
        // Progress is clamped, so an overshoot cannot invert the bars.
        assertTrue(WaveformSourcePolicy.flatten(bars, 4f).all { it == 0f })
        val half = WaveformSourcePolicy.flatten(bars, 0.5f)
        assertEquals(0.4f, half[0], 1e-6f)
    }

    // ------------------------------------------------- synthetic fallback

    @Test
    fun `synthetic bars are deterministic for the same phase`() {
        val a = WaveformSourcePolicy.syntheticAmplitudes(5, 1.234f)
        val b = WaveformSourcePolicy.syntheticAmplitudes(5, 1.234f)
        assertEquals(a, b)
        a.forEach { assertTrue("bar $it out of range", it in 0f..1f) }
    }

    @Test
    fun `synthetic bars rest when playback is paused`() {
        // The phase is derived from position, so pausing freezes rather than
        // rippling — which is what stops it reading as a live analyser.
        assertTrue(WaveformSourcePolicy.syntheticAmplitudes(5, 2f, isPlaying = false).all { it == 0f })
        assertEquals(0f, WaveformSourcePolicy.syntheticPhase(0L), 1e-6f)
        assertTrue(WaveformSourcePolicy.syntheticPhase(900L) > 0f)
        assertTrue(WaveformSourcePolicy.syntheticPhase(900L, 2f) > WaveformSourcePolicy.syntheticPhase(900L, 1f))
    }

    @Test
    fun `synthetic bar count is respected and zero is safe`() {
        assertEquals(0, WaveformSourcePolicy.syntheticAmplitudes(0, 1f).size)
        assertEquals(
            WaveformSourcePolicy.EXPANDED_BAR_COUNT,
            WaveformSourcePolicy.syntheticAmplitudes(WaveformSourcePolicy.EXPANDED_BAR_COUNT, 1f).size
        )
    }

}
