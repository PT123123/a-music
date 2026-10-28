package com.amusic.data.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds UPnP/DLNA MediaServers on the local network with an SSDP "M-SEARCH".
 *
 * The datagram is sent to the SSDP multicast group and every answering device replies unicast
 * with the URL of its *device description* XML. We fetch that XML and keep only devices that
 * expose a `ContentDirectory` service — i.e. the ones that can actually be browsed for music.
 *
 * A [WifiManager.MulticastLock] is held for the duration: without it most Android builds drop
 * incoming multicast/broadcast traffic and discovery silently returns nothing.
 */
class SsdpDiscovery(private val context: Context) {

    suspend fun discover(timeoutMs: Long = 5_000): List<UpnpDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock(LOCK_TAG) }.getOrNull()
        runCatching {
            lock?.setReferenceCounted(false)
            lock?.acquire()
        }
        try {
            collectLocations(timeoutMs)
                .mapNotNull(::fetchDevice)
                .distinctBy { it.udn }
        } finally {
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    private fun collectLocations(timeoutMs: Long): List<String> {
        val found = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_000
            socket.broadcast = true
            val group = InetAddress.getByName(SSDP_ADDR)

            TARGETS.forEach { st ->
                val payload = searchRequest(st).toByteArray(Charsets.UTF_8)
                runCatching { socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT)) }
                    .onFailure { Log.w(TAG, "M-SEARCH send failed for $st", it) }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    Log.w(TAG, "SSDP receive stopped early", t)
                    break
                }
                locationOf(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { found.add(it) }
            }
        }
        Log.i(TAG, "SSDP surfaced ${found.size} candidate device description(s)")
        return found.toList()
    }

    private fun locationOf(response: String): String? =
        response.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun fetchDevice(location: String): UpnpDevice? = runCatching {
        parseDevice(location, LanHttp.getText(location))
    }.getOrElse {
        Log.w(TAG, "not a usable media server at $location: ${it.message}")
        null
    }

    private fun searchRequest(st: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(SSDP_ADDR).append(':').append(SSDP_PORT).append("\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: ").append(st).append("\r\n")
        append("\r\n")
    }

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val LOCK_TAG = "amusic:lan-ssdp"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900

        /**
         * Media servers specifically first, then everything (`ssdp:all`) as a catch-all for the
         * handful of servers that advertise a device type we did not ask for.
         */
        private val TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaServer:1",
            "ssdp:all",
        )

        /**
         * Parse a device-description document into a [UpnpDevice], or null when it has no
         * ContentDirectory service (a renderer, a router, a printer, …).
         */
        fun parseDevice(location: String, xml: String): UpnpDevice? {
            val root = LanXml.parse(xml)?.documentElement ?: return null
            val control = LanXml.descendants(root, "service")
                .firstOrNull { LanXml.text(it, "serviceType").contains("ContentDirectory") }
                ?.let { LanXml.text(it, "controlURL") }
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val friendly = LanXml.text(root, "friendlyName")
            return UpnpDevice(
                udn = LanXml.text(root, "UDN").ifBlank { location },
                friendlyName = friendly.ifBlank { "DLNA 媒体库" },
                modelName = LanXml.text(root, "modelName"),
                location = location,
                controlUrl = LanHttp.resolve(location, control) ?: control,
            )
        }
    }
}
