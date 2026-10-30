package com.dynamicisland.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.dynamicisland.engine.core.model.NotificationGroups
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.engine.core.model.IslandNotification
import kotlin.math.abs

/** Fixed-size card with a scrolling inbox; row swipes never clear unrelated messages. */
@Composable
fun NotificationInboxContent(
    notifications: List<IslandNotification>,
    onOpenNotification: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissNotification: ((String) -> Unit)? = null
) {
    val groups = remember(notifications) { NotificationGroups.group(notifications.take(20)) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val entries = groups.flatMap { group -> if(group.key in collapsed) emptyList() else group.notifications }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications · ${notifications.size}", color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Clear", color = Color(0xFF0A84FF), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClearAll)
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            groups.forEach { group -> TextButton(onClick={collapsed=if(group.key in collapsed) collapsed-group.key else collapsed+group.key}) {
                Text("${group.label} · ${group.notifications.size}${if(group.key in collapsed) " +" else " −"}")
            } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(entries, key = { it.key }) { notification ->
                val latestDismiss by rememberUpdatedState(onDismissNotification)
                var offset by remember(notification.key) { mutableFloatStateOf(0f) }
                val threshold = with(LocalDensity.current) { 56.dp.toPx() }
                Row(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { translationX = offset; alpha = 1f - (abs(offset) / (threshold * 3f)).coerceIn(0f, .7f) }
                        .pointerInput(notification.key) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, amount -> change.consume(); offset += amount },
                                onDragEnd = {
                                    if (!notification.isOngoing && abs(offset) >= threshold) latestDismiss?.invoke(notification.key)
                                    offset = 0f
                                },
                                onDragCancel = { offset = 0f }
                            )
                        }
                        .semantics {
                            contentDescription = "${notification.appLabel ?: notification.packageName}: ${notification.title}"
                            dismiss {
                                if (!notification.isOngoing) latestDismiss?.invoke(notification.key)
                                latestDismiss != null && !notification.isOngoing
                            }
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = .05f))
                        .clickable { onOpenNotification(notification.key) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(notification.appLabel ?: notification.packageName.substringAfterLast('.'),
                            color = Color(0xFF8EBBFF), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if(notification.importance >= 4) Text("High priority",color=Color(0xFFB7D5C3),fontSize=10.sp)
                        Text(notification.title, color = Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        notification.body?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color.White.copy(alpha = .6f), fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (onDismissNotification != null && !notification.isOngoing) {
                        Text("×", color = Color.White.copy(alpha = .7f), fontSize = 20.sp,
                            modifier = Modifier.semantics { contentDescription = "Dismiss ${notification.title}" }
                                .clickable { onDismissNotification(notification.key) }.padding(8.dp))
                    }
                }
            }
        }
    }
}
