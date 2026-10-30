package com.arena.dynamicisland.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.content.CollapsedContent
import com.arena.dynamicisland.island.content.DropletMinimalContent
import com.arena.dynamicisland.island.content.IslandActivityContent

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DynamicIsland(
    presentation: IslandPresentation,
    privacySensors: Set<PrivacySensor> = emptySet(),
    privacyPlacement: PrivacyPlacement = PrivacyPlacement.Hidden,
    modifier: Modifier = Modifier,
    activityContent: @Composable (activity: IslandActivity, expanded: Boolean) -> Unit = { activity, expanded ->
        IslandActivityContent(activity = activity, expanded = expanded)
    },
    onSingleTap: (String?) -> Unit = {},
    onLongPressExpand: (String?) -> Unit = {},
    onSwipeStowOrRestore: () -> Unit = {},
    onSwipeDownCollapse: () -> Unit = {},
) {
    val target = presentation.size

    // Held as State<Dp>, never read in composition (chrisbanes/skills deferred-reads).
    val animatedWidth = animateDpAsState(
        targetValue = target.width,
        animationSpec = IslandMotion.SizeSpring,
        label = "islandWidth",
    )
    val animatedHeight = animateDpAsState(
        targetValue = target.height,
        animationSpec = IslandMotion.SizeSpring,
        label = "islandHeight",
    )
    val animatedRadius = animateDpAsState(
        targetValue = presentation.cornerRadius,
        animationSpec = IslandMotion.RadiusSpring,
        label = "islandRadius",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) IslandMotion.PRESS_SCALE else 1f,
        animationSpec = IslandMotion.PressSpring,
        label = "islandPressScale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = modifier
                .semantics { contentDescription = "Dynamic Island: ${presentation.contentKey}" }
                .islandSize(width = animatedWidth, height = animatedHeight)
                .scale(pressScale)
                .drawSquircleSurface(cornerRadiusState = animatedRadius)
                .pointerInput(presentation.contentKey) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            interactionSource.emit(press)
                            val released = tryAwaitRelease()
                            interactionSource.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onSingleTap(null) },
                        onLongPress = { onLongPressExpand(null) },
                    )
                }
                .pointerInput(Unit) {
                    var horizAccum = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(horizAccum) > SWIPE_STOW_THRESHOLD) {
                                onSwipeStowOrRestore()
                            }
                            horizAccum = 0f
                        },
                        onDragCancel = { horizAccum = 0f },
                    ) { _, dragAmount -> horizAccum += dragAmount }
                }
                .pointerInput(Unit) {
                    var vertAccum = 0f
                    detectVerticalDragGestures(
                        onDragEnd = {
                            when {
                                vertAccum > DRAG_THRESHOLD -> onSwipeDownCollapse()
                                vertAccum < -DRAG_THRESHOLD -> onLongPressExpand(null)
                            }
                            vertAccum = 0f
                        },
                        onDragCancel = { vertAccum = 0f },
                    ) { _, dragAmount -> vertAccum += dragAmount }
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = presentation,
                contentKey = { it.contentKey },
                transitionSpec = {
                    fadeIn(animationSpec = IslandMotion.ContentFadeIn) togetherWith
                        fadeOut(animationSpec = IslandMotion.ContentFadeOut)
                },
                label = "islandContent",
            ) { state ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (state) {
                        IslandPresentation.Collapsed, is IslandPresentation.Stowed ->
                            CollapsedContent(
                                privacySensors = if (privacyPlacement == PrivacyPlacement.InsideSensorBridge)
                                    privacySensors else emptySet(),
                            )
                        is IslandPresentation.Compact -> activityContent(state.activity, false)
                        is IslandPresentation.Expanded -> activityContent(state.activity, true)
                        is IslandPresentation.SplitDroplet -> activityContent(state.leading, false)
                    }
                }
            }
        }

        // Detached Minimal Circular Droplet (37x37dp) when two peer activities are live
        if (presentation is IslandPresentation.SplitDroplet) {
            Spacer(modifier = Modifier.width(IslandSize.DetachedGap))
            Box(
                modifier = Modifier
                    .size(IslandSize.Height)
                    .drawSquircleSurface(cornerRadiusState = animatedRadius)
                    .pointerInput(presentation.droplet.id) {
                        detectTapGestures(
                            onTap = { onSingleTap(presentation.droplet.id) },
                            onLongPress = { onLongPressExpand(presentation.droplet.id) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                DropletMinimalContent(activity = presentation.droplet)
            }
        }

        // Detached Lateral Privacy Indicator Dots (+10dp right of occupied capsule)
        if (privacyPlacement == PrivacyPlacement.DetachedRight && privacySensors.isNotEmpty()) {
            Spacer(modifier = Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                privacySensors.forEach { sensor ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(sensor.dotColor),
                    )
                }
            }
        }
    }
}

/**
 * Draw-phase G² continuous squircle fill + 11% white adaptive .keylineTint border.
 * Reading [cornerRadiusState] inside onDrawBehind avoids recomposing DynamicIsland on every frame.
 */
private fun Modifier.drawSquircleSurface(cornerRadiusState: State<Dp>): Modifier = drawWithCache {
    onDrawWithContent {
        val radiusPx = cornerRadiusState.value.toPx()
        val squirclePath = ContinuousSquircleShape.buildSquirclePath(
            w = size.width,
            h = size.height,
            r = radiusPx,
        )
        drawPath(path = squirclePath, color = Color.Black)
        clipPath(squirclePath) {
            this@onDrawWithContent.drawContent()
        }
        drawPath(
            path = squirclePath,
            color = IslandColors.KeylineTint,
            style = Stroke(width = 0.7.dp.toPx()),
        )
    }
}

private fun Modifier.islandSize(width: State<Dp>, height: State<Dp>): Modifier =
    layout { measurable, constraints ->
        val widthPx = width.value.roundToPx().coerceIn(0, constraints.maxWidth)
        val heightPx = height.value.roundToPx().coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
        layout(widthPx, heightPx) { placeable.place(0, 0) }
    }

private const val DRAG_THRESHOLD = 22f
private const val SWIPE_STOW_THRESHOLD = 28f
