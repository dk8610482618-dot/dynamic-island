package com.dynamicisland.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.core.geometry.ExpandedRegionLayoutSolver
import com.dynamicisland.engine.core.model.IslandActivity
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.ui.icons.IslandGlyph

/**
 * Apple HIG 4-region Turn-by-Turn Navigation Expanded Presenter (`.leading`, `.trailing`, `.center`, `.bottom`).
 * Implements `.dynamicIsland(verticalPlacement: .belowIfTooWide)` adaptive wrapping and 44x44dp min hit targets.
 */
@Composable
fun NavigationExpandedContent(
    activity: IslandActivity,
    onOpenSource: () -> Unit,
    onExecuteAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val region = activity.expandedData
    val distanceText = region.progressLabel ?: "250 m"
    val etaText = region.etaLabel ?: "ETA 8 min"
    val progress = (region.progressFraction ?: 0.62f).coerceIn(0.05f, 1f)
    val allocation = ExpandedRegionLayoutSolver.solveFourRegionGrid(
        containerWidthDp = 360f,
        cutoutWidthDp = 120f,
        leading = ExpandedRegionLayoutSolver.RegionRequest(
            requiredWidthDp = (activity.title.length * 7f).coerceAtLeast(64f),
            verticalPlacement = region.leadingVerticalPlacement,
            priority = region.leadingPriority
        ),
        trailing = ExpandedRegionLayoutSolver.RegionRequest(
            requiredWidthDp = (distanceText.length * 9f).coerceAtLeast(56f),
            verticalPlacement = region.trailingVerticalPlacement,
            priority = region.trailingPriority
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Turn by turn navigation. ${activity.title}. $distanceText. $etaText" },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top row: .leading (Turn Icon) + .trailing (Live Distance & ETA badge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A84FF).copy(alpha = 0.22f))
                        .border(1.dp, Color(0xFF0A84FF).copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    IslandGlyph(IslandSymbol.NAVIGATION, Color(0xFF0A84FF), Modifier.size(22.dp))
                }
                if (!allocation.leadingShiftsBelowCutout) {
                    Column(modifier = Modifier.widthIn(max = 170.dp)) {
                        Text(
                            text = activity.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        activity.subtitle?.let {
                            Text(
                                text = it,
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = distanceText,
                    color = Color(0xFF0A84FF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = etaText,
                    color = Color(0xFF30D158),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // .center region (or .belowIfTooWide wrapped full-width street/maneuver title)
        if (allocation.leadingShiftsBelowCutout) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = activity.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                activity.subtitle?.let {
                    Text(
                        text = it,
                        color = Color(0xFFB7BABD),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // .bottom region: Route progress bar + Liquid Glass 44dp min hit-target controls
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = Color(0xFF0A84FF),
            trackColor = Color.White.copy(alpha = 0.14f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val primaryAction = activity.actions.firstOrNull()
            if (primaryAction != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.08f))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .clickable { onExecuteAction(primaryAction.id) }
                        .semantics { contentDescription = primaryAction.title },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = primaryAction.title,
                        color = if (primaryAction.isDestructive) Color(0xFFFF453A) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A84FF).copy(alpha = 0.24f))
                    .border(1.dp, Color(0xFF0A84FF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenSource)
                    .semantics { contentDescription = "Open navigation map" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Open Route",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
