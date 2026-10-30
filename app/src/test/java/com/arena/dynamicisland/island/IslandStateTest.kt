package com.arena.dynamicisland.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interaction model is a pure reducer, so the whole island can be tested without a device:
 * no Compose runtime, no coroutines, no clock.
 */
class IslandStateTest {

    @Test
    fun `no activities collapses to the cutout`() {
        assertEquals(IslandPresentation.Collapsed, IslandState().presentation())
    }

    @Test
    fun `one activity shows as a compact pill sized by that activity`() {
        val presentation = IslandState().post(IslandActivity.Music()).presentation()

        assertTrue(presentation is IslandPresentation.Compact)
        val compact = presentation as IslandPresentation.Compact
        assertEquals(IslandActivity.Music.ID, compact.activity.id)
        assertEquals(IslandActivity.Music().compactWidth, presentation.size.width)
        assertEquals(IslandSize.Height, presentation.size.height)
    }

    @Test
    fun `expanding opens the highest priority activity`() {
        val presentation = IslandState()
            .post(IslandActivity.Music())
            .post(IslandActivity.Navigation())
            .expand()
            .presentation()

        assertTrue(presentation is IslandPresentation.Expanded)
        val expanded = presentation as IslandPresentation.Expanded
        assertEquals(IslandActivity.Navigation.ID, expanded.activity.id)
        assertEquals(IslandSize.ExpandedWidth, presentation.size.width)
        assertEquals(IslandActivity.Navigation().expandedHeight, presentation.size.height)
    }

    @Test
    fun `collapsing returns to the pill but keeps activities`() {
        val state = IslandState().post(IslandActivity.Music()).expand().collapse()

        assertEquals(1, state.activities.size)
        assertTrue(state.presentation() is IslandPresentation.Compact)
    }

    @Test
    fun `two peer activities split around the camera`() {
        val presentation = IslandState()
            .post(IslandActivity.Music())
            .post(IslandActivity.Call(isIncoming = false))
            .presentation()

        assertTrue(presentation is IslandPresentation.Split)
        val split = presentation as IslandPresentation.Split
        // Call outranks music, so it takes the leading slot.
        assertEquals(IslandActivity.Call.ID, split.leading.id)
        assertEquals(IslandActivity.Music.ID, split.trailing.id)
        assertEquals(IslandSize.SplitWidth, presentation.size.width)
    }

    @Test
    fun `low priority activities never split the pill`() {
        val presentation = IslandState()
            .post(IslandActivity.Charging())
            .post(IslandActivity.Timer())
            .presentation()

        assertTrue(presentation is IslandPresentation.Compact)
        assertEquals(IslandActivity.Timer.ID, (presentation as IslandPresentation.Compact).activity.id)
    }

    @Test
    fun `posting the same id updates data and preserves the expanded card`() {
        val state = IslandState()
            .post(IslandActivity.Timer(totalSeconds = 600, remainingSeconds = 600))
            .expand()
            .post(IslandActivity.Timer(totalSeconds = 600, remainingSeconds = 599))

        assertEquals(1, state.activities.size)
        assertEquals(IslandActivity.Timer.ID, state.expandedId)

        val presentation = state.presentation()
        assertTrue(presentation is IslandPresentation.Expanded)
        val timer = (presentation as IslandPresentation.Expanded).activity as IslandActivity.Timer
        assertEquals(599, timer.remainingSeconds)
    }

    @Test
    fun `dismissing the expanded activity closes the card`() {
        val state = IslandState()
            .post(IslandActivity.Music())
            .post(IslandActivity.Timer())
            .expand(IslandActivity.Timer.ID)
            .dismiss(IslandActivity.Timer.ID)

        assertNull(state.expandedId)
        assertEquals(1, state.activities.size)
        assertTrue(state.presentation() is IslandPresentation.Compact)
    }

    @Test
    fun `expanding an unknown id is a no-op`() {
        val state = IslandState().post(IslandActivity.Music())
        assertEquals(state, state.expand(IslandActivity.Call.ID))
    }

    @Test
    fun `ordering breaks priority ties by id`() {
        val state = IslandState().post(IslandActivity.Charging()).post(IslandActivity.Unlock)

        assertEquals(listOf(IslandActivity.Charging.ID, IslandActivity.Unlock.ID), state.ordered.map { it.id })
    }

    @Test
    fun `content key is stable while data ticks`() {
        val first = IslandState().post(IslandActivity.Music(elapsedSeconds = 10)).expand(IslandActivity.Music.ID)
        val second = first.post(IslandActivity.Music(elapsedSeconds = 11))

        assertEquals(first.presentation().contentKey, second.presentation().contentKey)
    }

    @Test
    fun `call initials handle single and multiple names`() {
        assertEquals("AI", IslandActivity.Call(name = "Ananya Iyer").initials)
        assertEquals("A", IslandActivity.Call(name = "Ananya").initials)
        assertEquals("MV", IslandActivity.Call(name = "  matt van horn ").initials)
    }

    @Test
    fun `progress is clamped`() {
        assertEquals(1f, IslandActivity.Music(elapsedSeconds = 999, durationSeconds = 100).progress, 0.0001f)
        assertEquals(0f, IslandActivity.Music(elapsedSeconds = -5, durationSeconds = 100).progress, 0.0001f)
        assertEquals(0f, IslandActivity.Music(durationSeconds = 0).progress, 0.0001f)
    }

    @Test
    fun `corner radius is a pill until the card opens`() {
        val compact = IslandState().post(IslandActivity.Music()).presentation()
        val expanded = IslandState().post(IslandActivity.Music()).expand().presentation()

        assertEquals(IslandSize.CollapsedCorner, compact.cornerRadius)
        assertEquals(IslandSize.ExpandedCorner, expanded.cornerRadius)
    }
}
