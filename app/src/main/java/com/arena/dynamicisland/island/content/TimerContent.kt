package com.arena.dynamicisland.island.content

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText

@Composable
fun TimerContent(
    activity: IslandActivity.Timer,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        ExpandedTimer(activity, modifier)
    } else {
        CompactTimer(activity, modifier)
    }
}

@Composable
private fun CompactTimer(activity: IslandActivity.Timer, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IslandRingProgress(
                progress = activity.progress,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
            IslandGlyph(
                glyph = Glyph.Stop,
                tint = IslandColors.Amber,
                modifier = Modifier.size(6.dp),
            )
        }
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = formatSeconds(activity.remainingSeconds),
            style = IslandText.Numeric,
        )
    }
}

@Composable
private fun ExpandedTimer(activity: IslandActivity.Timer, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (activity.isRunning) "TIMER" else "PAUSED",
            style = IslandText.Micro,
            color = IslandColors.TextTertiary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(contentAlignment = Alignment.Center) {
            IslandRingProgress(
                progress = activity.progress,
                strokeWidth = 4.dp,
                modifier = Modifier.size(92.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatSeconds(activity.remainingSeconds),
                    style = IslandText.Clock,
                )
                Text(
                    text = "of " + formatSeconds(activity.totalSeconds),
                    style = IslandText.Micro,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton(
                glyph = if (activity.isRunning) Glyph.Pause else Glyph.Play,
                size = 38.dp,
                background = IslandColors.SurfaceRaised,
                glyphSize = 14.dp,
            )
            GlyphButton(
                glyph = Glyph.Stop,
                size = 38.dp,
                background = IslandColors.SurfaceRaised,
                tint = IslandColors.Red,
                glyphSize = 13.dp,
            )
        }
    }
}
