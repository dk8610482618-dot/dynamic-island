package com.dynamicisland.core.media

/**
 * Gap **G9** — the output routing selector.
 *
 * Doc 2 wants the expanded media card to expose "a dedicated AirPlay or Bluetooth
 * audio output routing selector". The base rendered `audioRouteLabel` as text and
 * offered no way to change it.
 *
 * The constraint that shapes this module is real and easy to get wrong: **Android
 * does not let a third-party app change another application's media output
 * device.** What exists is:
 *
 *  * `AudioManager.availableCommunicationDevices` / `setCommunicationDevice` —
 *    API 31+, and for **communication** (call) audio only.
 *  * `AudioManager.setSpeakerphoneOn` — deprecated on API 31+, calls only.
 *  * `BluetoothA2dp` device connection — hidden/system API, not grantable.
 *  * `MediaRouter` — selects routes for playback *the calling app owns*.
 *
 * So for a call the island can genuinely change the route; for someone else's
 * music it cannot, and pretending otherwise produces a button that silently does
 * nothing. [RouteOption.isSelectable] encodes that distinction, and
 * [fallbackAction] tells the UI to hand off to the source app's own routing sheet
 * or to system settings instead of faking a toggle.
 *
 * Pure JVM: route kinds are plain enums, API level is a parameter.
 */
object AudioRoutePolicy {

    /** Physical/logical output kinds the island can name. */
    enum class AudioRouteKind {
        BUILTIN_SPEAKER,
        BUILTIN_EARPIECE,
        WIRED_HEADSET,
        WIRED_HEADPHONES,
        USB_AUDIO,
        BLUETOOTH_A2DP,
        BLUETOOTH_LE_AUDIO,
        BLUETOOTH_SCO,
        HEARING_AID,
        CAST,
        UNKNOWN
    }

    /** What audio the island is routing. The permissions differ by kind. */
    enum class RouteContext {
        /** A phone/VoIP call. Communication audio — routable on API 31+. */
        CALL,
        /** Media played by another app. Not routable by us. */
        MEDIA
    }

    /** Where the UI should send the user when we cannot change the route. */
    enum class FallbackAction {
        /** Change it ourselves. */
        NONE,
        /** Open the source app's own output sheet via its deep link. */
        SOURCE_APP_SHEET,
        /** Open Android's connected-device / media output settings. */
        SYSTEM_SETTINGS
    }

    data class RouteOption(
        val kind: AudioRouteKind,
        val label: String,
        val isAvailable: Boolean,
        val isSelected: Boolean,
        /**
         * True only when tapping this row will actually change the output. The UI
         * renders non-selectable rows as informational, not as dead buttons.
         */
        val isSelectable: Boolean
    )

    data class RoutingCapability(
        val context: RouteContext,
        val canChangeRoute: Boolean,
        val apiLevel: Int,
        val fallback: FallbackAction,
        /** Explanation for the capability panel when [canChangeRoute] is false. */
        val limitation: String? = null
    )

    /** `setCommunicationDevice` and `availableCommunicationDevices` are API 31. */
    const val MIN_API_FOR_COMMUNICATION_DEVICE: Int = 31

    /**
     * Decides what the island may promise for the current context.
     *
     * @param isControllableCall for [RouteContext.CALL], whether this app holds
     *   `ROLE_DIALER` or the call is otherwise ours to route. Without it even
     *   API 31+ cannot change a third-party call's route.
     */
    fun resolveCapability(
        context: RouteContext,
        apiLevel: Int,
        isControllableCall: Boolean = false,
        sourceAppExposesRoutingSheet: Boolean = false
    ): RoutingCapability = when (context) {
        RouteContext.CALL -> when {
            apiLevel >= MIN_API_FOR_COMMUNICATION_DEVICE && isControllableCall -> RoutingCapability(
                context = context,
                canChangeRoute = true,
                apiLevel = apiLevel,
                fallback = FallbackAction.NONE
            )

            isControllableCall -> RoutingCapability(
                context = context,
                canChangeRoute = false,
                apiLevel = apiLevel,
                fallback = FallbackAction.SYSTEM_SETTINGS,
                limitation = "Changing call audio needs Android 12 (API 31) or newer."
            )

            else -> RoutingCapability(
                context = context,
                canChangeRoute = false,
                apiLevel = apiLevel,
                fallback = FallbackAction.SYSTEM_SETTINGS,
                limitation = "Only the app handling the call can change its audio route."
            )
        }

        RouteContext.MEDIA -> RoutingCapability(
            context = context,
            // Android gives no third-party path to reroute another app's media.
            canChangeRoute = false,
            apiLevel = apiLevel,
            fallback = if (sourceAppExposesRoutingSheet) {
                FallbackAction.SOURCE_APP_SHEET
            } else {
                FallbackAction.SYSTEM_SETTINGS
            },
            limitation = "Android does not let one app change another app's media output. " +
                "This opens the player's own output picker instead."
        )
    }

    /**
     * Builds the selector rows. Available devices come from the engine; the
     * selected device is whatever is currently routed.
     *
     * Rows are ordered speaker → wired → bluetooth → cast, matching the system
     * media output sheet so the ordering is familiar.
     */
    fun buildOptions(
        availableKinds: Set<AudioRouteKind>,
        selectedKind: AudioRouteKind?,
        capability: RoutingCapability
    ): List<RouteOption> {
        val order = listOf(
            AudioRouteKind.BUILTIN_SPEAKER,
            AudioRouteKind.BUILTIN_EARPIECE,
            AudioRouteKind.WIRED_HEADPHONES,
            AudioRouteKind.WIRED_HEADSET,
            AudioRouteKind.USB_AUDIO,
            AudioRouteKind.BLUETOOTH_A2DP,
            AudioRouteKind.BLUETOOTH_LE_AUDIO,
            AudioRouteKind.BLUETOOTH_SCO,
            AudioRouteKind.HEARING_AID,
            AudioRouteKind.CAST,
            AudioRouteKind.UNKNOWN
        )
        return order
            .filter { it in availableKinds }
            .map { kind ->
                RouteOption(
                    kind = kind,
                    label = labelFor(kind),
                    isAvailable = true,
                    isSelected = kind == selectedKind,
                    // Never offer a selectable row for the device already in use.
                    isSelectable = capability.canChangeRoute && kind != selectedKind
                )
            }
    }

    /** User-facing wording. Avoids Apple-only product names. */
    fun labelFor(kind: AudioRouteKind): String = when (kind) {
        AudioRouteKind.BUILTIN_SPEAKER -> "Speaker"
        AudioRouteKind.BUILTIN_EARPIECE -> "Earpiece"
        AudioRouteKind.WIRED_HEADSET -> "Wired headset"
        AudioRouteKind.WIRED_HEADPHONES -> "Headphones"
        AudioRouteKind.USB_AUDIO -> "USB audio"
        AudioRouteKind.BLUETOOTH_A2DP -> "Bluetooth"
        AudioRouteKind.BLUETOOTH_LE_AUDIO -> "Bluetooth LE Audio"
        AudioRouteKind.BLUETOOTH_SCO -> "Bluetooth (call)"
        AudioRouteKind.HEARING_AID -> "Hearing aid"
        AudioRouteKind.CAST -> "Cast"
        AudioRouteKind.UNKNOWN -> "Other output"
    }

    /**
     * Maps an `AudioDeviceInfo.type` constant to a route kind. Kept here as plain
     * ints so the mapping is unit-testable without an Android device; the engine
     * passes `AudioDeviceInfo.TYPE_*` values straight through.
     */
    fun fromAudioDeviceType(type: Int): AudioRouteKind = when (type) {
        2 -> AudioRouteKind.BUILTIN_SPEAKER            // TYPE_BUILTIN_SPEAKER
        1 -> AudioRouteKind.BUILTIN_EARPIECE           // TYPE_BUILTIN_EARPIECE
        4 -> AudioRouteKind.WIRED_HEADSET              // TYPE_WIRED_HEADSET
        3 -> AudioRouteKind.WIRED_HEADPHONES           // TYPE_WIRED_HEADPHONES
        11 -> AudioRouteKind.USB_AUDIO                 // TYPE_USB_DEVICE
        22 -> AudioRouteKind.USB_AUDIO                 // TYPE_USB_HEADSET
        8 -> AudioRouteKind.BLUETOOTH_A2DP             // TYPE_BLUETOOTH_A2DP
        26 -> AudioRouteKind.BLUETOOTH_LE_AUDIO        // TYPE_BLE_HEADSET
        27 -> AudioRouteKind.BLUETOOTH_LE_AUDIO        // TYPE_BLE_SPEAKER
        7 -> AudioRouteKind.BLUETOOTH_SCO              // TYPE_BLUETOOTH_SCO
        23 -> AudioRouteKind.HEARING_AID               // TYPE_HEARING_AID
        9 -> AudioRouteKind.CAST                       // TYPE_REMOTE
        else -> AudioRouteKind.UNKNOWN
    }

    /**
     * Accessibility label for the selector row. States the limitation rather than
     * implying a control that will not respond, which is what doc 1 means by
     * accessibility being a first-class presenter output.
     */
    fun accessibilityLabel(option: RouteOption): String = when {
        option.isSelected -> "${option.label}, current output"
        option.isSelectable -> "Switch output to ${option.label}"
        else -> "${option.label}, available. Open the player's output picker to change."
    }
}
