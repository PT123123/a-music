package com.amusic.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the "now playing" artwork area is laid out. */
enum class PlayerStyle(val label: String, val hint: String) {
    DISC("唱片", "旋转黑胶，经典 QQ 音乐样式"),
    COVER("大封面", "方形封面配柔和阴影"),
    MINIMAL("极简", "歌词优先，小封面大字"),
}

/** Ordering of the 音乐馆 song list. */
enum class LibSort(val label: String) {
    RECENT("最近添加"),
    TITLE("歌曲名"),
    ARTIST("歌手"),
    ALBUM("专辑"),
    DURATION("时长"),
}

/**
 * Tiny SharedPreferences-backed store for user-visible app settings.
 * Values are exposed as [StateFlow]s so Compose can react to them directly.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _playerStyle = MutableStateFlow(
        runCatching { PlayerStyle.valueOf(prefs.getString(KEY_STYLE, null) ?: PlayerStyle.DISC.name) }
            .getOrDefault(PlayerStyle.DISC)
    )
    val playerStyle: StateFlow<PlayerStyle> = _playerStyle.asStateFlow()

    private val _accentId = MutableStateFlow(prefs.getString(KEY_ACCENT, null) ?: "QQ_GREEN")
    val accentId: StateFlow<String> = _accentId.asStateFlow()

    /** Last used sleep-timer length in minutes, so the dialog can pre-select it. */
    private val _sleepMinutes = MutableStateFlow(prefs.getInt(KEY_SLEEP_MIN, 30))
    val sleepMinutes: StateFlow<Int> = _sleepMinutes.asStateFlow()

    /** Most-recent-first search terms for the Discover tab (max [MAX_HISTORY]). */
    private val _searchHistory = MutableStateFlow(loadHistory())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /** Where downloaded online tracks are written. */
    private val _downloadDir = MutableStateFlow(prefs.getString(KEY_DL_DIR, null) ?: DEFAULT_DL_DIR)
    val downloadDir: StateFlow<String> = _downloadDir.asStateFlow()

    /** Which online source the 发现 tab last had selected ("QQ" / "NET24"). */
    private val _discoverSource = MutableStateFlow(prefs.getString(KEY_SOURCE, null) ?: SOURCE_QQ)
    val discoverSource: StateFlow<String> = _discoverSource.asStateFlow()

    /** How the 音乐馆 song list is ordered. */
    private val _libSort = MutableStateFlow(
        runCatching { LibSort.valueOf(prefs.getString(KEY_LIB_SORT, null) ?: LibSort.RECENT.name) }
            .getOrDefault(LibSort.RECENT)
    )
    val libSort: StateFlow<LibSort> = _libSort.asStateFlow()

    // ---- lyrics surface ----

    /** Show the currently-sung line in the playback notification (also covers the lock screen). */
    private val _lyricsInNotification = MutableStateFlow(prefs.getBoolean(KEY_LYRICS_NOTIF, true))
    val lyricsInNotification: StateFlow<Boolean> = _lyricsInNotification.asStateFlow()

    /** Show a floating, draggable lyric window over everything else. */
    private val _desktopLyrics = MutableStateFlow(prefs.getBoolean(KEY_DESKTOP_LYRICS, false))
    val desktopLyrics: StateFlow<Boolean> = _desktopLyrics.asStateFlow()

    fun setLyricsInNotification(on: Boolean) {
        prefs.edit().putBoolean(KEY_LYRICS_NOTIF, on).apply()
        _lyricsInNotification.value = on
    }

    fun setDesktopLyrics(on: Boolean) {
        prefs.edit().putBoolean(KEY_DESKTOP_LYRICS, on).apply()
        _desktopLyrics.value = on
    }

    // ---- equalizer ----

    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(KEY_EQ_ON, false))
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    /**
     * Per-band gain in dB, one entry per [com.amusic.player.Equalizer.BANDS]. Persisted as a
     * compact CSV because SharedPreferences has no float-list type.
     */
    private val _eqGains = MutableStateFlow(loadGains())
    val eqGains: StateFlow<List<Float>> = _eqGains.asStateFlow()

    private val _eqPresetId = MutableStateFlow(prefs.getString(KEY_EQ_PRESET, null) ?: "flat")
    val eqPresetId: StateFlow<String> = _eqPresetId.asStateFlow()

    fun setEqEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_EQ_ON, on).apply()
        _eqEnabled.value = on
    }

    fun setEqGains(gains: List<Float>) {
        _eqGains.value = gains
        prefs.edit().putString(KEY_EQ_GAINS, gains.joinToString(",") { "%.1f".format(it) }).apply()
    }

    fun setEqPresetId(id: String) {
        prefs.edit().putString(KEY_EQ_PRESET, id).apply()
        _eqPresetId.value = id
    }

    private fun loadGains(): List<Float> {
        val raw = prefs.getString(KEY_EQ_GAINS, null) ?: return emptyEqGains()
        val parsed = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parsed.size == EQ_BANDS) parsed else emptyEqGains()
    }

    private fun emptyEqGains(): List<Float> = List(EQ_BANDS) { 0f }

    // ---- setters ----

    fun setLibSort(sort: LibSort) {
        prefs.edit().putString(KEY_LIB_SORT, sort.name).apply()
        _libSort.value = sort
    }

    fun setPlayerStyle(style: PlayerStyle) {
        prefs.edit().putString(KEY_STYLE, style.name).apply()
        _playerStyle.value = style
    }

    fun setAccentId(id: String) {
        prefs.edit().putString(KEY_ACCENT, id).apply()
        _accentId.value = id
    }

    fun setSleepMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SLEEP_MIN, minutes).apply()
        _sleepMinutes.value = minutes
    }

    fun setDownloadDir(dir: String) {
        prefs.edit().putString(KEY_DL_DIR, dir).apply()
        _downloadDir.value = dir
    }

    fun setDiscoverSource(source: String) {
        prefs.edit().putString(KEY_SOURCE, source).apply()
        _discoverSource.value = source
    }

    // ---- search history ----

    fun addSearchHistory(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val next = (listOf(w) + _searchHistory.value.filter { !it.equals(w, ignoreCase = true) })
            .take(MAX_HISTORY)
        _searchHistory.value = next
        prefs.edit().putString(KEY_SEARCH_HISTORY, next.joinToString(SEP)).apply()
    }

    fun removeSearchHistory(word: String) {
        val next = _searchHistory.value.filter { it != word }
        _searchHistory.value = next
        prefs.edit().putString(KEY_SEARCH_HISTORY, next.joinToString(SEP)).apply()
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    private fun loadHistory(): List<String> =
        prefs.getString(KEY_SEARCH_HISTORY, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    companion object {
        const val DEFAULT_DL_DIR = "Music/amusic"
        const val SOURCE_QQ = "QQ"
        const val SOURCE_NET24 = "NET24"

        /** Band count; kept next to the prefs code so the CSV length check stays honest. */
        const val EQ_BANDS = 8

        private const val PREFS = "amusic_settings"
        private const val KEY_STYLE = "player_style"
        private const val KEY_ACCENT = "accent_id"
        private const val KEY_SLEEP_MIN = "sleep_minutes"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_DL_DIR = "download_dir"
        private const val KEY_SOURCE = "discover_source"
        private const val KEY_LIB_SORT = "lib_sort"
        private const val KEY_LYRICS_NOTIF = "lyrics_in_notification"
        private const val KEY_DESKTOP_LYRICS = "desktop_lyrics"
        private const val KEY_EQ_ON = "eq_enabled"
        private const val KEY_EQ_GAINS = "eq_gains"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val MAX_HISTORY = 12

        /** Unit separator: cannot occur inside a search term, so joining is lossless. */
        private const val SEP = "\u0001"
    }
}
