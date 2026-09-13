package com.amusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amusic.R
import com.amusic.data.model.Song
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.QQGreen
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

/**
 * A single library row: artwork (tap = details), title/artist, like-heart and
 * "add to playlist". Tapping the text area starts playback; tapping the artist
 * name (when [onArtistClick] is supplied) opens that artist's page instead.
 *
 * In [selectionMode] the row becomes a checkbox: tapping anywhere toggles [selected] and the
 * per-row actions are hidden. Long-pressing outside selection mode calls [onLongClick], which
 * is how the library enters that mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShowDetail: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    // `<unknown>` is what the MediaStore scanner writes when a file has no artist tag —
    // there is nothing to navigate to in that case.
    val linkArtist = onArtistClick?.takeIf {
        !selectionMode && song.artist.isNotBlank() && song.artist != UNKNOWN_ARTIST
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.accent.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect?.invoke() else onClick() },
                onLongClick = if (selectionMode) null else onLongClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent.accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = accent.onAccent,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }

        AsyncImage(
            model = artRequest(context, song.albumArtUri, ART_PX),
            contentDescription = if (selectionMode) null else "查看详情",
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (selectionMode) Modifier else Modifier.clickable(onClick = onShowDetail)),
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.ic_notification),
            placeholder = painterResource(R.drawable.ic_notification),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                song.title,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artist.ifBlank { "未知歌手" },
                    color = if (linkArtist != null) accent.accent else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = if (linkArtist != null) {
                        Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { linkArtist(song.artist) }
                    } else {
                        Modifier.weight(1f, fill = false)
                    },
                )
                if (song.album.isNotBlank()) {
                    Text(
                        " · ${song.album}",
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (!selectionMode) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) accent.accent else TextSecondary,
                )
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Filled.Add, contentDescription = "添加到歌单", tint = QQGreen)
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** Artist tag the scanners use for files without one — not navigable. */
internal const val UNKNOWN_ARTIST = "<unknown>"
