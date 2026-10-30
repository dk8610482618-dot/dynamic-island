package com.arena.dynamicisland.island.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText

/**
 * Transient confirmation: the island briefly becomes a checkmark, then returns to the cutout.
 *
 * The lifetime lives on [com.arena.dynamicisland.island.IslandActivity.Unlock.transientMillis];
 * this composable only draws it.
 */
@Composable
fun UnlockContent(expanded: Boolean, modifier: Modifier = Modifier) {
    if (expanded) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CheckBadge(size = 46.dp)
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = "Face ID", style = IslandText.Micro)
        }
    } else {
        Row(
            modifier = modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CheckBadge(size = 22.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Unlocked", style = IslandText.CompactTitle)
        }
    }
}

@Composable
private fun CheckBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(IslandColors.Green),
        contentAlignment = Alignment.Center,
    ) {
        IslandGlyph(
            glyph = Glyph.Check,
            tint = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
