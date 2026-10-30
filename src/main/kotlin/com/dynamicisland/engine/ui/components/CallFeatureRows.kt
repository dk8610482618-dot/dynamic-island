package com.dynamicisland.engine.ui.components

import android.os.Build
import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.core.call.CallSessionFeatures

/**
 * Gap G8 — extra call-session features (screen share, live captions, translation,
 * a shared session) as honest status rows.
 *
 * Doc 2 lists SharePlay, Live Captions and Live Translation as table-stakes call
 * features. Android exposes none of them as observable global state, so the only
 * defensible implementation is the one [CallSessionFeatures] encodes: every row
 * carries a [CallSessionFeatures.FeatureOrigin], and a row may be *drawn as
 * active* only when that state came from somewhere real — the device, or the
 * calling app advertising it in its session extras.
 *
 * Two rules this layer adds on top of the policy:
 * - labels are ours, and never use Apple product names ("FaceTime", "SharePlay");
 *   a source app's own [CallSessionFeatures.FeatureState.sourceLabel] wins when
 *   present, so the island repeats the app's wording instead of inventing one.
 * - a user *preference* is never rendered as a *running session*: system
 *   captioning being enabled reports "System captions on", never an active
 *   Live Captions row.
 */
@Composable
fun CallFeatureRows(
    isCallActive: Boolean,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isOnHold: Boolean,
    isControllable: Boolean,
    advertisedExtraKeys: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val observation = remember(
        isCallActive,
        isMuted,
        isSpeakerOn,
        isOnHold,
        isControllable,
        advertisedExtraKeys
    ) {
        // CaptioningManager reports the user's accessibility preference, not a
        // live caption session. It is read here only so the policy can fall back
        // to an honest "preference on" row instead of an unavailable one.
        val captioningEnabled = runCatching {
            (context.getSystemService(android.content.Context.CAPTIONING_SERVICE)
                as? CaptioningManager)?.isEnabled == true
        }.getOrDefault(false)

        CallSessionFeatures.CallObservation(
            isCallActive = isCallActive,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            isOnHold = isOnHold,
            isControllable = isControllable,
            apiLevel = Build.VERSION.SDK_INT,
            systemCaptioningEnabled = captioningEnabled,
            advertisedExtraKeys = advertisedExtraKeys
        )
    }

    val rows = remember(observation) {
        CallSessionFeatures.visibleFeatures(CallSessionFeatures.resolve(observation))
    }

    if (rows.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        rows.forEach { state ->
            val label = state.sourceLabel ?: state.feature.label()
            // The single honesty gate: active styling only when the state is real.
            val active = state.mayRenderAsActive
            Text(
                text = label,
                color = if (active) {
                    androidx.compose.ui.graphics.Color(0xFF30D158)
                } else {
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f)
                },
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        androidx.compose.ui.graphics.Color.White.copy(
                            alpha = if (active) 0.14f else 0.06f
                        )
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .semantics {
                        contentDescription = if (active) {
                            "$label, active"
                        } else {
                            "$label, not active"
                        }
                    }
            )
        }
    }
}

/**
 * Our own wording for each feature. Deliberately generic: the island must not
 * imply an Apple service it is not participating in.
 */
private fun CallSessionFeatures.CallFeature.label(): String = when (this) {
    CallSessionFeatures.CallFeature.MICROPHONE_MUTE -> "Muted"
    CallSessionFeatures.CallFeature.SPEAKER_ROUTING -> "Speaker"
    CallSessionFeatures.CallFeature.SCREEN_SHARE -> "Screen sharing"
    CallSessionFeatures.CallFeature.REMOTE_CONTROL_REQUEST -> "Remote control requested"
    CallSessionFeatures.CallFeature.LIVE_CAPTIONS -> "Live captions"
    CallSessionFeatures.CallFeature.LIVE_TRANSLATION -> "Live translation"
    CallSessionFeatures.CallFeature.SHAREPLAY_SESSION -> "Shared session"
    CallSessionFeatures.CallFeature.HOLD -> "On hold"
}

/**
 * Collects the feature keys a source app advertised in its session extras.
 *
 * Only what the app actually declared is returned; nothing is inferred from the
 * package name, the call duration, or the presence of a video call.
 */
fun advertisedFeatureKeys(extras: android.os.Bundle?): Set<String> {
    if (extras == null) return emptySet()
    val known = listOf(
        CallSessionFeatures.ExtraKeys.LIVE_CAPTIONS_ACTIVE,
        CallSessionFeatures.ExtraKeys.LIVE_TRANSLATION_ACTIVE,
        CallSessionFeatures.ExtraKeys.SHAREPLAY_SESSION_ACTIVE,
        CallSessionFeatures.ExtraKeys.SCREEN_SHARE_ACTIVE,
        CallSessionFeatures.ExtraKeys.REMOTE_CONTROL_REQUESTED
    )
    return known.filter { extras.getBoolean(it, false) }.toSet()
}
