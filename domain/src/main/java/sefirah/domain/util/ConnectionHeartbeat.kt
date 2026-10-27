package sefirah.domain.util

enum class HeartbeatAction { NONE, PROBE, EXPIRED }

/** An idle connection is probed once before it can be declared dead. Times are monotonic. */
class ConnectionHeartbeat(
    initialActivityMillis: Long,
    private val idleIntervalMillis: Long = 90_000L,
    private val probeTimeoutMillis: Long = 60_000L,
) {
    @Volatile
    var lastActivityMillis: Long = initialActivityMillis
        private set

    private var probeSentAt: Long? = null
    private var lastCheckMillis: Long = initialActivityMillis

    @Synchronized
    fun onMessageReceived(nowMillis: Long) {
        lastActivityMillis = nowMillis
        probeSentAt = null
    }

    @Synchronized
    fun nextAction(nowMillis: Long): HeartbeatAction {
        // Give the reader a fresh opportunity after a long scheduling gap, including if
        // a probe was pending before sleep. Sleep alone must not trigger a reconnect.
        if (nowMillis - lastCheckMillis > maxOf(idleIntervalMillis * 2, probeTimeoutMillis * 3)) {
            probeSentAt = null
        }
        lastCheckMillis = nowMillis

        probeSentAt?.let {
            return if (nowMillis - it >= probeTimeoutMillis) HeartbeatAction.EXPIRED else HeartbeatAction.NONE
        }
        if (nowMillis - lastActivityMillis < idleIntervalMillis) return HeartbeatAction.NONE

        probeSentAt = nowMillis
        return HeartbeatAction.PROBE
    }
}
