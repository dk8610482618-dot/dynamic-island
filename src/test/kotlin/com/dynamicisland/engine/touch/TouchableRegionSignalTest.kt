package com.dynamicisland.engine.touch

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Validates the touchable region invalidation signal across component boundaries.
 */
class TouchableRegionSignalTest {

    @Before
    fun setUp() {
        TouchableRegionSignal.resetForTesting()
    }

    @Test
    fun `initial generation is zero`() {
        assertEquals(0L, TouchableRegionSignal.currentGeneration)
        assertEquals(0L, TouchableRegionSignal.generation.value)
    }

    @Test
    fun `requestRepublish increments generation monotonically`() {
        TouchableRegionSignal.requestRepublish()
        assertEquals(1L, TouchableRegionSignal.currentGeneration)

        TouchableRegionSignal.requestRepublish()
        assertEquals(2L, TouchableRegionSignal.currentGeneration)
    }

    @Test
    fun `resetForTesting restores generation to zero`() {
        TouchableRegionSignal.requestRepublish()
        TouchableRegionSignal.requestRepublish()
        assertEquals(2L, TouchableRegionSignal.currentGeneration)

        TouchableRegionSignal.resetForTesting()
        assertEquals(0L, TouchableRegionSignal.currentGeneration)
    }
}
