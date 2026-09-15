package com.amusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.player.PlayerController
import com.amusic.ui.components.ART_PX
import com.amusic.ui.components.artRequest
import com.amusic.ui.nav.Routes
import com.amusic.ui.theme.Background
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.SurfaceVariant
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import com.amusic.ui.theme.songGlow
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(nav: NavHostController, modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val track = state.current ?: return
    val accent = LocalAccent.current
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val scope = rememberCoroutineScope()

    val audioPath = remember(track.uri) {
        if (track.uri.startsWith("file://")) track.uri.removePrefix("file://") else null
    }
    var isFav by remember { mutableStateOf(false) }
    LaunchedEffect(track.uri) {
        isFav = audioPath?.let { app.repository.isFavorite(it) } ?: false
    }

    val glow = remember(track.title) { songGlow(accent, track.title).first }

    // The mini player is drawn outside the NavHost and stays on screen for every route
    // except now-playing — including the queue itself. So the queue button has to be a
    // *toggle*: without this, every tap pushed another play_queue entry and the user had
    // to press back once per tap to get out.
    val navBackStackEntry by nav.currentBackStackEntryAsState()
    val onQueue = navBackStackEntry?.destination?.route == Routes.PLAY_QUEUE

    Column(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    listOf(glow.copy(alpha = 0.55f), SurfaceVariant, Background),
                )
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { nav.navigate(Routes.NOW_PLAYING) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (track.albumArtUri.isNullOrBlank()) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(glow, Background))),
                contentAlignment = Alignment.Center,
            ) { Text("♪", color = TextPrimary) }
        } else {
            AsyncImage(
                model = artRequest(context, track.albumArtUri, ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.ic_notification),
                placeholder = painterResource(R.drawable.ic_notification),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                track.title,
                color = TextPrimary,
                maxLines = 1,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(track.artist, color = TextSecondary, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = {
            val p = audioPath ?: return@IconButton
            scope.launch {
                app.repository.toggleFavorite(track.songId, p)
                isFav = app.repository.isFavorite(p)
            }
        }) {
            Icon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFav) "取消收藏" else "收藏",
                tint = if (isFav) accent.accent else TextSecondary,
            )
        }
        IconButton(onClick = { PlayerController.prev() }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", tint = TextPrimary)
        }
        IconButton(onClick = { PlayerController.togglePlay() }) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                tint = accent.accent,
            )
        }
        IconButton(onClick = { PlayerController.next() }) {
            Icon(Icons.Filled.SkipNext, contentDescription = "下一首", tint = TextPrimary)
        }
        IconButton(onClick = {
            if (onQueue) {
                nav.popBackStack()
            } else {
                nav.navigate(Routes.PLAY_QUEUE) { launchSingleTop = true }
            }
        }) {
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = if (onQueue) "关闭播放列表" else "播放列表",
                tint = if (onQueue) accent.accent else TextSecondary,
            )
        }
    }
    }

    // Thin progress strip — gives the user a constant sense of "how far through
    // the song am I" without crowding the row. Track is transparent so the
    // gradient underneath shows through where there's no progress yet.
    if (state.durationMs > 0) {
        val fraction = (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = accent.accent,
            trackColor = Color.Transparent,
        )
    }
}
