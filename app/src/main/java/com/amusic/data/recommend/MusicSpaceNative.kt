package com.amusic.data.recommend

/**
 * JNI surface of libmusicspace.so (native/musicspace) — the song->song scoring core
 * vendored from PT123123/music-recommend. All state lives natively; the app only
 * opens a payload directory and asks for rankings.
 *
 * [available] is false on builds made without the Rust toolchain: every call site
 * must check it first, because the external functions would throw
 * UnsatisfiedLinkError.
 */
object MusicSpaceNative {
    val available: Boolean = try {
        System.loadLibrary("musicspace")
        true
    } catch (_: Throwable) {
        false
    }

    /** Load manifest.kv + tracks.tsv from [dir]; returns track count, -1 on failure. */
    external fun open(dir: String): Int

    /**
     * song -> song similar. JSON string
     * `[{"id":"..","score":0.68,"groups":{"timbre":0.82}},..]`, null when [trackId]
     * is not in the payload, "[]" when it has no candidates.
     */
    external fun similar(trackId: String, limit: Int): String?

    /** Drop the loaded payload (replaced import). */
    external fun close()
}
