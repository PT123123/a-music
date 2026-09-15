package com.amusic.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.player.PlayerController
import com.amusic.ui.components.SongRow
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Play Queue Screen - shows the current playlist/queue being played.
 * Can be accessed from the mini player or now playing screen.
 */
@Composable
fun PlayQueueScreen(nav: NavHostController) {
    val state by PlayerController.state.collectAsState()
    val accent = LocalAccent.current
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val favPaths by app.repository.favoritePaths.collectAsState(initial = emptySet())
    
    val queue = state.playlist
    val currentIndex = state.index
    
    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "播放列表",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${queue.size} 首歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                
                if (queue.isNotEmpty()) {
                    IconButton(onClick = { 
                        // Clear queue but keep current track playing
                        PlayerController.clearQueue()
                    }) {
                        Icon(
                            Icons.Filled.ClearAll,
                            contentDescription = "清空列表",
                            tint = TextSecondary
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            if (queue.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "播放列表为空",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "去音乐馆选择歌曲播放",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                // Queue list — fills the remaining vertical space. The mini player is
                // rendered once at the AppNav level (see AppNav.kt) so we don't repeat
                // it here; otherwise the user sees two stacked mini players.
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(
                        items = queue,
                        key = { index, track -> "${track.uri}_$index" }
                    ) { index, track ->
                        QueueItem(
                            track = track,
                            isPlaying = index == currentIndex,
                            isFavorite = track.uri in favPaths,
                            onClick = { 
                                PlayerController.playQueue(queue, index)
                            },
                            onRemove = {
                                PlayerController.removeFromQueue(index)
                            },
                            onToggleFavorite = {
                                GlobalScope.launch {
                                    val song = app.repository.findSongByPath(track.uri)
                                    if (song != null) {
                                        app.repository.toggleFavorite(song.id, song.data)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single item in the play queue.
 */
@Composable
private fun QueueItem(
    track: com.amusic.player.Track,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val accent = LocalAccent.current
    val backgroundColor by animateColorAsState(
        targetValue = if (isPlaying) accent.accent.copy(alpha = 0.15f) else Color.Transparent,
        label = "queue_item_bg"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Playing indicator
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaying) {
                // Animated playing bars
                PlayingBars(modifier = Modifier.size(20.dp))
            } else {
                // Drag handle for reorder
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "拖动排序",
                    tint = TextSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(Modifier.width(8.dp))
        
        // Album art
        AsyncImage(
            model = track.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
            error = androidx.compose.ui.res.painterResource(R.drawable.ic_notification)
        )
        
        Spacer(Modifier.width(12.dp))
        
        // Title and artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isPlaying) accent.accent else TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Actions
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.PlayArrow else Icons.Filled.Add,
                contentDescription = if (isFavorite) "已收藏" else "添加收藏",
                tint = if (isFavorite) accent.accent else TextSecondary
            )
        }
        
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "从列表移除",
                tint = TextSecondary
            )
        }
    }
}

/**
 * Animated playing indicator bars.
 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(3) { index ->
            val animDelay = index * 150
            val height = remember { mutableStateOf(8f) }
            
            androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (PlayerController.state.value.isPlaying) {
                    when (index) {
                        0 -> 16f
                        1 -> 12f
                        else -> 8f
                    }
                } else 6f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 300,
                    delayMillis = animDelay,
                ),
                label = "bar_$index"
            ).let { animHeight ->
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(animHeight.value.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent.accent)
                )
            }
        }
    }
}
