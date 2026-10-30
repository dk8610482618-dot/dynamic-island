package com.arena.dynamicisland.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.dynamicisland.island.IslandActivity
import com.arena.dynamicisland.island.IslandColors
import com.arena.dynamicisland.island.IslandHost
import com.arena.dynamicisland.island.IslandPresentation
import com.arena.dynamicisland.island.rememberIslandController
import kotlinx.coroutines.delay

/**
 * Playground for the island: a wallpaper, a status bar, and a control panel that fires every
 * activity the island understands.
 *
 * The clock loop below is what makes the demo feel alive — it pushes *new data* into existing
 * activities once a second. Because the reducer treats a same-id post as an update, the island
 * keeps its size and expanded state while the numbers tick.
 */
@Composable
fun DemoApp() {
    val controller = rememberIslandController()
    var demoRun by remember { mutableStateOf(0) }

    // One shared tick for every time-based activity.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)

            (controller.activity(IslandActivity.Timer.ID) as? IslandActivity.Timer)?.let { timer ->
                if (!timer.isRunning) return@let
                val remaining = timer.remainingSeconds - 1
                if (remaining <= 0) {
                    controller.dismiss(timer.id)
                } else {
                    controller.post(timer.copy(remainingSeconds = remaining))
                }
            }

            (controller.activity(IslandActivity.Music.ID) as? IslandActivity.Music)?.let { music ->
                if (!music.isPlaying) return@let
                val next = music.elapsedSeconds + 1
                controller.post(music.copy(elapsedSeconds = if (next >= music.durationSeconds) 0 else next))
            }

            (controller.activity(IslandActivity.Call.ID) as? IslandActivity.Call)?.let { call ->
                if (call.isIncoming) return@let
                controller.post(call.copy(connectedSeconds = call.connectedSeconds + 1))
            }
        }
    }

    // Scripted tour, so the transition choreography can be judged without tapping.
    LaunchedEffect(demoRun) {
        if (demoRun == 0) return@LaunchedEffect
        controller.dismissAll()
        delay(300)
        controller.post(IslandActivity.Charging(levelPercent = 24))
        delay(1_800)
        controller.post(IslandActivity.Music())
        delay(1_800)
        controller.expand()
        delay(3_200)
        controller.collapse()
        delay(900)
        controller.post(IslandActivity.Call(isIncoming = true))
        delay(2_600)
        controller.dismiss(IslandActivity.Call.ID)
        delay(900)
        controller.post(IslandActivity.Navigation())
        delay(1_600)
        controller.post(IslandActivity.Timer(totalSeconds = 600, remainingSeconds = 412))
        delay(2_400)
        controller.expand(IslandActivity.Timer.ID)
        delay(2_800)
        controller.dismissAll()
        delay(400)
        controller.post(IslandActivity.Unlock)
        delay(2_400)
        controller.post(IslandActivity.Music())
    }

    val presentation = controller.presentation
    val statusAlpha by animateFloatAsState(
        targetValue = if (presentation is IslandPresentation.Expanded) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "statusBarAlpha",
    )

    Box(modifier = Modifier.fillMaxSize().background(Wallpaper)) {
        Column(modifier = Modifier.fillMaxSize()) {

            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                // Status bar flanking the island; it fades out when the card opens, like iOS.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                        .alpha(statusAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "9:41",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalGlyph()
                        Spacer(modifier = Modifier.width(6.dp))
                        BatteryGlyph()
                    }
                }

                // The island itself, centred where the camera cutout would be.
                IslandHost(
                    controller = controller,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = presentationLabel(presentation),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Tap the island to open it.\nDrag up to expand, down to close.",
                    color = Color.White.copy(alpha = 0.32f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            ControlPanel(
                controller = controller,
                onRunDemo = { demoRun += 1 },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ControlPanel(
    controller: com.arena.dynamicisland.island.IslandController,
    onRunDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ControlChip(
                label = "Music",
                active = controller.isActive(IslandActivity.Music.ID),
                modifier = Modifier.weight(1f),
            ) {
                if (controller.isActive(IslandActivity.Music.ID)) {
                    controller.dismiss(IslandActivity.Music.ID)
                } else {
                    controller.post(IslandActivity.Music())
                }
            }
            ControlChip(
                label = "Call",
                active = controller.isActive(IslandActivity.Call.ID),
                modifier = Modifier.weight(1f),
            ) {
                if (controller.isActive(IslandActivity.Call.ID)) {
                    controller.dismiss(IslandActivity.Call.ID)
                } else {
                    controller.post(IslandActivity.Call(isIncoming = true))
                }
            }
            ControlChip(
                label = "Timer",
                active = controller.isActive(IslandActivity.Timer.ID),
                modifier = Modifier.weight(1f),
            ) {
                if (controller.isActive(IslandActivity.Timer.ID)) {
                    controller.dismiss(IslandActivity.Timer.ID)
                } else {
                    controller.post(IslandActivity.Timer(totalSeconds = 600, remainingSeconds = 412))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ControlChip(
                label = "Charging",
                active = controller.isActive(IslandActivity.Charging.ID),
                modifier = Modifier.weight(1f),
            ) {
                if (controller.isActive(IslandActivity.Charging.ID)) {
                    controller.dismiss(IslandActivity.Charging.ID)
                } else {
                    controller.post(IslandActivity.Charging(levelPercent = 24))
                }
            }
            ControlChip(
                label = "Maps",
                active = controller.isActive(IslandActivity.Navigation.ID),
                modifier = Modifier.weight(1f),
            ) {
                if (controller.isActive(IslandActivity.Navigation.ID)) {
                    controller.dismiss(IslandActivity.Navigation.ID)
                } else {
                    controller.post(IslandActivity.Navigation())
                }
            }
            ControlChip(
                label = "Face ID",
                active = controller.isActive(IslandActivity.Unlock.ID),
                modifier = Modifier.weight(1f),
            ) {
                controller.post(IslandActivity.Unlock)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ControlChip(
                label = "Open",
                active = controller.presentation is IslandPresentation.Expanded,
                modifier = Modifier.weight(1f),
                onClick = { controller.expand() },
            )
            ControlChip(
                label = "Close",
                active = false,
                modifier = Modifier.weight(1f),
                onClick = { controller.collapse() },
            )
            ControlChip(
                label = "Clear",
                active = false,
                modifier = Modifier.weight(1f),
                onClick = { controller.dismissAll() },
            )
            ControlChip(
                label = "Demo",
                active = false,
                modifier = Modifier.weight(1f),
                accent = IslandColors.Accent,
                onClick = onRunDemo,
            )
        }
    }
}

@Composable
private fun ControlChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = IslandColors.Green,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) accent.copy(alpha = 0.22f) else Color(0x1AFFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) accent else Color.White.copy(alpha = 0.82f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun SignalGlyph() {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
        listOf(3f, 5f, 7f, 9f).forEach { height ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun BatteryGlyph() {
    Box(
        modifier = Modifier
            .width(22.dp)
            .height(11.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.35f))
            .padding(1.5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White),
        )
    }
}

private fun presentationLabel(presentation: IslandPresentation): String = when (presentation) {
    IslandPresentation.Collapsed -> "COLLAPSED"
    is IslandPresentation.Compact -> "COMPACT · ${presentation.activity.id.uppercase()}"
    is IslandPresentation.Expanded -> "EXPANDED · ${presentation.activity.id.uppercase()}"
    is IslandPresentation.Split -> "SPLIT · ${presentation.leading.id.uppercase()} + ${presentation.trailing.id.uppercase()}"
}

/** Deep purple-black wallpaper with two soft light sources, so the black island still reads. */
private val Wallpaper = Brush.verticalGradient(
    colors = listOf(Color(0xFF1B1436), Color(0xFF0B0A14), Color(0xFF05050A)),
)
