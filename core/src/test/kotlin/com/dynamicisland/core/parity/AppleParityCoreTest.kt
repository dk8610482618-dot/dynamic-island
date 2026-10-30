package com.dynamicisland.core.parity

import com.dynamicisland.core.activity.ActivityLifecyclePolicy
import com.dynamicisland.core.arbitration.RelevanceArbitrator
import com.dynamicisland.core.geometry.CapsuleAnchor
import com.dynamicisland.core.geometry.DevicePosture
import com.dynamicisland.core.geometry.ExpandedRegionLayoutSolver
import com.dynamicisland.core.geometry.GestureAction
import com.dynamicisland.core.geometry.GesturePolicy
import com.dynamicisland.core.geometry.InteractionMode
import com.dynamicisland.core.geometry.SecondaryBubblePlacement
import com.dynamicisland.core.motion.MetaballGeometry
import com.dynamicisland.core.motion.ScrubCollapseController
import com.dynamicisland.core.privacy.LockStatePrivacyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleParityCoreTest {

    @Test
    fun `apple parity mode routes single tap to open source and long press to expand`() {
        val tapActive = GesturePolicy.resolvePrimaryTap(
            mode = InteractionMode.APPLE_PARITY,
            configuredTap = GestureAction.OPEN_DASHBOARD,
            hasActiveActivity = true,
            visibleMedia = true,
            locked = false
        )
        assertEquals(GestureAction.OPEN_SOURCE, tapActive)

        val tapIdle = GesturePolicy.resolvePrimaryTap(
            mode = InteractionMode.APPLE_PARITY,
            configuredTap = GestureAction.PLAY_PAUSE,
            hasActiveActivity = false,
            visibleMedia = false,
            locked = false
        )
        assertEquals(GestureAction.OPEN_DASHBOARD, tapIdle)

        val holdActive = GesturePolicy.resolvePrimaryHold(
            mode = InteractionMode.APPLE_PARITY,
            configuredHold = GestureAction.OPEN_SOURCE,
            visibleMedia = true,
            locked = false
        )
        assertEquals(GestureAction.OPEN_DASHBOARD, holdActive)

        val lockedTap = GesturePolicy.resolvePrimaryTap(
            mode = InteractionMode.APPLE_PARITY,
            configuredTap = GestureAction.OPEN_SOURCE,
            hasActiveActivity = true,
            visibleMedia = true,
            locked = true
        )
        assertEquals(GestureAction.NONE, lockedTap)
    }

    @Test
    fun `lifecycle policy enforces staleDate 8h ceiling 4h post-end retention and 4KB payload cap`() {
        val start = 1_000_000L
        val staleAt = start + 15 * 60 * 1000L

        val fresh = ActivityLifecyclePolicy.evaluate(
            startedAtMillis = start,
            nowMillis = start + 5 * 60 * 1000L,
            staleAtMillis = staleAt,
            payloadBytes = 1024
        )
        assertFalse(fresh.isStale)
        assertFalse(fresh.shouldVacateIsland)
        assertTrue(fresh.isPayloadWithinLimit)

        val stale = ActivityLifecyclePolicy.evaluate(
            startedAtMillis = start,
            nowMillis = staleAt + 1L,
            staleAtMillis = staleAt,
            payloadBytes = 1024
        )
        assertTrue(stale.isStale)
        assertFalse(stale.shouldVacateIsland)

        val over8Hours = ActivityLifecyclePolicy.evaluate(
            startedAtMillis = start,
            nowMillis = start + ActivityLifecyclePolicy.MAX_ACTIVE_DURATION_MS,
            payloadBytes = 512
        )
        assertTrue(over8Hours.isExpiredActiveCeiling)
        assertTrue(over8Hours.shouldVacateIsland)

        val endedAt = start + 60_000L
        val postEnd2Hours = ActivityLifecyclePolicy.evaluate(
            startedAtMillis = start,
            nowMillis = endedAt + 2 * 3600 * 1000L,
            endedAtMillis = endedAt,
            dismissalPolicy = ActivityLifecyclePolicy.DismissalPolicy.DEFAULT
        )
        assertTrue(postEnd2Hours.shouldVacateIsland)
        assertTrue(postEnd2Hours.shouldRetainInInbox)

        val postEnd5Hours = ActivityLifecyclePolicy.evaluate(
            startedAtMillis = start,
            nowMillis = endedAt + 5 * 3600 * 1000L,
            endedAtMillis = endedAt,
            dismissalPolicy = ActivityLifecyclePolicy.DismissalPolicy.DEFAULT
        )
        assertFalse(postEnd5Hours.shouldRetainInInbox)

        val oversizedPayload = ActivityLifecyclePolicy.evaluate(
            startedAtMillis = start,
            nowMillis = start + 1000L,
            payloadBytes = 4097
        )
        assertFalse(oversizedPayload.isPayloadWithinLimit)
        assertTrue(oversizedPayload.shouldVacateIsland)
    }

    @Test
    fun `relevance arbitrator sorts by priority relevance FIFO and supports 3-activity mitosis cycling`() {
        val candidates = listOf(
            RelevanceArbitrator.Candidate(
                id = "music",
                item = "Spotify",
                basePriority = RelevanceArbitrator.PRIORITY_MEDIA_PLAYING,
                relevanceScore = 0.8f,
                startedAtMillis = 1000L
            ),
            RelevanceArbitrator.Candidate(
                id = "delivery",
                item = "UberEats",
                basePriority = RelevanceArbitrator.PRIORITY_RIDESHARE_DELIVERY_JOURNEY,
                relevanceScore = 0.9f,
                startedAtMillis = 2000L
            ),
            RelevanceArbitrator.Candidate(
                id = "nav",
                item = "GoogleMaps",
                basePriority = RelevanceArbitrator.PRIORITY_NAVIGATION_ONGOING,
                relevanceScore = 0.7f,
                startedAtMillis = 3000L
            ),
            RelevanceArbitrator.Candidate(
                id = "download",
                item = "ChromeDownload",
                basePriority = RelevanceArbitrator.PRIORITY_FILE_DOWNLOAD,
                relevanceScore = 0.5f,
                startedAtMillis = 500L
            )
        )

        val visible3 = RelevanceArbitrator.chooseVisible(candidates, maxConcurrent = 3, focusOffset = 0)
        assertEquals(listOf("GoogleMaps", "Spotify", "UberEats"), visible3)

        val cycled = RelevanceArbitrator.chooseVisible(candidates, maxConcurrent = 3, focusOffset = 1)
        assertEquals(listOf("Spotify", "UberEats", "GoogleMaps"), cycled)

        // Stale penalty demotes navigation below active music
        val withStaleNav = candidates.map {
            if (it.id == "nav") it.copy(isStale = true) else it
        }
        val visibleAfterStale = RelevanceArbitrator.chooseVisible(withStaleNav, maxConcurrent = 3, focusOffset = 0)
        assertEquals("Spotify", visibleAfterStale.first())
    }

    @Test
    fun `four-region expanded solver wraps wide regions below cutout and enforces 160dp nominal height`() {
        val fitBeside = ExpandedRegionLayoutSolver.solveFourRegionGrid(
            containerWidthDp = 370f,
            cutoutWidthDp = 125f,
            leading = ExpandedRegionLayoutSolver.RegionRequest(requiredWidthDp = 80f, priority = 1),
            trailing = ExpandedRegionLayoutSolver.RegionRequest(requiredWidthDp = 80f, priority = 0)
        )
        assertFalse(fitBeside.leadingShiftsBelowCutout)
        assertFalse(fitBeside.trailingShiftsBelowCutout)
        assertNull(fitBeside.fullWidthPriorityRegion)
        assertEquals(44f, fitBeside.cornerRadiusDp, 0.01f)
        assertTrue(fitBeside.containerHeightDp <= 160f)

        val wideLeading = ExpandedRegionLayoutSolver.solveFourRegionGrid(
            containerWidthDp = 370f,
            cutoutWidthDp = 125f,
            leading = ExpandedRegionLayoutSolver.RegionRequest(requiredWidthDp = 140f, priority = 2),
            trailing = ExpandedRegionLayoutSolver.RegionRequest(requiredWidthDp = 75f, priority = 1)
        )
        assertTrue(wideLeading.leadingShiftsBelowCutout)
        assertFalse(wideLeading.trailingShiftsBelowCutout)
        assertEquals("leading", wideLeading.fullWidthPriorityRegion)
    }

    @Test
    fun `width-limited landscape condenses slots to 28dp and hides trailing when narrow`() {
        val portrait = ExpandedRegionLayoutSolver.resolveWidthLimitedCompact(
            isLandscape = false,
            availableCapsuleWidthDp = 125f
        )
        assertFalse(portrait.isLimitedInWidth)
        assertTrue(portrait.showLeading && portrait.showTrailing)

        val landscapeTight = ExpandedRegionLayoutSolver.resolveWidthLimitedCompact(
            isLandscape = true,
            availableCapsuleWidthDp = 82f,
            cutoutWidthDp = 37f
        )
        assertTrue(landscapeTight.isLimitedInWidth)
        assertEquals(28f, landscapeTight.slotDiameterDp, 0.01f)
        assertTrue(landscapeTight.showLeading)
        assertFalse(landscapeTight.showTrailing)
    }

    @Test
    fun `foldable posture avoids crease intersection and supports duo outer right placement`() {
        val candidate = CapsuleAnchor.Window(left = 840, top = 24, width = 360, height = 110, rotation = 0f)
        val crease = ExpandedRegionLayoutSolver.ReservedRegion(leftPx = 980, topPx = 0, rightPx = 1020, bottomPx = 2200)
        val adjusted = ExpandedRegionLayoutSolver.resolvePostureWindow(
            candidate = candidate,
            screenWidthPx = 2000,
            screenHeightPx = 2200,
            posture = DevicePosture.FOLDABLE_HALF_OPENED,
            reservedCrease = crease,
            safeMarginPx = 20
        )
        assertTrue(adjusted.left + adjusted.width <= crease.leftPx || adjusted.left >= crease.rightPx)

        val duoRight = ExpandedRegionLayoutSolver.resolvePostureWindow(
            candidate = candidate,
            screenWidthPx = 2000,
            screenHeightPx = 2200,
            posture = DevicePosture.DUO_OUTER_RIGHT,
            safeMarginPx = 24
        )
        assertEquals(2000 - 360 - 24, duoRight.left)
    }

    @Test
    fun `scrub collapse controller interpolates 1 to 1 finger delta and velocity threshold`() {
        val midScrub = ScrubCollapseController.interpolate(
            cumulativeDeltaYPx = -150f,
            expandedWidthPx = 1000f,
            expandedHeightPx = 410f,
            compactWidthPx = 340f,
            compactHeightPx = 110f
        )
        assertEquals(0.5f, midScrub.progress, 0.01f)
        assertEquals(670f, midScrub.widthPx, 0.5f)
        assertEquals(260f, midScrub.heightPx, 0.5f)
        assertTrue(midScrub.cornerRadiusDp in 18.5f..44f)
        assertTrue(ScrubCollapseController.shouldCollapseOnRelease(midScrub.progress, 0f))
        assertTrue(ScrubCollapseController.shouldCollapseOnRelease(0.15f, -900f))
        assertFalse(ScrubCollapseController.shouldCollapseOnRelease(0.15f, 0f))
    }

    @Test
    fun `metaball geometry smooth minimum and ferrofluid bridge neck stretch and snap cleanly`() {
        val dCombined = MetaballGeometry.smoothMin(4f, 4f, k = 12f)
        assertTrue(dCombined < 4f)

        val connected = MetaballGeometry.computeBridgeNeck(
            primaryRightX = 600f,
            dropletLeftX = 610f,
            centerY = 55f,
            dropletDiameterPx = 96f,
            snapThresholdPx = 28f
        )
        assertTrue(connected.isConnected)
        assertTrue(connected.pinchHeight > 0f)

        val snapped = MetaballGeometry.computeBridgeNeck(
            primaryRightX = 600f,
            dropletLeftX = 632f,
            centerY = 55f,
            dropletDiameterPx = 96f,
            snapThresholdPx = 28f
        )
        assertFalse(snapped.isConnected)
        assertEquals(0f, snapped.pinchHeight, 0.001f)
    }

    @Test
    fun `tertiary droplet placement resolves non-overlapping third slot for iPhone 18 Pro mitosis`() {
        val primary = CapsuleAnchor.Window(left = 380, top = 20, width = 320, height = 96, rotation = 0f)
        val secondary = SecondaryBubblePlacement.resolve(primary, 1080, 2400, diameter = 96, gap = 24)
        assertNotNull(secondary)
        val tertiary = SecondaryBubblePlacement.resolveTertiary(
            primary = primary,
            secondary = secondary!!,
            screenWidth = 1080,
            screenHeight = 2400,
            diameter = 96,
            gap = 24
        )
        assertNotNull(tertiary)
        assertTrue(tertiary!!.left + tertiary.width <= primary.left || tertiary.left >= secondary.left + secondary.width)
    }

    @Test
    fun `smart lock screen privacy policy redacts personal details into semantic summaries when locked`() {
        val lockedMedia = LockStatePrivacyPolicy.resolveSummaryForKind(
            kindName = "MEDIA_SESSION",
            originalTitle = "Secret Voice Memo",
            originalSubtitle = "Private Artist",
            isKeyguardLocked = true
        )
        assertTrue(lockedMedia.isRedacted)
        assertEquals("Playing", lockedMedia.title)
        assertFalse(lockedMedia.allowDirectControlActions)

        val unlockedMedia = LockStatePrivacyPolicy.resolveSummaryForKind(
            kindName = "MEDIA_SESSION",
            originalTitle = "Starboy",
            originalSubtitle = "The Weeknd",
            isKeyguardLocked = false
        )
        assertFalse(unlockedMedia.isRedacted)
        assertEquals("Starboy", unlockedMedia.title)
        assertTrue(unlockedMedia.allowDirectControlActions)
    }
}
