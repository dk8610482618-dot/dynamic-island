package com.dynamicisland.core.geometry

enum class GestureAction {
    OPEN_DASHBOARD,
    PLAY_PAUSE,
    NEXT_TRACK,
    PREVIOUS_TRACK,
    OPEN_SOURCE,
    CYCLE_ACTIVITY,
    DISMISS_ALERT,
    NONE
}

enum class InteractionMode {
    /** Apple Dynamic Island HIG parity: Single tap (<200ms) opens host app (widgetURL); Long-press (~450ms) expands island. */
    APPLE_PARITY,
    /** Classic Android customizable mode: Uses user-configured tapAction and holdAction bindings. */
    ANDROID_CLASSIC
}

enum class LandscapeBehavior {
    /** Condenses compact/minimal slots to 28dp circular glyphs (isDynamicIslandLimitedInWidth = true). */
    ADAPTIVE_LIMITED_WIDTH,
    /** Keeps standard compact capsule in landscape. */
    FULL_CAPSULE,
    /** Hides the island completely in landscape orientation. */
    HIDE
}

enum class DevicePosture {
    NORMAL_PORTRAIT,
    LANDSCAPE_LIMITED,
    FOLDABLE_HALF_OPENED,
    DUO_OUTER_RIGHT
}

enum class ActivityPriority { BALANCED, MEDIA_FIRST, NAVIGATION_FIRST }
enum class AppearancePreset { MONOCHROME, SOFT_ACCENT, HIGH_CONTRAST, LIQUID_GLASS }
enum class MotionPreset { NONE, GENTLE, RESPONSIVE }

/** Input routing depends on the visible activity, never background playback alone. */
object GesturePolicy {
    fun allowed(action: GestureAction, visibleMedia: Boolean, locked: Boolean): GestureAction = when {
        locked -> GestureAction.NONE
        action in setOf(GestureAction.PLAY_PAUSE, GestureAction.NEXT_TRACK, GestureAction.PREVIOUS_TRACK) && !visibleMedia -> GestureAction.NONE
        else -> action
    }

    /**
     * Resolves primary single-tap gesture according to [InteractionMode].
     * In [InteractionMode.APPLE_PARITY], tapping a Compact or Minimal activity deep-links directly into the host app,
     * or opens the dashboard/inbox when idle.
     */
    fun resolvePrimaryTap(
        mode: InteractionMode,
        configuredTap: GestureAction,
        hasActiveActivity: Boolean,
        visibleMedia: Boolean,
        locked: Boolean
    ): GestureAction {
        if (locked) return GestureAction.NONE
        return when (mode) {
            InteractionMode.APPLE_PARITY -> if (hasActiveActivity) GestureAction.OPEN_SOURCE else GestureAction.OPEN_DASHBOARD
            InteractionMode.ANDROID_CLASSIC -> allowed(configuredTap, visibleMedia, locked)
        }
    }

    /**
     * Resolves primary touch-and-hold (long-press) gesture according to [InteractionMode].
     * In [InteractionMode.APPLE_PARITY], long-pressing expands the island into the 4-region Expanded presentation.
     */
    fun resolvePrimaryHold(
        mode: InteractionMode,
        configuredHold: GestureAction,
        visibleMedia: Boolean,
        locked: Boolean
    ): GestureAction {
        if (locked) return GestureAction.NONE
        return when (mode) {
            InteractionMode.APPLE_PARITY -> GestureAction.OPEN_DASHBOARD
            InteractionMode.ANDROID_CLASSIC -> allowed(configuredHold, visibleMedia, locked)
        }
    }
}
