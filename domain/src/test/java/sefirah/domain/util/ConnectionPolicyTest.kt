package sefirah.domain.util

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    @Test fun retriesBackOffAcrossDiscoveryAndWatchdogAndResetAfterRecovery() {
        val policy = ConnectionRetryPolicy()
        var now = 1_000L
        for (delay in listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            assertTrue(policy.canRetry(now))
            policy.failed(now)
            assertFalse(policy.canRetry(now + delay - 1))
            now += delay
            assertTrue(policy.canRetry(now))
        }
        policy.failed(now)
        policy.reset()
        assertTrue(policy.canRetry(now))
        policy.failed(now)
        assertTrue(policy.canRetry(now + 15_000L))
    }

    @Test fun simultaneousDialsChooseTheSameInitiatorOnBothPeers() {
        assertFalse(acceptIncomingConnection("a", "b", true, null))
        assertTrue(acceptIncomingConnection("b", "a", true, null))
        assertFalse(acceptIncomingConnection("a", "b", false, true))
        assertTrue(acceptIncomingConnection("b", "a", false, true))
    }

    @Test fun ordinaryIncomingWorksFromEitherPeerButDuplicatesAreRejected() {
        assertTrue(acceptIncomingConnection("a", "b", false, null))
        assertTrue(acceptIncomingConnection("b", "a", false, null))
        assertFalse(acceptIncomingConnection("b", "a", false, false))
    }
}
