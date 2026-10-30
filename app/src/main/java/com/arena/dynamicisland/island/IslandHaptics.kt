package com.arena.dynamicisland.island

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Hardware haptic actuator profiles synchronized with Dynamic Island spring milestones.
 */
object IslandHaptics {
    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Fired at t = 200ms when long-press expansion triggers. */
    fun performLongPressExpand(context: Context) {
        val vib = vibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vib.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
        ) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 140)
                .compose()
            vib.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(18L, 180))
        }
    }

    /** Fired on single-tap deep link or control button press. */
    fun performSelectionTick(context: Context) {
        val vib = vibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
        ) {
            vib.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1.0f)
                    .compose(),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(10L, 110))
        }
    }

    /** Ascending dual-primitive confirmation signature for Face ID authentication. */
    fun performFaceIdSuccess(context: Context) {
        val vib = vibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vib.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
            )
        ) {
            vib.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 1.0f, 80)
                    .compose(),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 14, 60, 22), intArrayOf(0, 140, 0, 220), -1))
        }
    }
}
