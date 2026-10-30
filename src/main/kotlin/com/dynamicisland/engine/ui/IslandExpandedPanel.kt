package com.dynamicisland.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dynamicisland.core.geometry.AppearancePreset
import com.dynamicisland.core.material.LiquidGlassPolicy
import com.dynamicisland.core.motion.ScrubCollapseController
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.*
import com.dynamicisland.engine.ui.components.*

/**
 * The 4-Region Expanded Dynamic Island Presentation.
 *
 * Implements:
 * - 1:1 finger-scrubbable upward collapse (`ScrubCollapseController`) interpolating corner radius,
 *   scale, and content opacity in real time with velocity-aware release completion
 * - `.keylineTint` 1dp outer perimeter border and 44dp continuous corner rounding
 * - Dedicated presenters for Media, Calls, Timers, Turn-by-Turn Navigation, Live Delivery Journeys,
 *   File Downloads, and the Notification Inbox
 */
@Composable
fun IslandExpandedPanel(
    state: IslandState,
    playback: MediaPlaybackSnapshot,
    call: SystemIngestionEvent.Telecom.CallStateChanged?,
    notificationCount: Int,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSource: (IslandActivity) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCommand: (IslandUserCommand) -> Unit,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    actionStatus: String? = null,
    options: com.dynamicisland.engine.settings.BehaviorOptions = com.dynamicisland.engine.settings.BehaviorOptions()
) {
    val activity = (state as? IslandState.Expanded)?.activity
    val close by rememberUpdatedState(onClose)
    val density = LocalDensity.current
    // Low-power drops the blur: it is the case where the system has already asked
    // every app to composite more cheaply.
    val context = LocalContext.current
    val powerSaveMode = remember(context) {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }
    val contentHeight = (196 + (density.fontScale.coerceIn(1f, 2f) - 1) * 90).dp

    var cumulativeDragYPx by remember { mutableFloatStateOf(0f) }
    var lastVelocityYPxPerSec by remember { mutableFloatStateOf(0f) }

    val scrubState = remember(cumulativeDragYPx) {
        ScrubCollapseController.interpolate(
            cumulativeDeltaYPx = cumulativeDragYPx,
            expandedWidthPx = with(density) { 366.dp.toPx() },
            expandedHeightPx = with(density) { contentHeight.toPx() },
            compactWidthPx = with(density) { 125.dp.toPx() },
            compactHeightPx = with(density) { 37.dp.toPx() },
            expandedRadiusDp = 36f,
            compactRadiusDp = 18.5f
        )
    }

    val keylineColor = remember(activity?.keylineTintHex, options.enableKeylineTint) {
        if (!options.enableKeylineTint) Color.White.copy(alpha = 0.14f)
        else activity?.keylineTintHex?.let {
            runCatching { Color(android.graphics.Color.parseColor(it)).copy(alpha = 0.55f) }.getOrNull()
        } ?: Color.White.copy(alpha = 0.16f)
    }

    val headerLabel = when (activity?.kind) {
        ActivityKind.MEDIA_SESSION -> "Now playing"
        ActivityKind.NAVIGATION -> "Navigation"
        ActivityKind.LIVE_JOURNEY -> "Live Activity · Delivery"
        ActivityKind.FILE_DOWNLOAD -> "Download"
        ActivityKind.LIVE_TIMER, ActivityKind.LIVE_STOPWATCH -> "Timer"
        ActivityKind.LIVE_CALL, ActivityKind.RINGING_CALL, ActivityKind.EMERGENCY_CALL -> "Phone Call"
        else -> "Dynamic Island"
    }

    val shape = RoundedCornerShape(scrubState.cornerRadiusDp.dp)

    // Gap G5 — Liquid Glass as a real material with anti-stacking.
    //
    // `AppearancePreset.LIQUID_GLASS` previously did nothing here beyond an alpha
    // swap on the compact capsule. Doc 2 requires adaptive translucency to lift
    // the controls *and* explicitly forbids stacking glass-on-glass across parent
    // and child views, "which creates visual noise and wastes GPU resources".
    //
    // `LiquidGlassPolicy.resolveSurfaces` enforces that: the card blurs, and the
    // controls inside it are demoted to a solid tint so only one layer in the
    // chain pays for an offscreen buffer. Reduce-transparency and low-power drop
    // the blur entirely rather than degrading it.
    val glassSurfaces = remember(
        options.appearance,
        options.reducedMotion,
        powerSaveMode
    ) {
        LiquidGlassPolicy.resolveSurfaces(
            chain = listOf(LiquidGlassPolicy.GlassSurface.CARD, LiquidGlassPolicy.GlassSurface.CONTROL),
            appearanceIsLiquidGlass = options.appearance == AppearancePreset.LIQUID_GLASS,
            reduceTransparency = options.reducedMotion,
            lowPowerMode = powerSaveMode
        )
    }
    val cardSurface = glassSurfaces.first()
    val controlSurface = glassSurfaces.last()

    // The island masks the physical cutout, so the card must stay opaque black
    // unless glass was actually resolved. A translucent card over the sensor
    // housing would reveal the camera aperture instead of hiding it.
    val cardBackground = when (cardSurface.treatment) {
        LiquidGlassPolicy.SurfaceTreatment.GLASS ->
            Color.White.copy(alpha = cardSurface.tintAlpha)
        LiquidGlassPolicy.SurfaceTreatment.SOLID_TINT ->
            Color(0xFF1C1C1E)
        LiquidGlassPolicy.SurfaceTreatment.NONE -> Color.Black
    }

    // Doc 2 requires 4.5:1 for body text and 3:1 for large text on these
    // surfaces. The tint is album-derived, so it can land anywhere; enforce the
    // ratio rather than hoping the artwork cooperates.
    val headerTextColor = remember(cardBackground, density.fontScale) {
        val backgroundArgb = cardBackground.toArgb()
        val foregroundArgb = LiquidGlassPolicy.ensureContrast(
            foregroundArgb = Color.White.toArgb(),
            backgroundArgb = backgroundArgb,
            textSizeSp = 15f * density.fontScale,
            isBoldOrHeavier = true
        )
        Color(foregroundArgb)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, surface = Color.Black)) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scrubState.scale
                    scaleY = scrubState.scale
                    alpha = scrubState.expandedContentAlpha.coerceAtLeast(0.25f)
                }
                .clip(shape)
                .background(cardBackground)
                .border(1.dp, keylineColor, shape)
                .semantics {
                    contentDescription = "Expanded island"
                    paneTitle = headerLabel
                }
        ) {
            // Header bar with 1:1 finger-scrubbable upward collapse gesture & 48x48dp close target
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                cumulativeDragYPx = 0f
                                lastVelocityYPxPerSec = 0f
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                cumulativeDragYPx += amount
                                lastVelocityYPxPerSec = amount * 60f
                            },
                            onDragEnd = {
                                val shouldClose = ScrubCollapseController.shouldCollapseOnRelease(
                                    progress = scrubState.progress,
                                    velocityYPxPerSec = lastVelocityYPxPerSec
                                ) || cumulativeDragYPx < -24.dp.toPx()
                                cumulativeDragYPx = 0f
                                lastVelocityYPxPerSec = 0f
                                if (shouldClose) close()
                            },
                            onDragCancel = {
                                cumulativeDragYPx = 0f
                                lastVelocityYPxPerSec = 0f
                            }
                        )
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (activity?.isStale == true) "$headerLabel · Stale" else headerLabel,
                    style = MaterialTheme.typography.labelLarge,
                    // Gap G5: the stale amber is contrast-checked against the
                    // resolved surface too, so a glass tint cannot make it fail.
                    color = if (activity?.isStale == true) {
                        Color(
                            LiquidGlassPolicy.ensureContrast(
                                foregroundArgb = Color(0xFFFF9F0A).toArgb(),
                                backgroundArgb = cardBackground.toArgb(),
                                textSizeSp = 15f * density.fontScale,
                                isBoldOrHeavier = true
                            )
                        )
                    } else {
                        headerTextColor
                    },
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        // Gap G5: the control sits inside the card, so the policy
                        // hands it SOLID_TINT rather than a second blur. When the
                        // card is plain black the treatment is NONE and nothing is
                        // drawn, which keeps the existing look.
                        .then(
                            if (controlSurface.treatment == LiquidGlassPolicy.SurfaceTreatment.SOLID_TINT) {
                                Modifier.background(Color.White.copy(alpha = controlSurface.tintAlpha))
                            } else {
                                Modifier
                            }
                        )
                        .clickable(onClick = onClose)
                        .semantics { contentDescription = "Collapse island" },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * .2f, size.height * .4f)
                            lineTo(size.width * .5f, size.height * .7f)
                            lineTo(size.width * .8f, size.height * .4f)
                        }
                        drawPath(
                            path,
                            Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                2.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }
                }
            }

            if (state is IslandState.NotificationInbox) {
                NotificationInboxContent(
                    state.notifications,
                    onOpenNotification = { onCommand(IslandUserCommand.OpenNotification(it)) },
                    onClearAll = { onCommand(IslandUserCommand.ClearInbox) },
                    onDismissNotification = { onCommand(IslandUserCommand.DismissNotification(it)) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when {
                        activity?.kind == ActivityKind.MEDIA_SESSION -> key(activity.ownerPackage, activity.title) {
                            MediaExpandedContent(
                                activity.title,
                                activity.subtitle,
                                (activity.compactLeading as? IslandSlotContent.Icon)?.iconBitmap,
                                playback,
                                onPlayPause,
                                onNext,
                                onPrevious,
                                onSeek,
                                notificationCount,
                                onOpenInbox = { onCommand(IslandUserCommand.OpenInbox) },
                                modifier = Modifier.height(contentHeight)
                            )
                        }
                        call != null && activity?.kind in setOf(
                            ActivityKind.LIVE_CALL,
                            ActivityKind.RINGING_CALL,
                            ActivityKind.EMERGENCY_CALL
                        ) -> CallExpandedContent(
                            call.callerName ?: "Phone call",
                            call.callerNumber,
                            "${call.durationSeconds / 60}:${(call.durationSeconds % 60).toString().padStart(2, '0')}",
                            call.status == SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,
                            call.isMicrophoneMuted,
                            call.isSpeakerphoneOn,
                            onAnswer = onAnswer,
                            onHangup = onHangup,
                            onToggleMute = onMute,
                            onToggleSpeaker = onSpeaker,
                            isControllable = call.isControllable,
                            isOnHold = call.status == SystemIngestionEvent.Telecom.CallStatus.ON_HOLD,
                            modifier = Modifier.height(contentHeight)
                        )
                        activity?.kind == ActivityKind.NAVIGATION -> NavigationExpandedContent(
                            activity = activity,
                            onOpenSource = { onOpenSource(activity) },
                            onExecuteAction = { actionId -> onCommand(IslandUserCommand.ExecuteAction(activity.id, actionId)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        activity?.kind == ActivityKind.LIVE_JOURNEY -> JourneyExpandedContent(
                            activity = activity,
                            onOpenSource = { onOpenSource(activity) },
                            onExecuteAction = { actionId -> onCommand(IslandUserCommand.ExecuteAction(activity.id, actionId)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        activity?.kind == ActivityKind.FILE_DOWNLOAD -> DownloadExpandedContent(
                            activity = activity,
                            onOpenSource = { onOpenSource(activity) },
                            onExecuteAction = { actionId -> onCommand(IslandUserCommand.ExecuteAction(activity.id, actionId)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        else -> Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                activity?.title ?: "You're all caught up",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                            activity?.subtitle?.let { Text(it, color = Color.LightGray) }
                            if (activity != null) {
                                TimerActions.cancellation(activity)?.let {
                                    TextButton(
                                        onClick = { onCommand(IslandUserCommand.CancelTimer(activity.id)) },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Cancel timer") }
                                }
                                if (activity.deepLinkIntent != null) {
                                    TextButton(
                                        onClick = { onOpenSource(activity) },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Open app") }
                                }
                            }
                            if (notificationCount > 0) {
                                TextButton(
                                    onClick = { onCommand(IslandUserCommand.OpenInbox) },
                                    modifier = Modifier.heightIn(min = 44.dp)
                                ) { Text("Notifications · $notificationCount") }
                            }
                            if (activity == null) {
                                TextButton(
                                    onClick = onOpenApp,
                                    modifier = Modifier.heightIn(min = 44.dp)
                                ) { Text("Island settings") }
                            }
                        }
                    }
                    actionStatus?.let {
                        Text(it, color = Color.LightGray, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
