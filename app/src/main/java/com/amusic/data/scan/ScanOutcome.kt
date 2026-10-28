package com.amusic.data.scan

import com.amusic.data.model.Song

/** A file the rules rejected. The file stays on disk — only the library row goes away. */
data class RejectedSong(
    val path: String,
    val title: String,
    val reason: ExclusionReason,
)

/**
 * Result of one scan pass: what stays in the library, and what was thrown out.
 *
 * The rejected list matters: rows that were added to the library *before* the rules
 * existed are still in the database, and the scan has to tell the repository which
 * paths to clean up.
 */
data class ScanOutcome(
    val kept: List<Song>,
    val rejected: List<RejectedSong>,
) {
    operator fun plus(other: ScanOutcome): ScanOutcome =
        ScanOutcome(kept + other.kept, rejected + other.rejected)
}
