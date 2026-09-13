package com.amusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amusic.data.prefs.SettingsRepository
import com.amusic.player.Equalizer
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

/**
 * The equalizer UI, shared by 设置 and the now-playing dialog so both always agree with the
 * stored settings — every control writes through [SettingsRepository], and
 * [com.amusic.MainApplication] pushes the resulting filter chain into mpv.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerPanel(
    settings: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val enabled by settings.eqEnabled.collectAsState()
    val gains by settings.eqGains.collectAsState()
    val presetId by settings.eqPresetId.collectAsState()

    Column(modifier.fillMaxWidth()) {
        // ---- on / off ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "均衡器已开启" else "均衡器已关闭",
                    color = if (enabled) accent.accent else TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    Equalizer.describe(enabled, gains),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { settings.setEqEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent.onAccent,
                    checkedTrackColor = accent.accent,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- presets ----
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Equalizer.presets.forEach { p ->
                val on = enabled && (presetId == p.id || Equalizer.presetFor(gains)?.id == p.id)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) accent.accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f))
                        .clickable {
                            settings.setEqGains(p.gains)
                            settings.setEqPresetId(p.id)
                            if (!enabled) settings.setEqEnabled(true)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        p.label,
                        color = if (on) accent.accent else TextPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- bands ----
        Row(Modifier.fillMaxWidth()) {
            Equalizer.BANDS.indices.forEach { i ->
                BandSlider(
                    label = Equalizer.BAND_LABELS[i],
                    value = gains.getOrElse(i) { 0f },
                    accentOn = enabled,
                    onChange = { v ->
                        val next = gains.toMutableList().also { it[i] = v }
                        settings.setEqGains(next)
                        settings.setEqPresetId(Equalizer.presetFor(next)?.id ?: "custom")
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "拖动滑块即生效，播放中也能改",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                settings.setEqGains(Equalizer.gainsOf("flat"))
                settings.setEqPresetId("flat")
            }) { Text("重置", color = accent.accent) }
        }
    }
}

/**
 * A rotated [Slider] — Compose has no vertical one, and `graphicsLayer` transforms pointer
 * input along with the drawing, so dragging still works after the -90° rotation.
 */
@Composable
private fun BandSlider(
    label: String,
    value: Float,
    accentOn: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (value == 0f) "0" else "%+.0f".format(value),
            color = if (value != 0f && accentOn) accent.accent else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            Modifier.height(132.dp).width(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = Equalizer.MIN_DB..Equalizer.MAX_DB,
                steps = 23,
                colors = SliderDefaults.colors(
                    thumbColor = accent.accent,
                    activeTrackColor = if (accentOn) accent.accent else TextSecondary,
                    inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
                ),
                modifier = Modifier
                    .width(122.dp)
                    .graphicsLayer { rotationZ = -90f },
            )
        }
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
