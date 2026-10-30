package com.arena.dynamicisland.island

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * Motion constants, in one place so the island's feel is tunable without hunting through layouts.
 *
 * The size spring is deliberately under-damped (ratio 0.74): the island overshoots by a hair and
 * settles back, which is what makes the morph read as a physical bubble rather than a resize.
 * Corner radius is damped harder so the shape does not visibly wobble while the pill stretches.
 */
object IslandMotion {

    val SizeSpring: SpringSpec<Dp> = spring(dampingRatio = 0.74f, stiffness = 420f)

    val RadiusSpring: SpringSpec<Dp> = spring(dampingRatio = 0.9f, stiffness = 520f)

    /** Content only fades in once the container is most of the way there. */
    const val CONTENT_FADE_IN_MILLIS: Int = 180
    const val CONTENT_FADE_IN_DELAY_MILLIS: Int = 70
    const val CONTENT_FADE_OUT_MILLIS: Int = 110

    /** Length of the content crossfade. Kept shorter than the size spring on purpose. */
    const val CONTENT_CROSSFADE_MILLIS: Int = 190

    val ContentFadeIn: TweenSpec<Float> =
        tween(durationMillis = CONTENT_FADE_IN_MILLIS, delayMillis = CONTENT_FADE_IN_DELAY_MILLIS)

    val ContentFadeOut: TweenSpec<Float> = tween(durationMillis = CONTENT_FADE_OUT_MILLIS)

    /** Small press-in scale for the whole island on touch down. */
    const val PRESS_SCALE: Float = 0.975f
}
