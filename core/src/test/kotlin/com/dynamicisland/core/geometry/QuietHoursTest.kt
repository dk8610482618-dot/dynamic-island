package com.dynamicisland.core.geometry
import org.junit.Assert.*
import org.junit.Test
class QuietHoursTest {
    @Test fun `overnight range includes midnight and excludes morning boundary`() {
        assertTrue(QuietHours.contains(0,1320,420));assertTrue(QuietHours.contains(1320,1320,420))
        assertFalse(QuietHours.contains(420,1320,420));assertFalse(QuietHours.contains(800,1320,420))
    }
    @Test fun `same day and disabled ranges`() {
        assertTrue(QuietHours.contains(600,540,720));assertFalse(QuietHours.contains(720,540,720))
        assertFalse(QuietHours.contains(600,600,600))
    }
    @Test(expected=IllegalArgumentException::class) fun `invalid time rejected`() { QuietHours.contains(1440,0,1) }
}
