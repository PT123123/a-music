package com.amusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amusic.data.model.Playlist
import com.amusic.data.model.Song
import com.amusic.ui.theme.QQGreen
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

/**
 * Picker to add [song] to an existing playlist or create a new one.
 */
@Composable
fun AddToPlaylistDialog(
    song: Song?,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
) {
    if (song == null) return

    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到歌单", color = TextPrimary) },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("新建歌单名称", color = TextPrimary.copy(alpha = 0.5f))
                        inner()
                    },
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(playlists) { pl ->
                        Text(
                            pl.name,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) }
            ) { Text("新建并添加", color = QQGreen) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextPrimary) }
        },
    )
}

/**
 * Same picker for the library's multi-select mode: "把选中的 [count] 首加进哪个歌单".
 * Shown whenever [count] > 0 so callers can drive it straight from their selection state.
 */
@Composable
fun AddManyToPlaylistDialog(
    count: Int,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
) {
    if (count <= 0) return

    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 $count 首到歌单", color = TextPrimary) },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("新建歌单名称", color = TextPrimary.copy(alpha = 0.5f))
                        inner()
                    },
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(playlists) { pl ->
                        Text(
                            pl.name,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    if (playlists.isEmpty()) {
                        item {
                            Text(
                                "还没有歌单，在上面的输入框里起个名字即可",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) }
            ) { Text("新建并添加", color = QQGreen) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextPrimary) }
        },
    )
}
