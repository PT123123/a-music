package com.amusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.amusic.data.media.SongDetail
import com.amusic.data.media.SongDetails
import com.amusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the async probing for [SongDetailSheet]: when a [song] is supplied the technical
 * parameters are read off the main thread, then handed to the sheet. Rendering nothing
 * when [song] is null keeps call sites to a single line.
 */
@Composable
fun SongDetailHost(
    song: Song?,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    if (song == null) return
    val context = LocalContext.current
    var detail by remember(song.id) { mutableStateOf<SongDetail?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }

    LaunchedEffect(song.id) {
        loading = true
        detail = withContext(Dispatchers.IO) {
            SongDetails.probe(context.applicationContext, song, isFavorite)
        }
        loading = false
    }

    SongDetailSheet(
        // Keep the heart in sync with the live favourite state.
        detail = detail?.copy(isFavorite = isFavorite),
        loading = loading,
        albumArtUri = song.albumArtUri,
        onDismiss = onDismiss,
        onToggleFavorite = onToggleFavorite,
    )
}
