package com.amusic.data.online

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One progress sample: bytes written so far and the announced total (0 when unknown). */
data class DlProgress(val done: Long, val total: Long) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    /** `12.4 / 198.5 MB`, or just the written amount when the server sent no length. */
    val sizeText: String
        get() = if (total > 0) "${fmtBytes(done)} / ${fmtBytes(total)}" else fmtBytes(done)
}

/** Compact byte size used across the download UI. */
fun fmtBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.0f KB".format(bytes / 1024.0)
}

/**
 * Downloads an online track's audio stream into the device's public Music collection so it
 * becomes a first-class part of the local library (visible to MediaStore, other players,
 * and our own scanner).
 *
 * Target folder: `Music/amusic/`. On API 29+ everything goes through MediaStore with the
 * IS_PENDING handshake (no storage permission needed). Older devices fall back to a direct
 * write + MediaScanner broadcast (needs WRITE_EXTERNAL_STORAGE, declared with
 * `maxSdkVersion="28"`).
 *
 * Two callers:
 *  - [download]       — QQ Music (`.m4a`, needs QQ's UA/Referer)
 *  - [downloadNet24]  — 无损站 (`.flac`, needs that site's UA/Referer)
 */
class OnlineDownloader(private val context: Context) {

    /** Everything the writers need to know about a single download. */
    private data class Spec(
        val fileName: String,
        val mime: String,
        val title: String,
        val artist: String,
        val album: String,
    )

    // ------------------------------------------------------------- QQ Music

    suspend fun download(
        song: OnlineSong,
        url: String,
        onProgress: (DlProgress) -> Unit = {},
    ): Uri? = download(
        url = url,
        headers = QqOnlineApi.downloadHeaders(),
        spec = Spec(
            fileName = fileNameFor(song.displayName, song.mid, "m4a"),
            mime = "audio/mp4",
            title = song.title,
            artist = song.artist,
            album = song.album,
        ),
        onProgress = onProgress,
    )

    // -------------------------------------------------------------- Net24

    suspend fun downloadNet24(
        song: Net24Song,
        dl: Net24Download,
        onProgress: (DlProgress) -> Unit = {},
    ): Uri? = download(
        url = dl.url,
        headers = Net24Api.downloadHeaders(),
        spec = Spec(
            fileName = dl.fileName,
            mime = mimeFor(dl.ext),
            title = song.title,
            artist = song.artist,
            album = song.album.ifBlank { "未知" },
        ),
        onProgress = onProgress,
    )

    // ---------------------------------------------------------------- core

    private suspend fun download(
        url: String,
        headers: Map<String, String>,
        spec: Spec,
        onProgress: (DlProgress) -> Unit,
    ): Uri? = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "download HTTP $code for ${spec.fileName}")
                return@withContext null
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeViaMediaStore(input, spec, total, onProgress)
                } else {
                    writeLegacy(input, spec, total, onProgress)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "download failed for ${spec.fileName}", t)
            null
        } finally {
            conn.disconnect()
        }
    }

    // ----------------------------------------------------------- MediaStore

    private fun writeViaMediaStore(
        input: InputStream,
        spec: Spec,
        total: Long,
        onProgress: (DlProgress) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Audio.Media.TITLE, spec.title)
            put(MediaStore.Audio.Media.ARTIST, spec.artist)
            put(MediaStore.Audio.Media.ALBUM, spec.album)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "MediaStore insert returned null for ${spec.fileName}")
            return null
        }
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("no output stream")
            out.use { copy(input, it, total, onProgress) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            Log.i(TAG, "saved via MediaStore: $RELATIVE_DIR${spec.fileName}")
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    // ---------------------------------------------------------------- legacy

    @Suppress("DEPRECATION")
    private fun writeLegacy(
        input: InputStream,
        spec: Spec,
        total: Long,
        onProgress: (DlProgress) -> Unit,
    ): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create ${dir.absolutePath}")
            return null
        }
        val dot = spec.fileName.lastIndexOf('.')
        val stem = if (dot > 0) spec.fileName.substring(0, dot) else spec.fileName
        val ext = if (dot > 0) spec.fileName.substring(dot) else ".m4a"
        var out = File(dir, spec.fileName)
        var n = 1
        while (out.exists()) {
            out = File(dir, "$stem ($n)$ext")
            n++
        }
        out.outputStream().use { copy(input, it, total, onProgress) }
        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
        Log.i(TAG, "saved via legacy path: ${out.absolutePath}")
        return Uri.fromFile(out)
    }

    // -------------------------------------------------------------- plumbing

    private fun copy(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: (DlProgress) -> Unit,
    ) {
        val buf = ByteArray(128 * 1024)
        var done = 0L
        var lastPct = -1
        var lastEmit = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            // Emit at most on every whole percent and at most ~5x/second, so a 200 MB flac
            // does not spam the UI with a thousand recompositions.
            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
            val now = SystemClock.elapsedRealtime()
            if (pct != lastPct || now - lastEmit >= 200) {
                lastPct = pct
                lastEmit = now
                onProgress(DlProgress(done, total))
            }
        }
        output.flush()
        onProgress(DlProgress(done, if (total > 0) total else done))
    }

    private fun fileNameFor(displayName: String, fallbackKey: String, ext: String): String {
        val base = displayName
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim()
            .take(120)
        return (base.ifBlank { fallbackKey }) + "." + ext
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "audio/*"
    }

    private companion object {
        const val TAG = "OnlineDownloader"
        const val FOLDER = "amusic"
        const val RELATIVE_DIR = "Music/$FOLDER/"
    }
}
