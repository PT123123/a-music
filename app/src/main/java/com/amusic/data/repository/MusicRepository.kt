package com.amusic.data.repository

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.amusic.data.db.MusicDatabase
import com.amusic.data.model.Favorite
import com.amusic.data.model.Playlist
import com.amusic.data.model.PlaylistSong
import com.amusic.data.model.Song
import com.amusic.data.scan.MediaStoreScanner
import com.amusic.data.scan.QqMusicScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for the music library. Wraps Room + the MediaStore / QQ Music scans.
 */
class MusicRepository(
    private val db: MusicDatabase,
    private val scanner: MediaStoreScanner,
    private val qqMusicScanner: QqMusicScanner,
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Tombstones for songs the user permanently deleted, so a rescan cannot resurrect them. */
    private val prefs = appContext.getSharedPreferences("amusic_library", Context.MODE_PRIVATE)
    private val blocked: MutableSet<String> = LinkedHashSet(
        prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty()
    )

    val songs: Flow<List<Song>> = db.songDao().observeAll()
    val artists: Flow<List<String>> = db.songDao().observeArtists()
    val playlists: Flow<List<Playlist>> = db.playlistDao().observeAll()

    /** Recycle bin contents, newest deletion first. */
    val trash: Flow<List<Song>> = db.songDao().observeTrash()

    // ---- favourites ----

    /** Paths of every liked song; a Set so list rows can test membership in O(1). */
    val favoritePaths: Flow<Set<String>> =
        db.favoriteDao().observePaths().map { it.toSet() }

    val favoriteSongs: Flow<List<Song>> = db.favoriteDao().observeSongs()

    suspend fun isFavorite(path: String): Boolean = db.favoriteDao().isFavorite(path)

    suspend fun toggleFavorite(songId: Long, path: String) {
        if (path.isBlank()) return
        val dao = db.favoriteDao()
        if (dao.isFavorite(path)) dao.remove(path) else dao.add(Favorite(path = path, songId = songId))
    }

    /** Bulk "like" for the multi-select bar — additive, so an already-liked song stays liked. */
    suspend fun like(songs: List<Song>) {
        val dao = db.favoriteDao()
        songs.forEach { song ->
            if (song.data.isNotBlank() && !dao.isFavorite(song.data)) {
                dao.add(Favorite(path = song.data, songId = song.id))
            }
        }
    }

    /** Bulk "unlike", used by the same bar when everything selected is already liked. */
    suspend fun unlike(songs: List<Song>) {
        val dao = db.favoriteDao()
        songs.forEach { song -> if (song.data.isNotBlank()) dao.remove(song.data) }
    }

    // ---- library ----

    fun songsByArtist(artist: String): Flow<List<Song>> = db.songDao().observeByArtist(artist)
    fun playlistSongs(playlistId: Long): Flow<List<Song>> = db.playlistDao().observeSongs(playlistId)

    /** Scan once if the library is empty; call on app start. */
    suspend fun scanIfNeeded() {
        if (db.songDao().count() == 0) scan()
    }

    suspend fun scan() {
        // MediaStore covers the general library; QqMusicScanner adds QQ Music's
        // downloaded songs (which may not be exposed cleanly via MediaStore).
        // Dedupe by file path so a song present in both sources wins only once.
        val scanned = scanner.scan() + qqMusicScanner.scan()
        val binned = db.songDao().trashedPaths().toHashSet()
        val keep = scanned
            .distinctBy { it.data }
            // Skip anything sitting in the recycle bin (it is still in `songs`, and an upsert
            // would clear its `trashedAt`) and anything permanently deleted.
            .filter { it.data !in binned && !isBlocked(it) }
        db.songDao().upsertAll(keep)
    }

    // ---- recycle bin ----

    /** Move songs out of the library and into the recycle bin. The files stay on disk. */
    suspend fun moveToTrash(songs: List<Song>) {
        if (songs.isEmpty()) return
        db.songDao().moveToTrash(songs.map { it.id }, System.currentTimeMillis())
    }

    suspend fun restore(songs: List<Song>) {
        if (songs.isEmpty()) return
        db.songDao().restore(songs.map { it.id })
    }

    /**
     * Permanently delete. Best-effort removes the audio file, then drops the row and leaves a
     * tombstone so the next scan cannot bring it back.
     *
     * Files we created ourselves (downloads under `Music/amusic/`) delete cleanly. Foreign
     * files — QQ Music's cache, anything the user copied in — are usually refused by scoped
     * storage on Android 11+, and for those the tombstone is the only thing standing between
     * "deleted" and "back after a rescan".
     */
    suspend fun purge(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        songs.forEach { song ->
            deleteFile(song)
            block(song)
        }
        db.songDao().purge(songs.map { it.id })
    }

    /** Empty the bin: every entry is purged (and tombstoned) in one go. */
    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val songs = db.songDao().trashedSongs()
        if (songs.isEmpty()) return@withContext
        songs.forEach { song ->
            deleteFile(song)
            block(song)
        }
        db.songDao().purgeAll()
    }

    private fun deleteFile(song: Song) {
        runCatching {
            val f = File(song.data)
            if (f.isFile && f.delete()) {
                Log.i(TAG, "removed file ${song.data}")
                return
            }
        }
        // Scoped storage refused the raw delete. Ask MediaStore instead: it can drop rows the
        // app owns, and quietly does nothing for files it doesn't.
        runCatching {
            appContext.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(song.id.toString()),
            )
        }.onFailure { Log.w(TAG, "could not delete ${song.data}: ${it.message}") }
    }

    // ---- tombstones ----

    /**
     * Key includes `dateAdded` on purpose: a file re-downloaded later gets a fresh MediaStore
     * timestamp, so it is a different key and the user gets their song back instead of a
     * puzzling invisible entry.
     */
    private fun key(song: Song): String = "${song.data}\u0000${song.dateAdded}"

    private fun isBlocked(song: Song): Boolean = blocked.contains(key(song))

    private fun block(song: Song) {
        if (blocked.add(key(song))) prefs.edit().putStringSet(KEY_BLOCKED, blocked.toSet()).apply()
    }

    // ---- song lookup ----

    /** Find a song by its file path. Returns null if not found. */
    suspend fun findSongByPath(path: String): Song? = db.songDao().findByPath(path)

    // ---- playlists ----

    suspend fun createPlaylist(name: String): Long =
        db.playlistDao().insert(Playlist(name = name))

    suspend fun addToPlaylist(playlistId: Long, song: Song) {
        val pos = db.playlistDao().nextPosition(playlistId)
        db.playlistDao().addSong(PlaylistSong(playlistId, song.id, pos))
    }

    /** Bulk variant used by the library's multi-select mode. */
    suspend fun addToPlaylist(playlistId: Long, songs: List<Song>) {
        songs.forEach { addToPlaylist(playlistId, it) }
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) =
        db.playlistDao().removeSong(playlistId, songId)

    suspend fun deletePlaylist(playlist: Playlist) =
        db.playlistDao().delete(playlist)

    private companion object {
        const val TAG = "MusicRepository"
        const val KEY_BLOCKED = "blocked_paths"
    }
}
