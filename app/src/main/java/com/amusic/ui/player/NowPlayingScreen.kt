package com.amusic.ui.player

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.data.lyrics.LyricLine
import com.amusic.data.prefs.PlayerStyle
import com.amusic.player.DesktopLyrics
import com.amusic.player.PlayerController
import com.amusic.player.SleepMode
import com.amusic.ui.components.ART_PX
import com.amusic.ui.components.EqualizerPanel
import com.amusic.ui.components.SleepTimerDialog
import com.amusic.ui.components.SongDetailHost
import com.amusic.ui.components.StylePickerDialog
import com.amusic.ui.components.artRequest
import com.amusic.ui.components.fmtRemaining
import com.amusic.ui.theme.AccentPalette
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.Background
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.Surface as SurfaceColor
import com.amusic.ui.theme.songGlow
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "NowPlaying"

@Composable
fun NowPlayingScreen(nav: NavHostController) {
    val state by PlayerController.state.collectAsState()
    val sleep by PlayerController.sleep.collectAsState()
    val track = state.current ?: run {
        nav.popBackStack()
        return
    }
    val isPlaying = state.isPlaying
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val accent = LocalAccent.current
    val style by app.settings.playerStyle.collectAsState()
    val accentId by app.settings.accentId.collectAsState()
    val defaultSleepMinutes by app.settings.sleepMinutes.collectAsState()
    val scope = rememberCoroutineScope()

    var showLyrics by remember { mutableStateOf(style == PlayerStyle.MINIMAL) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showEqDialog by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }

    val audioPath = remember(track.uri) { pathOf(track.uri) }

    // Lyrics are resolved app-wide by LyricCenter — the notification line and the floating
    // desktop window need them while no UI is open — so this screen only reads the shared state.
    val lyrics by app.lyricCenter.lines.collectAsState()
    val loadingLyrics by app.lyricCenter.loading.collectAsState()
    val desktopLyricsOn by app.settings.desktopLyrics.collectAsState()

    // Keep the favourite heart in sync with the DB.
    LaunchedEffect(track.uri) {
        isFav = audioPath?.let { app.repository.isFavorite(it) } ?: false
    }

    // Switching layout resets the artwork/lyrics default for that style.
    LaunchedEffect(style) {
        showLyrics = style == PlayerStyle.MINIMAL
    }

    val pos = state.positionMs
    // The hub already tracks the highlight off the same position stream, so reuse its index
    // instead of recomputing it here.
    val currentIndex by app.lyricCenter.index.collectAsState()
    val onSeek: (Long) -> Unit = remember { { PlayerController.seekTo(it) } }

    val glow = remember(track.title) { songGlow(accent, track.title) }

    AuroraBackground(
        modifier = Modifier.fillMaxSize(),
        seed = track.title,
        palette = accent,
        intensity = 0.95f,
    ) {
        // Album art layer (only when we actually have artwork; QQ songs have none).
        // The decode size is capped: this layer is blurred to within an inch of its life,
        // so full-resolution textures buy nothing and only cost GPU memory.
        val artModel = artRequest(context, track.albumArtUri, ART_PX)
        if (artModel != null) {
            AsyncImage(
                model = artModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(70.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.30f),
                            0.5f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.70f),
                        )
                    )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ---- header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                Text("正在播放", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLyrics = !showLyrics }) {
                    Text(
                        if (showLyrics) "封面" else "歌词",
                        color = accent.accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // ---- middle area ----
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (showLyrics) {
                    LyricsView(
                        lines = lyrics,
                        currentIndex = currentIndex,
                        loading = loadingLyrics,
                        accent = accent,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MusicArt(
                        style = style,
                        artModel = artModel,
                        isPlaying = isPlaying,
                        accent = accent,
                        glow = glow,
                        onTap = { showLyrics = true },
                    )
                }
            }

            // ---- title / artist ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        color = TextPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        track.artist,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                }
                if (sleep.mode != SleepMode.OFF) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Timer, contentDescription = null, tint = accent.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (sleep.mode == SleepMode.TIMED) fmtRemaining(sleep.remainingMs) else "本曲后",
                            color = accent.accent,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---- progress ----
            val dur = state.durationMs
            Slider(
                value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { PlayerController.seekTo((it * dur.toFloat()).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = accent.accent,
                    activeTrackColor = accent.accent,
                    inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatMs(pos), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(formatMs(dur), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(4.dp))

            // ---- transport ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { PlayerController.prev() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", tint = TextPrimary, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(24.dp))
                Box(
                    Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(accent.accent)
                        .clickable { PlayerController.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = accent.onAccent,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                IconButton(onClick = { PlayerController.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首", tint = TextPrimary, modifier = Modifier.size(36.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- volume ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = state.volume / 100f,
                    onValueChange = { PlayerController.setVolume((it * 100).toInt()) },
                    colors = SliderDefaults.colors(
                        thumbColor = accent.accent,
                        activeTrackColor = accent.accent,
                        inactiveTrackColor = TextSecondary.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.volume}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(26.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---- action row ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerAction(
                    icon = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (isFav) "已收藏" else "收藏",
                    tint = if (isFav) accent.accent else TextSecondary,
                    onClick = {
                        val p = audioPath ?: return@PlayerAction
                        scope.launch {
                            app.repository.toggleFavorite(track.songId, p)
                            isFav = app.repository.isFavorite(p)
                        }
                    },
                )
                PlayerAction(
                    icon = Icons.Filled.Timer,
                    label = if (sleep.mode == SleepMode.OFF) "定时" else fmtRemaining(sleep.remainingMs),
                    tint = if (sleep.mode == SleepMode.OFF) TextSecondary else accent.accent,
                    onClick = { showSleepDialog = true },
                )
                PlayerAction(
                    icon = Icons.Filled.Palette,
                    label = "样式",
                    tint = TextSecondary,
                    onClick = { showStyleDialog = true },
                )
                PlayerAction(
                    icon = Icons.Filled.Equalizer,
                    label = "均衡器",
                    tint = TextSecondary,
                    onClick = { showEqDialog = true },
                )
                PlayerAction(
                    icon = Icons.Filled.Subtitles,
                    label = "桌面歌词",
                    tint = if (desktopLyricsOn) accent.accent else TextSecondary,
                    onClick = {
                        val on = !desktopLyricsOn
                        app.settings.setDesktopLyrics(on)
                        if (on && !DesktopLyrics.canShow(context)) {
                            Toast.makeText(
                                context,
                                "还需要「显示在其他应用上层」权限：我的 → 播放器设置 → 桌面歌词",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                PlayerAction(
                    icon = Icons.Filled.Info,
                    label = "详情",
                    tint = TextSecondary,
                    onClick = { showDetail = true },
                )
            }

            Spacer(Modifier.height(6.dp))
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            currentMinutes = defaultSleepMinutes,
            onPickMinutes = {
                app.settings.setSleepMinutes(it)
                PlayerController.startSleepTimer(it)
                showSleepDialog = false
            },
            onPickEndOfTrack = {
                PlayerController.sleepAtEndOfTrack()
                showSleepDialog = false
            },
            onCancelTimer = {
                PlayerController.cancelSleepTimer()
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false },
        )
    }

    if (showStyleDialog) {
        StylePickerDialog(
            currentStyle = style,
            currentAccentId = accentId,
            onPickStyle = { app.settings.setPlayerStyle(it) },
            onPickAccent = { app.settings.setAccentId(it) },
            onDismiss = { showStyleDialog = false },
        )
    }

    // Wide dialog: eight band sliders need the room, so it opts out of the platform width cap.
    if (showEqDialog) {
        Dialog(
            onDismissRequest = { showEqDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            M3Surface(
                shape = RoundedCornerShape(22.dp),
                color = SurfaceColor,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(0.95f),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "均衡器",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    EqualizerPanel(app.settings)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEqDialog = false }) {
                            Text("关闭", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }

    // Locate the Song behind the current track so we can probe its parameters.
    val detailSong by app.repository.songs.collectAsState(initial = emptyList())
    val currentSong = remember(detailSong, track.uri) {
        detailSong.firstOrNull { song ->
            audioPath != null && song.data == audioPath
        }
    }
    if (showDetail && currentSong != null) {
        SongDetailHost(
            song = currentSong,
            isFavorite = isFav,
            onDismiss = { showDetail = false },
            onToggleFavorite = {
                val p = audioPath ?: return@SongDetailHost
                scope.launch {
                    app.repository.toggleFavorite(track.songId, p)
                    isFav = app.repository.isFavorite(p)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// artwork per style
// ---------------------------------------------------------------------------

@Composable
private fun MusicArt(
    style: PlayerStyle,
    artModel: Any?,
    isPlaying: Boolean,
    accent: AccentPalette,
    glow: Triple<Color, Color, Color>,
    onTap: () -> Unit,
) {
    // Read in the layer/draw phase only (see the graphicsLayer below) so the spinning
    // disc does not recompose its subtree 20x per second.
    val rotation = rememberDiscRotation(isPlaying)
    when (style) {
        PlayerStyle.DISC -> Box(
            Modifier.size(290.dp).clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            // vinyl body
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.05f),
                            )
                        )
                    )
            )
            ArtImage(
                model = artModel,
                accent = accent,
                glow = glow.first,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(26.dp)
                    .clip(CircleShape)
                    .graphicsLayer { rotationZ = rotation.value },
            )
            // spindle hole
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Background)
            )
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            )
        }

        PlayerStyle.COVER -> Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            ArtImage(
                model = artModel,
                accent = accent,
                glow = glow.first,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .shadow(26.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp)),
            )
        }

        PlayerStyle.MINIMAL -> Box(
            Modifier.fillMaxSize().clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            ArtImage(
                model = artModel,
                accent = accent,
                glow = glow.first,
                modifier = Modifier
                    .size(180.dp)
                    .shadow(20.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
    }
}

/** Album art with a generated gradient tile fallback (most QQ downloads have no art). */
@Composable
private fun ArtImage(
    model: Any?,
    accent: AccentPalette,
    glow: Color,
    modifier: Modifier,
) {
    if (model == null) {
        Box(
            modifier.background(
                Brush.linearGradient(
                    listOf(glow, accent.glowB.copy(alpha = 0.85f), Background),
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", color = Color.White.copy(alpha = 0.85f), fontSize = 64.sp)
        }
    } else {
        AsyncImage(
            model = model,
            contentDescription = "封面",
            modifier = modifier,
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.ic_notification),
            placeholder = painterResource(R.drawable.ic_notification),
        )
    }
}

/** Small labelled icon button used in the player's action row. */
@Composable
private fun PlayerAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------------------
// lyrics
// ---------------------------------------------------------------------------

/**
 * QQ-Music-style scrolling lyric view: the active line is highlighted in the accent
 * colour and kept centred, other lines are dimmed. Tapping a line seeks to it.
 */
@Composable
private fun LyricsView(
    lines: List<LyricLine>,
    currentIndex: Int,
    loading: Boolean,
    accent: AccentPalette,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(if (loading) "歌词加载中…" else "暂无歌词", color = TextSecondary)
        }
        return
    }

    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val halfViewport = maxHeight / 2
        val centerPx = with(density) { halfViewport.roundToPx() }
        val listState = rememberLazyListState()

        // Keep the active line dead-centre. `scrollOffset` alone is not trustworthy here:
        // it is interpreted relative to the item's own slot, and with a huge top
        // contentPadding plus not-yet-measured items the result can land half a screen
        // low (the reported "active line stuck behind the controls" bug). So we scroll
        // first, then measure where the item actually landed and correct the remainder.
        LaunchedEffect(currentIndex, centerPx) {
            if (currentIndex < 0 || centerPx <= 0) return@LaunchedEffect
            listState.animateScrollToItem(currentIndex, scrollOffset = -centerPx)
            val item = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentIndex }
                ?: return@LaunchedEffect
            val delta = item.offset - centerPx
            if (abs(delta) > 1) {
                Log.d(TAG, "lyric line $currentIndex off by ${delta}px -> correcting")
                listState.scrollBy(delta.toFloat())
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = halfViewport),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                val active = i == currentIndex
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.timeMs) }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        line.text,
                        color = if (active) accent.accent else TextSecondary,
                        style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!line.translation.isNullOrBlank()) {
                        Text(
                            line.translation,
                            color = if (active) TextPrimary.copy(alpha = 0.85f) else TextSecondary.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Index of the last line whose timestamp is <= [posMs], or -1 before the first line. */
private fun activeLineIndex(lines: List<LyricLine>, posMs: Long): Int {
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= posMs) idx = i else break
    }
    return idx
}

private fun pathOf(uri: String): String? =
    if (uri.startsWith("file://")) uri.removePrefix("file://") else null

/** Smoothly increments a rotation angle while playing and holds still while paused. */
@Composable
private fun rememberDiscRotation(isPlaying: Boolean): State<Float> {
    val rotation = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            delay(50)
            rotation.floatValue = (rotation.floatValue + 1.2f) % 360f
        }
    }
    return rotation
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
