package com.dynamicisland.engine.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import com.dynamicisland.engine.ui.IslandHaptics
import com.dynamicisland.engine.ui.motion.islandMotionSpec
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.ui.icons.IslandGlyph
import com.dynamicisland.engine.ui.util.rememberCurrentSecond

/**
 * iOS Live Activity timer card (366 x 84 pt).
 *
 * Layout matches the reverse-engineered reference: a translucent cancel disc on the
 * leading edge, "Timer / <label>" metadata stack, and a large light-weight monospaced
 * countdown on the trailing edge. The countdown ticks once per second instead of the
 * previously hard-coded "02:45".
 */
@Composable
fun TimerExpandedContent(
    title: String,
    subtitle: String?,
    baseTimeMillis: Long,
    isCountDown: Boolean,
    isRunning: Boolean,
    accent: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val second = rememberCurrentSecond(active = isRunning && baseTimeMillis > 0L)

    val remainingOrElapsedMs = remember(baseTimeMillis, isCountDown, second) {
        val now = System.currentTimeMillis()
        if (baseTimeMillis <= 0L) 0L
        else if (isCountDown) (baseTimeMillis - now).coerceAtLeast(0L)
        else (now - baseTimeMillis).coerceAtLeast(0L)
    }

    val hapticContext = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cancelScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = islandMotionSpec(),
        label = "TimerCancelScale"
    )

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { scaleX = cancelScale; scaleY = cancelScale }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClickLabel = "Cancel timer"
                ) {
                    IslandHaptics.perform(hapticContext)
                    onCancel()
                },
            contentAlignment = Alignment.Center
        ) {
            IslandGlyph(
                symbol = IslandSymbol.CLOSE,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IslandGlyph(
                    symbol = IslandSymbol.TIMER,
                    tint = accent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = title.ifBlank { "Timer" },
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle?.takeIf { it.isNotBlank() } ?: if (isCountDown) "Countdown" else "Stopwatch",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = formatDuration(remainingOrElapsedMs),
            color = accent,
            fontSize = 34.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
