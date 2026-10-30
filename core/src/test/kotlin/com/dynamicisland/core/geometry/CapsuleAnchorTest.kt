package com.dynamicisland.core.geometry
import org.junit.Assert.*
import org.junit.Test
class CapsuleAnchorTest {
    @Test fun `portrait clears status bar for usable controls`() {
        val p=CapsuleAnchor.resolve(1080,2400,3f,PixelRect(510,0,570,90),statusBarTop=96)
        assertEquals(390,p.width); assertEquals(114,p.height); assertTrue(p.top >= 96)
    }
    @Test fun `rotation follows left camera rather than screen top center`() {
        val p=CapsuleAnchor.resolve(2400,1080,3f,PixelRect(0,510,90,570),safeLeft=96)
        assertEquals(90f,p.rotation,0f);assertEquals(114,p.width);assertEquals(390,p.height)
        assertEquals(540f,p.top+p.height/2f,1f);assertTrue(p.left < 200)
    }
    @Test fun `reverse landscape follows right camera`() {
        val p=CapsuleAnchor.resolve(2400,1080,3f,PixelRect(2310,510,2400,570),safeRight=96)
        assertEquals(-90f,p.rotation,0f);assertTrue(p.left > 2000)
    }
    @Test fun `large offsets and small screens cannot strand the window`() {
        val p=CapsuleAnchor.resolve(100,80,3f,null,offsetX=9999f,offsetY=-9999f)
        assertTrue(p.left >= 0 && p.top >= 0);assertTrue(p.left+p.width<=100);assertTrue(p.top+p.height<=80)
    }
    @Test fun `exact overlap preserves cutout center when clearance disabled`() {
        val p=CapsuleAnchor.resolve(1080,2400,3f,PixelRect(510,30,570,90),clearSystemBars=false)
        assertEquals(540f,p.left+p.width/2f,1f);assertEquals(60f,p.top+p.height/2f,1f)
    }
    @Test fun `negative vertical offset cannot defeat status bar clearance`() {
        val p=CapsuleAnchor.resolve(1080,2400,3f,PixelRect(510,0,570,90), offsetY=-50f,statusBarTop=96)
        assertTrue(p.top >= 108)
    }
    @Test fun `side offsets cannot defeat left or right system gesture clearance`() {
        val left=CapsuleAnchor.resolve(2400,1080,3f,PixelRect(0,510,90,570), offsetX=-100f,safeLeft=96)
        val right=CapsuleAnchor.resolve(2400,1080,3f,PixelRect(2310,510,2400,570), offsetX=100f,safeRight=96)
        assertTrue(left.left >= 108)
        assertTrue(right.left + right.width <= 2400 - 108)
    }
    @Test fun `clearance falls back to available space on a tiny screen`() {
        val p=CapsuleAnchor.resolve(100,80,3f,PixelRect(45,0,55,10),statusBarTop=200,offsetY=-100f)
        assertEquals(0,p.top)
        assertTrue(p.top+p.height<=80)
    }
    @Test fun `bottom camera and navigation area remain clear after offsets`() {
        val p=CapsuleAnchor.resolve(1080,2400,3f,PixelRect(510,2310,570,2400),offsetY=100f,safeBottom=96)
        assertEquals(180f,p.rotation,0f)
        assertTrue(p.top+p.height<=2400-108)
    }
}
