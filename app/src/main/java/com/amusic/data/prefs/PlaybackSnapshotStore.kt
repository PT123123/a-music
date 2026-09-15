package com.amusic.data.prefs

import android.content.Context
import android.util.Log
import com.amusic.player.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remembers "what was playing" so the next launch can put the user back where they were.
 *
 * Split into two stores on purpose:
 *  - the **queue** is the expensive part (a few hundred tracks), so it goes to a JSON file and
 *    is only rewritten when the list itself changes — skipping a track must not rewrite it;
 *  - the **cursor** (index + position + the URI it belongs to) moves every few seconds while
 *    playing, so it lives in SharedPreferences where a write is a couple hundred bytes.
 *
 * Every method blocks; call them from a background dispatcher.
 */
class PlaybackSnapshotStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where the cursor sat inside the queue, and which track the position belongs to. */
    data class Cursor(val index: Int, val positionMs: Long, val uri: String)

    // ---- queue ----

    /** The last saved queue, or an empty list when nothing was playing. */
    fun loadQueue(): List<Track> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt(KEY_VERSION, 0) != VERSION) {
                Log.w(TAG, "ignoring a playback queue written by a different version")
                return emptyList()
            }
            val array = root.optJSONArray(KEY_TRACKS) ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString(KEY_URI).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(
                    uri = uri,
                    title = o.optString(KEY_TITLE),
                    artist = o.optString(KEY_ARTIST),
                    album = o.optString(KEY_ALBUM),
                    albumArtUri = o.optString(KEY_ART).takeIf { it.isNotBlank() },
                    durationMs = o.optLong(KEY_DURATION),
                    songId = o.optLong(KEY_SONG_ID),
                )
            }
        }.getOrElse {
            Log.w(TAG, "playback queue on disk is unreadable; starting empty", it)
            emptyList()
        }
    }

    /** Replaces the saved queue. An empty list wipes the file (nothing worth restoring). */
    fun saveQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) {
            file.delete()
            return
        }
        runCatching {
            val array = JSONArray()
            tracks.forEach { t ->
                array.put(
                    JSONObject().apply {
                        put(KEY_URI, t.uri)
                        put(KEY_TITLE, t.title)
                        put(KEY_ARTIST, t.artist)
                        put(KEY_ALBUM, t.album)
                        put(KEY_ART, t.albumArtUri.orEmpty())
                        put(KEY_DURATION, t.durationMs)
                        put(KEY_SONG_ID, t.songId)
                    }
                )
            }
            val payload = JSONObject()
                .put(KEY_VERSION, VERSION)
                .put(KEY_TRACKS, array)
                .toString()
            // Write-then-rename: a kill mid-write must not leave a truncated queue behind.
            val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "failed to persist the playback queue", it) }
    }

    // ---- cursor ----

    fun loadCursor(): Cursor? {
        val uri = prefs.getString(KEY_CUR_URI, null) ?: return null
        return Cursor(
            index = prefs.getInt(KEY_CUR_INDEX, 0),
            positionMs = prefs.getLong(KEY_CUR_POS, 0),
            uri = uri,
        )
    }

    fun saveCursor(index: Int, positionMs: Long, uri: String) {
        runCatching {
            prefs.edit()
                .putInt(KEY_CUR_INDEX, index)
                .putLong(KEY_CUR_POS, positionMs)
                .putString(KEY_CUR_URI, uri)
                .apply()
        }.onFailure { Log.w(TAG, "failed to persist the playback cursor", it) }
    }

    fun clearCursor() {
        prefs.edit().remove(KEY_CUR_INDEX).remove(KEY_CUR_POS).remove(KEY_CUR_URI).apply()
    }

    companion object {
        private const val TAG = "PlaybackSnapshotStore"

        /** Bump when the JSON shape changes so older files can be recognised and dropped. */
        private const val VERSION = 1

        private const val FILE_NAME = "playback_queue.json"
        private const val PREFS = "amusic_playback"

        private const val KEY_VERSION = "version"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_URI = "uri"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ALBUM = "album"
        private const val KEY_ART = "art"
        private const val KEY_DURATION = "dur"
        private const val KEY_SONG_ID = "song"

        private const val KEY_CUR_INDEX = "cursor_index"
        private const val KEY_CUR_POS = "cursor_position"
        private const val KEY_CUR_URI = "cursor_uri"
    }
}
