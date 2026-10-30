package com.dynamicisland.host

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dynamicisland.core.geometry.*
import com.dynamicisland.engine.settings.BehaviorOptions

@Composable
internal fun ExperienceSettingsPanel(options: BehaviorOptions, onOptions: (BehaviorOptions) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Interaction & Apple Parity", style = MaterialTheme.typography.titleLarge, color = Color.White)
        ChoiceRow("Interaction Mode", InteractionMode.values().toList(), options.interactionMode) {
            onOptions(options.copy(interactionMode = it))
        }
        Text(
            "Apple Parity: Single tap (<200ms) opens the active app via deep link; long press (~450ms) expands the island with 0.985x touch compression.",
            color = Color(0xFFB8C1BA)
        )
        ChoiceRow("Landscape Width Policy", LandscapeBehavior.values().toList(), options.landscapeBehavior) {
            onOptions(options.copy(landscapeBehavior = it))
        }
        ChoiceRow("Foldable / Posture", DevicePosture.values().toList(), options.devicePosture) {
            onOptions(options.copy(devicePosture = it))
        }

        Text("Appearance & motion", style = MaterialTheme.typography.titleLarge, color = Color.White)
        ChoiceRow("Appearance", AppearancePreset.values().toList(), options.appearance) {
            onOptions(options.copy(appearance = it))
        }
        ChoiceRow("Motion", MotionPreset.values().toList(), options.motion) {
            onOptions(options.copy(motion = it))
        }
        SettingToggle("Reduced motion", options.reducedMotion) {
            onOptions(options.copy(reducedMotion = it))
        }
        SettingToggle("Keyline tint (.keylineTint 1dp border)", options.enableKeylineTint) {
            onOptions(options.copy(enableKeylineTint = it))
        }
        SettingToggle("Metaball ferrofluid droplet bridge", options.enableMetaballShader) {
            onOptions(options.copy(enableMetaballShader = it))
        }
        SettingToggle("Secondary activity bubble", options.secondaryBubble) {
            onOptions(options.copy(secondaryBubble = it))
        }
        SettingToggle("3-activity mitosis (iPhone 18 Pro)", options.maxConcurrentActivities >= 3) {
            onOptions(options.copy(maxConcurrentActivities = if (it) 3 else 2))
        }
        SettingToggle("Idle mini-status bar", options.idleMiniStatusBar) {
            onOptions(options.copy(idleMiniStatusBar = it))
        }

        Text("Activity priority", style = MaterialTheme.typography.titleLarge, color = Color.White)
        ChoiceRow("Priority", ActivityPriority.values().toList(), options.priority) {
            onOptions(options.copy(priority = it))
        }
        Text("Calls always retain the highest priority. Live Activities arbitrate by relevanceScore + FIFO.", color = Color(0xFFB8C1BA))

        Text("Gestures & feedback", style = MaterialTheme.typography.titleLarge, color = Color.White)
        ChoiceRow("Tap", GestureAction.values().toList(), options.tapAction) {
            onOptions(options.copy(tapAction = it))
        }
        ChoiceRow("Hold", GestureAction.values().toList(), options.holdAction) {
            onOptions(options.copy(holdAction = it))
        }
        ChoiceRow("Swipe left", GestureAction.values().toList(), options.swipeLeftAction) {
            onOptions(options.copy(swipeLeftAction = it))
        }
        ChoiceRow("Swipe right", GestureAction.values().toList(), options.swipeRightAction) {
            onOptions(options.copy(swipeRightAction = it))
        }
        Text("Haptics: ${listOf("Off", "Light", "Standard", "Strong")[options.hapticIntensity]}", color = Color.White)
        Slider(
            value = options.hapticIntensity.toFloat(),
            onValueChange = { onOptions(options.copy(hapticIntensity = it.toInt())) },
            valueRange = 0f..3f,
            steps = 2
        )
    }
}

@Composable
private fun <T : Enum<T>> ChoiceRow(label: String, choices: List<T>, selected: T, onSelect: (T) -> Unit) {
    Text(label, color = Color.White)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = {
                    Text(
                        if (value == GestureAction.OPEN_DASHBOARD) "Expand island"
                        else value.name.lowercase().replace('_', ' ')
                    )
                }
            )
        }
    }
}

@Composable
private fun SettingToggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(value, change)
    }
}
