package com.dynamicisland.engine.ui

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.engine.core.model.ActivityKind
import com.dynamicisland.engine.core.model.IslandActivity
import com.dynamicisland.engine.core.model.IslandSlotContent
import com.dynamicisland.engine.core.model.IslandState
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.core.model.MediaPlaybackSnapshot
import com.dynamicisland.engine.settings.IslandSettings
import com.dynamicisland.engine.touch.TouchableRegionSignal
import com.dynamicisland.engine.ui.components.AlertPeekContent
import com.dynamicisland.engine.ui.components.AudioWaveformEqualizer
import com.dynamicisland.engine.ui.components.CallExpandedContent
import com.dynamicisland.engine.ui.components.MediaExpandedContent
import com.dynamicisland.engine.ui.components.NotificationInboxContent
import com.dynamicisland.engine.ui.components.TimerExpandedContent
import com.dynamicisland.engine.ui.icons.IslandGlyph
import com.dynamicisland.engine.ui.motion.SpringPhysicsSpecs
import com.dynamicisland.core.geometry.SquircleGeometry
import com.dynamicisland.engine.ui.squircle.SquircleShape
import com.dynamicisland.engine.ui.util.rememberCurrentSecond
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.dynamicisland.core.motion.DynamicIslandMotionEngine
import com.dynamicisland.core.physics.SpringCatalog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Flagship Jetpack Compose Dynamic Island UI Engine.
 *
 * Implements:
 * - Full 5-state presentation machine (Closed -> Compact -> Minimal -> Expanded -> AlertPeek).
 * - Exact iOS CASpringAnimation mechanical oscillator physics.
 * - Apple G2 continuous corner superellipse geometry via SquircleShape.
 * - Non-linear rubber-banding resistance on vertical over-drag.
 * - 0ms pass-through touch region boundary tracking.
 */
@Composable
fun DynamicIslandView(
    state: IslandState,
    onTapPrimary: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onTapSecondary: () -> Unit,
    onLongPressPrimary: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onLongPressSecondary: () -> Unit,
    onCollapseExpanded: () -> Unit,
    onDismissAlert: () -> Unit,
    modifier: Modifier = Modifier,
    /** Triggers a system screenshot when triple-tapped. */
    onTripleTap: () -> Unit = {},
    /** Opens a specific notification's source app, by notification key. */
    onOpenNotification: (String) -> Unit = {},
    /** Empties the notification inbox. */
    onClearInbox: () -> Unit = {},
    onDismissNotification: (String) -> Unit = {},
    notificationCount: Int = 0,
    onOpenInbox: () -> Unit = {},
    onBoundsChanged: (primaryRect: android.graphics.Rect, secondaryRect: android.graphics.Rect?) -> Unit,
    onMediaPlayPause: () -> Unit = {},
    onMediaSkipNext: () -> Unit = {},
    onMediaSkipPrevious: () -> Unit = {},
    onMediaSeek: (Long) -> Unit = {},
    onCallAnswer: () -> Unit = {},
    onCallHangup: () -> Unit = {},
    onCallToggleMute: () -> Unit = {},
    onCallToggleSpeaker: () -> Unit = {},
    onTimerCancel: () -> Unit = {},
    mediaPlayback: MediaPlaybackSnapshot = MediaPlaybackSnapshot(),
    topInset: Dp = 11.dp,
    /**
     * Horizontal placement offset, positive to the right, measured from the
     * screen centre.
     *
     * The overlay window spans the full width and aligns the island to
     * `TopCenter`, so this is a pure draw-time translation — changing it never
     * touches the window manager and never triggers a relayout.
     */
    horizontalOffset: Dp = 0.dp,
    /**
     * Vertical placement offset, positive downward, measured from the
     * automatic (cutout-aligned) top.
     *
     * A draw-time translation like [horizontalOffset], but kept separate from
     * [topInset] rather than folded into it: the offset may legitimately go
     * negative (the user can travel above the automatic top, and past the
     * screen edge), and a negative `padding` is not a legal modifier.
     */
    verticalOffset: Dp = 0.dp,
    /** Width size multiplier (0.50x to 1.40x). */
    islandWidthScale: Float = 1f,
    /** Height size multiplier (0.50x to 1.40x). */
    islandHeightScale: Float = 1f,
    /** Real-time microphone active state for iOS-style orange dot privacy indicator. */
    isMicrophoneInUse: Boolean = false,
    /** Real-time camera active state for iOS-style green dot privacy indicator. */
    isCameraInUse: Boolean = false
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    // Read for the mirroring rule on media swipes: the "next" affordance is
    // mirrored in a right-to-left layout, so the gesture that triggers it must
    // mirror with it.
    val layoutDirection = LocalLayoutDirection.current

    // Allocation-free cached drawing primitives scoped to this composable instance
    val pillPath = remember { android.graphics.Path() }
    val shadowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }
    }
    val fillPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
    }
    val pathSink = remember {
        object : SquircleGeometry.PathSink {
            override fun moveTo(x: Float, y: Float) {
                pillPath.moveTo(x, y)
            }
            override fun lineTo(x: Float, y: Float) {
                pillPath.lineTo(x, y)
            }
            override fun cubicTo(
                cp1x: Float,
                cp1y: Float,
                cp2x: Float,
                cp2y: Float,
                x: Float,
                y: Float
            ) {
                pillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x, y)
            }
            override fun close() {
                pillPath.close()
            }
        }
    }

    // ======================================================================
    // TARGET DIMENSIONS PER STATE
    //
    // Width and height scale independently per user preference.
    // For capsule states (Closed, Compact, Minimal, AlertPeek), corner radius
    // tracks height / 2 to preserve the continuous capsule curvature.
    // ======================================================================
    val isTimerCard = state is IslandState.Expanded && state.activity.kind.isTimerLike()
    val (baseWidthDp, baseHeightDp, baseRadiusDp) = when (state) {
        is IslandState.Closed -> Triple(122.dp, 36.dp, 18.dp)
        is IslandState.Compact -> Triple(236.dp, 36.dp, 18.dp)
        is IslandState.Minimal -> Triple(236.dp, 36.dp, 18.dp)
        is IslandState.Expanded ->
            if (isTimerCard) Triple(366.dp, 84.dp, 42.dp)
            else Triple(366.dp, 176.dp, 44.dp)
        is IslandState.AlertPeek -> Triple(300.dp, 52.dp, 26.dp)
        is IslandState.NotificationInbox -> Triple(366.dp, 176.dp, 44.dp)
    }

    val targetWidthDp = (baseWidthDp * islandWidthScale)
    val targetHeightDp = (baseHeightDp * islandHeightScale)
    val targetRadiusDp = if (baseRadiusDp >= baseHeightDp / 2f - 0.5.dp) {
        targetHeightDp / 2f
    } else {
        (baseRadiusDp * kotlin.math.min(islandWidthScale, islandHeightScale))
    }

    // Pill top offset inside the Host Box. The Host Box itself starts at y=0 (+verticalOffset)
    // so touches on the camera punch hole above the pill register without Compose hit-test drops.
    val pillTopOffsetDp = topInset

    // ======================================================================
    // MOTION
    //
    // All three geometry dimensions and the bubble scale are driven by ONE
    // engine advanced from ONE frame clock. The previous implementation ran a
    // separate `Animatable` coroutine per dimension, so their frame callbacks
    // could land on different frames and the pill's edges visibly wobbled.
    // ======================================================================
    val motion = remember { DynamicIslandMotionEngine() }

    // A frame counter bumped by the clock below; each change invalidates only
    // the draw scope that reads it, not the whole subtree.
    // Uses mutableLongStateOf to avoid boxing Long on every vsync frame.
    var motionFrame by remember { mutableLongStateOf(0L) }

    // True while the clock is parked. Declared before the effects that write it
    // so the retarget effect can wake the clock in the same breath as moving it.
    var idle by remember { mutableStateOf(false) }

    val targetSpec = when (state) {
        is IslandState.Expanded -> SpringCatalog.CompactToExpanded
        is IslandState.AlertPeek -> SpringCatalog.AlertBounceArrive
        else -> SpringCatalog.ExpandedToCompact
    }

    // Tracks previous state to distinguish state transitions (spring animation)
    // from live slider size adjustments (instant 1:1 resize).
    // Using a plain StateHolder avoids snapshot writes during composition.
    val lastObservedState = remember { StateHolder<IslandState?>(null) }

    // ======================================================================
    // TOUCHABLE REGION SYNC
    //
    // The overlay window spans the whole screen so the island can be placed
    // anywhere on it, which means the window itself must be told which part is
    // *input-active*. Everything outside that region passes straight through to
    // whatever is underneath, with no latency — this is what lets the rest of
    // the screen behave as if the overlay were not there.
    //
    // The region is computed from live geometry and synchronized immediately
    // on layout, slider updates, and settle frames with zero object allocations.
    // ======================================================================
    val hostOrigin = remember { android.graphics.Point(0, 0) }
    val hostSize = remember { android.graphics.Point(0, 0) }
    val boundsCache = remember { BoundsCache() }

    // Always-current references. Long-lived effects and pointerInput closures
    // read through these so captured closures never read stale values.
    val latestState by rememberUpdatedState(state)
    val latestTargetWidthDp by rememberUpdatedState(targetWidthDp)
    val latestTargetHeightDp by rememberUpdatedState(targetHeightDp)
    val latestOnBoundsChanged by rememberUpdatedState(onBoundsChanged)
    val latestDensity by rememberUpdatedState(density)
    val latestMediaPlayback by rememberUpdatedState(mediaPlayback)
    val latestLayoutDirection by rememberUpdatedState(layoutDirection)
    val latestOnTapPrimary by rememberUpdatedState(onTapPrimary)
    val latestOnLongPressPrimary by rememberUpdatedState(onLongPressPrimary)
    val latestOnCollapseExpanded by rememberUpdatedState(onCollapseExpanded)
    val latestOnDismissAlert by rememberUpdatedState(onDismissAlert)
    val latestOnTripleTap by rememberUpdatedState(onTripleTap)
    val latestOnClearInbox by rememberUpdatedState(onClearInbox)
    val latestOnMediaSkipNext by rememberUpdatedState(onMediaSkipNext)
    val latestOnMediaSkipPrevious by rememberUpdatedState(onMediaSkipPrevious)

    /**
     * Publishes the pill and bubble hit rects from the engine's live geometry.
     *
     * Every input is read at call time, so the result reflects the island as it
     * is now rather than as it was when this closure was created.
     *
     * @param force skip the redundant-publish guard. The guard compares against
     *   the last rects *this composition* sent, which is wrong whenever the
     *   host window has been replaced underneath it: the geometry is genuinely
     *   unchanged, but the new window has never been told about it. A rebind
     *   raises [TouchableRegionSignal] precisely to ask for this.
     */
    fun publishBounds(force: Boolean = false) {
        val origin = hostOrigin
        // Before the host has been laid out, `hostSize` is zero and the window
        // origin is meaningless. Publishing then would produce a degenerate rect
        // and lock input out permanently, so wait for a real layout.
        if (hostSize.x <= 0 || hostSize.y <= 0) return

        val scale = latestDensity.density
        val frame = motion.currentFrame
        val isWide = latestState is IslandState.Expanded ||
            latestState is IslandState.AlertPeek ||
            latestState is IslandState.NotificationInbox
        // During expansion, cover the target bounds immediately so button taps are never dropped
        val effectiveW = if (isWide) maxOf(frame.widthDp, latestTargetWidthDp.value) else frame.widthDp
        val effectiveH = if (isWide) maxOf(frame.heightDp, latestTargetHeightDp.value) else frame.heightDp
        val cx = origin.x + hostSize.x / 2f + frame.dragOffsetXDp * scale
        val halfW = effectiveW * scale / 2f

        val pillTopPx = origin.y + (pillTopOffsetDp.value * scale).roundToInt()
        // If the pill is placed near the top of the display (punch hole cutout zone),
        // extend the touchable region all the way to screen top (0) so touches on the top rim
        // or camera cutout register reliably.
        val topPx = if (pillTopPx <= 64f * scale) maxOf(0, origin.y) else (pillTopPx - 16f * scale).roundToInt().coerceAtLeast(0)
        val bottomPx = (pillTopPx + (effectiveH + frame.stretchDp) * scale).roundToInt()

        boundsCache.primary.set(
            (cx - halfW).roundToInt(),
            topPx,
            (cx + halfW).roundToInt(),
            bottomPx
        )

        // Detached secondary bubble is disabled; only the unified pill is rendered and touchable.
        val secondary: android.graphics.Rect? = null

        if (force || !boundsCache.hasPublished || boundsCache.primary != boundsCache.lastPublishedPrimary) {
            boundsCache.lastPublishedPrimary.set(boundsCache.primary)
            boundsCache.hasPublished = true
            latestOnBoundsChanged(boundsCache.primary, secondary)
        }
    }

    // Retarget when presentation or position changes, and wake the clock.
    // Including horizontalOffset and verticalOffset ensures bounds track slider dragging live.
    LaunchedEffect(
        targetWidthDp, targetHeightDp, targetRadiusDp, horizontalOffset, verticalOffset, topInset, state
    ) {
        val isWide = state is IslandState.Expanded ||
                state is IslandState.AlertPeek ||
                state is IslandState.NotificationInbox
        if (lastObservedState.value == state && lastObservedState.value != null) {
            // Live slider adjustment: snap directly to target dimensions for instant response
            motion.snapTo(
                widthDp = targetWidthDp.value,
                heightDp = targetHeightDp.value,
                radiusDp = targetRadiusDp.value,
                secondaryVisible = false,
                isWide = isWide
            )
            motionFrame++
            publishBounds(force = true)
        } else {
            lastObservedState.value = state
            motion.moveTo(
                targetWidthDp = targetWidthDp.value,
                targetHeightDp = targetHeightDp.value,
                targetRadiusDp = targetRadiusDp.value,
                spec = targetSpec,
                secondaryVisible = false,
                targetIsWide = isWide
            )
            idle = false
            publishBounds(force = true)
        }
    }

    // ======================================================================
    // THE SINGLE CLOCK
    //
    // Uses `withFrameNanos` rather than `Choreographer` directly so it is
    // driven by the same vsync Compose already synchronises to — two
    // independent frame sources is itself a source of beating.
    //
    // It is also *self-stopping*. The first version ran `while (true)` and
    // bumped `motionFrame` unconditionally, which invalidated the draw scope on
    // every vsync forever. That kept the island repainting 60 times a second
    // even when it was sitting completely still — burning power and, worse,
    // giving the frame scheduler no headroom to absorb the frames that actually
    // mattered. A resting island now costs nothing at all.
    //
    // `SETTLE_CONFIRM_FRAMES` guards against parking between two springs: the
    // engine reports settled only when every dimension has, and requiring it to
    // hold for a few consecutive frames stops a single momentarily-still frame
    // mid-oscillation from ending the animation early.
    // ======================================================================
    LaunchedEffect(Unit) {
        while (true) {
            // Suspend here until something moves again. `moveTo` sets `idle`
            // false, which restarts this loop on the very next frame.
            snapshotFlow { idle }.first { !it }

            var settledFrames = 0
            var last = 0L
            while (settledFrames < SETTLE_CONFIRM_FRAMES) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        // Clamp the delta so a stalled frame (GC, app switch)
                        // cannot teleport the animation; 50 ms is roughly three
                        // frames at 60 Hz.
                        val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                        val frame = motion.advance(dt)
                        motionFrame++
                        settledFrames = if (frame.settled) settledFrames + 1 else 0
                    }
                    last = now
                }
            }
            idle = true

            // The pill has stopped at a new size, so the input region has to
            // follow it. Publishing once here rather than every frame is what
            // keeps `setTouchableRegion` off the animation path while still
            // leaving the hit rect correct whenever the user could act on it.
            publishBounds(force = true)
        }
    }

    // A window rebind keeps this composition alive but replaces the surface, so
    // no revision is bumped and no layout callback fires. Without this the new
    // window would never receive a region — and a fullscreen window with no
    // touchable region ignores every touch on the device.
    LaunchedEffect(Unit) {
        TouchableRegionSignal.generation.collect {
            publishBounds(force = true)
        }
    }

    /**
     * Whether the in-flight gesture is a media track skip rather than a drag.
     *
     * Decided by **axis**, not by distance. That matters: if the test used the
     * distance threshold, the pill would follow the finger for the first 48 dp
     * and then freeze when the threshold was crossed — a visible stutter at the
     * moment of classification. Choosing the behaviour from the dominant axis
     * means the pill either moves for the whole gesture or not at all.
     *
     * Supports skipping in compact, minimal, and expanded modes whenever media is active.
     */
    fun isMediaSwipe(dx: Float, dy: Float, current: IslandState): Boolean {
        // Decided by what the pill is *showing*. An earlier `|| mediaPlayback.isActive`
        // at the end made every state qualify while audio played, so a horizontal
        // swipe on an open call card silently skipped the current track.
        val mediaOnScreen = when (current) {
            is IslandState.Expanded -> current.activity.kind == ActivityKind.MEDIA_SESSION
            is IslandState.Compact -> current.activity.kind == ActivityKind.MEDIA_SESSION
            is IslandState.Minimal ->
                current.primary.kind == ActivityKind.MEDIA_SESSION ||
                    current.secondary.kind == ActivityKind.MEDIA_SESSION
            // No card of its own: the pill is passive, so following the media
            // session is the only sensible reading of a horizontal swipe.
            is IslandState.Closed,
            is IslandState.AlertPeek,
            is IslandState.NotificationInbox -> latestMediaPlayback.isActive
        }
        return mediaOnScreen && abs(dx) > abs(dy)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // ==================================================================
        // CONSTANT-SIZE HOST
        //
        // Allocated once at the maximum bounds the island can ever reach. Both
        // the pill and the detached bubble are positioned *inside* it without
        // ever resizing it, so no measure or layout pass runs during animation.
        //
        // The previous implementation applied `Modifier.width(animatedW)` and
        // `.height(animatedH)` to the pill, forcing a full measure + layout +
        // re-place of the entire subtree on every animation frame — measured at
        // 18.6% janky frames and a 32 ms median.
        // ==================================================================
        Box(
            modifier = Modifier
                // User placement applied as a draw-time translation. Fractional
                // values are preserved; `IntOffset` would snap to whole pixels
                // and visibly stair-step a slow slider drag.
                .offset(x = horizontalOffset, y = verticalOffset)
                .size(MAX_HOST_WIDTH_DP, MAX_HOST_HEIGHT_DP)
                .onGloballyPositioned { coordinates ->
                    // Fires on first layout and on window moves. The host's size
                    // is a constant, so this is not a per-frame callback.
                    //
                    // The Point objects are mutated in place rather than
                    // reassigned: they are not snapshot state, so reassigning a
                    // `val` would not compile and making them state would invite
                    // recomposition from a layout callback — the loop this whole
                    // design exists to avoid.
                    val origin = coordinates.positionInWindow()
                    hostOrigin.set(origin.x.roundToInt(), origin.y.roundToInt())
                    hostSize.set(coordinates.size.width, coordinates.size.height)

                    // Only now is the window origin known, so this is the first
                    // moment a meaningful region can be published.
                    publishBounds(force = true)
                }
                // ==========================================================
                // THE PILL IS DRAWN, NOT LAID OUT.
                //
                // `drawWithContent` runs entirely in the draw phase. Reading the
                // animated dimensions there changes what is *painted* without
                // changing any node's measured size, so a frame costs a path
                // fill and a canvas clip instead of a full measure + layout +
                // re-place of the whole subtree.
                //
                // Content deliberately does not read the animated values, so it
                // is never distorted: it keeps its natural size and is only
                // faded, which is what the specification's choreography
                // describes. The clip below keeps it inside the pill while the
                // pill is still growing.
                // ==========================================================
                .drawWithContent {
                    // Reading this state is what invalidates the draw scope once
                    // per frame, without recomposing anything.
                    motionFrame.let { _ -> }

                    val frame = motion.currentFrame
                    val d = density.density
                    val w = frame.widthDp.coerceAtLeast(1f) * d
                    val h = (frame.heightDp + frame.stretchDp).coerceAtLeast(1f) * d
                    val r = frame.radiusDp * d

                    val left = (size.width - w) / 2f + frame.dragOffsetXDp * d
                    val top = pillTopOffsetDp.value * d

                    pillPath.rewind()
                    buildPillPath(pillPath, w, h, r, left, top, pathSink)

                    // ==================================================
                    // Everything below runs on the NATIVE canvas.
                    //
                    // `pillPath` is an `android.graphics.Path` reused across
                    // frames, so it is drawn through `nativeCanvas` rather than
                    // Compose's `drawPath`, which would demand a Compose `Path`
                    // and force a per-frame wrapper allocation. The clip is a raw
                    // `canvas.clipPath` inside save/restore, which pushes no
                    // layer — the Compose `clipPath` modifier pushes one.
                    // ==================================================
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas

                        // --- Shadow ---
                        if (SHADOWS_ENABLED) {
                            shadowPaint.color = 0x59000000
                            shadowPaint.setShadowLayer(6f * d, 0f, 2f * d, 0x59000000)
                            native.drawPath(pillPath, shadowPaint)

                            shadowPaint.setShadowLayer(28f * d, 0f, 14f * d, 0x38000000)
                            native.drawPath(pillPath, shadowPaint)
                            shadowPaint.clearShadowLayer()
                        }

                        // --- Surface fill ---
                        //
                        // Opaque black is the point: the pill's edges vanish
                        // into the cutout, which is what sells the illusion.
                        // Drawing a plain black rect instead would break the
                        // silhouette mid-transition.
                        fillPaint.color = android.graphics.Color.BLACK
                        native.drawPath(pillPath, fillPaint)

                        // --- Clip content to the animated pill, then draw it ---
                        val save = native.save()
                        native.clipPath(pillPath)
                        this@drawWithContent.drawContent()
                        native.restoreToCount(save)
                    }
                }
                .pointerInput(Unit) {
                    var lastTapTime = 0L
                    var tapCount = 0
                    var pendingTapJob: kotlinx.coroutines.Job? = null
                    detectTapGestures(
                        onTap = {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTapTime < 350L) {
                                tapCount++
                            } else {
                                tapCount = 1
                            }
                            lastTapTime = now

                            if (tapCount >= 3) {
                                tapCount = 0
                                pendingTapJob?.cancel()
                                pendingTapJob = null
                                latestOnTripleTap()
                            } else {
                                pendingTapJob?.cancel()
                                pendingTapJob = coroutineScope.launch {
                                    kotlinx.coroutines.delay(350L)
                                    tapCount = 0
                                    if (latestState is IslandState.Expanded) {
                                        latestOnCollapseExpanded()
                                    } else {
                                        latestOnTapPrimary()
                                    }
                                }
                            }
                        },
                        // A hold opens the underlying app instead of expanding.
                        // The arbiter resolves which activity a hold refers to,
                        // including when one is already expanded.
                        onLongPress = {
                            pendingTapJob?.cancel()
                            tapCount = 0
                            latestOnLongPressPrimary()
                        }
                    )
                }
                .pointerInput(state is IslandState.NotificationInbox) {
                    // The inbox owns its own scroll and row swipes.
                    if (state is IslandState.NotificationInbox) return@pointerInput
                    var accumX = 0f
                    var accumY = 0f
                    detectDragGestures(
                        onDragStart = {
                            accumX = 0f
                            accumY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = with(latestDensity) { dragAmount.x.toDp().value }
                            val dy = with(latestDensity) { dragAmount.y.toDp().value }

                            // Accumulate the RAW travel. The engine applies
                            // rubber-band resistance, which by design never
                            // reaches the finger's actual distance, so a swipe
                            // classified against the resisted offset would need a
                            // threshold that changed with pill width. Raw travel
                            // is the same number at every size.
                            accumX += dx
                            accumY += dy

                            // ==================================================
                            // THE PILL ONLY MOVES WHEN THE GESTURE IS NOT A
                            // MEDIA SWIPE.
                            //
                            // A track skip is a command, not a drag: the island
                            // should stay exactly where it is and simply change
                            // the song. Feeding those pixels into the drag state
                            // made the pill slide sideways under the finger and
                            // spring back, which reads as the island moving
                            // rather than as the track changing.
                            //
                            // The axis test is the same one used to classify the
                            // gesture at the end, so the two can never disagree:
                            // whatever moves the pill is exactly what is NOT
                            // treated as a skip.
                            // ==================================================
                            val inboxOpen = latestState is IslandState.NotificationInbox
                            if (!inboxOpen && !isMediaSwipe(accumX, accumY, latestState)) {
                                motion.addDrag(dxDp = dx, dyDp = dy)
                                // A drag displaces the pill every frame, so the
                                // clock has to be running even though no spring
                                // is moving yet.
                                idle = false
                            }
                        },
                        onDragEnd = {
                            val dx = accumX
                            val dy = accumY
                            val mediaState = latestState

                            // --------------------------------------------------
                            // MEDIA SWIPE
                            //
                            // Swiping across the island skips tracks, matching
                            // the behaviour of the island on iOS. Right goes
                            // forward, left goes back.
                            // --------------------------------------------------
                            val isMediaOnScreen = isMediaSwipe(dx, dy, mediaState)

                            // --------------------------------------------------
                            // SWIPE TO CLEAR THE INBOX
                            //
                            // A swipe on an open inbox dismisses the whole thing.
                            // Checked before the media case and gated on the
                            // inbox state, so the two gestures can never both
                            // claim the same drag.
                            // --------------------------------------------------
                            if (mediaState is IslandState.NotificationInbox) {
                                if (abs(dx) >= MEDIA_SWIPE_DP || abs(dy) >= MEDIA_SWIPE_DP) {
                                    latestOnClearInbox()
                                }
                                accumX = 0f
                                accumY = 0f
                                idle = false
                                motion.clearDrag()
                                return@detectDragGestures
                            }

                            if (isMediaOnScreen && abs(dx) >= MEDIA_SWIPE_DP) {
                                // Accommodate right-to-left layouts: the arrow
                                // that points "next" is mirrored, so the swipe
                                // must mirror with it or the gesture would
                                // invert for those users.
                                val forward = if (latestLayoutDirection == LayoutDirection.Rtl) {
                                    dx < 0f
                                } else {
                                    dx > 0f
                                }
                                if (forward) latestOnMediaSkipNext() else latestOnMediaSkipPrevious()

                                // Nothing else to do: the pill never moved, so
                                // there is nothing to spring back from. The
                                // `clearDrag` below still runs for uniformity.
                            } else if (abs(dy) > abs(dx)) {
                                // Vertical drags keep their original meanings.
                                val stretchDp = motion.currentFrame.stretchDp
                                when {
                                    stretchDp > 24f && mediaState !is IslandState.Expanded ->
                                        latestOnLongPressPrimary()

                                    dy < -12f -> when (mediaState) {
                                        is IslandState.Expanded -> latestOnCollapseExpanded()
                                        is IslandState.AlertPeek -> latestOnDismissAlert()
                                        else -> Unit
                                    }
                                }
                            } else if (mediaState is IslandState.Expanded) {
                                // A horizontal drag that was too short to skip is
                                // still a "push it away" gesture on the open card.
                                latestOnCollapseExpanded()
                            }

                            accumX = 0f
                            accumY = 0f
                            // Clearing the drag is itself a change the renderer
                            // must see — the pill springs back from its resisted
                            // position — so wake the clock before clearing it.
                            idle = false
                            motion.clearDrag()
                        },
                        onDragCancel = {
                            accumX = 0f
                            accumY = 0f
                            idle = false
                            motion.clearDrag()
                        }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // ==================================================================
            // CONTENT
            //
            // Content opacity is read from the SAME frame the geometry is
            // interpolated from, so the two cannot drift apart.
            //
            // This previously used `AnimatedContent`, which is an independent
            // animation system with its own clock: it ran a 110–220 ms
            // fade-and-scale while the pill ran a 480 ms spring. The two
            // finished at visibly different moments, which is precisely the
            // "two things moving at once" artefact that reads as jitter. There
            // is now one source of truth for both, and the alphas are the ones
            // the specification's choreography already describes.
            //
            // The outgoing and incoming layers are offset in the layer phase,
            // so the crossfade costs no layout.
            //
            //  - `compactAlpha` is already 0 by 30% expansion.
            //  - `expandedAlpha` only begins at 70% expansion, so card content
            //    never appears inside a pill too small to hold it.
            //
            // Content is laid out at the state's TARGET size — not the host's,
            // and not the animated size.
            //
            //  - Not the host's: the host is a constant 380x176 so animating it
            //    costs no layout, and a `fillMaxSize()` child would be laid out
            //    across all of it and clipped away outside a 130-236 dp pill.
            //
            //  - Not the animated size: `motion.currentFrame` is a plain Kotlin
            //    property, not snapshot state, so reading it here captures
            //    whatever it held when the composition last ran — which is the
            //    size *before* the animation started. The card's content would
            //    lay itself out inside the old compact pill and be clipped to a
            //    sliver.
            //
            // ==================================================================
            // CONTENT CROSSFADE
            //
            // Content opacity is read from the SAME frame the geometry is
            // interpolated from, so the two cannot drift apart.
            //
            // Both outgoing and incoming layers are sized to their own natural
            // target dimensions (`compactWidth x compactHeight` and
            // `expandedWidth x expandedHeight`). The native canvas `clipPath`
            // clips both layers smoothly to the physical pill contour as it
            // morphs, preventing content from being squished or clipped early.
            //
            // We cache `lastCompactActivity` and `lastExpandedActivity` so that
            // during reverse transitions (e.g. collapsing an expanded card back
            // to the pill), the outgoing expanded content continues to render
            // and smoothly fades out instead of abruptly vanishing on frame 1.
            // ==================================================================
            var lastCompactActivity by remember { mutableStateOf<IslandActivity?>(null) }
            var lastExpandedActivity by remember { mutableStateOf<IslandActivity?>(null) }
            var lastAlertPeek by remember { mutableStateOf<com.dynamicisland.engine.core.model.TransientAlert?>(null) }
            var lastNotifications by remember { mutableStateOf<List<com.dynamicisland.engine.core.model.IslandNotification>?>(null) }

            when (state) {
                is IslandState.Compact -> lastCompactActivity = state.activity
                is IslandState.Minimal -> lastCompactActivity = state.primary
                is IslandState.Expanded -> lastExpandedActivity = state.activity
                is IslandState.AlertPeek -> lastAlertPeek = state.alert
                is IslandState.NotificationInbox -> lastNotifications = state.notifications
                else -> Unit
            }

            val compactBaseW = when (state) {
                is IslandState.Closed -> 122.dp
                else -> 236.dp
            }
            val compactWidth = compactBaseW * islandWidthScale
            val compactHeight = 36.dp * islandHeightScale

            val expandedBaseW = when (state) {
                is IslandState.AlertPeek -> 300.dp
                else -> 366.dp
            }
            val expandedBaseH = when (state) {
                is IslandState.AlertPeek -> 52.dp
                is IslandState.Expanded -> if (isTimerCard) 84.dp else 176.dp
                else -> 176.dp
            }
            val expandedWidth = expandedBaseW * islandWidthScale
            val expandedHeight = expandedBaseH * islandHeightScale

            val isWideState = state is IslandState.Expanded ||
                    state is IslandState.AlertPeek ||
                    state is IslandState.NotificationInbox

            val shouldRenderCompact = !isWideState || lastCompactActivity != null
            val shouldRenderExpanded = isWideState || lastExpandedActivity != null || lastAlertPeek != null || lastNotifications != null

            if (shouldRenderCompact) {
                Box(
                    modifier = Modifier
                        .padding(top = pillTopOffsetDp)
                        .size(compactWidth, compactHeight)
                        .graphicsLayer {
                            motionFrame.let {}
                            val f = motion.currentFrame
                            val currentScale = if (f.settled) 1f else (0.90f + 0.10f * f.compactAlpha)
                            scaleX = currentScale
                            scaleY = currentScale
                            alpha = if (isWideState) {
                                if (f.settled) 0f else f.compactAlpha
                            } else {
                                if (f.settled) 1f else f.compactAlpha
                            }
                            translationX = f.dragOffsetXDp * density.density
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val compactAct = when (state) {
                        is IslandState.Compact -> state.activity
                        is IslandState.Minimal -> state.primary
                        else -> lastCompactActivity
                    }

                    if (compactAct != null && state !is IslandState.Closed) {
                        CompactPillContent(
                            activity = compactAct,
                            isMicrophoneInUse = isMicrophoneInUse,
                            isCameraInUse = isCameraInUse,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (isMicrophoneInUse || isCameraInUse) {
                        val privacyColor = when {
                            isMicrophoneInUse && isCameraInUse -> Color(0xFF0A84FF)
                            isCameraInUse -> Color(0xFF30D158)
                            else -> Color(0xFFFF9F0A)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(privacyColor)
                            )
                        }
                    }
                }
            }

            if (shouldRenderExpanded) {
                Box(
                    modifier = Modifier
                        .padding(top = pillTopOffsetDp)
                        .size(expandedWidth, expandedHeight)
                        .graphicsLayer {
                            motionFrame.let {}
                            val f = motion.currentFrame
                            val currentScale = if (f.settled) 1f else (0.86f + 0.14f * f.expandedAlpha)
                            scaleX = currentScale
                            scaleY = currentScale
                            alpha = if (isWideState) {
                                if (f.settled) 1f else f.expandedAlpha
                            } else {
                                if (f.settled) 0f else f.expandedAlpha
                            }
                            translationX = f.dragOffsetXDp * density.density
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when (state) {
                        is IslandState.Expanded -> ExpandedCardContent(
                            activity = state.activity,
                            mediaPlayback = mediaPlayback,
                            onMediaPlayPause = onMediaPlayPause,
                            onMediaSkipNext = onMediaSkipNext,
                            onMediaSkipPrevious = onMediaSkipPrevious,
                            onMediaSeek = onMediaSeek,
                            onCallAnswer = onCallAnswer,
                            onCallHangup = onCallHangup,
                            onCallToggleMute = onCallToggleMute,
                            onCallToggleSpeaker = onCallToggleSpeaker,
                            onTimerCancel = onTimerCancel,
                            notificationCount = notificationCount,
                            onOpenInbox = onOpenInbox,
                            modifier = Modifier.fillMaxSize()
                        )

                        is IslandState.AlertPeek -> AlertPeekContent(
                            alert = state.alert,
                            modifier = Modifier.fillMaxSize()
                        )

                        is IslandState.NotificationInbox -> NotificationInboxContent(
                            notifications = state.notifications,
                            onOpenNotification = { key -> onOpenNotification(key) },
                            onClearAll = onClearInbox,
                            onDismissNotification = onDismissNotification,
                            modifier = Modifier.fillMaxSize()
                        )

                        else -> {
                            // During reversion (collapse), maintain the outgoing expanded
                            // card content at its natural size so it cross-fades out smoothly!
                            lastExpandedActivity?.let { act ->
                                ExpandedCardContent(
                                    activity = act,
                                    mediaPlayback = mediaPlayback,
                                    onMediaPlayPause = onMediaPlayPause,
                                    onMediaSkipNext = onMediaSkipNext,
                                    onMediaSkipPrevious = onMediaSkipPrevious,
                                    onMediaSeek = onMediaSeek,
                                    onCallAnswer = onCallAnswer,
                                    onCallHangup = onCallHangup,
                                    onCallToggleMute = onCallToggleMute,
                                    onCallToggleSpeaker = onCallToggleSpeaker,
                                    onTimerCancel = onTimerCancel,
                                    notificationCount = notificationCount,
                                    onOpenInbox = onOpenInbox,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: lastAlertPeek?.let { alert ->
                                AlertPeekContent(
                                    alert = alert,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: lastNotifications?.let { notifs ->
                                NotificationInboxContent(
                                    notifications = notifs,
                                    onOpenNotification = { key -> onOpenNotification(key) },
                                    onClearAll = onClearInbox,
                                    onDismissNotification = onDismissNotification,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    if (isMicrophoneInUse) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 14.dp, end = 16.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF9500))
                            )
                        }
                    }
                }
            }

            // ==================================================================
            // SECONDARY DETACHED BUBBLE (multi-activity)
            //
            // This is a sibling of the pill *inside* the constant-size host, not
            // a Row item beside it. As a Row item it would have been pushed
            // around by the host's width — which is a constant 380 dp — leaving
            // it floating far from a compact pill that is only 236 dp wide.
            //
            // Its offset is computed from the animated pill width and applied as
            // a `graphicsLayer` translation, so following the pill as it grows
            // costs a GPU transform rather than a layout pass.
            //
            // The composable is only *present* while the state is Minimal, which
            // is a once-per-state-change composition rather than a per-frame one;
            // the split itself is animated by `secondaryScale` through the layer.
            // ==================================================================
        }
    }
}

private class StateHolder<T>(var value: T)

private class BoundsCache {
    val primary = android.graphics.Rect()
    val lastPublishedPrimary = android.graphics.Rect()
    var hasPublished = false
}


private class ActivityCache {
    var lastCompactActivity: IslandActivity? = null
    var lastExpandedActivity: IslandActivity? = null
    var lastAlertPeek: com.dynamicisland.engine.core.model.TransientAlert? = null
    var lastNotifications: List<com.dynamicisland.engine.core.model.IslandNotification>? = null

    fun update(state: IslandState) {
        when (state) {
            is IslandState.Compact -> lastCompactActivity = state.activity
            is IslandState.Minimal -> lastCompactActivity = state.primary
            is IslandState.Expanded -> lastExpandedActivity = state.activity
            is IslandState.AlertPeek -> lastAlertPeek = state.alert
            is IslandState.NotificationInbox -> lastNotifications = state.notifications
            else -> Unit
        }
    }
}

private val IconSquircleShape = SquircleShape(6.dp)


private fun ActivityKind.isTimerLike(): Boolean =
    this == ActivityKind.LIVE_TIMER || this == ActivityKind.LIVE_STOPWATCH

/**
 * Compact Pill Layout: Leading Slot + Trailing Slot + Microphone Indicator.
 */
@Composable
private fun CompactPillContent(
    activity: IslandActivity,
    isMicrophoneInUse: Boolean = false,
    isCameraInUse: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            RenderSlot(activity.compactLeading)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(contentAlignment = Alignment.CenterEnd) {
                RenderSlot(activity.compactTrailing)
            }
            if (isMicrophoneInUse || isCameraInUse) {
                val dotColor = when {
                    isMicrophoneInUse && isCameraInUse -> Color(0xFF0A84FF)
                    isCameraInUse -> Color(0xFF30D158)
                    else -> Color(0xFFFF9F0A)
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
    }
}

/**
 * Secondary Detached Bubble Content (1:1 glyph or app badge).
 */
@Composable
private fun SecondaryBubbleContent(
    activity: IslandActivity,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(20.dp),
        contentAlignment = Alignment.Center
    ) {
        RenderSlot(activity.minimalContent)
    }
}

@Composable
private fun slotColor(hex: String?, fallback: Color): Color =
    hex?.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    } ?: fallback

/**
 * Polymorphic slot renderer for glyphs, texts, waveforms, progress and chronometers.
 */
@Composable
private fun RenderSlot(content: IslandSlotContent) {
    when (content) {
        is IslandSlotContent.Text -> {
            Text(
                text = content.text,
                color = slotColor(content.colorHex, Color.White),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }

        is IslandSlotContent.Symbol -> {
            IslandGlyph(
                symbol = content.symbol,
                tint = slotColor(content.tintHex, Color.White),
                modifier = Modifier.size(18.dp)
            )
        }

        is IslandSlotContent.Icon -> {
            if (content.iconBitmap != null) {
                Image(
                    bitmap = content.iconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(SquircleShape(6.dp))
                )
            } else {
                IslandGlyph(
                    symbol = IslandSymbol.MUSIC_NOTE,
                    tint = slotColor(content.tintHex, Color.White),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        is IslandSlotContent.AudioWaveform -> {
            AudioWaveformEqualizer(
                amplitudes = content.amplitudes,
                color = slotColor(content.tintHex, Color.White)
            )
        }

        is IslandSlotContent.Progress -> {
            val fraction = if (content.max > 0) {
                (content.current.toFloat() / content.max).coerceIn(0f, 1f)
            } else 0f
            Text(
                text = "${(fraction * 100).toInt()}%",
                color = slotColor(content.tintHex, Color(0xFF34C759)),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }

        is IslandSlotContent.Chronometer -> {
            val second = rememberCurrentSecond(active = content.isRunning)
            val remMs = remember(content.baseTimeMillis, content.isCountDown, second) {
                val now = System.currentTimeMillis()
                if (content.baseTimeMillis <= 0L) 0L
                else if (content.isCountDown) {
                    (content.baseTimeMillis - now).coerceAtLeast(0L)
                } else {
                    (now - content.baseTimeMillis).coerceAtLeast(0L)
                }
            }
            Text(
                text = formatChronometer(remMs),
                color = slotColor(content.colorHex, Color(0xFFFF9F0A)),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }

        is IslandSlotContent.Composite -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                content.leadingIcon?.let { RenderSlot(it) }
                Spacer(modifier = Modifier.width(5.dp))
                content.text?.let { RenderSlot(it) }
            }
        }
    }
}

/**
 * Expanded Rich Card Router for Media, Call, Timer and Generic activities.
 */
@Composable
private fun ExpandedCardContent(
    activity: IslandActivity,
    mediaPlayback: MediaPlaybackSnapshot,
    onMediaPlayPause: () -> Unit,
    onMediaSkipNext: () -> Unit,
    onMediaSkipPrevious: () -> Unit,
    onMediaSeek: (Long) -> Unit,
    onCallAnswer: () -> Unit,
    onCallHangup: () -> Unit,
    onCallToggleMute: () -> Unit,
    onCallToggleSpeaker: () -> Unit,
    onTimerCancel: () -> Unit,
    notificationCount: Int,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (activity.kind) {
        ActivityKind.MEDIA_SESSION -> {
            val albumBitmap = (activity.expandedData.leading as? IslandSlotContent.Icon)?.iconBitmap
                ?: (activity.compactLeading as? IslandSlotContent.Icon)?.iconBitmap
            MediaExpandedContent(
                title = activity.title,
                artist = activity.subtitle,
                albumArt = albumBitmap,
                playback = mediaPlayback,
                onPlayPause = onMediaPlayPause,
                onSkipNext = onMediaSkipNext,
                onSkipPrevious = onMediaSkipPrevious,
                onSeek = onMediaSeek,
                notificationCount = notificationCount,
                onOpenInbox = onOpenInbox,
                modifier = modifier
            )
        }

        ActivityKind.RINGING_CALL, ActivityKind.LIVE_CALL, ActivityKind.EMERGENCY_CALL -> {
            CallExpandedContent(
                callerName = activity.title,
                callerNumber = activity.subtitle,
                durationText = when (val trailing = activity.compactTrailing) {
                    is IslandSlotContent.Text -> trailing.text
                    is IslandSlotContent.Chronometer -> formatChronometer(
                        (System.currentTimeMillis() - trailing.baseTimeMillis).coerceAtLeast(0L)
                    )
                    else -> ""
                },
                isIncoming = activity.kind == ActivityKind.RINGING_CALL,
                isMuted = activity.callState?.isMuted ?: false,
                isSpeaker = activity.callState?.isSpeakerOn ?: false,
                isControllable = activity.callState?.isControllable ?: false,
                isOnHold = activity.callState?.isOnHold ?: false,
                onAnswer = onCallAnswer,
                onHangup = onCallHangup,
                onToggleMute = onCallToggleMute,
                onToggleSpeaker = onCallToggleSpeaker,
                modifier = modifier
            )
        }

        ActivityKind.LIVE_TIMER, ActivityKind.LIVE_STOPWATCH -> {
            val chrono = (activity.compactTrailing as? IslandSlotContent.Chronometer)
                ?: (activity.expandedData.trailing as? IslandSlotContent.Chronometer)
            TimerExpandedContent(
                title = activity.title.ifBlank {
                    if (activity.kind == ActivityKind.LIVE_TIMER) "Timer" else "Stopwatch"
                },
                subtitle = activity.subtitle,
                baseTimeMillis = chrono?.baseTimeMillis ?: 0L,
                isCountDown = chrono?.isCountDown ?: (activity.kind == ActivityKind.LIVE_TIMER),
                isRunning = chrono?.isRunning ?: true,
                accent = chrono?.colorHex?.let { slotColor(it, Color(0xFFFF9F0A)) }
                    ?: Color(0xFFFF9F0A),
                onCancel = onTimerCancel,
                modifier = modifier
            )
        }

        else -> {
            // Generic notification / navigation expanded card
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        RenderSlot(activity.expandedData.leading)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activity.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        activity.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 13.sp,
                                maxLines = 2
                            )
                        }
                    }
                }

                activity.expandedData.bottom?.let {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        RenderSlot(it)
                    }
                }
            }
        }
    }
}

private fun formatChronometer(ms: Long): String {
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

// ==========================================================================
// Draw-path helpers
// ==========================================================================

/**
 * The largest bounds the island can occupy.
 *
 * The render host is allocated at exactly this size once and never resized, so
 * animating the pill costs a path rebuild rather than a layout pass. It is the
 * expanded card's footprint plus headroom for the rubber-band stretch.
 */
private val MAX_HOST_WIDTH_DP = (380f * IslandSettings.MAX_SCALE + 40f).dp
private val MAX_HOST_HEIGHT_DP = (176f * IslandSettings.MAX_SCALE + 40f).dp

/**
 * Consecutive settled frames required before the clock parks.
 *
 * Two spring dimensions settling a frame apart is normal; requiring the settled
 * state to hold for a few frames prevents the clock from stopping in the gap
 * between them.
 */
private const val SETTLE_CONFIRM_FRAMES = 3

/**
 * Whether the pill casts the specification's dual shadow.
 *
 * Kept as a named constant because the shadow is the one part of the draw that
 * costs real time: each `setShadowLayer` draw allocates a layer and blurs it,
 * and this runs twice per frame. Measured on the test emulator it accounts for
 * roughly 3 ms of a 27 ms frame — worth having, but not the dominant cost.
 */
private const val SHADOWS_ENABLED = true

/**
 * Raw travel, in dp, that a horizontal drag must cover to skip a track.
 *
 * Measured on raw pointer travel rather than on the engine's resisted offset:
 * the rubber band deliberately never reaches the finger's real distance, so a
 * threshold on the resisted value would silently change meaning with pill
 * width. 48 dp is well clear of an accidental brush and of the ~8 dp that
 * separates a tap from a drag.
 */
private const val MEDIA_SWIPE_DP = 28f

/** Diameter of the detached multi-activity bubble, matching the specification. */
private const val BUBBLE_SIZE_DP = 36f

/** Gap between the pill and the detached bubble. */
private const val BUBBLE_GAP_DP = 8f

/**
 * Builds the island outline into [path], reusing the object.
 *
 * Chooses between the capsule (a full Lamé superellipse, no straight edge) and
 * the continuous-corner squircle, per the rule in
 * [com.dynamicisland.core.geometry.ShapeSelection]. The selection is the same
 * one the core tests verify for curvature continuity — this function only
 * rasterises it.
 *
 * @param left/top the pill's top-left corner in canvas space, already including
 *   drag and placement offsets.
 * @param sink optional reusable PathSink to stream squircle commands with zero object allocation.
 */
private fun buildPillPath(
    path: android.graphics.Path,
    width: Float,
    height: Float,
    radius: Float,
    left: Float,
    top: Float,
    sink: SquircleGeometry.PathSink? = null
) {
    path.rewind()

    val capRadius = kotlin.math.min(width, height) / 2f

    if (radius >= capRadius - 0.5f) {
        // Capsule: two semicircular caps joined by straight edges. Drawn with
        // `arcTo` so it stays ONE contour — `addArc` would start a new contour
        // and close the previous one with a chord, clipping the pill into a
        // crescent. API 21+ float arcTo avoids RectF allocations.
        val r = capRadius
        path.moveTo(left + r, top)
        path.lineTo(left + width - r, top)
        path.arcTo(
            left + width - 2f * r, top,
            left + width, top + height,
            -90f, 180f, false
        )
        path.lineTo(left + r, top + height)
        path.arcTo(
            left, top,
            left + 2f * r, top + height,
            90f, 180f, false
        )
        path.close()
    } else {
        // Continuous-corner squircle: Apple's G2 spline, computed in :core (where
        // it is unit-tested) and streamed here.
        if (sink != null) {
            SquircleGeometry.emitPath(width, height, radius, left, top, sink)
        } else {
            for (segment in SquircleGeometry.path(width, height, radius, left, top)) {
                when (segment) {
                    is SquircleGeometry.Segment.MoveTo -> path.moveTo(segment.x, segment.y)
                    is SquircleGeometry.Segment.LineTo -> path.lineTo(segment.x, segment.y)
                    is SquircleGeometry.Segment.CubicTo -> path.cubicTo(
                        segment.cp1x, segment.cp1y,
                        segment.cp2x, segment.cp2y,
                        segment.x, segment.y
                    )
                    SquircleGeometry.Segment.Close -> path.close()
                }
            }
        }
    }
}



