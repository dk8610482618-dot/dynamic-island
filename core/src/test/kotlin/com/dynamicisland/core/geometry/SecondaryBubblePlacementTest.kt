package com.dynamicisland.core.geometry
import org.junit.Assert.*
import org.junit.Test
class SecondaryBubblePlacementTest {
 @Test fun `gap stays outside both windows`() {
  val p=CapsuleAnchor.Window(100,100,130,38,0f)
  val b=SecondaryBubblePlacement.resolve(p,400,800,34,8)!!
  assertEquals(238,b.left);assertEquals(p.left,100)
  assertTrue(b.left >= p.left+p.width+8)
 }
 @Test fun `collision uses opposite side and RTL reverses preference`() {
  val p=CapsuleAnchor.Window(250,100,130,38,0f)
  val b=SecondaryBubblePlacement.resolve(p,400,800,34,8)!!
  assertTrue(b.left+b.width < p.left)
  assertTrue(SecondaryBubblePlacement.resolve(CapsuleAnchor.Window(150,100,130,38,0f),500,800,34,8,true)!!.left<150)
 }
 @Test fun `too small viewport omits bubble without resizing primary`() {
  assertNull(SecondaryBubblePlacement.resolve(CapsuleAnchor.Window(0,0,130,38,0f),130,38,34,8))
 }
 @Test fun `landscape stays beside physical cutout axis`() {
  val p=CapsuleAnchor.Window(10,200,38,130,90f)
  val b=SecondaryBubblePlacement.resolve(p,800,400,34,8)!!
  assertEquals(338,b.top);assertEquals(90f,b.rotation,0f)
 }
}
