package com.amusic.ui.components

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amusic.R
import com.amusic.data.media.SongDetail
import com.amusic.ui.theme.Divider
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

/**
 * Detailed technical sheet for a track: format, bitrate, sample rate, size, path, ...
 * Numbers come from [SongDetail] (probed with MediaMetadataRetriever on demand).
 */
@Composable
fun SongDetailSheet(
    detail: SongDetail?,
    loading: Boolean,
    albumArtUri: String?,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    AppDialog(title = "歌曲详情", onDismiss = onDismiss) {
        if (loading || detail == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text("读取参数中…", color = TextSecondary)
            }
            return@AppDialog
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                    error = painterResource(R.drawable.ic_notification),
                    placeholder = painterResource(R.drawable.ic_notification),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.title,
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                    Text(detail.artist, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(detail.album, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (detail.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (detail.isFavorite) "取消收藏" else "收藏",
                        tint = if (detail.isFavorite) LocalAccent.current.accent else TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HairLine()

            DialogScrollBody {
                DetailRow("歌曲名", detail.title)
                DetailRow("歌手", detail.artist)
                DetailRow("专辑", detail.album.ifBlank { "未知" })
                HairLine()
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { DetailRow("格式", detail.container) }
                    Column(Modifier.weight(1f)) { DetailRow("时长", fmtDuration(detail.durationMs)) }
                }
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        DetailRow("码率", if (detail.bitrateKbps > 0) "${detail.bitrateKbps} kbps" else "未知")
                    }
                    Column(Modifier.weight(1f)) {
                        DetailRow("采样率", if (detail.sampleRateHz > 0) "${detail.sampleRateHz} Hz" else "未知")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        DetailRow("位深", if (detail.bitsPerSample > 0) "${detail.bitsPerSample} bit" else "未知")
                    }
                    Column(Modifier.weight(1f)) {
                        DetailRow("文件大小", fmtSize(detail.sizeBytes))
                    }
                }
                DetailRow("音频编码", detail.mimeType ?: "未知")
                DetailRow("来源", detail.sourceLabel, highlight = true)
                DetailRow("收藏状态", if (detail.isFavorite) "已收藏 ♥" else "未收藏", highlight = detail.isFavorite)
                HairLine()
                DetailRow("文件路径", detail.path)
                DetailRow(
                    "加入媒体库",
                    if (detail.dateAddedSec > 0) fmtDate(detail.dateAddedSec) else "未知",
                )
            }
        }
    }
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "未知"
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> "未知"
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.0f KB".format(bytes / 1024.0)
}

private fun fmtDate(sec: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(sec * 1000))
