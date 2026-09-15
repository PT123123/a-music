package com.amusic.ui.player

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.data.lyrics.LyricLine
import com.amusic.data.prefs.PlayerStyle
import com.amusic.data.prefs.SettingsRepository
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
import kotlin.math.exp
import kotlin.math.roundToInt

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
    val lyricsScale by app.settings.lyricsScale.collectAsState()

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
            if (showLyrics) {
                LyricsView(
                    lines = lyrics,
                    currentIndex = currentIndex,
                    loading = loadingLyrics,
                    accent = accent,
                    onSeek = onSeek,
                    scale = lyricsScale,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
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
            // In lyrics mode the controls shrink so the freed space goes to the lyrics —
            // QQ-Music-style. The size changes animate, so the switch reads as one motion.
            val compact = showLyrics
            val playBtnSize by animateDpAsState(if (compact) 48.dp else 68.dp, label = "playBtn")
            val playIconSize by animateDpAsState(if (compact) 24.dp else 34.dp, label = "playIcon")
            val skipIconSize by animateDpAsState(if (compact) 24.dp else 36.dp, label = "skipIcon")
            val transportGap by animateDpAsState(if (compact) 14.dp else 24.dp, label = "transportGap")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { PlayerController.prev() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", tint = TextPrimary, modifier = Modifier.size(skipIconSize))
                }
                Spacer(Modifier.width(transportGap))
                Box(
                    Modifier
                        .size(playBtnSize)
                        .clip(CircleShape)
                        .background(accent.accent)
                        .clickable { PlayerController.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = accent.onAccent,
                        modifier = Modifier.size(playIconSize),
                    )
                }
                Spacer(Modifier.width(transportGap))
                IconButton(onClick = { PlayerController.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首", tint = TextPrimary, modifier = Modifier.size(skipIconSize))
                }
            }

            // ---- lyrics font size (lyrics mode only, kept deliberately tiny) ----
            if (showLyrics) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { app.settings.setLyricsScale(lyricsScale - 0.1f) },
                        enabled = lyricsScale > SettingsRepository.LYRICS_SCALE_MIN + 0.01f,
                    ) {
                        Text("A−", color = TextSecondary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "${(lyricsScale * 100).roundToInt()}%",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(46.dp),
                    )
                    TextButton(
                        onClick = { app.settings.setLyricsScale(lyricsScale + 0.1f) },
                        enabled = lyricsScale < SettingsRepository.LYRICS_SCALE_MAX - 0.01f,
                    ) {
                        Text("A+", color = TextSecondary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // Lyrics mode hides these entirely — that room belongs to the lyrics. The
            // collapse is animated, so the transport/progress above glide down into their
            // compact spots (QQ-Music behaviour).
            AnimatedVisibility(
                visible = !showLyrics,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
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
                }
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

/** Vertical gap between two lyric lines. */
private val LYRIC_LINE_GAP = 14.dp

/**
 * Time constant of the scroll follower, in seconds. This is not a duration: the position eats
 * ~63% of the remaining distance every [LYRIC_FOLLOW_TAU_S], so every move eases out and the
 * feel does not depend on the frame rate.
 */
private const val LYRIC_FOLLOW_TAU_S = 0.17f

/** A jump longer than this many viewports is not animated — a seek just lands. */
private const val LYRIC_SNAP_VIEWPORTS = 1.3f

/** How long the list stays where the finger left it before sliding back to the active line. */
private const val LYRIC_HOLD_AFTER_DRAG_NANOS = 2_200_000_000L

/**
 * Scroll bookkeeping for the lyric view.
 *
 * [centreOf] is written by the layout pass and read by the animation loop, so the scroll target
 * is arithmetic rather than a guess. Only [offsetPx] is snapshot state: it is the one value read
 * while drawing, everything else is plain fields no composition ever observes.
 */
private class LyricScroll {
    /** Line index -> the y of that line's centre inside the lyric column, in px. */
    val centreOf = HashMap<Int, Float>()

    /** Current translation of the column: how far it is pulled up, in px. */
    val offsetPx = mutableFloatStateOf(0f)

    var dragging = false
    var holdUntilNanos = 0L
    var primed = false
    var lastFrameNanos = 0L

    /** Where line [index] has to sit for its centre to land on the viewport's centre. */
    fun offsetFor(index: Int, viewportPx: Float): Float? =
        centreOf[index]?.let { it - viewportPx / 2f }

    /** Index of the line nearest to [contentY] (a y in column coordinates), if measured yet. */
    fun lineAt(contentY: Float): Int? {
        var found = -1
        var closest = Float.MAX_VALUE
        centreOf.forEach { (index, centre) ->
            val distance = abs(centre - contentY)
            if (distance < closest) {
                closest = distance
                found = index
            }
        }
        return if (found >= 0) found else null
    }

    /** Hand the position back to the song, but only a moment after the finger is gone. */
    fun releaseForAWhile() {
        dragging = false
        holdUntilNanos = System.nanoTime() + LYRIC_HOLD_AFTER_DRAG_NANOS
    }

    /** Keep a drag inside the song: never past the first or the last line. */
    fun clampToSong(offset: Float, viewportPx: Float, lastIndex: Int): Float {
        val first = centreOf[0] ?: return offset
        val last = centreOf[lastIndex] ?: return offset
        return offset.coerceIn(
            minOf(first, last) - viewportPx / 2f,
            maxOf(first, last) - viewportPx / 2f,
        )
    }
}

/**
 * QQ-Music-style scrolling lyric view: the line being sung sits dead centre in the lyric area,
 * highlighted in the accent colour, with the rest dimmed. Tapping a line seeks to it and dragging
 * browses by hand; a moment after the finger goes up the list slides back to the active line.
 *
 * Keeping the active line exactly centred is the reason this no longer scrolls a lazy list by
 * index: every line reports where its own centre ended up, so the column is simply offset by
 * `lineCentre - viewport / 2`. A frame loop then eases the real position towards that target, so
 * the lyrics move continuously instead of snapping once per line — which also hides the fact that
 * mpv only reports its playback position about once a second.
 *
 * The gestures deliberately live on the fixed viewport rather than on the moving column: the
 * column keeps being translated, and a touch target that travels with it is a pain to hit.
 *
 * [scale] multiplies every font size (A− / A+ on the lyrics page).
 */
@Composable
private fun LyricsView(
    lines: List<LyricLine>,
    currentIndex: Int,
    loading: Boolean,
    accent: AccentPalette,
    onSeek: (Long) -> Unit,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(if (loading) "歌词加载中…" else "暂无歌词", color = TextSecondary)
        }
        return
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxHeight.toPx() }
        val scroll = remember(lines) { LyricScroll() }
        // Before the first line starts (index -1) the intro scrolls to line 0, so the song always
        // opens with its first line in the middle instead of an empty area.
        val activeIndex = rememberUpdatedState(if (currentIndex >= 0) currentIndex else 0)

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(lines, scroll) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            scroll.dragging = true
                            scroll.holdUntilNanos = 0L
                        },
                        onDragEnd = { scroll.releaseForAWhile() },
                        onDragCancel = { scroll.releaseForAWhile() },
                    ) { change, dragAmount ->
                        change.consume()
                        scroll.offsetPx.floatValue = scroll.clampToSong(
                            offset = scroll.offsetPx.floatValue - dragAmount,
                            viewportPx = viewportPx,
                            lastIndex = lines.lastIndex,
                        )
                    }
                }
                .pointerInput(lines, scroll) {
                    detectTapGestures { at ->
                        val line = scroll.lineAt(at.y + scroll.offsetPx.floatValue)
                        if (line != null) onSeek(lines[line].timeMs)
                    }
                },
        ) {
            LyricStack(
                lines = lines,
                currentIndex = currentIndex,
                accent = accent,
                scale = scale,
                scroll = scroll,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = -scroll.offsetPx.floatValue },
            )
        }

        LaunchedEffect(lines, viewportPx) {
            scroll.primed = false
            scroll.lastFrameNanos = 0L
            while (true) {
                withFrameNanos { now ->
                    val previous = scroll.lastFrameNanos
                    scroll.lastFrameNanos = now
                    val dt = if (previous == 0L) 1f / 60f
                    else ((now - previous) / 1_000_000_000.0).toFloat()

                    val target = scroll.offsetFor(activeIndex.value, viewportPx)
                        ?: return@withFrameNanos
                    val current = scroll.offsetPx.floatValue
                    when {
                        // The finger owns the position, and keeps it briefly after lifting.
                        scroll.dragging || now < scroll.holdUntilNanos -> Unit

                        // First paint, a seek, or a re-layout that moved everything: land in one
                        // step rather than flying across the whole song.
                        !scroll.primed || abs(target - current) > viewportPx * LYRIC_SNAP_VIEWPORTS -> {
                            scroll.offsetPx.floatValue = target
                            scroll.primed = true
                        }

                        else -> {
                            val follow = 1f - exp(-dt / LYRIC_FOLLOW_TAU_S)
                            scroll.offsetPx.floatValue = current + (target - current) * follow
                        }
                    }
                }
            }
        }
    }
}

/**
 * The lyric lines, stacked in a single column.
 *
 * The stack is measured with an unbounded height so every line keeps its true position — that is
 * what makes exact centring possible: the measure pass hands each line's centre back to [scroll]
 * before anything is drawn. The node itself stays viewport-sized and the caller clips it, so the
 * lines outside simply wait there until the column slides them in.
 */
@Composable
private fun LyricStack(
    lines: List<LyricLine>,
    currentIndex: Int,
    accent: AccentPalette,
    scale: Float,
    scroll: LyricScroll,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            lines.forEachIndexed { i, line ->
                LyricRow(
                    line = line,
                    active = i == currentIndex,
                    accent = accent,
                    scale = scale,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = LYRIC_LINE_GAP.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }

        var y = 0
        placeables.forEachIndexed { i, placeable ->
            scroll.centreOf[i] = y + placeable.height / 2f
            y += placeable.height + gap
        }
        val contentHeight = (y - gap).coerceAtLeast(0)

        layout(constraints.maxWidth, constraints.maxHeight.coerceAtMost(contentHeight)) {
            var top = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, top)
                top += placeable.height + gap
            }
        }
    }
}

/** One lyric line: the sung one grows into the accent-coloured title style, the rest stay dim. */
@Composable
private fun LyricRow(
    line: LyricLine,
    active: Boolean,
    accent: AccentPalette,
    scale: Float,
) {
    // The emphasis is animated rather than swapped: changing the font size in one step would
    // shove every line below it up by a couple of pixels, right as the list is moving.
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing),
        label = "lyricEmphasis",
    )
    val idleStyle = MaterialTheme.typography.bodyMedium
    val activeStyle = MaterialTheme.typography.titleMedium
    val transStyle = MaterialTheme.typography.bodySmall

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            line.text,
            color = lerpColor(TextSecondary, accent.accent, emphasis),
            style = idleStyle.copy(
                fontSize = lerp(idleStyle.fontSize, activeStyle.fontSize, emphasis) * scale,
                lineHeight = lerp(idleStyle.lineHeight, activeStyle.lineHeight, emphasis) * scale,
            ),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!line.translation.isNullOrBlank()) {
            Text(
                line.translation,
                color = lerpColor(
                    TextSecondary.copy(alpha = 0.55f),
                    TextPrimary.copy(alpha = 0.85f),
                    emphasis,
                ),
                style = transStyle.copy(
                    fontSize = transStyle.fontSize * scale,
                    lineHeight = transStyle.lineHeight * scale,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
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
