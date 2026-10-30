package com.arena.dynamicisland.island.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText

@Composable
fun ChargingContent(
    activity: IslandActivity.Charging,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        ExpandedCharging(activity, modifier)
    } else {
        CompactCharging(activity, modifier)
    }
}

@Composable
private fun CompactCharging(activity: IslandActivity.Charging, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IslandGlyph(
            glyph = Glyph.Bolt,
            tint = IslandColors.Green,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${activity.levelPercent}%",
            style = IslandText.Numeric,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (activity.isFastCharging) "FAST" else "CHG",
            style = IslandText.Micro,
            color = IslandColors.Green,
        )
    }
}

@Composable
private fun ExpandedCharging(activity: IslandActivity.Charging, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = "${activity.levelPercent}", style = IslandText.Clock)
            Text(
                text = "%",
                style = IslandText.Title,
                modifier = Modifier.padding(bottom = 4.dp, start = 1.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        BatteryBar(level = activity.levelPercent, modifier = Modifier.fillMaxWidth(0.8f))
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IslandGlyph(
                glyph = Glyph.Bolt,
                tint = IslandColors.Green,
                modifier = Modifier.size(11.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (activity.isFastCharging) "Fast charging" else "Charging",
                style = IslandText.Caption,
                color = IslandColors.Green,
            )
        }
    }
}

/** Battery body, cap, and a fill that turns green past the halfway mark. */
@Composable
private fun BatteryBar(level: Int, modifier: Modifier = Modifier) {
    val fraction = (level.coerceIn(0, 100) / 100f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, IslandColors.TextTertiary, RoundedCornerShape(7.dp))
                .padding(2.5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(17.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(IslandColors.Green),
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(IslandColors.TextTertiary),
        )
    }
}
