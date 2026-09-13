package com.amusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.amusic.ui.theme.Divider
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.Surface as SurfaceColor
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

/**
 * Shared dark dialog shell so every popup in the app has the same shape / colours.
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceColor,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                content()
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭", color = TextSecondary) }
                    if (confirmLabel != null && onConfirm != null) {
                        TextButton(onClick = onConfirm) { Text(confirmLabel, color = accent.accent) }
                    }
                }
            }
        }
    }
}

/** One label/value line inside a detail list. */
@Composable
fun DetailRow(label: String, value: String, highlight: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            color = if (highlight) LocalAccent.current.accent else TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Thin divider matching the app's palette. */
@Composable
fun HairLine() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Divider))
}

/** Selectable pill used by the style / timer pickers. */
@Composable
fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: String? = null,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                color = if (selected) accent.accent else TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                color = if (selected) accent.accent else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Scrollable dialog body with a bounded height. */
@Composable
fun DialogScrollBody(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
    ) { content() }
}
