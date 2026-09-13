package com.amusic.data.lyrics

/**
 * One timed lyric line. [translation] carries the optional translated line (QQ Music's `trans`).
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
)
