package sefirah.domain.util

/** Deduplication is per destination and only records frames actually flushed to its socket. */
class ClipboardDeliveryTracker {
    private data class Delivery(val stamp: Long, val text: String, val sentAt: Long, val session: Long?)
    private val delivered = mutableMapOf<String, Delivery>()

    @Synchronized
    fun shouldSend(deviceId: String, stamp: Long, text: String, now: Long, force: Boolean, session: Long? = null): Boolean {
        if (force) return true
        val previous = delivered[deviceId] ?: return true
        if (previous.session != session) return true
        if (previous.text != text) return true
        return !(stamp != 0L && previous.stamp == stamp || now - previous.sentAt < 1_000L)
    }

    @Synchronized
    fun sent(deviceId: String, stamp: Long, text: String, now: Long, session: Long? = null) {
        delivered[deviceId] = Delivery(stamp, text, now, session)
    }

    @Synchronized
    fun forget(deviceId: String) { delivered.remove(deviceId) }
}
