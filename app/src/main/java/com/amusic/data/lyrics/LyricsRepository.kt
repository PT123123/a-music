package com.amusic.data.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves lyrics for a track, in this order:
 *   1. in-memory cache
 *   2. a sidecar `.lrc` next to the audio file (`song.flac` -> `song.lrc`), if readable
 *   3. online: QQ Music search -> best match -> lyric (with translation)
 *
 * QQ Music's own on-device lyric files are stored in a private, hash-named folder that
 * other apps cannot read on Android 11+, and QQ's downloads here carry no embedded
 * lyrics — hence the online lookup.
 */
class LyricsRepository(private val context: Context) {

    private val cache = HashMap<String, List<LyricLine>>()

    suspend fun getLyrics(
        title: String,
        artist: String,
        audioPath: String?,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "$title\u0000$artist"
        cache[key]?.let {
            Log.d(TAG, "cache hit '$title' -> ${it.size} lines")
            return@withContext it
        }

        loadSidecar(audioPath)?.let {
            Log.d(TAG, "sidecar lrc '$title' -> ${it.size} lines")
            cache[key] = it
            return@withContext it
        }

        val online = runCatching { fetchFromQq(title, artist) }
            .getOrElse { e ->
                Log.w(TAG, "lyric fetch failed for '$title': ${e.message}")
                emptyList()
            }
        Log.d(TAG, "online lyrics '$title' / '$artist' -> ${online.size} lines")
        cache[key] = online
        online
    }

    private fun loadSidecar(audioPath: String?): List<LyricLine>? {
        if (audioPath.isNullOrBlank()) return null
        val audio = File(audioPath)
        val lrc = File(audio.parentFile, audio.nameWithoutExtension + ".lrc")
        if (!lrc.isFile) return null
        return runCatching { LrcParser.parse(lrc.readText()) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    // ---- QQ Music ----

    private fun fetchFromQq(title: String, artist: String): List<LyricLine> {
        val mid = searchSongMid(title, artist) ?: return emptyList()
        val raw = http(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" +
                "$mid&format=json&nobase64=1&g_tk=5381",
            referer = "https://y.qq.com/portal/player.html",
        )
        val json = JSONObject(raw)
        val lyric = json.optString("lyric").takeIf { it.isNotBlank() && it != "null" }
        val trans = json.optString("trans").takeIf { it.isNotBlank() && it != "null" }
        return LrcParser.parse(lyric, trans)
    }

    /** Searches QQ Music and returns the songmid of the closest title/artist match. */
    private fun searchSongMid(title: String, artist: String): String? {
        val kw = URLEncoder.encode("$title $artist", "UTF-8")
        val raw = http(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$kw&format=json&n=5&p=1",
            referer = "https://y.qq.com/",
        )
        val list = JSONObject(raw)
            .optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return null
        if (list.length() == 0) return null

        var bestMid: String? = null
        var bestScore = Int.MIN_VALUE
        val wantTitle = norm(title)
        val wantArtist = norm(artist)
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = norm(o.optString("songname"))
            val singers = o.optJSONArray("singer")
                ?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.optJSONObject(it)?.optString("name").orEmpty() } }
                .orEmpty()
            val singerNorm = norm(singers)
            var score = 0
            if (name == wantTitle) score += 100
            else if (name.contains(wantTitle) || wantTitle.contains(name)) score += 40
            if (wantTitle.isNotEmpty() && name.isNotEmpty()) {
                score += commonPrefix(wantTitle, name)
            }
            if (wantArtist.isNotEmpty() && singerNorm.contains(wantArtist)) score += 30
            if (score > bestScore) {
                bestScore = score
                bestMid = o.optString("songmid").takeIf { it.isNotBlank() }
            }
        }
        return bestMid
    }

    private fun http(url: String, referer: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Referer", referer)
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[\\s()\\[\\]（）【】《》~～!！?？,，.。\\-—_'\"、/|]"), "")

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private companion object {
        const val TAG = "LyricsRepository"
    }
}
