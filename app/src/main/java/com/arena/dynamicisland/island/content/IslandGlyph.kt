package com.arena.dynamicisland.island.content

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The handful of icons the island needs, drawn as vectors.
 *
 * Deliberately hand-drawn instead of pulled from an icon pack: it keeps the module dependency-free
 * and lets the strokes match the island's 1.6dp optical weight at 14–20dp sizes.
 */
enum class Glyph {
    Play,
    Pause,
    Previous,
    Next,
    Phone,
    PhoneDown,
    Bolt,
    ArrowRight,
    ArrowUp,
    Check,
    Stop,
    Lock,
}

@Composable
fun IslandGlyph(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    strokeWidth: Dp = 1.6.dp,
) {
    Canvas(modifier = modifier) {
        drawGlyph(glyph = glyph, tint = tint, strokeWidthPx = strokeWidth.toPx())
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, strokeWidthPx: Float) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (glyph) {
        Glyph.Play -> drawPath(
            path = Path().apply {
                moveTo(w * 0.30f, h * 0.15f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.30f, h * 0.85f)
                close()
            },
            color = tint,
        )

        Glyph.Pause -> {
            val barWidth = w * 0.15f
            val radius = CornerRadius(barWidth * 0.35f, barWidth * 0.35f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.29f, h * 0.17f),
                size = Size(barWidth, h * 0.66f),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.56f, h * 0.17f),
                size = Size(barWidth, h * 0.66f),
                cornerRadius = radius,
            )
        }

        Glyph.Previous, Glyph.Next -> {
            val mirrored = glyph == Glyph.Previous
            val triangle = Path().apply {
                if (mirrored) {
                    moveTo(w * 0.74f, h * 0.18f)
                    lineTo(w * 0.32f, h * 0.50f)
                    lineTo(w * 0.74f, h * 0.82f)
                } else {
                    moveTo(w * 0.26f, h * 0.18f)
                    lineTo(w * 0.68f, h * 0.50f)
                    lineTo(w * 0.26f, h * 0.82f)
                }
                close()
            }
            drawPath(triangle, tint)
            drawLine(
                color = tint,
                start = Offset(if (mirrored) w * 0.78f else w * 0.22f, h * 0.18f),
                end = Offset(if (mirrored) w * 0.78f else w * 0.22f, h * 0.82f),
                strokeWidth = strokeWidthPx * 1.15f,
                cap = StrokeCap.Round,
            )
        }

        Glyph.Phone, Glyph.PhoneDown -> {
            val handset = Path().apply {
                moveTo(w * 0.22f, h * 0.20f)
                cubicTo(w * 0.40f, h * 0.28f, w * 0.72f, h * 0.56f, w * 0.80f, h * 0.78f)
            }
            if (glyph == Glyph.PhoneDown) {
                rotate(degrees = 135f, pivot = center) {
                    drawPath(handset, tint, style = stroke)
                }
            } else {
                drawPath(handset, tint, style = stroke)
            }
        }

        Glyph.Bolt -> drawPath(
            path = Path().apply {
                moveTo(w * 0.56f, h * 0.08f)
                lineTo(w * 0.26f, h * 0.54f)
                lineTo(w * 0.47f, h * 0.54f)
                lineTo(w * 0.41f, h * 0.92f)
                lineTo(w * 0.74f, h * 0.44f)
                lineTo(w * 0.52f, h * 0.44f)
                close()
            },
            color = tint,
        )

        Glyph.ArrowRight, Glyph.ArrowUp -> {
            val path = Path().apply {
                if (glyph == Glyph.ArrowUp) {
                    moveTo(w * 0.50f, h * 0.82f)
                    lineTo(w * 0.50f, h * 0.22f)
                } else {
                    moveTo(w * 0.18f, h * 0.50f)
                    lineTo(w * 0.80f, h * 0.50f)
                }
            }
            drawPath(path, tint, style = stroke)
            // Head of the arrow.
            val head = Path().apply {
                if (glyph == Glyph.ArrowUp) {
                    moveTo(w * 0.28f, h * 0.42f)
                    lineTo(w * 0.50f, h * 0.18f)
                    lineTo(w * 0.72f, h * 0.42f)
                } else {
                    moveTo(w * 0.58f, h * 0.26f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.58f, h * 0.74f)
                }
            }
            drawPath(head, tint, style = stroke)
        }

        Glyph.Check -> {
            val path = Path().apply {
                moveTo(w * 0.20f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.74f)
                lineTo(w * 0.82f, h * 0.26f)
            }
            drawPath(path, tint, style = stroke)
        }

        Glyph.Stop -> drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.26f, h * 0.26f),
            size = Size(w * 0.48f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        )

        Glyph.Lock -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.24f, h * 0.44f),
                size = Size(w * 0.52f, h * 0.40f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w * 0.33f, h * 0.20f),
                size = Size(w * 0.34f, h * 0.34f),
                style = stroke,
            )
        }
    }
}
