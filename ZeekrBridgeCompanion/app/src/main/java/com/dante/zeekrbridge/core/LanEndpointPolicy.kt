package com.dante.zeekrbridge.core

/** One IPv4 address owned by the phone and the interface that owns it. */
data class LanEndpointCandidate(
    val ipv4: String,
    val interfaceName: String,
)

/**
 * Keeps the phone UI focused on addresses a car can realistically reach over
 * a phone hotspot or trusted LAN. Android hotspot implementations often expose
 * more than one interface, so the UI retains every usable candidate while
 * putting common hotspot addresses first.
 */
object LanEndpointPolicy {
    fun ordered(candidates: Collection<LanEndpointCandidate>): List<LanEndpointCandidate> =
        candidates
            .filter { isLocalLanIpv4(it.ipv4) }
            .distinctBy { it.ipv4 }
            .sortedWith(
                compareBy<LanEndpointCandidate> { rank(it) }
                    .thenBy { it.interfaceName }
                    .thenBy { it.ipv4 },
            )

    fun isLocalLanIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b, c, d) = octets
        if (a == 10) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        // Seen on the Zeekr/Android hotspot path during real-car testing.
        if (a == 192 && b == 0 && c == 0 && d in 1..254) return true
        return false
    }

    private fun rank(candidate: LanEndpointCandidate): Int {
        val ip = candidate.ipv4
        val iface = candidate.interfaceName.lowercase()
        val wifiLike = iface.startsWith("wlan") ||
            iface.startsWith("swlan") ||
            iface.startsWith("ap") ||
            "wifi" in iface
        return when {
            ip.startsWith("192.0.0.") -> 0
            wifiLike && ip.startsWith("192.168.") -> 1
            ip.startsWith("192.168.") -> 2
            wifiLike -> 3
            ip.startsWith("172.") -> 4
            else -> 5
        }
    }
}
