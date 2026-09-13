package com.amusic.data.online

/** Which of the source site's two catalogues a song id belongs to. */
enum class Net24Origin(val listLabel: String) {
    /** `searchOnlineMusicOne` — 网易云 based; its ids resolve for [Net24Quality.MASTER] / [Net24Quality.SURROUND]. */
    MASTER("母带源"),

    /** `searchOnlineMusicTwo` — 酷我 based; its ids resolve for [Net24Quality.LOSSLESS]. */
    LOSSLESS("无损源"),
}

/**
 * The three download tiers the source site exposes. [type] is the URL path segment.
 *
 * [origin] is not cosmetic — the site keys the id space **per source**, so an id from the
 * 母带源 list resolves to a completely unrelated song under `b`, and vice versa. Feeding the
 * wrong pairing silently downloads somebody else's track, which is why every request goes
 * through [Net24Song.qualityId] and the answer is name-checked before it is offered.
 */
enum class Net24Quality(
    val type: String,
    val label: String,
    val short: String,
    val origin: Net24Origin,
) {
    MASTER("a", "至臻母带", "母带", Net24Origin.MASTER),
    SURROUND("c", "高清环绕声", "环绕", Net24Origin.MASTER),
    LOSSLESS("b", "无损音质", "无损", Net24Origin.LOSSLESS),
}

/**
 * One song, merged from the two search endpoints.
 *
 * The two lists describe the same catalogue from different providers, so entries are folded
 * together by (title, lead artist). A song that only exists in one list simply has one id and
 * therefore fewer usable [qualities].
 */
data class Net24Song(
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    /** Id as understood by the 母带源 (`a` / `c` endpoints), or null when not listed there. */
    val masterId: String? = null,
    /** Id as understood by the 无损源 (`b` endpoint), or null when not listed there. */
    val losslessId: String? = null,
) {
    /** Best available id — used as the compose key and as the cache key prefix. */
    val id: String get() = masterId ?: losslessId.orEmpty()

    val displayName: String
        get() = if (artist.isBlank()) title else "$artist - $title"

    /** Tiers this song can actually serve, in display order. */
    val qualities: List<Net24Quality>
        get() = Net24Quality.entries.filter { qualityId(it) != null }

    fun qualityId(quality: Net24Quality): String? = when (quality.origin) {
        Net24Origin.MASTER -> masterId
        Net24Origin.LOSSLESS -> losslessId
    }

    /** Stable per-quality key used by the UI's progress map. */
    fun key(quality: Net24Quality): String = "${qualityId(quality).orEmpty()}:${quality.type}"

    /** Folds another listing of the same song into this one. */
    fun merge(other: Net24Song): Net24Song = copy(
        album = album.ifBlank { other.album },
        coverUrl = coverUrl.ifBlank { other.coverUrl },
        masterId = masterId ?: other.masterId,
        losslessId = losslessId ?: other.losslessId,
    )
}

/** A resolved download target scraped from a source-site detail page. */
data class Net24Download(
    val url: String,
    val fileName: String,
    val ext: String,
    val quality: String,
    /** Raw size text exactly as the site prints it, e.g. `198.47MB`. */
    val sizeText: String,
    /** Same size parsed to bytes, or 0 when unparseable. */
    val sizeBytes: Long,
)

/** Result of asking the site for one tier. */
sealed interface Net24Resolve {
    /** [tier] is the tier we actually resolved — for preview it is the lightest one available. */
    data class Ok(val tier: Net24Quality, val download: Net24Download) : Net24Resolve
    data class Fail(val reason: String) : Net24Resolve
}
