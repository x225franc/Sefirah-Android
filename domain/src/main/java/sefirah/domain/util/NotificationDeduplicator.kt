package sefirah.domain.util

/** Keeps only fingerprints, per PC. Briefly remembers removals to absorb OEM reposts. */
class NotificationDeduplicator {
    private data class Key(val device: String, val notification: String)
    private data class Entry(val fingerprint: String, val expiresAt: Long)
    private val entries = LinkedHashMap<Key, Entry>()

    @Synchronized
    fun shouldSend(device: String, notification: String, fingerprint: String, now: Long): Boolean {
        entries.entries.removeAll { it.value.expiresAt <= now }
        val key = Key(device, notification)
        val previous = entries[key]
        entries[key] = Entry(fingerprint, Long.MAX_VALUE)
        if (entries.size > 512) entries.remove(entries.keys.first())
        return previous?.fingerprint != fingerprint
    }

    @Synchronized
    fun removed(notification: String, now: Long) {
        entries.entries.filter { it.key.notification == notification }.forEach {
            it.setValue(it.value.copy(expiresAt = now + 120_000L))
        }
    }

    @Synchronized
    fun clearDevice(device: String) {
        entries.keys.removeAll { it.device == device }
    }
}
