package com.amusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.amusic.MainApplication
import com.amusic.data.model.Song
import com.amusic.ui.components.AddToPlaylistDialog
import com.amusic.ui.components.EmptyHint
import com.amusic.ui.components.TopBar
import com.amusic.ui.nav.Routes
import com.amusic.ui.theme.Background
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(nav: NavHostController, id: Long) {
    val repo = (LocalContext.current.applicationContext as MainApplication).repository
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val playlistName = playlists.find { it.id == id }?.name ?: "歌单"
    val songs by repo.playlistSongs(id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var pendingSong by remember { mutableStateOf<Song?>(null) }

    Column(Modifier.fillMaxSize().background(Background)) {
        TopBar(playlistName, onBack = { nav.popBackStack() })
        if (songs.isEmpty()) EmptyHint("歌单还是空的，去歌曲页长按 + 添加吧")
        else SongListContent(
            songs,
            onPlay = { playAll(songs, it) },
            onAdd = { pendingSong = it },
            onArtistClick = { nav.navigate(Routes.artist(it)) },
        )
    }

    AddToPlaylistDialog(
        song = pendingSong,
        playlists = playlists,
        onDismiss = { pendingSong = null },
        onPick = { pl -> scope.launch { repo.addToPlaylist(pl.id, pendingSong!!); pendingSong = null } },
        onCreate = { name -> scope.launch { val id = repo.createPlaylist(name); repo.addToPlaylist(id, pendingSong!!); pendingSong = null } },
    )
}
