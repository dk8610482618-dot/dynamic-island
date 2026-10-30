package com.dynamicisland.engine.settings

import android.content.Context
import com.dynamicisland.core.geometry.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BehaviorOptions(
    val enabled: Boolean = false,
    val secondaryBubble: Boolean = false,
    val priority: ActivityPriority = ActivityPriority.BALANCED,
    val appearance: AppearancePreset = AppearancePreset.MONOCHROME,
    val motion: MotionPreset = MotionPreset.GENTLE,
    val reducedMotion: Boolean = false,
    val hapticIntensity: Int = 1,
    val tapAction: GestureAction = GestureAction.OPEN_DASHBOARD,
    val holdAction: GestureAction = GestureAction.OPEN_SOURCE,
    val swipeLeftAction: GestureAction = GestureAction.NEXT_TRACK,
    val swipeRightAction: GestureAction = GestureAction.PREVIOUS_TRACK,
    val startOnBoot: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val systemAlertsEnabled: Boolean = true,
    val hideInLandscape: Boolean = false,
    val clearSystemBars: Boolean = true,
    val showOnLockScreen: Boolean = true,
    val highRefreshRate: Boolean = true,
    val blockedPackages: Set<String> = emptySet(),
    val quietHours: Boolean = false,
    val quietStart: Int = 1320,
    val quietEnd: Int = 420,
    val hideNotificationText: Boolean = false,
    val peekDurationMillis: Long = 3500L,
    val interactionMode: InteractionMode = InteractionMode.ANDROID_CLASSIC,
    val landscapeBehavior: LandscapeBehavior = LandscapeBehavior.ADAPTIVE_LIMITED_WIDTH,
    val devicePosture: DevicePosture = DevicePosture.NORMAL_PORTRAIT,
    val enableKeylineTint: Boolean = true,
    val enableMetaballShader: Boolean = true,
    val maxConcurrentActivities: Int = 3,
    val idleMiniStatusBar: Boolean = false,
    val hideInFullscreenApps: Boolean = false
)

/** Shared by the host, service and boot receiver; a deliberate Stop survives process death. */
class BehaviorPreferences internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("island_behavior", Context.MODE_PRIVATE)
    init {
        if (!prefs.getBoolean("refresh_preference_v15", false)) {
            prefs.edit().putBoolean("high_refresh_rate", true)
                .putBoolean("clear_system_bars", true)
                .putBoolean("refresh_preference_v15", true).apply()
        }
    }
    private val _options = MutableStateFlow(
        BehaviorOptions(
            enabled = prefs.getBoolean("enabled", false),
            secondaryBubble = prefs.getBoolean("secondary_bubble", false),
            priority = enumValue(prefs.getString("priority", null), ActivityPriority.BALANCED),
            appearance = enumValue(prefs.getString("appearance", null), AppearancePreset.MONOCHROME),
            motion = enumValue(prefs.getString("motion", null), MotionPreset.GENTLE),
            reducedMotion = prefs.getBoolean("reduced_motion", false),
            hapticIntensity = prefs.getInt("haptic_intensity", 1).coerceIn(0, 3),
            tapAction = enumValue(prefs.getString("tap_action", null), GestureAction.OPEN_DASHBOARD),
            holdAction = enumValue(prefs.getString("hold_action", null), GestureAction.OPEN_SOURCE),
            swipeLeftAction = enumValue(prefs.getString("swipe_left", null), GestureAction.NEXT_TRACK),
            swipeRightAction = enumValue(prefs.getString("swipe_right", null), GestureAction.PREVIOUS_TRACK),
            startOnBoot = prefs.getBoolean("start_on_boot", false),
            notificationsEnabled = prefs.getBoolean("notifications", true),
            systemAlertsEnabled = prefs.getBoolean("system_alerts", true),
            hideInLandscape = prefs.getBoolean("hide_landscape", false),
            clearSystemBars = prefs.getBoolean("clear_system_bars", true),
            showOnLockScreen = prefs.getBoolean("show_on_lock_screen", true),
            highRefreshRate = prefs.getBoolean("high_refresh_rate", true),
            blockedPackages = prefs.getStringSet("blocked_packages", emptySet())!!.toSet(),
            quietHours = prefs.getBoolean("quiet_hours", false),
            quietStart = prefs.getInt("quiet_start", 1320).coerceIn(0, 1439),
            quietEnd = prefs.getInt("quiet_end", 420).coerceIn(0, 1439),
            hideNotificationText = prefs.getBoolean("hide_notification_text", false),
            peekDurationMillis = prefs.getLong("peek_duration", 3500L).coerceIn(1500L, 8000L),
            interactionMode = enumValue(prefs.getString("interaction_mode", null), InteractionMode.ANDROID_CLASSIC),
            landscapeBehavior = enumValue(prefs.getString("landscape_behavior", null), LandscapeBehavior.ADAPTIVE_LIMITED_WIDTH),
            devicePosture = enumValue(prefs.getString("device_posture", null), DevicePosture.NORMAL_PORTRAIT),
            enableKeylineTint = prefs.getBoolean("enable_keyline_tint", true),
            enableMetaballShader = prefs.getBoolean("enable_metaball_shader", true),
            maxConcurrentActivities = prefs.getInt("max_concurrent_activities", 3).coerceIn(2, 3),
            idleMiniStatusBar = prefs.getBoolean("idle_mini_status_bar", false),
            hideInFullscreenApps = prefs.getBoolean("hide_in_fullscreen_apps", false)
        )
    )
    val options: StateFlow<BehaviorOptions> = _options.asStateFlow()

    @Synchronized
    fun update(transform: (BehaviorOptions) -> BehaviorOptions) {
        val next = transform(_options.value).let {
            it.copy(
                peekDurationMillis = it.peekDurationMillis.coerceIn(1500L, 8000L),
                quietStart = it.quietStart.coerceIn(0, 1439),
                quietEnd = it.quietEnd.coerceIn(0, 1439),
                blockedPackages = it.blockedPackages.toSet(),
                hapticIntensity = it.hapticIntensity.coerceIn(0, 3),
                maxConcurrentActivities = it.maxConcurrentActivities.coerceIn(2, 3)
            )
        }
        prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putBoolean("secondary_bubble", next.secondaryBubble)
            .putString("priority", next.priority.name)
            .putString("appearance", next.appearance.name)
            .putString("motion", next.motion.name)
            .putBoolean("reduced_motion", next.reducedMotion)
            .putInt("haptic_intensity", next.hapticIntensity)
            .putString("tap_action", next.tapAction.name)
            .putString("hold_action", next.holdAction.name)
            .putString("swipe_left", next.swipeLeftAction.name)
            .putString("swipe_right", next.swipeRightAction.name)
            .putBoolean("start_on_boot", next.startOnBoot)
            .putBoolean("notifications", next.notificationsEnabled)
            .putBoolean("system_alerts", next.systemAlertsEnabled)
            .putBoolean("hide_landscape", next.hideInLandscape)
            .putBoolean("clear_system_bars", next.clearSystemBars)
            .putBoolean("show_on_lock_screen", next.showOnLockScreen)
            .putBoolean("high_refresh_rate", next.highRefreshRate)
            .putStringSet("blocked_packages", next.blockedPackages)
            .putBoolean("quiet_hours", next.quietHours)
            .putInt("quiet_start", next.quietStart)
            .putInt("quiet_end", next.quietEnd)
            .putBoolean("hide_notification_text", next.hideNotificationText)
            .putLong("peek_duration", next.peekDurationMillis)
            .putString("interaction_mode", next.interactionMode.name)
            .putString("landscape_behavior", next.landscapeBehavior.name)
            .putString("device_posture", next.devicePosture.name)
            .putBoolean("enable_keyline_tint", next.enableKeylineTint)
            .putBoolean("enable_metaball_shader", next.enableMetaballShader)
            .putInt("max_concurrent_activities", next.maxConcurrentActivities)
            .putBoolean("idle_mini_status_bar", next.idleMiniStatusBar)
            .putBoolean("hide_in_fullscreen_apps", next.hideInFullscreenApps)
            .apply()
        _options.value = next
    }

    companion object {
        @Volatile private var instance: BehaviorPreferences? = null
        fun get(context: Context): BehaviorPreferences = instance ?: synchronized(this) {
            instance ?: BehaviorPreferences(context).also { instance = it }
        }
    }
}

private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback
