package com.amusic.data.online

/**
 * A song that lives on the internet (currently: QQ Music's public search API),
 * as opposed to [com.amusic.data.model.Song] which is a file on this device.
 *
 * Only the fields we actually render / use for downloading are kept.
 */
data class OnlineSong(
    val mid: String,          // QQ songmid — the stable key used to resolve a play URL
    val title: String,
    val artist: String,
    val album: String,
    val albumMid: String,
    val durationSec: Int,
    val sizeBytes: Long,      // advertised 128k file size; 0 when unknown
    val vipOnly: Boolean,     // pay.play == 1 -> needs a VIP account
) {
    /** QQ serves album art from a predictable URL keyed by albummid. */
    val coverUrl: String
        get() = if (albumMid.isBlank()) "" else "https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg"

    val durationText: String
        get() {
            if (durationSec <= 0) return "--:--"
            val m = durationSec / 60
            val s = durationSec % 60
            return "%d:%02d".format(m, s)
        }

    val sizeText: String
        get() = when {
            sizeBytes <= 0 -> ""
            sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
            else -> "%d KB".format(sizeBytes / 1024)
        }

    /** `歌手 - 歌名` shaped label used for one-off playback and tag copying. */
    val displayName: String
        get() = if (artist.isBlank()) title else "$artist - $title"
}
