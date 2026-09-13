package com.amusic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amusic.player.PlayerController
import com.amusic.player.SleepMode

/**
 * Sleep-timer picker: fixed durations plus "stop after this song".
 */
@Composable
fun SleepTimerDialog(
    currentMinutes: Int,
    onPickMinutes: (Int) -> Unit,
    onPickEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sleep = PlayerController.sleep.value
    val activeMinutes = if (sleep.mode == SleepMode.TIMED) sleep.remainingMs else -1L

    AppDialog(title = "定时播放", onDismiss = onDismiss) {
        Column {
            listOf(10, 20, 30, 60, 90).forEach { m ->
                val selected = sleep.mode == SleepMode.TIMED && currentMinutes == m
                ChoicePill(
                    text = "$m 分钟",
                    selected = selected,
                    trailing = if (selected && activeMinutes > 0) "剩 ${fmtRemaining(activeMinutes)}" else null,
                    onClick = { onPickMinutes(m) },
                )
            }
            Spacer(Modifier.height(4.dp))
            ChoicePill(
                text = "播完当前这首",
                selected = sleep.mode == SleepMode.END_OF_TRACK,
                subtitle = "不再自动播放下一首",
                onClick = onPickEndOfTrack,
            )
            if (sleep.mode != SleepMode.OFF) {
                Spacer(Modifier.height(4.dp))
                ChoicePill(text = "关闭定时", selected = false, onClick = onCancelTimer)
            }
        }
    }
}

fun fmtRemaining(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
