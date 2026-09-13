package com.amusic.data.online

import android.util.Log
import com.amusic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the online lossless download source ("无损站").
 *
 * The site's base URL is machine-local: it is set as `net24.baseUrl` in `local.properties`
 * (gitignored, see app/build.gradle.kts) and reaches the code through
 * [BuildConfig.NET24_BASE_URL]. Never hard-code the URL here — this file is committed.
 *
 * The site is a Next.js/React SPA, so there is no documented API. Two things make it usable:
 *
 *  1. Search — `POST <base>/api/player/searchOnlineMusic{One,Two}`
 *     with a JSON body `{"keyword":"…","page":1}`. Returns `{status, result:[…]}` where each
 *     item carries `id / cover / name / player / album`.
 *
 *  2. Resolve — the *detail page* `<base>/music/<type>/<id>` is
 *     server-rendered, and its React-flight payload embeds a full `itemMusic` object
 *     including a **direct, unauthenticated CDN url** (`…music.126.net/….flac` or
 *     `kw-….kuwo.cn/…`) plus `quality` and `size`.
 *
 * Gotchas learned by probing (each one cost a wasted request):
 *  - `searchOnlineMusicOne` (母带源) and `…Two` (无损源) index **different upstream
 *    providers**, and their ids are NOT interchangeable: `/music/b/<id-from-One>` answers
 *    with an unrelated 酷我 track, and `/music/a|c/<id-from-Two>` does the same in reverse.
 *    Only `a`+`c` from One, and `b` from Two, land on the song you asked for.
 *  - Every payload is therefore **name-checked** against the row before it is offered, so a
 *    broken pairing surfaces as "not available" instead of downloading the wrong song.
 *  - Detail pages are **rate limited per visitor** ("今日访问已达限额，可明日再来") with a small
 *    anonymous allowance. Nothing may be prefetched: a tier is resolved only when the user
 *    asks for it, and successful lookups are cached for the session.
 *  - A missing `Referer` (site origin) header on the search POST yields validation errors.
 *  - The keyword is sent *percent-encoded inside the JSON body* (the site uses
 *    `encodeURIComponent`), not as a raw string.
 *  - `<type>` must be a single letter `a` / `c` / `b`; integers are rejected.
 *  - The flight payload escapes quotes (`\"`) and ampersands (`\u0026`), so the raw HTML
 *    must be unescaped before the `itemMusic` object can be read as JSON-ish text.
 */
object Net24Api {

    private const val TAG = "Net24Api"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val BASE = BuildConfig.NET24_BASE_URL.trimEnd('/').ifBlank { "about:blank" }
    private val SITE = "$BASE/"
    private val API_BASE = "$BASE/api"
    private val EP_SEARCH_ONE = "$API_BASE/player/searchOnlineMusicOne"
    private val EP_SEARCH_TWO = "$API_BASE/player/searchOnlineMusicTwo"

    private const val QUOTA_MARK = "今日访问已达限额"

    /** Suggestions shown before the user types anything (mirrors the site's weekly chart). */
    val hotWords: List<String> = listOf(
        "周杰伦", "陈奕迅", "Beyond", "张学友", "蔡琴",
        "王菲", "邓紫棋", "谭咏麟", "林俊杰", "邓丽君",
        "富士山下", "渡口", "海阔天空", "晴天", "青花瓷", "稻香",
    )

    /** Resolved detail payloads, keyed by `"<type>:<id>"` — each one costs a quota slot. */
    private val cache = HashMap<String, Net24Detail>()

    // ---------------------------------------------------------------- search

    suspend fun search(keyword: String, page: Int = 1): List<Net24Song> = withContext(Dispatchers.IO) {
        val w = keyword.trim()
        if (w.isEmpty()) return@withContext emptyList()

        val body = JSONObject()
            .put("keyword", enc(w))
            .put("page", page)
            .toString()

        val one = runCatching { post(EP_SEARCH_ONE, body) }.getOrElse {
            Log.w(TAG, "search(one) failed for '$w': ${it.message}")
            ""
        }
        val two = runCatching { post(EP_SEARCH_TWO, body) }.getOrElse {
            Log.w(TAG, "search(two) failed for '$w': ${it.message}")
            ""
        }

        // Fold the two listings into one row per song, so a track present in both catalogues
        // gets all three quality buttons instead of two half-rows.
        val merged = LinkedHashMap<String, Net24Song>()
        (parseSearch(one, Net24Origin.MASTER) + parseSearch(two, Net24Origin.LOSSLESS)).forEach { song ->
            val k = mergeKey(song)
            val existing = merged[k]
            merged[k] = existing?.merge(song) ?: song
        }
        val out = merged.values.toList()
        Log.i(TAG, "search '$w' -> ${out.size} songs")
        out
    }

    private fun mergeKey(song: Net24Song): String =
        norm(song.title) + "\u0000" + leadArtist(song.artist)

    private fun parseSearch(body: String, origin: Net24Origin): List<Net24Song> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!root.optBoolean("status")) return emptyList()
        val arr = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<Net24Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out += Net24Song(
                title = o.optString("name"),
                artist = o.optString("player"),
                album = o.optString("album"),
                coverUrl = o.optString("cover"),
                masterId = if (origin == Net24Origin.MASTER) id else null,
                losslessId = if (origin == Net24Origin.LOSSLESS) id else null,
            )
        }
        return out
    }

    // --------------------------------------------------------------- resolve

    /** Everything the detail page tells us about one (type, id) pair. */
    private data class Net24Detail(
        val url: String,
        val name: String,
        val player: String,
        val quality: String,
        val sizeText: String,
        val ext: String,
    )

    private sealed interface DetailResult {
        data class Ok(val detail: Net24Detail) : DetailResult
        data class Fail(val reason: String) : DetailResult
    }

    /**
     * Resolve one tier for [song]. Never silently returns a different song: the site's answer
     * is compared with the row first, so a bad id pairing surfaces as [Net24Resolve.Fail].
     *
     * Call this *before* downloading — that is how the UI can show the file size, and it is
     * also the only way to keep the request count (and the site's daily quota) low.
     */
    suspend fun resolve(song: Net24Song, quality: Net24Quality): Net24Resolve {
        val id = song.qualityId(quality)
            ?: return Net24Resolve.Fail("这首歌在${quality.origin.listLabel}里没有${quality.short}版本")

        val detail = when (val r = detail(quality.type, id)) {
            is DetailResult.Fail -> return Net24Resolve.Fail(r.reason)
            is DetailResult.Ok -> r.detail
        }

        mismatchReason(song, detail)?.let { reason ->
            Log.w(TAG, "id mismatch ${quality.type}:$id — asked '${song.title}', got '${detail.name}'")
            return Net24Resolve.Fail(reason)
        }

        return Net24Resolve.Ok(
            tier = quality,
            download = Net24Download(
                url = detail.url,
                fileName = fileNameFor(song, detail.ext),
                ext = detail.ext,
                quality = detail.quality.ifBlank { quality.label },
                sizeText = detail.sizeText,
                sizeBytes = parseSize(detail.sizeText),
            )
        )
    }

    /**
     * Preview streams the *smallest* tier the row can serve so it starts fast — 无损 (b) is
     * roughly a quarter of the 母带 (a) size for the same track.
     */
    suspend fun preview(song: Net24Song): Net24Resolve {
        val preferred = Net24Quality.entries
            .filter { it in song.qualities }
            .minByOrNull { it.previewRank() }
            ?: return Net24Resolve.Fail("这首歌暂无可用的音质版本，站点只提供未收录的其它档")
        return resolve(song, preferred)
    }

    /** Crude ranking used only to pick the lightest preview tier. */
    private fun Net24Quality.previewRank(): Int = when (this) {
        Net24Quality.LOSSLESS -> 1
        Net24Quality.SURROUND -> 2
        Net24Quality.MASTER -> 3
    }

    /** Null when the site's answer really is the song we asked for. */
    private fun mismatchReason(song: Net24Song, d: Net24Detail): String? {
        val wantTitle = norm(song.title)
        val gotTitle = norm(d.name)
        if (wantTitle.isEmpty() || gotTitle.isEmpty()) return null
        val titleOk = wantTitle == gotTitle || wantTitle.contains(gotTitle) || gotTitle.contains(wantTitle)
        if (!titleOk) return "站点返回的是《${d.name}》，该音质档没有这首歌的版本"

        // Titles line up — only cross-check the artist when both sides actually have one.
        val wantArtist = leadArtist(song.artist)
        val gotArtist = leadArtist(d.player)
        if (wantArtist.isNotEmpty() && gotArtist.isNotEmpty() &&
            wantArtist != gotArtist && !gotArtist.contains(wantArtist) && !wantArtist.contains(gotArtist)
        ) {
            return "站点返回的是 ${d.player} 的版本，与这首歌不匹配"
        }
        return null
    }

    private suspend fun detail(type: String, id: String): DetailResult = withContext(Dispatchers.IO) {
        val key = "$type:$id"
        synchronized(cache) { cache[key] }?.let { return@withContext DetailResult.Ok(it) }

        val url = "$SITE" + "music/$type/$id"
        val html = runCatching { httpGet(url) }.getOrElse {
            Log.w(TAG, "detail fetch failed for $key: ${it.message}")
            return@withContext DetailResult.Fail("请求失败：${it.message ?: "网络异常"}")
        }
        if (html.contains(QUOTA_MARK)) {
            Log.w(TAG, "lossless site daily quota exhausted")
            return@withContext DetailResult.Fail("无损站今日访问额度已用完，明天再来（或登录站点账号）")
        }

        val parsed = parseDetail(html)
            ?: return@withContext DetailResult.Fail("站点没有给出这首歌的直链，可能已下架")
        synchronized(cache) { cache[key] = parsed }
        Log.i(TAG, "detail $key -> ${parsed.quality} ${parsed.sizeText} ${parsed.ext}")
        DetailResult.Ok(parsed)
    }

    private fun parseDetail(html: String): Net24Detail? {
        val anchor = html.indexOf("itemMusic")
        if (anchor < 0) return null
        val frag = html.substring(anchor, minOf(html.length, anchor + 2200))
            .replace("\\\"", "\"")
            .replace("\\n", " ")

        fun field(k: String): String {
            val m = Regex("\"$k\":\"([^\"]*)\"").find(frag) ?: return ""
            return m.groupValues[1]
        }

        val url = field("url")
        if (url.isBlank() || !url.startsWith("http")) return null
        val fmt = field("format").ifBlank { "flac" }
        return Net24Detail(
            url = url,
            name = field("name").unescape(),
            player = field("player").unescape(),
            quality = field("quality"),
            sizeText = field("size").trim(),
            ext = fmt.lowercase(),
        )
    }

    private fun fileNameFor(song: Net24Song, ext: String): String {
        val base = song.displayName
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim()
            .take(110)
        return (base.ifBlank { song.id }) + "." + ext.ifBlank { "flac" }
    }

    // -------------------------------------------------------------- plumbing

    /** Extra headers the downloader must reuse — the CDNs care about UA, the site about Referer. */
    fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to SITE,
        "Accept" to "*/*",
    )

    private fun post(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", SITE)
            setRequestProperty("Origin", BASE)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) Log.w(TAG, "HTTP $code for ${url.take(90)}")
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", SITE)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) Log.w(TAG, "HTTP $code for ${url.take(90)}")
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** `encodeURIComponent` semantics; [URLEncoder] uses `+` for spaces, the site uses `%20`. */
    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The flight payload escapes `&` as `\u0026`; turn it back into a character. */
    private fun String.unescape(): String =
        replace("\\u0026", "&").replace("\\/", "/")

    /** Loose key used for matching: case/punctuation/whitespace insensitive. */
    private fun norm(s: String): String = s
        .lowercase()
        .replace(Regex("[\\s()\\[\\]（）【】《》~～!！?？,，.。\\-—_'\"、/|]"), "")

    /** `周杰伦&温岚` -> `周杰伦`, so the two catalogues line up on the lead artist. */
    private fun leadArtist(s: String): String =
        norm(s.split('&', '、', ',', '，', '/', ';', '；').firstOrNull().orEmpty())

    /** `198.47MB` / `21.57 MB` -> bytes. Returns 0 when it cannot be read. */
    fun parseSize(text: String): Long {
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB)", RegexOption.IGNORE_CASE)
            .find(text) ?: return 0
        val value = m.groupValues[1].toDoubleOrNull() ?: return 0
        return when (m.groupValues[2].uppercase()) {
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            else -> value.toLong()
        }
    }
}
