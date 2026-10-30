package com.arena.dynamicisland.island

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The island is always black-on-black glass: it does not follow the app theme, because on iOS it
 * reads as a hole punched through the display. Colour is used only by live data (green call,
 * amber bolt, purple playback).
 */
object IslandColors {
    val Surface = Color(0xFF060607)
    val SurfaceRaised = Color(0xFF17171B)
    val Hairline = Color(0x1FFFFFFF)
    val InnerHighlight = Color(0x14FFFFFF)

    val TextPrimary = Color(0xFFF6F6F8)
    val TextSecondary = Color(0xB0FFFFFF)
    val TextTertiary = Color(0x7AFFFFFF)

    val Accent = Color(0xFF8B5CF6)
    val AccentSoft = Color(0xFFB79CFF)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Amber = Color(0xFFFFD60A)
    val Blue = Color(0xFF0A84FF)
    val ArtworkStart = Color(0xFF5B2EDD)
    val ArtworkEnd = Color(0xFFE05B9C)
}

object IslandText {
    val CompactTitle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = IslandColors.TextPrimary,
        letterSpacing = 0.1.sp,
    )
    val Title = TextStyle(
        fontSize = 14.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = IslandColors.TextPrimary,
        letterSpacing = 0.1.sp,
    )
    val Subtitle = TextStyle(
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        color = IslandColors.TextSecondary,
    )
    val Caption = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = IslandColors.TextSecondary,
        letterSpacing = 0.2.sp,
    )
    val Micro = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        color = IslandColors.TextTertiary,
        letterSpacing = 0.4.sp,
    )
    val Clock = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Medium,
        color = IslandColors.TextPrimary,
        letterSpacing = -0.5.sp,
    )
    val Numeric = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = IslandColors.TextPrimary,
        letterSpacing = 0.2.sp,
    )
    val ButtonLabel = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = IslandColors.TextPrimary,
    )
}
