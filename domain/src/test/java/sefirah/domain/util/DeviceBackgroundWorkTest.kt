package sefirah.domain.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DeviceBackgroundWorkTest {
    @Test fun blockedSmsProviderDoesNotHoldTheConnectionLockOrStartDuplicateQueries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val work = DeviceBackgroundWork(scope)
        val lifecycle = Mutex()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val queries = AtomicInteger()
        try {
            lifecycle.withLock {
                work.start("pc") {
                    queries.incrementAndGet()
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    finished.complete(Unit)
                }
            }
            withTimeout(2_000) { entered.await() }
            repeat(100) { work.start("pc") { queries.incrementAndGet() } }
            // DeviceInfo and Disconnect use this lock; clipboard frames follow DeviceInfo.
            withTimeout(2_000) { lifecycle.withLock { assertEquals(1, queries.get()) } }
            assertFalse(finished.isCompleted)
            release.countDown()
            withTimeout(2_000) { finished.await() }
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun reconnectCancelsStaleResultsAndWaitsForTheOldProviderWithoutBlockingTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val work = DeviceBackgroundWork(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val replacementFinished = CompletableDeferred<Unit>()
        var staleResultSent = false
        try {
            work.start("pc") {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                currentCoroutineContext().ensureActive()
                staleResultSent = true
            }
            withTimeout(2_000) { entered.await() }
            work.cancel("pc")
            work.start("pc") { replacementFinished.complete(Unit) }
            assertFalse(replacementFinished.isCompleted)
            release.countDown()
            withTimeout(2_000) { replacementFinished.await() }
            assertFalse(staleResultSent)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun cancellingAllDevicesAlsoCancelsQueuedLoads() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val work = DeviceBackgroundWork(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val newGenerationFinished = CompletableDeferred<Unit>()
        val staleQueries = AtomicInteger()
        try {
            work.start("pc1") {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
            withTimeout(2_000) { entered.await() }
            work.start("pc2") { staleQueries.incrementAndGet() }
            work.cancelAll()
            work.start("pc2") { newGenerationFinished.complete(Unit) }
            release.countDown()
            withTimeout(2_000) { newGenerationFinished.await() }
            assertEquals(0, staleQueries.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
