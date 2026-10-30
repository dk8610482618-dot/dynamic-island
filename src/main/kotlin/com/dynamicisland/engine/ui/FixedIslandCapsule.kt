package com.dynamicisland.engine.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.core.geometry.*
import com.dynamicisland.core.motion.MetaballGeometry
import com.dynamicisland.core.motion.ScrubCollapseController
import com.dynamicisland.core.privacy.LockStatePrivacyPolicy
import com.dynamicisland.engine.core.model.*
import com.dynamicisland.engine.settings.BehaviorOptions
import com.dynamicisland.engine.ui.components.AudioWaveformEqualizer
import com.dynamicisland.engine.ui.icons.IslandGlyph
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flagship Compact & Minimal Dynamic Island Capsule.
 *
 * Implements:
 * - Zero internal margin against the physical sensor cutout & concentric outer rounding
 * - Apple-Parity single-tap `widgetURL` deep-link vs. long-press expansion with `0.985f` press compression
 * - `.keylineTint` 1dp adaptive brand/artwork perimeter border
 * - 2D Metaball ferrofluid droplet detachment bridge shader (`MetaballGeometry`)
 * - Width-limited landscape 28dp circular condensation (`isDynamicIslandLimitedInWidth`)
 * - Stale activity dimming (`activity.isStale`) and Smart Lock Screen privacy redaction
 * - Optional Idle Mini-Status Bar mode (live clock + battery/connectivity glyph)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FixedIslandCapsule(
    state: IslandState,
    playback: MediaPlaybackSnapshot,
    locked: Boolean,
    rotation: Float,
    onOpen: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPlayPause: () -> Unit,
    options: BehaviorOptions = BehaviorOptions(),
    onGesture: ((GestureAction) -> Unit)? = null,
    secondary: Boolean = false,
    cornerRadius: Dp? = null,
    microphoneActive: Boolean = false,
    cameraActive: Boolean = false,
    isLandscape: Boolean = false
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val activity = when (state) {
            is IslandState.Compact -> state.activity
            is IslandState.Minimal -> if (secondary) state.secondary else state.primary
            is IslandState.Expanded -> state.activity
            else -> null
        }
        val media = activity?.kind == ActivityKind.MEDIA_SESSION
        val bitmap = if (!locked && media) (activity?.compactLeading as? IslandSlotContent.Icon)?.iconBitmap else null
        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        val symbol = when {
            locked -> IslandSymbol.BELL
            state is IslandState.AlertPeek -> (state.alert.leadingSlot as? IslandSlotContent.Symbol)?.symbol ?: IslandSymbol.BELL
            activity?.kind in listOf(ActivityKind.LIVE_CALL, ActivityKind.RINGING_CALL, ActivityKind.EMERGENCY_CALL) -> IslandSymbol.PHONE
            activity?.kind == ActivityKind.LIVE_TIMER -> IslandSymbol.TIMER
            activity?.kind == ActivityKind.LIVE_STOPWATCH -> IslandSymbol.TIMER
            activity?.kind == ActivityKind.NAVIGATION -> IslandSymbol.NAVIGATION
            activity?.kind == ActivityKind.LIVE_JOURNEY -> IslandSymbol.DELIVERY
            activity?.kind == ActivityKind.FILE_DOWNLOAD -> IslandSymbol.DOWNLOAD
            activity?.kind == ActivityKind.BATTERY_CHARGING -> IslandSymbol.BOLT
            activity?.kind == ActivityKind.ACCESSORY_BATTERY -> IslandSymbol.HEADSET
            media -> IslandSymbol.MUSIC_NOTE
            else -> IslandSymbol.BELL
        }

        val dispatch by rememberUpdatedState(onGesture)
        val currentOptions by rememberUpdatedState(options)
        val visibleMedia by rememberUpdatedState(media)
        val hasActivity by rememberUpdatedState(activity != null || state is IslandState.AlertPeek)
        var drag by remember { mutableFloatStateOf(0f) }

        fun route(action: GestureAction) {
            val allowed = GesturePolicy.allowed(action, visibleMedia, locked)
            if (allowed == GestureAction.NONE) return
            if (dispatch != null) dispatch?.invoke(allowed)
            else if (allowed == GestureAction.OPEN_DASHBOARD || allowed == GestureAction.OPEN_SOURCE) onOpen()
        }

        val parsedKeylineColor = remember(activity?.keylineTintHex, options.enableKeylineTint, options.appearance) {
            when {
                options.appearance == AppearancePreset.HIGH_CONTRAST -> Color.White
                options.appearance == AppearancePreset.LIQUID_GLASS -> Color.White.copy(alpha = 0.38f)
                options.enableKeylineTint && activity?.keylineTintHex != null ->
                    parseHexColor(activity.keylineTintHex, Color.White.copy(alpha = 0.28f)).copy(alpha = 0.65f)
                else -> Color.Transparent
            }
        }

        val tint = when {
            activity?.keylineTintHex != null && options.enableKeylineTint ->
                parseHexColor(activity.keylineTintHex, Color.White)
            options.appearance == AppearancePreset.SOFT_ACCENT -> Color(0xFFB7D5C3)
            else -> Color.White
        }

        val duration = if (options.reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()) 0 else when (options.motion) {
            MotionPreset.NONE -> 0
            MotionPreset.GENTLE -> 180
            MotionPreset.RESPONSIVE -> 90
        }

        val isLandscape = rotation == 90f || rotation == -90f
        val widthLimitedSpec = remember(isLandscape, options.landscapeBehavior) {
            if (options.landscapeBehavior == LandscapeBehavior.ADAPTIVE_LIMITED_WIDTH) {
                ExpandedRegionLayoutSolver.resolveWidthLimitedCompact(isLandscape = isLandscape, availableCapsuleWidthDp = if (isLandscape) 96f else 125f)
            } else {
                ExpandedRegionLayoutSolver.WidthLimitedCompactSpec(false, 34f, true, true, false)
            }
        }

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed && !options.reducedMotion) ScrubCollapseController.PRE_EXPANSION_PRESS_SCALE else 1f,
            animationSpec = tween(durationMillis = 60),
            label = "PressCompressionScale"
        )

        val staleAlpha = if (activity?.isStale == true) 0.52f else 1f
        val shape = if (cornerRadius != null) RoundedCornerShape(cornerRadius) else RoundedCornerShape(percent = 50)

        val a11yDescription = remember(locked, secondary, activity, state) {
            if (locked && activity != null) {
                val redacted = LockStatePrivacyPolicy.resolveSummaryForKind(
                    kindName = activity.kind.name,
                    originalTitle = activity.title,
                    originalSubtitle = activity.subtitle,
                    isKeyguardLocked = true
                )
                "Dynamic Island. ${redacted.title}. ${redacted.subtitle}"
            } else if (locked) {
                "Dynamic Island. Unlock to open controls"
            } else if (secondary) {
                "Secondary activity. ${activity?.title ?: "Open controls"}"
            } else if (activity != null) {
                val staleSuffix = if (activity.isStale) ", update delayed" else ""
                "Primary island. ${activity.title}$staleSuffix"
            } else {
                "Primary island. Open controls"
            }
        }

        // 2D Metaball Ferrofluid Neck Bridge Canvas when Minimal state is active
        if (!secondary && state is IslandState.Minimal && options.enableMetaballShader) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val neck = MetaballGeometry.computeBridgeNeck(
                    primaryRightX = size.width * 0.88f,
                    dropletLeftX = size.width * 0.98f,
                    centerY = size.height / 2f,
                    dropletDiameterPx = size.height,
                    snapThresholdPx = size.width * 0.25f
                )
                if (neck.isConnected && neck.pinchHeight > 1f) {
                    val path = Path().apply {
                        moveTo(neck.leftAnchorX, neck.centerY - neck.pinchHeight * 0.6f)
                        quadraticBezierTo(
                            (neck.leftAnchorX + neck.rightAnchorX) / 2f,
                            neck.centerY - neck.pinchHeight * 0.25f,
                            neck.rightAnchorX,
                            neck.centerY - neck.pinchHeight * 0.5f
                        )
                        lineTo(neck.rightAnchorX, neck.centerY + neck.pinchHeight * 0.5f)
                        quadraticBezierTo(
                            (neck.leftAnchorX + neck.rightAnchorX) / 2f,
                            neck.centerY + neck.pinchHeight * 0.25f,
                            neck.leftAnchorX,
                            neck.centerY + neck.pinchHeight * 0.6f
                        )
                        close()
                    }
                    drawPath(path, Color.Black)
                }
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = pressScale
                    scaleY = pressScale
                    alpha = staleAlpha
                }
                .clip(shape)
                .background(Color.Black)
                .border(
                    width = if (parsedKeylineColor != Color.Transparent) 1.dp else 0.dp,
                    color = parsedKeylineColor,
                    shape = shape
                )
                .pointerInput(locked, media, state) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            drag += amount
                        },
                        onDragEnd = {
                            if (kotlin.math.abs(drag) >= 36.dp.toPx()) {
                                when {
                                    state is IslandState.AlertPeek -> route(GestureAction.DISMISS_ALERT)
                                    state is IslandState.Minimal -> route(GestureAction.CYCLE_ACTIVITY)
                                    else -> route(if (drag < 0) currentOptions.swipeLeftAction else currentOptions.swipeRightAction)
                                }
                            }
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f }
                    )
                }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        val resolved = GesturePolicy.resolvePrimaryTap(
                            mode = currentOptions.interactionMode,
                            configuredTap = currentOptions.tapAction,
                            hasActiveActivity = hasActivity,
                            visibleMedia = visibleMedia,
                            locked = locked
                        )
                        route(resolved)
                    },
                    onLongClick = {
                        val resolved = GesturePolicy.resolvePrimaryHold(
                            mode = currentOptions.interactionMode,
                            configuredHold = currentOptions.holdAction,
                            visibleMedia = visibleMedia,
                            locked = locked
                        )
                        route(resolved)
                    }
                )
                .semantics {
                    contentDescription = a11yDescription
                    if (state is IslandState.AlertPeek) {
                        liveRegion = LiveRegionMode.Polite
                    }
                }
                .padding(horizontal = if (secondary || widthLimitedSpec.isLimitedInWidth) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val slotSize = if (widthLimitedSpec.isLimitedInWidth) 24.dp else 26.dp

            // Leading Slot (compactLeading or minimal droplet telemetry)
            Box(Modifier.size(slotSize), contentAlignment = Alignment.Center) {
                when {
                    secondary && activity?.minimalContent is IslandSlotContent.Progress -> {
                        val prog = activity.minimalContent as IslandSlotContent.Progress
                        val frac = if (prog.max > 0) (prog.current.toFloat() / prog.max.toFloat()).coerceIn(0f, 1f) else 0.5f
                        Canvas(Modifier.size(22.dp)) {
                            drawCircle(Color.White.copy(alpha = 0.2f), style = Stroke(2.2.dp.toPx()))
                            drawArc(
                                color = tint,
                                startAngle = -90f,
                                sweepAngle = 360f * frac,
                                useCenter = false,
                                style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                    image != null -> Image(
                        image,
                        "Album cover",
                        Modifier.size(if (widthLimitedSpec.isLimitedInWidth) 20.dp else 24.dp).clip(RoundedCornerShape(5.dp)),
                        contentScale = ContentScale.Crop
                    )
                    activity != null || state is IslandState.AlertPeek || locked -> Crossfade(
                        symbol,
                        animationSpec = tween(duration),
                        label = "Activity icon"
                    ) { glyph ->
                        IslandGlyph(glyph, tint, Modifier.size(18.dp))
                    }
                    options.idleMiniStatusBar && state is IslandState.Closed -> {
                        val clockText = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
                        Text(
                            text = clockText,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Leave the hardware aperture area clear. Never place controls over the hole.
            if (!secondary) Spacer(Modifier.weight(1f))

            // Trailing Slot (compactTrailing)
            if (!secondary && widthLimitedSpec.showTrailing) {
                when {
                    media && !locked && playback.isPlaying -> {
                        val waveAmps = (activity?.compactTrailing as? IslandSlotContent.AudioWaveform)?.amplitudes
                            ?.takeIf { it.isNotEmpty() } ?: listOf(0.45f, 0.85f, 0.6f, 0.9f)
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            AudioWaveformEqualizer(
                                amplitudes = waveAmps,
                                color = tint,
                                modifier = Modifier.height(16.dp)
                            )
                        }
                    }
                    media && !locked && playback.canPlayPause -> {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            IslandGlyph(
                                if (playback.isPlaying) IslandSymbol.PAUSE else IslandSymbol.PLAY,
                                Color.White,
                                Modifier.size(16.dp)
                            )
                        }
                    }
                    !locked && activity?.kind == ActivityKind.FILE_DOWNLOAD -> {
                        val frac = (activity.expandedData.progressFraction ?: 0.5f).coerceIn(0f, 1f)
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(18.dp)) {
                                drawCircle(Color.White.copy(alpha = 0.2f), style = Stroke(2.dp.toPx()))
                                drawArc(
                                    color = tint,
                                    startAngle = -90f,
                                    sweepAngle = 360f * frac,
                                    useCenter = false,
                                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
                    !locked && (activity?.kind == ActivityKind.NAVIGATION || activity?.kind == ActivityKind.LIVE_JOURNEY) -> {
                        val badge = activity.expandedData.progressLabel?.take(6) ?: "•"
                        Text(
                            text = badge,
                            color = tint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    activity != null || state is IslandState.AlertPeek -> {
                        Text(
                            text = if (activity?.isStale == true) "…" else "•",
                            color = tint,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    options.idleMiniStatusBar && state is IslandState.Closed -> {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            IslandGlyph(IslandSymbol.BATTERY, Color(0xFF30D158), Modifier.size(15.dp))
                        }
                    }
                    else -> Spacer(Modifier.size(26.dp))
                }
            }
        }

        // Tri-state iOS-accurate Privacy Indicator Dot
        val dotColor = when {
            microphoneActive && cameraActive -> Color(0xFF0A84FF) // iOS Blue (Both)
            cameraActive -> Color(0xFF30D158)                     // iOS Green (Camera)
            microphoneActive -> Color(0xFFFF9F0A)                 // iOS Orange (Mic)
            else -> null
        }
        if (!secondary && dotColor != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .background(dotColor, CircleShape)
                    .semantics { contentDescription = "Privacy indicator active" }
            )
        }
    }
}

private fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(fallback)
}
