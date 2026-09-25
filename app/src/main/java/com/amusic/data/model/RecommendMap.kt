package com.amusic.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Join row between the on-device library and the recommendation payload:
 * one song's content-hash track id, cached with the (size, dateAdded) it was
 * computed from so rescans only re-hash changed files.
 */
@Entity(tableName = "recommend_map", indices = [Index("trackId")])
data class RecommendMap(
    @PrimaryKey val songId: Long,
    val trackId: String,
    val size: Long,
    val dateAdded: Long,
)
