package com.amusic.data.lan

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * Browses a plain HTTP "autoindex" file server — nginx, Apache, `python -m http.server`, a NAS
 * web share — by scraping the `<a href>` links out of its directory listing.
 *
 * This is deliberately the lowest-common-denominator of "HTTP music": no WebDAV, no JSON API,
 * just the HTML every static file server already serves. Directories are told apart from files
 * by a trailing slash or the absence of a file extension; anything without a known audio suffix
 * is hidden so a listing full of cover art / logs does not clutter the player.
 */
class HttpDirectoryBrowser {

    suspend fun list(baseUrl: String, url: String): List<LanEntry> = withContext(Dispatchers.IO) {
        val body = LanHttp.getText(url)
        parse(baseUrl, url, body)
    }

    private fun parse(baseUrl: String, url: String, body: String): List<LanEntry> {
        val out = ArrayList<LanEntry>()
        val seen = HashSet<String>()

        // A synthetic ".." row keeps the tree navigable without a separate breadcrumb control.
        parentOf(baseUrl, url)?.let { parent ->
            out += LanEntry(id = parent, name = PARENT, isContainer = true)
        }

        for (match in ANCHOR.findAll(body)) {
            val href = unquote(match.groupValues[1]).trim()
            if (href.isEmpty()) continue
            val lower = href.lowercase()
            if (lower.startsWith("#") || lower.startsWith("?") ||
                lower.startsWith("javascript:") || lower.startsWith("mailto:") ||
                lower.startsWith("..")
            ) continue

            val abs = LanHttp.resolve(url, href) ?: continue
            if (abs == url || abs == baseUrl) continue

            val ext = extensionOf(abs)
            val dir = href.endsWith("/")
            val hasQuery = abs.contains('?')
            // Sort / column links (`?C=N;O=D`) are query-only; drop them unless the URL carries a
            // real audio extension (some servers sign media links with a query string).
            if (hasQuery && ext !in AUDIO_EXTENSIONS) continue

            val isContainer = dir || (ext.isEmpty() && !hasQuery)
            if (!isContainer && ext !in AUDIO_EXTENSIONS) continue
            if (!seen.add(abs)) continue

            out += LanEntry(
                id = abs,
                name = textOf(match.groupValues[2]).ifBlank { fileNameOf(abs) },
                isContainer = isContainer,
                mediaUrl = if (isContainer) null else abs,
            )
        }
        Log.i(TAG, "listed ${out.size} entries under ${url.take(120)}")
        return out
    }

    /** The folder one level up, or null when [url] is already the source root. */
    private fun parentOf(baseUrl: String, url: String): String? {
        if (url == baseUrl) return null
        val trimmed = url.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        if (cut <= 0) return null
        val parent = trimmed.substring(0, cut + 1)
        return if (parent.startsWith(baseUrl) && parent != url) parent else null
    }

    private fun fileNameOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/')
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    private fun unquote(raw: String): String =
        if (raw.length >= 2 && (raw.first() == '"' || raw.first() == '\'') && raw.last() == raw.first()) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }

    private fun textOf(html: String): String =
        html.replace(TAG_RE, " ")
            .let(::unescape)
            .replace(WHITESPACE_RE, " ")
            .trim()

    private fun unescape(s: String): String {
        val named = s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        return NUMERIC_RE.replace(named) { m ->
            val code = m.groupValues[1].toIntOrNull(10)
                ?: m.groupValues[2].toIntOrNull(16)
            code?.let { runCatching { String(Character.toChars(it)) }.getOrDefault("") }.orEmpty()
        }
    }

    private companion object {
        const val TAG = "HttpBrowser"
        const val PARENT = ".."

        /**
         * Matches `<a … href=…>label</a>`. Group 1 is the raw href (quotes included, so that a
         * bare unquoted href also works), group 2 is the link text.
         */
        val ANCHOR = Regex(
            """<a\b[^>]*\bhref\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val TAG_RE = Regex("<[^>]*>")
        val WHITESPACE_RE = Regex("\\s+")
        val NUMERIC_RE = Regex("&#(?:x([0-9a-fA-F]+)|(\\d+));")
    }
}
