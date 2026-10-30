package com.dynamicisland.engine.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import com.dynamicisland.engine.settings.BehaviorPreferences
import com.dynamicisland.core.geometry.MotionPreset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import com.dynamicisland.engine.ui.IslandHaptics
import com.dynamicisland.engine.ui.motion.islandMotionSpec
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.core.model.MediaPlaybackSnapshot
import com.dynamicisland.engine.ui.icons.IslandGlyph
import com.dynamicisland.engine.ui.icons.drawSkipGlyph
import com.dynamicisland.engine.ui.motion.SpringPhysicsSpecs
import com.dynamicisland.engine.ui.squircle.SquircleShape
import com.dynamicisland.engine.ui.util.rememberElapsedTicker

/**
 * Rich interactive expanded media card reproducing the iOS Now Playing Dynamic Island.
 *
 * Geometry follows the reverse-engineered 366 x 176 pt card:
 * a 56 pt album tile, a 5 pt scrubber with a small circular knob (never a Material
 * [androidx.compose.material3.Slider] thumb, which was being clipped into a tall pill),
 * and a borderless transport row of monochrome glyphs.
 */
@Composable
fun MediaExpandedContent(
    title: String,
    artist: String?,
    albumArt: android.graphics.Bitmap?,
    playback: MediaPlaybackSnapshot,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    notificationCount: Int = 0,
    onOpenInbox: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticContext = LocalContext.current

    // Re-read the clock twice a second so the elapsed label and scrubber advance live.
    val tick = rememberElapsedTicker(active = playback.isPlaying, intervalMillis = 500L)

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val livePosition = remember(playback, tick) {
        playback.positionAt(SystemClock.elapsedRealtime())
    }
    val durationMs = playback.durationMs.coerceAtLeast(0L)
    val liveFraction = if (durationMs > 0L) {
        (livePosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val fraction = if (isScrubbing) scrubFraction else liveFraction
    val motionOptions by BehaviorPreferences.get(hapticContext).options.collectAsState()
    val smoothProgress = playback.isPlaying && playback.canSeek && !motionOptions.reducedMotion &&
        motionOptions.motion != MotionPreset.NONE && android.animation.ValueAnimator.areAnimatorsEnabled()
    var frameElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(smoothProgress) {
        if (smoothProgress) while (true) withFrameNanos { frameElapsed = SystemClock.elapsedRealtime() }
    }
    val shownPosition = if (isScrubbing) (scrubFraction * durationMs).toLong() else livePosition

    // Avoid allocating a new ImageBitmap wrapper on every 500ms ticker tick
    val albumArtBitmap = remember(albumArt) { albumArt?.asImageBitmap() }

    // iOS scales the artwork down slightly while paused.
    val artScale by animateFloatAsState(
        targetValue = if (playback.isPlaying) 1f else 0.9f,
        animationSpec = islandMotionSpec(),
        label = "AlbumArtScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ---------- Header: artwork + metadata + live waveform ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { scaleX = artScale; scaleY = artScale }
                    .clip(SquircleShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF3A3A3C), Color(0xFF1B1B1F))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (albumArtBitmap != null) {
                    Image(
                        bitmap = albumArtBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    IslandGlyph(
                        symbol = IslandSymbol.MUSIC_NOTE,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "Not Playing" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // No placeholder subtitle. An unset artist is a line of nothing, and
                // printing "Unknown Artist" on a real card is worse than a shorter card:
                // it reads as data the app does not have. The row simply closes up.
                if (!artist.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artist,
                        color = Color.White.copy(alpha = 0.52f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (notificationCount > 0) {
                Text(
                    text = "Inbox $notificationCount",
                    color = Color(0xFF8EBBFF),
                    fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenInbox).padding(8.dp)
                )
            } else AudioWaveformEqualizer(
                // Empty amplitudes render the animated "playing" indicator; a paused
                // session gets flat bars. No measured data is implied.
                amplitudes = if (playback.isPlaying) emptyList() else FLAT_BARS,
                color = Color.White.copy(alpha = if (playback.isPlaying) 0.9f else 0.35f),
                barWidth = 3.dp,
                maxHeight = 16.dp,
                minHeight = 3.dp,
                spacing = 2.5.dp
            )
        }

        // ---------- Scrubber (hidden when the session reports no duration) ----------
        if (playback.canSeek) {
            Column(modifier = Modifier.fillMaxWidth()) {
                IslandScrubber(
                    fraction = {
                        if (smoothProgress && !isScrubbing && durationMs > 0L)
                            (playback.positionAt(frameElapsed).toFloat() / durationMs).coerceIn(0f, 1f)
                        else fraction
                    },
                    isScrubbing = isScrubbing,
                    onScrubStart = { f ->
                        isScrubbing = true
                        scrubFraction = f
                    },
                    onScrubMove = { f -> scrubFraction = f },
                    onScrubCancel = { isScrubbing = false },
                    onScrubEnd = { f ->
                        isScrubbing = false
                        IslandHaptics.perform(hapticContext)
                        onSeek((f * durationMs).toLong())
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TimeLabel(formatMs(shownPosition))
                    TimeLabel("-${formatMs((durationMs - shownPosition).coerceAtLeast(0L))}")
                }
            }
        } else {
            Spacer(modifier = Modifier.height(1.dp))
        }

        // ---------- Transport ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportButton(
                symbol = null,
                contentDescription = "Previous track",
                glyphSize = 24.dp,
                onClick = onSkipPrevious,
                enabled = playback.canPrevious,
                hapticLabel = HapticFeedbackType.LongPress,
                drawSkipForward = false
            )

            TransportButton(
                symbol = if (playback.isPlaying) IslandSymbol.PAUSE else IslandSymbol.PLAY,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                glyphSize = 30.dp,
                onClick = onPlayPause,
                enabled = playback.canPlayPause,
                hapticLabel = HapticFeedbackType.LongPress
            )

            TransportButton(
                symbol = null,
                contentDescription = "Next track",
                glyphSize = 24.dp,
                onClick = onSkipNext,
                enabled = playback.canNext,
                hapticLabel = HapticFeedbackType.LongPress,
                drawSkipForward = true
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1
    )
}

/**
 * iOS Now Playing style transport control: no filled pill, a 44 dp touch target and a
 * spring press-scale rather than the Material ripple.
 */
@Composable
private fun TransportButton(
    symbol: IslandSymbol?,
    contentDescription: String,
    glyphSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    hapticLabel: HapticFeedbackType,
    drawSkipForward: Boolean = false,
    enabled: Boolean = true
) {
    val hapticContext = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = islandMotionSpec(),
        label = "TransportPressScale"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .35f }
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = contentDescription
            ) {
                IslandHaptics.perform(hapticContext)
                onClick()
            }
            .semantics {
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        if (symbol != null) {
            IslandGlyph(
                symbol = symbol,
                tint = Color.White,
                modifier = Modifier.size(glyphSize)
            )
        } else {
            Canvas(modifier = Modifier.size(glyphSize)) {
                drawSkipGlyph(forward = drawSkipForward, color = Color.White)
            }
        }
    }
}

/**
 * 5 dp scrubbable progress track with an animated knob (4.5 dp base radius, scaling to 1.35x while scrubbing).
 *
 * Implements a unified gesture pipeline via [awaitEachGesture] on [pointerInput] keyed to Unit,
 * completely avoiding mid-gesture coroutine restarts caused by scrubbing state mutations.
 * Both discrete seek taps and continuous scrub dragging are handled seamlessly without gesture conflicts.
 */
@Composable
private fun IslandScrubber(
    fraction: () -> Float,
    isScrubbing: Boolean,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrubMove by rememberUpdatedState(onScrubMove)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentOnScrubCancel by rememberUpdatedState(onScrubCancel)

    val baseKnobRadius = 4.5.dp
    val baseKnobRadiusPx = with(density) { baseKnobRadius.toPx() }
    val trackHeight = with(density) { 5.dp.toPx() }

    val thumbScale by animateFloatAsState(
        targetValue = if (isScrubbing) 1.35f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "ScrubberThumbScale"
    )

    Canvas(
        modifier = modifier
            .height(24.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val usable = (size.width - 2f * baseKnobRadiusPx).coerceAtLeast(1f)
                    fun xToFraction(x: Float): Float =
                        ((x - baseKnobRadiusPx) / usable).coerceIn(0f, 1f)

                    val startFraction = xToFraction(down.position.x)
                    var lastFraction = startFraction
                    currentOnScrubStart(startFraction)

                    val pointerId = down.id
                    var committed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (change.isConsumed) break
                            change.consume()
                            lastFraction = xToFraction(change.position.x)
                            if (!change.pressed) {
                                committed = true
                                currentOnScrubEnd(lastFraction)
                                break
                            }
                            currentOnScrubMove(lastFraction)
                        }
                    } finally {
                        if (!committed) currentOnScrubCancel()
                    }
                }
            }
    ) {
        val centerY = size.height / 2f
        val trackLeft = baseKnobRadiusPx
        val trackRight = (size.width - baseKnobRadiusPx).coerceAtLeast(trackLeft + 1f)
        val trackWidth = trackRight - trackLeft
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

        drawRoundRect(
            color = SCRUBBER_TRACK_COLOR,
            topLeft = Offset(trackLeft, centerY - trackHeight / 2f),
            size = Size(trackWidth, trackHeight),
            cornerRadius = radius
        )

        val filled = trackWidth * fraction().coerceIn(0f, 1f)
        val knobCenter = Offset(trackLeft + filled, centerY)

        if (filled > 0f) {
            // 1. Soft gradient line along the active played track
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0x33FFFFFF), Color(0x66FFFFFF), Color.White),
                    startX = trackLeft,
                    endX = knobCenter.x
                ),
                topLeft = Offset(trackLeft, centerY - trackHeight / 2f),
                size = Size(filled, trackHeight),
                cornerRadius = radius
            )

            // 2. Dynamic tapering luminous comet trail behind the knob
            val trailLength = (baseKnobRadiusPx * 6f).coerceAtMost(filled)
            if (trailLength > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color(0x66FFFFFF), Color.White),
                        startX = knobCenter.x - trailLength,
                        endX = knobCenter.x
                    ),
                    topLeft = Offset(knobCenter.x - trailLength, centerY - (trackHeight * 1.5f) / 2f),
                    size = Size(trailLength, trackHeight * 1.5f),
                    cornerRadius = CornerRadius((trackHeight * 1.5f) / 2f)
                )
            }

            // 3. Multi-tier luminous radial neon bloom glow centered on the knob
            val glowRadius = baseKnobRadiusPx * 3.5f * thumbScale
            withTransform({
                translate(knobCenter.x, centerY)
                scale(glowRadius, glowRadius, Offset.Zero)
            }) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFFFFF),
                            Color(0x99FFFFFF),
                            Color(0x44FFFFFF),
                            Color(0x11FFFFFF),
                            Color.Transparent
                        ),
                        center = Offset.Zero,
                        radius = 1f
                    ),
                    radius = 1f,
                    center = Offset.Zero
                )
            }
        }

        drawCircle(
            color = Color.White,
            radius = baseKnobRadiusPx * thumbScale,
            center = knobCenter
        )
    }
}

private val SCRUBBER_TRACK_COLOR = Color(0x2EFFFFFF)
private val SCRUBBER_GLOW_BRUSH = Brush.radialGradient(
    colors = listOf(Color(0x66FFFFFF), Color(0x1FFFFFFF), Color.Transparent),
    center = Offset.Zero, radius = 1f
)

private val FLAT_BARS = List(5) { 0.12f }

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val sTen = (seconds / 10).toInt()
    val sOne = (seconds % 10).toInt()
    return "$minutes:$sTen$sOne"
}
