package com.dynamicisland.engine

import com.dynamicisland.engine.arbiter.IslandPriorityArbiter
import com.dynamicisland.engine.core.bus.*
import com.dynamicisland.engine.core.model.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExpansionRegressionTest {
    private fun media(playing: Boolean = true) = SystemIngestionEvent.Media.PlaybackChanged("player.app", playing, if(playing) 3 else 2, 0, 1000, 1f, 0)
    private fun notification() = SystemIngestionEvent.NotificationEvent.Posted("message", "chat.app", 1, null, false, "Message", "Body", null, null, null, false, null, false, actions=emptyList(), contentIntent=null, statusBarNotification=null)

    @Test fun `media opens pauses and closes without losing its card`() = runTest(UnconfinedTestDispatcher()) {
        val bus = IslandEventBus(); val arbiter = IslandPriorityArbiter(bus, backgroundScope)
        bus.emitEvent(media()); runCurrent()
        bus.emitCommand(IslandUserCommand.TapPrimary); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Expanded)
        bus.emitEvent(media(false)); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Expanded)
        bus.emitCommand(IslandUserCommand.CollapseExpanded); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Compact)
        bus.emitCommand(IslandUserCommand.TapPrimary); runCurrent()
        bus.emitCommand(IslandUserCommand.TapPrimary); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Compact)
    }
    @Test fun `notifications do not displace open music controls`() = runTest(UnconfinedTestDispatcher()) {
        val bus = IslandEventBus(); val arbiter = IslandPriorityArbiter(bus, backgroundScope)
        bus.emitEvent(media()); bus.emitCommand(IslandUserCommand.TapPrimary); runCurrent()
        bus.emitEvent(notification()); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Expanded)
        assertEquals(1, arbiter.notificationCount.value)
    }
    @Test fun `notification alert tap opens inbox and close retains its entries`() = runTest(UnconfinedTestDispatcher()) {
        val bus = IslandEventBus(); val arbiter = IslandPriorityArbiter(bus, backgroundScope)
        bus.emitEvent(notification()); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.AlertPeek)
        bus.emitCommand(IslandUserCommand.TapPrimary); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.NotificationInbox)
        bus.emitCommand(IslandUserCommand.CloseInbox); runCurrent()
        assertEquals(1, arbiter.notificationCount.value)
    }
    @Test fun `explicit secondary media selection opens alongside an existing timer`() = runTest(UnconfinedTestDispatcher()) {
        val bus = IslandEventBus(); val arbiter = IslandPriorityArbiter(bus, backgroundScope)
        bus.emitEvent(media())
        bus.emitEvent(notification().copy(key="timer_alarm", packageName="clock.app", category="alarm", isOngoing=true, title="Timer", text="00:30", chronometerBase=0L, showChronometer=true))
        runCurrent()
        bus.emitCommand(IslandUserCommand.DismissAlertPeek); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Minimal)
        bus.emitCommand(IslandUserCommand.TapSecondary); runCurrent()
        assertEquals(ActivityKind.MEDIA_SESSION, (arbiter.islandState.value as IslandState.Expanded).activity.kind)
        bus.emitEvent(SystemIngestionEvent.Telecom.CallStateChanged("call",SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,"Caller",null,null,null)); runCurrent()
        assertFalse(arbiter.islandState.value is IslandState.Expanded)
    }
    @Test fun `locking closes controls and unlocking does not reopen private content`() = runTest(UnconfinedTestDispatcher()) {
        val bus = IslandEventBus(); val arbiter = IslandPriorityArbiter(bus, backgroundScope)
        bus.emitEvent(media()); bus.emitCommand(IslandUserCommand.TapPrimary); runCurrent()
        bus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(true)); runCurrent()
        assertEquals(IslandState.Closed, arbiter.islandState.value)
        bus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(false)); runCurrent()
        assertTrue(arbiter.islandState.value is IslandState.Compact)
    }
}
