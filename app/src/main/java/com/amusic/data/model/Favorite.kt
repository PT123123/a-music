package com.amusic.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A "liked" song. Keyed by the file path rather than the song id, because ids from
 * MediaStore / the QQ scanner can change between scans while the path stays put —
 * so favourites survive a library rescan.
 */
@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val path: String,
    val songId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)
