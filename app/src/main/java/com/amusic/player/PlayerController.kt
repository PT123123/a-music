package com.amusic.player

import android.content.Context
import android.content.Intent
import android.util.Log
import com.amusic.data.prefs.PlaybackSnapshotStore
import kotlin.math.abs
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var bootstrapped = false

    private lateinit var snapshots: PlaybackSnapshotStore

    /**
     * What the last successful write contained — not the live state. Keeping "last written"
     * rather than "current" is what makes the position throttle in [mirrorStateToDisk] work.
     */
    private var savedQueue: List<Track>? = null
    private var savedIndex = -1
    private var savedUri: String? = null
    private var savedPositionMs = 0L
    private var savedPlaying = false

    private val diskLock = Mutex()

    /**
     * True while [queue] came off disk and mpv has not opened the file yet. The restored
     * session is visible everywhere (mini player, queue screen, lyrics) but stays silent —
     * and keeps the foreground service off — until the user actually asks for playback.
     */
    private var pendingRestore = false

    /**
     * Resume point handed to the `time-pos` observer. mpv only reports a position once the
     * file is really open, so a seek issued right after `loadfile` would be dropped; parking
     * the target here makes the seek land on the first report instead.
     */
    private var pendingSeekMs = 0L

    // ---- sleep timer ----
    private val _sleep = MutableStateFlow(SleepState())
    val sleep: StateFlow<SleepState> = _sleep.asStateFlow()
    private var sleepJob: Job? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext

        // Restore + persistence are plain Kotlin state, so they are wired up before the
        // native library is touched: even if libmpv refuses to load, the previous queue is
        // still restored and the failure is reported by the catch below.
        //
        // The queue is a few hundred KB of JSON, so it is read off the main thread — the UI
        // starts empty for a frame and fills in as soon as the snapshot lands. Mirroring only
        // starts once the restore is in: subscribing before that would report the empty state
        // as a change and wipe the snapshot it is about to read.
        if (!bootstrapped) {
            bootstrapped = true
            snapshots = PlaybackSnapshotStore(appContext)
            scope.launch(Dispatchers.IO) {
                restoreLastSession()
                mirrorStateToDisk()
            }
        }

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
        // An explicit load always supersedes a restored-but-never-played session.
        pendingRestore = false
        pendingSeekMs = 0
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

    fun togglePlay() {
        val restored = takeRestoreTarget()
        if (restored != null) {
            openRestored(restored)
            return
        }
        MPVLib.command(arrayOf("cycle", "pause"))
    }

    fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        val restored = takeRestoreTarget()
        if (restored != null) {
            openRestored(restored)
            return
        }
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
        if (_state.value.positionMs > RESTART_THRESHOLD_MS) {
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

    // ---- Queue management ----

    /** Add a track to the end of the queue. */
    fun addToQueue(track: Track) = addToQueue(listOf(track))

    /**
     * Add multiple tracks to the end of the queue.
     *
     * With nothing queued yet (fresh install, or the user just cleared the list) appending
     * would leave a list whose cursor is -1: no current track, nothing in mpv, no mini
     * player — i.e. the button looks broken. Start playing instead, the way QQ Music does.
     */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (queue.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        queue = queue + tracks
        _state.update { it.copy(playlist = queue) }
    }

    /** Remove a track from the queue at [index]. */
    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) return
        
        val wasPlaying = index == queueIndex
        
        queue = queue.toMutableList().apply { removeAt(index) }
        
        if (wasPlaying) {
            // Current track removed - load the track at same index (or previous if at end)
            queueIndex = index.coerceIn(0, queue.lastIndex)
            _state.update { it.copy(playlist = queue, index = queueIndex) }
            if (queue.isEmpty()) {
                pendingRestore = false
                pendingSeekMs = 0
                _state.update { it.copy(current = null, isPlaying = false) }
            } else if (pendingRestore) {
                // Restored session that was never played: nothing is loaded in mpv, so just
                // move the cursor onto the replacement track instead of starting playback.
                val next = queue[queueIndex]
                pendingSeekMs = 0
                _state.update {
                    it.copy(current = next, positionMs = 0, durationMs = next.durationMs)
                }
            } else {
                loadCurrent(replace = true)
            }
        } else if (index < queueIndex) {
            // Removed a track before current - adjust index
            queueIndex--
            _state.update { it.copy(playlist = queue, index = queueIndex) }
        } else {
            _state.update { it.copy(playlist = queue) }
        }
    }

    /** Clear the queue (keeps current track playing). */
    fun clearQueue() {
        val current = queue.getOrNull(queueIndex)
        queue = if (current != null) listOf(current) else emptyList()
        queueIndex = if (current != null) 0 else -1
        _state.update { it.copy(playlist = queue, index = queueIndex) }
    }

    /** Move a track from [fromIndex] to [toIndex] in the queue. */
    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || fromIndex >= queue.size) return
        if (toIndex < 0 || toIndex >= queue.size) return
        
        val mutableQueue = queue.toMutableList()
        val track = mutableQueue.removeAt(fromIndex)
        mutableQueue.add(toIndex, track)
        queue = mutableQueue
        
        // Update current index if needed
        queueIndex = when {
            fromIndex == queueIndex -> toIndex
            fromIndex < queueIndex && toIndex >= queueIndex -> queueIndex - 1
            fromIndex > queueIndex && toIndex <= queueIndex -> queueIndex + 1
            else -> queueIndex
        }
        
        _state.update { it.copy(playlist = queue, index = queueIndex) }
    }

    /** Get current queue as a list of tracks. */
    fun getQueue(): List<Track> = queue

    /** Get current track index in queue. */
    fun getCurrentIndex(): Int = queueIndex

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
        if (property != "time-pos") return
        val target = pendingSeekMs
        if (target > 0) {
            // First report after a restored load: mpv holds an open file now, so the resume
            // point can finally be applied. Seeking before this point gets silently dropped.
            pendingSeekMs = 0
            seekTo(clampSeek(target))
            return
        }
        _state.update { it.copy(positionMs = value * 1000) }
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

    // ---- session persistence ----

    private data class Restored(val track: Track, val positionMs: Long)

    /**
     * Put the last session back on screen: its queue, which track was current and how far into
     * it the user got. Deliberately does not open the file or start the foreground service —
     * the app comes up silent, exactly as it was left, and the first press of play picks the
     * song up at the saved position.
     */
    private fun restoreLastSession() {
        // Should the app have started playing something while this was reading, that wins.
        if (queue.isNotEmpty() || _state.value.current != null) return

        val tracks = snapshots.loadQueue()
        if (tracks.isEmpty()) {
            snapshots.clearCursor()
            return
        }
        val cursor = snapshots.loadCursor()
        val index = resolveIndex(tracks, cursor)
        val track = tracks[index]
        val position = cursor?.positionMs?.coerceAtLeast(0L) ?: 0L

        queue = tracks
        queueIndex = index
        pendingRestore = true
        pendingSeekMs = 0

        // Mark the snapshot as already on disk first, so the mirror below does not
        // immediately write back what it just read.
        savedQueue = tracks
        savedIndex = index
        savedUri = track.uri
        savedPositionMs = position
        savedPlaying = false

        _state.update {
            it.copy(
                isPlaying = false,
                current = track,
                positionMs = position,
                durationMs = track.durationMs,
                playlist = tracks,
                index = index,
            )
        }
        Log.i(TAG, "restored session: ${tracks.size} tracks, #$index, ${position / 1000}s in")
    }

    /**
     * The saved index is only trusted when the track it pointed at is still identifiable by
     * URI, so a queue that changed on disk falls back to its first track instead of landing on
     * whatever happens to sit at that offset.
     */
    private fun resolveIndex(tracks: List<Track>, cursor: PlaybackSnapshotStore.Cursor?): Int {
        if (cursor == null) return 0
        val byUri = tracks.indexOfFirst { it.uri == cursor.uri }
        return when {
            byUri >= 0 -> byUri
            cursor.index in tracks.indices -> cursor.index
            else -> 0
        }
    }

    /**
     * Mirror live state to disk as it changes. Hooked onto the state flow rather than onto
     * every mutator, so transitions nobody calls directly — auto-advance from [eventEndFile],
     * running off the end of the queue — are covered too.
     *
     * The position ticks about once a second while playing, so writes are throttled: the queue
     * only when the list changed, the cursor on anything that stops the clock (track change,
     * play/pause) and otherwise every [POSITION_SAVE_MS] of playback.
     */
    private fun mirrorStateToDisk() {
        scope.launch {
            _state.collect { st ->
                if (savedQueue != st.playlist) {
                    savedQueue = st.playlist
                    writeToDisk { snapshots.saveQueue(st.playlist) }
                }

                val uri = st.current?.uri
                if (uri == null) {
                    if (savedUri != null) {
                        savedUri = null
                        writeToDisk { snapshots.clearCursor() }
                    }
                    return@collect
                }

                val due = uri != savedUri ||
                    st.index != savedIndex ||
                    st.isPlaying != savedPlaying ||
                    abs(st.positionMs - savedPositionMs) >= POSITION_SAVE_MS
                if (!due) return@collect

                savedIndex = st.index
                savedUri = uri
                savedPlaying = st.isPlaying
                savedPositionMs = st.positionMs
                writeToDisk { snapshots.saveCursor(st.index, st.positionMs, uri) }
            }
        }
    }

    /** Disk writes are serialised: the state flow can schedule two of them in one frame. */
    private fun writeToDisk(block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            diskLock.withLock {
                runCatching(block)
                    .onFailure { Log.w(TAG, "failed to persist playback state", it) }
            }
        }
    }

    /** Consumes the restored-session marker; null when mpv already holds the current track. */
    private fun takeRestoreTarget(): Restored? {
        if (!pendingRestore) return null
        pendingRestore = false
        val track = queue.getOrNull(queueIndex) ?: return null
        return Restored(track, _state.value.positionMs.coerceAtLeast(0L))
    }

    /**
     * Open the file behind a restored session and hand the resume point to the observer
     * (see [pendingSeekMs]). A resume point of only a few seconds is dropped — restarting the
     * song beats starting one second in.
     */
    private fun openRestored(restored: Restored) {
        try {
            MPVLib.command(arrayOf("loadfile", restored.track.uri, "replace"))
        } catch (e: Throwable) {
            Log.w(TAG, "restore loadfile failed for ${restored.track.uri.take(120)}", e)
            return
        }
        val resumeAt = if (restored.positionMs > RESTART_THRESHOLD_MS) restored.positionMs else 0L
        pendingSeekMs = resumeAt
        _state.update {
            it.copy(
                current = restored.track,
                isPlaying = true,
                positionMs = resumeAt,
                durationMs = restored.track.durationMs,
            )
        }
        ensureServiceRunning()
    }

    /** Keeps a resume point away from the end, where the seek would simply finish the track. */
    private fun clampSeek(ms: Long): Long {
        val duration = _state.value.durationMs
        if (duration <= 0) return ms
        return ms.coerceIn(0, (duration - END_GUARD_MS).coerceAtLeast(0))
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

    /** From here on, "restart this track" reads better than "resume where you were". */
    private const val RESTART_THRESHOLD_MS = 3000L

    /** Most playback that can be lost if the process dies between two snapshot writes. */
    private const val POSITION_SAVE_MS = 4000L

    /** Keeps a resume seek out of the closing seconds, where it would just end the track. */
    private const val END_GUARD_MS = 2000L
}
