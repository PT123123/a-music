package com.amusic.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.amusic.data.model.Song
import java.io.File

/**
 * Everything we can tell the user about a track. Populated on demand (when the detail
 * sheet opens) via [MediaMetadataRetriever], so the library scan stays fast and we get
 * real numbers for both MediaStore songs and QQ Music downloads.
 */
data class SongDetail(
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val sizeBytes: Long,
    val container: String,
    val durationMs: Long,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val mimeType: String?,
    val dateAddedSec: Long,
    val isFavorite: Boolean,
) {
    val sourceLabel: String
        get() = if (path.contains("qqmusic", ignoreCase = true)) "QQ 音乐下载" else "本机媒体库"
}

object SongDetails {

    private const val TAG = "SongDetails"

    fun probe(context: Context, song: Song, isFavorite: Boolean): SongDetail {
        var durationMs = song.durationMs
        var bitrate = 0
        var sampleRate = 0
        var bits = 0
        var mime: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(song.data)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { durationMs = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.let { bitrate = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                ?.toIntOrNull()?.let { sampleRate = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()?.let { bits = it }
            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed for ${song.data}: ${t.message}")
        } finally {
            runCatching { retriever.release() }
        }

        val size = if (song.size > 0) song.size else runCatching { File(song.data).length() }.getOrDefault(0L)

        // Derive a bitrate when the extractor could not report one.
        if (bitrate <= 0 && durationMs > 0 && size > 0) {
            bitrate = ((size * 8L) / durationMs).toInt()   // bits per second
        }

        val ext = song.data.substringAfterLast('.', "").lowercase()

        return SongDetail(
            title = song.title,
            artist = song.artist,
            album = song.album,
            path = song.data,
            sizeBytes = size,
            container = if (ext.isEmpty()) "未知" else ext.uppercase(),
            durationMs = durationMs,
            bitrateKbps = (bitrate / 1000.0).toInt(),
            sampleRateHz = sampleRate,
            bitsPerSample = bits,
            mimeType = mime,
            dateAddedSec = song.dateAdded,
            isFavorite = isFavorite,
        )
    }
}
