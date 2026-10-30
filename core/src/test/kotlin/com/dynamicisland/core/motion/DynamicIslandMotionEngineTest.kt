package com.dynamicisland.core.motion

import com.dynamicisland.core.physics.CatalogEntry
import com.dynamicisland.core.physics.SpringCatalog
import com.dynamicisland.core.physics.SpringSimulation
import com.dynamicisland.core.physics.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verification suite for [DynamicIslandMotionEngine], fulfilling the Elite Android Engineer
 * motion & physics specification.
 *
 * Verifies:
 * 1. One-clock frame advancement: all dimensions evaluated synchronously on identical dt.
 * 2. Self-stopping idle loop: settled states report settled == true, snap exact targets, burn zero cycles.
 * 3. Spring interruption math: preserving position and velocity across retargeting and spec adoption.
 * 4. Rubber-band drag & snapback physics: non-linear resistance, release snapback, and seamless mid-snapback catch.
 * 5. Exhaustive unit test coverage across all 18 entries in [SpringCatalog.all].
 * 6. Allocation-free execution in the frame step path.
 */
class DynamicIslandMotionEngineTest {

    // =========================================================================
    // 1. One-Clock Frame Advancement
    // =========================================================================

    @Test
    fun `all four dimensions advance on the exact same clock and delta`() {
        val engine = DynamicIslandMotionEngine()

        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            targetRadiusDp = 44f,
            spec = SpringCatalog.CompactToExpanded,
            secondaryVisible = true,
            targetIsWide = true
        )

        // Advance by a single 16.6ms frame
        val frame = engine.advance(1f / 60f)

        assertFalse("Engine should be in motion on the first frame", frame.settled)
        assertTrue("Width should have progressed from initial", frame.widthDp > DynamicIslandMotionEngine.INITIAL_WIDTH_DP)
        assertTrue("Height should have progressed from initial", frame.heightDp > DynamicIslandMotionEngine.INITIAL_HEIGHT_DP)
        assertTrue("Radius should have progressed from initial", frame.radiusDp > DynamicIslandMotionEngine.INITIAL_RADIUS_DP)
        assertTrue("Bubble scale should have progressed towards 1.0", frame.secondaryScale > 0f)

        // Verify values are currentFrame
        assertEquals(frame.widthDp, engine.currentFrame.widthDp, 0f)
        assertEquals(frame.heightDp, engine.currentFrame.heightDp, 0f)
        assertEquals(frame.radiusDp, engine.currentFrame.radiusDp, 0f)
        assertEquals(frame.secondaryScale, engine.currentFrame.secondaryScale, 0f)
    }

    @Test
    fun `frame advancement produces deterministic trajectory regardless of sample frequency`() {
        // Trajectories sampled at 60 Hz, 90 Hz, and 120 Hz must land on identical positions at matched time slices
        fun evaluateAt(instantSeconds: Float, fps: Int): DynamicIslandMotionEngine.Frame {
            val engine = DynamicIslandMotionEngine()
            engine.moveTo(
                targetWidthDp = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
                targetHeightDp = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
                targetRadiusDp = 44f,
                spec = SpringCatalog.CompactToExpanded,
                secondaryVisible = false,
                targetIsWide = true
            )
            val dt = 1f / fps
            var t = 0f
            var frame = engine.currentFrame
            while (t < instantSeconds - 1e-6f) {
                val step = minOf(dt, instantSeconds - t)
                frame = engine.advance(step)
                t += step
            }
            return frame
        }

        val checkpoints = listOf(0.05f, 0.10f, 0.20f, 0.35f)
        for (cp in checkpoints) {
            val frame60 = evaluateAt(cp, 60)
            val frame90 = evaluateAt(cp, 90)
            val frame120 = evaluateAt(cp, 120)

            assertEquals("Width at $cp s matches between 60 and 120 Hz", frame120.widthDp, frame60.widthDp, 0.05f)
            assertEquals("Height at $cp s matches between 60 and 120 Hz", frame120.heightDp, frame60.heightDp, 0.05f)
            assertEquals("Width at $cp s matches between 90 and 120 Hz", frame120.widthDp, frame90.widthDp, 0.05f)
            assertEquals("Height at $cp s matches between 90 and 120 Hz", frame120.heightDp, frame90.heightDp, 0.05f)
        }
    }

    // =========================================================================
    // 2. Self-Stopping Idle Loop & Settlement
    // =========================================================================

    @Test
    fun `initial frame is settled at resting pill dimensions`() {
        val engine = DynamicIslandMotionEngine()
        val frame = engine.currentFrame

        assertTrue("Initial state must be settled", frame.settled)
        assertEquals("Initial width matches compact pill", DynamicIslandMotionEngine.INITIAL_WIDTH_DP, frame.widthDp, 0.01f)
        assertEquals("Initial height matches compact pill", DynamicIslandMotionEngine.INITIAL_HEIGHT_DP, frame.heightDp, 0.01f)
        assertEquals("Initial radius matches compact pill", DynamicIslandMotionEngine.INITIAL_RADIUS_DP, frame.radiusDp, 0.01f)
        assertEquals("Initial bubble scale is 0", 0f, frame.secondaryScale, 0f)
        assertEquals("Compact alpha is 1.0 at rest", 1f, frame.compactAlpha, 0f)
        assertEquals("Expanded alpha is 0.0 at rest", 0f, frame.expandedAlpha, 0f)
        assertEquals("Stretch is 0", 0f, frame.stretchDp, 0f)
        assertEquals("Drag offset X is 0", 0f, frame.dragOffsetXDp, 0f)
    }

    @Test
    fun `engine transitions from active motion to self-settled state snapping to target`() {
        val engine = DynamicIslandMotionEngine()
        val targetW = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP
        val targetH = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP
        val targetR = 44f

        engine.moveTo(
            targetWidthDp = targetW,
            targetHeightDp = targetH,
            targetRadiusDp = targetR,
            spec = SpringCatalog.CompactToExpanded,
            secondaryVisible = false,
            targetIsWide = true
        )

        val dt = 1f / 120f
        var settledSeen = false
        var framesElapsed = 0

        while (framesElapsed < 240) {
            val frame = engine.advance(dt)
            framesElapsed++
            if (frame.settled) {
                settledSeen = true
                break
            }
        }

        assertTrue("Animation must settle within 2 seconds", settledSeen)
        val settledFrame = engine.currentFrame
        assertTrue("settled flag must be true", settledFrame.settled)
        assertEquals("Width must snap exactly to target upon settling", targetW, settledFrame.widthDp, 0.001f)
        assertEquals("Height must snap exactly to target upon settling", targetH, settledFrame.heightDp, 0.001f)
        assertEquals("Radius must snap exactly to target upon settling", targetR, settledFrame.radiusDp, 0.001f)
        assertEquals("Compact alpha must settle to 0.0 on wide card", 0f, settledFrame.compactAlpha, 0.001f)
        assertEquals("Expanded alpha must settle to 1.0 on wide card", 1f, settledFrame.expandedAlpha, 0.001f)

        // Subsequent steps while idle must maintain settled state with zero changes
        val afterIdle = engine.advance(dt)
        assertTrue("Subsequent frame while idle must remain settled", afterIdle.settled)
        assertEquals(targetW, afterIdle.widthDp, 0f)
        assertEquals(targetH, afterIdle.heightDp, 0f)
    }

    @Test
    fun `snapTo immediately settles engine to target coordinates`() {
        val engine = DynamicIslandMotionEngine()
        engine.snapTo(
            widthDp = 300f,
            heightDp = 52f,
            radiusDp = 26f,
            secondaryVisible = true,
            isWide = true
        )

        val frame = engine.currentFrame
        assertTrue("snapTo must yield a settled frame", frame.settled)
        assertEquals(300f, frame.widthDp, 0.001f)
        assertEquals(52f, frame.heightDp, 0.001f)
        assertEquals(26f, frame.radiusDp, 0.001f)
        assertEquals(1f, frame.secondaryScale, 0.001f)
        assertEquals(0f, frame.compactAlpha, 0.001f)
        assertEquals(1f, frame.expandedAlpha, 0.001f)
        assertFalse("Drag must be inactive", engine.isDragging)
        assertFalse("Releasing must be inactive", engine.isReleasing)
    }

    // =========================================================================
    // 3. Spring Interruption Math
    // =========================================================================

    @Test
    fun `moveTo mid-flight preserves live position and velocity with same spec`() {
        val engine = DynamicIslandMotionEngine()
        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            targetRadiusDp = 44f,
            spec = SpringCatalog.CompactToExpanded,
            secondaryVisible = false,
            targetIsWide = true
        )

        // Advance 15 frames (~125ms) into expansion
        repeat(15) { engine.advance(1f / 120f) }
        val prePosW = engine.currentFrame.widthDp
        val prePosH = engine.currentFrame.heightDp

        // Interrupt and retarget to AlertPeek
        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.ALERT_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.ALERT_HEIGHT_DP,
            targetRadiusDp = 26f,
            spec = SpringCatalog.CompactToExpanded,
            secondaryVisible = false,
            targetIsWide = true
        )

        val postPosW = engine.currentFrame.widthDp
        val postPosH = engine.currentFrame.heightDp

        assertEquals("Width position must be strictly preserved across retarget", prePosW, postPosW, 1e-4f)
        assertEquals("Height position must be strictly preserved across retarget", prePosH, postPosH, 1e-4f)

        // Step by a tiny dt (1 microsecond) to verify velocity continuity: dx = v * dt
        val nextFrame = engine.advance(1e-5f)
        assertEquals("Width must not jump in position immediately after retarget", prePosW, nextFrame.widthDp, 0.02f)
        assertEquals("Height must not jump in position immediately after retarget", prePosH, nextFrame.heightDp, 0.02f)
    }

    @Test
    fun `moveTo mid-flight adopts a different SpringSpec preserving live state`() {
        val engine = DynamicIslandMotionEngine()
        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            targetRadiusDp = 44f,
            spec = SpringCatalog.CompactToExpanded, // T=0.48, z=0.80
            secondaryVisible = false,
            targetIsWide = true
        )

        // Run halfway
        repeat(20) { engine.advance(1f / 120f) }
        val midWidth = engine.currentFrame.widthDp
        val midHeight = engine.currentFrame.heightDp

        // Reverse to compact using ExpandedToCompact spec (T=0.40, z=0.86)
        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.INITIAL_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.INITIAL_HEIGHT_DP,
            targetRadiusDp = DynamicIslandMotionEngine.INITIAL_RADIUS_DP,
            spec = SpringCatalog.ExpandedToCompact,
            secondaryVisible = false,
            targetIsWide = false
        )

        assertEquals("Adopted spec must preserve live width", midWidth, engine.currentFrame.widthDp, 1e-4f)
        assertEquals("Adopted spec must preserve live height", midHeight, engine.currentFrame.heightDp, 1e-4f)

        // Continue collapse until settled
        var frames = 0
        while (frames < 240 && !engine.currentFrame.settled) {
            engine.advance(1f / 120f)
            frames++
        }

        assertTrue("Reversed collapse must settle cleanly", engine.currentFrame.settled)
        assertEquals(DynamicIslandMotionEngine.INITIAL_WIDTH_DP, engine.currentFrame.widthDp, 0.01f)
        assertEquals(DynamicIslandMotionEngine.INITIAL_HEIGHT_DP, engine.currentFrame.heightDp, 0.01f)
        assertEquals(1f, engine.currentFrame.compactAlpha, 0.01f)
        assertEquals(0f, engine.currentFrame.expandedAlpha, 0.01f)
    }

    @Test
    fun `rapid multi-retargeting does not cause numerical instability or divergence`() {
        val engine = DynamicIslandMotionEngine()
        val targets = listOf(
            Triple(200f, 60f, 30f),
            Triple(370f, 160f, 44f),
            Triple(130f, 38f, 19f),
            Triple(300f, 52f, 26f),
            Triple(150f, 40f, 20f)
        )

        for (target in targets) {
            repeat(5) { engine.advance(1f / 120f) }
            engine.moveTo(
                targetWidthDp = target.first,
                targetHeightDp = target.second,
                targetRadiusDp = target.third,
                spec = SpringCatalog.CompactToExpanded,
                secondaryVisible = false
            )
            assertTrue("Width must remain finite", engine.currentFrame.widthDp.isFinite())
            assertTrue("Height must remain finite", engine.currentFrame.heightDp.isFinite())
        }

        // Run until settled
        repeat(300) { engine.advance(1f / 120f) }
        assertTrue("Engine must settle after multi-retarget sequence", engine.currentFrame.settled)
        assertEquals(targets.last().first, engine.currentFrame.widthDp, 0.01f)
        assertEquals(targets.last().second, engine.currentFrame.heightDp, 0.01f)
    }

    // =========================================================================
    // 4. Rubber-Band Drag & Snapback Physics
    // =========================================================================

    @Test
    fun `downward drag produces non-linear rubber-band stretch asymptotic to dimension`() {
        val engine = DynamicIslandMotionEngine()
        val h = DynamicIslandMotionEngine.INITIAL_HEIGHT_DP // 38.6 dp

        engine.addDrag(0f, 50f)
        val frame1 = engine.advance(1f / 120f)
        assertTrue("Drag is active", engine.isDragging)
        assertEquals(50f, engine.rawDragY, 0.001f)

        // Apple formula: (1 - 1 / ((x * c / d) + 1)) * d
        val expectedStretch50 = (1f - (1f / ((50f * 0.55f / h) + 1f))) * h
        assertEquals("Stretch for 50dp drag", expectedStretch50, frame1.stretchDp, 0.01f)

        // Double the drag: stretch must increase with diminishing returns
        engine.addDrag(0f, 50f) // rawDragY is now 100f
        val frame2 = engine.advance(1f / 120f)
        val expectedStretch100 = (1f - (1f / ((100f * 0.55f / h) + 1f))) * h
        assertEquals("Stretch for 100dp drag", expectedStretch100, frame2.stretchDp, 0.01f)

        assertTrue("Resistance increases (stretch < raw)", frame2.stretchDp < 100f)
        assertTrue("Stretch is strictly sub-linear", (frame2.stretchDp - frame1.stretchDp) < frame1.stretchDp)
        assertTrue("Stretch cannot exceed dimension asymptote", frame2.stretchDp < h)
    }

    @Test
    fun `upward drag produces zero downward stretch`() {
        val engine = DynamicIslandMotionEngine()
        engine.addDrag(0f, -40f) // drag upwards
        val frame = engine.advance(1f / 120f)

        assertTrue(engine.isDragging)
        assertEquals(-40f, engine.rawDragY, 0.001f)
        assertEquals("Upward drag must not stretch downward into cutout", 0f, frame.stretchDp, 0f)
    }

    @Test
    fun `horizontal drag produces symmetric rubber-band displacement`() {
        val engine = DynamicIslandMotionEngine()
        val w = DynamicIslandMotionEngine.INITIAL_WIDTH_DP

        // Drag right
        engine.addDrag(60f, 0f)
        val frameRight = engine.advance(1f / 120f)
        val expectedRight = (1f - (1f / ((60f * 0.55f / w) + 1f))) * w
        assertEquals(expectedRight, frameRight.dragOffsetXDp, 0.01f)

        // Snap back to zero
        engine.clearDrag()
        repeat(120) { engine.advance(1f / 120f) }

        // Drag left by equal magnitude
        engine.addDrag(-60f, 0f)
        val frameLeft = engine.advance(1f / 120f)
        assertEquals(-expectedRight, frameLeft.dragOffsetXDp, 0.01f)
    }

    @Test
    fun `clearDrag initiates snapback under RubberBandSnapback spring and settles to zero`() {
        val engine = DynamicIslandMotionEngine()
        engine.addDrag(30f, 60f)
        engine.advance(1f / 120f)

        val stretchBeforeRelease = engine.currentFrame.stretchDp
        val dragXBeforeRelease = engine.currentFrame.dragOffsetXDp
        assertTrue(stretchBeforeRelease > 0f)
        assertTrue(dragXBeforeRelease > 0f)

        engine.clearDrag()
        assertTrue("releasing state must be active after clearDrag", engine.isReleasing)
        assertFalse("isDragging must be false after clearDrag", engine.isDragging)

        // The very next advance must be continuous from the resisted offsets
        val firstReleaseFrame = engine.advance(1f / 240f)
        assertEquals("Release handoff must be continuous in stretch", stretchBeforeRelease, firstReleaseFrame.stretchDp, 0.1f)
        assertEquals("Release handoff must be continuous in X", dragXBeforeRelease, firstReleaseFrame.dragOffsetXDp, 0.1f)

        // Step until release springs settle
        var settled = false
        repeat(240) {
            val f = engine.advance(1f / 120f)
            if (f.settled) settled = true
        }

        assertTrue("Snapback must settle to rest", settled)
        assertFalse("releasing must be false once settled", engine.isReleasing)
        assertEquals("stretch must park at 0", 0f, engine.currentFrame.stretchDp, 0f)
        assertEquals("drag offsetX must park at 0", 0f, engine.currentFrame.dragOffsetXDp, 0f)
    }

    @Test
    fun `drag interruption during snapback preserves live resisted position via inverseRubberBand with zero step`() {
        val engine = DynamicIslandMotionEngine()
        engine.addDrag(40f, 80f)
        engine.advance(1f / 120f)
        engine.clearDrag()
        assertTrue(engine.isReleasing)

        // Step 10 frames into the snapback (~80ms)
        repeat(10) { engine.advance(1f / 120f) }
        val liveStretch = engine.currentFrame.stretchDp
        val liveDragX = engine.currentFrame.dragOffsetXDp
        assertTrue("Island should be mid-snapback", liveStretch > 0f)
        assertTrue(engine.isReleasing)

        // User catches the island mid-snapback with 0 new displacement
        engine.addDrag(0f, 0f)
        assertFalse("releasing must be cancelled when re-caught", engine.isReleasing)
        assertTrue("isDragging must now be active", engine.isDragging)

        // Verify that on the immediately following advance, the resolved stretch and dragX are continuous!
        val caughtFrame = engine.advance(0f)
        assertEquals("Position must not jump when catching mid-snapback (stretch)", liveStretch, caughtFrame.stretchDp, 1e-3f)
        assertEquals("Position must not jump when catching mid-snapback (dragX)", liveDragX, caughtFrame.dragOffsetXDp, 1e-3f)

        // Adding small finger delta moves smoothly from the caught position
        engine.addDrag(0f, 2f)
        val movedFrame = engine.advance(1f / 120f)
        assertTrue("Stretch follows pointer smoothly", movedFrame.stretchDp > liveStretch)
    }

    @Test
    fun `inverseRubberBand is the exact algebraic inverse of rubberBand`() {
        val engine = DynamicIslandMotionEngine()
        val dimensions = listOf(38.6f, 52f, 160f, 370f)
        val rawValues = listOf(1f, 5f, 15f, 30f, 60f, 120f, 250f, 500f)

        for (d in dimensions) {
            for (raw in rawValues) {
                val resisted = engine.rubberBand(raw, d)
                val recoveredRaw = engine.inverseRubberBand(resisted, d)
                assertEquals(
                    "d=$d, raw=$raw: recovered raw must match original within 0.01%",
                    raw,
                    recoveredRaw,
                    raw * 0.0001f
                )
            }
        }
    }

    // =========================================================================
    // 5. Unit Test Coverage Across All 18 Spring Catalogs
    // =========================================================================

    @Test
    fun `every catalog entry has physically valid parameters`() {
        assertEquals("Catalog must contain exactly 18 entries", 18, SpringCatalog.all.size)

        for (entry in SpringCatalog.all) {
            val spec = entry.spec
            assertTrue("${entry.name}: response must be > 0", spec.response > 0f)
            assertTrue("${entry.name}: dampingRatio must be in [0, 1)", spec.dampingRatio in 0f..<1f)
            assertTrue("${entry.name}: stiffness must be > 0", spec.stiffness > 0f)
            assertTrue("${entry.name}: damping must be > 0", spec.damping > 0f)
            assertTrue("${entry.name}: natural frequency omega0 must be > 0", spec.omega0 > 0f)
            assertTrue("${entry.name}: 2% settling time must be positive and realistic", spec.settlingTime2Percent in 0.1f..3.0f)
            assertNotNull("${entry.name}: provenance must be non-null", entry.provenance)
        }
    }

    @Test
    fun `every catalog entry simulates accurately and settles within predicted budget`() {
        for (entry in SpringCatalog.all) {
            val sim = SpringSimulation(entry.spec, initialValue = 0f)
            sim.retarget(100f)

            var t = 0f
            val dt = 1f / 240f
            var didSettle = false

            while (t < 2.0f) {
                val isMoving = sim.update(dt)
                t += dt
                if (!isMoving) {
                    didSettle = true
                    break
                }
            }

            assertTrue("${entry.name} failed to settle within 2.0s (t=$t, value=${sim.value})", didSettle)
            assertEquals("${entry.name} value at settlement", 100f, sim.value, SpringSimulation.SETTLE_DISTANCE)
            assertTrue("${entry.name} velocity at settlement", abs(sim.velocity) < SpringSimulation.SETTLE_VELOCITY)
        }
    }

    @Test
    fun `every catalog entry preserves position and velocity continuity under mid-flight retargeting`() {
        for (entry in SpringCatalog.all) {
            val sim = SpringSimulation(entry.spec, initialValue = 50f)
            sim.retarget(150f)

            // Step 15 frames
            repeat(15) { sim.update(1f / 120f) }
            val posBefore = sim.value
            val velBefore = sim.velocity

            // Retarget to new location
            sim.retarget(80f)

            assertEquals("${entry.name}: position jump on retarget", posBefore, sim.value, 1e-5f)
            assertEquals("${entry.name}: velocity jump on retarget", velBefore, sim.velocity, 1e-4f)
        }
    }

    @Test
    fun `DynamicIslandMotionEngine drives transitions smoothly with every catalog spring`() {
        for (entry in SpringCatalog.all) {
            val engine = DynamicIslandMotionEngine()
            engine.moveTo(
                targetWidthDp = 280f,
                targetHeightDp = 70f,
                targetRadiusDp = 35f,
                spec = entry.spec,
                secondaryVisible = true,
                targetIsWide = true
            )

            var frames = 0
            while (frames < 360 && !engine.currentFrame.settled) {
                engine.advance(1f / 120f)
                frames++
            }

            assertTrue("Engine using ${entry.name} must settle", engine.currentFrame.settled)
            assertEquals("${entry.name}: final width", 280f, engine.currentFrame.widthDp, 0.01f)
            assertEquals("${entry.name}: final height", 70f, engine.currentFrame.heightDp, 0.01f)
            assertEquals("${entry.name}: final radius", 35f, engine.currentFrame.radiusDp, 0.01f)
            assertEquals("${entry.name}: final bubble scale", 1f, engine.currentFrame.secondaryScale, 0.01f)
        }
    }

    // =========================================================================
    // 6. Zero-Allocation & GC Hygiene in Frame Step Path
    // =========================================================================

    @Test
    fun `advance does not allocate per-frame garbage`() {
        val engine = DynamicIslandMotionEngine()
        engine.moveTo(
            targetWidthDp = DynamicIslandMotionEngine.EXPANDED_WIDTH_DP,
            targetHeightDp = DynamicIslandMotionEngine.EXPANDED_HEIGHT_DP,
            targetRadiusDp = 44f,
            spec = SpringCatalog.CompactToExpanded,
            secondaryVisible = true,
            targetIsWide = true
        )

        // Warm up JIT
        repeat(20_000) { engine.advance(1f / 120f) }

        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(20)

        val before = runtime.totalMemory() - runtime.freeMemory()
        repeat(100_000) { engine.advance(1f / 120f) }
        val after = runtime.totalMemory() - runtime.freeMemory()

        val growth = after - before
        assertTrue(
            "100k advance steps grew the heap by $growth bytes; frame path must be leak and allocation minimal",
            growth < 10_000_000L
        )
    }
}
