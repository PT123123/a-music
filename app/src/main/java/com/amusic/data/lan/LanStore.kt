package com.amusic.data.lan

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists the user's LAN sources (hand-added HTTP servers plus remembered DLNA devices) and
 * the global play mode.
 *
 * SharedPreferences rather than Room: this is a handful of settings and a short list, exactly
 * like [com.amusic.data.prefs.SettingsRepository] — not library content that needs querying.
 */
class LanStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<LanSource>> = _sources.asStateFlow()

    /** true = 缓存播放 (download to disk, then play); false = 流式播放 (play the URL directly). */
    private val _cacheMode = MutableStateFlow(prefs.getBoolean(KEY_CACHE_MODE, false))
    val cacheMode: StateFlow<Boolean> = _cacheMode.asStateFlow()

    fun setCacheMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_CACHE_MODE, on).apply()
        _cacheMode.value = on
    }

    /**
     * Save [source] unless an identical target is already known, in which case the existing
     * entry is returned. This is what stops every DLNA scan from stacking up a new copy of the
     * same living-room server.
     */
    fun upsert(source: LanSource): LanSource {
        val existing = _sources.value.firstOrNull { sameTarget(it, source) }
        if (existing != null) return existing
        persist(_sources.value + source)
        return source
    }

    fun removeSource(id: String) {
        persist(_sources.value.filterNot { it.id == id })
    }

    private fun sameTarget(a: LanSource, b: LanSource): Boolean =
        a.protocol == b.protocol && when (b.protocol) {
            LanProtocol.DLNA -> a.controlUrl == b.controlUrl
            LanProtocol.HTTP -> a.baseUrl.trimEnd('/') == b.baseUrl.trimEnd('/')
        }

    private fun persist(list: List<LanSource>) {
        _sources.value = list
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("protocol", s.protocol.name)
                    .put("baseUrl", s.baseUrl)
                    .put("controlUrl", s.controlUrl),
            )
        }
        prefs.edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    private fun loadSources(): List<LanSource> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("baseUrl")
                if (url.isBlank()) return@mapNotNull null
                LanSource(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name").ifBlank { url },
                    protocol = runCatching { LanProtocol.valueOf(o.optString("protocol")) }
                        .getOrDefault(LanProtocol.HTTP),
                    baseUrl = url,
                    controlUrl = o.optString("controlUrl"),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        private const val PREFS = "amusic_lan"
        private const val KEY_SOURCES = "sources"
        private const val KEY_CACHE_MODE = "cache_mode"
    }
}
