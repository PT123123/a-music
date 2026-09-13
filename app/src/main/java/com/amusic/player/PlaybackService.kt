package com.amusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amusic.MainApplication
import com.amusic.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground playback service (foregroundServiceType = mediaPlayback).
 *
 * Owns the [MediaSessionCompat] and the playback notification, and bridges transport
 * intents / media buttons to [PlayerController]. The actual audio is produced by libmpv
 * inside [PlayerController].
 *
 * It is also the host for the two "lyrics everywhere" surfaces, because both need an owner
 * that outlives the UI: the notification line (which doubles as the lock-screen line, since
 * the notification is `VISIBILITY_PUBLIC`) and the floating desktop window.
 */
class PlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var app: MainApplication

    /** One scope for every bridge below; cancelled wholesale in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Last state we rendered, so a lyric change can refresh the notification on its own. */
    private var lastState = PlaybackState()

    override fun onCreate() {
        super.onCreate()
        PlayerController.init(this) // safe no-op if already initialized
        app = application as MainApplication
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()

        mediaSession = MediaSessionCompat(this, "AMusic").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }

        PlayerController.state
            .onEach { state ->
                lastState = state
                syncToMediaSession(state)
                updateNotification(state)
            }
            .launchIn(scope)

        // ---- 状态栏 / 锁屏歌词: repaint the notification whenever the sung line changes
        app.lyricCenter.current
            .onEach { refreshNotificationForLyric() }
            .launchIn(scope)

        // ---- 桌面歌词: follow the setting, then feed the window the current line
        app.settings.desktopLyrics
            .onEach { on ->
                if (on) {
                    DesktopLyrics.show(this)
                    if (!DesktopLyrics.isShowing) {
                        // Overlay permission missing / window refused. The settings screen is
                        // where the user is sent to grant it, so just leave a breadcrumb here.
                        Log.w(TAG, "desktop lyrics requested but overlay unavailable")
                    }
                } else {
                    DesktopLyrics.hide()
                }
            }
            .launchIn(scope)

        app.lyricCenter.current
            .onEach { line ->
                if (DesktopLyrics.isShowing) DesktopLyrics.update(line?.text, line?.translation)
            }
            .launchIn(scope)
    }

    /** Re-render the notification in place after a lyric change. */
    private fun refreshNotificationForLyric() {
        if (lastState.current == null) return
        runCatching { notificationManager.notify(NOTIF_ID, buildNotification(lastState)) }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = PlayerController.resume()
        override fun onPause() = PlayerController.pause()
        fun onToggle() = PlayerController.togglePlay()
        override fun onSkipToNext() = PlayerController.next()
        override fun onSkipToPrevious() = PlayerController.prev()
        override fun onSeekTo(pos: Long) = PlayerController.seekTo(pos)
        override fun onStop() {
            PlayerController.pause()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerController.resume()
            ACTION_PAUSE -> PlayerController.pause()
            ACTION_TOGGLE -> PlayerController.togglePlay()
            ACTION_NEXT -> PlayerController.next()
            ACTION_PREV -> PlayerController.prev()
        }
        return START_STICKY
    }

    private fun syncToMediaSession(state: PlaybackState) {
        val track = state.current
        val metadata = MediaMetadataCompat
            .Builder()
            .apply {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "")
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist ?: "")
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album ?: "")
                if (track?.albumArtUri != null)
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track.albumArtUri)
                putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
            }
            .build()
        mediaSession.setMetadata(metadata)

        val pbState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                state.positionMs,
                1f
            )
            .build()
        mediaSession.setPlaybackState(pbState)
    }

    private fun updateNotification(state: PlaybackState) {
        val track = state.current
        if (track == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val notification = buildNotification(state)
        if (state.isPlaying) {
            startForeground(NOTIF_ID, notification)
        } else {
            notificationManager.notify(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(state: PlaybackState): Notification {
        val track = state.current!!
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // The sung line takes the prominent second row so it also shows on the lock screen
        // (the notification is VISIBILITY_PUBLIC); the artist drops to the sub-text.
        val lyric = if (app.settings.lyricsInNotification.value) {
            app.lyricCenter.current.value?.text?.takeIf { it.isNotBlank() }
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(contentIntent)
            .setContentTitle(track.title)
            .setContentText(lyric ?: track.artist)
            .apply { if (lyric != null) setSubText(track.artist) }
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(R.drawable.ic_skip_prev, "上一首", actionIntent(ACTION_PREV))
            .addAction(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                if (state.isPlaying) "暂停" else "播放",
                actionIntent(ACTION_TOGGLE)
            )
            .addAction(R.drawable.ic_skip_next, "下一首", actionIntent(ACTION_NEXT))
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        DesktopLyrics.hide()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "amusic_playback"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "com.amusic.player.PLAY"
        const val ACTION_PAUSE = "com.amusic.player.PAUSE"
        const val ACTION_TOGGLE = "com.amusic.player.TOGGLE"
        const val ACTION_NEXT = "com.amusic.player.NEXT"
        const val ACTION_PREV = "com.amusic.player.PREV"
    }
}
