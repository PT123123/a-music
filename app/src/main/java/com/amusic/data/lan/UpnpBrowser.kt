package com.amusic.data.lan

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.IOException

/**
 * Reads a UPnP MediaServer's content tree over SOAP (`ContentDirectory#Browse`).
 *
 * [controlUrl] is the absolute control endpoint taken from the device description (see
 * [SsdpDiscovery]). The response embeds DIDL-Lite metadata as an escaped XML string; it is
 * unwrapped and parsed into the same [LanEntry] rows the HTTP browser produces, so the UI never
 * needs to know which protocol it is looking at.
 */
class UpnpBrowser(private val controlUrl: String) {

    suspend fun browse(objectId: String, startIndex: Int = 0, count: Int = 200): UpnpBrowseResult =
        withContext(Dispatchers.IO) {
            parse(soapPost(browseEnvelope(objectId, startIndex, count)))
        }

    private fun soapPost(body: String): String {
        val conn = LanHttp.open(controlUrl, method = "POST")
        try {
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$SERVICE#Browse\"")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("SOAP HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(xml: String): UpnpBrowseResult {
        val root = LanXml.parse(xml)?.documentElement ?: throw IOException("invalid SOAP response")

        // A fault still arrives as HTTP 200, so check for UPnP's own error before trusting the body.
        val errorCode = LanXml.text(root, "errorCode")
        if (errorCode.isNotBlank()) {
            throw IOException("UPnP $errorCode ${LanXml.text(root, "errorDescription")}".trim())
        }

        val didl = LanXml.descendants(root, "Result").firstOrNull()?.textContent.orEmpty()
        val entries = parseDidl(didl)
        val total = LanXml.text(root, "TotalMatches").toIntOrNull() ?: entries.size
        val returned = LanXml.text(root, "NumberReturned").toIntOrNull() ?: entries.size
        Log.i(TAG, "browse returned ${entries.size}/$total under $controlUrl")
        return UpnpBrowseResult(entries, total, returned)
    }

    private fun parseDidl(didl: String): List<LanEntry> {
        if (didl.isBlank()) return emptyList()
        val root = LanXml.parse(didl)?.documentElement ?: return emptyList()
        val out = ArrayList<LanEntry>()
        LanXml.childElements(root).forEach { el ->
            when (LanXml.localName(el)) {
                "container" -> out += containerEntry(el)
                "item" -> itemEntry(el)?.let { out += it }
            }
        }
        return out
    }

    private fun containerEntry(el: Element) = LanEntry(
        id = el.getAttribute("id"),
        name = LanXml.text(el, "title").ifBlank { "未命名" },
        isContainer = true,
    )

    private fun itemEntry(el: Element): LanEntry? {
        val resources = LanXml.descendants(el, "res")
        val res = resources.firstOrNull { isHttpAudio(it.getAttribute("protocolInfo")) }
            ?: resources.firstOrNull { it.getAttribute("protocolInfo").startsWith("http-get", ignoreCase = true) }
            ?: resources.firstOrNull()
        val url = res?.textContent?.trim().orEmpty()
        if (url.isBlank()) return null
        return LanEntry(
            id = el.getAttribute("id"),
            name = LanXml.text(el, "title").ifBlank { "未命名" },
            isContainer = false,
            mediaUrl = url,
            durationMs = LanXml.parseDuration(res?.getAttribute("duration")),
            artist = LanXml.text(el, "artist"),
            album = LanXml.text(el, "album"),
            albumArtUrl = LanXml.text(el, "albumArtURI").ifBlank { null },
            sizeBytes = res?.getAttribute("size")?.toLongOrNull() ?: 0L,
        )
    }

    /** `protocolInfo` looks like `http-get:*:audio/flac:*`. */
    private fun isHttpAudio(protocolInfo: String): Boolean =
        protocolInfo.startsWith("http-get", ignoreCase = true) &&
            protocolInfo.substringAfter(':', "").contains("audio", ignoreCase = true)

    private fun browseEnvelope(objectId: String, start: Int, count: Int): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Browse xmlns:u="$SERVICE">
<ObjectID>${escapeXml(objectId)}</ObjectID>
<BrowseFlag>BrowseDirectChildren</BrowseFlag>
<Filter>*</Filter>
<StartingIndex>$start</StartingIndex>
<RequestedCount>$count</RequestedCount>
<SortCriteria></SortCriteria>
</u:Browse>
</s:Body>
</s:Envelope>"""

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private companion object {
        const val TAG = "UpnpBrowser"
        const val SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"
    }
}
