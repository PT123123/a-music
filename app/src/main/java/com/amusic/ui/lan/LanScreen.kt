package com.amusic.ui.lan

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amusic.MainApplication
import com.amusic.data.lan.LanCache
import com.amusic.data.lan.LanProtocol
import com.amusic.data.lan.LanSource
import com.amusic.data.lan.LanStore
import com.amusic.data.lan.SsdpDiscovery
import com.amusic.data.lan.UpnpDevice
import com.amusic.data.online.fmtBytes
import com.amusic.ui.components.ChoicePill
import com.amusic.ui.components.HairLine
import com.amusic.ui.components.TopBar
import com.amusic.ui.nav.Routes
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.Divider
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "局域网播放" hub — the LAN half of the app, mirroring how 发现 hosts the online sources.
 *
 * Manages the three things a LAN client needs: the playback mode (stream vs. download-then-play),
 * DLNA/UPnP device discovery, and hand-added HTTP directory sources. Browsing happens on
 * [LanBrowseScreen], reached by tapping any source row.
 */
@Composable
fun LanScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val store = app.lanStore
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()

    val sources by store.sources.collectAsState()
    val cacheMode by store.cacheMode.collectAsState()

    val cache = remember { LanCache(app) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<UpnpDevice>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Runs on every entry into the screen, so returning from a browse session refreshes the size.
    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "局域网播放", onBack = { nav.popBackStack() })

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SectionTitle("播放方式")
                ChoicePill(
                    text = "流式播放（不缓存）",
                    selected = !cacheMode,
                    subtitle = "mpv 直接读取电脑上的 URL，边下边播，不占手机存储",
                    onClick = { store.setCacheMode(false) },
                )
                ChoicePill(
                    text = "缓存播放（先下载）",
                    selected = cacheMode,
                    subtitle = "先下载到手机再播放，重复播放秒开、断网也能听",
                    onClick = { store.setCacheMode(true) },
                )
                if (cacheBytes > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "缓存占用 ${fmtBytes(cacheBytes)}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { cache.clear() }
                                cacheBytes = 0
                                toast("已清空局域网缓存")
                            }
                        }) { Text("清空缓存", color = accent.accent) }
                    }
                }

                Spacer(Modifier.height(4.dp))
                HairLine()

                SectionTitle("DLNA / UPnP 设备")
                TextButton(
                    enabled = !scanning,
                    onClick = {
                        scanning = true
                        scope.launch {
                            val found = runCatching { SsdpDiscovery(app).discover() }
                                .getOrDefault(emptyList())
                            devices = found
                            scanning = false
                            toast(
                                if (found.isEmpty()) "没找到 DLNA 设备，确认和电脑在同一个 Wi-Fi"
                                else "发现 ${found.size} 台设备"
                            )
                        }
                    },
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            color = accent.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = accent.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "搜索中…" else "搜索局域网设备", color = accent.accent)
                }

                // Saved DLNA devices stay listed so they remain browsable after a restart,
                // when SSDP discovery may well come up empty (AP isolation, device asleep…).
                val savedDlna = sources.filter { it.protocol == LanProtocol.DLNA }
                savedDlna.forEach { source ->
                    SourceRow(
                        icon = Icons.Filled.Wifi,
                        title = source.name,
                        subtitle = "DLNA · ${source.baseUrl}",
                        onClick = { nav.navigate(Routes.lanBrowse(source.id)) },
                        onDelete = {
                            store.removeSource(source.id)
                            toast("已删除 ${source.name}")
                        },
                    )
                }
                devices.filter { d -> savedDlna.none { it.controlUrl == d.controlUrl } }.forEach { device ->
                    SourceRow(
                        icon = Icons.Filled.Wifi,
                        title = device.friendlyName,
                        subtitle = listOf(device.modelName, "新发现").filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = {
                            val source = store.upsert(
                                LanSource(
                                    id = LanStore.newId(),
                                    name = device.friendlyName,
                                    protocol = LanProtocol.DLNA,
                                    baseUrl = device.location,
                                    controlUrl = device.controlUrl,
                                )
                            )
                            nav.navigate(Routes.lanBrowse(source.id))
                        },
                    )
                }
                if (savedDlna.isEmpty() && devices.isEmpty() && !scanning) {
                    Hint("点「搜索局域网设备」扫描同网段的 DLNA/UPnP 媒体服务器。电脑上装 MiniDLNA、开 Windows「媒体流」，或直接用群晖 / Plex 的 DLNA 服务都可以。")
                }

                Spacer(Modifier.height(4.dp))
                HairLine()

                SectionTitle("HTTP 音乐源")
                TextButton(onClick = { showAdd = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = accent.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("添加 HTTP 地址", color = accent.accent)
                }

                sources.filter { it.protocol == LanProtocol.HTTP }.forEach { source ->
                    SourceRow(
                        icon = Icons.Filled.Wifi,
                        title = source.name,
                        subtitle = source.baseUrl,
                        onClick = { nav.navigate(Routes.lanBrowse(source.id)) },
                        onDelete = {
                            store.removeSource(source.id)
                            toast("已删除 ${source.name}")
                        },
                    )
                }
                if (sources.none { it.protocol == LanProtocol.HTTP }) {
                    Hint("电脑上开一个静态文件服务（例如在音乐目录跑 `python -m http.server 8000`，或 nginx / Apache 的 autoindex），把地址填进来，就能直接浏览并播放那台电脑上的音乐。")
                }

                Spacer(Modifier.height(4.dp))
                HairLine()
                SectionTitle("说明")
                Text(
                    "· 流式播放：mpv 直接打开 http:// 地址，走 demuxer 缓存，不落盘。\n" +
                        "· 缓存播放：文件下载到应用私有目录 Android/data/com.amusic/files/lan_cache，" +
                        "不会混进音乐馆，也不会被别的 App 看到。\n" +
                        "· 手机与电脑要在同一局域网；SSDP 依赖 Wi-Fi 组播，个别路由器开了「AP 隔离」时会搜不到，" +
                        "此时可改用 HTTP 源，或直接填 DLNA 设备的地址。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAdd) {
        AddHttpDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url ->
                val source = store.upsert(
                    LanSource(
                        id = LanStore.newId(),
                        name = name.ifBlank { url },
                        protocol = LanProtocol.HTTP,
                        baseUrl = url,
                    )
                )
                showAdd = false
                toast("已添加 ${source.name}")
            },
        )
    }
}

@Composable
private fun AddHttpDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val accent = LocalAccent.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://") }
    val valid = url.trim().startsWith("http://") || url.trim().startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 HTTP 音乐源", color = TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("目录地址") },
                    placeholder = { Text("http://192.168.1.10:8000/") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    // A trailing slash matters: relative hrefs in the listing resolve against it.
                    val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
                    onAdd(name.trim(), normalized)
                },
            ) { Text("添加", color = accent.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextPrimary) }
        },
    )
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = TextSecondary)
            }
        } else {
            Text("›", color = Divider, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        color = LocalAccent.current.accent,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}
