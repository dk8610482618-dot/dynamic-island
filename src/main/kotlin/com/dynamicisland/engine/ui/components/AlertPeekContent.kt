package com.dynamicisland.engine.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynamicisland.engine.core.model.IslandSlotContent
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.core.model.TransientAlert
import com.dynamicisland.engine.ui.icons.IslandGlyph

private fun parseColor(hex: String?): Color? =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/**
 * Transient alert banner presentation inside the Dynamic Island
 * (charging, silent switch, accessory connection, heads-up notification).
 *
 * Sized for the 280 x 52 pt alert peek: the metadata stack is a single 14 sp line plus an
 * optional 12 sp line, and the leading glyph sits in a tinted 30 pt disc. The previous
 * 44 pt peeks were shorter than their own content and clipped the second line.
 */
@Composable
fun AlertPeekContent(
    alert: TransientAlert,
    modifier: Modifier = Modifier
) {
    val accent = when (val slot = alert.leadingSlot) {
        is IslandSlotContent.Symbol -> parseColor(slot.tintHex) ?: Color.White
        is IslandSlotContent.Text -> parseColor(slot.colorHex) ?: Color.White
        is IslandSlotContent.AudioWaveform -> parseColor(slot.tintHex) ?: Color.White
        else -> Color.White
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            when (val slot = alert.leadingSlot) {
                is IslandSlotContent.Symbol -> IslandGlyph(
                    symbol = slot.symbol,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )

                is IslandSlotContent.Icon -> if (slot.iconBitmap != null) {
                    Image(
                        bitmap = slot.iconBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    IslandGlyph(
                        symbol = IslandSymbol.BELL,
                        tint = accent,
                        modifier = Modifier.size(17.dp)
                    )
                }

                is IslandSlotContent.AudioWaveform -> AudioWaveformEqualizer(
                    amplitudes = slot.amplitudes,
                    color = accent,
                    maxHeight = 12.dp
                )

                is IslandSlotContent.Text -> IslandGlyph(
                    symbol = IslandSymbol.BELL,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )

                else -> IslandGlyph(
                    symbol = IslandSymbol.BELL,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = alert.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            alert.message?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        alert.trailingSlot?.let { trailing ->
            Spacer(modifier = Modifier.width(10.dp))
            when (trailing) {
                is IslandSlotContent.Text -> Text(
                    text = trailing.text,
                    color = parseColor(trailing.colorHex) ?: Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )

                is IslandSlotContent.Symbol -> IslandGlyph(
                    symbol = trailing.symbol,
                    tint = parseColor(trailing.tintHex) ?: Color.White,
                    modifier = Modifier.size(17.dp)
                )

                is IslandSlotContent.Progress -> Text(
                    text = "${(trailing.current.toFloat() / trailing.max.coerceAtLeast(1)).times(100).toInt()}%",
                    color = parseColor(trailing.tintHex) ?: Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                else -> Unit
            }
        }
    }
}
