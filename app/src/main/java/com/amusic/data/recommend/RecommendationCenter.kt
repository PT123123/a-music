package com.amusic.data.recommend

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.amusic.data.db.MusicDatabase
import com.amusic.data.db.SongDao
import com.amusic.data.model.RecommendMap
import com.amusic.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Shown on the settings import row; matchedSongs is "songs that carry a track id". */
data class RecommendStatus(
    val nativeAvailable: Boolean = false,
    val payloadLoaded: Boolean = false,
    val payloadTracks: Int = 0,
    val matchedSongs: Int = 0,
    val librarySongs: Int = 0,
    val indexing: Boolean = false,
    val lastError: String? = null,
)

/** One similarity facet of a [SimilarSong], e.g. timbre 0.82. */
data class SimilarGroup(val key: String, val score: Double)

data class SimilarSong(val song: Song, val score: Double, val groups: List<SimilarGroup>)

sealed interface SimilarResult {
    data class Ok(val items: List<SimilarSong>) : SimilarResult
    /** The engine is not usable at all (no .so / nothing imported). */
    data object Unavailable : SimilarResult
    /** Usable in general, but this seed has no features in the imported payload. */
    data object NoPayloadForSong : SimilarResult
}

/**
 * Glue between the a-music library and the music-recommend query engine
 * (libmusicspace.so — see native/musicspace). Three pieces of state:
 *
 *  1. payload files `manifest.kv` + `tracks.tsv`, exported by the desktop extractor and
 *     imported via SAF into filesDir/musicspace (content-hash ids only, no titles);
 *  2. the native Space, opened once per process and kept natively;
 *  3. RecommendMap: songId → trackId, derived by content hash and cached in Room so
 *     rescans only re-hash changed files.
 *
 * Feature extraction never runs here — a song that is not in the desktop export simply
 * has no recommendation identity (NoPayloadForSong), which the UI states honestly.
 */
class RecommendationCenter(
    private val context: Context,
    db: MusicDatabase,
    private val songDao: SongDao,
    private val scope: CoroutineScope,
) {
    private val dao = db.recommendMapDao()
    private val payloadDir get() = File(context.filesDir, "musicspace")

    private val _status = MutableStateFlow(RecommendStatus(nativeAvailable = MusicSpaceNative.available))
    val status: StateFlow<RecommendStatus> = _status.asStateFlow()

    // CONFLATED: library rescan bursts coalesce into one re-index pass.
    private val indexRequests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch(Dispatchers.IO) {
            if (MusicSpaceNative.available && payloadDir.isDirectory) {
                val n = runCatching { MusicSpaceNative.open(payloadDir.absolutePath) }.getOrDefault(-1)
                if (n >= 0) _status.update { it.copy(payloadLoaded = true, payloadTracks = n) }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (u in indexRequests) refreshIndexOnce()
        }
        // Room flows emit the current table immediately, so this covers first run too.
        scope.launch(Dispatchers.IO) {
            songDao.observeAll().collect { requestIndex() }
        }
    }

    fun requestIndex() {
        indexRequests.trySend(Unit)
    }

    /**
     * Copy manifest.kv + tracks.tsv out of the picked SAF tree, open them natively,
     * and kick off (re)matching. Returns a user-readable error or null on success.
     */
    suspend fun import(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        if (!MusicSpaceNative.available) return@withContext "构建缺少 libmusicspace.so,请用带 Rust 工具链的构建"
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext "无法读取所选目录"
        val dir = payloadDir
        dir.deleteRecursively()
        dir.mkdirs()
        for (name in listOf("manifest.kv", "tracks.tsv")) {
            val doc = root.findFile(name)
            if (doc == null) {
                dir.deleteRecursively()
                return@withContext "目录里缺少 $name(先用桌面端 music-recommend 导出)"
            }
            try {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                } ?: run {
                    dir.deleteRecursively()
                    return@withContext "无法读取 $name"
                }
            } catch (_: SecurityException) {
                dir.deleteRecursively()
                return@withContext "无法读取 $name(权限不足)"
            }
        }
        val n = runCatching { MusicSpaceNative.open(dir.absolutePath) }.getOrDefault(-1)
        if (n < 0) {
            dir.deleteRecursively()
            return@withContext "载荷解析失败(导出版本和 app 不匹配?重新导出一次)"
        }
        _status.update { it.copy(payloadLoaded = true, payloadTracks = n, lastError = null) }
        requestIndex()
        null
    }

    /**
     * Hash every library song that changed since the last pass (size + dateAdded
     * decide) and store its content-hash track id. First pass reads up to 512 KB per
     * song — minutes on a big library, then near-free.
     */
    private suspend fun refreshIndexOnce() {
        // No payload yet → hashing the whole library would be pure battery waste;
        // import() flips payloadLoaded and asks again.
        if (!_status.value.payloadLoaded) return
        _status.update { it.copy(indexing = true, lastError = null) }
        try {
            val songs = songDao.allOnce()
            val existing = dao.all().associateBy { it.songId }
            val updates = ArrayList<RecommendMap>()
            var matched = 0
            for (song in songs) {
                val row = existing[song.id]
                if (row != null && row.size == song.size && row.dateAdded == song.dateAdded) {
                    matched++
                    continue
                }
                val tid = if (song.data.startsWith("content://")) {
                    null
                } else {
                    TrackIdentity.trackIdFor(song.data, song.size)
                }
                if (tid != null) {
                    updates += RecommendMap(song.id, tid, song.size, song.dateAdded)
                    matched++
                }
            }
            if (updates.isNotEmpty()) dao.upsertAll(updates)
            dao.prune()
            _status.update { it.copy(indexing = false, matchedSongs = matched, librarySongs = songs.size) }
        } catch (e: Exception) {
            _status.update { it.copy(indexing = false, lastError = e.message ?: "索引失败") }
        }
    }

    /** song -> song similar, resolved back onto library rows. */
    suspend fun similar(songId: Long, limit: Int = 30): SimilarResult = withContext(Dispatchers.Default) {
        if (!MusicSpaceNative.available || !_status.value.payloadLoaded) {
            return@withContext SimilarResult.Unavailable
        }
        val seedRow = dao.getBySongId(songId) ?: return@withContext SimilarResult.NoPayloadForSong
        val json = runCatching { MusicSpaceNative.similar(seedRow.trackId, limit) }.getOrNull()
            ?: return@withContext SimilarResult.NoPayloadForSong

        val arr = JSONArray(json)
        if (arr.length() == 0) return@withContext SimilarResult.Ok(emptyList())

        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) ids += arr.getJSONObject(i).getString("id")
        // trackId -> songId comes from Room; several songs may share a hash if the same
        // file exists twice — keep the first seen so the list has no duplicates.
        val songIdByTrack = HashMap<String, Long>()
        dao.getByTrackIds(ids).forEach { row ->
            songIdByTrack.putIfAbsent(row.trackId, row.songId)
        }
        val songs = songDao.getByIds(songIdByTrack.values.toList()).associateBy { it.id }

        val items = ArrayList<SimilarSong>(arr.length())
        val seen = HashSet<Long>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sid = songIdByTrack[o.getString("id")] ?: continue
            if (!seen.add(sid)) continue
            val song = songs[sid] ?: continue
            val gj = o.getJSONObject("groups")
            val groups = (0 until gj.length()).map { j ->
                val key = gj.names().getString(j)
                SimilarGroup(key, gj.getDouble(key))
            }
            items += SimilarSong(song, o.getDouble("score"), groups)
        }
        SimilarResult.Ok(items)
    }
}
