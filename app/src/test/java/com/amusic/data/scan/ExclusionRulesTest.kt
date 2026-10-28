package com.amusic.data.scan

import com.amusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The exclusion rules decide what never reaches the library. A rule that is too eager
 * silently deletes songs the user wanted; a rule that is too lazy lets voice memos
 * back in. Both directions are pinned down here.
 */
class ExclusionRulesTest {

    private fun song(
        path: String,
        title: String = "unknown",
        durationMs: Long = 3 * 60_000L,
        album: String = "<unknown>",
        artist: String = "<unknown>",
    ) = Song(
        id = 1L,
        title = title,
        artist = artist,
        album = album,
        albumId = 0L,
        albumArtUri = null,
        durationMs = durationMs,
        data = path,
        size = 1024L,
        dateAdded = 0L,
    )

    // ------------------------------------------------------------- length rules

    @Test
    fun voiceMessage_tooShort_isDropped() {
        val s = song("/storage/emulated/0/Music/IMG_0001.opus", durationMs = 8_000L)
        assertEquals(ExclusionReason.TOO_SHORT, ExclusionRules.reject(s))
    }

    @Test
    fun shortFileFlaggedAsMusic_isKept() {
        val s = song("/storage/emulated/0/Music/intro.mp3", durationMs = 12_000L)
        assertNull(ExclusionRules.reject(s, isMusic = true))
    }

    @Test
    fun unknownDuration_isNeverADropReason() {
        val s = song("/storage/emulated/0/Music/amazing.mp3", durationMs = 0L)
        assertNull(ExclusionRules.reject(s))
    }

    @Test
    fun longNonMusic_isDropped() {
        val s = song("/storage/emulated/0/Misc/meeting.m4a", durationMs = 20 * 60_000L)
        assertEquals(ExclusionReason.NOT_MUSIC_TOO_LONG, ExclusionRules.reject(s))
    }

    @Test
    fun longFileFlaggedAsMusic_isKept() {
        // Live albums run past an hour and must survive.
        val s = song("/storage/emulated/0/Music/The Wall.flac", durationMs = 80 * 60_000L)
        assertNull(ExclusionRules.reject(s, isMusic = true))
    }

    // --------------------------------------------------------------- name rules

    @Test
    fun chineseTitle_isDropped() {
        val s = song("/storage/emulated/0/Music/a.m4a", title = "我的录音 01", durationMs = 60_000L)
        assertEquals(ExclusionReason.TITLE_KEYWORD, ExclusionRules.reject(s))
    }

    @Test
    fun englishPhraseTitle_isDropped() {
        val s = song("/storage/emulated/0/Music/a.m4a", title = "Voice note 3", durationMs = 60_000L)
        assertEquals(ExclusionReason.TITLE_KEYWORD, ExclusionRules.reject(s))
    }

    @Test
    fun keywordInsideAWord_isNotAMatch() {
        // "chanson" contains "chanson", not "voice" — whole-word matching keeps it.
        val s = song("/storage/emulated/0/Music/a.mp3", title = "Une chanson d'été", durationMs = 200_000L)
        assertNull(ExclusionRules.reject(s))
    }

    @Test
    fun albumKeyword_isDropped() {
        val s = song("/storage/emulated/0/Music/a.m4a", title = "note", album = "语音备忘", durationMs = 60_000L)
        assertEquals(ExclusionReason.ALBUM_KEYWORD, ExclusionRules.reject(s))
    }

    @Test
    fun artistKeyword_isDropped() {
        val s = song("/storage/emulated/0/Music/a.m4a", title = "note", artist = "Sound Recorder", durationMs = 60_000L)
        assertEquals(ExclusionReason.ARTIST_KEYWORD, ExclusionRules.reject(s))
    }

    @Test
    fun liveAlbum_isKept() {
        // "Live Recordings" is a real album title — the live hint overrules the keyword.
        val s = song("/storage/emulated/0/Music/a.flac", title = "Sympathy", album = "Live Recordings", durationMs = 300_000L)
        assertNull(ExclusionRules.reject(s))
    }

    @Test
    fun unknownArtist_isNotDropped() {
        val s = song("/storage/emulated/0/Music/a.mp3", title = "稻香", artist = "<unknown>", durationMs = 200_000L)
        assertNull(ExclusionRules.reject(s))
    }

    // ------------------------------------------------------------- folder rules

    @Test
    fun recorderFolder_isDropped() {
        val s = song("/storage/emulated/0/MIUI/sound_recorder/20230101_120000.m4a", durationMs = 180_000L)
        assertEquals(ExclusionReason.FOLDER, ExclusionRules.reject(s))
    }

    @Test
    fun wechatVoiceFolder_isDropped() {
        // 60 s — long enough that the length rule does not fire first, so the folder
        // rule is what rejects it.
        val s = song("/storage/emulated/0/Android/media/com.tencent.mm/voice/x.m4a", durationMs = 60_000L)
        assertEquals(ExclusionReason.FOLDER, ExclusionRules.reject(s))
    }

    @Test
    fun formatRuleWinsOverTheFolderRule() {
        // .amr is undecodable for libmpv — that is the more precise reason, and it is
        // checked first.
        val s = song("/storage/emulated/0/Android/media/com.tencent.mm/voice/x.amr", durationMs = 20_000L)
        assertEquals(ExclusionReason.UNSUPPORTED_FORMAT, ExclusionRules.reject(s))
    }

    @Test
    fun chatFolder_shortVoiceNote_isDropped() {
        val s = song("/storage/emulated/0/Telegram/Telegram Audio/file_12.oga", durationMs = 45_000L)
        assertEquals(ExclusionReason.FOLDER, ExclusionRules.reject(s))
    }

    @Test
    fun chatFolder_sharedSong_isKept() {
        // A song that was shared in a chat lives in the same folder — only the short
        // voice notes are dropped.
        val s = song("/storage/emulated/0/Telegram/Telegram Audio/file_12.mp3", durationMs = 6 * 60_000L)
        assertNull(ExclusionRules.reject(s))
    }

    @Test
    fun musicFolder_isKept() {
        val s = song("/storage/emulated/0/Music/周杰伦/稻香.mp3", title = "稻香", durationMs = 210_000L)
        assertNull(ExclusionRules.reject(s))
    }

    // ------------------------------------------------------------ format rules

    @Test
    fun drmContainer_isDropped() {
        val s = song("/storage/emulated/0/Music/qqmusic/song/a.mflac", durationMs = 200_000L)
        assertEquals(ExclusionReason.UNSUPPORTED_FORMAT, ExclusionRules.reject(s))
    }

    @Test
    fun voiceMessageContainer_isDropped() {
        val s = song("/storage/emulated/0/Music/a.silk", durationMs = 60_000L)
        assertEquals(ExclusionReason.UNSUPPORTED_FORMAT, ExclusionRules.reject(s))
    }
}
