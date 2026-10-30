package com.dynamicisland.core.media

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Gap **G7** — a real FFT-driven equaliser, with an honest synthetic fallback.
 *
 * Doc 2 asks for "a real-time audio visualizer in the compactTrailing and minimal
 * slots, rendering a multi-bar vertical frequency equalizer that animates in sync
 * with the active audio stream and flattens smoothly when playback pauses", and
 * lists an "FFT audio waveform" in the Phase 4 deliverables. The imported base
 * rendered `AudioWaveformEqualizer` from a hard-coded
 * `listOf(0.45f, 0.85f, 0.6f, 0.9f)` fallback — bars that moved but measured
 * nothing.
 *
 * This is the same class of problem as the camera privacy dot, and gets the same
 * answer the user chose for privacy: **capability-gated, and never presented as
 * real when it is not.**
 *
 * `android.media.audiofx.Visualizer` needs `RECORD_AUDIO` plus
 * `MODIFY_AUDIO_SETTINGS`, and attaching to audio session 0 (the global output
 * mix) is restricted or outright unsupported on a large share of devices and
 * ROMs. So [resolve] reports which of three sources is actually in use, and the
 * UI must label [WaveformSource.SYNTHETIC] as indicative rather than letting it
 * masquerade as a spectrum analyser of someone else's audio.
 *
 * Doc 1 adds the performance constraint that shapes the API: "Avoid running
 * waveform analysis unless media is actually playing and the waveform is
 * visible." [resolve] therefore takes visibility and playback state and returns
 * [WaveformSource.FLAT] rather than doing DSP work nobody can see.
 *
 * Pure JVM — the FFT magnitude and binning maths is real and unit-testable
 * without an audio device.
 */
object WaveformSourcePolicy {

    /** Number of bars in the compact equaliser. */
    const val DEFAULT_BAR_COUNT: Int = 5

    /** Bars in the expanded scrub-region visualiser. */
    const val EXPANDED_BAR_COUNT: Int = 16

    /** Visualizer was added in API 9; session-0 restrictions tightened over time. */
    const val MIN_API_FOR_VISUALIZER: Int = 19

    /** Where the bars came from. Surfaced in the capability panel. */
    enum class WaveformSource {
        /**
         * Genuine spectrum data from `android.media.audiofx.Visualizer`. The only
         * source that may be described as a visualiser in the UI.
         */
        REAL_FFT,

        /**
         * Deterministic motion derived from playback state (playing, position,
         * speed). It indicates "audio is playing" and nothing more, and must be
         * labelled as such.
         */
        SYNTHETIC,

        /** Paused, stopped, or hidden: bars at rest. */
        FLAT,

        /** The user asked for the visualiser but the platform refuses it. */
        UNAVAILABLE
    }

    /** Why real FFT is not available. */
    enum class UnavailableReason {
        NO_RECORD_AUDIO_PERMISSION,
        VISUALIZER_UNSUPPORTED_BY_DEVICE,
        SESSION_ZERO_RESTRICTED,
        API_LEVEL_TOO_LOW,
        NOT_PLAYING,
        NOT_VISIBLE,
        REDUCED_MOTION
    }

    data class Capability(
        val source: WaveformSource,
        val reason: UnavailableReason? = null,
        /**
         * Copy-safe flag: when false the UI must not call this a visualiser or
         * spectrum. Prevents a synthetic animation from being a false signal.
         */
        val mayBeDescribedAsRealSpectrum: Boolean = source == WaveformSource.REAL_FFT
    )

    /**
     * Decides the waveform source. Ordered cheapest-signal-first so the most
     * specific reason is reported.
     */
    fun resolve(
        isVisible: Boolean,
        isPlaying: Boolean,
        waveformEnabledInSettings: Boolean,
        hasRecordAudioPermission: Boolean,
        deviceSupportsVisualizer: Boolean,
        visualizerSessionAttached: Boolean,
        apiLevel: Int,
        reduceMotion: Boolean = false
    ): Capability {
        if (!isVisible) return Capability(WaveformSource.FLAT, UnavailableReason.NOT_VISIBLE)
        if (!waveformEnabledInSettings) return Capability(WaveformSource.FLAT)
        if (!isPlaying) return Capability(WaveformSource.FLAT, UnavailableReason.NOT_PLAYING)

        // Reduce Motion keeps a static level indicator: doc 2 requires replacing
        // spring motion with static cross-fades when the flag is active, and a
        // continuously animating equaliser is motion.
        if (reduceMotion) return Capability(WaveformSource.FLAT, UnavailableReason.REDUCED_MOTION)

        val realAvailable = apiLevel >= MIN_API_FOR_VISUALIZER &&
            hasRecordAudioPermission &&
            deviceSupportsVisualizer &&
            visualizerSessionAttached

        if (realAvailable) return Capability(WaveformSource.REAL_FFT)

        // Report the most fundamental blocker, not the last one checked.
        val reason = when {
            apiLevel < MIN_API_FOR_VISUALIZER -> UnavailableReason.API_LEVEL_TOO_LOW
            !hasRecordAudioPermission -> UnavailableReason.NO_RECORD_AUDIO_PERMISSION
            !deviceSupportsVisualizer -> UnavailableReason.VISUALIZER_UNSUPPORTED_BY_DEVICE
            else -> UnavailableReason.SESSION_ZERO_RESTRICTED
        }
        return Capability(WaveformSource.SYNTHETIC, reason)
    }

    // ---------------------------------------------------------------------
    // Real FFT path
    // ---------------------------------------------------------------------

    /**
     * Converts the byte buffer filled by `Visualizer.getFft()` into linear
     * magnitudes.
     *
     * The documented layout is interleaved real/imaginary pairs: index `2k` is
     * the real part and `2k + 1` the imaginary part of bin `k`, with bin 0 being
     * DC. Magnitude is the modulus of each pair. Values are normalised to 0..1
     * against the theoretical maximum for an 8-bit-per-component buffer.
     */
    fun magnitudesFromVisualizerFft(fft: ByteArray): FloatArray {
        val pairs = fft.size / 2
        if (pairs <= 0) return FloatArray(0)
        val out = FloatArray(pairs)
        // Full-scale modulus for two signed bytes: 127 * sqrt(2).
        val fullScale = 127f * 1.41421356f
        for (k in 0 until pairs) {
            val real = fft[2 * k].toFloat()
            val imaginary = fft[2 * k + 1].toFloat()
            out[k] = (hypot(real, imaginary) / fullScale).coerceIn(0f, 1f)
        }
        // DC carries no musical information and dominates; drop it so the bars
        // are not all pinned to the bass bin.
        if (out.isNotEmpty()) out[0] = 0f
        return out
    }

    /**
     * Bins magnitudes into [barCount] bars on a **logarithmic** frequency scale.
     *
     * Linear binning puts almost every bar in the high frequencies where music
     * has little energy, which is why naive visualisers look dead. Human pitch
     * perception is logarithmic, so bin edges are spaced by ratio.
     *
     * @param minBin first bin to include (skips sub-audio rumble).
     * @param maxBin last bin, exclusive; capped to the input length.
     */
    fun binMagnitudes(
        magnitudes: FloatArray,
        barCount: Int = DEFAULT_BAR_COUNT,
        minBin: Int = 1,
        maxBin: Int = magnitudes.size
    ): List<Float> {
        // Two bins is the minimum for any log spacing (bin 0 is DC and is
        // dropped, so a single-bin buffer has nothing to draw).
        if (barCount <= 0 || magnitudes.size < 2) return List(max(barCount, 0)) { 0f }
        // `from` must stay >= 1: the log spacing below divides by it, and bin 0
        // is DC, which carries no musical energy anyway.
        val from = minBin.coerceIn(1, magnitudes.size - 1)
        val to = maxBin.coerceIn(from + 1, magnitudes.size)
        if (to - from < barCount) {
            // Not enough resolution for the requested bars: pad with the last
            // available bins rather than inventing energy.
            return (from until to).map { magnitudes[it] } +
                List(barCount - (to - from)) { if (to > from) magnitudes[to - 1] else 0f }
        }

        val ratio = ((to - 1).toDouble() / from.toDouble()).pow(1.0 / barCount)
        val bars = ArrayList<Float>(barCount)
        var start = from.toDouble()
        repeat(barCount) {
            val end = min(start * ratio, to.toDouble())
            val lo = start.toInt().coerceIn(from, to - 1)
            val hi = max(end.toInt(), lo + 1).coerceAtMost(to)
            var sum = 0f
            var count = 0
            for (i in lo until hi) {
                sum += magnitudes[i]
                count++
            }
            bars += if (count > 0) (sum / count).coerceIn(0f, 1f) else 0f
            start = end
        }
        return bars
    }

    /**
     * Temporal smoothing. Attack is fast so transients are visible; release is
     * slower so bars fall smoothly instead of flickering — the "flattens smoothly
     * when playback pauses" behaviour doc 2 asks for.
     */
    fun smooth(previous: List<Float>, current: List<Float>, attack: Float = 0.65f, release: Float = 0.22f): List<Float> {
        if (previous.isEmpty()) return current
        return current.mapIndexed { index, value ->
            val before = previous.getOrElse(index) { value }
            val coefficient = if (value > before) attack else release
            (before + (value - before) * coefficient.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        }
    }

    /** Interpolates every bar toward zero by [progress] (0 = as-is, 1 = flat). */
    fun flatten(current: List<Float>, progress: Float): List<Float> {
        val p = progress.coerceIn(0f, 1f)
        return current.map { it * (1f - p) }
    }

    // ---------------------------------------------------------------------
    // Synthetic fallback path
    // ---------------------------------------------------------------------

    /**
     * Deterministic indicative motion for [WaveformSource.SYNTHETIC].
     *
     * A pure function of bar index and phase — no randomness, no hidden state —
     * so the same playback position always produces the same bars. That matters
     * because this path is *not* measuring audio: if it drifted randomly it would
     * read as a real analyser, which is precisely the false signal we refuse to
     * create. It looks like a gentle standing wave, and the UI labels it
     * "Playing" rather than showing a spectrum.
     *
     * @param phaseRadians advances with playback position, not wall time, so a
     *   paused track freezes instead of continuing to ripple.
     */
    fun syntheticAmplitudes(
        barCount: Int = DEFAULT_BAR_COUNT,
        phaseRadians: Float,
        energy: Float = 0.7f,
        isPlaying: Boolean = true
    ): List<Float> {
        if (barCount <= 0) return emptyList()
        if (!isPlaying) return List(barCount) { 0f }
        val amplitude = energy.coerceIn(0f, 1f)
        return List(barCount) { index ->
            val normalised = (index + 0.5f) / barCount
            // Two sines at incommensurate rates give an organic, non-repeating
            // look while remaining fully deterministic.
            val primary = sin(phaseRadians + normalised * 3.1f)
            val secondary = sin(phaseRadians * 0.63f + normalised * 7.7f)
            val envelope = 0.55f + 0.45f * sin(normalised * 3.14159f) // taper at the edges
            val value = (0.5f + 0.32f * primary + 0.18f * secondary) * envelope * amplitude
            value.coerceIn(0.06f, 1f)
        }
    }

    /**
     * Phase for [syntheticAmplitudes] derived from playback position and speed.
     * Using position rather than elapsed wall time is what makes the bars freeze
     * when playback pauses and resume where they left off.
     */
    fun syntheticPhase(positionMs: Long, playbackSpeed: Float = 1f): Float {
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        // Roughly one visual cycle every 900 ms of audio at 1x.
        return ((positionMs * speed) / 900.0).toFloat() * 2f * Math.PI.toFloat()
    }

    /**
     * True when bars should keep animating at all. Doc 1's battery rule: no
     * recurring wakeups for content that is not changing and not visible.
     */
    fun shouldAnimate(capability: Capability, isVisible: Boolean, isPlaying: Boolean): Boolean =
        isVisible && isPlaying && capability.source != WaveformSource.FLAT &&
            capability.source != WaveformSource.UNAVAILABLE
}
