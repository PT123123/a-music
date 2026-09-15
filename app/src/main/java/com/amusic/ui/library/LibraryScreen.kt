package com.amusic.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amusic.MainApplication
import com.amusic.data.model.Playlist
import com.amusic.data.model.Song
import com.amusic.data.prefs.LibSort
import com.amusic.player.PlayerController
import com.amusic.ui.components.AddManyToPlaylistDialog
import com.amusic.ui.components.AddToPlaylistDialog
import com.amusic.ui.components.SongDetailHost
import com.amusic.ui.components.SongRow
import com.amusic.ui.nav.Routes
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * One-shot "scroll the list to [index]" request. [seq] is bumped on every click so the
 * consumer's [androidx.compose.runtime.LaunchedEffect] re-runs even when the target index
 * hasn't changed — without it, a second click on the same song would be a no-op because
 * the state value would be identical.
 */
data class ScrollRequest(val index: Int, val seq: Long)

@Composable
fun LibraryScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as MainApplication
    val repo = app.repository
    val songs by repo.songs.collectAsState(initial = emptyList())
    val artists by repo.artists.collectAsState(initial = emptyList())
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val favorites by repo.favoriteSongs.collectAsState(initial = emptyList())
    val sort by app.settings.libSort.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current

    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    var scrollRequest by remember { mutableStateOf<ScrollRequest?>(null) }
    var scrollSeq by remember { mutableStateOf(0L) }

    // CJK-aware ordering: plain Unicode compare puts 中文 in code-point order, which
    // looks random; Collator sorts it the way a Chinese user expects.
    val collator = remember { Collator.getInstance(Locale.CHINA) }
    val q = query.trim()

    val shownSongs = remember(songs, q, sort) { filterAndSort(songs, q, sort, collator) }
    val shownArtists = remember(artists, q) {
        if (q.isEmpty()) artists else artists.filter { it.contains(q, ignoreCase = true) }
    }
    val shownPlaylists = remember(playlists, q) {
        if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }
    val shownFavorites = remember(favorites, q) {
        if (q.isEmpty()) favorites else favorites.filter { it.matches(q) }
    }
    val artistCounts = remember(songs) { songs.groupBy { it.artist }.mapValues { it.value.size } }

    val openArtist: (String) -> Unit = { nav.navigate(Routes.artist(it)) }
    val playing by PlayerController.state.collectAsState()

    AuroraBackground(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("音乐馆", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${songs.size} 首",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.size(8.dp))

                LibrarySearchField(query = query, onQuery = { query = it })

                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    contentColor = accent.accent,
                ) {
                    listOf("歌曲", "歌手", "歌单", "收藏").forEachIndexed { i, label ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = {
                                Text(
                                    label,
                                    color = if (tab == i) accent.accent else TextSecondary,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                        )
                    }
                }

                when (tab) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        SortRow(
                            sort = sort,
                            shown = shownSongs.size,
                            total = songs.size,
                            onSort = { app.settings.setLibSort(it) },
                        )
                        if (shownSongs.isEmpty()) {
                            EmptyHint(if (q.isEmpty()) "曲库还是空的，去「发现」下载或扫一遍本地音乐" else "没有匹配「$q」的歌曲")
                        } else {
                            SongListContent(
                                songs = shownSongs,
                                onPlay = { idx -> playAll(shownSongs, idx) },
                                onAdd = { pendingSong = it },
                                onArtistClick = openArtist,
                                selectionEnabled = true,
                                scrollRequest = scrollRequest,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    1 -> if (shownArtists.isEmpty()) {
                        EmptyHint(if (q.isEmpty()) "还没有识别到歌手" else "没有匹配「$q」的歌手")
                    } else {
                        ArtistListContent(shownArtists, artistCounts, openArtist)
                    }

                    2 -> PlaylistListContent(
                        shownPlaylists,
                        onPlaylist = { nav.navigate(Routes.playlist(it.id)) },
                        onCreate = { scope.launch { repo.createPlaylist(it) } },
                    )

                    else -> if (shownFavorites.isEmpty()) {
                        EmptyHint(
                            if (q.isEmpty()) "还没有收藏的歌曲，点歌曲右侧的 ♥ 收藏"
                            else "收藏里没有匹配「$q」的歌曲"
                        )
                    } else {
                        FavoriteTabContent(shownFavorites, onAdd = { pendingSong = it }, onArtistClick = openArtist)
                    }
                }
            }

            // Floating button to jump to now-playing screen.
            // Always shown so the user can find the player regardless of playback state.
            // The mini-player (in AppNav) only appears when something is playing, so a
            // dedicated entry point here means the user is never "locked out" of the
            // now-playing screen. Sits above the mini-player / tab bar by virtue of being
            // inside the content Box.
            //
            // Visual contract:
            //  - a compact circular crosshair-like FAB so it reads as "locate the current
            //    song" at a glance, without covering rows with a wide pill.
            //  - containerColor is always full accent (no alpha fade) so the FAB doesn't
            //    disappear into the aurora background.
            //  - icon switches between PlayCircle (idle) and GraphicEq (something is
            //    loaded) so the user can tell whether anything is queued.
            //  - bottom padding is bumped above the mini-player's ~56dp height so the
            //    FAB never overlaps the play controls even when both are visible.
            FloatingActionButton(
                onClick = {
                    val current = playing.current
                    if (tab == 0 && current != null) {
                        val idx = shownSongs.indexOfFirst { it.id == current.songId }
                        if (idx >= 0) {
                            scrollSeq++
                            scrollRequest = ScrollRequest(idx, scrollSeq)
                        } else {
                            nav.navigate(Routes.NOW_PLAYING)
                        }
                    } else {
                        nav.navigate(Routes.NOW_PLAYING)
                    }
                },
                containerColor = accent.accent,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp),
            ) {
                Icon(
                    if (playing.current != null) Icons.Filled.GraphicEq
                    else Icons.Filled.PlayCircle,
                    contentDescription = if (playing.current != null) "定位正在播放" else "跳到播放页",
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }

    AddToPlaylistDialog(
        song = pendingSong,
        playlists = playlists,
        onDismiss = { pendingSong = null },
        onPick = { pl -> scope.launch { repo.addToPlaylist(pl.id, pendingSong!!); pendingSong = null } },
        onCreate = { name -> scope.launch { val id = repo.createPlaylist(name); repo.addToPlaylist(id, pendingSong!!); pendingSong = null } },
    )
}

// ---------------------------------------------------------------- filtering

/** Free-text match across the three fields a user is likely to type. */
internal fun Song.matches(q: String): Boolean =
    title.contains(q, ignoreCase = true) ||
        artist.contains(q, ignoreCase = true) ||
        album.contains(q, ignoreCase = true)

internal fun filterAndSort(
    songs: List<Song>,
    query: String,
    sort: LibSort,
    collator: Collator,
): List<Song> {
    val base = if (query.isEmpty()) songs else songs.filter { it.matches(query) }
    return base.sortedWith(songComparator(sort, collator))
}

/**
 * Comparator for [sort]. Every branch falls back to the localised title so the result is
 * deterministic instead of depending on the incoming list order.
 */
private fun songComparator(sort: LibSort, collator: Collator): Comparator<Song> = when (sort) {
    LibSort.RECENT -> Comparator { a, b ->
        val byDate = b.dateAdded.compareTo(a.dateAdded)
        if (byDate != 0) byDate else collator.compare(a.title, b.title)
    }

    LibSort.TITLE -> Comparator { a, b -> collator.compare(a.title, b.title) }

    LibSort.ARTIST -> Comparator { a, b ->
        val byArtist = collator.compare(a.artist, b.artist)
        if (byArtist != 0) byArtist else collator.compare(a.title, b.title)
    }

    LibSort.ALBUM -> Comparator { a, b ->
        val byAlbum = collator.compare(a.album, b.album)
        if (byAlbum != 0) byAlbum else collator.compare(a.title, b.title)
    }

    LibSort.DURATION -> Comparator { a, b ->
        val byLength = a.durationMs.compareTo(b.durationMs)
        if (byLength != 0) byLength else collator.compare(a.title, b.title)
    }
}

// -------------------------------------------------------------- search field

@Composable
private fun LibrarySearchField(query: String, onQuery: (String) -> Unit) {
    val accent = LocalAccent.current
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text("搜索歌曲 / 歌手 / 专辑", color = TextSecondary) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清空", tint = TextSecondary)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = accent.accent.copy(alpha = 0.12f),
            unfocusedContainerColor = accent.accent.copy(alpha = 0.06f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = accent.accent,
        ),
    )
}

/** Sort selector + result counter, shown above the song list only. */
@Composable
private fun SortRow(
    sort: LibSort,
    shown: Int,
    total: Int,
    onSort: (LibSort) -> Unit,
) {
    val accent = LocalAccent.current
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { open = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Sort, contentDescription = null, tint = accent.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(sort.label, color = accent.accent, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = accent.accent, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                LibSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (s == sort) "✓ ${s.label}" else s.label,
                                color = if (s == sort) accent.accent else TextPrimary,
                            )
                        },
                        onClick = { onSort(s); open = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (shown == total) "$total 首" else "$shown / $total 首",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ------------------------------------------------------------------- lists

internal fun playAll(songs: List<Song>, index: Int) {
    if (songs.isEmpty()) return
    PlayerController.playQueue(songs.map { it.toTrack() }, index)
}

/**
 * Shared list of songs. Self-contained: reads its own favourite state, hosts the detail
 * sheet and — when [selectionEnabled] — owns the whole multi-select experience
 * (long-press to enter, check boxes, bulk actions), so callers stay one-liners.
 */
@Composable
internal fun SongListContent(
    songs: List<Song>,
    onPlay: (Int) -> Unit,
    onAdd: (Song) -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    selectionEnabled: Boolean = false,
    scrollRequest: ScrollRequest? = null,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as MainApplication
    val repo = app.repository
    val favPaths by repo.favoritePaths.collectAsState(initial = emptySet())
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var detailSong by remember { mutableStateOf<Song?>(null) }
    // Index of the row currently being highlighted by a "locate current song" request.
    var highlightIndex by remember { mutableStateOf<Int?>(null) }

    // Scroll to (and briefly highlight) the target item whenever the parent asks, e.g. via
    // the crosshair FAB. [ScrollRequest.seq] differs on every click so this re-runs even
    // when the target index is unchanged.
    LaunchedEffect(scrollRequest) {
        scrollRequest?.let { req ->
            if (req.index in songs.indices) {
                highlightIndex = req.index
                lazyListState.animateScrollToItem(req.index)
                delay(1600)
                highlightIndex = null
            }
        }
    }

    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showAddMany by remember { mutableStateOf(false) }
    // Songs waiting for the 从列表移除 / 删除文件 choice — one row from the long-press
    // menu, or the whole selection from the bulk bar.
    var deleteTargets by remember { mutableStateOf<List<Song>?>(null) }

    // Filtering or leaving the tab can drop rows out from under the selection.
    LaunchedEffect(songs) {
        val live = songs.map { it.id }.toSet()
        val pruned = selected.intersect(live)
        if (pruned.size != selected.size) selected = pruned
        if (selectionMode && pruned.isEmpty()) selectionMode = false
    }

    val chosen = remember(songs, selected) { songs.filter { it.id in selected } }
    val allLiked = chosen.isNotEmpty() && chosen.all { it.data in favPaths }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    // System back first leaves multi-select instead of leaving the screen.
    BackHandler(enabled = selectionMode) { exitSelection() }

    Column(modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionHeader(
                count = selected.size,
                total = songs.size,
                onSelectAll = { selected = songs.map { it.id }.toSet() },
                onClearAll = { selected = emptySet() },
                onExit = { exitSelection() },
            )
        }

        LazyColumn(Modifier.weight(1f), state = lazyListState) {
            itemsIndexed(songs, key = { _, s -> s.id }) { idx, song ->
                SongRow(
                    song = song,
                    isFavorite = song.data in favPaths,
                    highlighted = highlightIndex == idx,
                    onClick = { onPlay(idx) },
                    onToggleFavorite = { scope.launch { repo.toggleFavorite(song.id, song.data) } },
                    onAddToPlaylist = { onAdd(song) },
                    onShowDetail = { detailSong = song },
                    onArtistClick = onArtistClick,
                    selectionMode = selectionMode,
                    selected = song.id in selected,
                    onToggleSelect = {
                        selected = if (song.id in selected) selected - song.id else selected + song.id
                    },
                    menu = if (selectionEnabled) {
                        {
                            DropdownMenuItem(
                                text = { Text("加入歌单", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                onClick = { closeMenu(); onAdd(song) },
                            )
                            DropdownMenuItem(
                                text = { Text("查看详情", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                onClick = { closeMenu(); detailSong = song },
                            )
                            DropdownMenuItem(
                                text = { Text("多选", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                onClick = { closeMenu(); selectionMode = true; selected = setOf(song.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("删除…", color = Color(0xFFE57373)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(18.dp)) },
                                onClick = { closeMenu(); deleteTargets = listOf(song) },
                            )
                        }
                    } else null,
                )
            }
        }

        if (selectionMode) {
            SelectionActionBar(
                count = selected.size,
                allLiked = allLiked,
                onPlaylist = { if (chosen.isNotEmpty()) showAddMany = true },
                onFavorite = {
                    val target = chosen
                    scope.launch {
                        if (allLiked) repo.unlike(target) else repo.like(target)
                    }
                },
                onDelete = { if (chosen.isNotEmpty()) deleteTargets = chosen },
            )
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

    AddManyToPlaylistDialog(
        count = if (showAddMany) chosen.size else 0,
        playlists = playlists,
        onDismiss = { showAddMany = false },
        onPick = { pl ->
            val target = chosen
            showAddMany = false
            scope.launch { repo.addToPlaylist(pl.id, target) }
            exitSelection()
        },
        onCreate = { name ->
            val target = chosen
            showAddMany = false
            scope.launch {
                val id = repo.createPlaylist(name)
                repo.addToPlaylist(id, target)
            }
            exitSelection()
        },
    )

    // The one delete entry point — offers "remove from list (file kept, restorable in the
    // trash)" vs "delete the audio file (gone for good)".
    deleteTargets?.let { targets ->
        val danger = Color(0xFFE57373)
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text("删除 ${targets.size} 首歌曲", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "从列表移除",
                        color = LocalAccent.current.accent,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val t = targets
                                deleteTargets = null
                                exitSelection()
                                scope.launch { repo.moveToTrash(t) }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    Text(
                        "保留音频文件，之后可在「我的 → 回收站」恢复",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "删除文件",
                        color = danger,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val t = targets
                                deleteTargets = null
                                exitSelection()
                                scope.launch { repo.purge(t) }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    Text(
                        "同时把音频文件从磁盘删除，不可恢复",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteTargets = null }) { Text("取消", color = TextPrimary) }
            },
        )
    }
}

// ------------------------------------------------------------- selection UI

@Composable
private fun SelectionHeader(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onExit: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.accent.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Filled.Close, contentDescription = "退出多选", tint = TextPrimary)
        }
        Text("已选 $count 首", color = accent.accent, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = if (count == total) onClearAll else onSelectAll) {
            Text(if (count == total) "取消全选" else "全选", color = accent.accent)
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    allLiked: Boolean,
    onPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionAction(
            icon = Icons.Filled.PlaylistAdd,
            label = "加入歌单",
            tint = accent.accent,
            enabled = count > 0,
            onClick = onPlaylist,
        )
        SelectionAction(
            icon = if (allLiked) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
            label = if (allLiked) "取消喜欢" else "喜欢",
            tint = accent.accent,
            enabled = count > 0,
            onClick = onFavorite,
        )
        SelectionAction(
            icon = Icons.Filled.DeleteSweep,
            label = "删除",
            tint = Color(0xFFE57373),
            enabled = count > 0,
            onClick = onDelete,
        )
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else TextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            label,
            color = if (enabled) tint else TextSecondary.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Favourites tab: a "play all" header followed by the same rows (multi-select included). */
@Composable
private fun FavoriteTabContent(
    songs: List<Song>,
    onAdd: (Song) -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { PlayerController.playQueue(songs.map { it.toTrack() }, 0) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = accent.accent)
                Spacer(Modifier.size(6.dp))
                Text("播放全部", color = accent.accent, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            Text("${songs.size} 首", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        SongListContent(
            songs = songs,
            onPlay = { idx -> PlayerController.playQueue(songs.map { it.toTrack() }, idx) },
            onAdd = onAdd,
            onArtistClick = onArtistClick,
            selectionEnabled = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ArtistListContent(
    artists: List<String>,
    counts: Map<String, Int>,
    onArtist: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(artists) { artist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onArtist(artist) }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text(artist, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "${counts[artist] ?: 0} 首",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun PlaylistListContent(
    playlists: List<Playlist>,
    onPlaylist: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val accent = LocalAccent.current

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { showCreate = true }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = accent.accent)
            Spacer(Modifier.width(12.dp))
            Text("新建歌单", color = accent.accent, style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn {
            items(playlists) { pl ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlaylist(pl) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text(pl.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建歌单", color = TextPrimary) },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("歌单名称", color = TextSecondary) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onCreate(name.trim()); showCreate = false },
                ) { Text("创建", color = accent.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消", color = TextPrimary) }
            },
        )
    }
}
