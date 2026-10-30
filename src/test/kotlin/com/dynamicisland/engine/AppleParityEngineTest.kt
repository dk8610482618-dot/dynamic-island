package com.dynamicisland.engine

import android.app.Notification
import com.dynamicisland.core.geometry.InteractionMode
import com.dynamicisland.engine.arbiter.IslandPriorityArbiter
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.ActivityKind
import com.dynamicisland.engine.core.model.IslandState
import com.dynamicisland.engine.ingestion.notifications.LiveUpdateProgressParser
import com.dynamicisland.engine.settings.BehaviorOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppleParityEngineTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var eventBus: IslandEventBus
    private lateinit var optionsFlow: MutableStateFlow<BehaviorOptions>
    private lateinit var arbiter: IslandPriorityArbiter
    private var simulatedClockMillis: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        eventBus = IslandEventBus()
        optionsFlow = MutableStateFlow(BehaviorOptions())
        simulatedClockMillis = 1_700_000_000_000L
    }

    private fun TestScope.startArbiter() {
        arbiter = IslandPriorityArbiter(
            eventBus = eventBus,
            scope = backgroundScope,
            behavior = optionsFlow,
            clockMillis = { simulatedClockMillis }
        )
    }

    private fun emitMediaSession(
        packageName: String = "com.spotify.music",
        title: String = "Starboy",
        artist: String = "The Weeknd",
        dominantColorHex: String? = "#FF2D55"
    ) {
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = packageName,
                isPlaying = true,
                playbackState = 3,
                positionMs = 15_000L,
                durationMs = 210_000L,
                playbackSpeed = 1f,
                lastUpdateElapsedRealtime = 1_000L
            )
        )
        eventBus.emitEvent(
            SystemIngestionEvent.Media.MetadataChanged(
                packageName = packageName,
                title = title,
                artist = artist,
                album = "Starboy",
                albumArtBitmap = null,
                albumArtUri = null,
                dominantColorHex = dominantColorHex
            )
        )
    }

    @Test
    fun `LiveUpdateProgressParser classifies navigation turn-by-turn notifications`() {
        val parsed = LiveUpdateProgressParser.classifyAndParse(
            packageName = "com.google.android.apps.maps",
            category = Notification.CATEGORY_NAVIGATION,
            title = "Turn right onto Market St",
            text = "350 ft • 12 min remaining",
            subText = "ETA 6:45 PM",
            progressCurrent = 62,
            progressMax = 100,
            isIndeterminate = false,
            showChronometer = false,
            isCountDown = false,
            isProgressStyle = true
        )
        assertNotNull(parsed)
        assertEquals(ActivityKind.NAVIGATION, parsed!!.kind)
        assertEquals("350 ft", parsed.compactTrailingBadge)
        assertEquals("ETA 6:45 PM", parsed.etaLabel)
        assertEquals("#0A84FF", parsed.keylineTintHex)
    }

    @Test
    fun `LiveUpdateProgressParser classifies rideshare and delivery journeys with milestones`() {
        val parsed = LiveUpdateProgressParser.classifyAndParse(
            packageName = "com.doordash.driverapp",
            category = Notification.CATEGORY_PROGRESS,
            title = "Dasher is on the way with your order",
            text = "Delivery in 6 min",
            subText = "6 min",
            progressCurrent = 75,
            progressMax = 100,
            isIndeterminate = false,
            showChronometer = false,
            isCountDown = false,
            isProgressStyle = true
        )
        assertNotNull(parsed)
        assertEquals(ActivityKind.LIVE_JOURNEY, parsed!!.kind)
        assertEquals(4, parsed.milestones.size)
        assertEquals(2, parsed.currentMilestoneIndex)
        assertEquals("#30D158", parsed.keylineTintHex)
    }

    @Test
    fun `LiveUpdateProgressParser classifies ongoing file downloads`() {
        val parsed = LiveUpdateProgressParser.classifyAndParse(
            packageName = "com.android.providers.downloads",
            category = Notification.CATEGORY_PROGRESS,
            title = "Downloading macOS-Sequoia.dmg",
            text = "420 MB of 1.0 GB",
            subText = "42%",
            progressCurrent = 42,
            progressMax = 100,
            isIndeterminate = false,
            showChronometer = false,
            isCountDown = false,
            isProgressStyle = false
        )
        assertNotNull(parsed)
        assertEquals(ActivityKind.FILE_DOWNLOAD, parsed!!.kind)
        assertEquals("42%", parsed.compactTrailingBadge)
        assertEquals("#64D2FF", parsed.keylineTintHex)
    }

    @Test
    fun `Apple Parity mode single tap keeps compact state while long press expands`() = testScope.runTest {
        optionsFlow.value = BehaviorOptions(interactionMode = InteractionMode.APPLE_PARITY)
        startArbiter()

        emitMediaSession(dominantColorHex = "#FF2D55")
        testDispatcher.scheduler.runCurrent()

        assertTrue(arbiter.islandState.value is IslandState.Compact)
        val compact = arbiter.islandState.value as IslandState.Compact
        assertEquals("#FF2D55", compact.activity.keylineTintHex)

        // Single tap in APPLE_PARITY mode does not expand the island (routes to host app deepLinkIntent)
        eventBus.emitCommand(IslandUserCommand.TapPrimary)
        testDispatcher.scheduler.runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Compact)

        // Long press in APPLE_PARITY mode expands the island into the 4-region card
        eventBus.emitCommand(IslandUserCommand.LongPressPrimary)
        testDispatcher.scheduler.runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Expanded)
    }

    @Test
    fun `Android Classic mode single tap expands primary activity`() = testScope.runTest {
        optionsFlow.value = BehaviorOptions(interactionMode = InteractionMode.ANDROID_CLASSIC)
        startArbiter()

        emitMediaSession()
        testDispatcher.scheduler.runCurrent()

        eventBus.emitCommand(IslandUserCommand.TapPrimary)
        testDispatcher.scheduler.runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Expanded)
    }

    @Test
    fun `3-activity mitosis populates primary secondary and tertiary slots and supports focus cycling`() = testScope.runTest {
        optionsFlow.value = BehaviorOptions(maxConcurrentActivities = 3)
        startArbiter()

        // 1. Media Session
        emitMediaSession(title = "Midnight City", artist = "M83")

        // 2. Countdown Timer
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "timer-1",
                packageName = "com.google.android.deskclock",
                notificationId = 101,
                category = Notification.CATEGORY_ALARM,
                isOngoing = true,
                title = "Kitchen Timer",
                text = "04:30",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = simulatedClockMillis + 270_000L,
                showChronometer = true,
                isChronometerCountDown = true,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null,
                shouldAlert = false,
                postedAtMillis = simulatedClockMillis
            )
        )
        // 3. Live Delivery Journey
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "delivery-1",
                packageName = "com.ubercab.eats",
                notificationId = 102,
                category = Notification.CATEGORY_PROGRESS,
                isOngoing = true,
                title = "Courier is on the way",
                text = "Delivery in 6 min",
                subText = "6 min",
                progressCurrent = 70,
                progressMax = 100,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null,
                shouldAlert = false,
                postedAtMillis = simulatedClockMillis,
                isProgressStyle = true,
                relevanceScore = 88.0f
            )
        )
        testDispatcher.scheduler.runCurrent()

        val state = arbiter.islandState.value
        assertTrue("Expected Minimal state for multi-activity mitosis, got $state", state is IslandState.Minimal)
        val minimal = state as IslandState.Minimal
        assertNotNull(minimal.primary)
        assertNotNull(minimal.secondary)
        assertNotNull("Expected tertiary slot populated when maxConcurrentActivities = 3", minimal.tertiary)

        val initialPrimaryId = minimal.primary.id
        eventBus.emitCommand(IslandUserCommand.CycleActivityFocus(1))
        testDispatcher.scheduler.runCurrent()

        val rotated = arbiter.islandState.value as IslandState.Minimal
        assertTrue("Expected primary activity to rotate on CycleActivityFocus", rotated.primary.id != initialPrimaryId)
    }

    @Test
    fun `ActivityKit 4KB payload cap rejects oversized notification payloads`() = testScope.runTest {
        startArbiter()
        val hugeText = "X".repeat(4500) // > 4096 UTF-16 bytes
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "oversized-1",
                packageName = "com.ubercab.eats",
                notificationId = 201,
                category = Notification.CATEGORY_PROGRESS,
                isOngoing = true,
                title = "Order Status",
                text = hugeText,
                subText = null,
                progressCurrent = 50,
                progressMax = 100,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null,
                shouldAlert = false,
                isProgressStyle = true
            )
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals(IslandState.Closed, arbiter.islandState.value)
    }

    @Test
    fun `ActivityKit staleDate marks activity as stale when clock passes staleAtMillis`() = testScope.runTest {
        startArbiter()
        val staleDeadline = simulatedClockMillis + 60_000L
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "delivery-live-1",
                packageName = "com.ubercab.eats",
                notificationId = 301,
                category = Notification.CATEGORY_PROGRESS,
                isOngoing = true,
                title = "Order on the way",
                text = "Delivery in 8 min",
                subText = "8 min",
                progressCurrent = 65,
                progressMax = 100,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null,
                shouldAlert = false,
                postedAtMillis = simulatedClockMillis,
                isProgressStyle = true,
                staleAtMillis = staleDeadline
            )
        )
        testDispatcher.scheduler.runCurrent()
        val initial = (arbiter.islandState.value as IslandState.Compact).activity
        assertEquals(false, initial.isStale)

        // Advance clock past staleAtMillis and trigger re-evaluation
        simulatedClockMillis = staleDeadline + 5_000L
        eventBus.emitCommand(IslandUserCommand.CollapseExpanded)
        testDispatcher.scheduler.runCurrent()
        val afterStale = (arbiter.islandState.value as IslandState.Compact).activity
        assertEquals(true, afterStale.isStale)
    }
}
