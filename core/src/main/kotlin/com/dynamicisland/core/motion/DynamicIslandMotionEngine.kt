package com.dynamicisland.core.motion

import com.dynamicisland.core.physics.SpringCatalog
import com.dynamicisland.core.physics.SpringSimulation
import com.dynamicisland.core.physics.SpringSpec
import kotlin.math.abs
import kotlin.math.sign

/**
 * Every animated dimension of the island, evaluated on **one clock**.
 *
 * ### Why this exists
 *
 * The previous implementation ran three independent `Animatable` coroutines —
 * one each for width, height and corner radius — and a fourth for the secondary
 * bubble. Each has its own frame callback, so their values could advance on
 * different frames. The visible result was a pill whose edges wobbled slightly
 * out of step during a transition, which reads as jitter.
 *
 * Here all dimensions are advanced by a single [advance] call from a single
 * frame callback, so they are guaranteed to be sampled at the same instant with
 * the same `dt`. Desynchronisation becomes structurally impossible rather than
 * something a developer has to remember to avoid.
 *
 * ### The constant-size contract
 *
 * This engine reports *virtual* dimensions — the size the pill should be drawn
 * at. It does **not** resize any layout. The renderer is expected to allocate a
 * container at the expanded card's maximum bounds once and then draw the pill
 * at these dimensions inside it, so changing a dimension never triggers a
 * measure or layout pass. See PLAN.md Finding 16.
 *
 * The engine is pure Kotlin with no Android dependency, so its trajectory is
 * unit-testable and identical on every device.
 */
class DynamicIslandMotionEngine {

    /** A single frame's worth of resolved geometry. */
    data class Frame(
        /** Pill width in dp at this instant. */
        val widthDp: Float,
        /** Pill height in dp at this instant. */
        val heightDp: Float,
        /** Corner radius in dp at this instant. */
        val radiusDp: Float,
        /** Secondary bubble scale, 0 when absent. */
        val secondaryScale: Float,
        /** Opacity of the outgoing (compact) content, 0..1. */
        val compactAlpha: Float,
        /** Opacity of the incoming (expanded) content, 0..1. */
        val expandedAlpha: Float,
        /** Downward stretch in dp from a rubber-band drag, already resisted. */
        val stretchDp: Float,
        /** Horizontal rubber-band drag offset in dp. */
        val dragOffsetXDp: Float,
        /** True once every spring has settled and no drag is in flight. */
        val settled: Boolean
    )

    // --- Geometry springs, all sharing one spec and one clock --------------
    //
    // Held in `var` rather than `val` because SpringSimulation derives its
    // frequencies from its spec at construction, so adopting a different spring
    // means a different instance. `adoptSpec` rebuilds them while carrying live
    // position and velocity across, which is what keeps an interrupted
    // transition continuous.
    private var width = SpringSimulation(SpringCatalog.CompactToExpanded, INITIAL_WIDTH_DP)
    private var height = SpringSimulation(SpringCatalog.CompactToExpanded, INITIAL_HEIGHT_DP)
    private var radius = SpringSimulation(SpringCatalog.CompactToExpanded, INITIAL_RADIUS_DP)
    private var bubble = SpringSimulation(SpringCatalog.BifurcationSplit, 0f)

    /**
     * Release springs for the rubber-band drag.
     *
     * A drag is not spring-driven while the finger is down — the pointer owns the
     * offset. But on release the pill must travel back to rest under the
     * specification's `RUBBER_BAND_SNAPBACK` spring rather than teleporting: the
     * previous implementation zeroed the raw drag on `ACTION_UP`, so the very
     * next frame reported a stretch of `0` and the pill snapped.
     *
     * These two springs only run while [releasing] is true.
     */
    private var releaseX = SpringSimulation(SpringCatalog.RubberBandSnapback, 0f)
    private var releaseY = SpringSimulation(SpringCatalog.RubberBandSnapback, 0f)
    private var releasing = false

    /** True while the island is snapping back from a released drag. */
    val isReleasing: Boolean get() = releasing

    /**
     * The spec currently driving the geometry springs.
     *
     * Changing it mid-flight preserves position *and* velocity, so switching
     * from an expansion spec to a collapse spec keeps the motion continuous
     * through an interruption.
     */
    private var currentSpec: SpringSpec = SpringCatalog.CompactToExpanded

    // --- Drag state (not spring-driven; fed straight from the pointer) -----
    private var dragRawY = 0f
    private var dragRawX = 0f

    /**
     * The area the current transition started from.
     *
     * Choreography has to be normalised against the transition that is actually
     * happening, not against a fixed pair of reference rectangles. A single
     * compact → expanded span cannot serve both wide presentations: the alert
     * peek is 300x52 against the expanded card's 370x160, so a span built for
     * the card leaves the alert permanently below its content threshold (it
     * would open and render invisible), while a span built for the alert makes
     * the *card's* content appear almost immediately, before the pill is big
     * enough to hold it.
     *
     * Re-anchoring on each [moveTo] resolves both: progress always runs 0 → 1
     * across the transition the user is actually watching, whatever its size.
     *
     * Captured at [moveTo] rather than at the previous target so an interrupted
     * transition re-anchors from where it actually is. The alternative — keeping
     * the original origin — would make a reversal start partway through the
     * alpha ramp and pop.
     */
    private var originArea = INITIAL_WIDTH_DP * INITIAL_HEIGHT_DP

    /** The area the current transition is heading for. */
    private var destinationArea = INITIAL_WIDTH_DP * INITIAL_HEIGHT_DP

    /** True when the island began the current transition as a wide card. */
    private var currentIsWide: Boolean = false

    /** True when the island is transitioning towards a wide card. */
    private var destinationIsWide: Boolean = false

    /**
     * Choreography progress for a state that was snapped rather than animated.
     *
     * A snapped state has no transition to measure progress across — origin and
     * destination are the same rectangle — so the span collapses to zero and
     * area normalisation cannot say whether the island is a resting pill or a
     * resting card. Without this, `snapTo` would report every state as fully
     * progressed, which leaves the compact pill showing no content at all.
     *
     * Set by [snapTo] and cleared by [moveTo]; while it is set it wins.
     */
    private var snappedProgress: Float? = null

    /** Most recent resolved frame, so a caller can read without advancing. */
    var currentFrame: Frame = restingFrame(INITIAL_WIDTH_DP, INITIAL_HEIGHT_DP)
        private set

    /**
     * Points every dimension at a new target using [spec], preserving velocity.
     *
     * Changing the spec mid-flight is the interruptible case: the springs carry
     * their current position and velocity into the new target, so a
     * half-completed expansion that is reversed collapses smoothly from
     * wherever it actually is rather than snapping.
     */
    fun moveTo(
        targetWidthDp: Float,
        targetHeightDp: Float,
        targetRadiusDp: Float,
        spec: SpringSpec,
        secondaryVisible: Boolean,
        targetIsWide: Boolean = isWideFootprint(targetWidthDp, targetHeightDp)
    ) {
        if (spec !== currentSpec) {
            currentSpec = spec
            // SpringSimulation caches omega0 and the damped frequency from its
            // spec at construction, so adopting a different spring means a new
            // instance. Rebuild each one carrying the live state across, and
            // only then retarget — the ordering matters, because retarget()
            // snapshots (value, velocity) as the new initial conditions.
            width = adopt(width, spec)
            height = adopt(height, spec)
            radius = adopt(radius, spec)
        }

        // Re-anchor the alpha ramp on this transition, starting from where the
        // pill actually is right now so an interruption stays continuous.
        originArea = width.value * height.value
        destinationArea = targetWidthDp * targetHeightDp
        currentIsWide = isWideFootprint(width.value, height.value)
        destinationIsWide = targetIsWide
        snappedProgress = null

        width.retarget(targetWidthDp)
        height.retarget(targetHeightDp)
        radius.retarget(targetRadiusDp)

        // Detached bubble physics: BifurcationSplit (T=0.52, ζ=0.72) when tearing
        // away from the pill, BifurcationMerge (T=0.44, ζ=0.82) when collapsing
        // back into it, per the specification's animation matrix.
        val bubbleSpec = if (secondaryVisible) SpringCatalog.BifurcationSplit else SpringCatalog.BifurcationMerge
        if (bubble.spec !== bubbleSpec) {
            bubble = adopt(bubble, bubbleSpec)
        }
        bubble.retarget(if (secondaryVisible) 1f else 0f)
        currentFrame = resolve(isSettled = false)
    }

    /**
     * Rebuilds [existing] under [spec], carrying its live position and velocity
     * across so the trajectory does not jump.
     *
     * The rebuilt spring starts at the old spring's *current* value and
     * velocity, and is then pointed at the old spring's target. Because
     * [SpringSimulation.retarget] re-seeds from the live state and resets
     * elapsed time, the result is continuous in both position and velocity —
     * verified to floating-point exactness by the motion-quality tests.
     */
    private fun adopt(existing: SpringSimulation, spec: SpringSpec): SpringSimulation =
        SpringSimulation(spec, initialValue = existing.value).apply {
            // Seed velocity directly, then retarget so the initial conditions
            // are exactly the old spring's live state.
            seedState(value = existing.value, velocity = existing.velocity)
            retarget(existing.target)
        }

    /** True while a drag is displacing the island. */
    val isDragging: Boolean get() = dragRawX != 0f || dragRawY != 0f

    /** Current accumulated raw horizontal drag in dp. */
    val rawDragX: Float get() = dragRawX

    /** Current accumulated raw vertical drag in dp. */
    val rawDragY: Float get() = dragRawY

    /** Snaps every dimension without animating. */
    fun snapTo(
        widthDp: Float,
        heightDp: Float,
        radiusDp: Float,
        secondaryVisible: Boolean,
        isWide: Boolean = isWideFootprint(widthDp, heightDp)
    ) {
        width.snapTo(widthDp)
        height.snapTo(heightDp)
        radius.snapTo(radiusDp)
        bubble.snapTo(if (secondaryVisible) 1f else 0f)
        dragRawX = 0f
        dragRawY = 0f
        releasing = false
        releaseX.snapTo(0f)
        releaseY.snapTo(0f)
        currentIsWide = isWide
        destinationIsWide = isWide
        // Land the transition anchor on the state we just snapped to, so the
        // next moveTo measures from here rather than from wherever the previous
        // transition happened to be heading.
        originArea = widthDp * heightDp
        destinationArea = widthDp * heightDp
        snappedProgress = if (isWide) 1f else 0f
        currentFrame = resolve(isSettled = true)
    }

    /**
     * Which side of the choreography a resting rectangle belongs to.
     *
     * A degenerate span means there is no transition to measure progress across,
     * so the side has to be inferred from the size itself. Area is the right
     * discriminator because every wide presentation (the expanded card, the alert
     * peek, the inbox) is larger than the compact pill in both dimensions.
     */
    private fun restingProgressFor(w: Float, h: Float): Float =
        if (w * h > COMPACT_AREA) 1f else 0f

    /**
     * Advances every dimension by [dtSeconds] and resolves one frame.
     *
     * A single call drives every spring, so they cannot drift apart.
     *
     * @return the resolved frame, also available via [currentFrame].
     */
    fun advance(dtSeconds: Float): Frame {
        // Every spring steps on the SAME delta. This is the whole point of the
        // class: three separate Animatable coroutines previously advanced on
        // their own callbacks and could land on different frames.
        val a = width.update(dtSeconds)
        val b = height.update(dtSeconds)
        val c = radius.update(dtSeconds)
        val d = bubble.update(dtSeconds)

        if (releasing) {
            val movingX = releaseX.update(dtSeconds)
            val movingY = releaseY.update(dtSeconds)
            if (!movingX && !movingY) {
                // Park exactly on zero so the resting frame is bit-identical to
                // the no-drag frame, instead of a residual 0.04 dp offset.
                releasing = false
                releaseX.snapTo(0f)
                releaseY.snapTo(0f)
            }
        }

        // A pointer-driven drag is motion even though no spring is moving. If the
        // settle test ignored it the clock would park mid-drag and the pill would
        // stop following the finger until the next event re-armed it.
        val isSettled = !(a || b || c || d) && !releasing && !isDragging
        if (isSettled) {
            currentIsWide = destinationIsWide
            width.snapTo(width.target)
            height.snapTo(height.target)
            radius.snapTo(radius.target)
            bubble.snapTo(bubble.target)
        }

        currentFrame = resolve(isSettled = isSettled)
        return currentFrame
    }

    // --- Drag input --------------------------------------------------------

    /**
     * Adds raw pointer travel, in dp. Positive y is downward.
     *
     * The raw value is stored unclamped; resistance is applied when the frame is
     * resolved, so releasing the drag springs back from the *resisted* position
     * rather than snapping.
     */
    fun addDrag(dxDp: Float, dyDp: Float) {
        // A new drag takes over from any in-flight release.
        if (releasing) {
            val w = width.value
            val h = height.value
            val currentResistedX = releaseX.value
            val currentResistedY = releaseY.value
            releasing = false
            releaseX.snapTo(0f)
            releaseY.snapTo(0f)
            dragRawX = if (currentResistedX == 0f) 0f else sign(currentResistedX) * inverseRubberBand(abs(currentResistedX), w, RUBBER_BAND_C)
            dragRawY = if (currentResistedY > 0f) inverseRubberBand(currentResistedY, h, RUBBER_BAND_C) else 0f
        }
        dragRawX += dxDp
        dragRawY += dyDp
    }

    /**
     * Ends the drag, handing the current resisted offsets to the snap-back
     * springs so the pill travels back to rest instead of teleporting.
     */
    fun clearDrag() {
        if (isDragging) {
            val w = width.value
            val h = height.value
            releaseX.snapTo(resistedX(w))
            releaseY.snapTo(resistedStretch(h))
            releaseX.retarget(0f)
            releaseY.retarget(0f)
            releasing = true
        }
        dragRawX = 0f
        dragRawY = 0f
    }

    // --- Frame resolution --------------------------------------------------

    fun resistedStretch(h: Float): Float =
        if (dragRawY > 0f) rubberBand(dragRawY, h, RUBBER_BAND_C) else 0f

    fun resistedX(w: Float): Float =
        if (dragRawX == 0f) 0f else sign(dragRawX) * rubberBand(abs(dragRawX), w, RUBBER_BAND_C)

    private fun resolve(isSettled: Boolean = false): Frame {
        val w = width.value
        val h = height.value

        // Rubber-band resistance, applied at read time. Only downward drag
        // stretches the pill; only horizontal drag shifts it. After release the
        // same values come from the snap-back springs, so the geometry is
        // continuous across the hand-off.
        val stretch = when {
            dragRawY > 0f -> resistedStretch(h)
            releasing -> releaseY.value.coerceAtLeast(0f)
            else -> 0f
        }
        val dragX = when {
            dragRawX != 0f -> resistedX(w)
            releasing -> releaseX.value
            else -> 0f
        }

        // Content choreography, driven by AREA progress rather than width
        // travel (PLAN.md Finding 15): it is the only formulation under which
        // the specification's own frame-9 state is consistent with its own 70%
        // claim.
        val progress = areaProgress(w, h)
        val alphas = choreography(progress)
        val compactAlpha = unpackCompact(alphas)
        val expandedAlpha = unpackExpanded(alphas)

        return Frame(
            widthDp = w,
            heightDp = h,
            radiusDp = radius.value,
            secondaryScale = bubble.value.coerceIn(0f, 1f),
            compactAlpha = compactAlpha,
            expandedAlpha = expandedAlpha,
            stretchDp = stretch,
            dragOffsetXDp = dragX,
            settled = isSettled
        )
    }

    /**
     * How far the current transition has run, as normalised area.
     *
     * Clamped to 0..1 so a rubber-band stretch — which changes height without
     * being part of the transition — cannot push choreography past its ends.
     *
     * The span is the *current transition's* origin and destination, set by
     * [moveTo]. See [originArea] for why a fixed pair of reference rectangles
     * cannot work across presentations of different sizes.
     */
    private fun areaProgress(w: Float, h: Float): Float {
        snappedProgress?.let { return it }
        val span = destinationArea - originArea
        // Only a degenerate span (origin == destination) lacks a transition to
        // measure progress across. A negative span is the normal collapse case
        // (contracting from expanded to compact); both numerator and denominator
        // are negative, so their quotient smoothly yields progress from 0.0 to 1.0.
        if (abs(span) < 1f) return restingProgressFor(w, h)
        return ((w * h - originArea) / span).coerceIn(0f, 1f)
    }

    /**
     * Maps transition progress onto the two content layers' opacities.
     * Packed into a 64-bit Long (compact in upper 32 bits, expanded in lower 32 bits)
     * to eliminate Pair allocations in the frame render path.
     */
    private fun choreography(progress: Float): Long {
        val outgoing = (1f - progress / OUT_END).coerceIn(0f, 1f)
        val incoming = ((progress - IN_START) / (1f - IN_START)).coerceIn(0f, 1f)

        return when {
            // Growing out of the pill: compact content leads, card follows.
            !currentIsWide && destinationIsWide -> packAlphas(outgoing, incoming)

            // Collapsing back into the pill: the pill's content returns as the
            // card's departs.
            currentIsWide && !destinationIsWide -> packAlphas(incoming, outgoing)

            // Both ends on the same side or resting:
            destinationIsWide -> packAlphas(0f, 1f)
            else -> packAlphas(1f, 0f)
        }
    }

    private fun packAlphas(compact: Float, expanded: Float): Long =
        (compact.toBits().toLong() shl 32) or (expanded.toBits().toLong() and 0xFFFFFFFFL)

    private fun unpackCompact(packed: Long): Float =
        Float.fromBits((packed ushr 32).toInt())

    private fun unpackExpanded(packed: Long): Float =
        Float.fromBits(packed.toInt())

    /**
     * Apple's non-linear rubber-band resistance:
     * `f(x, d, c) = (1 − 1/((x·c/d) + 1)) · d`.
     *
     * Asymptotically approaches `d` as the finger travels, so the island always
     * keeps moving a little rather than stopping dead — the detail that makes
     * an over-drag feel elastic rather than broken.
     */
    fun rubberBand(rawDelta: Float, dimension: Float, c: Float = RUBBER_BAND_C): Float {
        if (rawDelta <= 0f || dimension <= 0f) return 0f
        return (1f - (1f / ((rawDelta * c / dimension) + 1f))) * dimension
    }

    /**
     * Exact algebraic inverse of [rubberBand]:
     * `x = (d · y) / (c · (d − y))`.
     *
     * Used when a new drag interrupts an in-flight snapback release, so the
     * raw drag accumulator is seeded to reproduce the live resisted offset
     * without any positional step.
     */
    fun inverseRubberBand(resistedDelta: Float, dimension: Float, c: Float = RUBBER_BAND_C): Float {
        if (resistedDelta <= 0f || dimension <= 0f || c <= 0f) return 0f
        val clampedY = resistedDelta.coerceAtMost(dimension * 0.99f)
        return (dimension * clampedY) / (c * (dimension - clampedY))
    }

    /**
     * A static resting frame for the constructor, before any [advance].
     *
     * Frames for resting states otherwise go through the normal [resolve] path so
     * the alpha gates are applied in exactly one place; hardcoding them is how a
     * snapped card would end up with its content invisible.
     */
    private fun restingFrame(w: Float, h: Float) = Frame(
        widthDp = w,
        heightDp = h,
        radiusDp = INITIAL_RADIUS_DP,
        secondaryScale = 0f,
        compactAlpha = 1f,
        expandedAlpha = 0f,
        stretchDp = 0f,
        dragOffsetXDp = 0f,
        settled = true
    )

    companion object {
        /** Resting capsule, matching the specification's compact pill. */
        const val INITIAL_WIDTH_DP = 130.4f
        const val INITIAL_HEIGHT_DP = 38.6f
        const val INITIAL_RADIUS_DP = 19.3f

        /** The expanded card. */
        const val EXPANDED_WIDTH_DP = 370f
        const val EXPANDED_HEIGHT_DP = 160f

        /** The alert peek card. See [areaProgress] for its role in the span. */
        const val ALERT_WIDTH_DP = 300f
        const val ALERT_HEIGHT_DP = 52f

        /** Area of the resting capsule; the compact/wide discriminator. */
        val COMPACT_AREA = INITIAL_WIDTH_DP * INITIAL_HEIGHT_DP

        /** Apple's standard rubber-band friction constant. */
        const val RUBBER_BAND_C = 0.55f

        /**
         * Progress at which the incoming layer starts fading in.
         *
         * The specification gives 0.70, pairing it with an outgoing ramp that
         * ends at 0.30. Those two do not overlap, so the island passes through a
         * fully blank state mid-transition — measured at 10 frames on a 60 Hz
         * panel, which is a visible blink.
         *
         * Starting the incoming ramp at 0.30 and running the outgoing one to 0.60
         * keeps the specification's staging (compact content leads, card content
         * follows) while guaranteeing the two always overlap.
         */
        const val IN_START = 0.30f

        /** Progress at which the outgoing layer has fully faded. */
        const val OUT_END = 0.60f

        /**
         * Whether a footprint is one of the wide presentations.
         *
         * Height 45 dp sits between the 38.6 dp pill and the 52 dp alert peek;
         * width 280 dp sits between the 236 dp compact pill and both wide cards.
         */
        fun isWideFootprint(widthDp: Float, heightDp: Float): Boolean =
            heightDp > 45f || widthDp > 280f
    }
}
