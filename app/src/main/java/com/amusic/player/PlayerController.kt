package com.amusic.player

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Singleton playback core. Wraps libmpv (via [MPVLib]) and exposes a [StateFlow] of
 * [PlaybackState] for the UI. Also implements [MPVLib.EventObserver] to translate mpv
 * property changes / events into state updates.
 *
 * Call [init] once from [com.amusic.MainApplication] with the application context.
 */
object PlayerController : MPVLib.EventObserver {

    private const val TAG = "PlayerController"

    private lateinit var appContext: Context
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var queue: List<Track> = emptyList()
    private var queueIndex = -1
    private var initialized = false

    // ---- sleep timer ----
    private val _sleep = MutableStateFlow(SleepState())
    val sleep: StateFlow<SleepState> = _sleep.asStateFlow()
    private var sleepJob: Job? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        try {
            MPVLib.create(appContext)
            // --- audio-only tuning (no video pipeline at all) ---
            MPVLib.setOptionString("profile", "fast")
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("vid", "no")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            MPVLib.setOptionString("audio-client-name", "AMusic")
            MPVLib.setOptionString("gapless-audio", "yes")
            MPVLib.setOptionString("save-position-on-quit", "no")
            MPVLib.setOptionString("force-window", "no")
            // mobile-friendly demuxer cache
            MPVLib.setOptionString("demuxer-max-bytes", "67108864")      // 64 MB
            MPVLib.setOptionString("demuxer-max-back-bytes", "67108864")
            MPVLib.init()

            // observe only what the UI needs
            MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)

            MPVLib.addObserver(this)
            initialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize libmpv (did you build libmpv.so + libplayer.so?)", t)
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        queue = tracks
        queueIndex = startIndex.coerceIn(0, tracks.lastIndex)
        _state.update { it.copy(playlist = tracks, index = queueIndex) }
        loadCurrent(replace = true)
    }

    /** Play a single track (e.g. from search) as a one-item queue. */
    fun playTrack(track: Track) = playQueue(listOf(track), 0)

    fun playFromUri(uri: String, title: String = "", artist: String = "", album: String = "") {
        playTrack(Track(uri = uri, title = title, artist = artist, album = album, albumArtUri = null))
    }

    private fun loadCurrent(replace: Boolean = true) {
        val t = queue.getOrNull(queueIndex) ?: return
        try {
            MPVLib.command(arrayOf("loadfile", t.uri, if (replace) "replace" else "append"))
        } catch (e: Throwable) {
            // This runs from UI coroutines, where an uncaught throwable takes the process
            // down. A malformed/expired online URL must fail quietly instead.
            Log.w(TAG, "loadfile failed for ${t.uri.take(120)}", e)
        }
        // Reset the duration too, otherwise the seek bar keeps showing the previous
        // track's length until mpv reports the new one.
        _state.update { it.copy(current = t, isPlaying = true, positionMs = 0, durationMs = t.durationMs) }
        ensureServiceRunning()
    }

    fun togglePlay() = MPVLib.command(arrayOf("cycle", "pause"))

    fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        MPVLib.setPropertyBoolean("pause", false)
        _state.update { it.copy(isPlaying = true) }
        ensureServiceRunning()
    }

    fun next() {
        if (queue.isEmpty()) return
        if (queueIndex < queue.lastIndex) {
            queueIndex++
            _state.update { it.copy(index = queueIndex) }
            loadCurrent(replace = true)
        } else {
            // already at the end -> restart last track
            loadCurrent(replace = true)
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        // QQ Music behavior: if >3s in, restart current; else go previous.
        if (_state.value.positionMs > 3000) {
            seekTo(0)
            return
        }
        if (queueIndex > 0) {
            queueIndex--
            _state.update { it.copy(index = queueIndex) }
            loadCurrent(replace = true)
        } else {
            seekTo(0)
        }
    }

    fun seekTo(ms: Long) {
        MPVLib.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
        _state.update { it.copy(positionMs = ms.coerceAtLeast(0)) }
    }

    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 100)
        MPVLib.setPropertyDouble("volume", vol.toDouble())
        _state.update { it.copy(volume = vol) }
    }

    /**
     * Replace the audio filter chain (the equalizer). An empty string clears it.
     *
     * A malformed chain is a hard failure inside mpv, so this is the one place allowed to
     * swallow it: worst case the user hears un-equalised audio instead of losing playback.
     */
    fun applyAudioFilter(af: String) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyString("af", af) }
            .onFailure { Log.w(TAG, "mpv rejected audio filter '$af'", it) }
    }

    // ---- sleep timer ----

    /** Pause playback after [minutes] minutes. Replaces any running timer. */
    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val totalMs = minutes.coerceAtLeast(1) * 60_000L
        sleepJob = scope.launch {
            var left = totalMs
            _sleep.value = SleepState(SleepMode.TIMED, left)
            while (left > 0) {
                delay(TICK_MS)
                left -= TICK_MS
                _sleep.value = SleepState(SleepMode.TIMED, left.coerceAtLeast(0))
            }
            Log.i(TAG, "sleep timer fired after $minutes min -> pausing")
            pause()
            _sleep.value = SleepState()
        }
    }

    /** Stop (pause) once the current track reaches its natural end. */
    fun sleepAtEndOfTrack() {
        sleepJob?.cancel()
        sleepJob = null
        _sleep.value = SleepState(SleepMode.END_OF_TRACK, 0)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleep.value = SleepState()
    }

    // ---- MPVLib.EventObserver ----

    override fun eventProperty(property: String, value: Long) {
        if (property == "time-pos") {
            _state.update { it.copy(positionMs = value * 1000) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            _state.update { it.copy(isPlaying = !value) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
            "volume" -> _state.update { it.copy(volume = value.toInt().coerceIn(0, 100)) }
        }
    }

    override fun eventEndFile(reason: Int) {
        // Only auto-advance on natural end-of-file (reason == EOF). Manual loadfile
        // produces STOP/REDIRECT which we must ignore, otherwise we'd skip twice.
        if (reason != MPVLib.MpvEndFileReason.EOF) return

        // Sleep timer set to "after this song": stop here instead of advancing.
        if (_sleep.value.mode == SleepMode.END_OF_TRACK) {
            Log.i(TAG, "sleep timer (end of track) -> stopping")
            _sleep.value = SleepState()
            pause()
            _state.update { it.copy(positionMs = _state.value.durationMs) }
            return
        }

        val st = _state.value
        if (st.index in 0 until queue.lastIndex) {
            queueIndex = st.index + 1
            _state.update { it.copy(index = queueIndex) }
            loadCurrent(replace = true)
        } else {
            // end of queue
            _state.update { it.copy(isPlaying = false) }
        }
    }

    private fun ensureServiceRunning() {
        runCatching {
            val intent = Intent(appContext, PlaybackService::class.java)
            appContext.startForegroundService(intent)
        }
    }

    fun release() {
        MPVLib.removeObserver(this)
        MPVLib.destroy()
        initialized = false
    }

    private const val TICK_MS = 500L
}
