package sefirah.domain.util

/** Shared by discovery and the watchdog: discovery must not bypass a failed dial's backoff. */
class ConnectionRetryPolicy {
    private var failures = 0
    private var retryAt = 0L

    fun canRetry(now: Long): Boolean = now >= retryAt

    fun failed(now: Long) {
        val delay = minOf(300_000L, 15_000L shl minOf(failures, 5))
        failures++
        retryAt = now + delay
    }

    fun reset() {
        failures = 0
        retryAt = 0L
    }
}

/** Both peers choose the same socket when they dial each other simultaneously. */
fun acceptIncomingConnection(
    localId: String,
    remoteId: String,
    outgoingAttempt: Boolean,
    existingOutgoing: Boolean?,
): Boolean {
    if (existingOutgoing == false) return false
    if (!outgoingAttempt && existingOutgoing == null) return true
    return localId.compareTo(remoteId) > 0
}
