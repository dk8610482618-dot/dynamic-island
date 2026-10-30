package com.dynamicisland.engine.settings

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class BehaviorPreferencesTest {
    private val values = mutableMapOf<String, Any>()
    private val context = mock<Context>()
    private val prefs = mock<SharedPreferences>()
    private val editor = mock<SharedPreferences.Editor>()

    private fun preferences(): BehaviorPreferences {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
        }
        whenever(prefs.getLong(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1)
        }
        whenever(prefs.getInt(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1)
        }
        whenever(prefs.getStringSet(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (values[it.getArgument<String>(0)] as? Set<String>) ?: it.getArgument<Set<String>>(1)
        }
        whenever(editor.putInt(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Int>(1); editor
        }
        whenever(editor.putStringSet(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Set<String>>(1).toSet(); editor
        }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
        }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer {
            it.getArgument<String?>(1)?.let { value -> values[it.getArgument<String>(0)] = value }; editor
        }
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); editor
        }
        whenever(editor.putLong(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Long>(1); editor
        }
        return BehaviorPreferences(context)
    }

    @Test fun `high refresh is enabled on upgrade and subsequent opt out persists`() {
        values["high_refresh_rate"] = false
        values["clear_system_bars"] = false
        val upgraded = preferences()
        assertTrue(upgraded.options.value.highRefreshRate)
        assertTrue(upgraded.options.value.clearSystemBars)
        upgraded.update { it.copy(highRefreshRate = false, clearSystemBars = false) }
        assertFalse(preferences().options.value.highRefreshRate)
        assertFalse(preferences().options.value.clearSystemBars)
    }

    @Test fun `explicit stop survives recreation and cannot be undone by boot preference`() {
        val first = preferences()
        first.update { it.copy(enabled = true, startOnBoot = true) }
        first.update { it.copy(enabled = false) }
        val restored = preferences().options.value
        assertFalse(restored.enabled)
        assertTrue(restored.startOnBoot)
    }

    @Test fun `behaviour and alert duration persist across recreation`() {
        val first = preferences()
        first.update { it.copy(notificationsEnabled = false, hideInLandscape = true, peekDurationMillis = 5000) }
        val restored = preferences().options.value
        assertFalse(restored.notificationsEnabled)
        assertTrue(restored.hideInLandscape)
        assertEquals(5000L, restored.peekDurationMillis)
    }

    @Test fun `alert duration is bounded on both writes and reads`() {
        val first = preferences()
        first.update { it.copy(peekDurationMillis = Long.MAX_VALUE) }
        assertEquals(8000L, first.options.value.peekDurationMillis)
        values["peek_duration"] = -1L
        assertEquals(1500L, preferences().options.value.peekDurationMillis)
    }
    @Test fun `privacy quiet hours and blocked apps survive recreation`() {
        val first=preferences()
        first.update { it.copy(hideNotificationText=true,quietHours=true,quietStart=1300,quietEnd=420,blockedPackages=setOf("com.example.app")) }
        val next=preferences().options.value
        assertTrue(next.hideNotificationText);assertTrue(next.quietHours)
        assertEquals(1300,next.quietStart);assertEquals(420,next.quietEnd)
        assertEquals(setOf("com.example.app"),next.blockedPackages)
    }

    @Test fun `experience preferences persist and feedback stays bounded`() {
        val first=preferences()
        first.update {it.copy(secondaryBubble=true,priority=com.dynamicisland.core.geometry.ActivityPriority.MEDIA_FIRST,
            appearance=com.dynamicisland.core.geometry.AppearancePreset.HIGH_CONTRAST,reducedMotion=true,hapticIntensity=999,
            holdAction=com.dynamicisland.core.geometry.GestureAction.NONE)}
        val restored=preferences().options.value
        assertTrue(restored.secondaryBubble);assertTrue(restored.reducedMotion);assertEquals(3,restored.hapticIntensity)
        assertEquals(com.dynamicisland.core.geometry.ActivityPriority.MEDIA_FIRST,restored.priority)
        assertEquals(com.dynamicisland.core.geometry.GestureAction.NONE,restored.holdAction)
    }

}
