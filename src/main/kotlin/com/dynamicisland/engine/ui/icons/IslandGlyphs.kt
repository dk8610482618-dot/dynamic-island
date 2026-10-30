package com.dynamicisland.engine.ui.icons

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
import androidx.compose.ui.graphics.drawscope.scale
import com.dynamicisland.engine.core.model.IslandSymbol

/**
 * Monochrome vector glyphs (SF Symbols equivalents) for the Dynamic Island.
 *
 * Every glyph is authored on a 24x24 grid and scaled to the requested size, so it stays
 * crisp at every density. These replace the emoji characters the island used to render
 * (which produced a red musical note, a grey pause badge and a literal "X" next to a mic).
 */
@Composable
fun IslandGlyph(
    symbol: IslandSymbol,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawIslandGlyph(symbol, tint)
    }
}

fun DrawScope.drawIslandGlyph(symbol: IslandSymbol, color: Color) {
    val unit = size.minDimension / 24f
    if (unit <= 0f) return
    scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
        drawGlyph24(symbol, color)
    }
}

/** Handset used by PHONE / PHONE_END (rotated 135 degrees like the iOS decline button). */
private val handsetPath: Path = Path().apply {
    moveTo(6.3f, 10.6f)
    cubicTo(7.4f, 13.2f, 9.4f, 15.2f, 12.0f, 16.3f)
    lineTo(14.2f, 14.1f)
    cubicTo(14.5f, 13.8f, 14.9f, 13.7f, 15.3f, 13.9f)
    cubicTo(16.4f, 14.4f, 17.6f, 14.7f, 18.8f, 14.7f)
    cubicTo(19.4f, 14.7f, 19.9f, 15.2f, 19.9f, 15.8f)
    lineTo(19.9f, 19.4f)
    cubicTo(19.9f, 20.0f, 19.4f, 20.5f, 18.8f, 20.5f)
    cubicTo(9.3f, 20.5f, 2.9f, 14.1f, 2.9f, 4.6f)
    cubicTo(2.9f, 4.0f, 3.4f, 3.5f, 4.0f, 3.5f)
    lineTo(7.6f, 3.5f)
    cubicTo(8.2f, 3.5f, 8.7f, 4.0f, 8.7f, 4.6f)
    cubicTo(8.7f, 5.8f, 8.9f, 7.0f, 9.4f, 8.1f)
    cubicTo(9.6f, 8.5f, 9.5f, 8.9f, 9.2f, 9.2f)
    close()
}

private val bellPath: Path = Path().apply {
    moveTo(12f, 2.6f)
    cubicTo(9.1f, 2.6f, 7.1f, 4.8f, 7.1f, 7.8f)
    lineTo(7.1f, 11.4f)
    cubicTo(7.1f, 13.1f, 6.3f, 14.4f, 5.2f, 15.6f)
    cubicTo(4.6f, 16.2f, 5.0f, 17.2f, 5.9f, 17.2f)
    lineTo(18.1f, 17.2f)
    cubicTo(19.0f, 17.2f, 19.4f, 16.2f, 18.8f, 15.6f)
    cubicTo(17.7f, 14.4f, 16.9f, 13.1f, 16.9f, 11.4f)
    lineTo(16.9f, 7.8f)
    cubicTo(16.9f, 4.8f, 14.9f, 2.6f, 12f, 2.6f)
    close()
}

private val boltPath: Path = Path().apply {
    moveTo(13.6f, 1.8f)
    lineTo(4.7f, 13.5f)
    lineTo(11.0f, 13.5f)
    lineTo(10.2f, 22.2f)
    lineTo(19.3f, 10.4f)
    lineTo(12.9f, 10.4f)
    close()
}

private val navigationPath: Path = Path().apply {
    moveTo(12f, 2.2f)
    lineTo(21.2f, 21.4f)
    lineTo(12f, 16.6f)
    lineTo(2.8f, 21.4f)
    close()
}

private val triangleUpPath: Path = Path().apply {
    moveTo(12f, 9.2f)
    lineTo(17.2f, 17.4f)
    lineTo(6.8f, 17.4f)
    close()
}

private fun DrawScope.drawGlyph24(symbol: IslandSymbol, color: Color) {
    val stroke = 2f

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = stroke) {
        drawLine(
            color = color,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }

    fun arc(cx: Float, cy: Float, r: Float, startAngle: Float, sweep: Float, width: Float = stroke) {
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = width, cap = StrokeCap.Round)
        )
    }

    fun rrect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
        drawRoundRect(
            color = color,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            cornerRadius = CornerRadius(radius, radius)
        )
    }

    when (symbol) {
        IslandSymbol.PLAY -> {
            val p = Path().apply {
                moveTo(8.2f, 5.4f)
                lineTo(18.8f, 12f)
                lineTo(8.2f, 18.6f)
                close()
            }
            drawPath(p, color)
        }

        IslandSymbol.PAUSE -> {
            rrect(7.0f, 5.0f, 10.6f, 19.0f, 1.6f)
            rrect(13.4f, 5.0f, 17.0f, 19.0f, 1.6f)
        }

        IslandSymbol.MUSIC_NOTE -> {
            // Eighth note: filled head, stem and flag.
            drawCircle(color, 3.1f, Offset(9.0f, 17.2f))
            line(12.1f, 17.2f, 12.1f, 4.6f)
            val flag = Path().apply {
                moveTo(12.1f, 4.6f)
                cubicTo(13.6f, 4.2f, 15.8f, 5.1f, 17.4f, 7.0f)
                cubicTo(15.4f, 7.3f, 13.4f, 8.3f, 12.1f, 9.8f)
                close()
            }
            drawPath(flag, color)
        }

        IslandSymbol.BELL -> {
            drawPath(bellPath, color)
            arc(12f, 18.2f, 2.3f, 10f, 160f)
        }

        IslandSymbol.BELL_SLASH -> {
            drawPath(bellPath, color)
            arc(12f, 18.2f, 2.3f, 10f, 160f)
            line(4.2f, 3.2f, 19.8f, 21.0f, 2.4f)
        }

        IslandSymbol.BOLT -> drawPath(boltPath, color)

        IslandSymbol.HEADSET -> {
            arc(12f, 13.4f, 8.2f, 190f, 160f, 2.2f)
            rrect(3.6f, 12.6f, 7.2f, 19.0f, 1.8f)
            rrect(16.8f, 12.6f, 20.4f, 19.0f, 1.8f)
        }

        IslandSymbol.WIFI -> {
            arc(12f, 19.6f, 4.4f, 205f, 130f, 2.1f)
            arc(12f, 19.6f, 8.2f, 205f, 130f, 2.1f)
            arc(12f, 19.6f, 12.0f, 205f, 130f, 2.1f)
            drawCircle(color, 1.5f, Offset(12f, 19.2f))
        }

        IslandSymbol.PHONE -> drawPath(handsetPath, color)

        IslandSymbol.PHONE_END -> rotate(135f, Offset(12f, 12f)) {
            drawPath(handsetPath, color)
        }

        IslandSymbol.TIMER -> {
            drawCircle(color, 8.0f, Offset(12f, 13.6f), style = Stroke(stroke))
            line(12f, 9.2f, 12f, 13.8f)
            line(12f, 13.8f, 15.2f, 15.8f)
            line(8.8f, 2.8f, 15.2f, 2.8f)
        }

        IslandSymbol.NAVIGATION -> drawPath(navigationPath, color)
        IslandSymbol.DELIVERY -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(3.0f, 7.0f),
                size = Size(12.0f, 9.0f),
                cornerRadius = CornerRadius(2.0f, 2.0f),
                style = Stroke(stroke)
            )
            line(15.0f, 10.0f, 19.5f, 10.0f)
            line(19.5f, 10.0f, 21.0f, 13.5f)
            line(21.0f, 13.5f, 21.0f, 16.0f)
            line(15.0f, 16.0f, 21.0f, 16.0f)
            drawCircle(color, 2.2f, Offset(7.2f, 17.8f))
            drawCircle(color, 2.2f, Offset(17.5f, 17.8f))
        }
        IslandSymbol.DOWNLOAD -> {
            line(12f, 3.8f, 12f, 15.2f, 2.2f)
            line(7.8f, 11.4f, 12f, 15.6f, 2.2f)
            line(16.2f, 11.4f, 12f, 15.6f, 2.2f)
            line(5.0f, 19.5f, 19.0f, 19.5f, 2.2f)
        }

        IslandSymbol.MIC -> {
            rrect(9.0f, 2.4f, 15.0f, 13.2f, 3.0f)
            arc(12f, 12.4f, 5.6f, 0f, 180f)
            line(12f, 18f, 12f, 21.2f)
            line(8.6f, 21.2f, 15.4f, 21.2f)
        }

        IslandSymbol.MIC_OFF -> {
            rrect(9.0f, 2.4f, 15.0f, 13.2f, 3.0f)
            arc(12f, 12.4f, 5.6f, 20f, 140f)
            line(12f, 18f, 12f, 21.2f)
            line(8.6f, 21.2f, 15.4f, 21.2f)
            line(4.0f, 3.0f, 20.0f, 21.0f, 2.4f)
        }

        IslandSymbol.SPEAKER -> {
            val p = Path().apply {
                moveTo(3.4f, 9.0f)
                lineTo(6.4f, 9.0f)
                lineTo(11.0f, 5.0f)
                lineTo(11.0f, 19.0f)
                lineTo(6.4f, 15.0f)
                lineTo(3.4f, 15.0f)
                close()
            }
            drawPath(p, color)
            arc(11.6f, 12f, 5.2f, -55f, 110f, 2.1f)
            arc(11.6f, 12f, 8.8f, -50f, 100f, 2.1f)
        }

        IslandSymbol.AIRPLAY -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(2.6f, 3.2f),
                size = Size(18.8f, 15.0f),
                cornerRadius = CornerRadius(3.6f, 3.6f),
                style = Stroke(stroke)
            )
            drawPath(triangleUpPath, color)
        }

        IslandSymbol.BATTERY -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(1.8f, 6.6f),
                size = Size(17.6f, 10.8f),
                cornerRadius = CornerRadius(3.0f, 3.0f),
                style = Stroke(stroke)
            )
            rrect(19.9f, 10.0f, 22.2f, 14.0f, 1.2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(4.0f, 8.8f),
                size = Size(9.0f, 6.4f),
                cornerRadius = CornerRadius(1.4f, 1.4f)
            )
        }

        IslandSymbol.CLOSE -> {
            line(6.0f, 6.0f, 18.0f, 18.0f, 2.4f)
            line(18.0f, 6.0f, 6.0f, 18.0f, 2.4f)
        }
    }
}

/**
 * Small transport chevrons (skip) drawn separately because they combine a bar + triangle.
 */
fun DrawScope.drawSkipGlyph(forward: Boolean, color: Color) {
    val unit = size.minDimension / 24f
    if (unit <= 0f) return
    scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
        val barX = if (forward) 16.2f else 5.4f
        drawRoundRect(
            color = color,
            topLeft = Offset(barX, 5.2f),
            size = Size(2.6f, 13.6f),
            cornerRadius = CornerRadius(1.2f, 1.2f)
        )
        val p = Path().apply {
            if (forward) {
                moveTo(6.4f, 6.0f)
                lineTo(15.0f, 12f)
                lineTo(6.4f, 18.0f)
            } else {
                moveTo(17.6f, 6.0f)
                lineTo(9.0f, 12f)
                lineTo(17.6f, 18.0f)
            }
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.4f, join = StrokeJoin.Round))
    }
}
