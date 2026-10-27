package sefirah.domain.model

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.util.MessageSerializer
import java.util.concurrent.atomic.AtomicInteger

class DeviceConnectionTest {
    @Test fun disconnectIsFlushedBeforeTheCallerClosesTheConnection() = runBlocking {
        val output = ByteChannel()
        val connection = DeviceConnection("pc", writeChannel = output, monotonicClock = { 0L })
        try {
            assertTrue(connection.sendMessageAndFlush(Disconnect))
            assertEquals(MessageSerializer.serialize(Disconnect), withTimeout(2_000) { output.readUTF8Line() })
        } finally {
            connection.close()
        }
    }

    @Test fun blockedWriterTimesOutAndClosesTheConnection() = runBlocking {
        val output = object : ByteWriteChannel by ByteChannel() {
            override suspend fun flush() { awaitCancellation() }
        }
        val connection = DeviceConnection("pc", writeChannel = output, monotonicClock = { 0L })
        try {
            val sent = withTimeout(2_000) {
                connection.sendMessageAndFlush(Disconnect, 50)
            }
            assertFalse(sent)
            assertFalse(connection.sendMessageAndFlush(Disconnect))
        } finally {
            connection.close()
        }
    }

    @Test fun disconnectIsHandledBeforeEofAndDuplicateStartDoesNotReplaceReader() = runBlocking {
        val input = ByteChannel()
        val connection = DeviceConnection("pc", readChannel = input, monotonicClock = { 0L })
        val closeCount = AtomicInteger()
        val handled = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val device = object : BaseRemoteDevice() {
            override val deviceId = "pc"
            override val deviceName = "PC"
        }
        try {
            connection.startListening(
                getDevice = { device },
                onMessage = { _, message ->
                    assertEquals(Disconnect, message)
                    delay(30)
                    handled.complete(Unit)
                },
                onClose = {
                    closeCount.incrementAndGet()
                    assertTrue(handled.isCompleted)
                    closed.complete(Unit)
                },
            )
            connection.startListening({ device }, { _, _ -> error("Duplicate reader") }, { error("Duplicate close") })
            input.writeStringUtf8("${MessageSerializer.serialize(Disconnect)}\n")
            input.flushAndClose()
            withTimeout(2_000) { closed.await() }
            connection.close()
            assertEquals(1, closeCount.get())
        } finally {
            connection.close()
        }
    }

    @Test fun closingBeforeListenerRegistrationStillNotifiesExactlyOnce() {
        val connection = DeviceConnection("pc", monotonicClock = { 0L })
        val closeCount = AtomicInteger()
        connection.close()
        connection.startListening({ null }, { _, _ -> }, { closeCount.incrementAndGet() })
        connection.close()
        assertEquals(1, closeCount.get())
    }
}
