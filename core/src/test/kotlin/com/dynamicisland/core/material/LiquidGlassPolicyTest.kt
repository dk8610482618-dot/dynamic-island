package com.dynamicisland.core.material

import com.dynamicisland.core.material.LiquidGlassPolicy.GlassSurface
import com.dynamicisland.core.material.LiquidGlassPolicy.SurfaceTreatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap **G5**: Liquid Glass as a real material with anti-stacking, plus
 * doc 2's measurable accessibility contract (8-point grid, 4.5:1 / 3:1 contrast,
 * Dynamic Type bounds).
 */
class LiquidGlassPolicyTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val midGrey = 0xFF808080.toInt()

    // ------------------------------------------------- anti-stacking

    @Test
    fun `a control inside a glass card is demoted so glass never stacks`() {
        val resolved = LiquidGlassPolicy.resolveSurfaces(
            chain = listOf(GlassSurface.CARD, GlassSurface.CONTROL),
            appearanceIsLiquidGlass = true
        )

        assertEquals(SurfaceTreatment.GLASS, resolved[0].treatment)
        assertEquals(SurfaceTreatment.SOLID_TINT, resolved[1].treatment)
        assertTrue(resolved[1].demotedToAvoidStacking)
        assertEquals(0f, resolved[1].blurRadiusDp, 0.0001f)
        assertTrue(
            "Exactly one layer in the chain may blur",
            resolved.count { it.treatment == SurfaceTreatment.GLASS } == 1
        )
    }

    @Test
    fun `a pre-blurred ancestor demotes the card itself`() {
        val resolved = LiquidGlassPolicy.resolveSurfaces(
            chain = listOf(GlassSurface.CARD, GlassSurface.CONTROL),
            appearanceIsLiquidGlass = true,
            parentIsGlass = true
        )
        assertTrue(resolved.none { it.treatment == SurfaceTreatment.GLASS })
        assertTrue(resolved.all { it.demotedToAvoidStacking })
    }

    @Test
    fun `glass disappears entirely under reduce transparency or low power`() {
        listOf(
            LiquidGlassPolicy.resolveSurfaces(listOf(GlassSurface.CARD), true, reduceTransparency = true),
            LiquidGlassPolicy.resolveSurfaces(listOf(GlassSurface.CARD), true, lowPowerMode = true)
        ).forEach { resolved ->
            assertEquals(SurfaceTreatment.NONE, resolved[0].treatment)
            assertEquals(0f, resolved[0].blurRadiusDp, 0.0001f)
            assertFalse(resolved[0].demotedToAvoidStacking)
        }
    }

    @Test
    fun `the material is opt-in through the appearance preset`() {
        // This is what the imported base got wrong: LIQUID_GLASS was a single
        // alpha swap. Anything else must produce no glass at all.
        val resolved = LiquidGlassPolicy.resolveSurfaces(
            chain = listOf(GlassSurface.CARD, GlassSurface.CONTROL),
            appearanceIsLiquidGlass = false
        )
        assertTrue(resolved.all { it.treatment == SurfaceTreatment.NONE })
    }

    @Test
    fun `a demoted control keeps a lighter tint so it still reads as raised`() {
        val card = LiquidGlassPolicy.tintAlphaFor(GlassSurface.CARD, SurfaceTreatment.SOLID_TINT)
        val control = LiquidGlassPolicy.tintAlphaFor(GlassSurface.CONTROL, SurfaceTreatment.SOLID_TINT)
        assertTrue("card=$card control=$control", card > control)
    }

    // ------------------------------------------------------------ WCAG

    @Test
    fun `contrast ratio spans one to twenty one`() {
        assertEquals(21.0, LiquidGlassPolicy.contrastRatio(white, black), 0.01)
        assertEquals(21.0, LiquidGlassPolicy.contrastRatio(black, white), 0.01)
        assertEquals(1.0, LiquidGlassPolicy.contrastRatio(black, black), 0.01)
    }

    @Test
    fun `relative luminance matches the published srgb values`() {
        assertEquals(0.0, LiquidGlassPolicy.relativeLuminance(black), 1e-6)
        assertEquals(1.0, LiquidGlassPolicy.relativeLuminance(white), 1e-6)
        // Mid grey linearises to ~0.2159, not 0.5 — the point of the transfer fn.
        assertEquals(0.2159, LiquidGlassPolicy.relativeLuminance(midGrey), 0.001)
    }

    @Test
    fun `the large text exception uses three to one`() {
        // Mid grey on white is ~3.95:1 — fails body text, passes large text.
        val ratio = LiquidGlassPolicy.contrastRatio(midGrey, white)
        assertTrue(ratio < LiquidGlassPolicy.MIN_CONTRAST_BODY)
        assertTrue(ratio >= LiquidGlassPolicy.MIN_CONTRAST_LARGE)

        assertFalse(LiquidGlassPolicy.meetsContrast(midGrey, white, textSizeSp = 12f))
        assertTrue(LiquidGlassPolicy.meetsContrast(midGrey, white, textSizeSp = 20f))
        assertTrue(LiquidGlassPolicy.meetsContrast(midGrey, white, textSizeSp = 14f, isBoldOrHeavier = true))
        assertFalse(LiquidGlassPolicy.meetsContrast(midGrey, white, textSizeSp = 13f, isBoldOrHeavier = true))
    }

    @Test
    fun `alpha is ignored when measuring luminance but honoured when compositing`() {
        val translucentWhite = 0x66FFFFFF
        // A 40% white tint measured raw would look like white and pass. Composited
        // over the black island it is far darker — the failure we must catch.
        val composited = LiquidGlassPolicy.compositeOver(translucentWhite, black)
        assertTrue(
            "raw=${LiquidGlassPolicy.contrastRatio(translucentWhite, white)} " +
                "composited=${LiquidGlassPolicy.contrastRatio(composited, white)}",
            LiquidGlassPolicy.contrastRatio(composited, white) >
                LiquidGlassPolicy.contrastRatio(black, white) - 0.001
        )
        assertFalse(LiquidGlassPolicy.meetsContrast(black, black, textSizeSp = 12f))
    }

    @Test
    fun `ensureContrast lifts a failing tint until it passes and leaves a passing one alone`() {
        val failing = 0xFF202020.toInt() // near-black on the black island
        val fixed = LiquidGlassPolicy.ensureContrast(failing, black, textSizeSp = 12f)
        assertTrue(
            LiquidGlassPolicy.contrastRatio(fixed, black) >= LiquidGlassPolicy.MIN_CONTRAST_BODY
        )

        val alreadyFine = white
        assertEquals(alreadyFine, LiquidGlassPolicy.ensureContrast(alreadyFine, black, textSizeSp = 12f))
    }

    // ------------------------------------------------------------- grid

    @Test
    fun `grid snapping keeps zero reachable for the cutout padding rule`() {
        assertEquals(0f, LiquidGlassPolicy.snapToGrid(0f), 0.0001f)
        assertEquals(0f, LiquidGlassPolicy.snapToGrid(3.9f), 0.0001f)
        assertEquals(8f, LiquidGlassPolicy.snapToGrid(4.1f), 0.0001f)
        assertEquals(16f, LiquidGlassPolicy.snapToGrid(13f), 0.0001f)
        assertEquals(LiquidGlassPolicy.GRID_DP, LiquidGlassPolicy.snapToGrid(8f), 0.0001f)
        // A non-positive grid must pass values through, not divide by zero.
        assertEquals(13f, LiquidGlassPolicy.snapToGrid(13f, 0f), 0.0001f)
    }

    @Test
    fun `dynamic type scales but is bounded`() {
        assertEquals(28f, LiquidGlassPolicy.scaleForDynamicType(14f, 2f), 0.0001f)
        // A 3.0x accessibility scale must not push past the cap.
        assertEquals(34f, LiquidGlassPolicy.scaleForDynamicType(14f, 3f), 0.0001f)
        assertEquals(34f, LiquidGlassPolicy.scaleForDynamicType(14f, 50f), 0.0001f)
        // Sub-0.85 scales are clamped so text cannot become unreadable.
        assertEquals(14f * 0.85f, LiquidGlassPolicy.scaleForDynamicType(14f, 0.2f), 0.0001f)
    }

    @Test
    fun `expanded height grows for large text but never past the 160dp cap`() {
        assertEquals(120f, LiquidGlassPolicy.expandedHeightDp(120f, 1f), 0.0001f)
        assertEquals(156f, LiquidGlassPolicy.expandedHeightDp(120f, 1.3f), 0.0001f)
        assertEquals(160f, LiquidGlassPolicy.expandedHeightDp(120f, 2f), 0.0001f)
        // A scale below 1 must not shrink the card.
        assertEquals(120f, LiquidGlassPolicy.expandedHeightDp(120f, 0.8f), 0.0001f)
    }
}
