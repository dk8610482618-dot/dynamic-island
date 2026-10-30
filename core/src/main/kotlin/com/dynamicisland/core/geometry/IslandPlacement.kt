package com.dynamicisland.core.geometry

import kotlin.math.abs

/**
 * Where the island sits on screen.
 *
 * ### Why this exists
 *
 * The specification assumes the island is locked to the hardware cutout: "the
 * island is aligned symmetrically along the horizontal axis, with its center
 * matching the horizontal midpoint of the display." That is the correct
 * *default* — the entire illusion depends on the software surface blending into
 * the physical camera aperture.
 *
 * But it is a bad default on real hardware:
 *
 *  - many Android devices have **off-centre** cutouts (the `island_test` AVD
 *    reports its hole at x = 552.5 px against a screen centre of 540.0 px);
 *  - a corner punch-hole cannot be centred at all without the island floating
 *    over unrelated screen content;
 *  - the status-bar height varies enough that the specification's fixed
 *    11 pt top margin lands under the clock on some ROMs;
 *  - some users simply want the island lower or shifted, and there is no good
 *    argument for refusing them.
 *
 * So placement is resolved in three layers, most specific winning:
 *
 * ```
 * AUTO  →  cutout-derived default, or the specification's canonical figure
 * NUDGE →  AUTO plus a device-specific correction  (calibration)
 * MANUAL → an explicit user-chosen offset          (this class)
 * ```
 *
 * Manual placement never *replaces* the automatic value; it is expressed as an
 * offset from it, so a device-specific correction stays meaningful and a user
 * who resets the offset gets the correct default back.
 */
object IslandPlacement {

    /** Placement strategy for one axis. */
    enum class Mode {
        /**
         * Derive from the physical cutout where one exists, else fall back to
         * the specification's canonical figure. The correct default.
         */
        AUTO,

        /**
         * Offset from [AUTO] by a user-chosen amount. The island still tracks
         * cutout changes (rotation, fold, display switch) but sits where the
         * user put it.
         */
        OFFSET
    }

    /**
     * The user's placement preference.
     *
     * Both offsets are in **dp** and measured from the automatic position, so
     * positive `y` moves the island **down** the screen (matching Android's
     * y-down coordinate system, and matching what a user expects from dragging
     * it downward).
     *
     * @param offsetXDp horizontal offset from the automatic centre. Positive
     *   moves right.
     * @param offsetYDp vertical offset from the automatic top. Positive moves
     *   down.
     */
    data class UserOffset(
        val offsetXDp: Float = 0f,
        val offsetYDp: Float = 0f
    ) {
        val isDefault: Boolean get() = offsetXDp == 0f && offsetYDp == 0f

        val modeX: Mode get() = if (offsetXDp == 0f) Mode.AUTO else Mode.OFFSET
        val modeY: Mode get() = if (offsetYDp == 0f) Mode.AUTO else Mode.OFFSET

        /** Clamps both offsets into the permitted travel envelope, guarding against non-finite values. */
        fun clampedTo(envelope: TravelEnvelope): UserOffset {
            val safeX = when {
                offsetXDp.isNaN() -> 0f
                offsetXDp == Float.POSITIVE_INFINITY -> envelope.horizontalDp
                offsetXDp == Float.NEGATIVE_INFINITY -> -envelope.horizontalDp
                else -> offsetXDp
            }
            val safeY = when {
                offsetYDp.isNaN() -> 0f
                offsetYDp == Float.POSITIVE_INFINITY -> envelope.downDp
                offsetYDp == Float.NEGATIVE_INFINITY -> -envelope.upDp
                else -> offsetYDp
            }
            return UserOffset(
                offsetXDp = safeX.coerceIn(-envelope.horizontalDp, envelope.horizontalDp),
                offsetYDp = safeY.coerceIn(-envelope.upDp, envelope.downDp)
            )
        }
    }

    /**
     * How far the island may be moved before it stops being usable.
     *
     * There is no specification guidance here — the specification assumes a
     * fixed position — so these are engineering limits derived from what the
     * island must keep doing:
     *
     *  - horizontally it must stay **fully on screen** for the current island
     *    size;
     *  - downward it must stay clear of the **navigation bar / gesture area**,
     *    so the expanded card's controls remain tappable;
     *  - upward it may deliberately travel **past the top edge**. See
     *    [UP_TRAVEL_DP] for why that is the honest limit rather than zero.
     *
     * `upDp` and `downDp` are relative to the island's **automatic** top, and
     * that matters: the automatic position is already below the status bar, so
     * a downward budget measured from the screen top would let the two stack and
     * push the island past the navigation bar. See [forAutomaticPosition].
     */
    data class TravelEnvelope(
        val horizontalDp: Float,
        val upDp: Float,
        val downDp: Float
    ) {
        companion object {

            /**
             * Upward travel budget, in dp, measured from the island's
             * automatic top.
             *
             * Why not zero — or why not just the distance to the screen top?
             * The renderer paints every presentation centred inside a
             * constant-size host (see `DynamicIslandView`), so the pill's
             * *visible* top sits well below the placement top — on the
             * `island_test` AVD roughly 73 dp below it. A budget equal to the
             * automatic top (the old value) therefore stopped the pill about
             * one status bar short of the screen edge: the slider bottomed
             * out around -51 dp of travel that was nowhere near enough to
             * place the pill where the user wanted it.
             *
             * 250 dp is the product requirement — the vertical control must
             * reach -250 dp — and it comfortably covers every device
             * (automatic top + host centring is at most ~155 dp), with
             * deliberate overshoot left over: the user can push the pill off
             * the top edge if they want to. That tail is safe to allow
             * because the island stays recoverable from any position — the
             * host app's slider and Reset control do not depend on the island
             * being visible.
             */
            const val UP_TRAVEL_DP = 250f

            /**
             * Computes the envelope **from the island's automatic position**,
             * which is the only correct frame of reference.
             *
             * @param automaticTopDp where the island rests with no offset.
             * @param automaticCenterXDp its resting horizontal centre.
             */
            fun forAutomaticPosition(
                screenWidthDp: Float,
                screenHeightDp: Float,
                islandWidthDp: Float,
                islandHeightDp: Float,
                automaticTopDp: Float,
                automaticCenterXDp: Float,
                navigationBarDp: Float,
                edgeMarginDp: Float = 8f
            ): TravelEnvelope {
                val safeEdgeMargin = edgeMarginDp.coerceAtLeast(0f)
                val safeNavBar = navigationBarDp.coerceAtLeast(0f)

                // Horizontal travel: how far the centre may move across the screen.
                // Uses the wider room so corner and off-centre cutouts are not frozen
                // with a 0 dp envelope. Clamping to screen bounds is enforced by applyOffset.
                val leftRoom = automaticCenterXDp - islandWidthDp / 2f - safeEdgeMargin
                val rightRoom = screenWidthDp - safeEdgeMargin -
                    (automaticCenterXDp + islandWidthDp / 2f)
                val maxRoom = maxOf(leftRoom, rightRoom, (screenWidthDp - islandWidthDp) / 2f - safeEdgeMargin)
                val horizontal = if (leftRoom <= CLAMP_EPSILON || rightRoom <= CLAMP_EPSILON) {
                    maxOf(maxRoom, 150f)
                } else {
                    maxRoom.coerceAtLeast(0f)
                }

                // Upward: a real, reachable budget, not the distance to the
                // screen top. The pill is drawn centred in a tall host, so its
                // visible top is well below this value and the user needs the
                // extra travel to bring it to the edge — and may push it
                // further. See UP_TRAVEL_DP.
                val up = UP_TRAVEL_DP

                // Downward: stop the island's BOTTOM before the navigation area.
                // Measured from the automatic top, not from the screen top.
                val bottomLimit = screenHeightDp - safeNavBar - safeEdgeMargin
                val down = (bottomLimit - automaticTopDp - islandHeightDp).coerceAtLeast(0f)

                return TravelEnvelope(horizontal, up, down)
            }

            /**
             * Convenience overload for callers that only know the screen and the
             * island size. Assumes the island rests at the status bar.
             *
             * Prefer [forAutomaticPosition]; this exists so a caller cannot get
             * the frame of reference wrong by default.
             */
            fun forScreen(
                screenWidthDp: Float,
                screenHeightDp: Float,
                islandWidthDp: Float,
                islandHeightDp: Float,
                statusBarDp: Float,
                navigationBarDp: Float,
                edgeMarginDp: Float = 8f
            ): TravelEnvelope = forAutomaticPosition(
                screenWidthDp = screenWidthDp,
                screenHeightDp = screenHeightDp,
                islandWidthDp = islandWidthDp,
                islandHeightDp = islandHeightDp,
                automaticTopDp = statusBarDp,
                automaticCenterXDp = screenWidthDp / 2f,
                navigationBarDp = navigationBarDp,
                edgeMarginDp = edgeMarginDp
            )
        }
    }

    /**
     * The resolved on-screen position of the island, in dp.
     *
     * @param centerXDp horizontal centre of the island.
     * @param topDp distance from the top of the screen to the island's top edge.
     * @param anchoredToCutout whether the automatic position came from a real
     *   physical cutout, as opposed to the canonical fallback.
     */
    data class ResolvedPlacement(
        val centerXDp: Float,
        val topDp: Float,
        val anchoredToCutout: Boolean
    ) {
        fun leftDp(islandWidthDp: Float): Float = centerXDp - islandWidthDp / 2f
    }

    /**
     * Reasons the automatic position could not come from a cutout.
     *
     * Surfaced so the host UI can explain *why* the island is where it is —
     * a user who cannot work out why the island is off-centre deserves an
     * answer rather than a shrug.
     */
    enum class FallbackReason {
        /** A cutout exists and was used. */
        NONE,

        /** The device reports no cutout at all (tablet, under-display camera). */
        NO_CUTOUT,

        /** A cutout exists but sits off-centre; the island follows it anyway. */
        OFF_CENTRE_CUTOUT,

        /** A corner cutout cannot be centred without covering unrelated content. */
        CORNER_CUTOUT,

        /** The status bar height was unavailable, so a nominal value was used. */
        UNKNOWN_STATUS_BAR
    }

    /** Epsilon tolerance for floating-point clamping detection. */
    const val CLAMP_EPSILON: Float = 0.001f

    /**
     * Resolves the automatic (cutout-derived) position, before any user offset.
     *
     * @param profile the device's display facts.
     * @param islandWidthDp the island's width at the current presentation, used
     *   to keep the automatic centre from pushing the island off screen when the
     *   cutout sits near an edge.
     * @param statusBarDp measured status-bar height, or `null` if unknown.
     * @param canonicalTopDp the specification's top margin in **iPhone points**,
     *   converted to dp through [density]. The specification says 11 pt.
     * @param density the point-to-dp conversion for this panel.
     * @param edgeMarginDp clearance from the screen edge.
     */
    fun resolveAutomatic(
        profile: DeviceGeometryProfile,
        islandWidthDp: Float,
        statusBarDp: Float?,
        canonicalTopDp: Float = 11f,
        density: DensityNormalizer,
        edgeMarginDp: Float = 8f
    ): Pair<ResolvedPlacement, FallbackReason> {
        val cutout = profile.cutoutRectPx

        // The specification's margin is in points, not dp. Under the physical
        // path that is a real difference (1.0435 on a perfect-density panel), and
        // under [DensityNormalizer.Mode.CANONICAL] the scale is 1.0 so the
        // canonical figure passes through unchanged.
        val marginDp = density.pointsToDp(canonicalTopDp)

        if (cutout == null || cutout.isEmpty) {
            // No hardware cutout: the specification's own Android normalization
            // says to model the iPhone footprint instead — centred, with an
            // 11 pt top margin, but pushed below the status bar so it is not
            // drawn under the clock.
            val top = (statusBarDp ?: canonicalTopDp) + marginDp
            return ResolvedPlacement(
                centerXDp = profile.widthDp / 2f,
                topDp = top,
                anchoredToCutout = false
            ) to FallbackReason.NO_CUTOUT
        }

        // A cutout exists. Follow its centre rather than the screen's, because
        // they differ on real devices — the island_test AVD's hole is 12.5 px
        // right of screen centre, and centring on the screen would leave the
        // island visibly off the camera.
        val cutoutCentreDp = cutout.centerX / profile.densityScale
        val screenCentreDp = profile.widthDp / 2f
        val offCentre = abs(cutoutCentreDp - screenCentreDp) > 1f

        // Following the cutout verbatim is wrong when the hole sits close enough
        // to an edge that half the island would hang off screen — which is the
        // normal case for a corner punch-hole. Clamp the *automatic* centre so the
        // resting island is always fully on screen and respects edgeMarginDp;
        // [applyOffset] then handles the user's own travel from there.
        val safeEdgeMargin = edgeMarginDp.coerceAtLeast(0f)
        val halfWidth = islandWidthDp / 2f
        val minCentre = halfWidth + safeEdgeMargin
        val maxCentre = profile.widthDp - halfWidth - safeEdgeMargin
        val automaticCentreDp = if (islandWidthDp > 0f && minCentre <= maxCentre) {
            cutoutCentreDp.coerceIn(minCentre, maxCentre)
        } else {
            cutoutCentreDp
        }

        // A cutout flush with a screen edge or resting in the corner margin region is a
        // corner punch-hole. Those cannot be treated like a centred hole: following it
        // directly would park the island in a corner over unrelated content. It is still
        // followed — that is where the camera is — but the reason is reported so the host UI
        // can explain it.
        val flushWithEdge = cutout.left <= 0 || cutout.right >= profile.widthPx
        val inCornerRegion = cutoutCentreDp < minCentre || cutoutCentreDp > maxCentre ||
            cutoutCentreDp < profile.widthDp * 0.25f || cutoutCentreDp > profile.widthDp * 0.75f
        val isCornerCutout = flushWithEdge || inCornerRegion

        // For clearance below the cutout when status-bar height is unknown, use the
        // bottom of the cutout rather than its top so the island sits cleanly below the camera aperture.
        val cutoutClearanceDp = if (cutout.bottom > 0) {
            cutout.bottom / profile.densityScale
        } else if (cutout.top > 0) {
            cutout.top / profile.densityScale
        } else {
            0f
        }

        val reason = when {
            isCornerCutout -> FallbackReason.CORNER_CUTOUT
            offCentre -> FallbackReason.OFF_CENTRE_CUTOUT
            statusBarDp == null -> FallbackReason.UNKNOWN_STATUS_BAR
            else -> FallbackReason.NONE
        }

        // The overlay is composited beneath SystemUI, so a top of 0 would hide the
        // island under the clock. Where the status-bar height is unknown the
        // cutout's own bottom clearance is the best available stand-in.
        val top = (statusBarDp ?: cutoutClearanceDp) + marginDp

        return ResolvedPlacement(
            centerXDp = automaticCentreDp,
            topDp = top,
            anchoredToCutout = true
        ) to reason
    }

    /**
     * Applies a user offset to an automatic placement, re-clamped to the
     * permitted travel envelope.
     *
     * Horizontal offset moves the **centre**; vertical offset moves the **top**.
     * That asymmetry is deliberate: dragging horizontally should feel like
     * sliding the island along the top edge, whereas dragging vertically should
     * feel like pulling it down into the screen.
     *
     * Two independent clamps run here, and both are needed:
     *
     *  1. the [envelope], which is the smooth user-facing limit, computed from
     *     the automatic position;
     *  2. a hard bounds clamp, because the envelope is computed for one
     *     island *size* and the island changes size — a wide expanded card must
     *     still fit even if the user dragged the pill to the edge.
     *
     * Upward is the single direction where the island may leave the screen:
     * the top floor comes from [TravelEnvelope.upDp], not from y = 0, so the
     * user's full travel budget is genuinely reachable instead of being
     * silently clipped by this clamp.
     */
    fun applyOffset(
        automatic: ResolvedPlacement,
        offset: UserOffset,
        islandWidthDp: Float,
        islandHeightDp: Float,
        screenWidthDp: Float,
        screenHeightDp: Float,
        navigationBarDp: Float,
        envelope: TravelEnvelope,
        edgeMarginDp: Float = 8f
    ): ResolvedPlacement {
        val clamped = offset.clampedTo(envelope)

        val safeEdgeMargin = edgeMarginDp.coerceAtLeast(0f)
        val safeNavBar = navigationBarDp.coerceAtLeast(0f)
        val halfWidth = islandWidthDp / 2f
        val minCentre = halfWidth + safeEdgeMargin
        val maxCentre = screenWidthDp - halfWidth - safeEdgeMargin
        val desiredCentre = automatic.centerXDp + clamped.offsetXDp
        val centre = if (minCentre <= maxCentre) {
            desiredCentre.coerceIn(minCentre, maxCentre)
        } else {
            // The island is wider than the screen allows; centre it.
            screenWidthDp / 2f
        }

        // Vertical: the floor is the envelope's upward budget (deliberately
        // above the screen top, see UP_TRAVEL_DP); the ceiling keeps the
        // island's bottom clear of the navigation area.
        val topFloor = automatic.topDp - envelope.upDp
        val lowestTop = screenHeightDp - safeNavBar - islandHeightDp - safeEdgeMargin
        val uncappedTop = automatic.topDp + clamped.offsetYDp
        val top = if (lowestTop >= topFloor) {
            uncappedTop.coerceIn(topFloor, lowestTop)
        } else {
            // The allowed range is empty — an island and margins taller than
            // the screen; rest at the automatic position rather than a
            // nonsensical clamp.
            automatic.topDp
        }

        return automatic.copy(centerXDp = centre, topDp = top)
    }

    /**
     * The complete placement pipeline: automatic, then offset, then clamped.
     *
     * This is the single entry point the overlay should call. Keeping the three
     * stages in one place means the manual path cannot accidentally skip the
     * cutout anchoring.
     */
    fun resolve(
        profile: DeviceGeometryProfile,
        islandWidthDp: Float,
        islandHeightDp: Float,
        statusBarDp: Float?,
        navigationBarDp: Float,
        userOffset: UserOffset,
        density: DensityNormalizer,
        canonicalTopDp: Float = 11f,
        edgeMarginDp: Float = 8f
    ): PlacementResult {
        val (automatic, reason) = resolveAutomatic(
            profile = profile,
            islandWidthDp = islandWidthDp,
            statusBarDp = statusBarDp,
            canonicalTopDp = canonicalTopDp,
            density = density,
            edgeMarginDp = edgeMarginDp
        )

        // The envelope is measured from the AUTOMATIC position, not the screen
        // top. Measuring from the screen top double-counts the status-bar
        // clearance the automatic position already includes, and lets a
        // downward drag push the island past the navigation bar.
        val envelope = TravelEnvelope.forAutomaticPosition(
            screenWidthDp = profile.widthDp,
            screenHeightDp = profile.heightDp,
            islandWidthDp = islandWidthDp,
            islandHeightDp = islandHeightDp,
            automaticTopDp = automatic.topDp,
            automaticCenterXDp = automatic.centerXDp,
            navigationBarDp = navigationBarDp,
            edgeMarginDp = edgeMarginDp
        )

        val placed = applyOffset(
            automatic = automatic,
            offset = userOffset,
            islandWidthDp = islandWidthDp,
            islandHeightDp = islandHeightDp,
            screenWidthDp = profile.widthDp,
            screenHeightDp = profile.heightDp,
            navigationBarDp = navigationBarDp,
            envelope = envelope,
            edgeMarginDp = edgeMarginDp
        )

        return PlacementResult(
            placement = placed,
            automatic = automatic,
            envelope = envelope,
            appliedOffset = UserOffset(
                offsetXDp = placed.centerXDp - automatic.centerXDp,
                offsetYDp = placed.topDp - automatic.topDp
            ),
            fallbackReason = reason,
            wasClamped = abs(placed.centerXDp - (automatic.centerXDp + userOffset.offsetXDp)) > CLAMP_EPSILON ||
                abs(placed.topDp - (automatic.topDp + userOffset.offsetYDp)) > CLAMP_EPSILON
        )
    }

    /** Everything a caller needs to draw, explain and reset the placement. */
    data class PlacementResult(
        val placement: ResolvedPlacement,
        val automatic: ResolvedPlacement,
        val envelope: TravelEnvelope,
        /** The offset actually applied, after clamping. */
        val appliedOffset: UserOffset,
        val fallbackReason: FallbackReason,
        /** True when the requested offset was reduced to keep the island on screen. */
        val wasClamped: Boolean
    )
}
