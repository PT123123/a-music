package com.amusic.data.recommend

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The content-stable track id used by music-recommend: SHA-1 over "v2:" + file size +
 * the first 256 KB + (for files > 512 KB) the last 256 KB, hex, first 16 chars.
 *
 * This MUST stay byte-identical to `track_id_for()` in the desktop engine's
 * `preprocess/audio.py` — the recommendation payload is keyed by these ids, and the
 * phone joins it to its own library by re-deriving them. Size + head/tail blocks (not
 * a full-file hash) keep it cheap: at most 512 KB read per song.
 */
object TrackIdentity {
    private const val CHUNK = 256 * 1024

    /** Null when the file cannot be read (gone, or a content:// URI without a path). */
    fun trackIdFor(path: String, size: Long): String? = try {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("v2:".toByteArray(Charsets.UTF_8))
        md.update(size.toString().toByteArray(Charsets.UTF_8))

        File(path).inputStream().use { ins ->
            val head = ByteArray(CHUNK)
            var off = 0
            while (off < CHUNK) {
                val n = ins.read(head, off, CHUNK - off)
                if (n <= 0) break
                off += n
            }
            md.update(head, 0, off)
        }

        if (size > 2 * CHUNK) {
            RandomAccessFile(path, "r").use { raf ->
                raf.seek(size - CHUNK)
                val tail = ByteArray(CHUNK)
                var off = 0
                while (off < CHUNK) {
                    val n = raf.read(tail, off, CHUNK - off)
                    if (n <= 0) break
                    off += n
                }
                md.update(tail, 0, off)
            }
        }

        md.digest().joinToString("") { "%02x".format(it) }.take(16)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
