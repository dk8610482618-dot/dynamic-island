package com.arena.dynamicisland.island.content

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText
import kotlin.math.abs
import kotlin.math.sin

/** Slim rounded progress track used under the music card. */
@Composable
fun IslandProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = Color(0x33FFFFFF),
    barColor: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, size = size, cornerRadius = radius)
        val filled = size.width * progress.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                color = barColor,
                size = Size(filled, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** Circular progress, drawn clockwise from twelve o'clock. */
@Composable
fun IslandRingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp,
    color: Color = IslandColors.Amber,
    trackColor: Color = Color(0x2EFFFFFF),
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

/**
 * Four bars that breathe while audio plays.
 *
 * Each bar shares one infinite transition but reads it with a different phase offset, which is
 * enough to look like a live level meter without a per-frame allocation or an audio feed.
 */
@Composable
fun WaveformBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    color: Color = Color.White,
) {
    // Only run the clock while audio is actually playing: a paused meter that keeps animating
    // costs a frame of work forever and looks static anyway.
    val phase = if (isPlaying) rememberWaveformPhase() else 0f

    Canvas(modifier = modifier) {
        val count = barCount.coerceAtLeast(1)
        val gap = size.width / (count * 2.2f)
        val barWidth = (size.width - gap * (count - 1)) / count
        repeat(count) { index ->
            val offset = index * 0.9f
            val level = if (isPlaying) {
                0.35f + 0.65f * abs(sin(phase + offset))
            } else {
                0.30f + 0.10f * index
            }
            val barHeight = size.height * level.coerceIn(0.18f, 1f)
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = color.copy(alpha = if (isPlaying) 0.92f else 0.45f),
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun rememberWaveformPhase(): Float {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveformPhase",
    )
    return phase
}

/** Circular tappable control, used for transport and call actions. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    background: Color = Color.Transparent,
    tint: Color = Color.White,
    glyphSize: Dp = size * 0.44f,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        IslandGlyph(glyph = glyph, tint = tint, modifier = Modifier.size(glyphSize))
    }
}

/** Small filled circle with initials — the stand-in for a contact photo. */
@Composable
fun AvatarBubble(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    background: Color = IslandColors.SurfaceRaised,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = IslandText.Micro.copy(
                fontSize = (size.value * 0.34f).sp,
                color = Color.White,
            ),
        )
    }
}
