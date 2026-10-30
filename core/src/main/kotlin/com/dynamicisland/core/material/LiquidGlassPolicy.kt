package com.dynamicisland.core.material

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Gap **G5** — Liquid Glass material, contrast and the 8-point grid.
 *
 * Doc 2 requires two things the imported base did not do. Its entire
 * `AppearancePreset.LIQUID_GLASS` implementation was one line that set the
 * capsule fill to `Color.White.copy(alpha = 0.38f)` — a flat alpha swap, with no
 * blur, no elevation, and crucially no rule about stacking. Doc 2 is explicit:
 * use "adaptive translucent blurs to elevate interactive buttons above
 * background content while **strictly avoiding stacking glass-on-glass across
 * parent and child views, which creates visual noise and wastes GPU resources**".
 *
 * Doc 2 also fixes the accessibility contract for the same surfaces: an
 * **8-point spatial grid**, **4.5:1** contrast for normal body text, **3:1** for
 * large heavier-weight text, and Dynamic Type scaling. Those are measurable, so
 * they are implemented as real arithmetic here (WCAG 2.x relative luminance)
 * rather than as a comment someone has to remember — a translucent white overlay
 * on dark artwork is exactly the case where contrast silently fails.
 *
 * Pure JVM: colours are plain `Int` ARGB values. No `android.graphics.Color`, so
 * the tests genuinely execute the maths.
 */
object LiquidGlassPolicy {

    /** Doc 2's spatial grid. */
    const val GRID_DP: Float = 8f

    /** WCAG AA for normal body text. */
    const val MIN_CONTRAST_BODY: Double = 4.5

    /** WCAG AA for large or heavier-weight text. */
    const val MIN_CONTRAST_LARGE: Double = 3.0

    /**
     * Apple's own guidance treats ~18.66 px / 14 pt bold and up as "large".
     * In sp, 18 sp is the practical threshold on Android.
     */
    const val LARGE_TEXT_SP: Float = 18f

    /** Surfaces that may carry the glass treatment. */
    enum class GlassSurface {
        /** The expanded card itself — the outer glass pane. */
        CARD,

        /** A button, chip or scrubber sitting inside the card. */
        CONTROL,

        /** A sheet presented above the card (output routing, action list). */
        POPOVER
    }

    /** What a surface should actually render after anti-stacking resolution. */
    enum class SurfaceTreatment {
        /** Real translucent blur. */
        GLASS,

        /**
         * No blur: a subtle solid tint. Used for a child whose parent is already
         * glass, so the GPU does not composite two blurred layers and the two
         * do not produce moiré where they overlap.
         */
        SOLID_TINT,

        /** Nothing: the surface is content-only, relying on its parent's glass. */
        NONE
    }

    data class ResolvedSurface(
        val surface: GlassSurface,
        val treatment: SurfaceTreatment,
        /** Blur radius in px at density 1.0; 0 when [treatment] is not GLASS. */
        val blurRadiusDp: Float,
        /** Overlay tint alpha, 0..1. */
        val tintAlpha: Float,
        /** True when this surface was demoted from GLASS to avoid stacking. */
        val demotedToAvoidStacking: Boolean = false
    )

    /**
     * Resolves a stack of surfaces so that **at most one layer in any
     * parent→child chain blurs**.
     *
     * The chain is ordered outermost first: `[CARD, CONTROL]` means a control
     * inside a card. When the card already renders glass, its controls are
     * demoted to [SurfaceTreatment.SOLID_TINT]; when the card is opaque (for
     * example under Reduce Transparency or on a low-power device), the controls
     * may keep their own glass because nothing is stacked behind them.
     *
     * @param parentIsGlass whether an ancestor *outside* this list is already
     *   blurred, e.g. a system sheet hosting the card.
     */
    fun resolveSurfaces(
        chain: List<GlassSurface>,
        appearanceIsLiquidGlass: Boolean,
        reduceTransparency: Boolean = false,
        lowPowerMode: Boolean = false,
        parentIsGlass: Boolean = false
    ): List<ResolvedSurface> {
        // Glass is opt-in via the appearance preset; blur costs a full-screen
        // offscreen buffer, so it is also dropped under reduce-transparency and
        // low-power, which are the two cases where the user or the system has
        // already asked for cheaper compositing.
        val glassAllowed = appearanceIsLiquidGlass && !reduceTransparency && !lowPowerMode

        var ancestorBlurred = parentIsGlass
        return chain.map { surface ->
            // Glass is opt-in via the appearance preset; blur costs a full-screen
            // offscreen buffer, so it is also dropped under reduce-transparency
            // and low-power, which are the two cases where the user or the system
            // has already asked for cheaper compositing.
            val wantsGlass = glassAllowed
            val treatment = when {
                !wantsGlass -> SurfaceTreatment.NONE
                ancestorBlurred -> SurfaceTreatment.SOLID_TINT
                else -> SurfaceTreatment.GLASS
            }
            if (treatment == SurfaceTreatment.GLASS) ancestorBlurred = true

            ResolvedSurface(
                surface = surface,
                treatment = treatment,
                blurRadiusDp = if (treatment == SurfaceTreatment.GLASS) blurRadiusFor(surface, lowPowerMode) else 0f,
                tintAlpha = tintAlphaFor(surface, treatment),
                demotedToAvoidStacking = wantsGlass && treatment == SurfaceTreatment.SOLID_TINT
            )
        }
    }

    /** Blur radius per surface. Deeper surfaces blur more: they hide more. */
    fun blurRadiusFor(surface: GlassSurface, lowPowerMode: Boolean): Float = if (lowPowerMode) {
        0f
    } else {
        when (surface) {
            GlassSurface.CARD -> 24f
            GlassSurface.CONTROL -> 12f
            GlassSurface.POPOVER -> 32f
        }
    }

    /**
     * Tint alpha per treatment. The control tint is deliberately lighter than the
     * card's so a button still reads as *above* the card once its blur has been
     * removed by anti-stacking.
     */
    fun tintAlphaFor(surface: GlassSurface, treatment: SurfaceTreatment): Float = when (treatment) {
        SurfaceTreatment.NONE -> 0f
        SurfaceTreatment.GLASS -> when (surface) {
            GlassSurface.CARD -> 0.32f
            GlassSurface.CONTROL -> 0.18f
            GlassSurface.POPOVER -> 0.28f
        }
        SurfaceTreatment.SOLID_TINT -> when (surface) {
            GlassSurface.CARD -> 0.92f
            GlassSurface.CONTROL -> 0.26f
            GlassSurface.POPOVER -> 0.95f
        }
    }

    // ---------------------------------------------------------------------
    // Contrast and colour arithmetic (WCAG 2.x)
    // ---------------------------------------------------------------------

    /** Extracts 0..1 red from an ARGB int. */
    fun red(argb: Int): Float = ((argb shr 16) and 0xFF) / 255f

    fun green(argb: Int): Float = ((argb shr 8) and 0xFF) / 255f

    fun blue(argb: Int): Float = (argb and 0xFF) / 255f

    fun alpha(argb: Int): Float = ((argb shr 24) and 0xFF) / 255f

    /** Linearises one sRGB channel. */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance, 0 (black) .. 1 (white). Ignores alpha. */
    fun relativeLuminance(argb: Int): Double {
        val r = linearize(red(argb))
        val g = linearize(green(argb))
        val b = linearize(blue(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * WCAG contrast ratio, 1..21. Order-independent: the lighter colour is
     * always the numerator.
     */
    fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
        val l1 = relativeLuminance(foregroundArgb)
        val l2 = relativeLuminance(backgroundArgb)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Composites a translucent `foreground` over an opaque `background`, which is
     * what a glass tint actually is. Needed because checking the tint colour
     * alone would report a passing contrast that the composited result does not
     * have.
     */
    fun compositeOver(foregroundArgb: Int, backgroundArgb: Int): Int {
        val a = alpha(foregroundArgb).toDouble()
        val outR = (red(foregroundArgb) * a + red(backgroundArgb) * (1 - a)) * 255f
        val outG = (green(foregroundArgb) * a + green(backgroundArgb) * (1 - a)) * 255f
        val outB = (blue(foregroundArgb) * a + blue(backgroundArgb) * (1 - a)) * 255f
        return (0xFF shl 24) or
            (outR.toInt().coerceIn(0, 255) shl 16) or
            (outG.toInt().coerceIn(0, 255) shl 8) or
            outB.toInt().coerceIn(0, 255)
    }

    /**
     * Doc 2's contrast rule, with the large-text exception applied by size and
     * weight rather than guessed by the caller.
     */
    fun meetsContrast(
        foregroundArgb: Int,
        backgroundArgb: Int,
        textSizeSp: Float,
        isBoldOrHeavier: Boolean = false
    ): Boolean {
        val isLarge = textSizeSp >= LARGE_TEXT_SP || (isBoldOrHeavier && textSizeSp >= 14f)
        val required = if (isLarge) MIN_CONTRAST_LARGE else MIN_CONTRAST_BODY
        return contrastRatio(foregroundArgb, backgroundArgb) >= required
    }

    /**
     * Darkens or lightens [foregroundArgb] until it clears the required ratio
     * against [backgroundArgb]. Used when album-art tinting produces a colour
     * that would fail contrast on the black island surface — the tint is kept as
     * the hue, only its luminance moves.
     *
     * Returns the original colour when it already passes.
     */
    fun ensureContrast(
        foregroundArgb: Int,
        backgroundArgb: Int,
        textSizeSp: Float,
        isBoldOrHeavier: Boolean = false,
        maxSteps: Int = 24
    ): Int {
        if (meetsContrast(foregroundArgb, backgroundArgb, textSizeSp, isBoldOrHeavier)) return foregroundArgb

        val isLarge = textSizeSp >= LARGE_TEXT_SP || (isBoldOrHeavier && textSizeSp >= 14f)
        val required = if (isLarge) MIN_CONTRAST_LARGE else MIN_CONTRAST_BODY

        // Move toward whichever extreme the background is further from.
        val backgroundLuminance = relativeLuminance(backgroundArgb)
        val lighten = backgroundLuminance < 0.5

        var current = foregroundArgb
        repeat(maxSteps) {
            current = stepToward(current, lighten)
            if (contrastRatio(current, backgroundArgb) >= required) return current
        }
        return if (lighten) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    /** Nudges a colour ~6% toward white or black, preserving alpha. */
    private fun stepToward(argb: Int, lighten: Boolean): Int {
        val delta = if (lighten) 16 else -16
        val a = argb and (0xFF shl 24)
        val r = (((argb shr 16) and 0xFF) + delta).coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) + delta).coerceIn(0, 255)
        val b = ((argb and 0xFF) + delta).coerceIn(0, 255)
        return a or (r shl 16) or (g shl 8) or b
    }

    /**
     * Snaps to the 8-point grid required by doc 2.
     *
     * Zero stays reachable on purpose: the same document mandates *zero* internal
     * padding between the compact slots and the camera cutout, so a helper that
     * rounded small insets up to a minimum would break that rule.
     */
    fun snapToGrid(valueDp: Float, gridDp: Float = GRID_DP): Float {
        if (gridDp <= 0f) return valueDp
        return kotlin.math.round(valueDp / gridDp) * gridDp
    }

    /**
     * Scales a base size by the user's Dynamic Type / font-scale factor, bounded
     * so that a 2.0x accessibility scale cannot push the expanded card past
     * doc 2's 160 dp height cap. Beyond the bound, text truncates instead of
     * growing the surface — doc 1: "compact text should truncate before geometry
     * becomes unstable".
     */
    fun scaleForDynamicType(baseSp: Float, fontScale: Float, maxSp: Float = 34f): Float =
        min(baseSp * fontScale.coerceIn(0.85f, 3.0f), maxSp)

    /**
     * Whether the expanded card should grow vertically for large text or clamp at
     * the 160 dp cap and truncate.
     */
    fun expandedHeightDp(naturalHeightDp: Float, fontScale: Float, hardCapDp: Float = 160f): Float =
        min(naturalHeightDp * fontScale.coerceAtLeast(1f), hardCapDp)
}
