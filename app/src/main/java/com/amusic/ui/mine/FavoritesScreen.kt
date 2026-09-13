package com.amusic.ui.mine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amusic.MainApplication
import com.amusic.data.model.Song
import com.amusic.player.PlayerController
import com.amusic.ui.components.AddToPlaylistDialog
import com.amusic.ui.components.SongDetailHost
import com.amusic.ui.components.SongRow
import com.amusic.ui.nav.Routes
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** "我喜欢" — the user's liked songs, newest first. */
@Composable
fun FavoritesScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as MainApplication
    val repo = app.repository
    val songs by repo.favoriteSongs.collectAsState(initial = emptyList())
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val favPaths by repo.favoritePaths.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current

    var detailSong by remember { mutableStateOf<Song?>(null) }
    var pendingSong by remember { mutableStateOf<Song?>(null) }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
                Text("我喜欢", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "${songs.size} 首",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = songs.isNotEmpty(),
                    onClick = { PlayerController.playQueue(songs.map { it.toTrack() }, 0) },
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = accent.accent)
                    Spacer(Modifier.size(6.dp))
                    Text("播放全部", color = accent.accent, fontWeight = FontWeight.Medium)
                }
            }

            if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = accent.accent.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text("还没有收藏的歌曲", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "在歌曲列表或播放页点 ♥ 即可收藏",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(songs, key = { _, s -> s.id }) { idx, song ->
                        SongRow(
                            song = song,
                            isFavorite = song.data in favPaths,
                            onClick = { PlayerController.playQueue(songs.map { it.toTrack() }, idx) },
                            onToggleFavorite = { scope.launch { repo.toggleFavorite(song.id, song.data) } },
                            onAddToPlaylist = { pendingSong = song },
                            onShowDetail = { detailSong = song },
                            onArtistClick = { nav.navigate(Routes.artist(it)) },
                        )
                    }
                }
            }
        }
    }

    SongDetailHost(
        song = detailSong,
        isFavorite = detailSong?.let { it.data in favPaths } ?: false,
        onDismiss = { detailSong = null },
        onToggleFavorite = {
            detailSong?.let { s -> scope.launch { repo.toggleFavorite(s.id, s.data) } }
        },
    )

    AddToPlaylistDialog(
        song = pendingSong,
        playlists = playlists,
        onDismiss = { pendingSong = null },
        onPick = { pl ->
            val s = pendingSong ?: return@AddToPlaylistDialog
            scope.launch { repo.addToPlaylist(pl.id, s); pendingSong = null }
        },
        onCreate = { name ->
            val s = pendingSong ?: return@AddToPlaylistDialog
            scope.launch {
                val id = repo.createPlaylist(name)
                repo.addToPlaylist(id, s)
                pendingSong = null
            }
        },
    )
}
