package com.amusic.player

import kotlin.math.abs

/**
 * Graphical equalizer for the libmpv pipeline.
 *
 * mpv has no native EQ, but this build ships `libavfilter` and has `lavfi-bridge` compiled
 * in, so every ffmpeg audio filter is reachable through `af=lavfi=[…]`. We chain one
 * peaking-biquad `equalizer` per band; mpv's filter list parser splits on `,` *outside*
 * brackets, so each band gets its own `lavfi=[…]` wrapper, which is unambiguous.
 *
 * Bands with ~0 dB are omitted entirely — an empty [`af`] clears the chain, which is what
 * "均衡器关闭" means.
 */
object Equalizer {

    /** Centre frequencies, low to high. Changing this invalidates stored gains. */
    val BANDS: List<Int> = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000)

    /** Short axis labels for the sliders. */
    val BAND_LABELS: List<String> = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k")

    const val MIN_DB = -12f
    const val MAX_DB = 12f

    data class Preset(val id: String, val label: String, val gains: List<Float>)

    val presets: List<Preset> = listOf(
        Preset("flat", "平直", List(8) { 0f }),
        Preset("pop", "流行", listOf(2f, 1f, 0f, -1f, 0f, 1f, 2f, 2f)),
        Preset("rock", "摇滚", listOf(5f, 3f, -1f, -2f, -1f, 2f, 4f, 5f)),
        Preset("jazz", "爵士", listOf(3f, 2f, 1f, 1f, -1f, -1f, 0f, 2f)),
        Preset("classical", "古典", listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 3f)),
        Preset("bass", "低音增强", listOf(8f, 6f, 4f, 1f, 0f, 0f, 0f, 0f)),
        Preset("vocal", "人声", listOf(-2f, -2f, -1f, 1f, 3f, 3f, 2f, 0f)),
        Preset("electronic", "电子", listOf(4f, 3f, 2f, 0f, -1f, 1f, 3f, 4f)),
    )

    fun gainsOf(presetId: String): List<Float> =
        presets.firstOrNull { it.id == presetId }?.gains ?: List(BANDS.size) { 0f }

    /** Which preset matches [gains] exactly, if any — used to highlight the current chip. */
    fun presetFor(gains: List<Float>): Preset? =
        presets.firstOrNull { p -> p.gains.size == gains.size && p.gains.indices.all { abs(p.gains[it] - gains[it]) < 0.05f } }

    /**
     * Builds the mpv `af` value for [gains]. Returns an empty string when nothing is boosted
     * or cut, which clears any existing filter chain.
     */
    fun afString(enabled: Boolean, gains: List<Float>): String {
        if (!enabled) return ""
        return BANDS.indices
            .filter { it < gains.size && abs(gains[it]) >= 0.05f }
            .joinToString(",") { i ->
                "lavfi=[equalizer=f=${BANDS[i]}:t=q:w=1.0:g=${"%.1f".format(gains[i])}]"
            }
    }

    /** Human-readable summary for the settings screen. */
    fun describe(enabled: Boolean, gains: List<Float>): String {
        if (!enabled) return "关闭"
        val p = presetFor(gains)
        return p?.label ?: "自定义"
    }
}
