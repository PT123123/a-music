package com.amusic.player

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amusic.MainApplication
import com.amusic.R
import com.amusic.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Manages Live Activity (灵动岛/动态岛) for playback on HyperOS/ColorOS/etc.
 * Falls back gracefully on devices without Live Activity support.
 */
object LiveActivityManager {
    private const val TAG = "LiveActivityManager"
    private const val LIVE_ACTIVITY_ID = "amusic_live_activity"
    
    private var isSupported = false
    private var service: LiveActivityService? = null
    
    fun init(context: Context) {
        // Check if Live Activity is supported (Android 12+ / API 31+)
        isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        
        if (isSupported) {
            Log.i(TAG, "Live Activity supported on this device")
        } else {
            Log.i(TAG, "Live Activity not supported (API < 31)")
        }
    }
    
    fun start(context: Context, state: PlaybackState) {
        if (!isSupported) return
        
        val track = state.current ?: return
        
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Build a custom notification that can be displayed as a Live Activity
        // Note: Full Live Activity requires a separate widget extension module
        // This implementation uses an ongoing notification as a fallback/alternative
        
        val lyric = (context.applicationContext as? MainApplication)
            ?.lyricCenter?.current?.value?.text?.takeIf { it.isNotBlank() }
        
        val notification = NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(lyric ?: track.artist)
            .apply { if (lyric != null) setSubText(track.artist) }
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCustomBigContentView(buildExpandedView(context, state))
            .setCustomContentView(buildCompactView(context, state))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(LIVE_NOTIF_ID, notification)
    }
    
    fun update(context: Context, state: PlaybackState) {
        if (!isSupported) return
        start(context, state)
    }
    
    fun stop(context: Context) {
        if (!isSupported) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(LIVE_NOTIF_ID)
    }
    
    private fun buildCompactView(context: Context, state: PlaybackState): android.widget.RemoteViews {
        val track = state.current ?: return android.widget.RemoteViews(context.packageName, R.layout.live_activity_compact)
        
        return android.widget.RemoteViews(context.packageName, R.layout.live_activity_compact).apply {
            setTextViewText(R.id.live_title, track.title)
            setTextViewText(R.id.live_artist, track.artist)
            setImageViewResource(R.id.live_icon, R.drawable.ic_notification)
            
            // Animate based on playing state
            if (state.isPlaying) {
                setViewVisibility(R.id.live_playing_indicator, android.view.View.VISIBLE)
            } else {
                setViewVisibility(R.id.live_playing_indicator, android.view.View.GONE)
            }
        }
    }
    
    private fun buildExpandedView(context: Context, state: PlaybackState): android.widget.RemoteViews {
        val track = state.current ?: return android.widget.RemoteViews(context.packageName, R.layout.live_activity_expanded)
        
        val lyric = (context.applicationContext as? MainApplication)
            ?.lyricCenter?.current?.value?.text
        
        return android.widget.RemoteViews(context.packageName, R.layout.live_activity_expanded).apply {
            setTextViewText(R.id.live_expanded_title, track.title)
            setTextViewText(R.id.live_expanded_artist, track.artist)
            setTextViewText(R.id.live_expanded_lyric, lyric ?: "")
            
            // Progress (0-100)
            val progress = if (state.durationMs > 0) {
                ((state.positionMs.toFloat() / state.durationMs) * 100).toInt()
            } else 0
            setProgressBar(R.id.live_expanded_progress, 100, progress, false)
            
            // Time labels
            val posStr = formatTime(state.positionMs)
            val durStr = formatTime(state.durationMs)
            setTextViewText(R.id.live_expanded_time_pos, posStr)
            setTextViewText(R.id.live_expanded_time_dur, durStr)
            
            // Play/pause icon
            val playPauseIcon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            setImageViewResource(R.id.live_expanded_play_pause, playPauseIcon)
        }
    }
    
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
    
    private const val LIVE_NOTIF_ID = 1002
}

/**
 * Simple Live Activity service wrapper for Android 12+
 * Full implementation would require a separate widget extension module
 */
class LiveActivityService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        observePlaybackState()
    }
    
    private fun observePlaybackState() {
        PlayerController.state
            .onEach { state ->
                if (state.isPlaying && state.current != null) {
                    LiveActivityManager.update(this, state)
                } else {
                    LiveActivityManager.stop(this)
                }
            }
            .launchIn(scope)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): android.os.IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
