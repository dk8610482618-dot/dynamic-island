package com.dynamicisland.engine.ui.components

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.core.media.AudioRoutePolicy

/**
 * Gap G9 — output routing row for the media card.
 *
 * Doc 2 asks for an AirPlay-style output selector. Android offers no third-party
 * path to reroute *another* app's media output, so the honest version of this
 * feature is a row that reports where audio is currently going and hands the user
 * to something that can actually change it — the player's own sheet or the system
 * sound settings — instead of a toggle that silently does nothing.
 *
 * [AudioRoutePolicy] owns that decision; this composable only renders it.
 *
 * @param sourceAppHasRoutingSheet true when the media source advertises its own
 *   output picker, which makes a deep link back to the app the better fallback
 *   than Android's settings screen.
 * @param onOpenSource invoked for the [AudioRoutePolicy.FallbackAction.SOURCE_APP_SHEET]
 *   hand-off.
 */
@Composable
fun AudioRouteRow(
    modifier: Modifier = Modifier,
    sourceAppHasRoutingSheet: Boolean = false,
    onOpenSource: () -> Unit = {}
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // Which outputs are physically connected right now.
    val availableKinds = remember(audioManager, context) {
        audioManager?.connectedOutputKinds().orEmpty()
    }

    // The current route. AudioManager has no single "what is media playing on"
    // query for a third-party app, so this is inferred from the connection
    // heuristics the platform does expose. It is a best-effort label, and the row
    // is deliberately informational rather than an interactive picker.
    val selectedKind = remember(audioManager, availableKinds) {
        audioManager?.inferredOutputKind(availableKinds)
    }

    val capability = remember(sourceAppHasRoutingSheet) {
        AudioRoutePolicy.resolveCapability(
            context = AudioRoutePolicy.RouteContext.MEDIA,
            apiLevel = Build.VERSION.SDK_INT,
            isControllableCall = false,
            sourceAppExposesRoutingSheet = sourceAppHasRoutingSheet
        )
    }

    val options = remember(availableKinds, selectedKind, capability) {
        AudioRoutePolicy.buildOptions(
            availableKinds = availableKinds,
            selectedKind = selectedKind,
            capability = capability
        )
    }

    val current = options.firstOrNull { it.isSelected } ?: options.firstOrNull()
    if (current == null) return

    val limitation = capability.limitation
    val description = buildString {
        append(AudioRoutePolicy.accessibilityLabel(current))
        if (limitation != null) {
            append(". ")
            append(limitation)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { context.performFallback(capability.fallback, onOpenSource) }
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // No material-icons dependency in this module, so the headphone glyph is
        // drawn directly — the same approach the close chevron uses.
        androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
            val stroke = 1.6.dp.toPx()
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            val paint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f)
            // Headband arc.
            drawArc(
                color = paint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.18f, size.height * 0.22f),
                size = Size(size.width * 0.64f, size.height * 0.64f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = cap)
            )
            // Left and right ear cups.
            for (x in listOf(size.width * 0.10f, size.width * 0.72f)) {
                drawRoundRect(
                    color = paint,
                    topLeft = Offset(x, size.height * 0.52f),
                    size = Size(size.width * 0.18f, size.height * 0.34f),
                    cornerRadius = CornerRadius(size.width * 0.09f)
                )
            }
        }
        Text(
            text = current.label,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (limitation != null) {
            Text(
                text = limitation,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Maps Android's connected-device list onto the policy's route vocabulary.
 *
 * Only output devices are considered; inputs would report the microphone, which
 * is a separate (and privacy-sensitive) indicator.
 */
private fun AudioManager.connectedOutputKinds(): Set<AudioRoutePolicy.AudioRouteKind> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptySet()
    val devices: Array<AudioDeviceInfo> = runCatching {
        getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    }.getOrNull() ?: return emptySet()

    // fromAudioDeviceType is total: unrecognised device types map to UNKNOWN
    // rather than null. Filter those out so an exotic headset does not add a
    // meaningless row to the option list.
    return devices.map { info -> AudioRoutePolicy.fromAudioDeviceType(info.type) }
        .filter { it != AudioRoutePolicy.AudioRouteKind.UNKNOWN }
        .toSet()
}

/**
 * Best-effort inference of the active media output.
 *
 * There is no public API that reports another app's current media route, so this
 * uses the connection flags the platform does expose and falls back to the
 * builtin speaker. The result labels the row; it never drives a control that
 * would claim to switch the route.
 */
private fun AudioManager.inferredOutputKind(
    available: Set<AudioRoutePolicy.AudioRouteKind>
): AudioRoutePolicy.AudioRouteKind? = when {
    isBluetoothA2dpOn && AudioRoutePolicy.AudioRouteKind.BLUETOOTH_A2DP in available ->
        AudioRoutePolicy.AudioRouteKind.BLUETOOTH_A2DP
    isWiredHeadsetOn && AudioRoutePolicy.AudioRouteKind.WIRED_HEADPHONES in available ->
        AudioRoutePolicy.AudioRouteKind.WIRED_HEADPHONES
    AudioRoutePolicy.AudioRouteKind.BUILTIN_SPEAKER in available ->
        AudioRoutePolicy.AudioRouteKind.BUILTIN_SPEAKER
    else -> available.firstOrNull()
}

/**
 * Sends the user somewhere that can actually change the output, per the policy's
 * fallback decision.
 */
private fun Context.performFallback(
    fallback: AudioRoutePolicy.FallbackAction,
    onOpenSource: () -> Unit
) {
    when (fallback) {
        AudioRoutePolicy.FallbackAction.NONE -> Unit
        AudioRoutePolicy.FallbackAction.SOURCE_APP_SHEET -> onOpenSource()
        AudioRoutePolicy.FallbackAction.SYSTEM_SETTINGS -> runCatching {
            startActivity(
                Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
