package com.amusic.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.amusic.player.Track

/**
 * A song as stored in the on-device library (mirrors MediaStore.Audio.Media).
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val id: Long,                 // MediaStore audio id
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtUri: String?,                 // content://.../albumart/<albumId>
    val durationMs: Long,
    val data: String,                         // filesystem path (preferred for mpv loadfile)
    val size: Long,
    val dateAdded: Long,
    /**
     * Epoch millis when the user moved this song to the recycle bin, or null while it is a
     * normal library member. Trashed rows are hidden from every list but stay in the table
     * so 恢复 is a single UPDATE — and so the file on disk is never touched by a delete.
     */
    val trashedAt: Long? = null,
) {
    /** Map to the player core's [Track]. Uses a file:// URI so libmpv can open it directly. */
    fun toTrack(): Track = Track(
        uri = if (data.startsWith("file://") || data.startsWith("content://")) data else "file://$data",
        title = title,
        artist = artist,
        album = album,
        albumArtUri = albumArtUri,
        durationMs = durationMs,
        songId = id,
    )
}
