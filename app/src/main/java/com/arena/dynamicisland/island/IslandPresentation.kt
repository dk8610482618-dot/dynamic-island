package com.arena.dynamicisland.island

import androidx.compose.runtime.Immutable

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The four shapes the island can take. This is derived from [IslandState]; it is never stored,
 * so there is exactly one source of truth for "what is on screen right now".
 */
@Immutable
sealed interface IslandPresentation {

    /** The plain camera cutout. No activity. */
    data object Collapsed : IslandPresentation

    /** A single activity in the pill. */
    data class Compact(val activity: IslandActivity) : IslandPresentation

    /** A single activity opened to its full card. */
    data class Expanded(val activity: IslandActivity) : IslandPresentation

    /** Two activities sharing the pill, split around the camera cutout. */
    data class Split(val leading: IslandActivity, val trailing: IslandActivity) : IslandPresentation
}

/** Fixed geometry of the island, in dp. */
object IslandSize {
    /** Height of the collapsed cutout / compact pill. */
    val Height: Dp = 37.dp

    /** Collapsed width — the physical sensor housing. */
    val CollapsedWidth: Dp = 126.dp

    /** Width used for the two-up split layout. */
    val SplitWidth: Dp = 340.dp

    /** Width of an expanded card. */
    val ExpandedWidth: Dp = 371.dp

    val CollapsedCorner: Dp = 18.5.dp
    val ExpandedCorner: Dp = 44.dp

    val Collapsed: DpSize = DpSize(CollapsedWidth, Height)
    val Split: DpSize = DpSize(SplitWidth, Height)
}

/** Target size for the current presentation. The container springs toward this. */
val IslandPresentation.size: DpSize
    get() = when (this) {
        IslandPresentation.Collapsed -> IslandSize.Collapsed
        is IslandPresentation.Compact -> DpSize(activity.compactWidth, IslandSize.Height)
        is IslandPresentation.Expanded -> DpSize(IslandSize.ExpandedWidth, activity.expandedHeight)
        is IslandPresentation.Split -> IslandSize.Split
    }

/** Target corner radius. Compact is always a full pill; the expanded card uses a smaller radius. */
val IslandPresentation.cornerRadius: Dp
    get() = when (this) {
        IslandPresentation.Collapsed, is IslandPresentation.Compact, is IslandPresentation.Split ->
            IslandSize.CollapsedCorner
        is IslandPresentation.Expanded -> IslandSize.ExpandedCorner
    }

/**
 * Identity of the *content*, not of the data. Content only crossfades when this changes, so a
 * ticking countdown updates in place instead of re-animating the whole card every second.
 */
val IslandPresentation.contentKey: String
    get() = when (this) {
        IslandPresentation.Collapsed -> "collapsed"
        is IslandPresentation.Compact -> "compact:${activity.id}"
        is IslandPresentation.Expanded -> "expanded:${activity.id}"
        is IslandPresentation.Split -> "split:${leading.id}+${trailing.id}"
    }
