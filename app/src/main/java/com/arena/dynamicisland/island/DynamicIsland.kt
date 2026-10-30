package com.arena.dynamicisland.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.arena.dynamicisland.island.content.CollapsedContent
import com.arena.dynamicisland.island.content.IslandActivityContent
import com.arena.dynamicisland.island.content.SplitContent

/**
 * The island itself: a black capsule that springs between four shapes and crossfades its contents.
 *
 * This composable is purely presentational. It renders whatever [IslandPresentation] it is handed
 * and reports gestures upward; all decision making lives in [IslandState].
 *
 * @param activityContent lets a caller replace the per-activity UI without touching the container.
 * @param onTap fired on tap — conventionally "toggle open/closed".
 * @param onSwipeDown fired when the user drags down, which collapses the card.
 * @param onSwipeUp fired when the user drags up, which opens the card.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DynamicIsland(
    presentation: IslandPresentation,
    modifier: Modifier = Modifier,
    activityContent: @Composable (activity: IslandActivity, expanded: Boolean) -> Unit = { activity, expanded ->
        IslandActivityContent(activity = activity, expanded = expanded)
    },
    onTap: () -> Unit = {},
    onSwipeDown: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
) {
    val target = presentation.size

    // Held as State, not read as a value: the size is consumed in the layout phase below, so a
    // frame of animation invalidates layout only — it does not recompose this container.
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

    // The radius is read in composition because it feeds clip/border shapes, which are parameters.
    val radius by animateDpAsState(
        targetValue = presentation.cornerRadius,
        animationSpec = IslandMotion.RadiusSpring,
        label = "islandRadius",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) IslandMotion.PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "islandPressScale",
    )

    val shape = RoundedCornerShape(radius)

    Box(
        modifier = modifier
            .islandSize(width = animatedWidth, height = animatedHeight)
            .scale(pressScale)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF111114), IslandColors.Surface),
                ),
            )
            .border(width = 0.7.dp, color = IslandColors.Hairline, shape = shape)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = { offset ->
                        // Hold the pressed state until the finger actually leaves, so the press-in
                        // scale animates both ways instead of sticking.
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)
                        val released = tryAwaitRelease()
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                        )
                    },
                    onTap = { onTap() },
                )
            }
            .pointerInput(Unit) {
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragEnd = {
                        when {
                            accumulated > DRAG_THRESHOLD -> onSwipeDown()
                            accumulated < -DRAG_THRESHOLD -> onSwipeUp()
                        }
                        accumulated = 0f
                    },
                    onDragCancel = { accumulated = 0f },
                ) { _, dragAmount ->
                    accumulated += dragAmount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = presentation,
            // Keyed on the *shape*, not the data: a countdown tick or playback progress must update
            // in place instead of crossfading the whole card every second.
            contentKey = { it.contentKey },
            transitionSpec = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = IslandMotion.CONTENT_FADE_IN_MILLIS,
                        delayMillis = IslandMotion.CONTENT_FADE_IN_DELAY_MILLIS,
                    ),
                ) togetherWith fadeOut(
                    animationSpec = tween(durationMillis = IslandMotion.CONTENT_FADE_OUT_MILLIS),
                )
            },
            label = "islandContent",
        ) { state ->
            // Render from the lambda's own target, never from the captured outer value: both
            // branches stay composed during the transition and must keep their own identity.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    IslandPresentation.Collapsed -> CollapsedContent()
                    is IslandPresentation.Compact -> activityContent(state.activity, false)
                    is IslandPresentation.Expanded -> activityContent(state.activity, true)
                    is IslandPresentation.Split -> SplitContent(
                        leading = state.leading,
                        trailing = state.trailing,
                        activityContent = activityContent,
                    )
                }
            }
        }
    }
}

/**
 * Sizes the island from animated [Dp] state without reading it in composition.
 *
 * Children are measured with fixed constraints so the capsule's contents see the real width while
 * the spring is still in flight, instead of a stale one from the previous frame.
 */
private fun Modifier.islandSize(width: State<Dp>, height: State<Dp>): Modifier = layout { measurable, constraints ->
    val widthPx = width.value.roundToPx().coerceIn(0, constraints.maxWidth)
    val heightPx = height.value.roundToPx().coerceIn(0, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
    layout(widthPx, heightPx) {
        placeable.place(0, 0)
    }
}

/** Drag distance past which a swipe counts as a collapse / expand gesture. */
private const val DRAG_THRESHOLD = 22f
