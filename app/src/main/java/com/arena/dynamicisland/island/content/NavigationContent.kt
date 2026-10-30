package com.arena.dynamicisland.island.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText

@Composable
fun NavigationContent(
    activity: IslandActivity.Navigation,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        ExpandedNavigation(activity, modifier)
    } else {
        CompactNavigation(activity, modifier)
    }
}

@Composable
private fun CompactNavigation(activity: IslandActivity.Navigation, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ManeuverTile(size = 23.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = activity.distance,
            style = IslandText.CompactTitle,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = activity.eta,
            style = IslandText.Micro,
            color = IslandColors.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExpandedNavigation(activity: IslandActivity.Navigation, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ManeuverTile(size = 42.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = activity.distance, style = IslandText.Title)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activity.maneuver,
                        style = IslandText.Subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = activity.road,
                    style = IslandText.Subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(IslandColors.Hairline),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IslandGlyph(
                    glyph = Glyph.ArrowRight,
                    tint = IslandColors.Blue,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "${activity.eta} · ${activity.arrival}", style = IslandText.Caption)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(IslandColors.SurfaceRaised)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(text = "End", style = IslandText.ButtonLabel, color = IslandColors.Red)
            }
        }
    }
}

/** Blue rounded tile holding the maneuver arrow. */
@Composable
private fun ManeuverTile(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(IslandColors.Blue),
        contentAlignment = Alignment.Center,
    ) {
        IslandGlyph(
            glyph = Glyph.ArrowUp,
            tint = androidx.compose.ui.graphics.Color.White,
            strokeWidth = 1.8.dp,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}
