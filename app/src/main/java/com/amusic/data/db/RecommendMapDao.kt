package com.amusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amusic.data.model.RecommendMap

@Dao
interface RecommendMapDao {
    @Query("SELECT * FROM recommend_map WHERE songId = :songId")
    suspend fun getBySongId(songId: Long): RecommendMap?

    @Query("SELECT * FROM recommend_map WHERE trackId IN (:trackIds)")
    suspend fun getByTrackIds(trackIds: List<String>): List<RecommendMap>

    @Query("SELECT * FROM recommend_map")
    suspend fun all(): List<RecommendMap>

    @Query("SELECT COUNT(*) FROM recommend_map")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RecommendMap>)

    /** Drop rows whose song vanished from the library (purged from the bin). */
    @Query("DELETE FROM recommend_map WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun prune()
}
