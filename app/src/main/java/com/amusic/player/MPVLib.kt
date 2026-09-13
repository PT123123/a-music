package com.amusic.player

import android.content.Context

/**
 * Thin JNI bridge to libmpv (audio-only build).
 *
 * The C side lives in app/src/main/jni (compiled to libplayer.so) and links the
 * prebuilt libmpv.so. Every `external fun` here MUST match a `JNIEXPORT ... JNICALL
 * Java_com_amusic_player_MPVLib_*` in the native sources — if you rename a function,
 * update the C JNINativeMethod table too.
 *
 * Note: video surface / thumbnail methods from the upstream mpv-android wrapper are
 * intentionally omitted because this is an audio player.
 */
@Suppress("unused")
object MPVLib {
    init {
        // libmpv first, then our wrapper that registers the native methods.
        System.loadLibrary("mpv")
        System.loadLibrary("player")
    }

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()

    external fun command(cmd: Array<out String>)

    external fun setOptionString(name: String, value: String): Int

    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)

    external fun observeProperty(property: String, format: Int)

    // ---- Observers (Kotlin-side fan-out from the native event thread) ----

    private val observers = mutableListOf<EventObserver>()

    @JvmStatic
    fun addObserver(o: EventObserver) {
        synchronized(observers) { observers.add(o) }
    }

    @JvmStatic
    fun removeObserver(o: EventObserver) {
        synchronized(observers) { observers.remove(o) }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        synchronized(observers) { for (o in observers) o.eventProperty(property) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }

    @JvmStatic
    fun event(eventId: Int) {
        synchronized(observers) { for (o in observers) o.event(eventId) }
    }

    /** Forwarded from the native END_FILE event, carrying mpv's end reason. */
    @JvmStatic
    fun eventEndFile(reason: Int) {
        synchronized(observers) { for (o in observers) o.eventEndFile(reason) }
    }

    private val logObservers = mutableListOf<LogObserver>()

    @JvmStatic
    fun addLogObserver(o: LogObserver) {
        synchronized(logObservers) { logObservers.add(o) }
    }

    @JvmStatic
    fun removeLogObserver(o: LogObserver) {
        synchronized(logObservers) { logObservers.remove(o) }
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(logObservers) { for (o in logObservers) o.logMessage(prefix, level, text) }
    }

    interface EventObserver {
        fun eventProperty(property: String) {}
        fun eventProperty(property: String, value: Long) {}
        fun eventProperty(property: String, value: Boolean) {}
        fun eventProperty(property: String, value: Double) {}
        fun eventProperty(property: String, value: String) {}
        fun event(eventId: Int) {}
        fun eventEndFile(reason: Int) {}
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE: Int = 0
        const val MPV_FORMAT_STRING: Int = 1
        const val MPV_FORMAT_OSD_STRING: Int = 2
        const val MPV_FORMAT_FLAG: Int = 3
        const val MPV_FORMAT_INT64: Int = 4
        const val MPV_FORMAT_DOUBLE: Int = 5
        const val MPV_FORMAT_NODE: Int = 6
        const val MPV_FORMAT_NODE_ARRAY: Int = 7
        const val MPV_FORMAT_NODE_MAP: Int = 8
        const val MPV_FORMAT_BYTE_ARRAY: Int = 9
    }

    object MpvEvent {
        const val MPV_EVENT_NONE: Int = 0
        const val MPV_EVENT_SHUTDOWN: Int = 1
        const val MPV_EVENT_LOG_MESSAGE: Int = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY: Int = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY: Int = 4
        const val MPV_EVENT_COMMAND_REPLY: Int = 5
        const val MPV_EVENT_START_FILE: Int = 6
        const val MPV_EVENT_END_FILE: Int = 7
        const val MPV_EVENT_FILE_LOADED: Int = 8
        const val MPV_EVENT_AUDIO_RECONFIG: Int = 18
        const val MPV_EVENT_SEEK: Int = 20
        const val MPV_EVENT_PLAYBACK_RESTART: Int = 21
        const val MPV_EVENT_PROPERTY_CHANGE: Int = 22
    }

    /** mpv_end_file_reason values. */
    object MpvEndFileReason {
        const val EOF: Int = 0
        const val ERROR: Int = 1
        const val STOP: Int = 2
        const val QUIT: Int = 3
        const val REDIRECT: Int = 4
    }

    object MpvLogLevel {
        const val MPV_LOG_LEVEL_NONE: Int = 0
        const val MPV_LOG_LEVEL_FATAL: Int = 10
        const val MPV_LOG_LEVEL_ERROR: Int = 20
        const val MPV_LOG_LEVEL_WARN: Int = 30
        const val MPV_LOG_LEVEL_INFO: Int = 40
        const val MPV_LOG_LEVEL_V: Int = 50
        const val MPV_LOG_LEVEL_DEBUG: Int = 60
        const val MPV_LOG_LEVEL_TRACE: Int = 70
    }
}
