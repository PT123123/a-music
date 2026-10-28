package com.amusic.data.lan

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/**
 * Minimal HTTP plumbing shared by the LAN browsers.
 *
 * The app deliberately carries no HTTP client dependency (every other network call in the tree
 * is raw [HttpURLConnection] too), so this only wraps the two things the LAN code needs: a GET
 * that returns decoded text, and relative-URL resolution for scraping listings.
 */
internal object LanHttp {

    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 15_000

    /** A normal-looking UA: a few DLNA servers reject blank or curl-ish agents outright. */
    const val USER_AGENT = "AMusic-LAN/1.0 (Android)"

    fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }

    /**
     * GET [url] and decode the body with the charset the server announced (UTF-8 otherwise).
     * A non-2xx status throws instead of handing back an empty body, so the UI shows a real
     * error rather than "this folder is empty".
     */
    fun getText(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.close()
                throw IOException("HTTP $code")
            }
            val charset = charsetOf(conn.contentType) ?: Charsets.UTF_8
            return conn.inputStream.bufferedReader(charset).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Resolve a possibly-relative [href] against [base]; null when it cannot be parsed. */
    fun resolve(base: String, href: String): String? =
        runCatching { URI(base).resolve(href.trim()).toString() }.getOrNull()

    private fun charsetOf(contentType: String?): Charset? {
        if (contentType == null) return null
        val marker = contentType.indexOf("charset=", ignoreCase = true)
        if (marker < 0) return null
        val raw = contentType.substring(marker + "charset=".length)
            .substringBefore(';')
            .trim()
            .trim('"', '\'')
        return runCatching { Charset.forName(raw) }.getOrNull()
    }
}
