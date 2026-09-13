package com.amusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amusic.data.model.Favorite
import com.amusic.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT path FROM favorites")
    fun observePaths(): Flow<List<String>>

    @Query(
        """SELECT s.* FROM songs s
           INNER JOIN favorites f ON f.path = s.data
           ORDER BY f.addedAt DESC"""
    )
    fun observeSongs(): Flow<List<Song>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    suspend fun isFavorite(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun remove(path: String)

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun count(): Int
}
