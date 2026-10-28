package com.amusic.data.lan

/** Which wire protocol a saved LAN source speaks. */
enum class LanProtocol { HTTP, DLNA }

/**
 * A LAN music source the user can browse.
 *
 * For [LanProtocol.HTTP] this is a static file server's URL and [baseUrl] is both the browse
 * root and the thing we cache against. For [LanProtocol.DLNA] the browse entry point is an
 * *object id* ("0" for the root) rather than a URL, so [baseUrl] only records where the device
 * description came from while [controlUrl] is the ContentDirectory endpoint we POST SOAP to.
 */
data class LanSource(
    val id: String,
    val name: String,
    val protocol: LanProtocol,
    val baseUrl: String,
    val controlUrl: String = "",
)

/**
 * One row in a browse listing, produced by either the HTTP or the DLNA browser.
 *
 * [id] is the value the browsable container is keyed by: an absolute URL for HTTP (so the same
 * URL resolves relative links correctly) or a UPnP object id for DLNA.
 */
data class LanEntry(
    val id: String,
    val name: String,
    val isContainer: Boolean,
    val mediaUrl: String? = null,
    val durationMs: Long = 0,
    val artist: String = "",
    val album: String = "",
    val albumArtUrl: String? = null,
    val sizeBytes: Long = 0,
)

/** A browse cursor: the container we are currently looking at. */
data class LanNode(val id: String, val title: String)

/** A UPnP/DLNA MediaServer found on the network. */
data class UpnpDevice(
    val udn: String,
    val friendlyName: String,
    val modelName: String,
    val location: String,
    val controlUrl: String,
)

/** One page of a ContentDirectory browse. */
data class UpnpBrowseResult(
    val entries: List<LanEntry>,
    val totalMatches: Int,
    val returned: Int,
)

/**
 * Extensions we treat as playable audio when scraping a plain HTTP directory listing. The
 * listing has no MIME types to go on, so guessing by suffix is the only option; anything with
 * a different suffix is hidden rather than offered as a row that would fail in mpv.
 */
internal val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "ape", "wv", "wma",
    "dsf", "dff", "mka", "mp2", "m4b", "aif", "aiff", "alac", "tak", "tta", "mpc", "amr",
)

/** Lower-case file extension of a path or URL, or "" when there is none. */
internal fun extensionOf(nameOrUrl: String): String {
    val path = nameOrUrl.substringBefore('?').substringBefore('#')
    val last = path.substringAfterLast('/')
    val dot = last.lastIndexOf('.')
    return if (dot in 0 until last.length - 1) last.substring(dot + 1).lowercase() else ""
}
