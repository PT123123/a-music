package com.amusic.data.lan

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small DOM helper shared by the UPnP parsers.
 *
 * Everything UPnP emits is namespace-prefixed and the prefixes are chosen by the server
 * (`dc:title`, `upnp:artist`, `<res>`), so elements are matched on their *local* name with the
 * prefix stripped. Parsing is namespace-unaware on purpose — that keeps `dc:title` and a bare
 * `title` indistinguishable, which is exactly what we want here.
 */
internal object LanXml {

    /** Parse [xml], or null when it is not well-formed. Doctype/entity resolution is disabled. */
    fun parse(xml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        // Best-effort XXE hardening; Android's parser rejects some of these features, which is
        // why each is guarded. The content comes from the LAN, but there is no reason to fetch
        // external entities regardless.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrNull()

    /** Local tag name with any namespace prefix stripped (`dc:title` -> `title`). */
    fun localName(el: Element): String = el.tagName.substringAfterLast(':')

    /** Direct child elements of [el], in document order. */
    fun childElements(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            (kids.item(i) as? Element)?.let { out.add(it) }
        }
        return out
    }

    /** Every descendant of [root] whose local tag name is [name], in document order. */
    fun descendants(root: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        walk(root, name, out)
        return out
    }

    private fun walk(el: Element, name: String, out: MutableList<Element>) {
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            if (localName(child) == name) out.add(child)
            walk(child, name, out)
        }
    }

    /** Trimmed text of the first descendant named [name], or "" when absent. */
    fun text(el: Element, name: String): String =
        descendants(el, name).firstOrNull()?.textContent?.trim().orEmpty()

    /**
     * Parse a UPnP/DIDL duration (`H:MM:SS.mmm` or `MM:SS`) into milliseconds; 0 when the
     * server sent nothing usable.
     */
    fun parseDuration(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.trim().split(':')
        if (parts.size < 2) return 0
        val seconds = parts.last().toDoubleOrNull() ?: return 0
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return 0
        val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: 0 else 0
        return (hours * 3600 + minutes * 60) * 1000L + (seconds * 1000).toLong()
    }
}
