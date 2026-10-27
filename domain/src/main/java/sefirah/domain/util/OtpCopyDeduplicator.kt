package sefirah.domain.util

/** Bounded memory only. Uses the caller's monotonic clock; nothing is written to disk. */
class OtpCopyDeduplicator(private val windowMillis: Long = 120_000L) {
    private data class Key(val source: String, val code: String)
    private val recent = LinkedHashMap<Key, Long>()

    fun isDuplicate(source: String, code: String, now: Long): Boolean {
        prune(now)
        return recent.containsKey(Key(source, code))
    }

    fun record(source: String, code: String, now: Long) {
        prune(now)
        recent[Key(source, code)] = now
        if (recent.size > 32) recent.remove(recent.keys.first())
    }

    fun clear() = recent.clear()

    private fun prune(now: Long) {
        recent.entries.removeAll { now - it.value >= windowMillis || now < it.value }
    }
}
