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
 * - Which of the files are *music* is decided by [ExclusionRules]; this scanner only
 *   reads the library and reports every file it saw, so the repository can also drop
 *   rows that were added before the rules existed.
 */
class MediaStoreScanner(private val context: Context) {

    fun scan(): ScanOutcome {
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
        val kept = mutableListOf<Song>()
        val rejected = mutableListOf<RejectedSong>()

        // No SQL-side filtering on purpose: the rules in ExclusionRules need the
        // duration, the IS_MUSIC flag and the path of every row to judge it, and the
        // rejected list is what lets the repository clean up old rows.
        context.contentResolver.query(uri, projection, null, null, "LOWER(${MediaStore.Audio.Media.TITLE})")
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
                val colIsMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)

                while (cursor.moveToNext()) {
                    val data = cursor.getString(colData) ?: continue
                    if (data.isBlank()) continue
                    val albumId = cursor.getLong(colAlbumId)
                    val albumArtUri = if (albumId > 0)
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId
                        ).toString()
                    else null

                    val song = Song(
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

                    // IS_MUSIC: the system's own verdict on "this is a song". Files it
                    // does not believe are music get the length rules applied.
                    val isMusic = cursor.getInt(colIsMusic) == 1
                    val reason = ExclusionRules.reject(song, isMusic)
                    if (reason == null) {
                        kept.add(song)
                    } else {
                        rejected.add(RejectedSong(song.data, song.title, reason))
                    }
                }
            }
        return ScanOutcome(kept, rejected)
    }
}
