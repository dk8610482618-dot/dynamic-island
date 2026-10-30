package com.dynamicisland.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Physical-size normalization, verified against real device profiles.
 *
 * This is the test that cannot be run on the emulator: the `island_test` AVD
 * reports `xdpi == densityDpi == 420`, so its ratio is exactly 1.0 and the
 * physical path is indistinguishable from the canonical one there. Synthetic
 * profiles are therefore the only way to verify the math. See DEVICE_NOTES.md
 * Finding A.
 */
class DensityNormalizerTest {

    // =====================================================================
    // The physical anchor
    // =====================================================================

    /**
     * The specification's own numbers must produce the specification's own
     * physical size.
     */
    @Test
    fun `specification anchor is 20_7mm wide and 6_1mm tall`() {
        assertEquals(375f, DensityNormalizer.IPHONE_COMPACT_WIDTH_PX, 0.001f)
        assertEquals(111f, DensityNormalizer.IPHONE_COMPACT_HEIGHT_PX, 0.001f)
        assertEquals(20.707f, DensityNormalizer.IPHONE_COMPACT_WIDTH_MM, 0.01f)
        assertEquals(6.129f, DensityNormalizer.IPHONE_COMPACT_HEIGHT_MM, 0.01f)
    }

    /**
     * On a panel whose `xdpi` genuinely matches its density bucket, the physical
     * size is reached at ≈130.43 dp — **not** at the specification's 125 dp.
     *
     * This is the crux of Finding 4: 125 is an iPhone *point* value, and a
     * point is not a dp. Treating them as equal makes the island ~4% too small
     * even on a well-behaved device.
     */
    @Test
    fun `a matched 460ppi panel yields 130_43 dp not 125 dp`() {
        val normalizer = DensityNormalizer.forProfile(DensityNormalizer.referencePanel())

        assertEquals(DensityNormalizer.Mode.PHYSICAL, normalizer.mode)

        val widthDp = normalizer.pointsToDp(DensityNormalizer.IPHONE_COMPACT_WIDTH_PT)
        val heightDp = normalizer.pointsToDp(DensityNormalizer.IPHONE_COMPACT_HEIGHT_PT)

        assertEquals("iPhone 125 pt is 130.43 dp of physical glass", 130.43f, widthDp, 0.05f)
        assertEquals("iPhone 37 pt is 38.61 dp of physical glass", 38.61f, heightDp, 0.05f)

        assertFalse(
            "confusing points with dp would give 125; the whole point of this " +
                "path is that it does not",
            kotlin.math.abs(widthDp - 125f) < 1f
        )
    }

    /** [DensityNormalizer.CANONICAL_SCALE] must agree with the anchor. */
    @Test
    fun `canonical scale is the anchor ratio`() {
        assertEquals(1.0435f, DensityNormalizer.CANONICAL_SCALE, 0.001f)
    }

    // =====================================================================
    // Real device profiles — the table from PLAN.md Finding 4
    // =====================================================================

    /**
     * Each real device must land within a hair of the iPhone's physical width.
     *
     * The expected dp figures are derived from `xPpi / densityDpi`:
     * `130.43 × ratio`. They are NOT the same number across devices — that is
     * the point. A Pixel 8 Pro needs a visibly larger dp value than a Galaxy S24
     * to look the same size on glass.
     */
    @Test
    fun `real device profiles reach the same physical width in different dp`() {
        data class Device(val name: String, val xdpi: Float, val densityDpi: Int)

        val devices = listOf(
            Device("Pixel 8 Pro", 489f, 420),
            Device("Pixel 8", 428f, 420),
            Device("Galaxy S24", 416f, 450),
            Device("OnePlus 12", 510f, 480),
            Device("iPhone-class 460ppi", 460f, 460),
        )

        val referenceMm = DensityNormalizer.IPHONE_COMPACT_WIDTH_MM

        for (d in devices) {
            val profile = DeviceGeometryProfile(
                densityDpi = d.densityDpi,
                xdpi = d.xdpi,
                ydpi = d.xdpi,
                widthPx = 1080,
                heightPx = 2400,
            )
            val normalizer = DensityNormalizer.forProfile(profile)
            assertEquals("${d.name} should take the physical path", DensityNormalizer.Mode.PHYSICAL, normalizer.mode)

            val widthDp = normalizer.pointsToDp(DensityNormalizer.IPHONE_COMPACT_WIDTH_PT)

            // Convert the chosen dp back through Android's own density to mm.
            val renderedMm = widthDp * profile.densityScale / d.xdpi * MM_PER_INCH

            assertEquals(
                "${d.name}: rendered width should be the iPhone's physical width",
                referenceMm, renderedMm, 0.02f
            )
        }
    }

    /**
     * The specific regression that motivated the physical path.
     *
     * A naive `125 dp` island renders **17.04 mm** of glass on a Pixel 8 Pro
     * against the iPhone's **20.71 mm** — 3.7 mm short, an 18% error, plainly
     * visible next to a real device.
     */
    @Test
    fun `a naive 125 dp island is too small on a Pixel 8 Pro`() {
        val profile = DeviceGeometryProfile(
            densityDpi = 420, xdpi = 489f, ydpi = 489f, widthPx = 1080, heightPx = 2400
        )

        val naiveMm = 125f * profile.densityScale / profile.xdpi * MM_PER_INCH
        assertEquals("the bug: 125 dp is only 17.04 mm here", 17.04f, naiveMm, 0.05f)

        val normalizer = DensityNormalizer.forProfile(profile)
        val correctedDp = normalizer.pointsToDp(125f)
        val correctedMm = correctedDp * profile.densityScale / profile.xdpi * MM_PER_INCH

        assertEquals("the fix: 20.71 mm, matching an iPhone", 20.71f, correctedMm, 0.05f)
        assertEquals("which takes 151.86 dp on this panel", 151.86f, correctedDp, 0.05f)

        assertTrue(
            "the correction must be a real change, not cosmetic",
            kotlin.math.abs(naiveMm - correctedMm) > 3f
        )
    }

    /**
     * The naive error has **both signs**, which is why a fixed correction
     * constant cannot work.
     *
     * Where a device's `xdpi` exceeds its density bucket — the Pixel family,
     * most OnePlus panels — a naive island comes out too *small*. Where the
     * density bucket is inflated above the physical density — a Galaxy S24
     * reports 450 against a 416 ppi panel — the same code produces an island
     * that is too *big*. Only the per-device ratio fixes both.
     */
    @Test
    fun `the naive error has both signs across real devices`() {
        val targetMm = DensityNormalizer.IPHONE_COMPACT_WIDTH_MM

        data class Case(val name: String, val xdpi: Float, val densityDpi: Int, val tooSmall: Boolean)
        val cases = listOf(
            Case("Pixel 8 Pro", 489f, 420, tooSmall = true),
            Case("Pixel 8", 428f, 420, tooSmall = true),
            Case("OnePlus 12", 510f, 480, tooSmall = true),
            Case("iPhone-class 460", 460f, 460, tooSmall = true),
            Case("Galaxy S24", 416f, 450, tooSmall = false),
        )

        var sawTooSmall = false
        var sawTooBig = false

        for (c in cases) {
            val profile = DeviceGeometryProfile(
                densityDpi = c.densityDpi, xdpi = c.xdpi, ydpi = c.xdpi,
                widthPx = 1080, heightPx = 2400
            )
            val naiveMm = 125f * profile.densityScale / c.xdpi * MM_PER_INCH
            val delta = naiveMm - targetMm

            if (c.tooSmall) {
                sawTooSmall = true
                assertTrue(
                    "${c.name}: naive 125 dp (${"%.2f".format(naiveMm)} mm) should be SHORT " +
                        "of the ${"%.2f".format(targetMm)} mm target",
                    delta < 0f
                )
            } else {
                sawTooBig = true
                assertTrue(
                    "${c.name}: naive 125 dp (${"%.2f".format(naiveMm)} mm) should EXCEED " +
                        "the ${"%.2f".format(targetMm)} mm target, because its density bucket " +
                        "is inflated above its physical density",
                    delta > 0f
                )
            }

            // Whatever the direction, normalization must land on target.
            val n = DensityNormalizer.forProfile(profile)
            val fixedMm = n.pointsToDp(125f) * profile.densityScale / c.xdpi * MM_PER_INCH
            assertEquals("${c.name}: must land on target either way", targetMm, fixedMm, 0.02f)
        }

        assertTrue("the suite must cover the too-small direction", sawTooSmall)
        assertTrue("the suite must cover the too-big direction", sawTooBig)
    }

    // =====================================================================
    // The guard band — fallback is a normal path
    // =====================================================================

    /** Every implausible `xdpi` must fall back rather than produce nonsense. */
    @Test
    fun `implausible xdpi falls back to canonical dp`() {
        data class Bad(val label: String, val xdpi: Float, val densityDpi: Int = 420)

        val bad = listOf(
            Bad("zero", 0f),
            Bad("negative", -1f),
            Bad("not a number", Float.NaN),
            Bad("positive infinity", Float.POSITIVE_INFINITY),
            Bad("absurdly large", 9999f),
            Bad("absurdly small", 5f),
            Bad("ratio just below band", 209f),   // 0.498
            Bad("ratio just above band", 841f),   // 2.002
        )

        for (b in bad) {
            val profile = DeviceGeometryProfile(
                densityDpi = b.densityDpi, xdpi = b.xdpi, ydpi = b.xdpi,
                widthPx = 1080, heightPx = 2400
            )
            val normalizer = DensityNormalizer.forProfile(profile)

            assertEquals("${b.label}: must fall back", DensityNormalizer.Mode.CANONICAL, normalizer.mode)
            assertEquals("${b.label}: canonical scale is 1.0", 1f, normalizer.scale, 0f)
            assertEquals(
                "${b.label}: specification dp passes through unchanged",
                125f, normalizer.pointsToDp(125f), 0f
            )
            assertTrue("${b.label}: must explain itself", normalizer.rejectionReason().isNotBlank())
        }
    }

    /** The band's inclusive edges must be accepted, not rejected. */
    @Test
    fun `ratios inside the guard band are accepted`() {
        val edges = listOf(
            0.5f to "lower edge",
            2.0f to "upper edge",
            1.0f to "exact match",
            1.164f to "Pixel 8 Pro, the worst real case",
        )
        for ((ratio, label) in edges) {
            val profile = DeviceGeometryProfile(
                densityDpi = 400, xdpi = 400f * ratio, ydpi = 400f * ratio,
                widthPx = 1080, heightPx = 2400
            )
            assertEquals(
                "$label (ratio $ratio) should take the physical path",
                DensityNormalizer.Mode.PHYSICAL,
                DensityNormalizer.forProfile(profile).mode
            )
        }
    }

    /** `forceCanonical` must override even a perfectly good profile. */
    @Test
    fun `forceCanonical overrides a usable profile`() {
        val n = DensityNormalizer.forProfile(
            DensityNormalizer.referencePanel(), forceCanonical = true
        )
        assertEquals(DensityNormalizer.Mode.CANONICAL, n.mode)
        assertEquals(125f, n.pointsToDp(125f), 0f)
        assertTrue(n.isForcedCanonical)
        assertEquals("forced canonical mode", n.rejectionReason())
        assertTrue(n.describe().contains("forced canonical mode"))
    }

    // =====================================================================
    // Round-trip conversions and unit helpers
    // =====================================================================

    @Test
    fun `pointsToDp and dpToPoints round-trip with precision`() {
        val normalizer = DensityNormalizer.forProfile(DensityNormalizer.referencePanel())
        val originalPt = 125f
        val dp = normalizer.pointsToDp(originalPt)
        val roundTripPt = normalizer.dpToPoints(dp)

        assertEquals(originalPt, roundTripPt, 0.001f)
    }

    @Test
    fun `pointsToDpRounded correctly rounds to nearest integer dp`() {
        val normalizer = DensityNormalizer.forProfile(DensityNormalizer.referencePanel())
        // 125 pt * 1.0435 = 130.434 dp -> rounds to 130
        assertEquals(130f, normalizer.pointsToDpRounded(125f), 0.001f)
        // 37 pt * 1.0435 = 38.608 dp -> rounds to 39
        assertEquals(39f, normalizer.pointsToDpRounded(37f), 0.001f)
    }

    @Test
    fun `island dimensions in mm agree with physical specification`() {
        val normalizer = DensityNormalizer.forProfile(DensityNormalizer.referencePanel())
        assertEquals(20.707f, normalizer.islandWidthMm(), 0.01f)
        assertEquals(6.129f, normalizer.islandHeightMm(), 0.01f)
    }

    @Test
    fun `pixel conversion helpers are accurate and bidirectional`() {
        val profile = DeviceGeometryProfile(
            densityDpi = 320, // 2.0x scale
            xdpi = 320f,
            ydpi = 320f,
            widthPx = 1080,
            heightPx = 1920
        )
        val normalizer = DensityNormalizer.forProfile(profile)
        val dp = 100f
        val px = normalizer.dpToPx(dp)
        assertEquals(200f, px, 0.001f)
        assertEquals(dp, normalizer.pxToDp(px), 0.001f)

        val pt = 50f
        val pxFromPt = normalizer.pointsToPx(pt)
        val roundTripPt = normalizer.pxToPoints(pxFromPt)
        assertEquals(pt, roundTripPt, 0.001f)
    }

    @Test
    fun `rejectionReason correctly handles non-positive densityDpi`() {
        val zeroDpiProfile = DeviceGeometryProfile(
            densityDpi = 0, xdpi = 400f, ydpi = 400f, widthPx = 1080, heightPx = 2400
        )
        val nZero = DensityNormalizer.forProfile(zeroDpiProfile)
        assertEquals(DensityNormalizer.Mode.CANONICAL, nZero.mode)
        assertEquals("densityDpi not positive (0)", nZero.rejectionReason())

        val negDpiProfile = DeviceGeometryProfile(
            densityDpi = -160, xdpi = 400f, ydpi = 400f, widthPx = 1080, heightPx = 2400
        )
        val nNeg = DensityNormalizer.forProfile(negDpiProfile)
        assertEquals("densityDpi not positive (-160)", nNeg.rejectionReason())
    }

    @Test
    fun `describe outputs decimal points with US locale`() {
        val normalizer = DensityNormalizer.forProfile(DensityNormalizer.referencePanel())
        val desc = normalizer.describe()
        assertTrue("Description must contain decimal dot: $desc", desc.contains("scale=1.0435"))
        assertFalse("Description must not contain comma: $desc", desc.contains(","))
    }

    // =====================================================================
    // DeviceGeometryProfile and PixelRect
    // =====================================================================

    @Test
    fun `DeviceGeometryProfile metrics and orientation helpers are correct`() {
        val portraitProfile = DeviceGeometryProfile(
            densityDpi = 480, // 3.0x
            xdpi = 480f,
            ydpi = 480f,
            widthPx = 1080,
            heightPx = 2400
        )
        assertTrue(portraitProfile.isPortrait)
        assertFalse(portraitProfile.isLandscape)
        assertEquals(360f, portraitProfile.widthDp, 0.01f)
        assertEquals(800f, portraitProfile.heightDp, 0.01f)
        assertEquals(1080f / 480f * MM_PER_INCH, portraitProfile.widthMm, 0.01f)
        assertEquals(2400f / 480f * MM_PER_INCH, portraitProfile.heightMm, 0.01f)
        assertEquals(1080f / 2400f, portraitProfile.aspectRatio, 0.001f)

        val landscapeProfile = DeviceGeometryProfile(
            densityDpi = 480,
            xdpi = 480f,
            ydpi = 480f,
            widthPx = 2400,
            heightPx = 1080
        )
        assertTrue(landscapeProfile.isLandscape)
        assertFalse(landscapeProfile.isPortrait)
        assertEquals(2400f / 1080f, landscapeProfile.aspectRatio, 0.001f)
    }

    @Test
    fun `DeviceGeometryProfile division by zero protection`() {
        val zeroProfile = DeviceGeometryProfile(
            densityDpi = 0,
            xdpi = 0f,
            ydpi = 0f,
            widthPx = 0,
            heightPx = 0
        )
        assertEquals(1f, zeroProfile.densityScale, 0.001f)
        assertEquals(0f, zeroProfile.widthDp, 0.001f)
        assertEquals(0f, zeroProfile.heightDp, 0.001f)
        assertEquals(0f, zeroProfile.widthMm, 0.001f)
        assertEquals(0f, zeroProfile.heightMm, 0.001f)
        assertEquals(0f, zeroProfile.aspectRatio, 0.001f)
    }

    @Test
    fun `PixelRect geometry intersections and bounding union`() {
        val r1 = PixelRect(left = 10, top = 20, right = 50, bottom = 60)
        assertEquals(40, r1.width)
        assertEquals(40, r1.height)
        assertEquals(30f, r1.centerX, 0.001f)
        assertEquals(40f, r1.centerY, 0.001f)
        assertFalse(r1.isEmpty)

        val empty = PixelRect(50, 60, 50, 60)
        assertTrue(empty.isEmpty)

        val overlapping = PixelRect(30, 40, 80, 90)
        assertTrue(r1.intersects(overlapping))

        val disjoint = PixelRect(100, 100, 150, 150)
        assertFalse(r1.intersects(disjoint))

        val unionRect = r1.union(overlapping)
        assertEquals(10, unionRect.left)
        assertEquals(20, unionRect.top)
        assertEquals(80, unionRect.right)
        assertEquals(90, unionRect.bottom)

        assertTrue(r1.contains(25, 35))
        assertTrue(r1.contains(25f, 35f))
        assertFalse(r1.contains(5, 5))
    }

    // =====================================================================
    // Emulator reality
    // =====================================================================

    /**
     * Pins the measured behaviour of the actual verification device.
     *
     * `island_test` reports `xdpi == densityDpi == 420`, so it takes the
     * physical path with a scale of ≈1.0435 — but that is *only* because the
     * ratio is 1.0; the emulator has no genuine physical-density discrepancy to
     * exercise. If this test ever changes, the AVD configuration changed and
     * the device notes are stale.
     */
    @Test
    fun `island_test avd profile matches the measured device`() {
        val profile = DeviceGeometryProfile(
            densityDpi = 420,
            xdpi = 420f,
            ydpi = 420f,
            widthPx = 1080,
            heightPx = 2400,
            cutoutRectPx = PixelRect(480, 0, 625, 136),
            cutoutCircleDiameterPx = 68f,
        )

        assertEquals("density scale is 2.625", 2.625f, profile.densityScale, 0.001f)
        assertEquals("1080 px is 411.4 dp wide", 411.43f, profile.widthDp, 0.05f)

        val normalizer = DensityNormalizer.forProfile(profile)
        assertEquals(DensityNormalizer.Mode.PHYSICAL, normalizer.mode)
        assertEquals(
            "ratio 1.0 means the emulator reduces to the canonical scale",
            DensityNormalizer.CANONICAL_SCALE,
            normalizer.scale,
            0.001f
        )
        assertEquals("hence 130.4 dp", 130.43f, normalizer.pointsToDp(125f), 0.05f)
    }
}
