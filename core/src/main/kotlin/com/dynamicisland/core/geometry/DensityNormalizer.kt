package com.dynamicisland.core.geometry

import java.util.Locale

/**
 * Converts the specification's iPhone-point dimensions into Android dp such
 * that the island occupies the **same physical size** on any panel.
 *
 * ### The problem this solves
 *
 * The specification's geometry is stated in points on an iPhone, at `@3x`, on a
 * 460 ppi panel: the compact island is `125 pt × 37 pt`, which is
 * `375 × 111` physical pixels, which is **20.707 mm × 6.129 mm** of glass.
 *
 * Android's `dp` is defined as `1/160 inch`, so a naive conversion gives
 * `20.707 mm × 160/25.4 = 130.43 dp`. That would be correct **only if**
 * Android's `densityDpi` equalled the panel's physical density. It very often
 * does not:
 *
 * | device | xdpi | densityDpi | ratio | a naive 125 dp renders as |
 * |---|---|---|---|---|
 * | Pixel 8 Pro | 489 | 420 | 1.164 | 24.1 mm — 3.4 mm too wide |
 * | Galaxy S24 | 416 | 450 | 0.924 | 19.1 mm |
 * | OnePlus 12 | 510 | 480 | 1.063 | 22.0 mm |
 *
 * So the physical size must be recovered through the ratio `xdpi / densityDpi`:
 *
 * ```
 * dp = targetMm × (160 / 25.4) × (xdpi / densityDpi)
 * ```
 *
 * ### Why the guard band exists
 *
 * `xdpi` is unreliable in the field — `0`, negative, absurdly large, or just
 * `densityDpi` copied across. When the ratio falls outside a plausible band the
 * normalizer **falls back to the specification's canonical dp values**, which
 * is the correct behaviour: a slightly-wrong-but-sane island beats a
 * confidently-wrong one.
 *
 * The fallback is therefore a normal operating path, not an error path.
 */
class DensityNormalizer private constructor(
    val profile: DeviceGeometryProfile,
    private val physicalScale: Float,
    val mode: Mode,
    val isForcedCanonical: Boolean = false
) {

    enum class Mode {
        /** `xdpi` was usable; dimensions are scaled to match iPhone physical size. */
        PHYSICAL,

        /**
         * `xdpi` was missing or implausible; the specification's canonical dp
         * values are used verbatim.
         */
        CANONICAL
    }

    /**
     * Multiply a specification value expressed in iPhone **points** by this to
     * get Android dp.
     *
     * In [Mode.CANONICAL] this is 1.0, so specification numbers pass through
     * unchanged — which is exactly right, since the canonical values already
     * *are* the specification's dp figures.
     */
    val scale: Float get() = physicalScale

    /** Converts an iPhone point value to dp. */
    fun pointsToDp(iPhonePoints: Float): Float = iPhonePoints * physicalScale

    /** Converts an iPhone point value to dp, rounded to whole dp. */
    fun pointsToDpRounded(iPhonePoints: Float): Float = kotlin.math.round(pointsToDp(iPhonePoints))

    /** Converts a dp value back to iPhone points, for reporting. */
    fun dpToPoints(dp: Float): Float = dp / physicalScale

    /** Converts an iPhone point value to display pixels. */
    fun pointsToPx(iPhonePoints: Float): Float = pointsToDp(iPhonePoints) * profile.densityScale

    /** Converts an Android dp value to display pixels. */
    fun dpToPx(dp: Float): Float = dp * profile.densityScale

    /** Converts a display pixel value to Android dp. */
    fun pxToDp(px: Float): Float = if (profile.densityScale > 0f) px / profile.densityScale else px

    /** Converts a display pixel value back to iPhone points. */
    fun pxToPoints(px: Float): Float = dpToPoints(pxToDp(px))

    /** The island's physical width in millimetres, for reporting. */
    fun islandWidthMm(): Float = IPHONE_COMPACT_WIDTH_MM

    /** The island's physical height in millimetres, for reporting. */
    fun islandHeightMm(): Float = IPHONE_COMPACT_HEIGHT_MM

    /** A human-readable explanation of how this scale was chosen. */
    fun describe(): String = when (mode) {
        Mode.PHYSICAL ->
            "PHYSICAL scale=${String.format(Locale.US, "%.4f", physicalScale)} " +
                "(xdpi=${profile.xdpi} / densityDpi=${profile.densityDpi})"
        Mode.CANONICAL ->
            if (isForcedCanonical) {
                "CANONICAL scale=1.0000 (forced canonical mode)"
            } else {
                "CANONICAL scale=1.0000 (xdpi=${profile.xdpi} unusable: ${rejectionReason()})"
            }
    }

    /** Why [Mode.CANONICAL] was chosen, for logging and the conformance report. */
    fun rejectionReason(): String {
        if (isForcedCanonical) return "forced canonical mode"
        val xdpi = profile.xdpi
        val densityDpi = profile.densityDpi
        return when {
            densityDpi <= 0 -> "densityDpi not positive ($densityDpi)"
            xdpi <= 0f -> "xdpi not positive"
            !xdpi.isFinite() -> "xdpi not finite"
            else -> {
                val ratio = xdpi / densityDpi.toFloat()
                "ratio ${String.format(Locale.US, "%.3f", ratio)} outside [$MIN_RATIO, $MAX_RATIO]"
            }
        }
    }

    companion object {
        // ------------------------------------------------------------------
        // The specification's physical anchors
        // ------------------------------------------------------------------

        /** An iPhone 15/16 Pro panel: 460 ppi, `@3x`. */
        const val IPHONE_PPI = 460f

        /** Retina scale factor. */
        const val IPHONE_SCALE = 3f

        /** Compact island width in iPhone points. */
        const val IPHONE_COMPACT_WIDTH_PT = 125f

        /** Compact island height in iPhone points. */
        const val IPHONE_COMPACT_HEIGHT_PT = 37f

        /** Compact island width in physical pixels: `125 × 3`. */
        const val IPHONE_COMPACT_WIDTH_PX = IPHONE_COMPACT_WIDTH_PT * IPHONE_SCALE

        /** Compact island height in physical pixels: `37 × 3`. */
        const val IPHONE_COMPACT_HEIGHT_PX = IPHONE_COMPACT_HEIGHT_PT * IPHONE_SCALE

        /** Compact island width in millimetres: ≈20.707 mm. */
        const val IPHONE_COMPACT_WIDTH_MM = IPHONE_COMPACT_WIDTH_PX / IPHONE_PPI * MM_PER_INCH

        /** Compact island height in millimetres: ≈6.129 mm. */
        const val IPHONE_COMPACT_HEIGHT_MM = IPHONE_COMPACT_HEIGHT_PX / IPHONE_PPI * MM_PER_INCH

        /** `dp` per millimetre: `160 / 25.4` ≈ 6.29921. */
        const val DP_PER_MM = 160f / MM_PER_INCH

        /**
         * The scale a perfect-density Android panel would produce: about 1.0435.
         *
         * This is `130.43 dp / 125 pt` — the amount by which a true physical
         * match exceeds the specification's canonical dp value.
         */
        val CANONICAL_SCALE = IPHONE_COMPACT_WIDTH_MM * DP_PER_MM / IPHONE_COMPACT_WIDTH_PT

        /**
         * Plausible band for `xdpi / densityDpi`.
         *
         * Android's density buckets (`ldpi` 0.75 through `xxxhdpi` 4.0) mean a
         * real device's physical density can legitimately differ from its bucket
         * by a wide margin — the Pixel 8 Pro's 1.164 is entirely normal. Outside
         * 0.5–2.0 the reading is almost certainly garbage.
         */
        const val MIN_RATIO = 0.5f
        const val MAX_RATIO = 2.0f

        /**
         * Builds a normalizer for [profile].
         *
         * @param forceCanonical for tests and for reporting; skips the physical
         *   path entirely.
         */
        fun forProfile(
            profile: DeviceGeometryProfile,
            forceCanonical: Boolean = false
        ): DensityNormalizer {
            if (forceCanonical) {
                return DensityNormalizer(profile, 1f, Mode.CANONICAL, isForcedCanonical = true)
            }

            val xdpi = profile.xdpi
            val densityDpi = profile.densityDpi.toFloat()

            val usable = xdpi.isFinite() && xdpi > 0f && densityDpi > 0f
            if (!usable) {
                return DensityNormalizer(profile, 1f, Mode.CANONICAL)
            }

            val ratio = xdpi / densityDpi
            if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
                return DensityNormalizer(profile, 1f, Mode.CANONICAL)
            }

            // dp = mm × (160/25.4) × (xdpi / densityDpi)
            //
            // Note this is written in terms of the millimetre target rather than
            // as `125 × ratio`, because the two differ: a physical match is
            // 130.43 dp on a perfectly-specified panel, not 125. Using
            // `125 × ratio` would silently bake in the canonical value and make
            // the physical path a no-op on the two most common configurations.
            val physical = IPHONE_COMPACT_WIDTH_MM * DP_PER_MM * ratio
            val scale = physical / IPHONE_COMPACT_WIDTH_PT

            return DensityNormalizer(profile, scale, Mode.PHYSICAL)
        }

        /**
         * A profile matching the specification's own reference panel, used by
         * tests to confirm the physical path yields the expected 130.43 dp.
         */
        fun referencePanel(): DeviceGeometryProfile = DeviceGeometryProfile(
            densityDpi = 460,
            xdpi = 460f,
            ydpi = 460f,
            widthPx = 1206,
            heightPx = 2622,
        )

        /** The canonical Android fallback, for a device with no cutout at all. */
        fun noCutoutProfile(): DeviceGeometryProfile = DeviceGeometryProfile(
            densityDpi = 160,
            xdpi = 0f,
            ydpi = 0f,
            widthPx = 1080,
            heightPx = 2400,
        )
    }
}
