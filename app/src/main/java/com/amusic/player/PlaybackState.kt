package com.amusic.player

/**
 * Minimal track descriptor the player core understands. The data layer (Room `Song`)
 * maps to this so the playback core stays decoupled from the persistence model.
 */
data class Track(
    val uri: String,           // content:// or file:// path passed to mpv loadfile
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUri: String?,  // content://... album art, or null
    val durationMs: Long = 0,
    val songId: Long = 0,      // library id, used for favourites
)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val current: Track? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val playlist: List<Track> = emptyList(),
    val index: Int = -1,
)

/** How the sleep timer will stop playback. */
enum class SleepMode { OFF, TIMED, END_OF_TRACK }

data class SleepState(
    val mode: SleepMode = SleepMode.OFF,
    val remainingMs: Long = 0,
)
