package com.amusic.data.lyrics

/**
 * Minimal but robust LRC parser.
 *
 * Handles:
 *  - `[mm:ss.xx]` / `[mm:ss:xx]` / `[mm:ss]` time tags (1–3 fractional digits)
 *  - multiple time tags on one line (`[00:01.00][00:05.00]chorus`)
 *  - `[offset:±ms]` global offset
 *  - `[ti:]/[ar:]/[al:]/[by:]` metadata tags (skipped)
 *  - a separate translation LRC merged in by exact timestamp
 */
object LrcParser {

    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:\\s*(-?\\d+)\\s*]", RegexOption.IGNORE_CASE)

    fun parse(lrc: String?, translation: String? = null): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()

        val offsetMs = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val transMap = parseTimedText(translation)

        // timestamp -> text (later duplicates overwrite, matching common LRC semantics)
        val lines = LinkedHashMap<Long, String>()
        for (raw in lrc.lineSequence()) {
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in tags) {
                val t = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3]) + offsetMs
                lines[t.coerceAtLeast(0)] = text
            }
        }
        if (lines.isEmpty()) return emptyList()

        return lines.entries
            .sortedBy { it.key }
            .map { (t, text) -> LyricLine(t, text, transMap[t]) }
    }

    private fun parseTimedText(lrc: String?): Map<Long, String> {
        if (lrc.isNullOrBlank()) return emptyMap()
        val out = HashMap<Long, String>()
        for (raw in lrc.lineSequence()) {
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in tags) out[toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])] = text
        }
        return out
    }

    private fun toMillis(mm: String, ss: String, frac: String): Long {
        val minutes = mm.toLongOrNull() ?: 0L
        val seconds = ss.toLongOrNull() ?: 0L
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.substring(0, 3).toLong()
        }
        return minutes * 60_000 + seconds * 1000 + ms
    }
}
