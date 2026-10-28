package com.amusic.ui.lan

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amusic.MainApplication
import com.amusic.data.lan.HttpDirectoryBrowser
import com.amusic.data.lan.LanCache
import com.amusic.data.lan.LanEntry
import com.amusic.data.lan.LanNode
import com.amusic.data.lan.LanProtocol
import com.amusic.data.lan.UpnpBrowser
import com.amusic.data.online.DlProgress
import com.amusic.player.PlayerController
import com.amusic.player.Track
import com.amusic.ui.components.TopBar
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.Divider
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

/**
 * Browses one saved LAN source and plays from it.
 *
 * A source is walked with a stack of containers ([LanNode]): an HTTP source pushes URLs, a DLNA
 * source pushes object ids. System back unwinds the stack before leaving the screen. Tapping a
 * track plays the whole folder as a queue when streaming, or transparently downloads it first
 * when 缓存播放 is on.
 */
@Composable
fun LanBrowseScreen(nav: NavHostController, sourceId: String) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val store = app.lanStore
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()

    val sources by store.sources.collectAsState()
    val cacheMode by store.cacheMode.collectAsState()
    val source = sources.firstOrNull { it.id == sourceId }

    val cache = remember { LanCache(app) }
    val stack = remember { mutableStateListOf<LanNode>() }
    var entries by remember { mutableStateOf<List<LanEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Map<String, DlProgress>>(emptyMap()) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refresh() {
        val node = stack.lastOrNull() ?: return
        val src = source ?: return
        scope.launch {
            loading = true
            error = null
            runCatching {
                when (src.protocol) {
                    LanProtocol.HTTP -> HttpDirectoryBrowser().list(src.baseUrl, node.id)
                    LanProtocol.DLNA -> UpnpBrowser(src.controlUrl).browse(node.id).entries
                }
            }.onSuccess {
                entries = it
                if (it.isEmpty()) error = "这里没有可播放的内容"
            }.onFailure {
                entries = emptyList()
                error = it.message ?: it::class.java.simpleName
            }
            loading = false
        }
    }

    fun goUp() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        refresh()
    }

    fun openContainer(entry: LanEntry) {
        stack.add(LanNode(entry.id, displayName(entry.name)))
        refresh()
    }

    /** Caches [entry] (if needed) and returns the local file, or null on failure. */
    suspend fun ensureCached(entry: LanEntry): File? {
        val url = entry.mediaUrl ?: return null
        cache.existing(url)?.let { return it }
        progress = progress + (entry.id to DlProgress(0, entry.sizeBytes))
        return try {
            cache.fetch(url) { p -> progress = progress + (entry.id to p) }
        } catch (t: Throwable) {
            null
        } finally {
            progress = progress - entry.id
        }
    }

    fun play(entry: LanEntry) {
        if (entry.mediaUrl == null) return
        if (!cacheMode) {
            // Stream: hand the whole folder to the player so 下一首 keeps walking the album.
            val items = entries.filter { !it.isContainer && it.mediaUrl != null }
            if (items.isEmpty()) return
            val start = items.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
            PlayerController.playQueue(items.map { it.toTrack() }, start)
            toast("正在播放：${displayName(entry.name)}")
        } else {
            scope.launch {
                val file = ensureCached(entry)
                if (file == null) {
                    toast("缓存失败：${displayName(entry.name)}")
                    return@launch
                }
                PlayerController.playTrack(entry.toTrack(file))
                toast("正在播放（已缓存）：${displayName(entry.name)}")
            }
        }
    }

    LaunchedEffect(sourceId, source?.id) {
        if (source != null && stack.isEmpty()) {
            stack.add(
                if (source.protocol == LanProtocol.HTTP) LanNode(source.baseUrl, source.name)
                else LanNode("0", source.name)
            )
            refresh()
        }
    }

    BackHandler(enabled = stack.size > 1) { goUp() }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = stack.lastOrNull()?.title ?: source?.name ?: "局域网",
                onBack = { if (stack.size > 1) goUp() else nav.popBackStack() },
                actionIcon = Icons.Filled.Refresh,
                onAction = { refresh() },
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stack.joinToString(" / ") { it.title },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (cacheMode) "缓存播放" else "流式播放",
                    color = accent.accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            when {
                source == null -> CenteredText("找不到这个音乐源")
                loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent.accent)
                }
                error != null && entries.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { refresh() }) { Text("重试", color = accent.accent) }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        LanRow(
                            entry = entry,
                            progress = progress[entry.id],
                            cacheMode = cacheMode,
                            onClick = {
                                when {
                                    !entry.isContainer -> play(entry)
                                    entry.name == ".." -> goUp()
                                    else -> openContainer(entry)
                                }
                            },
                            onCache = {
                                scope.launch {
                                    val file = ensureCached(entry)
                                    toast(
                                        if (file != null) "已缓存：${displayName(entry.name)}"
                                        else "缓存失败：${displayName(entry.name)}"
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanRow(
    entry: LanEntry,
    progress: DlProgress?,
    cacheMode: Boolean,
    onClick: () -> Unit,
    onCache: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isContainer) Icons.Filled.Folder else Icons.Filled.MusicNote,
            contentDescription = null,
            tint = accent.accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName(entry.name),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            val subtitle = listOf(
                entry.artist,
                entry.album,
                if (entry.durationMs > 0) clock(entry.durationMs) else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (entry.isContainer || subtitle.isNotBlank()) {
                Text(
                    if (entry.isContainer) "文件夹" else subtitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            entry.isContainer -> Text("›", color = Divider, style = MaterialTheme.typography.titleLarge)
            progress != null -> CircularProgressIndicator(
                progress = { progress.fraction },
                color = accent.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
            cacheMode -> IconButton(onClick = onCache) {
                Icon(Icons.Filled.Download, contentDescription = "缓存", tint = accent.accent)
            }
            else -> Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = accent.accent,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Strip the file extension for display; names in listings are otherwise unreadable at a glance. */
private fun displayName(raw: String): String {
    val dot = raw.lastIndexOf('.')
    return if (dot > 0 && raw.length - dot <= 5) raw.substring(0, dot) else raw
}

private fun clock(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun LanEntry.toTrack(local: File? = null): Track = Track(
    uri = when {
        local != null -> "file://${local.absolutePath}"
        mediaUrl != null -> mediaUrl
        else -> ""
    },
    title = displayName(name),
    artist = artist,
    album = album,
    albumArtUri = albumArtUrl,
    durationMs = durationMs,
)
