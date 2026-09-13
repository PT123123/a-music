package com.amusic.data.online

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin client for QQ Music's *public* web endpoints.
 *
 * Two calls make the whole online feature work:
 *  1. [search]      -> `c.y.qq.com/soso/fcgi-bin/client_search_cp`  (keyword -> songs)
 *  2. [directUrl]   -> `u.y.qq.com/cgi-bin/musicu.fcg`             (songmid -> playable URL)
 *
 * Notes / gotchas learned the hard way:
 *  - The endpoints reject requests without a browser-ish `User-Agent` and a
 *    `Referer: https://y.qq.com/`. Missing them yields a base64 "anti-crawl" blob
 *    instead of JSON.
 *  - Resolved URLs come back as plain HTTP (e.g. `aqqmusic.tc.qq.com`), so the app
 *    needs `usesCleartextTraffic=true` in the manifest to download them.
 *  - A VIP-only track resolves to an empty `purl`; we surface that to the UI rather
 *    than attempting a download that would 403.
 */
object QqOnlineApi {

    private const val TAG = "QqOnlineApi"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private const val REFERER = "https://y.qq.com/"

    /** Arbitrary but stable; QQ only echoes it back in the signed URL. */
    private const val GUID = "3982823384"

    private const val SEARCH_ENDPOINT = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val MUSICU_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    /** Suggestions shown before the user types anything. */
    val hotWords: List<String> = listOf(
        "周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "薛之谦",
        "毛不易", "五月天", "华语流行", "轻音乐", "粤语经典",
    )

    // ---------------------------------------------------------------- search

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<OnlineSong> =
        withContext(Dispatchers.IO) {
            val w = keyword.trim()
            if (w.isEmpty()) return@withContext emptyList()
            val url = buildString {
                append(SEARCH_ENDPOINT)
                append("?w=").append(enc(w))
                append("&format=json&n=").append(pageSize)
                append("&p=").append(page)
                append("&cr=1&new_json=0&platform=wxforsong&needNewCode=0")
            }
            val body = runCatching { httpGet(url) }.getOrElse {
                Log.w(TAG, "search failed for '$w': ${it.message}")
                return@withContext emptyList()
            }
            runCatching { parseSearch(body) }.getOrElse {
                Log.w(TAG, "search parse failed for '$w': ${it.message}")
                emptyList()
            }.also { Log.i(TAG, "search '$w' -> ${it.size} songs") }
        }

    private fun parseSearch(body: String): List<OnlineSong> {
        val root = JSONObject(body)
        val list = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return emptyList()

        val out = ArrayList<OnlineSong>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val mid = o.optString("songmid")
            if (mid.isBlank()) continue
            out += OnlineSong(
                mid = mid,
                title = o.optString("songname").ifBlank { o.optString("title") },
                artist = singersOf(o),
                album = o.optString("albumname"),
                albumMid = o.optString("albummid"),
                durationSec = o.optInt("interval"),
                sizeBytes = o.optLong("size128"),
                vipOnly = o.optJSONObject("pay")?.optInt("payplay", 0) == 1,
            )
        }
        return out
    }

    private fun singersOf(o: JSONObject): String {
        val arr = o.optJSONArray("singer") ?: return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotBlank()) names += n
        }
        return names.joinToString(" / ")
    }

    // ------------------------------------------------------------ direct url

    /**
     * Resolve a playable/downloadable URL for [mid].
     * Returns null when the track is not freely available (VIP-only, taken down…).
     */
    suspend fun directUrl(mid: String): String? = withContext(Dispatchers.IO) {
        if (mid.isBlank()) return@withContext null

        val vkeyParam = JSONObject()
            .put("guid", GUID)
            .put("songmid", JSONArray().put(mid))
            .put("songtype", JSONArray().put(0))
            .put("uin", "0")
            .put("loginflag", 1)
            .put("platform", "20")

        val payload = JSONObject()
            .put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .put(
                "req", JSONObject()
                    .put("module", "CDN.SrfCdnDispatchServer")
                    .put("method", "GetCdnDispatch")
                    .put("param", JSONObject().put("guid", GUID).put("calltype", 0).put("userip", "")),
            )
            .put(
                "req_0", JSONObject()
                    .put("module", "vkey.GetVkeyServer")
                    .put("method", "CgiGetVkey")
                    .put("param", vkeyParam),
            )

        val url = "$MUSICU_ENDPOINT?format=json&data=" + enc(payload.toString())
        val body = runCatching { httpGet(url) }.getOrElse {
            Log.w(TAG, "vkey request failed for $mid: ${it.message}")
            return@withContext null
        }

        val data = try {
            JSONObject(body).optJSONObject("req_0")?.optJSONObject("data")
        } catch (t: Throwable) {
            Log.w(TAG, "vkey parse failed for $mid: ${t.message}")
            null
        }

        val purl = data?.optJSONArray("midurlinfo")
            ?.optJSONObject(0)
            ?.optString("purl")
            .orEmpty()
        if (purl.isBlank()) {
            Log.i(TAG, "no playable url for $mid (VIP-only or unavailable)")
            return@withContext null
        }
        if (purl.startsWith("http")) return@withContext purl

        val sip = data?.optJSONArray("sip")
        val host = if (sip != null && sip.length() > 0) sip.optString(0) else ""
        host + purl
    }

    // -------------------------------------------------------------- plumbing

    private fun httpGet(url: String, referer: String = REFERER): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "*/*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) Log.w(TAG, "HTTP $code for ${url.take(120)}")
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** Headers the downloader must send when pulling audio off QQ's CDN. */
    internal fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to REFERER,
        "Accept" to "*/*",
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
