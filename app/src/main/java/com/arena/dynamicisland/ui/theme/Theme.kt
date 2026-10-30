package com.arena.dynamicisland.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.arena.dynamicisland.island.IslandColors

private val IslandColorScheme = darkColorScheme(
    primary = IslandColors.Accent,
    onPrimary = Color.White,
    secondary = IslandColors.Green,
    background = Color(0xFF05050A),
    onBackground = IslandColors.TextPrimary,
    surface = IslandColors.Surface,
    onSurface = IslandColors.TextPrimary,
)

/**
 * The app is dark-only by design: the island is a hole in the display, so a light shell would
 * make it read as a floating widget instead of part of the hardware.
 */
@Composable
fun DynamicIslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IslandColorScheme,
        content = content,
    )
}
