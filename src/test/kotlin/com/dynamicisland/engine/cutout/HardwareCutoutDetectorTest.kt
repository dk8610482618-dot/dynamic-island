package com.dynamicisland.engine.cutout

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito

/**
 * Unit tests for cutout classification, multi-rect dual hole union, and
 * calibration geometry adjustments.
 */
class HardwareCutoutDetectorTest {

    private val screenWidth = 1080
    private val safeTop = 80
    private lateinit var mockRectScope: MockedConstruction<Rect>

    @Before
    fun setUp() {
        mockRectScope = Mockito.mockConstruction(Rect::class.java) { mock, context ->
            val args = context.arguments()
            if (args.size == 4) {
                mock.left = (args[0] as Number).toInt()
                mock.top = (args[1] as Number).toInt()
                mock.right = (args[2] as Number).toInt()
                mock.bottom = (args[3] as Number).toInt()
            }
            Mockito.`when`(mock.width()).thenAnswer { mock.right - mock.left }
            Mockito.`when`(mock.height()).thenAnswer { mock.bottom - mock.top }
            Mockito.`when`(mock.centerX()).thenAnswer { (mock.left + mock.right) / 2 }
            Mockito.`when`(mock.centerY()).thenAnswer { (mock.top + mock.bottom) / 2 }
        }
    }

    @After
    fun tearDown() {
        mockRectScope.close()
    }

    @Test
    fun `classifies centered single hole as CENTER_PUNCH_HOLE`() {
        // 60x60 camera centered at x=540 (left=510, right=570)
        val rect = Rect(510, 20, 570, 80)
        val type = HardwareCutoutDetector.classifyCutout(
            topRect = rect,
            allRects = listOf(rect),
            screenWidth = screenWidth,
            safeTop = safeTop
        )
        assertEquals(CutoutType.CENTER_PUNCH_HOLE, type)
    }

    @Test
    fun `classifies left-aligned hole as CORNER_PUNCH_HOLE_LEFT`() {
        val rect = Rect(40, 20, 100, 80)
        val type = HardwareCutoutDetector.classifyCutout(
            topRect = rect,
            allRects = listOf(rect),
            screenWidth = screenWidth,
            safeTop = safeTop
        )
        assertEquals(CutoutType.CORNER_PUNCH_HOLE_LEFT, type)
    }

    @Test
    fun `classifies right-aligned hole as CORNER_PUNCH_HOLE_RIGHT`() {
        val rect = Rect(980, 20, 1040, 80)
        val type = HardwareCutoutDetector.classifyCutout(
            topRect = rect,
            allRects = listOf(rect),
            screenWidth = screenWidth,
            safeTop = safeTop
        )
        assertEquals(CutoutType.CORNER_PUNCH_HOLE_RIGHT, type)
    }

    @Test
    fun `classifies multiple centered apertures as DUAL_PILL`() {
        // Two camera sensors near the center:
        // Sensor 1: 460..510 (center 485)
        // Sensor 2: 570..620 (center 595)
        // Combined union: 460..620 (center 540)
        val r1 = Rect(460, 20, 510, 80)
        val r2 = Rect(570, 20, 620, 80)
        val unionRect = Rect(460, 20, 620, 80)

        val type = HardwareCutoutDetector.classifyCutout(
            topRect = unionRect,
            allRects = listOf(r1, r2),
            screenWidth = screenWidth,
            safeTop = safeTop
        )
        assertEquals(CutoutType.DUAL_PILL, type)
    }

    @Test
    fun `classifies single wide centered cutout as DUAL_PILL`() {
        // Pill aperture with width > 2.2 * height
        // width = 160, height = 50 -> 160 > 110
        val pillRect = Rect(460, 20, 620, 70)
        val type = HardwareCutoutDetector.classifyCutout(
            topRect = pillRect,
            allRects = listOf(pillRect),
            screenWidth = screenWidth,
            safeTop = safeTop
        )
        assertEquals(CutoutType.DUAL_PILL, type)
    }

    @Test
    fun `classifies teardrop notch as WATERDROP_NOTCH`() {
        // Touches top (top=0), height >= safeTop * 0.8 (70 >= 64),
        // width < 0.25 * screenWidth (90 < 270), aspect ratio < 1.8 (90/70 = 1.28)
        val notchRect = Rect(495, 0, 585, 70)
        val type = HardwareCutoutDetector.classifyCutout(
            topRect = notchRect,
            allRects = listOf(notchRect),
            screenWidth = screenWidth,
            safeTop = safeTop
        )
        assertEquals(CutoutType.WATERDROP_NOTCH, type)
    }

    @Test
    fun `calibration adjustments correctly expand bounds and apply offsets`() {
        val baseRect = Rect(500, 20, 580, 80) // 80 width, 60 height
        val config = CalibrationConfig(
            offsetX = 10,
            offsetY = -5,
            widthPadding = 20,
            heightPadding = 15,
            cornerRadiusOverride = 24f
        )

        val calibrated = Rect(
            baseRect.left + config.offsetX - (config.widthPadding / 2),
            baseRect.top + config.offsetY,
            baseRect.right + config.offsetX + (config.widthPadding / 2),
            baseRect.bottom + config.offsetY + config.heightPadding
        )

        assertEquals(500 + 10 - 10, calibrated.left) // 500
        assertEquals(20 - 5, calibrated.top)         // 15
        assertEquals(580 + 10 + 10, calibrated.right)// 600
        assertEquals(80 - 5 + 15, calibrated.bottom) // 90

        assertEquals(100, calibrated.width())
        assertEquals(75, calibrated.height())
        assertEquals(550, calibrated.centerX())
        assertEquals(52, calibrated.centerY())
    }

    @Test
    fun `cutout geometry default parameters preserve backward compatibility`() {
        val geo = CutoutGeometry(
            type = CutoutType.CENTER_PUNCH_HOLE,
            hardwareBounds = Rect(500, 0, 580, 80),
            centerPoint = android.graphics.Point(540, 40),
            safeInsetTop = 80,
            recommendedCompactWidth = 300,
            recommendedCompactHeight = 100,
            recommendedCornerRadius = 50f
        )

        assertEquals(0, geo.safeInsetLeft)
        assertEquals(0, geo.safeInsetRight)
        assertEquals(0, geo.safeInsetBottom)
        assertFalse(geo.isLandscape)
    }
}
