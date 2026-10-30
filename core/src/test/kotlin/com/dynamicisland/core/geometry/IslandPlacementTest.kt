package com.dynamicisland.core.geometry

import com.dynamicisland.core.geometry.IslandPlacement.FallbackReason
import com.dynamicisland.core.geometry.IslandPlacement.UserOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-positionable island placement.
 *
 * The default must be cutout-anchored — the whole illusion depends on the black
 * surface blending into the camera aperture — but the user must be able to move
 * it: at least 250 dp upward (a product requirement, because the pill is drawn
 * centred in a tall host and its visible top sits well below the placement
 * top), downward only as far as the navigation bar allows. Horizontal travel
 * keeps the island fully on screen; upward is the one direction allowed past
 * the screen top, and it stays recoverable via Reset.
 */
class IslandPlacementTest {

    /** The measured `island_test` AVD. */
    private fun emulatorProfile() = DeviceGeometryProfile(
        densityDpi = 420,
        xdpi = 420f,
        ydpi = 420f,
        widthPx = 1080,
        heightPx = 2400,
        // Measured: bounding rect 480..625, real circular hole 68 px dia.
        cutoutRectPx = PixelRect(480, 0, 625, 136),
        cutoutCircleDiameterPx = 68f
    )

    private fun noCutoutProfile() = DeviceGeometryProfile(
        densityDpi = 420, xdpi = 420f, ydpi = 420f,
        widthPx = 1080, heightPx = 2400,
        cutoutRectPx = null
    )

    private val density = DensityNormalizer.forProfile(emulatorProfile())

    // =====================================================================
    // Automatic placement
    // =====================================================================

    /**
     * With a cutout, the island's centre must follow the **cutout**, not the
     * screen.
     *
     * This is a real defect the measured device exposes: its hole sits at
     * x = 552.5 px against a screen centre of 540.0. Centring on the screen
     * would leave the island 12.5 px — about 4.8 dp — to the left of the
     * camera, producing exactly the visible seam the specification calls out as
     * a CRITICAL defect.
     */
    @Test
    fun `automatic placement follows the cutout centre not the screen centre`() {
        val profile = emulatorProfile()
        val (placement, reason) = IslandPlacement.resolveAutomatic(
            profile = profile,
            islandWidthDp = 130f,
            statusBarDp = 24f,
            density = density
        )

        val cutoutCentreDp = 552.5f / profile.densityScale      // 210.48 dp
        val screenCentreDp = profile.widthDp / 2f               // 205.71 dp

        assertEquals(
            "island centre must match the cutout centre",
            cutoutCentreDp, placement.centerXDp, 0.05f
        )
        assertTrue("should be anchored to a real cutout", placement.anchoredToCutout)
        assertEquals(
            "an off-centre cutout must be reported, not silently accepted",
            FallbackReason.OFF_CENTRE_CUTOUT, reason
        )

        // The screen centre would be wrong by ~4.8 dp.
        val error = kotlin.math.abs(screenCentreDp - placement.centerXDp)
        assertTrue(
            "the two centres really do differ (by ${"%.2f".format(error)} dp)",
            error > 4f
        )
    }

    /** With no cutout, fall back to the specification's centred footprint. */
    @Test
    fun `automatic placement centres on screen when there is no cutout`() {
        val profile = noCutoutProfile()
        val (placement, reason) = IslandPlacement.resolveAutomatic(
            profile = profile,
            islandWidthDp = 130f,
            statusBarDp = 24f,
            density = DensityNormalizer.forProfile(profile)
        )

        assertEquals("should centre on screen", profile.widthDp / 2f, placement.centerXDp, 0.01f)
        assertFalse("cannot be anchored to a cutout that does not exist", placement.anchoredToCutout)
        assertEquals(FallbackReason.NO_CUTOUT, reason)
    }

    /**
     * The island must sit **below** the status bar.
     *
     * The existing implementation already does this — an overlay at y = 0 is
     * composited beneath SystemUI and gets clipped by the clock. The placement
     * logic must preserve that, including when a user drags upward.
     */
    @Test
    fun `placement clears the status bar`() {
        val statusBar = 24f
        val (placement, _) = IslandPlacement.resolveAutomatic(
            profile = emulatorProfile(),
            islandWidthDp = 130f,
            statusBarDp = statusBar,
            canonicalTopDp = 11f,
            density = density
        )
        assertTrue(
            "top (${placement.topDp} dp) must clear the ${statusBar} dp status bar",
            placement.topDp >= statusBar
        )
    }

    // =====================================================================
    // User offsets
    // =====================================================================

    /** A zero offset must leave the automatic position untouched. */
    @Test
    fun `default offset is a no-op`() {
        assertTrue(UserOffset().isDefault)
        assertEquals(IslandPlacement.Mode.AUTO, UserOffset().modeX)
        assertEquals(IslandPlacement.Mode.AUTO, UserOffset().modeY)

        val result = IslandPlacement.resolve(
            profile = emulatorProfile(),
            islandWidthDp = 130f,
            islandHeightDp = 38f,
            statusBarDp = 24f,
            navigationBarDp = 24f,
            userOffset = UserOffset(),
            density = density
        )

        assertEquals(result.automatic.centerXDp, result.placement.centerXDp, 0.001f)
        assertEquals(result.automatic.topDp, result.placement.topDp, 0.001f)
        assertFalse(result.wasClamped)
        assertTrue(result.appliedOffset.isDefault)
    }

    /** A user offset must move the island on both axes, independently. */
    @Test
    fun `offset moves the island on both axes`() {
        val auto = IslandPlacement.resolve(
            profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(), density = density
        )

        // Positive x is right; positive y is down.
        val moved = IslandPlacement.resolve(
            profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(offsetXDp = 40f, offsetYDp = 60f),
            density = density
        )

        assertEquals("x should move right by 40", auto.placement.centerXDp + 40f, moved.placement.centerXDp, 0.01f)
        assertEquals("y should move down by 60", auto.placement.topDp + 60f, moved.placement.topDp, 0.01f)
        assertFalse("a sane offset should not clamp", moved.wasClamped)
    }

    /** The axes must be independent: moving x must not alter y. */
    @Test
    fun `axes are independent`() {
        val base = IslandPlacement.resolve(
            profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(), density = density
        )

        for (dx in listOf(-100f, -10f, 10f, 100f)) {
            val r = IslandPlacement.resolve(
                profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
                statusBarDp = 24f, navigationBarDp = 24f,
                userOffset = UserOffset(offsetXDp = dx, offsetYDp = 0f), density = density
            )
            assertEquals("x-only move must not change y", base.placement.topDp, r.placement.topDp, 0.001f)
        }

        for (dy in listOf(0f, 20f, 200f)) {
            val r = IslandPlacement.resolve(
                profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
                statusBarDp = 24f, navigationBarDp = 24f,
                userOffset = UserOffset(offsetXDp = 0f, offsetYDp = dy), density = density
            )
            assertEquals("y-only move must not change x", base.placement.centerXDp, r.placement.centerXDp, 0.001f)
        }
    }

    // =====================================================================
    // Clamping — the island must never leave the screen
    // =====================================================================

    /**
     * An absurd horizontal offset must clamp so the island stays fully visible.
     *
     * This is the safety property that matters most: a user dragging the island
     * to the edge must not be able to lose it.
     */
    @Test
    fun `an extreme horizontal offset keeps the island on screen`() {
        val profile = emulatorProfile()
        val islandWidth = 130f

        for (dx in listOf(-100000f, -5000f, 5000f, 100000f)) {
            val r = IslandPlacement.resolve(
                profile = profile, islandWidthDp = islandWidth, islandHeightDp = 38f,
                statusBarDp = 24f, navigationBarDp = 24f,
                userOffset = UserOffset(offsetXDp = dx, offsetYDp = 0f), density = density
            )

            val left = r.placement.leftDp(islandWidth)
            val right = left + islandWidth

            assertTrue(
                "dx=$dx: left edge ($left dp) must be on screen",
                left >= -0.01f
            )
            assertTrue(
                "dx=$dx: right edge ($right dp) must be within ${profile.widthDp} dp",
                right <= profile.widthDp + 0.01f
            )
            assertTrue("dx=$dx: clamping must be reported", r.wasClamped)
        }
    }

    /** An extreme downward offset must clamp above the navigation area. */
    @Test
    fun `an extreme downward offset keeps the island above the navigation bar`() {
        val profile = emulatorProfile()
        val navBar = 24f
        val islandHeight = 38f

        val r = IslandPlacement.resolve(
            profile = profile, islandWidthDp = 130f, islandHeightDp = islandHeight,
            statusBarDp = 24f, navigationBarDp = navBar,
            userOffset = UserOffset(offsetYDp = 100000f), density = density
        )

        val bottom = r.placement.topDp + islandHeight
        assertTrue(
            "island bottom ($bottom dp) must stay above the navigation bar " +
                "at ${profile.heightDp - navBar} dp",
            bottom <= profile.heightDp - navBar + 0.01f
        )
    }

    /**
     * Upward travel must stop at the 250 dp budget, not at the status bar.
     *
     * The old limit was the automatic top itself (status bar + 11 dp = 35 dp
     * on this profile), which stopped the pill roughly a status bar short of
     * the screen edge — its visible top sits about 73 dp below the placement
     * top, so that budget was never enough to place the pill where the user
     * wanted it.
     */
    @Test
    fun `an extreme upward offset clamps at the upward travel budget`() {
        val r = IslandPlacement.resolve(
            profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(offsetYDp = -100000f), density = density
        )
        assertEquals(
            "upward travel must stop at the 250 dp budget",
            -250f, r.appliedOffset.offsetYDp, 0.01f
        )
        assertEquals(
            "the top must move up by the full budget from the automatic top",
            r.automatic.topDp - 250f, r.placement.topDp, 0.01f
        )
        assertTrue("clamping must be reported", r.wasClamped)
    }

    /** The envelope must be sane where it should be. */
    @Test
    fun `travel envelope is sensible`() {
        val profile = emulatorProfile()
        val envelope = IslandPlacement.TravelEnvelope.forAutomaticPosition(
            screenWidthDp = profile.widthDp,
            screenHeightDp = profile.heightDp,
            islandWidthDp = 130f,
            islandHeightDp = 38f,
            automaticTopDp = 35f,          // status bar 24 + canonical 11
            automaticCenterXDp = profile.widthDp / 2f,
            navigationBarDp = 24f
        )

        // 411.43 dp wide, 130 dp island, 8 dp margin each side:
        // (411.43/2) - 65 - 8 = 132.71 dp of travel each way.
        assertEquals("horizontal travel", 132.71f, envelope.horizontalDp, 0.5f)
        assertEquals(
            "upward travel is the product-required 250 dp budget",
            250f, envelope.upDp, 0.01f
        )
        assertTrue("downward travel must be available", envelope.downDp > 0f)
        assertTrue(
            "the envelope must not let the island leave the screen",
            envelope.horizontalDp < profile.widthDp / 2f
        )
    }

    /**
     * **Regression: the envelope must be measured from the automatic position,
     * not the screen top.**
     *
     * Measuring downward travel from `screenHeight - navBar` double-counts the
     * status-bar clearance the automatic position already contains. On the
     * `island_test` AVD that produced an island whose bottom landed 27 dp past
     * the navigation bar when dragged fully down.
     */
    @Test
    fun `envelope does not double count the status bar clearance`() {
        val profile = emulatorProfile()
        val statusBar = 24f
        val navBar = 24f
        val islandHeight = 38f

        val r = IslandPlacement.resolve(
            profile = profile, islandWidthDp = 130f, islandHeightDp = islandHeight,
            statusBarDp = statusBar, navigationBarDp = navBar,
            userOffset = UserOffset(offsetYDp = 100000f), density = density
        )

        val bottom = r.placement.topDp + islandHeight
        val navBarTop = profile.heightDp - navBar

        assertTrue(
            "island bottom ($bottom dp) must stay above the navigation bar " +
                "at $navBarTop dp, but the envelope allowed it ${
                    "%.2f".format(bottom - navBarTop)
                } dp past",
            bottom <= navBarTop + 0.01f
        )
    }

    /**
     * A wide presentation must still fit when the user has dragged the pill to
     * the edge — the second clamp exists for exactly this.
     */
    @Test
    fun `a wide presentation stays on screen after an edge drag`() {
        val profile = emulatorProfile()
        val wideCard = 370f   // the expanded card

        val r = IslandPlacement.resolve(
            profile = profile,
            islandWidthDp = wideCard,
            islandHeightDp = 160f,
            statusBarDp = 24f,
            navigationBarDp = 24f,
            userOffset = UserOffset(offsetXDp = 500f),   // drag hard right
            density = density
        )

        val left = r.placement.leftDp(wideCard)
        val right = left + wideCard

        assertTrue("wide card left edge ($left dp) on screen", left >= -0.01f)
        assertTrue(
            "wide card right edge ($right dp) within ${profile.widthDp} dp",
            right <= profile.widthDp + 0.01f
        )
    }

    /**
     * If the island is wider than the screen permits, it must centre rather than
     * clamp to a nonsensical position.
     */
    @Test
    fun `an oversized island centres instead of clamping`() {
        val narrow = DeviceGeometryProfile(
            densityDpi = 420, xdpi = 420f, ydpi = 420f,
            widthPx = 400, heightPx = 800,   // 152 dp wide screen
        )
        val huge = 300f

        val r = IslandPlacement.resolve(
            profile = narrow, islandWidthDp = huge, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(offsetXDp = 50f), density = density
        )

        assertEquals(
            "an island wider than the screen must be centred",
            narrow.widthDp / 2f, r.placement.centerXDp, 0.5f
        )
    }

    // =====================================================================
    // Explainability
    // =====================================================================

    /** The result must explain where the island is and whether it was moved. */
    @Test
    fun `placement result explains itself`() {
        val r = IslandPlacement.resolve(
            profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(offsetXDp = 30f, offsetYDp = 40f), density = density
        )

        assertEquals("requested offset is reported back", 30f, r.appliedOffset.offsetXDp, 0.01f)
        assertEquals(40f, r.appliedOffset.offsetYDp, 0.01f)
        assertTrue("automatic position is retained for reset", r.automatic.centerXDp > 0f)
        assertEquals(FallbackReason.OFF_CENTRE_CUTOUT, r.fallbackReason)
        assertTrue("envelope is reported for the UI to bound a drag", r.envelope.horizontalDp > 0f)
    }

    /** A partial clamp must report what was actually applied. */
    @Test
    fun `a clamped offset reports the applied value not the requested one`() {
        val r = IslandPlacement.resolve(
            profile = emulatorProfile(), islandWidthDp = 130f, islandHeightDp = 38f,
            statusBarDp = 24f, navigationBarDp = 24f,
            userOffset = UserOffset(offsetXDp = 100000f), density = density
        )

        assertTrue("must report clamping", r.wasClamped)
        assertTrue(
            "applied offset (${r.appliedOffset.offsetXDp}) must be far below the " +
                "requested 100000",
            r.appliedOffset.offsetXDp < 200f
        )
        assertTrue("applied offset must be positive", r.appliedOffset.offsetXDp > 0f)
    }

    // =====================================================================
    // Corner punch-holes and bounds hardening
    // =====================================================================

    /**
     * Real-world corner punch-holes (e.g. Galaxy S10, Pixel 5) have bezels and do not
     * touch pixel 0 (e.g. left = 40 px). They must still be recognized as CORNER_CUTOUT
     * and automatically clamped to respect edgeMarginDp.
     */
    @Test
    fun `corner punch-hole with bezel is classified as CORNER_CUTOUT and clamped to edge margin`() {
        val cornerProfile = DeviceGeometryProfile(
            densityDpi = 420,
            xdpi = 420f,
            ydpi = 420f,
            widthPx = 1080,
            heightPx = 2400,
            cutoutRectPx = PixelRect(40, 0, 120, 80), // left-corner punch-hole with 40px bezel
            cutoutCircleDiameterPx = 68f
        )
        val islandWidth = 130f
        val edgeMargin = 8f

        val (placement, reason) = IslandPlacement.resolveAutomatic(
            profile = cornerProfile,
            islandWidthDp = islandWidth,
            statusBarDp = 24f,
            density = DensityNormalizer.forProfile(cornerProfile),
            edgeMarginDp = edgeMargin
        )

        assertEquals("corner punch-hole must be classified as CORNER_CUTOUT", FallbackReason.CORNER_CUTOUT, reason)
        val left = placement.leftDp(islandWidth)
        assertEquals("automatic position must be clamped to edge margin", edgeMargin, left, 0.05f)
    }

    /**
     * Corner cutouts resting at edgeMarginDp must not produce a 0 dp horizontal travel envelope.
     * The envelope must use the wider room across the screen (at least 150 dp).
     */
    @Test
    fun `corner cutout receives wide horizontal travel envelope`() {
        val cornerProfile = DeviceGeometryProfile(
            densityDpi = 420,
            xdpi = 420f,
            ydpi = 420f,
            widthPx = 1080,
            heightPx = 2400,
            cutoutRectPx = PixelRect(30, 0, 110, 80),
            cutoutCircleDiameterPx = 68f
        )
        val islandWidth = 130f
        val edgeMargin = 8f

        val result = IslandPlacement.resolve(
            profile = cornerProfile,
            islandWidthDp = islandWidth,
            islandHeightDp = 38f,
            statusBarDp = 24f,
            navigationBarDp = 24f,
            userOffset = UserOffset(),
            density = DensityNormalizer.forProfile(cornerProfile),
            edgeMarginDp = edgeMargin
        )

        assertTrue(
            "envelope horizontal travel (${result.envelope.horizontalDp}) must be at least 150 dp for corner cutout",
            result.envelope.horizontalDp >= 150f
        )

        // Dragging fully right should move the island to the right edge margin without going off screen
        val rightDrag = IslandPlacement.resolve(
            profile = cornerProfile,
            islandWidthDp = islandWidth,
            islandHeightDp = 38f,
            statusBarDp = 24f,
            navigationBarDp = 24f,
            userOffset = UserOffset(offsetXDp = 1000f),
            density = DensityNormalizer.forProfile(cornerProfile),
            edgeMarginDp = edgeMargin
        )
        val rightEdge = rightDrag.placement.leftDp(islandWidth) + islandWidth
        assertEquals(
            "right edge must be clamped to screenWidth - edgeMargin",
            cornerProfile.widthDp - edgeMargin,
            rightEdge,
            0.05f
        )
    }

    /**
     * UserOffset with non-finite values (NaN or Infinity) must be sanitized and clamped safely
     * without producing NaN coordinates in placement.
     */
    @Test
    fun `non-finite user offset values are safely sanitized`() {
        val envelope = IslandPlacement.TravelEnvelope(100f, 50f, 200f)
        val nanOffset = UserOffset(offsetXDp = Float.NaN, offsetYDp = Float.POSITIVE_INFINITY)
        val clamped = nanOffset.clampedTo(envelope)

        assertEquals("NaN horizontal offset must be sanitized to 0", 0f, clamped.offsetXDp, 0.001f)
        assertEquals("Infinity vertical offset must be clamped to downDp", envelope.downDp, clamped.offsetYDp, 0.001f)

        val result = IslandPlacement.resolve(
            profile = emulatorProfile(),
            islandWidthDp = 130f,
            islandHeightDp = 38f,
            statusBarDp = 24f,
            navigationBarDp = 24f,
            userOffset = nanOffset,
            density = density
        )
        assertTrue("centerXDp must be finite", result.placement.centerXDp.isFinite())
        assertTrue("topDp must be finite", result.placement.topDp.isFinite())
    }

    /**
     * Custom edge margins must be strictly respected by both resolveAutomatic and applyOffset.
     */
    @Test
    fun `custom edge margin and navigation bar clearances are strictly enforced`() {
        val customMargin = 16f
        val navBar = 32f
        val islandHeight = 40f
        val profile = emulatorProfile()

        val r = IslandPlacement.resolve(
            profile = profile,
            islandWidthDp = 130f,
            islandHeightDp = islandHeight,
            statusBarDp = 24f,
            navigationBarDp = navBar,
            userOffset = UserOffset(offsetXDp = -10000f, offsetYDp = 10000f),
            density = density,
            edgeMarginDp = customMargin
        )

        val left = r.placement.leftDp(130f)
        assertEquals("left edge must clamp to customMargin", customMargin, left, 0.05f)

        val bottom = r.placement.topDp + islandHeight
        val expectedMaxBottom = profile.heightDp - navBar - customMargin
        assertEquals("bottom edge must clamp to screenHeight - navBar - customMargin", expectedMaxBottom, bottom, 0.05f)
    }
}
