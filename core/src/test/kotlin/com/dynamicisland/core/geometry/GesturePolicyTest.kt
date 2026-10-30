package com.dynamicisland.core.geometry
import org.junit.Assert.*
import org.junit.Test
class GesturePolicyTest {
 @Test fun `background music never steals a call or notification gesture`() {
  listOf(GestureAction.PLAY_PAUSE,GestureAction.NEXT_TRACK,GestureAction.PREVIOUS_TRACK).forEach {
   assertEquals(GestureAction.NONE,GesturePolicy.allowed(it,false,false))
   assertEquals(it,GesturePolicy.allowed(it,true,false))
  }
 }
 @Test fun `locked screen rejects every action`() {
  GestureAction.values().forEach{assertEquals(GestureAction.NONE,GesturePolicy.allowed(it,true,true))}
 }
}
