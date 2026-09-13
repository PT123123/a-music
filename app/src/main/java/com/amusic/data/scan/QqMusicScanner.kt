package com.amusic.data.scan

import android.content.Context
import android.os.Environment
import com.amusic.data.model.Song
import java.io.File
import java.io.RandomAccessFile

/**
 * Scans QQ Music's on-device download folder for *playable* songs.
 *
 * QQ Music dumps downloads into a public shared folder:
 *   /sdcard/Music/qqmusic/song/          (mainland builds)
 *   /sdcard/qqmusic/song/                (legacy / some builds)
 *
 * Most of those files are DRM-encrypted and CANNOT be decoded by ffmpeg/libmpv:
 *   - `歌手 - 歌名 [mqms2].mflac0.flac`  (encrypted FLAC, magic != "fLaC")
 *   - `.qmc` / `.mgg` / `.tm0` / `.kgm` ...  (other DRM containers)
 *
 * This scanner keeps only the genuinely plain files (FLAC / MP3 / OGG / M4A / ...).
 * It filters in two layers:
 *   1. extension whitelist + name-marker blacklist (cheap, no I/O)
 *   2. a magic-byte probe for ambiguous containers (flac/ogg/ape/wv) so encrypted
 *      copies with a ".flac" suffix are rejected as well.
 *
 * The surviving files live in shared storage and are readable by path once the app
 * holds READ_MEDIA_AUDIO (API 33+) / READ_EXTERNAL_STORAGE, so libmpv can open them
 * via a plain `file://` URI.
 */
class QqMusicScanner(private val context: Context) {

    private val playableExt = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus",
        "wav", "wma", "ape", "tak", "tta", "mp2", "wv",
    )

    /** Infix markers of DRM/encrypted downloads (QQ Music / NetEase / Kugou). */
    private val encryptedMarkers = listOf(
        ".mflac", ".qmc", ".mgg", ".tm0", ".kgm", ".kgma", ".vpr", ".xm",
    )

    fun scan(): List<Song> {
        val roots = listOf(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "qqmusic/song",
            ),
            File(Environment.getExternalStorageDirectory(), "qqmusic/song"),
        )
        val out = mutableListOf<Song>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && isPlayable(it) }
                .forEach { out.add(toSong(it)) }
        }
        return out
    }

    private fun isPlayable(f: File): Boolean {
        val name = f.name.lowercase()
        val ext = name.substringAfterLast('.', "")
        if (ext !in playableExt) return false
        if (encryptedMarkers.any { name.contains(it) }) return false
        // Containers with a well-defined magic: require it, so encrypted copies
        // masquerading as ".flac" (e.g. ".mflac0.flac") get rejected too.
        return when (ext) {
            "flac" -> probe(f, "fLaC")
            "ogg", "oga" -> probe(f, "OggS")
            "ape" -> probe(f, "MAC ")
            "wv" -> probe(f, "wvpk")
            else -> true // mp3/m4a/aac/wav/wma/tak/tta/mp2 — allow by extension
        }
    }

    private fun probe(f: File, magic: String): Boolean = try {
        val expect = magic.toByteArray(Charsets.US_ASCII)
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(expect.size)
            val n = raf.read(buf)
            n == expect.size && buf.contentEquals(expect)
        }
    } catch (_: Exception) {
        false
    }

    private fun toSong(f: File): Song {
        val path = f.canonicalPath
        val (artist, title) = parseName(f.nameWithoutExtension)
        // Stable, collision-resistant id kept out of MediaStore's small positive range.
        val id = (path.hashCode().toLong() and 0xFFFFFFFFL) or 0x7000000000000000L
        return Song(
            id = id,
            title = title,
            artist = artist,
            album = "",
            albumId = 0,
            albumArtUri = null,
            durationMs = 0,          // unknown until libmpv loads it
            data = path,
            size = f.length(),
            dateAdded = f.lastModified() / 1000,
        )
    }

    /** QQ naming: "Artist - Title [quality]" (the brackets are stripped). */
    private fun parseName(rawName: String): Pair<String, String> {
        val unknown = "<unknown>"
        val cleaned = rawName.replace(Regex("\\[[^\\]]*]"), "").trim()
        val idx = cleaned.indexOf(" - ")
        return if (idx > 0) {
            val artist = cleaned.substring(0, idx).trim()
            val title = cleaned.substring(idx + 3).trim().ifBlank { unknown }
            artist.ifBlank { unknown } to title
        } else {
            unknown to cleaned.ifBlank { unknown }
        }
    }
}
