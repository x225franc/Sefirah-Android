package sefirah.domain.util

import org.junit.Assert.*
import org.junit.Test

class NotificationDeduplicatorTest {
    @Test fun suppressesRepeatsButAllowsChangesAndOtherDevices() {
        val cache = NotificationDeduplicator()
        assertTrue(cache.shouldSend("pc", "notification", "a", 0))
        assertFalse(cache.shouldSend("pc", "notification", "a", 100))
        assertTrue(cache.shouldSend("other", "notification", "a", 100))
        assertTrue(cache.shouldSend("pc", "notification", "b", 200))
        cache.clearDevice("pc")
        assertTrue(cache.shouldSend("pc", "notification", "b", 300))
    }

    @Test fun remembersRemovedNotificationsBriefly() {
        val cache = NotificationDeduplicator()
        cache.shouldSend("pc", "notification", "a", 0)
        cache.removed("notification", 100)
        assertFalse(cache.shouldSend("pc", "notification", "a", 200))
        cache.removed("notification", 300)
        assertTrue(cache.shouldSend("pc", "notification", "a", 120_300))
    }
}
