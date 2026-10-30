package com.dynamicisland.engine

import com.dynamicisland.engine.arbiter.IslandPriorityArbiter
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.ActivityKind
import com.dynamicisland.engine.core.model.IslandSlotContent
import com.dynamicisland.engine.core.model.IslandState
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.core.model.PriorityRank
import com.dynamicisland.engine.core.model.TransientAlert
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IslandPriorityArbiterTest {

    // Unconfined so SharedFlow emissions reach the arbiter's collectors immediately;
    // with a queued dispatcher the subscription resumption is never drained here.
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var eventBus: IslandEventBus
    private lateinit var arbiter: IslandPriorityArbiter

    @Before
    fun setUp() {
        eventBus = IslandEventBus()
    }

    /**
     * The arbiter runs long-lived collectors, so it must be hosted on the test's
     * [kotlinx.coroutines.test.TestScope.backgroundScope]: that scope shares the test
     * scheduler (so `advanceUntilIdle()` drives it) but is cancelled when the test
     * finishes instead of making `runTest` hang on an unfinished coroutine.
     */
    private fun kotlinx.coroutines.test.TestScope.startArbiter() {
        arbiter = IslandPriorityArbiter(eventBus, backgroundScope)
    }

    @Test
    fun `initial state is Closed`() = testScope.runTest {
        startArbiter()
        assertEquals(IslandState.Closed, arbiter.islandState.value)
    }

    @Test
    fun `clear dismisses only represented dismissible notifications`() = testScope.runTest {
        val dismissed = mutableListOf<String>()
        arbiter = IslandPriorityArbiter(eventBus, backgroundScope, onDismissNotifications = { dismissed.addAll(it) })
        postNotification("first", "One")
        postNotification("second", "Two")
        eventBus.emitCommand(IslandUserCommand.ClearInbox)
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf("first", "second"), dismissed.toSet())
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf("first", "second"), (arbiter.islandState.value as IslandState.NotificationInbox).notifications.map { it.key }.toSet())
    }

    @Test
    fun `individual dismissal preserves other inbox entries`() = testScope.runTest {
        val dismissed = mutableListOf<String>()
        arbiter = IslandPriorityArbiter(eventBus, backgroundScope, onDismissNotifications = { dismissed.addAll(it) })
        postNotification("first", "One")
        postNotification("second", "Two")
        eventBus.emitCommand(IslandUserCommand.DismissNotification("first"))
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("first"), dismissed)
        assertEquals(setOf("first", "second"), (arbiter.islandState.value as IslandState.NotificationInbox).notifications.map { it.key }.toSet())
        eventBus.emitEvent(SystemIngestionEvent.NotificationEvent.Removed("first", "com.chat.app", 0, 0))
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("second"), (arbiter.islandState.value as IslandState.NotificationInbox).notifications.map { it.key })
    }

    @Test
    fun `notification snapshot restores inbox without a heads up alert`() = testScope.runTest {
        startArbiter()
        eventBus.emitEvent(SystemIngestionEvent.NotificationEvent.Posted(
            key = "restored", packageName = "chat", notificationId = 1, category = null,
            isOngoing = false, title = "Already waiting", text = null, subText = null,
            progressCurrent = null, progressMax = null, isProgressIndeterminate = false,
            chronometerBase = null, showChronometer = false, actions = emptyList(),
            contentIntent = null, statusBarNotification = null, shouldAlert = false
        ))
        testDispatcher.scheduler.runCurrent()
        assertEquals(IslandState.Closed, arbiter.islandState.value)
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.runCurrent()
        assertEquals("restored", (arbiter.islandState.value as IslandState.NotificationInbox).newest?.key)
    }

    @Test
    fun `background player metadata cannot replace foreground track`() = testScope.runTest {
        startArbiter()
        eventBus.emitEvent(SystemIngestionEvent.Media.PlaybackChanged("playerA", true, 3, 0, 1000, 1f, 0))
        eventBus.emitEvent(SystemIngestionEvent.Media.MetadataChanged("playerA", "Track A", null, null, null, null))
        eventBus.emitEvent(SystemIngestionEvent.Media.MetadataChanged("playerB", "Track B", null, null, null, null))
        testDispatcher.scheduler.runCurrent()
        assertEquals("Track A", (arbiter.islandState.value as IslandState.Compact).activity.title)
    }

    @Test
    fun `resume restores playing indicators without requiring new metadata`() = testScope.runTest {
        startArbiter()
        eventBus.emitEvent(SystemIngestionEvent.Media.PlaybackChanged("playerA", true, 3, 0, 1000, 1f, 0))
        eventBus.emitEvent(SystemIngestionEvent.Media.PlaybackChanged("playerA", false, 2, 0, 1000, 1f, 0))
        eventBus.emitEvent(SystemIngestionEvent.Media.PlaybackChanged("playerA", true, 3, 0, 1000, 1f, 0))
        testDispatcher.scheduler.runCurrent()
        val activity = (arbiter.islandState.value as IslandState.Compact).activity
        assertTrue(activity.compactTrailing is IslandSlotContent.AudioWaveform)
        assertEquals("Playing", (activity.expandedData.trailing as IslandSlotContent.Text).text)
    }

    @Test
    fun `disabled notifications do not enter inbox`() = testScope.runTest {
        val options = kotlinx.coroutines.flow.MutableStateFlow(
            com.dynamicisland.engine.settings.BehaviorOptions(notificationsEnabled = false)
        )
        arbiter = IslandPriorityArbiter(eventBus, backgroundScope, behavior = options)
        postNotification("hidden", "Hidden")
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.runCurrent()
        assertEquals(IslandState.Closed, arbiter.islandState.value)
    }

    @Test
    fun `single media session produces Compact presentation`() = testScope.runTest {
        startArbiter()
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3, // STATE_PLAYING
                positionMs = 15000L,
                durationMs = 210000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue("Expected Compact state for single media session", state is IslandState.Compact)
        val compact = state as IslandState.Compact
        assertEquals("com.spotify.music", compact.activity.ownerPackage)
        assertEquals(PriorityRank.MEDIA_PLAYBACK, compact.activity.rank)
    }

    @Test
    fun `two simultaneous activities trigger Minimal multi-island presentation`() = testScope.runTest {
        startArbiter()
        // 1. Post media playback (Rank 400)
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 15000L,
                durationMs = 210000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // 2. Post ongoing timer notification (Rank 200)
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "timer_notification_key",
                packageName = "com.google.android.deskclock",
                notificationId = 42,
                category = "alarm",
                isOngoing = true,
                title = "Timer",
                text = "04:59",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = 300000L,
                showChronometer = true,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue("Expected Minimal state when 2 activities are active", state is IslandState.Minimal)
        val minimal = state as IslandState.Minimal

        // Timer (Rank 200) must be Primary attached, Media (Rank 400) must be Secondary detached
        assertEquals("timer_notification_key", minimal.primary.id)
        assertEquals(PriorityRank.LIVE_TIMER_STOPWATCH, minimal.primary.rank)
        assertEquals("activity_media_session", minimal.secondary.id)
        assertEquals(PriorityRank.MEDIA_PLAYBACK, minimal.secondary.rank)
        assertEquals(2, minimal.totalActiveActivitiesCount)
    }

    @Test
    fun `telecom call preempts all lower priority activities and takes primary slot`() = testScope.runTest {
        startArbiter()
        // 1. Start media
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 1000L,
                durationMs = 200000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // 2. Incoming phone call arrives (Rank 100)
        eventBus.emitEvent(
            SystemIngestionEvent.Telecom.CallStateChanged(
                callId = "call_99",
                status = SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,
                callerName = "John Appleseed",
                callerNumber = "+1555123456",
                avatarUri = null,
                avatarBitmap = null,
                durationSeconds = 0L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue(state is IslandState.Minimal)
        val minimal = state as IslandState.Minimal

        // Call (Rank 100) must take the primary position over Media (Rank 400)
        assertEquals("activity_telecom_call_99", minimal.primary.id)
        assertEquals(PriorityRank.EMERGENCY_OR_CALL, minimal.primary.rank)
        assertEquals("John Appleseed", minimal.primary.title)
    }

    @Test
    fun `user tap expands primary activity and a second tap restores compact`() = testScope.runTest {
        startArbiter()
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 1000L,
                durationMs = 200000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // A single TAP opens the island. The gesture that used to expand was the
        // long press; it now launches the app instead, so this test asserts the
        // new contract rather than the old one.
        eventBus.emitCommand(IslandUserCommand.TapPrimary)
        testDispatcher.scheduler.advanceUntilIdle()

        val expandedState = arbiter.islandState.value
        assertTrue("Expected Expanded state after a tap", expandedState is IslandState.Expanded)
        val expanded = expandedState as IslandState.Expanded
        assertEquals("activity_media_session", expanded.activity.id)

        // User taps again to close.
        eventBus.emitCommand(IslandUserCommand.CollapseExpanded)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Expected Compact state after collapse", arbiter.islandState.value is IslandState.Compact)
    }

    /**
     * A hold must not expand.
     *
     * The two gestures are deliberately distinct: tap opens the island, hold
     * opens the app. A regression that made a hold expand as well would leave no
     * gesture for launching the app at all.
     */
    @Test
    fun `user long-press does not expand the island`() = testScope.runTest {
        startArbiter()
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 1000L,
                durationMs = 200000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val before = arbiter.islandState.value
        assertTrue("precondition: should start compact", before is IslandState.Compact)

        eventBus.emitCommand(IslandUserCommand.LongPressPrimary)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "a hold must launch the app, not expand the island; state was " +
                arbiter.islandState.value,
            arbiter.islandState.value is IslandState.Compact
        )
    }

    @Test
    fun `transient alert peek auto-dismisses and restores previous state`() = testScope.runTest {
        startArbiter()
        // Start with media
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 1000L,
                durationMs = 200000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Trigger Battery Charging Alert Peek (duration 2500ms)
        val alert = TransientAlert(
            id = "charging_alert",
            title = "Charging",
            message = "88%",
            leadingSlot = IslandSlotContent.Text("⚡"),
            trailingSlot = IslandSlotContent.Text("88%"),
            durationMillis = 2500L,
            rank = PriorityRank.TRANSIENT_SYSTEM_ALERT
        )
        eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(alert))
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify AlertPeek presentation
        val peekState = arbiter.islandState.value
        assertTrue("Expected AlertPeek state", peekState is IslandState.AlertPeek)
        val peek = peekState as IslandState.AlertPeek
        assertEquals("Charging", peek.alert.title)
        assertTrue("Underlying state must be Compact", peek.underlyingState is IslandState.Compact)

        // Advance time past 2500ms
        testScope.advanceTimeBy(2600L)
        testDispatcher.scheduler.advanceUntilIdle()

        // Must restore Compact state
        assertTrue("Expected restoration to Compact state after alert timeout", arbiter.islandState.value is IslandState.Compact)
    }

    @Test
    fun `incoming call interrupts and cancels lower-priority expanded activity`() = testScope.runTest {
        startArbiter()
        // 1. Start media and expand it
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 1000L,
                durationMs = 200000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
        // Expanded via a tap: that is the gesture that opens the island now. The
        // hold launches the app instead, so it would not put the arbiter into an
        // expanded state for this test to interrupt.
        eventBus.emitCommand(IslandUserCommand.TapPrimary)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(arbiter.islandState.value is IslandState.Expanded)

        // 2. Incoming call arrives
        eventBus.emitEvent(
            SystemIngestionEvent.Telecom.CallStateChanged(
                callId = "urgent_call",
                status = SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,
                callerName = "Boss",
                callerNumber = "+1555999888",
                avatarUri = null,
                avatarBitmap = null,
                durationSeconds = 0L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Expansion must be canceled, transition to call
        val state = arbiter.islandState.value
        assertTrue("Call must cancel media expansion", state !is IslandState.Expanded)
        assertTrue(state is IslandState.Minimal)
        assertEquals("activity_telecom_urgent_call", (state as IslandState.Minimal).primary.id)
    }

    // =====================================================================
    // Notification inbox
    // =====================================================================

    /** Posts a plain (non-ongoing) notification, which is what a chat message is. */
    private fun postNotification(key: String, title: String, text: String? = null) {
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = key,
                packageName = "com.example.app",
                notificationId = key.hashCode(),
                category = null,
                isOngoing = false,
                title = title,
                text = text,
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    /**
     * A notification that is not ongoing shows a transient peek, NOT the inbox.
     * The inbox is something the user opens; a peek is something the island does.
     */
    @Test
    fun `a posted notification pokes the island without opening the inbox`() = testScope.runTest {
        startArbiter()
        postNotification("k1", "Alice", "Are you around?")

        assertTrue(
            "an arriving notification should peek, not sit in an open inbox",
            arbiter.islandState.value is IslandState.AlertPeek
        )
    }

    @Test
    fun `tapping a quiet island opens the inbox`() = testScope.runTest {
        startArbiter()
        postNotification("k1", "Alice", "Are you around?")

        // Let the peek expire, which is what happens while the user is away.
        testDispatcher.scheduler.advanceTimeBy(4_000L)
        testDispatcher.scheduler.advanceUntilIdle()

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue("expected the inbox to open, was $state", state is IslandState.NotificationInbox)
        assertEquals(1, (state as IslandState.NotificationInbox).count)
        assertEquals("Alice", state.notifications.first().title)
    }

    /** Newest first: the thing that just arrived is what is being looked for. */
    @Test
    fun `inbox is ordered newest first`() = testScope.runTest {
        startArbiter()
        postNotification("k1", "First")
        postNotification("k2", "Second")
        postNotification("k3", "Third")

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value as IslandState.NotificationInbox
        assertEquals(listOf("Third", "Second", "First"), state.notifications.map { it.title })
    }

    /**
     * An app updating a notification replaces its entry rather than adding one.
     *
     * Progress notifications re-post many times a second. Without replacement the
     * inbox would fill with near-identical rows for one piece of work.
     */
    @Test
    fun `re-posting the same key replaces rather than duplicates`() = testScope.runTest {
        startArbiter()
        postNotification("k1", "Downloading", "10%")
        postNotification("k1", "Downloading", "80%")

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value as IslandState.NotificationInbox
        assertEquals("the update must replace, not append", 1, state.count)
        assertEquals("80%", state.notifications.first().body)
    }

    @Test
    fun `clearing empties the inbox and restores the underlying state`() = testScope.runTest {
        startArbiter()
        postNotification("k1", "Alice")
        testDispatcher.scheduler.advanceTimeBy(4_000L)
        testDispatcher.scheduler.advanceUntilIdle()

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(arbiter.islandState.value is IslandState.NotificationInbox)

        eventBus.emitCommand(IslandUserCommand.ClearInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "clearing must fall back to the ordinary presentation",
            IslandState.Closed,
            arbiter.islandState.value
        )
    }

    /**
     * A notification dismissed from the system shade leaves the inbox too.
     *
     * Otherwise the island would keep offering to open something that is no
     * longer there, and the two surfaces would disagree.
     */
    @Test
    fun `a notification removed from the shade leaves the inbox`() = testScope.runTest {
        startArbiter()
        postNotification("k1", "Alice")
        postNotification("k2", "Bob")
        testDispatcher.scheduler.advanceTimeBy(4_000L)
        testDispatcher.scheduler.advanceUntilIdle()

        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Removed(
                key = "k1",
                packageName = "com.example.app",
                notificationId = 1,
                reason = 0
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value as IslandState.NotificationInbox
        assertEquals(1, state.count)
        assertEquals("Bob", state.notifications.first().title)
    }

    /**
     * The inbox is bounded.
     *
     * A listener runs for days and sees every notification the device posts; an
     * unbounded list is a slow leak in a component the user never restarts.
     */
    @Test
    fun `inbox drops the oldest entries beyond its cap`() = testScope.runTest {
        startArbiter()
        repeat(40) { i -> postNotification("k$i", "Message $i") }

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value as IslandState.NotificationInbox
        assertEquals("inbox must be capped", 20, state.count)
        assertEquals("the newest must survive", "Message 39", state.newest?.title)
    }

    /** An empty inbox is not something the user can open. */
    @Test
    fun `opening an empty inbox does nothing`() = testScope.runTest {
        startArbiter()
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(IslandState.Closed, arbiter.islandState.value)
    }

    /**
     * A low-priority ongoing notification — no category, no progress, no
     * chronometer — waits in the inbox and does not take the island.
     *
     * Every ongoing notification used to be promoted to an activity. On a real
     * device the shade always holds some of these ("Serial console enabled",
     * USB debugging, sync notices), so each one pinned a card the island could
     * not shake — and because opening the inbox requires a quiet island, the
     * inbox itself became unreachable.
     */
    @Test
    fun `a low-priority ongoing notification waits in the inbox without taking the island`() = testScope.runTest {
        startArbiter()

        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "k_serial",
                packageName = "android",
                notificationId = 55,
                category = null,
                isOngoing = true,
                title = "Serial console enabled",
                text = "Performance is impacted.",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "an ongoing system notice must not occupy the island",
            IslandState.Closed,
            arbiter.islandState.value
        )

        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value as IslandState.NotificationInbox
        assertEquals("it must still be found in the inbox", 1, state.count)
        assertEquals("Serial console enabled", state.newest?.title)
    }

    /**
     * A media notification (category transport, or from the active media package)
     * must never be promoted to a LIVE_TIMER or LIVE_STOPWATCH, even if it has
     * showChronometer=true.
     */
    @Test
    fun `media notification with chronometer does not override media session as timer`() = testScope.runTest {
        startArbiter()

        // 1. Media is playing
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "ch.blinkenlights.android.vanilla",
                isPlaying = true,
                playbackState = 3,
                positionMs = 15000L,
                durationMs = 200000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val mediaState = arbiter.islandState.value
        assertTrue("Media should be compact pill", mediaState is IslandState.Compact)
        assertEquals(ActivityKind.MEDIA_SESSION, (mediaState as IslandState.Compact).activity.kind)

        // 2. The player posts an ongoing notification with showChronometer = true
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "vanilla_notification",
                packageName = "ch.blinkenlights.android.vanilla",
                notificationId = 10,
                category = "transport",
                isOngoing = true,
                title = "Moonlight Sonata",
                text = "Ludwig van Beethoven",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = 1000L,
                showChronometer = true,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Island must STILL be the genuine MEDIA_SESSION, not overridden by a stopwatch or timer
        val currentState = arbiter.islandState.value
        assertTrue("Island must remain compact media pill, not a timer", currentState is IslandState.Compact)
        val compact = currentState as IslandState.Compact
        assertEquals("Must remain MEDIA_SESSION", ActivityKind.MEDIA_SESSION, compact.activity.kind)
        assertTrue("Trailing slot must be AudioWaveform, not Chronometer", compact.activity.compactTrailing is IslandSlotContent.AudioWaveform)
    }

    /**
     * Lockscreen Presentation:
     * - If no activities: island rests at Closed around cutout.
     * - Incoming alerts, media, calls, and timers display on lockscreen.
     */
    @Test
    fun `lockscreen redacts outgoing content and restores activities on unlock`() = testScope.runTest {
        startArbiter()

        // Screen is locked with no activities -> Closed
        eventBus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(isLocked = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Locked with no media must be Closed", IslandState.Closed, arbiter.islandState.value)

        // Incoming alert posted while locked -> Displays AlertPeek
        eventBus.emitEvent(
            SystemIngestionEvent.TransientAlertPost(
                TransientAlert(
                    id = "battery_alert",
                    title = "Battery 20%",
                    leadingSlot = IslandSlotContent.Symbol(IslandSymbol.BATTERY),
                    durationMillis = 3000L,
                    rank = PriorityRank.TRANSIENT_SYSTEM_ALERT
                )
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val alertState = arbiter.islandState.value
        assertEquals("Private event content must not reach a locked presentation", IslandState.Closed, alertState)

        // Music starts playing while locked
        eventBus.emitEvent(
            SystemIngestionEvent.Media.PlaybackChanged(
                packageName = "com.spotify.music",
                isPlaying = true,
                playbackState = 3,
                positionMs = 5000L,
                durationMs = 180000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedRealtime = 1000L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // After alert peek finishes
        testDispatcher.scheduler.advanceTimeBy(3500L)
        testDispatcher.scheduler.advanceUntilIdle()

        val lockMediaState = arbiter.islandState.value
        assertEquals("Locked media content is redacted", IslandState.Closed, lockMediaState)
        assertEquals(null,arbiter.mediaActivity.value)

        // Unlocking restores normal arbitration
        eventBus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(isLocked = false))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Unlocked maintains media", arbiter.islandState.value is IslandState.Compact)
    }

    /**
     * Answer and Decline have no API behind them unless this app is the bound
     * `InCallService`. The flag has to survive the trip from the event, through
     * the arbiter, into the activity's call state — otherwise the call card
     * renders two buttons that silently ignore the tap on every install that is
     * not the default dialer.
     */
    @Test
    fun `call controllability is carried from the event into the call state`() = testScope.runTest {
        startArbiter()

        fun ringing(controllable: Boolean) =
            SystemIngestionEvent.Telecom.CallStateChanged(
                callId = "call_1",
                status = SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,
                callerName = "Ada Lovelace",
                callerNumber = "+15551234",
                avatarUri = null,
                avatarBitmap = null,
                isControllable = controllable
            )

        eventBus.emitEvent(ringing(controllable = false))
        testDispatcher.scheduler.advanceUntilIdle()

        val observed = arbiter.islandState.value
        assertTrue("expected the call to take the island", observed is IslandState.Compact)
        assertEquals(
            "telephony-broadcast path must not claim call control",
            false,
            (observed as IslandState.Compact).activity.callState?.isControllable
        )

        eventBus.emitEvent(ringing(controllable = true))
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = arbiter.islandState.value
        assertTrue(updated is IslandState.Compact)
        assertEquals(
            "default-dialer path must keep its controls",
            true,
            (updated as IslandState.Compact).activity.callState?.isControllable
        )
    }

    /**
     * The platform keeps a media session for nearly every installed app and it
     * publishes metadata while idle. Metadata must decorate a card that
     * playback opened, never open one on its own — otherwise a device that has
     * never played anything carries a permanent music card.
     */
    @Test
    fun `metadata alone does not open the island`() = testScope.runTest {
        startArbiter()

        eventBus.emitEvent(
            SystemIngestionEvent.Media.MetadataChanged(
                packageName = "com.google.android.googlequicksearchbox",
                title = "Unknown Title",
                artist = null,
                album = null,
                albumArtBitmap = null,
                albumArtUri = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "an idle session's metadata must not take the island",
            arbiter.islandState.value is IslandState.Closed
        )

        // Once playback opens the card, the same metadata decorates it.
        eventBus.emitEvent(playing("com.google.android.googlequicksearchbox"))
        eventBus.emitEvent(
            SystemIngestionEvent.Media.MetadataChanged(
                packageName = "com.google.android.googlequicksearchbox",
                title = "Real Track",
                artist = "Real Artist",
                album = null,
                albumArtBitmap = null,
                albumArtUri = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue(state is IslandState.Compact)
        assertEquals("Real Track", (state as IslandState.Compact).activity.title)
    }

    private fun playing(packageName: String) =
        SystemIngestionEvent.Media.PlaybackChanged(
            packageName = packageName,
            isPlaying = true,
            playbackState = 3, // STATE_PLAYING
            positionMs = 1000L,
            durationMs = 200000L,
            playbackSpeed = 1.0f,
            lastUpdateElapsedRealtime = 1L
        )

    private fun paused(packageName: String) =
        SystemIngestionEvent.Media.PlaybackChanged(
            packageName = packageName,
            isPlaying = false,
            playbackState = 2, // STATE_PAUSED
            positionMs = 1000L,
            durationMs = 200000L,
            playbackSpeed = 1.0f,
            lastUpdateElapsedRealtime = 1L
        )

    /**
     * One media activity serves every session, so the session that does not own
     * it must not drive it. Otherwise a second player reporting "paused" repaints
     * the first player's card: the expanded card shows a play button while the
     * compact slot keeps a playing waveform, which is what the field log showed.
     */
    @Test
    fun `another player's pause does not repaint the owning session's card`() = testScope.runTest {
        startArbiter()

        eventBus.emitEvent(playing("com.spotify.music"))
        testDispatcher.scheduler.advanceUntilIdle()
        eventBus.emitEvent(paused("com.google.android.bluetooth"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue(state is IslandState.Compact)
        val card = (state as IslandState.Compact).activity
        assertEquals("com.spotify.music", card.ownerPackage)
        assertTrue(
            "the owning session must still read as playing",
            card.compactTrailing is IslandSlotContent.AudioWaveform
        )
    }

    /** A second session that is actually playing does take the card over. */
    @Test
    fun `a newly playing session takes the media card over`() = testScope.runTest {
        startArbiter()

        eventBus.emitEvent(playing("com.spotify.music"))
        testDispatcher.scheduler.advanceUntilIdle()
        eventBus.emitEvent(playing("com.google.android.apps.youtube.music"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue(state is IslandState.Compact)
        assertEquals(
            "com.google.android.apps.youtube.music",
            (state as IslandState.Compact).activity.ownerPackage
        )
    }

    @Test
    fun `navigation turn-by-turn notification receives Rank 300`() = testScope.runTest {
        startArbiter()

        // Google Maps turn by turn
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "maps_nav",
                packageName = "com.google.android.apps.maps",
                notificationId = 101,
                category = "navigation",
                isOngoing = true,
                title = "In 200m, turn left",
                text = "Market Street",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = arbiter.islandState.value
        assertTrue("Navigation must be active", state is IslandState.Compact)
        val nav = (state as IslandState.Compact).activity
        assertEquals(PriorityRank.NAVIGATION_TURN_BY_TURN, nav.rank)
        assertEquals(ActivityKind.NAVIGATION, nav.kind)
    }

    @Test
    fun `priority ladder correctly orders Call over Timer over Navigation over Media`() = testScope.runTest {
        startArbiter()

        // 1. Post Media (Rank 400)
        eventBus.emitEvent(playing("com.spotify.music"))
        testDispatcher.scheduler.advanceUntilIdle()

        // 2. Post Navigation (Rank 300)
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "maps_nav",
                packageName = "com.google.android.apps.maps",
                notificationId = 101,
                category = "navigation",
                isOngoing = true,
                title = "Turn Right",
                text = "Oak St",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = null,
                showChronometer = false,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Navigation (300) > Media (400)
        var state = arbiter.islandState.value as IslandState.Minimal
        assertEquals("maps_nav", state.primary.id)
        assertEquals("activity_media_session", state.secondary.id)

        // 3. Post Timer (Rank 200)
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "timer_clock",
                packageName = "com.google.android.deskclock",
                notificationId = 102,
                category = "alarm",
                isOngoing = true,
                title = "Timer",
                text = "03:00",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = 180000L,
                showChronometer = true,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Timer (200) > Navigation (300) > Media (400)
        // Top 2: Primary is Timer (200), Secondary is Navigation (300)
        state = arbiter.islandState.value as IslandState.Minimal
        assertEquals("timer_clock", state.primary.id)
        assertEquals("maps_nav", state.secondary.id)

        // 4. Post Emergency / Call (Rank 100)
        eventBus.emitEvent(
            SystemIngestionEvent.Telecom.CallStateChanged(
                callId = "vip_call",
                status = SystemIngestionEvent.Telecom.CallStatus.ACTIVE_CONNECTED,
                callerName = "Alice",
                callerNumber = "+12345",
                avatarUri = null,
                avatarBitmap = null,
                durationSeconds = 12L
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Call (100) > Timer (200)
        state = arbiter.islandState.value as IslandState.Minimal
        assertEquals("activity_telecom_vip_call", state.primary.id)
        assertEquals("timer_clock", state.secondary.id)
    }

    @Test
    fun `higher priority timer preempts expanded lower priority media activity`() = testScope.runTest {
        startArbiter()

        // Start media and expand it
        eventBus.emitEvent(playing("com.spotify.music"))
        testDispatcher.scheduler.advanceUntilIdle()
        eventBus.emitCommand(IslandUserCommand.TapPrimary)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Media should be expanded", arbiter.islandState.value is IslandState.Expanded)

        // Incoming Timer (Rank 200) arrives
        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "timer_alarm",
                packageName = "com.google.android.deskclock",
                notificationId = 55,
                category = "alarm",
                isOngoing = true,
                title = "Timer Finished",
                text = "00:00",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = 0L,
                showChronometer = true,
                actions = emptyList(),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Media expansion must be collapsed; Timer (200) becomes primary
        val state = arbiter.islandState.value
        assertTrue("Timer must collapse expanded media", state !is IslandState.Expanded)
        assertTrue("State must be Minimal with Timer as primary", state is IslandState.Minimal)
        assertEquals("timer_alarm", (state as IslandState.Minimal).primary.id)
    }

    @Test
    fun `lower priority alert cannot displace active higher priority alert`() = testScope.runTest {
        startArbiter()

        // 1. Post High Priority Emergency Alert (Rank 100)
        val emergencyAlert = TransientAlert(
            id = "alert_emergency",
            title = "Emergency Warning",
            message = "Tornado Siren",
            leadingSlot = IslandSlotContent.Text("⚠️"),
            durationMillis = 5000L,
            rank = PriorityRank.EMERGENCY_OR_CALL
        )
        eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(emergencyAlert))
        testDispatcher.scheduler.advanceUntilIdle()

        var alertState = arbiter.islandState.value as IslandState.AlertPeek
        assertEquals("alert_emergency", alertState.alert.id)

        // 2. Post Battery alert (Rank 500) while emergency alert is active
        val batteryAlert = TransientAlert(
            id = "alert_battery",
            title = "Battery Full",
            message = "100%",
            leadingSlot = IslandSlotContent.Text("🔋"),
            durationMillis = 3000L,
            rank = PriorityRank.TRANSIENT_SYSTEM_ALERT
        )
        eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(batteryAlert))
        testDispatcher.scheduler.advanceUntilIdle()

        // Emergency alert must NOT be replaced by battery alert
        alertState = arbiter.islandState.value as IslandState.AlertPeek
        assertEquals("alert_emergency", alertState.alert.id)
    }

    @Test
    fun `keyguard locked closes inbox and suppresses opening inbox on lock screen`() = testScope.runTest {
        startArbiter()
        postNotification("msg_1", "Secret Message", "Private OTP: 1234")
        testDispatcher.scheduler.advanceTimeBy(4000L)
        testDispatcher.scheduler.advanceUntilIdle()

        // Open inbox while unlocked
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Inbox open while unlocked", arbiter.islandState.value is IslandState.NotificationInbox)

        // Lock keyguard
        eventBus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(isLocked = true))
        testDispatcher.scheduler.advanceUntilIdle()

        // Inbox must be closed immediately upon keyguard lock
        assertTrue("Keyguard lock must close inbox", arbiter.islandState.value !is IslandState.NotificationInbox)
        assertEquals(IslandState.Closed, arbiter.islandState.value)

        // User tries to open inbox on lockscreen
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Cannot open inbox on lockscreen", IslandState.Closed, arbiter.islandState.value)

        // Tap on closed island also must not open inbox on lockscreen
        eventBus.emitCommand(IslandUserCommand.TapPrimary)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Tap cannot open inbox on lockscreen", IslandState.Closed, arbiter.islandState.value)

        // Unlock keyguard
        eventBus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(isLocked = false))
        testDispatcher.scheduler.advanceUntilIdle()

        // Now user can open inbox
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Unlocked device allows opening inbox", arbiter.islandState.value is IslandState.NotificationInbox)
    }

    @Test
    fun `action execution callback runs without deadlock`() = testScope.runTest {
        startArbiter()
        var callbackInvoked = false

        val testAction = com.dynamicisland.engine.core.model.IslandAction(
            id = "test_action_btn",
            title = "Dismiss",
            actionCallback = {
                callbackInvoked = true
            }
        )

        eventBus.emitEvent(
            SystemIngestionEvent.NotificationEvent.Posted(
                key = "action_notif",
                packageName = "com.test.app",
                notificationId = 88,
                category = "alarm",
                isOngoing = true,
                title = "Alarm",
                text = "Ringing",
                subText = null,
                progressCurrent = null,
                progressMax = null,
                isProgressIndeterminate = false,
                chronometerBase = 0L,
                showChronometer = true,
                actions = listOf(testAction),
                contentIntent = null,
                statusBarNotification = null
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Execute action
        eventBus.emitCommand(IslandUserCommand.ExecuteAction("action_notif", "test_action_btn"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Action callback must be executed", callbackInvoked)
    }
    @Test fun `blocked application never enters inbox`() = testScope.runTest {
        val options=kotlinx.coroutines.flow.MutableStateFlow(com.dynamicisland.engine.settings.BehaviorOptions(blockedPackages=setOf("com.example.app")))
        arbiter=IslandPriorityArbiter(eventBus,backgroundScope,behavior=options)
        postNotification("blocked","Private","Secret body")
        assertEquals(0,arbiter.notificationCount.value)
        assertEquals(IslandState.Closed,arbiter.islandState.value)
    }
    @Test fun `privacy setting redacts existing and newly posted inbox contents`() = testScope.runTest {
        val options=kotlinx.coroutines.flow.MutableStateFlow(com.dynamicisland.engine.settings.BehaviorOptions())
        arbiter=IslandPriorityArbiter(eventBus,backgroundScope,behavior=options)
        postNotification("private","Secret title","Secret body")
        options.value=options.value.copy(hideNotificationText=true)
        eventBus.emitCommand(IslandUserCommand.OpenInbox)
        testDispatcher.scheduler.runCurrent()
        val row=(arbiter.islandState.value as IslandState.NotificationInbox).notifications.single()
        assertEquals("com.example.app",row.title);assertEquals(null,row.body)
    }

}
