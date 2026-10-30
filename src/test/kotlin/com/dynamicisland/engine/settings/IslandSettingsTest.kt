package com.dynamicisland.engine.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.dynamicisland.core.geometry.IslandPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class IslandSettingsTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockHandler: Handler

    private var registeredListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val prefsStore = mutableMapOf<String, Float>()

    @Before
    fun setUp() {
        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()
        mockHandler = mock()

        prefsStore.clear()

        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockPrefs)

        whenever(mockPrefs.registerOnSharedPreferenceChangeListener(any())).thenAnswer { invocation ->
            registeredListener = invocation.getArgument(0)
            null
        }

        whenever(mockPrefs.getFloat(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<Float>(1)
            prefsStore[key] ?: default
        }

        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putFloat(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<Float>(1)
            prefsStore[key] = value
            mockEditor
        }
        whenever(mockEditor.apply()).thenAnswer {
            null
        }
    }

    private fun createSettings(): IslandSettings {
        return IslandSettings(context = mockContext, mainHandler = mockHandler)
    }

    @Test
    fun `in-memory StateFlow emissions update with 0ms latency`() {
        val settings = createSettings()

        assertEquals(0f, settings.offset.value.offsetXDp, 0.001f)
        assertEquals(0f, settings.offset.value.offsetYDp, 0.001f)

        settings.setOffsetX(42f)
        assertEquals("Offset X must update immediately in memory", 42f, settings.offset.value.offsetXDp, 0.001f)

        settings.setOffsetY(-15f)
        assertEquals("Offset Y must update immediately in memory", -15f, settings.offset.value.offsetYDp, 0.001f)

        settings.setWidthScale(1.25f)
        assertEquals("Width scale must update immediately in memory", 1.25f, settings.widthScale.value, 0.001f)

        settings.setHeightScale(0.85f)
        assertEquals("Height scale must update immediately in memory", 0.85f, settings.heightScale.value, 0.001f)
    }

    @Test
    fun `slider dragging debounces disk write by 150ms`() {
        val settings = createSettings()

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        // Rapid slider drag events (e.g. 50 ticks)
        for (i in 1..50) {
            settings.setOffsetX(i.toFloat())
        }

        // Must remove prior callbacks on every tick to debounce
        verify(mockHandler, times(50)).removeCallbacks(any())
        // Must schedule persist with 150ms delay
        verify(mockHandler, times(50)).postDelayed(runnableCaptor.capture(), eq(IslandSettings.DEBOUNCE_DELAY_MS))

        // SharedPreferences edit should NOT have been applied yet (debounced)
        verify(mockEditor, never()).apply()

        // Simulate 150ms timer expiration
        val runnable = runnableCaptor.value
        runnable.run()

        // Now disk write executes once
        verify(mockEditor, times(1)).apply()
        assertEquals(50f, prefsStore["placement_offset_x_dp"])
    }

    @Test
    fun `flush immediately writes pending state to disk without waiting for debounce`() {
        val settings = createSettings()

        settings.setOffsetX(35f)
        verify(mockEditor, never()).apply()

        settings.flush()
        verify(mockEditor, times(1)).apply()
        assertEquals(35f, prefsStore["placement_offset_x_dp"])
    }

    @Test
    fun `atomic offset operations preserve independent axes`() {
        val settings = createSettings()

        settings.setOffsetX(25f)
        settings.setOffsetY(50f)

        assertEquals(25f, settings.offset.value.offsetXDp, 0.001f)
        assertEquals(50f, settings.offset.value.offsetYDp, 0.001f)

        settings.nudge(5f, -10f)
        assertEquals(30f, settings.offset.value.offsetXDp, 0.001f)
        assertEquals(40f, settings.offset.value.offsetYDp, 0.001f)
    }

    @Test
    fun `self-written preference changes do not overwrite newer in-memory dragging state`() {
        val settings = createSettings()
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        // User drags to 20
        settings.setOffsetX(20f)
        verify(mockHandler).postDelayed(runnableCaptor.capture(), eq(150L))

        // Timer fires, writing 20 to prefs
        runnableCaptor.value.run()
        verify(mockEditor, times(1)).apply()

        // User continues dragging to 25 before prefs listener fires
        settings.setOffsetX(25f)
        assertEquals(25f, settings.offset.value.offsetXDp, 0.001f)

        // Prefs listener notifies with KEY_OFFSET_X for the earlier write (which wrote 20)
        registeredListener?.onSharedPreferenceChanged(mockPrefs, "placement_offset_x_dp")

        // In-memory value MUST NOT rubber-band back to 20; it must stay 25!
        assertEquals("StateFlow must not be clobbered by stale echo", 25f, settings.offset.value.offsetXDp, 0.001f)
    }

    @Test
    fun `reset returns to default and flushes immediately`() {
        val settings = createSettings()

        settings.setOffset(IslandPlacement.UserOffset(50f, 60f))
        settings.setWidthScale(1.3f)
        settings.setHeightScale(1.2f)
        assertTrue(settings.isCustomised)

        settings.reset()

        assertTrue(settings.offset.value.isDefault)
        assertEquals(1f, settings.widthScale.value, 0.001f)
        assertEquals(1f, settings.heightScale.value, 0.001f)
        assertFalse(settings.isCustomised)

        verify(mockEditor, times(1)).apply()
        assertEquals(0f, prefsStore["placement_offset_x_dp"])
        assertEquals(0f, prefsStore["placement_offset_y_dp"])
        assertEquals(1f, prefsStore["island_width_scale"])
        assertEquals(1f, prefsStore["island_height_scale"])
    }
}
