package com.amusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.amusic.data.db.MusicDatabase
import com.amusic.data.lyrics.LyricCenter
import com.amusic.data.lyrics.LyricsRepository
import com.amusic.data.prefs.SettingsRepository
import com.amusic.data.recommend.RecommendationCenter
import com.amusic.data.repository.MusicRepository
import com.amusic.data.scan.MediaStoreScanner
import com.amusic.data.scan.QqMusicScanner
import com.amusic.player.Equalizer
import com.amusic.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainApplication : Application(), ImageLoaderFactory {

    lateinit var repository: MusicRepository
        private set

    /** 相似推荐: desktop-extracted payload + on-device Rust scoring (native/musicspace). */
    lateinit var recommendation: RecommendationCenter
        private set

    lateinit var lyricsRepository: LyricsRepository
        private set

    /**
     * App-wide "current lyric line" hub. Lives here rather than in the player screen because
     * the notification and the floating desktop window need lyrics while no UI is open.
     */
    lateinit var lyricCenter: LyricCenter
        private set

    lateinit var settings: SettingsRepository
        private set

    /** App-lifetime scope for the two long-running bridges wired in [onCreate]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Album art streams in from remote CDNs at full resolution (the 无损站 covers are
     * 1000px+). Uploading those as *hardware* bitmaps trips an fdsan abort inside the
     * Adreno GL driver on some devices — the process dies in RenderThread during
     * eglSwapBuffers with `attempted to close file descriptor N`, which is what made
     * "试听" look like a random crash. Software bitmaps take the slow-but-safe path.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .allowHardware(false)
            .crossfade(false)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Initialize libmpv (safe no-op if already done).
        PlayerController.init(this)

        settings = SettingsRepository(this)
        val db = MusicDatabase.get(this)
        repository = MusicRepository(
            db,
            MediaStoreScanner(this),
            QqMusicScanner(this),
            this,
        )
        recommendation = RecommendationCenter(this, db, db.songDao(), scope)
        recommendation.start()
        lyricsRepository = LyricsRepository(this)
        lyricCenter = LyricCenter(lyricsRepository, scope)

        // Keep the lyric hub pointed at whatever is playing, from anywhere in the app.
        scope.launch {
            PlayerController.state.collect { state ->
                lyricCenter.follow(state.current)
                lyricCenter.tick(state.positionMs)
            }
        }

        // Equalizer: settings changes are pushed straight into mpv's audio filter chain.
        scope.launch {
            combine(settings.eqEnabled, settings.eqGains) { on, gains ->
                Equalizer.afString(on, gains)
            }
                .distinctUntilChanged()
                .collect { PlayerController.applyAudioFilter(it) }
        }

        // First-launch library scan. Runtime permission is requested in MainActivity,
        // which will re-trigger scanIfNeeded() once granted.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.scanIfNeeded() }
        }
    }
}
