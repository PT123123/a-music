package com.amusic.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.data.recommend.SimilarResult
import com.amusic.data.recommend.SimilarSong
import com.amusic.player.PlayerController
import com.amusic.ui.components.ART_PX
import com.amusic.ui.components.artRequest
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

private val GROUP_LABELS = mapOf(
    "energy" to "能量",
    "timbre" to "音色",
    "rhythm" to "节奏",
    "harmony" to "和声",
    "melody" to "旋律",
    "vocal" to "人声",
    "instrumentation" to "配器",
    "structure" to "结构",
    "embedding" to "整体听感",
)

private fun groupLabel(key: String) = GROUP_LABELS[key] ?: key

/** song -> song similar, scored entirely on-device by libmusicspace. */
@Composable
fun SimilarSongsScreen(nav: NavHostController, songId: Long) {
    val app = LocalContext.current.applicationContext as MainApplication
    val accent = LocalAccent.current
    val songs by app.repository.songs.collectAsState(initial = emptyList())
    val seed = remember(songs, songId) { songs.firstOrNull { it.id == songId } }

    var result by remember { mutableStateOf<SimilarResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(songId) {
        loading = true
        result = app.recommendation.similar(songId, limit = 30)
        loading = false
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
                Column {
                    Text("相似歌曲", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    seed?.let {
                        Text(
                            "与「${it.title}」听感接近",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val r = result
            when {
                loading -> {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "正在算…",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                    Spacer(Modifier.weight(1f))
                }
                r is SimilarResult.Unavailable -> Message(
                    "推荐引擎不可用。\n\n需要在带 Rust 工具链的构建里编入 libmusicspace.so," +
                        "并在 设置 → 相似推荐 导入桌面端导出的特征数据。"
                )
                r is SimilarResult.NoPayloadForSong -> Message(
                    "导入的特征数据里还没有这首歌。\n\n" +
                        "特征由桌面端 music-recommend 抽取(手机不做分析),把这首歌加进桌面端曲库重新导出、再导入一次就有了。"
                )
                r is SimilarResult.Ok && r.items.isEmpty() -> Message("曲库里没有和它相似的其他歌曲。")
                r is SimilarResult.Ok -> {
                    val items = r.items
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(items, key = { _, s -> s.song.id }) { idx, hit ->
                            SimilarRow(
                                hit = hit,
                                onClick = {
                                    PlayerController.playQueue(items.map { it.song.toTrack() }, idx)
                                },
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ColumnScope.Message(text: String) {
    Spacer(Modifier.weight(1f))
    Text(
        text,
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun SimilarRow(hit: SimilarSong, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artRequest(context, hit.song.albumArtUri, ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.ic_notification),
            placeholder = painterResource(R.drawable.ic_notification),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hit.song.title,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                hit.song.artist.ifBlank { "未知歌手" },
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append("相似度 ")
                    append("%.2f".format(hit.score))
                    hit.groups.take(3).forEach { g ->
                        append(" · ")
                        append(groupLabel(g.key))
                        append(" ")
                        append("%.2f".format(g.score))
                    }
                },
                color = accent.accent,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
