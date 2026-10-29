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

/** Both peers choose the same socket when their outgoing TLS connections are ready. */
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

fun canResumeConnection(paused: Boolean, userInitiated: Boolean): Boolean = !paused || userInitiated

// Longer than the desktop idle interval (60s) plus its response window (60s).
fun canReplaceSilentConnection(silentMillis: Long): Boolean = silentMillis >= 180_000L

/** The old session remains owned until the replacement has completed its handshake. */
suspend fun <T> prepareConnectionReplacement(
    candidate: T,
    authenticate: suspend (T) -> Boolean,
    commit: suspend (T) -> Unit,
    discard: (T) -> Unit,
): Boolean {
    try {
        if (!authenticate(candidate)) {
            discard(candidate)
            return false
        }
        commit(candidate)
        return true
    } catch (error: Throwable) {
        discard(candidate)
        throw error
    }
}
