package com.dynamicisland.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.dynamicisland.engine.core.model.IslandActivity
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.ui.icons.IslandGlyph

/**
 * Android 16+ `Notification.ProgressStyle` & Apple ActivityKit Live Journey Presenter
 * for Rideshare, Courier, and Food Delivery tracking with `MinimalProgressBar` and milestone queue.
 */
@Composable
fun JourneyExpandedContent(
    activity: IslandActivity,
    onOpenSource: () -> Unit,
    onExecuteAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val region = activity.expandedData
    val milestones = region.milestones.ifEmpty {
        listOf("Confirmed", "Preparing", "On the way", "Arriving")
    }
    val currentStage = region.currentMilestoneIndex.coerceIn(0, milestones.lastIndex)
    val etaLabel = region.etaLabel ?: "Arriving soon"
    val accent = Color(0xFF30D158)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Live delivery journey. ${activity.title}. Stage ${milestones[currentStage]}. $etaLabel" },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // .leading & .trailing header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(accent.copy(alpha = 0.2f))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    IslandGlyph(IslandSymbol.DELIVERY, accent, Modifier.size(20.dp))
                }
                Column {
                    Text(
                        text = activity.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activity.subtitle ?: milestones[currentStage],
                        color = Color(0xFFB7BABD),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = etaLabel,
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // .bottom region: Segmented MinimalProgressBar & Milestone Queue Tracker
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                milestones.forEachIndexed { idx, _ ->
                    val completed = idx <= currentStage
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(if (completed) accent else Color.White.copy(alpha = 0.15f))
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                milestones.forEachIndexed { idx, label ->
                    Text(
                        text = label,
                        color = if (idx == currentStage) Color.White else Color(0xFF8E8E93),
                        fontSize = 10.sp,
                        fontWeight = if (idx == currentStage) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // Liquid Glass 44x44dp minimum hit-target action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            activity.actions.firstOrNull()?.let { action ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.07f))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .clickable { onExecuteAction(action.id) }
                        .semantics { contentDescription = action.title },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = action.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenSource)
                    .semantics { contentDescription = "Track live order" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Track Live",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
