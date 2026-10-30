package com.dynamicisland.core.privacy

/**
 * Gap **G3** — contextual app suppression and full-screen auto-hide.
 *
 * Doc 2 requires an app-by-app configuration matrix ("Hide Notch in Apps") that
 * chooses which applications may surface content in the island, *and* automatic
 * hiding of the overlay inside specific foreground applications such as
 * full-screen games or video players.
 *
 * The imported base had neither. It declared `hideInFullscreenApps` in
 * `BehaviorOptions`, persisted it to SharedPreferences and then never read it
 * again — a dead setting that appeared in no UI. Its `blockedPackages` set
 * filters *notifications*, which is a different question from *overlay
 * visibility*: blocking a package stops its messages reaching the island, but
 * the idle pill still drew over that app's full-screen video.
 *
 * The two concerns are therefore modelled as two separate sets with two
 * separate effects, and [Decision] reports which one fired so the capability
 * panel can explain the behaviour instead of the user guessing.
 *
 * Pure JVM: no `android.*`, no process inspection. The engine observes the
 * foreground state and passes it in.
 */
object AppSuppressionPolicy {

    /** Per-package visibility rule chosen by the user. */
    enum class PackageRule {
        /** Normal behaviour: the island draws over this app. */
        ALLOW,

        /**
         * Hide the idle pill and non-critical content while this app is in the
         * foreground, but still surface an incoming call. This is the sensible
         * default for games and video players.
         */
        HIDE_NON_CRITICAL,

        /** Hide the overlay completely while this app is in the foreground. */
        HIDE_ALWAYS
    }

    /** What the engine observed about the foreground application. */
    data class ForegroundAppState(
        val packageName: String,
        /** System bars are hidden — immersive or immersive-sticky. */
        val isImmersiveFullscreen: Boolean = false,
        /**
         * True when the foreground app holds an active video surface. Set from a
         * real signal only (a `PlaybackState` with video, or a visible
         * `SurfaceView` reported by the window); never inferred from the package
         * name, because a guess here hides the island for no reason.
         */
        val isPlayingVideo: Boolean = false,
        val isScreenOn: Boolean = true,
        /** True while the device is on the keyguard. */
        val isLocked: Boolean = false
    )

    /** Why the overlay was suppressed, for diagnostics. */
    enum class SuppressionReason {
        /** The user chose [PackageRule.HIDE_ALWAYS] for this package. */
        USER_RULE_HIDE_ALWAYS,

        /** The user chose [PackageRule.HIDE_NON_CRITICAL] for this package. */
        USER_RULE_HIDE_NON_CRITICAL,

        /** Global full-screen auto-hide fired on an immersive foreground app. */
        GLOBAL_FULLSCREEN_IMMERSIVE,

        /** Global full-screen auto-hide fired on detected video playback. */
        GLOBAL_FULLSCREEN_VIDEO
    }

    /**
     * @param hideNothing the overlay is fully removed, idle pill included.
     * @param hideNonCritical the idle pill and routine content are removed but a
     *   critical alert (incoming call, alarm) still surfaces.
     */
    data class Decision(
        val hideNothing: Boolean = false,
        val hideNonCritical: Boolean = false,
        val reason: SuppressionReason? = null
    ) {
        val suppressesOverlay: Boolean get() = hideNothing || hideNonCritical

        companion object {
            val Visible = Decision()
        }
    }

    /**
     * Resolves overlay visibility for the current foreground application.
     *
     * @param packageRules per-package matrix from settings.
     * @param autoHideInFullscreen the global `hideInFullscreenApps` switch.
     * @param pendingAlertIsCritical true when the content currently wanting to
     *   show is an incoming call, alarm or other critical alert. Critical alerts
     *   survive [PackageRule.HIDE_NON_CRITICAL] and full-screen auto-hide, but
     *   never [PackageRule.HIDE_ALWAYS] — an explicit user rule wins.
     */
    fun resolve(
        foreground: ForegroundAppState?,
        packageRules: Map<String, PackageRule>,
        autoHideInFullscreen: Boolean,
        pendingAlertIsCritical: Boolean = false,
        islandEnabled: Boolean = true
    ): Decision {
        if (!islandEnabled || foreground == null || !foreground.isScreenOn) return Decision.Visible

        val rule = packageRules[foreground.packageName]

        // An explicit per-package rule always wins over the global heuristic.
        when (rule) {
            PackageRule.HIDE_ALWAYS -> return Decision(
                hideNothing = true,
                reason = SuppressionReason.USER_RULE_HIDE_ALWAYS
            )

            PackageRule.HIDE_NON_CRITICAL -> return if (pendingAlertIsCritical) {
                Decision.Visible
            } else {
                Decision(
                    hideNonCritical = true,
                    reason = SuppressionReason.USER_RULE_HIDE_NON_CRITICAL
                )
            }

            PackageRule.ALLOW, null -> Unit
        }

        if (!autoHideInFullscreen) return Decision.Visible

        // Immersive full-screen is the reliable signal: games and video players
        // hide the system bars, and the island drawing over them is exactly what
        // the user asked to avoid.
        if (foreground.isImmersiveFullscreen) {
            return if (pendingAlertIsCritical) {
                Decision.Visible
            } else {
                Decision(
                    hideNonCritical = true,
                    reason = SuppressionReason.GLOBAL_FULLSCREEN_IMMERSIVE
                )
            }
        }

        if (foreground.isPlayingVideo) {
            return if (pendingAlertIsCritical) {
                Decision.Visible
            } else {
                Decision(
                    hideNonCritical = true,
                    reason = SuppressionReason.GLOBAL_FULLSCREEN_VIDEO
                )
            }
        }

        return Decision.Visible
    }

    /**
     * Whether the island's *idle* pill may draw. The idle pill is the first thing
     * to be suppressed: it carries no information, so hiding it costs nothing.
     */
    fun mayShowIdlePill(decision: Decision): Boolean = !decision.suppressesOverlay

    /**
     * Whether routine activity content (media, navigation, downloads, peeks) may
     * draw under [decision].
     */
    fun mayShowRoutineContent(decision: Decision): Boolean = !decision.suppressesOverlay

    /**
     * Whether a critical alert may draw. Only an explicit
     * [PackageRule.HIDE_ALWAYS] stops it.
     */
    fun mayShowCriticalAlert(decision: Decision): Boolean = !decision.hideNothing

    /**
     * Normalises a rule map: drops blank package names and collapses duplicates,
     * so a malformed import from the settings JSON cannot inject an empty key
     * that would match nothing and silently disable suppression.
     */
    fun sanitize(rules: Map<String, PackageRule>): Map<String, PackageRule> =
        rules.entries
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value }
}
