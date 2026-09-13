package com.amusic.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.data.model.Song
import com.amusic.ui.components.ART_PX
import com.amusic.ui.components.artRequest
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DANGER = Color(0xFFE57373)

/**
 * 回收站 — where deleted songs wait before they are gone for good.
 *
 * Deleting from the library only sets `trashedAt`, so this screen can put a song back with a
 * single UPDATE and nothing on disk has been touched. 彻底删除 is the destructive path: it
 * removes the row, tries to delete the file, and leaves a tombstone so a rescan cannot bring
 * the song back.
 */
@Composable
fun TrashScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as MainApplication
    val repo = app.repository
    val songs by repo.trash.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var confirmPurge by remember { mutableStateOf<Song?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
                Text("回收站", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (songs.isNotEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }) {
                        Text("清空", color = DANGER)
                    }
                }
            }

            Text(
                "移入回收站的歌曲不会删文件，随时可以恢复。彻底删除会移除记录（并尽量删掉文件），之后重新扫描也不会再出现。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("回收站是空的", color = TextSecondary)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
                    items(songs, key = { it.id }) { song ->
                        TrashRow(
                            song = song,
                            onRestore = { scope.launch { repo.restore(listOf(song)) } },
                            onPurge = { confirmPurge = song },
                        )
                    }
                }
            }
        }
    }

    confirmPurge?.let { song ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text("彻底删除", color = TextPrimary) },
            text = {
                Text(
                    "《${song.title}》将从曲库永久移除，此操作无法撤销。",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = song
                    confirmPurge = null
                    scope.launch { repo.purge(listOf(target)) }
                }) { Text("彻底删除", color = DANGER) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = null }) { Text("取消", color = TextPrimary) }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站", color = TextPrimary) },
            text = {
                Text("${songs.size} 首将被永久移除，此操作无法撤销。", color = TextSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    scope.launch { repo.emptyTrash() }
                }) { Text("清空", color = DANGER) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("取消", color = TextPrimary) }
            },
        )
    }
}

@Composable
private fun TrashRow(
    song: Song,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artRequest(context, song.albumArtUri, ART_PX),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            error = painterResource(R.drawable.ic_notification),
            placeholder = painterResource(R.drawable.ic_notification),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                song.artist.ifBlank { "未知歌手" },
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOf(fmtSize(song.size), fmtDate(song.trashedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(Icons.Filled.RestoreFromTrash, contentDescription = "恢复", tint = accent.accent)
        }
        IconButton(onClick = onPurge) {
            Icon(Icons.Filled.DeleteForever, contentDescription = "彻底删除", tint = DANGER)
        }
    }
}

/** Compact byte size, e.g. `12.3 MB`. */
internal fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}

private val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

private fun fmtDate(at: Long?): String =
    if (at == null || at <= 0) "" else "删除于 " + stamp.format(Date(at))
