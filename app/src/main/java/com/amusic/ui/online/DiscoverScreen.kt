package com.amusic.ui.online

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.data.online.DlProgress
import com.amusic.data.online.Net24Api
import com.amusic.data.online.Net24Download
import com.amusic.data.online.Net24Quality
import com.amusic.data.online.Net24Resolve
import com.amusic.data.online.Net24Song
import com.amusic.data.online.OnlineDownloader
import com.amusic.data.online.OnlineSong
import com.amusic.data.online.QqOnlineApi
import com.amusic.data.online.fmtBytes
import com.amusic.data.prefs.SettingsRepository
import com.amusic.player.PlayerController
import com.amusic.player.Track
import com.amusic.ui.components.artRequest
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a row's download button currently stands. */
private enum class DlState { IDLE, RUNNING, DONE }

private const val TAG = "Discover"

/** Cover art is never drawn larger than ~300dp, so this decode cap is plenty. */
private const val COVER_PX = 512

/** The two online catalogues wired into this tab. */
private enum class Source(val id: String, val label: String, val tagline: String) {
    QQ(SettingsRepository.SOURCE_QQ, "QQ音乐", "在线试听 · 下载 m4a"),
    NET24(SettingsRepository.SOURCE_NET24, "无损音乐", "母带 / 环绕 / 无损 · 直链 flac"),
}

/**
 * "发现" tab — the online half of the app.
 *
 * Hosts two interchangeable sources behind one switcher:
 *  - **QQ音乐** streams QQ's public search API for instant preview / m4a download.
 *  - **无损音乐** adapts an online lossless download site (base URL injected from the
 *    gitignored local.properties). Its search gives song ids, and its
 *    server-rendered detail page leaks a direct CDN link plus the file size for each tier —
 *    so each row exposes the tiers that song actually has, and tapping one resolves the
 *    real size *before* anything is downloaded.
 */
@Composable
fun DiscoverScreen(@Suppress("UNUSED_PARAMETER") nav: NavHostController) {
    val app = LocalContext.current.applicationContext as MainApplication
    val repo = app.repository
    val settings = app.settings
    val accent = LocalAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val songs by repo.songs.collectAsState(initial = emptyList())
    val favorites by repo.favoriteSongs.collectAsState(initial = emptyList())
    val sourceId by settings.discoverSource.collectAsState()
    val source = if (sourceId == SettingsRepository.SOURCE_NET24) Source.NET24 else Source.QQ

    var scanning by remember { mutableStateOf(false) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun rescan() {
        if (scanning) return
        scanning = true
        scope.launch {
            runCatching { repo.scan() }
            scanning = false
            toast("本地曲库已更新")
        }
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            // ---------- header: title + the library tools moved out of 音乐馆 ----------
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("发现", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text(source.tagline, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !scanning, onClick = { rescan() }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = accent.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (scanning) "扫描中…" else "重新扫描", color = accent.accent)
                }
            }

            Text(
                "本地 ${songs.size} 首 · 收藏 ${favorites.size} 首 · 下载到 ${SettingsRepository.DEFAULT_DL_DIR}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )

            // ---------- source switcher ----------
            SourceTabs(
                selected = source,
                onSelect = { settings.setDiscoverSource(it.id) },
            )

            Spacer(Modifier.height(8.dp))

            when (source) {
                Source.QQ -> QqSection(
                    settings = settings,
                    downloader = remember { OnlineDownloader(app) },
                    toast = ::toast,
                    rescan = ::rescan,
                )

                Source.NET24 -> Net24Section(
                    settings = settings,
                    downloader = remember { OnlineDownloader(app) },
                    toast = ::toast,
                    rescan = ::rescan,
                )
            }
        }
    }
}

// -------------------------------------------------------------- source tabs

@Composable
private fun SourceTabs(selected: Source, onSelect: (Source) -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Source.entries.forEach { s ->
            val on = s == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) accent.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                    .border(
                        width = 1.dp,
                        color = if (on) accent.accent else Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(s) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.label,
                    color = if (on) accent.accent else TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ------------------------------------------------------------------- search box

@Composable
private fun SearchBox(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
) {
    val accent = LocalAccent.current
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(placeholder, color = TextSecondary) },
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
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = accent.accent.copy(alpha = 0.12f),
            unfocusedContainerColor = accent.accent.copy(alpha = 0.07f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = accent.accent,
        ),
    )
}

// ---------------------------------------------------------------- QQ section

@Composable
private fun QqSection(
    settings: SettingsRepository,
    downloader: OnlineDownloader,
    toast: (String) -> Unit,
    rescan: () -> Unit,
) {
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()

    val history by settings.searchHistory.collectAsState()

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, DlProgress>>(emptyMap()) }
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var resolving by remember { mutableStateOf<String?>(null) }

    fun submit(word: String) {
        val w = word.trim()
        if (w.isEmpty() || loading) return
        query = w
        settings.addSearchHistory(w)
        scope.launch {
            loading = true
            val r = runCatching { QqOnlineApi.search(w) }.getOrDefault(emptyList())
            results = r
            searched = true
            loading = false
            if (r.isEmpty()) toast("没有搜到结果，换个关键词试试")
        }
    }

    fun preview(song: OnlineSong) {
        if (resolving != null) return
        resolving = song.mid
        scope.launch {
            try {
                val url = QqOnlineApi.directUrl(song.mid)
                if (url == null) {
                    toast("《${song.title}》需要 VIP，无法试听")
                    return@launch
                }
                PlayerController.playTrack(
                    Track(
                        uri = url,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        albumArtUri = song.coverUrl.takeIf { it.isNotBlank() },
                        durationMs = song.durationSec * 1000L,
                    )
                )
                toast("正在播放：${song.title}")
            } catch (t: Throwable) {
                // An uncaught exception inside this coroutine would take the whole process
                // down; a flaky CDN or a malformed response must never do that.
                Log.w(TAG, "QQ preview failed: ${song.title}", t)
                toast("试听失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                resolving = null
            }
        }
    }

    fun download(song: OnlineSong) {
        val mid = song.mid
        if (progress.containsKey(mid) || downloaded.contains(mid)) return
        progress = progress + (mid to DlProgress(0, 0))
        scope.launch {
            try {
                val url = QqOnlineApi.directUrl(mid)
                if (url == null) {
                    toast("《${song.title}》需要 VIP 或已下架，无法下载")
                    return@launch
                }
                toast("开始下载：${song.title}")
                val uri = downloader.download(song, url) { p -> progress = progress + (mid to p) }
                if (uri == null) {
                    toast("下载失败：${song.title}")
                    return@launch
                }
                downloaded = downloaded + mid
                toast("已下载：${song.title}")
                delay(700)
                rescan()
            } catch (t: Throwable) {
                Log.w(TAG, "QQ download failed: ${song.title}", t)
                toast("下载失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                progress = progress - mid
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchBox(query = query, onQuery = { query = it }, placeholder = "搜索歌曲 / 歌手 / 专辑", onSubmit = { submit(query) })
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent.accent)
            }

            results.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.mid }) { song ->
                    QqSongRow(
                        song = song,
                        dlState = when {
                            downloaded.contains(song.mid) -> DlState.DONE
                            progress.containsKey(song.mid) -> DlState.RUNNING
                            else -> DlState.IDLE
                        },
                        progress = progress[song.mid] ?: DlProgress(0, 0),
                        resolving = resolving == song.mid,
                        onClick = { preview(song) },
                        onDownload = { download(song) },
                    )
                }
                item { FooterHint("最多显示 20 条 · 点行试听，点 ⤓ 下载") }
            }

            else -> Suggestions(
                history = history,
                words = QqOnlineApi.hotWords,
                onWord = { submit(it) },
                onRemoveHistory = { settings.removeSearchHistory(it) },
                onClearHistory = { settings.clearSearchHistory() },
                emptyHint = if (searched) "没有找到结果，换个关键词试试" else null,
                tip = "点任意一行可直接在线试听；点 ⤓ 下载到本地，下载完自动加入音乐馆。长按历史词可删除。",
            )
        }
    }
}

// ------------------------------------------------------------- Net24 section

/** A tier the user asked about and we resolved successfully, waiting for a yes/no. */
private data class PendingDownload(
    val song: Net24Song,
    val quality: Net24Quality,
    val download: Net24Download,
)

@Composable
private fun Net24Section(
    settings: SettingsRepository,
    downloader: OnlineDownloader,
    toast: (String) -> Unit,
    rescan: () -> Unit,
) {
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val history by settings.searchHistory.collectAsState()

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Net24Song>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, DlProgress>>(emptyMap()) }
    var done by remember { mutableStateOf<Set<String>>(emptySet()) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }

    /** Tiers we already looked up, so the button can show its size from then on. */
    var sizes by remember { mutableStateOf<Map<String, Net24Download>>(emptyMap()) }
    var pending by remember { mutableStateOf<PendingDownload?>(null) }

    fun submit(word: String) {
        val w = word.trim()
        if (w.isEmpty() || loading) return
        query = w
        settings.addSearchHistory(w)
        scope.launch {
            loading = true
            val r = runCatching { Net24Api.search(w) }.getOrDefault(emptyList())
            results = r
            searched = true
            loading = false
            if (r.isEmpty()) toast("没有搜到结果，换个关键词试试")
        }
    }

    fun preview(song: Net24Song) {
        if (previewing != null) return
        previewing = song.id
        scope.launch {
            try {
                when (val r = Net24Api.preview(song)) {
                    is Net24Resolve.Fail -> toast("《${song.title}》：${r.reason}")
                    is Net24Resolve.Ok -> {
                        val d = r.download
                        // Remember the size for the tier we streamed, so its button can show it.
                        sizes = sizes + (song.key(r.tier) to d)
                        PlayerController.playTrack(
                            Track(
                                uri = d.url,
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                albumArtUri = song.coverUrl.takeIf { it.isNotBlank() },
                                durationMs = 0L,
                            )
                        )
                        toast("正在试听：${song.title}（${d.quality}${if (d.sizeText.isNotBlank()) " · ${d.sizeText}" else ""}）")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "lossless preview failed: ${song.title}", t)
                toast("试听失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                previewing = null
            }
        }
    }

    /**
     * Step one of a download: ask the site for the real size. Nothing is transferred yet —
     * this is exactly the "下载前先看多大" step, and it is also where a mis-paired id gets
     * caught (the site answers with a different song, which we refuse).
     */
    fun resolveFor(song: Net24Song, quality: Net24Quality) {
        val key = song.key(quality)
        if (resolving != null || progress.containsKey(key) || done.contains(key)) return
        resolving = key
        scope.launch {
            try {
                when (val r = Net24Api.resolve(song, quality)) {
                    is Net24Resolve.Fail -> toast("《${song.title}》${quality.short}：${r.reason}")
                    is Net24Resolve.Ok -> {
                        sizes = sizes + (key to r.download)
                        pending = PendingDownload(song, quality, r.download)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "lossless resolve failed: ${song.title} ${quality.type}", t)
                toast("查询失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                resolving = null
            }
        }
    }

    fun startDownload(p: PendingDownload) {
        val key = p.song.key(p.quality)
        pending = null
        if (progress.containsKey(key) || done.contains(key)) return
        progress = progress + (key to DlProgress(0, p.download.sizeBytes))
        scope.launch {
            try {
                toast("开始下载：${p.song.title} · ${p.download.quality} ${p.download.sizeText}")
                val uri = downloader.downloadNet24(p.song, p.download) { pr ->
                    progress = progress + (key to pr)
                }
                if (uri == null) {
                    toast("下载失败：${p.song.title}")
                    return@launch
                }
                done = done + key
                toast("已下载：${p.song.title}（${p.download.quality}）")
                delay(700)
                rescan()
            } catch (t: Throwable) {
                Log.w(TAG, "lossless download failed: ${p.song.title} ${p.quality.type}", t)
                toast("下载失败：${t.message ?: t::class.java.simpleName}")
            } finally {
                progress = progress - key
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchBox(query = query, onQuery = { query = it }, placeholder = "搜索歌曲 / 歌手（如：晴天 周杰伦）", onSubmit = { submit(query) })
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent.accent)
            }

            results.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.id }) { song ->
                    Net24SongRow(
                        song = song,
                        progress = progress,
                        done = done,
                        sizes = sizes,
                        resolvingKey = resolving,
                        previewing = previewing == song.id,
                        onPreview = { preview(song) },
                        onPick = { q -> resolveFor(song, q) },
                    )
                }
                item {
                    FooterHint(
                        "点行试听（自动选最小的一档）· 点音质按钮会先查一次大小，确认后再下载。\n" +
                            "站点按天限制访问次数，所以大小是点的时候才去查的，查过一次就会记住。"
                    )
                }
            }

            else -> Suggestions(
                history = history,
                words = Net24Api.hotWords,
                onWord = { submit(it) },
                onRemoveHistory = { settings.removeSearchHistory(it) },
                onClearHistory = { settings.clearSearchHistory() },
                emptyHint = if (searched) "没有找到结果，换个关键词试试" else null,
                tip = "无损站：母带 / 环绕 / 无损三档。它家的「母带源」和「无损源」是两套不同的 id，" +
                    "这里已经按来源分别取档，并对不上号的会被挡掉，不会下错歌。点行可先用无损试听。长按历史词可删除。",
            )
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("下载 ${p.quality.label}", color = TextPrimary) },
            text = {
                Column {
                    Text(p.song.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (p.song.album.isBlank()) p.song.artist else "${p.song.artist} · ${p.song.album}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "格式  ${p.download.ext.uppercase()}",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "大小  ${p.download.sizeText.ifBlank { "未知" }}" +
                            p.download.sizeBytes.let { if (it > 0) "（${fmtBytes(it)}）" else "" },
                        color = accent.accent,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "来源  ${p.quality.origin.listLabel}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "保存到 ${SettingsRepository.DEFAULT_DL_DIR}，下完自动加入音乐馆。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { startDownload(p) }) {
                    Text("下载", color = accent.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消", color = TextPrimary) }
            },
        )
    }
}

// ------------------------------------------------------------------ rows

@Composable
private fun QqSongRow(
    song: OnlineSong,
    dlState: DlState,
    progress: DlProgress,
    resolving: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            AsyncImage(
                model = artRequest(context, song.coverUrl, COVER_PX),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.ic_notification),
                placeholder = painterResource(R.drawable.ic_notification),
            )
            if (song.vipOnly) {
                Text(
                    "VIP",
                    color = accent.onAccent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.accent)
                        .padding(horizontal = 3.dp),
                )
            }
            if (resolving) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = accent.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

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
                if (song.album.isBlank()) song.artist else "${song.artist} · ${song.album}",
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOf(song.durationText, song.sizeText).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when (dlState) {
            DlState.DONE -> Icon(
                Icons.Filled.Check,
                contentDescription = "已下载",
                tint = accent.accent,
                modifier = Modifier.padding(horizontal = 12.dp).size(22.dp),
            )

            DlState.RUNNING -> ProgressDial(progress = progress, accent = accent)

            DlState.IDLE -> IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "下载", tint = accent.accent)
            }
        }
    }
}

@Composable
private fun Net24SongRow(
    song: Net24Song,
    progress: Map<String, DlProgress>,
    done: Set<String>,
    sizes: Map<String, Net24Download>,
    resolvingKey: String?,
    previewing: Boolean,
    onPreview: () -> Unit,
    onPick: (Net24Quality) -> Unit,
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    // A song only appears in one of the site's two catalogues; show where it came from so the
    // button set (and its absence) makes sense.
    val sourceLabel = when {
        song.masterId != null && song.losslessId != null -> "母带源 + 无损源"
        song.masterId != null -> "母带源"
        else -> "无损源"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPreview)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                AsyncImage(
                    model = artRequest(context, song.coverUrl, COVER_PX),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    error = painterResource(R.drawable.ic_notification),
                    placeholder = painterResource(R.drawable.ic_notification),
                )
                if (previewing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = accent.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }

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
                    if (song.album.isBlank()) song.artist else "${song.artist} · ${song.album}",
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "无损站 · 来自$sourceLabel",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- the quality buttons: this is the bit "adapted to" the site's own endpoints ----
        Row(
            Modifier.padding(start = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            song.qualities.forEach { q ->
                val key = song.key(q)
                QualityChip(
                    label = q.short,
                    sizeText = sizes[key]?.sizeText,
                    state = when {
                        done.contains(key) -> DlState.DONE
                        progress.containsKey(key) -> DlState.RUNNING
                        else -> DlState.IDLE
                    },
                    progress = progress[key] ?: DlProgress(0, 0),
                    busy = resolvingKey == key,
                    onClick = { onPick(q) },
                )
            }
        }

        // Live byte counter for whichever tier of this row is downloading.
        val running = song.qualities.firstOrNull { progress.containsKey(song.key(it)) }
        if (running != null) {
            val p = progress.getValue(song.key(running))
            Text(
                "${running.short}  ${(p.fraction * 100).toInt()}%  ·  ${p.sizeText}",
                color = accent.accent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 60.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun QualityChip(
    label: String,
    sizeText: String?,
    state: DlState,
    progress: DlProgress,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val active = state != DlState.IDLE
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) accent.accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f))
            .border(
                width = 1.dp,
                color = if (active) accent.accent else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(enabled = state == DlState.IDLE && !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            busy -> CircularProgressIndicator(
                color = accent.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(15.dp),
            )

            state == DlState.RUNNING -> CircularProgressIndicator(
                progress = { progress.fraction },
                color = accent.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(15.dp),
            )

            state == DlState.DONE -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = accent.accent,
                modifier = Modifier.size(15.dp),
            )

            else -> Icon(
                Icons.Filled.Download,
                contentDescription = null,
                tint = accent.accent,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            buildString {
                append(label)
                when {
                    busy -> append(" 查询中")
                    state == DlState.RUNNING -> append(" ${(progress.fraction * 100).toInt()}%")
                    state == DlState.DONE -> append(" 已存")
                    !sizeText.isNullOrBlank() -> append(" $sizeText")
                }
            },
            color = if (active) accent.accent else TextPrimary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ProgressDial(progress: DlProgress, accent: com.amusic.ui.theme.AccentPalette) {
    Box(
        Modifier.padding(horizontal = 6.dp).size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.fraction },
            color = accent.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(30.dp),
        )
        Text(
            "${(progress.fraction * 100).toInt()}",
            color = TextPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun FooterHint(text: String) {
    Text(
        text,
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

// ----------------------------------------------------------- suggestions

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(
    history: List<String>,
    words: List<String>,
    onWord: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    emptyHint: String?,
    tip: String,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (emptyHint != null) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = accent.accent)
                Spacer(Modifier.width(8.dp))
                Text(emptyHint, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "搜索历史",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearHistory) { Text("清空", color = TextSecondary) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                history.forEach { w ->
                    WordChip(
                        text = w,
                        highlight = true,
                        onClick = { onWord(w) },
                        onLongClick = { onRemoveHistory(w) },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "热门搜索",
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words.forEach { w ->
                WordChip(text = w, highlight = false, onClick = { onWord(w) })
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(tip, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(
    text: String,
    highlight: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = LocalAccent.current
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (highlight) palette.accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = if (highlight) palette.accent else TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
