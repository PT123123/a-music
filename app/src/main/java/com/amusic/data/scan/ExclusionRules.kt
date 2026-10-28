package com.amusic.data.scan

import com.amusic.data.model.Song

/**
 * Why a file was kept out of the library.
 *
 * The reason is only used for logging and for cleaning up rows that were added before
 * the rules existed — the file itself is never touched.
 */
enum class ExclusionReason(val label: String) {
    UNSUPPORTED_FORMAT("encrypted/undecodable container"),
    TOO_SHORT("too short for a song"),
    NOT_MUSIC_TOO_LONG("not flagged as music and far too long"),
    TITLE_KEYWORD("title looks like a recording"),
    ALBUM_KEYWORD("album looks like a recording"),
    ARTIST_KEYWORD("artist looks like a recorder"),
    FOLDER("comes from a recording folder"),
}

/**
 * Hard-coded "this is not a song" rules.
 *
 * The library is a *music* library, so voice memos, chat voice messages, call
 * recordings, ringtones and DRM-protected downloads must not show up in it. Those
 * files are easy to recognise, and every recogniser lives here:
 *
 *  1. length  — too short for a song, or (when MediaStore does not think the file is
 *                music) far too long for a song: a call recording, a meeting, an
 *                audiobook.
 *  2. name    — the title / album / artist carries a "recording" word.
 *  3. folder  — the file comes from a directory that only ever holds recordings.
 *
 * The rules live apart from the scanners on purpose:
 *  - there is exactly one place to look when a file is wrongly kept or wrongly dropped;
 *  - the QQ Music scanner can reuse the same verdicts;
 *  - they are unit-testable without a device (see `ExclusionRulesTest`).
 *
 * [VERSION] must be bumped whenever a rule changes: the library re-scans on the next
 * launch when it sees a new version, so files that were already in the library are
 * cleaned up as well (see `MusicRepository.scanIfNeeded`).
 */
object ExclusionRules {

    /** Bump on every rule change — it triggers a re-scan of the existing library. */
    const val VERSION = 1

    // ------------------------------------------------------------------ length

    /** Below this a file is a blip, a notification or a chat voice message. */
    const val MIN_DURATION_MS = 30_000L

    /**
     * Above this a file that MediaStore does *not* flag as music is a recording
     * (call, meeting, sleep recorder, audiobook) — never a track.
     */
    const val MAX_NON_MUSIC_DURATION_MS = 15 * 60_000L

    /** Chat folders hold voice notes (short) next to songs that were shared (long). */
    private const val CHAT_VOICE_MAX_MS = 5 * 60_000L

    // ------------------------------------------------------------------- names

    /** Title keywords. CJK matches as a substring, ASCII as whole words. */
    private val TITLE_KEYWORDS = listOf(
        // Chinese
        "录音", "语音", "通话", "留言", "口述", "录制", "录音笔", "语音消息",
        // English
        "voice", "voices", "recording", "recordings", "recorder", "voicemail",
        "memo", "memos", "whatsapp", "telegram",
        // English phrases
        "voice note", "voice message", "sound recorder", "voice recorder",
        "call recording", "call record", "call log",
    )

    /** Album keywords — recorder apps name the album after themselves. */
    private val ALBUM_KEYWORDS = listOf(
        "录音", "语音", "通话录音", "语音备忘", "备忘录",
        "voice", "voices", "recording", "recordings", "voicemail", "memo", "memos",
        "voice memo", "voice notes", "recorder", "sound recorder", "voice recorder",
    )

    /** Artist keywords — recorder apps write themselves into the artist field. */
    private val ARTIST_KEYWORDS = listOf(
        "录音", "录音笔", "语音备忘", "通话录音",
        "recorder", "sound recorder", "voice recorder", "voice memo",
    )

    /**
     * Words that mean "this really is music": live albums and concert recordings are
     * titled "Live at …" / "Live Recordings" and must not be dropped as recordings.
     */
    private val MUSIC_CONTEXT = listOf("live", "现场", "实况", "演唱会", "unplugged")

    // ----------------------------------------------------------------- folders

    /**
     * A path fragment that marks a directory as "recordings live here".
     *
     * [maxDurationMs] limits the rule to short files: chat folders hold both voice
     * messages *and* songs that were shared in a conversation, and only the former is
     * short. Folders that hold nothing but recordings leave it at [Long.MAX_VALUE].
     */
    private data class FolderRule(
        val marker: String,
        val maxDurationMs: Long = Long.MAX_VALUE,
    )

    private val FOLDER_RULES = listOf(
        // ---- recorder apps: the whole folder is recordings ----
        FolderRule("/recordings"),
        FolderRule("/recording"),
        FolderRule("/recorder"),
        FolderRule("/sound_recorder"),
        FolderRule("/soundrecorder"),
        FolderRule("/sounds_recorder"),
        FolderRule("/voice_recorder"),
        FolderRule("/voice_memos"),
        FolderRule("/voice memos"),
        FolderRule("录音"),
        FolderRule("语音备忘"),
        FolderRule("录音笔"),
        // ---- call recordings ----
        FolderRule("/callrecording"),
        FolderRule("/call_recording"),
        FolderRule("/call_record"),
        FolderRule("/call recordings"),
        FolderRule("/calllog"),
        FolderRule("通话录音"),
        // ---- voice messages ----
        FolderRule("/voice notes"),
        FolderRule("/voicemessages"),
        FolderRule("语音"),
        // ---- WeChat / QQ ----
        FolderRule("/micromsg/voice"),
        FolderRule("/com.tencent.mm"),
        FolderRule("/tencent/qq/audios"),
        FolderRule("/tencent/qqfile"),
        FolderRule("/audiocache"),
        // ---- other messengers: only their short voice notes ----
        FolderRule("/whatsapp voice notes", CHAT_VOICE_MAX_MS),
        FolderRule("/whatsapp audio", CHAT_VOICE_MAX_MS),
        FolderRule("/telegram audio", CHAT_VOICE_MAX_MS),
        FolderRule("/signal", CHAT_VOICE_MAX_MS),
        // ---- system sounds ----
        FolderRule("/ringtones"),
        FolderRule("/notifications"),
        FolderRule("/alarms"),
        FolderRule("/alarm_sounds"),
    )

    // ---------------------------------------------------------------- formats

    /**
     * Containers the decoder cannot open, plus the old voice-message formats. DRM
     * copies from QQ Music / NetEase / Kugou carry these suffixes.
     */
    private val BLOCKED_EXTENSIONS = setOf(
        "mflac", "qmc", "mgg", "tm0", "kgm", "kgma", "qcz", "tkm", "vpr",
        "amr", "silk", "slk", "nav",
    )

    /** Splits latin text into words; CJK text is matched as a substring instead. */
    private val SEPARATORS = Regex("[^a-z0-9]+")

    /**
     * The verdict for one file: `null` means it belongs in the library.
     *
     * @param isMusic MediaStore's IS_MUSIC flag. When the system believes the file is
     *   music, the length rules do not apply — a 20-second jingle is still music.
     */
    fun reject(song: Song, isMusic: Boolean = false): ExclusionReason? {
        val ext = song.data.substringAfterLast('.', "").lowercase()
        if (ext in BLOCKED_EXTENSIONS) return ExclusionReason.UNSUPPORTED_FORMAT

        // duration 0 means "unknown" (e.g. the QQ Music scanner cannot know it) —
        // unknown length is never a reason to drop a file.
        val duration = song.durationMs
        if (duration > 0 && !isMusic) {
            if (duration < MIN_DURATION_MS) return ExclusionReason.TOO_SHORT
            if (duration > MAX_NON_MUSIC_DURATION_MS) return ExclusionReason.NOT_MUSIC_TOO_LONG
        }

        if (matchesFolder(song.data, duration)) return ExclusionReason.FOLDER

        // "Live at …" / "现场版" are music even when they say "recording".
        val liveContext = looksLikeMusic(song.title) || looksLikeMusic(song.album)
        if (!liveContext) {
            if (matchesAny(song.title, TITLE_KEYWORDS)) return ExclusionReason.TITLE_KEYWORD
            if (matchesAny(song.album, ALBUM_KEYWORDS)) return ExclusionReason.ALBUM_KEYWORD
        }
        if (matchesAny(song.artist, ARTIST_KEYWORDS)) return ExclusionReason.ARTIST_KEYWORD

        return null
    }

    private fun matchesFolder(path: String, durationMs: Long): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        return FOLDER_RULES.any { rule ->
            if (!lower.contains(rule.marker)) return@any false
            rule.maxDurationMs == Long.MAX_VALUE || durationMs <= 0L || durationMs <= rule.maxDurationMs
        }
    }

    private fun matchesAny(text: String, keywords: List<String>): Boolean {
        if (text.isBlank()) return false
        return keywords.any { matchesKeyword(text, it) }
    }

    /**
     * Keyword matching:
     *  - Chinese (any non-ASCII) has no word boundaries, so it matches as a substring:
     *    "录音" hits "我的录音 01".
     *  - Multi-word English phrases match as substrings: "voice note".
     *  - Single English words must match a whole word, otherwise "voice" would also
     *    hit "chanson" and "record" would hit "recorded".
     */
    private fun matchesKeyword(text: String, keyword: String): Boolean {
        val haystack = text.lowercase()
        val needle = keyword.lowercase()
        if (needle.any { it.code > 127 }) return haystack.contains(needle)
        if (needle.contains(' ')) return haystack.contains(needle)
        val tokens = haystack.split(SEPARATORS)
        return tokens.any { it == needle || it == "${needle}s" || it == "${needle}es" }
    }

    /** True when the text carries a "this is a concert recording" hint. */
    private fun looksLikeMusic(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return MUSIC_CONTEXT.any { ctx ->
            if (ctx.any { it.code > 127 }) lower.contains(ctx)
            else lower.split(SEPARATORS).any { it == ctx }
        }
    }
}
