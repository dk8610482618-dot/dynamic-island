package com.dynamicisland.engine.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.dynamicisland.engine.settings.BehaviorPreferences
import com.dynamicisland.core.geometry.MotionPreset
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * iOS-style equalizer glyph for the island.
 *
 * Two modes:
 * - [amplitudes] non-empty: renders the supplied values verbatim. Only a genuine audio
 *   source should ever provide these (the engine no longer fabricates them).
 * - [amplitudes] empty: renders the "playing" indicator — five bars with a deterministic
 *   travelling-wave animation. This is iconography for the real `PlaybackState.PLAYING`,
 *   not measured audio, so no fake telemetry is presented as data.
 *
 * Rendered directly into Canvas with drawRoundRect to eliminate per-frame Box layout passes.
 */
@Composable
fun AudioWaveformEqualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 15.dp,
    minHeight: Dp = 3.dp,
    spacing: Dp = 2.dp,
    color: Color = Color.White
) {
    val realData = amplitudes.isNotEmpty()
    val options by BehaviorPreferences.get(LocalContext.current).options.collectAsState()
    val reduced = options.reducedMotion || options.motion == MotionPreset.NONE || !android.animation.ValueAnimator.areAnimatorsEnabled()

    // Defer reading the animated phase into the Canvas draw lambda.
    // Reading `animateFloat` in composition causes this Composable to recompose
    // on every single frame (60-120 fps). Reading `animatedPhase?.value` inside
    // the DrawScope lambda executes only the Draw phase, completely eliminating recompositions.
    val transition = rememberInfiniteTransition(label = "WaveformIndicator")
    val animatedPhase = if (realData || reduced) {
        null
    } else {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "WaveformPhase"
        )
    }

    val totalWidth = barWidth * BAR_COUNT + spacing * (BAR_COUNT - 1)

    Canvas(
        modifier = modifier.size(width = totalWidth, height = maxHeight)
    ) {
        val phase = animatedPhase?.value ?: 0f
        val barWidthPx = barWidth.toPx()
        val maxHeightPx = maxHeight.toPx()
        val minHeightPx = minHeight.toPx()
        val spacingPx = spacing.toPx()
        val cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)

        for (index in 0 until BAR_COUNT) {
            val amplitude = if (realData) {
                amplitudes.getOrElse(index) { 0.12f }
            } else {
                val wave = sin((phase * 2.0 * PI) + index * 0.9)
                (0.45f + 0.55f * abs(wave)).toFloat()
            }
            val clamped = amplitude.coerceIn(0.12f, 1f)
            val h = minHeightPx + (maxHeightPx - minHeightPx) * clamped
            val x = index * (barWidthPx + spacingPx)
            val y = maxHeightPx - h

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidthPx, h),
                cornerRadius = cornerRadius
            )
        }
    }
}

private const val BAR_COUNT = 5
