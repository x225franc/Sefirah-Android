package sefirah.domain.util

import org.junit.Assert.*
import org.junit.Test

class ClipboardDeliveryTrackerTest {
    @Test fun failedOrMissingTransportNeverPoisonsTheNextCopyAttempt() {
        val delivery = ClipboardDeliveryTracker()
        // No successful flush was recorded: listener, logcat and a later retry may all try.
        repeat(3) { assertTrue(delivery.shouldSend("pc", 42L, "test", 1_000L + it, false)) }
        delivery.sent("pc", 42L, "test", 1_003L)
        assertFalse(delivery.shouldSend("pc", 42L, "test", 30_000L, false))
    }

    @Test fun manualTileCanResendTheExactClipRegardlessOfDuplicateOrCooldown() {
        val delivery = ClipboardDeliveryTracker()
        delivery.sent("pc", 42L, "test", 1_000L)
        assertFalse(delivery.shouldSend("pc", 42L, "test", 1_001L, false))
        assertTrue(delivery.shouldSend("pc", 42L, "test", 1_001L, true))
        assertTrue(delivery.shouldSend("pc", 0L, "test", 1_001L, true))
    }

    @Test fun onePcReceivingCannotSuppressAnotherPcThatMissedTheCopy() {
        val delivery = ClipboardDeliveryTracker()
        delivery.sent("server", 42L, "test", 1_000L)
        assertFalse(delivery.shouldSend("server", 42L, "test", 2_000L, false))
        assertTrue(delivery.shouldSend("laptop", 42L, "test", 2_000L, false))
    }

    @Test fun reconnectClearsOnlyTheDestinationThatLostItsSession() {
        val delivery = ClipboardDeliveryTracker()
        delivery.sent("server", 42L, "test", 1_000L)
        delivery.sent("laptop", 42L, "test", 1_000L)
        delivery.forget("laptop")
        assertTrue(delivery.shouldSend("laptop", 42L, "test", 2_000L, false))
        assertFalse(delivery.shouldSend("server", 42L, "test", 2_000L, false))
    }

    @Test fun workerAndForegroundDetectorShareABoundedTextCooldown() {
        val delivery = ClipboardDeliveryTracker()
        delivery.sent("pc", 0L, "test", 1_000L)
        assertFalse(delivery.shouldSend("pc", 43L, "test", 1_999L, false))
        assertTrue(delivery.shouldSend("pc", 43L, "test", 2_000L, false))
        assertTrue(delivery.shouldSend("pc", 0L, "different", 1_001L, false))
    }

    @Test fun socketReplacementDoesNotReuseThePreviousSessionsDuplicateMarker() {
        val delivery = ClipboardDeliveryTracker()
        delivery.sent("pc", 42L, "test", 1_000L, session = 100L)
        assertFalse(delivery.shouldSend("pc", 42L, "test", 1_001L, false, session = 100L))
        assertTrue(delivery.shouldSend("pc", 42L, "test", 1_001L, false, session = 101L))
    }
}
