package sefirah.domain.util

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class ConnectionPolicyTest {
    @Test fun manualDisconnectBlocksAutomaticReconnectButAllowsExplicitPeerConnect() {
        assertFalse(canResumeConnection(paused = true, userInitiated = false))
        assertTrue(canResumeConnection(paused = true, userInitiated = true))
        assertTrue(canResumeConnection(paused = false, userInitiated = false))
    }

    @Test fun normalDesktopHeartbeatSilenceDoesNotAllowSessionReplacement() {
        for (silence in listOf(30_000L, 60_000L, 80_000L, 120_000L, 179_999L)) {
            assertFalse(canReplaceSilentConnection(silence))
        }
        assertTrue(canReplaceSilentConnection(180_000L))
    }

    @Test fun failedOrCancelledReplacementKeepsTheOldSessionAndManualPause() = runBlocking {
        for (cancelled in listOf(false, true)) {
            var active = "old"
            var paused = true
            val closed = mutableListOf<String>()
            try {
                val adopted = prepareConnectionReplacement("candidate",
                    authenticate = { if (cancelled) throw CancellationException() else false },
                    commit = { active = it; paused = false; closed += "old" },
                    discard = { closed += it },
                )
                assertFalse(adopted)
            } catch (_: CancellationException) {
                assertTrue(cancelled)
            }
            assertEquals("old", active)
            assertTrue(paused)
            assertEquals(listOf("candidate"), closed)
        }
    }

    @Test fun successfulReplacementPublishesNewSessionBeforeClosingOldOne() = runBlocking {
        var active = "old"
        val closed = mutableListOf<String>()
        assertTrue(prepareConnectionReplacement("new",
            authenticate = { assertEquals("old", active); assertTrue(closed.isEmpty()); true },
            commit = { active = it; closed += "old" },
            discard = { closed += it },
        ))
        assertEquals("new", active)
        assertEquals(listOf("old"), closed)
    }

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
