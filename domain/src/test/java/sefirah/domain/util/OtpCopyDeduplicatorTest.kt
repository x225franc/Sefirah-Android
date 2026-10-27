package sefirah.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpCopyDeduplicatorTest {
    @Test fun deduplicatesPerCodeAndSourceUntilExpiry() {
        val cache = OtpCopyDeduplicator(100)
        assertFalse(cache.isDuplicate("sms", "482913", 0))
        cache.record("sms", "482913", 0)
        assertTrue(cache.isDuplicate("sms", "482913", 99))
        assertFalse(cache.isDuplicate("mail", "482913", 99))
        assertFalse(cache.isDuplicate("sms", "736291", 99))
        assertFalse(cache.isDuplicate("sms", "482913", 100))
    }

    @Test fun clearAllowsRecopyAndCacheIsBounded() {
        val cache = OtpCopyDeduplicator()
        repeat(33) { cache.record("sms", it.toString(), 0) }
        assertFalse(cache.isDuplicate("sms", "0", 1))
        assertTrue(cache.isDuplicate("sms", "32", 1))
        cache.clear()
        assertFalse(cache.isDuplicate("sms", "32", 1))
    }
}
