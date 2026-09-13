package com.amusic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amusic.data.prefs.PlayerStyle
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.Palettes

/**
 * Lets the user pick one of the player layouts (records / big cover / minimal)
 * and, in the same sheet, the app's accent colour.
 */
@Composable
fun StylePickerDialog(
    currentStyle: PlayerStyle,
    currentAccentId: String,
    onPickStyle: (PlayerStyle) -> Unit,
    onPickAccent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalAccent.current
    AppDialog(title = "播放器样式", onDismiss = onDismiss) {
        Column {
            PlayerStyle.values().forEach { style ->
                ChoicePill(
                    text = style.label,
                    selected = style == currentStyle,
                    subtitle = style.hint,
                    onClick = { onPickStyle(style) },
                )
            }
            HairLine()
            Text(
                "主题配色",
                color = accent.accent,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            Palettes.forEach { p ->
                ChoicePill(
                    text = p.label,
                    selected = p.id == currentAccentId,
                    trailing = if (p.id == currentAccentId) "使用中" else null,
                    onClick = { onPickAccent(p.id) },
                )
            }
        }
    }
}
