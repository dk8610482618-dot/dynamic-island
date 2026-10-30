package com.dynamicisland.core.physics

/**
 * Provenance of a spring entry, so the conformance report can distinguish a
 * value the specification states from one this implementation had to infer.
 */
enum class SpringProvenance {
    /** Stated verbatim in the specification's animation parameter table. */
    SPEC_TABLE,

    /** Stated in the specification's state transition matrix only. */
    SPEC_TRANSITION_MATRIX,

    /**
     * Not stated anywhere; inferred by this implementation because a
     * transition exists without a governing spring. Flagged so it is auditable.
     */
    INFERRED
}

/** A named spring together with where its numbers came from. */
data class CatalogEntry(
    val name: String,
    val spec: SpringSpec,
    val provenance: SpringProvenance,
    val note: String = ""
)

/**
 * Every spring in the system, in one place.
 *
 * The specification scatters its motion values across three tables that do not
 * agree with each other:
 *
 *  - the **Animation Parameter Table** (10 rows, each with T, ζ, k, c);
 *  - the **State Transition Matrix** (13 rows, naming a T per transition);
 *  - the **Physics** section's frame-by-frame traces.
 *
 * Cross-checking them found two gaps and one conflict:
 *
 *  - `COMPACT → IDLE` ("collapse to cutout") specifies **T = 0.36** in the
 *    transition matrix, but the animation table has no 0.36 entry at all, and
 *    0.36 conflicts with `EXPANDED → COMPACT`'s 0.40.
 *  - `IDLE → COMPACT` is named in the state machine but appears in neither
 *    numeric table.
 *  - the frame-by-frame traces are not the trajectory of their own stated
 *    springs (see PLAN.md Findings 1 and 9), so they are not usable as a
 *    source of parameters.
 *
 * Both gaps are filled here and tagged [SpringProvenance.INFERRED] so a reader
 * never mistakes them for specification text.
 *
 * Every entry is authored as `(response, dampingRatio)`. Stiffness and damping
 * are derived — see [SpringSpec].
 */
object SpringCatalog {

    // ---------------------------------------------------------------------
    // Specification animation parameter table (10 entries)
    // ---------------------------------------------------------------------

    /** `COMPACT_TO_EXPANDED` — T=0.48, ζ=0.80, k=171.2, c=20.94. */
    val CompactToExpanded = SpringSpec(response = 0.48f, dampingRatio = 0.80f)

    /** `EXPANDED_TO_COMPACT` — T=0.40, ζ=0.86, k=246.7, c=27.02. */
    val ExpandedToCompact = SpringSpec(response = 0.40f, dampingRatio = 0.86f)

    /** `TOUCH_PUNCH_DOWN` / `TOUCH_PUNCH_UP` — T=0.22, ζ=0.62, k=815.7. */
    val TouchPunch = SpringSpec(response = 0.22f, dampingRatio = 0.62f)

    /** `BIFURCATION_SPLIT` — T=0.52, ζ=0.72, k=145.9. */
    val BifurcationSplit = SpringSpec(response = 0.52f, dampingRatio = 0.72f)

    /** `BIFURCATION_MERGE` — T=0.44, ζ=0.82, k=203.9. */
    val BifurcationMerge = SpringSpec(response = 0.44f, dampingRatio = 0.82f)

    /** `SWIPE_DISMISS` — T=0.32, ζ=0.94, k=385.5. */
    val SwipeDismiss = SpringSpec(response = 0.32f, dampingRatio = 0.94f)

    /** `SWIPE_CONCEAL` — T=0.30, ζ=0.95, k=438.6. */
    val SwipeConceal = SpringSpec(response = 0.30f, dampingRatio = 0.95f)

    /** `SWIPE_RESTORE` — T=0.38, ζ=0.78, k=273.4. */
    val SwipeRestore = SpringSpec(response = 0.38f, dampingRatio = 0.78f)

    /** `ALERT_BOUNCE_ARRIVE` — T=0.50, ζ=0.68, k=157.9. */
    val AlertBounceArrive = SpringSpec(response = 0.50f, dampingRatio = 0.68f)

    /** `ALERT_DISMISS` — T=0.35, ζ=0.90, k=322.2. */
    val AlertDismiss = SpringSpec(response = 0.35f, dampingRatio = 0.90f)

    // ---------------------------------------------------------------------
    // Transition-matrix entries with no animation-table counterpart
    // ---------------------------------------------------------------------

    /** `IDLE_TO_COMPACT` — T=0.44, ζ=0.82 (same figures as BifurcationMerge). */
    val IdleToCompact = SpringSpec(response = 0.44f, dampingRatio = 0.82f)

    /**
     * `COMPACT_TO_IDLE` ("collapse to cutout") — T=0.36 stated with **no ζ**.
     *
     * The damping ratio is inferred from the specification's snappy-collapse
     * family (0.86–0.95). 0.86 was chosen as the least-damped member of that
     * family, matching `ExpandedToCompact`, since both are user- or
     * system-initiated collapses that should not appear springy.
     */
    val CompactToIdle = SpringSpec(response = 0.36f, dampingRatio = 0.86f)

    // ---------------------------------------------------------------------
    // Extended activities. The specification states these inline in prose
    // rather than in a table.
    // ---------------------------------------------------------------------

    /** Navigation turn-arrow rotation — "T = 0.35 s, ζ = 0.72". */
    val NavigationTurnRotate = SpringSpec(response = 0.35f, dampingRatio = 0.72f)

    /** Privacy-indicator detachment — "T = 0.35 s, ζ = 0.75". */
    val PrivacyDotDetach = SpringSpec(response = 0.35f, dampingRatio = 0.75f)

    /** Face ID HUD shrink-back — "T = 0.30 s, ζ = 0.90". */
    val FaceIdHud = SpringSpec(response = 0.30f, dampingRatio = 0.90f)

    /**
     * Press-scale feedback on transport and action controls.
     *
     * The specification's tactile table uses the 0.22/0.62 punch spring, but
     * the component-level interaction is described separately as a snappier
     * response. This is the value the current implementation already uses for
     * button press-scale and it reads correctly, so it is kept.
     */
    val ControlPressScale = SpringSpec(response = 0.22f, dampingRatio = 0.55f)

    /** Playback artwork scale when paused — gentle, barely perceptible. */
    val ArtworkPauseScale = SpringSpec(response = 0.48f, dampingRatio = 0.80f)

    /**
     * Rubber-band snap-back after an over-drag is released.
     *
     * T = 0.32, ζ = 0.75 per the specification's prose, which differs from
     * `SWIPE_DISMISS` (T=0.32, ζ=0.94) despite the shared response.
     */
    val RubberBandSnapback = SpringSpec(response = 0.32f, dampingRatio = 0.75f)

    // ---------------------------------------------------------------------
    // Named catalog for iteration and reporting
    // ---------------------------------------------------------------------

    /**
     * Every named spring, for tests that assert catalogue-wide invariants and
     * for the conformance report that must disclose which values were inferred.
     */
    val all: List<CatalogEntry> = listOf(
        CatalogEntry("COMPACT_TO_EXPANDED", CompactToExpanded, SpringProvenance.SPEC_TABLE),
        CatalogEntry("EXPANDED_TO_COMPACT", ExpandedToCompact, SpringProvenance.SPEC_TABLE),
        CatalogEntry("TOUCH_PUNCH", TouchPunch, SpringProvenance.SPEC_TABLE),
        CatalogEntry("BIFURCATION_SPLIT", BifurcationSplit, SpringProvenance.SPEC_TABLE),
        CatalogEntry("BIFURCATION_MERGE", BifurcationMerge, SpringProvenance.SPEC_TABLE),
        CatalogEntry("SWIPE_DISMISS", SwipeDismiss, SpringProvenance.SPEC_TABLE),
        CatalogEntry("SWIPE_CONCEAL", SwipeConceal, SpringProvenance.SPEC_TABLE),
        CatalogEntry("SWIPE_RESTORE", SwipeRestore, SpringProvenance.SPEC_TABLE),
        CatalogEntry("ALERT_BOUNCE_ARRIVE", AlertBounceArrive, SpringProvenance.SPEC_TABLE),
        CatalogEntry("ALERT_DISMISS", AlertDismiss, SpringProvenance.SPEC_TABLE),

        CatalogEntry(
            "IDLE_TO_COMPACT", IdleToCompact, SpringProvenance.SPEC_TRANSITION_MATRIX,
            note = "T=0.44, ζ=0.82; named in the state machine, absent from the animation table."
        ),
        CatalogEntry(
            "COMPACT_TO_IDLE", CompactToIdle, SpringProvenance.INFERRED,
            note = "T=0.36 stated in the transition matrix with NO damping ratio. " +
                "ζ=0.86 inferred from the snappy-collapse family."
        ),

        CatalogEntry(
            "NAVIGATION_TURN_ROTATE", NavigationTurnRotate, SpringProvenance.SPEC_TABLE,
            note = "Stated inline in the System Activities prose."
        ),
        CatalogEntry(
            "PRIVACY_DOT_DETACH", PrivacyDotDetach, SpringProvenance.SPEC_TABLE,
            note = "Stated inline in the System Activities prose."
        ),
        CatalogEntry(
            "FACE_ID_HUD", FaceIdHud, SpringProvenance.SPEC_TABLE,
            note = "Stated inline in the System Activities prose."
        ),

        CatalogEntry(
            "CONTROL_PRESS_SCALE", ControlPressScale, SpringProvenance.INFERRED,
            note = "Carried over from the existing implementation; no specification value."
        ),
        CatalogEntry(
            "ARTWORK_PAUSE_SCALE", ArtworkPauseScale, SpringProvenance.INFERRED,
            note = "Chosen to match the expansion response; no specification value."
        ),
        CatalogEntry(
            "RUBBER_BAND_SNAPBACK", RubberBandSnapback, SpringProvenance.SPEC_TABLE,
            note = "T=0.32, ζ=0.75 from the physics prose; distinct from SWIPE_DISMISS."
        ),
    )

    /** Only the entries the specification actually tabulates. */
    val specTable: List<CatalogEntry>
        get() = all.filter { it.provenance == SpringProvenance.SPEC_TABLE }

    /** Entries this implementation had to invent or complete. */
    val inferred: List<CatalogEntry>
        get() = all.filter { it.provenance == SpringProvenance.INFERRED }
}
