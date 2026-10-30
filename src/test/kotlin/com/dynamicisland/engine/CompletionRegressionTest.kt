package com.dynamicisland.engine

import android.app.PendingIntent
import com.dynamicisland.engine.core.model.*
import com.dynamicisland.engine.core.bus.*
import com.dynamicisland.engine.arbiter.IslandPriorityArbiter
import com.dynamicisland.engine.settings.BehaviorOptions
import com.dynamicisland.core.geometry.ActivityPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompletionRegressionTest {
    private fun activity(kind:ActivityKind,actions:List<IslandAction>):IslandActivity {
        val slot=IslandSlotContent.Symbol(IslandSymbol.TIMER)
        return IslandActivity("timer","clock.app","Countdown",kind=kind,rank=PriorityRank.LIVE_TIMER_STOPWATCH,
            compactLeading=slot,compactTrailing=slot,minimalContent=slot,expandedData=ExpandedRegionData(slot,slot),actions=actions)
    }
    @Test fun `arbitrary destructive and first timer actions cannot be cancellation`() {
        val arbitrary=IslandAction("first","Delete",isDestructive=true,pendingIntent=mock())
        assertNull(TimerActions.cancellation(activity(ActivityKind.LIVE_TIMER,listOf(arbitrary))))
        assertEquals(ActionSemantics.UNKNOWN,TimerActions.classify("Cancel"))
        assertEquals(ActionSemantics.UNKNOWN,TimerActions.classify("Stop music"))
        assertEquals(ActionSemantics.CANCEL_TIMER,TimerActions.classify("Cancel timer"))
    }
    @Test fun `only explicit timer cancellation is offered and it needs a live handle`() {
        val cancel=IslandAction("cancel","Cancel timer",semantics=ActionSemantics.CANCEL_TIMER,pendingIntent=mock())
        assertEquals(cancel,TimerActions.cancellation(activity(ActivityKind.LIVE_TIMER,listOf(cancel))))
        assertNull(TimerActions.cancellation(activity(ActivityKind.MEDIA_SESSION,listOf(cancel))))
        assertNull(TimerActions.cancellation(activity(ActivityKind.LIVE_TIMER,listOf(cancel.copy(pendingIntent=null)))))
    }
    @Test fun `notification groups use platform importance then recency without duplicates`() {
        val old=IslandNotification("old","chat.app","Old",postedAtMillis=1,appLabel="Chat",importance=4)
        val recent=old.copy(key="recent",title="Recent",postedAtMillis=100,importance=2)
        val other=old.copy(key="other",packageName="other.app",appLabel="Other",importance=3)
        val groups=NotificationGroups.group(listOf(recent,other,old,old))
        assertEquals(2,groups.size);assertEquals(listOf("old","recent"),groups.first().notifications.map{it.key})
    }
    @Test fun `notification access loss clears retained inbox media and alert content`() = runTest(UnconfinedTestDispatcher()) {
        val bus=IslandEventBus();val arbiter=IslandPriorityArbiter(bus,backgroundScope)
        bus.emitEvent(SystemIngestionEvent.Media.PlaybackChanged("player.app",true,3,0,1000,1f,0))
        bus.emitEvent(SystemIngestionEvent.NotificationEvent.Posted("secret","chat.app",1,null,false,"Secret","Private",null,null,null,false,null,false,actions=emptyList(),contentIntent=null,statusBarNotification=null))
        runCurrent()
        bus.emitEvent(SystemIngestionEvent.NotificationEvent.AccessRevoked);runCurrent()
        assertEquals(IslandState.Closed,arbiter.islandState.value)
        assertEquals(0,arbiter.notificationCount.value);assertNull(arbiter.mediaActivity.value)
    }
    @Test fun `media preference never preempts a call`() = runTest(UnconfinedTestDispatcher()) {
        val bus=IslandEventBus()
        val prefs=MutableStateFlow(BehaviorOptions(priority=ActivityPriority.MEDIA_FIRST))
        val arbiter=IslandPriorityArbiter(bus,backgroundScope,behavior=prefs)
        bus.emitEvent(SystemIngestionEvent.Media.PlaybackChanged("player.app",true,3,0,1000,1f,0))
        bus.emitEvent(SystemIngestionEvent.Telecom.CallStateChanged("call",SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,"Caller",null,null,null))
        runCurrent()
        val state=arbiter.islandState.value
        val first=when(state){is IslandState.Compact->state.activity;is IslandState.Minimal->state.primary;is IslandState.Expanded->state.activity;else->null}
        assertEquals(PriorityRank.EMERGENCY_OR_CALL,first?.rank)
    }
}
