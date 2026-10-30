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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText

@Composable
fun CallContent(
    activity: IslandActivity.Call,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        ExpandedCall(activity, modifier)
    } else {
        CompactCall(activity, modifier)
    }
}

@Composable
private fun CompactCall(activity: IslandActivity.Call, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarBubble(initials = activity.initials, size = 24.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activity.name,
                style = IslandText.CompactTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (activity.isIncoming) "Incoming call" else formatSeconds(activity.connectedSeconds),
                style = IslandText.Micro,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        CallActionButton(glyph = Glyph.PhoneDown, color = IslandColors.Red, size = 26.dp)
        Spacer(modifier = Modifier.width(6.dp))
        CallActionButton(glyph = Glyph.Phone, color = IslandColors.Green, size = 26.dp)
    }
}

@Composable
private fun ExpandedCall(activity: IslandActivity.Call, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (activity.isIncoming) "INCOMING CALL" else "ON CALL",
            style = IslandText.Micro,
            color = IslandColors.TextTertiary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = activity.name,
            style = IslandText.Title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = when {
                activity.isIncoming && activity.isVideo -> "FaceTime Video"
                activity.isIncoming -> "Mobile"
                else -> formatSeconds(activity.connectedSeconds)
            },
            style = IslandText.Subtitle,
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CallActionButton(glyph = Glyph.PhoneDown, color = IslandColors.Red, size = 52.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "Decline", style = IslandText.Caption)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CallActionButton(glyph = Glyph.Phone, color = IslandColors.Green, size = 52.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (activity.isIncoming) "Accept" else "Speaker",
                    style = IslandText.Caption,
                )
            }
        }
    }
}

@Composable
private fun CallActionButton(
    glyph: Glyph,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        IslandGlyph(
            glyph = glyph,
            tint = Color.White,
            strokeWidth = 1.7.dp,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}
