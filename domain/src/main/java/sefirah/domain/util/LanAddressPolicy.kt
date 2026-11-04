package sefirah.domain.util

/** Only directly attached private IPv4 subnets may use an explicitly selected LAN network. */
object LanAddressPolicy {
    fun isDirectLanPeer(peer: String, local: String, prefix: Int): Boolean {
        if (prefix !in 1..32) return false // Never turn a default route into a VPN bypass.
        val destination = ipv4(peer) ?: return false
        val source = ipv4(local) ?: return false
        if (!isPrivate(destination) || !isPrivate(source)) return false
        val mask = (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
        return (destination and mask) == (source and mask)
    }

    private fun ipv4(address: String): Long? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun isPrivate(ip: Long): Boolean =
        (ip ushr 24) == 10L || (ip ushr 20) == 0xac1L || (ip ushr 16) == 0xc0a8L
}
