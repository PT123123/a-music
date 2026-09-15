package com.amusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amusic.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    // Every user-facing query filters on `trashedAt IS NULL`: a song in the recycle bin is
    // still in the table (so it can be restored) but must not show up anywhere.
    @Query("SELECT * FROM songs WHERE trashedAt IS NULL ORDER BY title COLLATE LOCALIZED")
    fun observeAll(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE artist = :artist AND trashedAt IS NULL ORDER BY title COLLATE LOCALIZED")
    fun observeByArtist(artist: String): Flow<List<Song>>

    @Query(
        """SELECT DISTINCT artist FROM songs
           WHERE trashedAt IS NULL AND artist IS NOT NULL AND artist != '' AND artist != '<unknown>'
           ORDER BY artist COLLATE LOCALIZED"""
    )
    fun observeArtists(): Flow<List<String>>

    /** Contents of the recycle bin, most recently deleted first. */
    @Query("SELECT * FROM songs WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE data = :path LIMIT 1")
    suspend fun findByPath(path: String): Song?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<Song>)

    @Query("SELECT COUNT(*) FROM songs WHERE trashedAt IS NULL")
    suspend fun count(): Int

    // ---- recycle bin ----

    @Query("UPDATE songs SET trashedAt = :at WHERE id IN (:ids)")
    suspend fun moveToTrash(ids: List<Long>, at: Long)

    @Query("UPDATE songs SET trashedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun purge(ids: List<Long>)

    @Query("DELETE FROM songs WHERE trashedAt IS NOT NULL")
    suspend fun purgeAll()

    /** Paths currently sitting in the bin — the scanner must not resurrect them. */
    @Query("SELECT data FROM songs WHERE trashedAt IS NOT NULL")
    suspend fun trashedPaths(): List<String>

    /** Full rows currently sitting in the bin. */
    @Query("SELECT * FROM songs WHERE trashedAt IS NOT NULL")
    suspend fun trashedSongs(): List<Song>
}
