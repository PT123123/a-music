package com.amusic.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amusic.MainApplication
import com.amusic.ui.library.PlaylistListContent
import com.amusic.ui.nav.Routes
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.Divider
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun MineScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as MainApplication
    val repo = app.repository
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val favCount by repo.favoriteSongs.collectAsState(initial = emptyList())
    val trashCount by repo.trash.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(accent.accent.copy(alpha = 0.18f)),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("♪", color = accent.accent, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "我的音乐",
                        color = TextPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${playlists.size} 个歌单 · ${favCount.size} 首收藏",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            EntryRow(
                icon = Icons.Filled.Favorite,
                title = "我喜欢",
                subtitle = "${favCount.size} 首",
                onClick = { nav.navigate(Routes.FAVORITES) },
            )
            EntryRow(
                icon = Icons.Filled.DeleteOutline,
                title = "回收站",
                subtitle = if (trashCount.isEmpty()) "空" else "${trashCount.size} 首 · 可恢复",
                onClick = { nav.navigate(Routes.TRASH) },
            )
            EntryRow(
                icon = Icons.Filled.Settings,
                title = "播放器设置",
                subtitle = "均衡器 · 歌词 · 样式 · 定时",
                onClick = { nav.navigate(Routes.SETTINGS) },
            )

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = accent.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("我的歌单", color = accent.accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            PlaylistListContent(
                playlists,
                onPlaylist = { nav.navigate("playlist/${it.id}") },
                onCreate = { scope.launch { repo.createPlaylist(it) } },
            )
        }
    }
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = Divider, style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.padding(top = 4.dp))
}
