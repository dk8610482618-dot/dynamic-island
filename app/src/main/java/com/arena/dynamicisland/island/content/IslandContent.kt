package com.arena.dynamicisland.island.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors

/**
 * Entry point for per-activity UI.
 *
 * Activities receive only the facts they need — their own data and whether they are expanded —
 * never the container, the priority list or the gestures. That keeps every layout drop-in
 * replaceable and previewable in isolation.
 */
@Composable
fun IslandActivityContent(
    activity: IslandActivity,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    when (activity) {
        is IslandActivity.Music -> MusicContent(activity, expanded, modifier)
        is IslandActivity.Call -> CallContent(activity, expanded, modifier)
        is IslandActivity.Timer -> TimerContent(activity, expanded, modifier)
        is IslandActivity.Charging -> ChargingContent(activity, expanded, modifier)
        is IslandActivity.Navigation -> NavigationContent(activity, expanded, modifier)
        IslandActivity.Unlock -> UnlockContent(expanded, modifier)
    }
}

/**
 * The cutout itself: the camera lens and the TrueDepth dot, sitting where the hardware sits.
 *
 * Nothing is interactive here — when no activity is live, the island *is* the sensor housing,
 * which is why this state renders as two physical elements rather than as empty black.
 */
@Composable
fun CollapsedContent(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF20293A), Color(0xFF07090D)),
                    ),
                )
                .border(0.6.dp, Color(0x2BFFFFFF), CircleShape),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFF121216))
                .border(0.5.dp, Color(0x1FFFFFFF), CircleShape),
        )
    }
}

/**
 * Two activities in one pill, split around the camera.
 *
 * Both halves run their normal compact layout; they only lose the fixed width, so every activity
 * has exactly one compact implementation whether it is alone or sharing.
 */
@Composable
fun SplitContent(
    leading: IslandActivity,
    trailing: IslandActivity,
    activityContent: @Composable (IslandActivity, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            activityContent(leading, false)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            activityContent(trailing, false)
        }
    }
}

/** Shared muted pill background for small in-card actions. */
internal val ActionPillColor: Color = IslandColors.SurfaceRaised
