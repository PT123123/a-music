package com.amusic.data.lan

import android.content.Context
import android.util.Log
import com.amusic.data.online.DlProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * On-disk cache backing 缓存播放 (as opposed to 流式播放).
 *
 * Files land in the app's private external directory
 * (`Android/data/<pkg>/files/lan_cache`), so no storage permission is involved, the cache never
 * leaks into the music library (unlike [com.amusic.data.online.OnlineDownloader], which writes
 * to `Music/amusic/` deliberately), and uninstalling removes it. A URL is fetched once and
 * reused on every later play, which is what makes a re-listen instant and offline-capable.
 *
 * Downloads are staged as `<name>.part` and renamed on completion, so an interrupted fetch can
 * never masquerade as a finished cache entry.
 */
class LanCache(context: Context) {

    private val root: File =
        File(context.applicationContext.getExternalFilesDir(null) ?: context.filesDir, DIR)

    /** The cached file for [url] when a completed download is present. */
    fun existing(url: String): File? {
        val file = fileFor(url)
        return if (file.isFile && file.length() > 0) file else null
    }

    /** Download [url] into the cache (or return the existing copy). Null on any failure. */
    suspend fun fetch(url: String, onProgress: (DlProgress) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            existing(url)?.let { return@withContext it }
            if (!root.exists() && !root.mkdirs()) return@withContext null

            val target = fileFor(url)
            val part = File(root, target.name + ".part")
            val conn = LanHttp.open(url)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "HTTP $code for ${url.take(120)}")
                    return@withContext null
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { output -> copy(input, output, total, onProgress) }
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    return@withContext null
                }
                target
            } catch (t: Throwable) {
                Log.w(TAG, "cache fetch failed: ${url.take(120)}", t)
                part.delete()
                null
            } finally {
                conn.disconnect()
            }
        }

    fun sizeBytes(): Long =
        root.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun clear() {
        root.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * Cache filename: a SHA-1 of the full URL (so signed URLs with query strings stay distinct
     * and the name is filesystem-safe) plus the original extension, which mpv uses as a hint.
     */
    fun fileFor(url: String): File =
        File(root, sha1(url) + "." + extensionOf(url).ifBlank { "bin" })

    private fun copy(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: (DlProgress) -> Unit,
    ) {
        val buffer = ByteArray(128 * 1024)
        var done = 0L
        var lastEmit = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 200) {
                lastEmit = now
                onProgress(DlProgress(done, total))
            }
        }
        output.flush()
        onProgress(DlProgress(done, if (total > 0) total else done))
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LanCache"
        const val DIR = "lan_cache"
    }
}
