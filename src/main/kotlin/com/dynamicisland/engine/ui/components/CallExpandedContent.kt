package com.dynamicisland.engine.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import com.dynamicisland.engine.ui.IslandHaptics
import com.dynamicisland.engine.ui.motion.islandMotionSpec
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.ui.icons.IslandGlyph

private val CallGreen = Color(0xFF30D158)
private val CallRed = Color(0xFFFF453A)
private val CallAmber = Color(0xFFFF9F0A)

/**
 * Rich interactive expanded call card reproducing the iOS CallKit Dynamic Island.
 *
 * The previous implementation put emoji in the avatar (📞), the speaker (🔊), the mute
 * toggle ("🎙️✕" - a microphone emoji followed by a literal multiplication sign) and the
 * hang-up button. All of those are now monochrome vector glyphs, and the ringing state
 * gets the iOS pulsing answer affordance.
 */
@Composable
fun CallExpandedContent(
    callerName: String,
    callerNumber: String?,
    durationText: String,
    isIncoming: Boolean,
    isMuted: Boolean,
    isSpeaker: Boolean,
    /**
     * Whether this app can accept or end the call — i.e. it is the bound
     * `InCallService`. Without it the Answer/Decline/End controls have no API
     * behind them and would silently do nothing, so the card swaps them for the
     * place the action actually lives: the user's own dialer.
     */
    isControllable: Boolean,
    isOnHold: Boolean = false,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ---------- Caller identity ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InitialsAvatar(
                name = callerName,
                size = 52.dp,
                isIncoming = isIncoming
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callerName.ifBlank { "Unknown Caller" },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        isIncoming -> "Incoming call"
                        isOnHold -> "On hold"
                        durationText.isNotBlank() -> durationText
                        else -> callerNumber.orEmpty()
                    },
                    color = when {
                        isIncoming -> CallGreen
                        isOnHold -> CallAmber
                        else -> Color.White.copy(alpha = 0.5f)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ---------- Actions ----------
        if (isIncoming) {
            if (isControllable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallButton(
                        background = CallRed,
                        size = 52.dp,
                        onClick = onHangup,
                        label = "Decline call",
                        glyph = { CallGlyph(IslandSymbol.PHONE_END, 24.dp, Color.White) }
                    )
                    CallButton(
                        background = CallGreen,
                        size = 52.dp,
                        onClick = onAnswer,
                        label = "Answer call",
                        pulsing = true,
                        glyph = { CallGlyph(IslandSymbol.PHONE, 24.dp, Color.White) }
                    )
                }
            } else {
                // No InCallService means no accept API. Say where the button is
                // instead of rendering two that ignore the tap.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Answer or decline from your dialer",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isControllable) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallButton(
                    background = if (isSpeaker) Color.White else Color.White.copy(alpha = 0.12f),
                    onClick = onToggleSpeaker,
                    label = if (isSpeaker) "Disable speaker" else "Enable speaker",
                    glyph = {
                        CallGlyph(
                            IslandSymbol.SPEAKER,
                            20.dp,
                            if (isSpeaker) Color.Black else Color.White
                        )
                    }
                )
                CallButton(
                    background = if (isMuted) Color.White else Color.White.copy(alpha = 0.12f),
                    onClick = onToggleMute,
                    label = if (isMuted) "Unmute" else "Mute",
                    glyph = {
                        CallGlyph(
                            if (isMuted) IslandSymbol.MIC_OFF else IslandSymbol.MIC,
                            20.dp,
                            if (isMuted) Color.Black else Color.White
                        )
                    }
                )
                // End-call only exists through the bound InCallService. Mute and
                // speaker keep working via AudioManager, so they stay either way.
                if (isControllable) {
                    CallButton(
                        background = CallRed,
                        onClick = onHangup,
                        label = "End call",
                        glyph = { CallGlyph(IslandSymbol.PHONE_END, 20.dp, Color.White) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CallGlyph(symbol: IslandSymbol, size: Dp, tint: Color) {
    IslandGlyph(symbol = symbol, tint = tint, modifier = Modifier.size(size))
}

/**
 * Circular call action with a spring press-scale and an optional expanding halo
 * (used for the incoming-call answer button, mirroring the iOS `animate-pulse`).
 *
 * Deferring animation progress reads into the draw phase ensures zero recompositions
 * occur across the 1800ms pulse cycle.
 */
@Composable
private fun CallButton(
    background: Color,
    onClick: () -> Unit,
    label: String,
    glyph: @Composable () -> Unit,
    size: Dp = 48.dp,
    pulsing: Boolean = false
) {
    val hapticContext = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = islandMotionSpec(),
        label = "CallButtonPressScale"
    )

    val haloProgress = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "AnswerPulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "AnswerHaloProgress"
        )
    } else {
        null
    }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .drawBehind {
                val progress = haloProgress?.value ?: 0f
                if (progress > 0f) {
                    val haloAlpha = 0.34f * (1f - progress)
                    val haloScale = 1f + (0.45f * progress)
                    drawCircle(
                        color = background.copy(alpha = haloAlpha),
                        radius = (this.size.minDimension / 2f) * haloScale
                    )
                }
            }
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = label
            ) {
                IslandHaptics.perform(hapticContext)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        glyph()
    }
}

/**
 * Gradient initials avatar matching the iOS contact monogram.
 *
 * When the caller is an unknown number or a raw phone number, a clean monochrome
 * phone glyph is presented in place of confusing digit initials.
 */
@Composable
private fun InitialsAvatar(
    name: String,
    size: Dp,
    isIncoming: Boolean
) {
    val isPhoneNumberOrUnknown = remember(name) {
        val trimmed = name.trim()
        trimmed.isBlank() ||
            trimmed.equals("Unknown Caller", ignoreCase = true) ||
            trimmed.all { it.isDigit() || it in "+-() #* " }
    }

    val initials = remember(name, isPhoneNumberOrUnknown) {
        if (isPhoneNumberOrUnknown) {
            ""
        } else {
            val chars = name.trim()
                .split(' ', '\t')
                .filter { it.isNotBlank() }
                .mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
                .take(2)
                .joinToString("")
            chars.ifBlank { "#" }
        }
    }

    val backgroundBrush = remember(isIncoming) {
        val colors = if (isIncoming) {
            listOf(Color(0xFF7A5CFF), Color(0xFF4B2FD6))
        } else {
            listOf(Color(0xFF5E5CE6), Color(0xFF3634A3))
        }
        Brush.linearGradient(colors)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundBrush),
        contentAlignment = Alignment.Center
    ) {
        if (isPhoneNumberOrUnknown) {
            CallGlyph(
                symbol = IslandSymbol.PHONE,
                size = size * 0.44f,
                tint = Color.White
            )
        } else {
            Text(
                text = initials,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
