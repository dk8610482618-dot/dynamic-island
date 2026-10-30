package com.dynamicisland.engine.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.dynamicisland.engine.settings.BehaviorPreferences

/** Short, user-selected feedback; no repeating vibrations or background effects. */
object IslandHaptics {
    fun perform(context: Context) {
        val level = BehaviorPreferences.get(context).options.value.hapticIntensity
        if (level == 0) return
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(8L + level * 4L, listOf(1,40,100,180)[level])) }
    }
}
