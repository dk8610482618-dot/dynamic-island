package com.dynamicisland.core.call

/**
 * Gap **G8** — SharePlay, Live Captions and Live Translation state in the
 * expanded call card.
 *
 * Doc 2: "Expanding the active call exposes quick-action toggles for microphone
 * mute, speaker/audio routing, FaceTime screen sharing or remote control
 * requests, **Live Captions and Live Translation status**, and active **SharePlay
 * co-watching or collaborative document sessions**."
 *
 * The base had zero occurrences of any of these. They are also the features in
 * the research that are most tempting to fake, because none of them is observable
 * for *another application's* call:
 *
 *  * **SharePlay** is an Apple ActivityKit/GroupFaceTime construct. Android has
 *    no equivalent system API, and a third-party overlay cannot know whether a
 *    VoIP app started a co-watch session.
 *  * **Live Translation** has no public Android API to query or toggle.
 *  * **Live Caption** exists on Android (API 30+) but there is no public API to
 *    read whether a caption session is currently running for another app. What
 *    *is* readable is the user's system captioning preference via
 *    `CaptioningManager.isEnabled`.
 *
 * So, consistent with the camera-dot decision, every row is **capability-gated
 * and carries its origin**. A row is rendered only when the source application
 * actually advertised the feature in its call notification/session extras, or
 * when a real system signal exists. [FeatureOrigin.UNAVAILABLE] rows are never
 * shown as active — the UI hides them or greys them out with an explanation.
 *
 * Pure JVM. The engine extracts the extras; this module decides what may be
 * claimed.
 */
object CallSessionFeatures {

    /** The features doc 2 lists for the expanded call card. */
    enum class CallFeature {
        MICROPHONE_MUTE,
        SPEAKER_ROUTING,
        SCREEN_SHARE,
        REMOTE_CONTROL_REQUEST,
        LIVE_CAPTIONS,
        LIVE_TRANSLATION,
        SHAREPLAY_SESSION,
        HOLD
    }

    /** Where a feature's state came from. Determines whether it may be shown. */
    enum class FeatureOrigin {
        /**
         * Read from the device: Telecom call state, AudioManager, or
         * CaptioningManager. Trustworthy and may be shown as live status.
         */
        DEVICE_STATE,

        /**
         * The calling application declared it in notification or media-session
         * extras. Trustworthy only as "the app says so" — shown with the app's
         * own wording.
         */
        SOURCE_ADVERTISED,

        /**
         * Nothing observable. Must not be rendered as an active state. Either
         * hidden, or shown disabled with an explanation.
         */
        UNAVAILABLE
    }

    /** Why a feature is unavailable, for the capability panel. */
    enum class UnavailableReason {
        /** Not the default dialer, so Telecom control is not granted. */
        NOT_DEFAULT_DIALER,
        /** Source app did not advertise it. */
        NOT_ADVERTISED_BY_SOURCE,
        /** No public Android API exists to observe it. */
        NO_PUBLIC_API,
        /** Requires an API level above this device. */
        API_LEVEL_TOO_LOW,
        /** No active call to attach the feature to. */
        NO_ACTIVE_CALL
    }

    data class FeatureState(
        val feature: CallFeature,
        val origin: FeatureOrigin,
        val isActive: Boolean = false,
        /** True when the user may toggle it from the island. */
        val isActionable: Boolean = false,
        val unavailableReason: UnavailableReason? = null,
        /** Source-supplied label, e.g. "Sharing with 2 people". */
        val sourceLabel: String? = null
    ) {
        /**
         * The single rule that keeps this honest: a feature may only be *drawn as
         * active* when its state came from somewhere real.
         */
        val mayRenderAsActive: Boolean
            get() = isActive && origin != FeatureOrigin.UNAVAILABLE

        val mayRenderAtAll: Boolean
            get() = origin != FeatureOrigin.UNAVAILABLE || unavailableReason == null
    }

    /** Everything the engine observed about one call. */
    data class CallObservation(
        val isCallActive: Boolean,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isOnHold: Boolean = false,
        /** True when this app holds `ROLE_DIALER` and may control the call. */
        val isControllable: Boolean = false,
        val apiLevel: Int = 34,
        /** `CaptioningManager.isEnabled` — the user's system caption preference. */
        val systemCaptioningEnabled: Boolean = false,
        /**
         * Keys the source app put in its call notification/session extras. Only
         * features present here may be shown as source-advertised.
         */
        val advertisedExtraKeys: Set<String> = emptySet()
    )

    /**
     * Extra keys we recognise. A source app that wants its co-watch session shown
     * in the island advertises it explicitly; we never guess from the app name or
     * the call duration.
     */
    object ExtraKeys {
        const val LIVE_CAPTIONS_ACTIVE = "island.feature.live_captions"
        const val LIVE_TRANSLATION_ACTIVE = "island.feature.live_translation"
        const val SHAREPLAY_SESSION_ACTIVE = "island.feature.shared_session"
        const val SCREEN_SHARE_ACTIVE = "island.feature.screen_share"
        const val REMOTE_CONTROL_REQUESTED = "island.feature.remote_control"
        const val SESSION_LABEL = "island.feature.session_label"
    }

    /** Live Caption arrived with the Android 11 captioning framework. */
    const val MIN_API_FOR_LIVE_CAPTION: Int = 30

    /**
     * Resolves every feature row for the expanded call card.
     *
     * @param sessionLabel optional source-supplied wording used for the
     *   SharePlay/translation rows so the island repeats the app's own phrasing
     *   rather than inventing "FaceTime".
     */
    fun resolve(observation: CallObservation, sessionLabel: String? = null): List<FeatureState> {
        if (!observation.isCallActive) {
            return CallFeature.values().map {
                FeatureState(it, FeatureOrigin.UNAVAILABLE, unavailableReason = UnavailableReason.NO_ACTIVE_CALL)
            }
        }

        val advertised = observation.advertisedExtraKeys
        val controllable = observation.isControllable

        return listOf(
            // Mute and hold come from Telecom/AudioManager: real device state.
            FeatureState(
                feature = CallFeature.MICROPHONE_MUTE,
                origin = FeatureOrigin.DEVICE_STATE,
                isActive = observation.isMuted,
                // Toggling someone else's call needs the dialer role.
                isActionable = controllable
            ),
            FeatureState(
                feature = CallFeature.SPEAKER_ROUTING,
                origin = FeatureOrigin.DEVICE_STATE,
                isActive = observation.isSpeakerOn,
                isActionable = controllable
            ),
            FeatureState(
                feature = CallFeature.HOLD,
                origin = FeatureOrigin.DEVICE_STATE,
                isActive = observation.isOnHold,
                isActionable = controllable
            ),

            // Screen share: only the calling app knows. Advertise-gated.
            if (ExtraKeys.SCREEN_SHARE_ACTIVE in advertised) {
                FeatureState(
                    feature = CallFeature.SCREEN_SHARE,
                    origin = FeatureOrigin.SOURCE_ADVERTISED,
                    isActive = true,
                    isActionable = false,
                    sourceLabel = sessionLabel
                )
            } else {
                FeatureState(
                    feature = CallFeature.SCREEN_SHARE,
                    origin = FeatureOrigin.UNAVAILABLE,
                    unavailableReason = UnavailableReason.NOT_ADVERTISED_BY_SOURCE
                )
            },

            if (ExtraKeys.REMOTE_CONTROL_REQUESTED in advertised) {
                FeatureState(
                    feature = CallFeature.REMOTE_CONTROL_REQUEST,
                    origin = FeatureOrigin.SOURCE_ADVERTISED,
                    isActive = true,
                    isActionable = false,
                    sourceLabel = sessionLabel
                )
            } else {
                FeatureState(
                    feature = CallFeature.REMOTE_CONTROL_REQUEST,
                    origin = FeatureOrigin.UNAVAILABLE,
                    unavailableReason = UnavailableReason.NOT_ADVERTISED_BY_SOURCE
                )
            },

            // Live Captions: the system preference is real and readable; whether
            // a caption session is running for this call is only knowable if the
            // source says so. Combine both rather than claiming either alone.
            resolveLiveCaptions(observation),

            // Live Translation: no public Android API at all.
            if (ExtraKeys.LIVE_TRANSLATION_ACTIVE in advertised) {
                FeatureState(
                    feature = CallFeature.LIVE_TRANSLATION,
                    origin = FeatureOrigin.SOURCE_ADVERTISED,
                    isActive = true,
                    isActionable = false,
                    sourceLabel = sessionLabel
                )
            } else {
                FeatureState(
                    feature = CallFeature.LIVE_TRANSLATION,
                    origin = FeatureOrigin.UNAVAILABLE,
                    unavailableReason = UnavailableReason.NO_PUBLIC_API
                )
            },

            // SharePlay has no Android equivalent; treat it as a co-watch session
            // the source app may advertise, and never label it "SharePlay".
            if (ExtraKeys.SHAREPLAY_SESSION_ACTIVE in advertised) {
                FeatureState(
                    feature = CallFeature.SHAREPLAY_SESSION,
                    origin = FeatureOrigin.SOURCE_ADVERTISED,
                    isActive = true,
                    isActionable = false,
                    sourceLabel = sessionLabel
                )
            } else {
                FeatureState(
                    feature = CallFeature.SHAREPLAY_SESSION,
                    origin = FeatureOrigin.UNAVAILABLE,
                    unavailableReason = UnavailableReason.NOT_ADVERTISED_BY_SOURCE
                )
            }
        )
    }

    private fun resolveLiveCaptions(observation: CallObservation): FeatureState {
        val advertised = ExtraKeys.LIVE_CAPTIONS_ACTIVE in observation.advertisedExtraKeys
        return when {
            advertised -> FeatureState(
                feature = CallFeature.LIVE_CAPTIONS,
                origin = FeatureOrigin.SOURCE_ADVERTISED,
                isActive = true,
                isActionable = false
            )

            observation.apiLevel < MIN_API_FOR_LIVE_CAPTION -> FeatureState(
                feature = CallFeature.LIVE_CAPTIONS,
                origin = FeatureOrigin.UNAVAILABLE,
                unavailableReason = UnavailableReason.API_LEVEL_TOO_LOW
            )

            // The system captioning preference is genuinely readable. Report it as
            // "enabled in system settings", not as "captions are running".
            observation.systemCaptioningEnabled -> FeatureState(
                feature = CallFeature.LIVE_CAPTIONS,
                origin = FeatureOrigin.DEVICE_STATE,
                isActive = false,
                isActionable = false,
                sourceLabel = "System captions on"
            )

            else -> FeatureState(
                feature = CallFeature.LIVE_CAPTIONS,
                origin = FeatureOrigin.UNAVAILABLE,
                unavailableReason = UnavailableReason.NOT_ADVERTISED_BY_SOURCE
            )
        }
    }

    /** Rows safe to draw in the expanded card, in a stable order. */
    fun visibleFeatures(states: List<FeatureState>): List<FeatureState> =
        states.filter { it.mayRenderAtAll }

    /** Rows that may be shown lit up. */
    fun activeFeatures(states: List<FeatureState>): List<FeatureState> =
        states.filter { it.mayRenderAsActive }

    /**
     * Accessibility wording for the expanded call pane. Doc 1 requires an
     * `expandedPaneTitle` and `stateDescription` per activity, independent of the
     * visual string, and forbids announcing every tick — so the duration is
     * summarised, not streamed.
     */
    fun paneTitle(callerLabel: String?, serviceType: String?): String {
        val caller = callerLabel?.takeIf { it.isNotBlank() } ?: "Unknown caller"
        val service = serviceType?.takeIf { it.isNotBlank() }
        return if (service == null) "Active call with $caller" else "Active $service call with $caller"
    }
}
