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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandText

@Composable
fun MusicContent(
    activity: IslandActivity.Music,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        ExpandedMusic(activity, modifier)
    } else {
        CompactMusic(activity, modifier)
    }
}

@Composable
private fun CompactMusic(activity: IslandActivity.Music, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(size = 22.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = activity.title,
            style = IslandText.CompactTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        WaveformBars(
            isPlaying = activity.isPlaying,
            barCount = 4,
            modifier = Modifier
                .width(22.dp)
                .height(16.dp),
        )
    }
}

@Composable
private fun ExpandedMusic(activity: IslandActivity.Music, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(size = 46.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.title,
                    style = IslandText.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = activity.artist,
                    style = IslandText.Subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            WaveformBars(
                isPlaying = activity.isPlaying,
                barCount = 4,
                modifier = Modifier
                    .width(26.dp)
                    .height(20.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        IslandProgressBar(
            progress = activity.progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatSeconds(activity.elapsedSeconds), style = IslandText.Micro)
            Text(
                text = "-" + formatSeconds((activity.durationSeconds - activity.elapsedSeconds).coerceAtLeast(0)),
                style = IslandText.Micro,
            )
        }

        Spacer(modifier = Modifier.height(9.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton(glyph = Glyph.Previous, size = 34.dp, tint = IslandColors.TextSecondary)
            GlyphButton(
                glyph = if (activity.isPlaying) Glyph.Pause else Glyph.Play,
                size = 42.dp,
                background = IslandColors.SurfaceRaised,
                glyphSize = 17.dp,
            )
            GlyphButton(glyph = Glyph.Next, size = 34.dp, tint = IslandColors.TextSecondary)
        }
    }
}

/** Stand-in album art: a two-stop gradient with three "level" bars, so it reads as artwork. */
@Composable
private fun Artwork(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(
                Brush.linearGradient(
                    colors = listOf(IslandColors.ArtworkStart, IslandColors.ArtworkEnd),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(size * 0.07f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(0.30f, 0.54f, 0.40f).forEach { fraction ->
                Box(
                    modifier = Modifier
                        .width(size * 0.07f)
                        .height(size * fraction)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xE6FFFFFF)),
                )
            }
        }
    }
}

/** mm:ss. Negative values are clamped by callers, not here. */
internal fun formatSeconds(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}
