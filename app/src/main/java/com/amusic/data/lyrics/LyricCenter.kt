package com.amusic.data.lyrics

import com.amusic.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide "what is being sung right now" hub.
 *
 * Lyrics used to be loaded by [com.amusic.ui.player.NowPlayingScreen] alone, which meant they
 * only existed while that screen was open. The notification / lock-screen line and the floating
 * desktop window need them at all times, so loading moved here: whoever is playing drives
 * [follow], and every surface reads the same flows.
 */
class LyricCenter(
    private val repo: LyricsRepository,
    private val scope: CoroutineScope,
) {
    private val _lines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lines: StateFlow<List<LyricLine>> = _lines.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Index of the line currently being sung, or -1 before the first one. */
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _current = MutableStateFlow<LyricLine?>(null)
    val current: StateFlow<LyricLine?> = _current.asStateFlow()

    private var loadedKey: String? = null

    /** Load lyrics for [track] if it differs from the one we already have. */
    fun follow(track: Track?) {
        val key = track?.let { "${it.title}\u0000${it.artist}\u0000${pathOf(it.uri)}" }
        if (key == loadedKey) return
        loadedKey = key

        _lines.value = emptyList()
        _index.value = -1
        _current.value = null

        if (track == null) {
            _loading.value = false
            return
        }

        _loading.value = true
        scope.launch {
            val ls = runCatching { repo.getLyrics(track.title, track.artist, pathOf(track.uri)) }
                .getOrDefault(emptyList())
            // A fast skip can land the next song's answer first; drop stale results.
            if (loadedKey != key) return@launch
            _lines.value = ls
            _loading.value = false
        }
    }

    /** Advance the highlight to [positionMs]. Called on every playback tick. */
    fun tick(positionMs: Long) {
        val ls = _lines.value
        val i = if (ls.isEmpty()) -1 else lastLineAt(ls, positionMs)
        if (i == _index.value) return
        _index.value = i
        _current.value = if (i >= 0) ls[i] else null
    }

    private fun lastLineAt(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i else break
        }
        return idx
    }

    private fun pathOf(uri: String): String? =
        if (uri.startsWith("file://")) uri.removePrefix("file://") else null
}
