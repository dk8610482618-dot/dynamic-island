package com.dynamicisland.core.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM solver for Apple's 4-region DynamicIslandExpandedRegion grid
 * (`.leading`, `.trailing`, `.center`, `.bottom`), `.belowIfTooWide` adaptive wrapping,
 * width-limited landscape condensation (`isDynamicIslandLimitedInWidth`), and foldable
 * `ReservedRegion` crease avoidance.
 */
object ExpandedRegionLayoutSolver {
    const val MAX_NOMINAL_EXPANDED_HEIGHT_DP = 160f
    const val EXPANDED_CORNER_RADIUS_DP = 44f
    const val COMPACT_CORNER_RADIUS_DP = 18.5f
    const val LIMITED_WIDTH_SLOT_DIAMETER_DP = 28f
    const val MIN_INTERACTIVE_TOUCH_TARGET_DP = 44f
    const val SPATIAL_GRID_UNIT_DP = 8f

    enum class VerticalPlacement {
        BESIDE_CUTOUT,
        BELOW_IF_TOO_WIDE
    }

    data class RegionRequest(
        val requiredWidthDp: Float,
        val verticalPlacement: VerticalPlacement = VerticalPlacement.BELOW_IF_TOO_WIDE,
        val priority: Int = 0
    )

    data class FourRegionAllocation(
        val leadingShiftsBelowCutout: Boolean,
        val trailingShiftsBelowCutout: Boolean,
        val fullWidthPriorityRegion: String?,
        val leadingMaxWidthDp: Float,
        val trailingMaxWidthDp: Float,
        val centerTopOffsetDp: Float,
        val bottomTopOffsetDp: Float,
        val containerHeightDp: Float,
        val cornerRadiusDp: Float
    )

    data class WidthLimitedCompactSpec(
        val isLimitedInWidth: Boolean,
        val slotDiameterDp: Float,
        val showLeading: Boolean,
        val showTrailing: Boolean,
        val condenseToGlyphOnly: Boolean
    )

    data class ReservedRegion(
        val leftPx: Int,
        val topPx: Int,
        val rightPx: Int,
        val bottomPx: Int
    ) {
        val widthPx: Int get() = (rightPx - leftPx).coerceAtLeast(0)
        val heightPx: Int get() = (bottomPx - topPx).coerceAtLeast(0)
    }

    /**
     * Evaluates whether `.leading` or `.trailing` regions exceed the horizontal span beside
     * the physical sensor cutout and must wrap downward below the cutout (`.belowIfTooWide`).
     */
    fun solveFourRegionGrid(
        containerWidthDp: Float,
        cutoutWidthDp: Float,
        cutoutHeightDp: Float = 37f,
        outerMarginDp: Float = 16f,
        leading: RegionRequest,
        trailing: RegionRequest,
        hasCenterContent: Boolean = true,
        hasBottomContent: Boolean = true,
        fontScale: Float = 1f
    ): FourRegionAllocation {
        val availableSideSpan = ((containerWidthDp - cutoutWidthDp) / 2f - outerMarginDp).coerceAtLeast(28f)
        val leadingTooWide = leading.verticalPlacement == VerticalPlacement.BELOW_IF_TOO_WIDE &&
            leading.requiredWidthDp > availableSideSpan
        val trailingTooWide = trailing.verticalPlacement == VerticalPlacement.BELOW_IF_TOO_WIDE &&
            trailing.requiredWidthDp > availableSideSpan

        val fullWidthWinner = when {
            leadingTooWide && trailingTooWide -> if (leading.priority >= trailing.priority) "leading" else "trailing"
            leadingTooWide -> "leading"
            trailingTooWide -> "trailing"
            else -> null
        }

        val fullInnerWidth = (containerWidthDp - outerMarginDp * 2f).coerceAtLeast(availableSideSpan)
        val extraWrappedRows = (if (leadingTooWide) 1 else 0) + (if (trailingTooWide) 1 else 0)
        val topBandHeight = max(cutoutHeightDp, 40f) + extraWrappedRows * 24f
        val centerHeight = if (hasCenterContent) 28f else 0f
        val bottomHeight = if (hasBottomContent) 56f else 16f

        val rawHeight = topBandHeight + centerHeight + bottomHeight + 16f
        val maxAllowedHeight = MAX_NOMINAL_EXPANDED_HEIGHT_DP * fontScale.coerceIn(1f, 1.6f)
        val clampedHeight = min(rawHeight, maxAllowedHeight).coerceAtLeast(88f)
        val effectiveRadius = if (clampedHeight < 104f) clampedHeight / 2f else EXPANDED_CORNER_RADIUS_DP

        return FourRegionAllocation(
            leadingShiftsBelowCutout = leadingTooWide,
            trailingShiftsBelowCutout = trailingTooWide,
            fullWidthPriorityRegion = fullWidthWinner,
            leadingMaxWidthDp = if (leadingTooWide) fullInnerWidth else availableSideSpan,
            trailingMaxWidthDp = if (trailingTooWide) fullInnerWidth else availableSideSpan,
            centerTopOffsetDp = topBandHeight,
            bottomTopOffsetDp = topBandHeight + centerHeight,
            containerHeightDp = clampedHeight,
            cornerRadiusDp = effectiveRadius
        )
    }

    /**
     * Implements `isDynamicIslandLimitedInWidth`: in landscape or narrow-width environments,
     * switches `compactLeading`, `compactTrailing`, and `minimal` slots to strict 28dp circular
     * glyph templates, hiding the trailing slot if both cannot fit beside the aperture.
     */
    fun resolveWidthLimitedCompact(
        isLandscape: Boolean,
        availableCapsuleWidthDp: Float,
        cutoutWidthDp: Float = 37f
    ): WidthLimitedCompactSpec {
        val isLimited = isLandscape || availableCapsuleWidthDp < 112f
        if (!isLimited) {
            return WidthLimitedCompactSpec(
                isLimitedInWidth = false,
                slotDiameterDp = 34f,
                showLeading = true,
                showTrailing = true,
                condenseToGlyphOnly = false
            )
        }
        val sideRoom = (availableCapsuleWidthDp - cutoutWidthDp) / 2f
        val canFitBoth = sideRoom >= LIMITED_WIDTH_SLOT_DIAMETER_DP
        return WidthLimitedCompactSpec(
            isLimitedInWidth = true,
            slotDiameterDp = LIMITED_WIDTH_SLOT_DIAMETER_DP,
            showLeading = true,
            showTrailing = canFitBoth,
            condenseToGlyphOnly = true
        )
    }

    /**
     * Ensures interactive control hit targets inside the Expanded view adhere to the
     * minimum 44x44dp HIG accessibility standard.
     */
    fun enforceHitTargetDp(requestedSizeDp: Float): Float =
        max(requestedSizeDp, MIN_INTERACTIVE_TOUCH_TARGET_DP)

    /**
     * Repositions the window horizontally when a foldable crease or dual-screen [ReservedRegion]
     * intersects the candidate window bounds, or anchors to the outer right edge in [DevicePosture.DUO_OUTER_RIGHT].
     */
    fun resolvePostureWindow(
        candidate: CapsuleAnchor.Window,
        screenWidthPx: Int,
        screenHeightPx: Int,
        posture: DevicePosture = DevicePosture.NORMAL_PORTRAIT,
        reservedCrease: ReservedRegion? = null,
        safeMarginPx: Int = 16
    ): CapsuleAnchor.Window {
        if (posture == DevicePosture.DUO_OUTER_RIGHT) {
            val rightAlignedX = (screenWidthPx - candidate.width - safeMarginPx).coerceAtLeast(safeMarginPx)
            return candidate.copy(left = rightAlignedX)
        }
        if (reservedCrease == null || reservedCrease.widthPx <= 0) return candidate

        val intersectsX = candidate.left < reservedCrease.rightPx &&
            (candidate.left + candidate.width) > reservedCrease.leftPx
        val intersectsY = candidate.top < reservedCrease.bottomPx &&
            (candidate.top + candidate.height) > reservedCrease.topPx

        if (!intersectsX || !intersectsY) return candidate

        // Shift to the left half or right half of the foldable display clear of the crease
        val leftOption = (reservedCrease.leftPx - candidate.width - safeMarginPx).coerceAtLeast(safeMarginPx)
        val rightOption = (reservedCrease.rightPx + safeMarginPx)
            .coerceAtMost((screenWidthPx - candidate.width - safeMarginPx).coerceAtLeast(safeMarginPx))

        val newLeft = if (candidate.left + candidate.width / 2 <= (reservedCrease.leftPx + reservedCrease.rightPx) / 2) {
            leftOption
        } else {
            rightOption
        }
        return candidate.copy(left = newLeft)
    }
}
