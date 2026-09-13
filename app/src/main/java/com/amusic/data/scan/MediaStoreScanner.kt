package com.amusic.data.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.amusic.data.model.Song

/**
 * Reads the on-device audio library from MediaStore.Audio.Media.
 *
 * Notes:
 * - We prefer the file path (`DATA`) for [Song.data] so libmpv can open it directly.
 * - Album art is exposed as a content URI built from the album id.
 */
class MediaStoreScanner(private val context: Context) {

    fun scan(): List<Song> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.IS_MUSIC,
        )

        val unknown = "<unknown>"
        val list = mutableListOf<Song>()

        // Keep real audio only, and drop DRM/encrypted downloads (QQ Music `.mflac`,
        // NetEase `.qmc`, Kugou `.kgm`, ...) — ffmpeg/libmpv cannot decode those.
        val selection = buildString {
            append("(${MediaStore.Audio.Media.IS_MUSIC} = 1 OR ${MediaStore.Audio.Media.DURATION} >= 30000)")
            append(" AND LOWER(${MediaStore.Audio.Media.DATA}) NOT LIKE '%.mflac%'")
            append(" AND LOWER(${MediaStore.Audio.Media.DATA}) NOT LIKE '%.qmc%'")
            append(" AND LOWER(${MediaStore.Audio.Media.DATA}) NOT LIKE '%.mgg%'")
            append(" AND LOWER(${MediaStore.Audio.Media.DATA}) NOT LIKE '%.tm0%'")
            append(" AND LOWER(${MediaStore.Audio.Media.DATA}) NOT LIKE '%.kgm%'")
        }

        context.contentResolver.query(uri, projection, selection, null, "LOWER(${MediaStore.Audio.Media.TITLE})")
            ?.use { cursor ->
                val colId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val colTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val colArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val colAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val colAlbumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val colDur = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val colData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val colSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val colDate = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val data = cursor.getString(colData) ?: continue
                    if (data.isBlank()) continue
                    val albumId = cursor.getLong(colAlbumId)
                    val albumArtUri = if (albumId > 0)
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId
                        ).toString()
                    else null

                    list.add(
                        Song(
                            id = cursor.getLong(colId),
                            title = cursor.getString(colTitle) ?: unknown,
                            artist = (cursor.getString(colArtist) ?: unknown).ifBlank { unknown },
                            album = (cursor.getString(colAlbum) ?: unknown).ifBlank { unknown },
                            albumId = albumId,
                            albumArtUri = albumArtUri,
                            durationMs = cursor.getLong(colDur),
                            data = data,
                            size = cursor.getLong(colSize),
                            dateAdded = cursor.getLong(colDate),
                        )
                    )
                }
            }
        return list
    }
}
