package sefirah.domain.model

import android.os.SystemClock
import android.util.Log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import sefirah.domain.util.ConnectionHeartbeat
import sefirah.domain.util.HeartbeatAction
import sefirah.domain.util.MessageSerializer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

class DeviceConnection(
    val deviceId: String,
    var sslSocket: SSLSocket? = null,
    var readChannel: ByteReadChannel? = null,
    var writeChannel: ByteWriteChannel? = null,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val closeNotified = AtomicBoolean(false)
    @Volatile private var closeListener: ((DeviceConnection) -> Unit)? = null
    private val heartbeat = ConnectionHeartbeat(monotonicClock())

    /** Monotonic time of the last received message, including heartbeats. */
    val lastActivityMillis: Long get() = heartbeat.lastActivityMillis

    fun heartbeatAction(nowMillis: Long = monotonicClock()): HeartbeatAction = heartbeat.nextAction(nowMillis)

    fun sendMessage(message: SocketMessage) {
        scope.launch { sendMessageAndFlush(message, WRITE_TIMEOUT_MS) }
    }

    /**
     * Completes after this message is flushed, so a Disconnect can precede socket close.
     * The timeout also covers waiting for other writes. A blocked Java stream is unblocked
     * by closing the socket; coroutine cancellation alone cannot interrupt socket writes.
     */
    suspend fun sendMessageAndFlush(message: SocketMessage, timeoutMillis: Long = 1_000L): Boolean {
        if (closed.get()) return false
        val write = scope.async {
            mutex.withLock {
                if (closed.get()) return@withLock false
                val channel = writeChannel ?: return@withLock false
                val jsonMessage = MessageSerializer.serialize(message) ?: return@withLock false
                channel.writeStringUtf8("$jsonMessage\n")
                channel.flush()
                true
            }
        }
        return try {
            val sent = withTimeoutOrNull(timeoutMillis) { write.await() } ?: false
            if (!sent) close()
            sent
        } catch (ex: CancellationException) {
            close()
            throw ex
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to send message to $deviceId", ex)
            close()
            false
        } finally {
            write.cancel()
        }
    }

    /** Starts exactly one reader and reports closure exactly once for this connection. */
    fun startListening(
        getDevice: suspend (String) -> BaseRemoteDevice?,
        onMessage: suspend (BaseRemoteDevice, SocketMessage) -> Unit,
        onClose: (DeviceConnection) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            if (closeListener != null) return
            closeListener = onClose
            val channel = readChannel
            if (closed.get() || channel == null) {
                close()
                return
            }

            scope.launch {
                try {
                    while (isActive && !channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        val message = MessageSerializer.deserialize(line) ?: continue
                        heartbeat.onMessageReceived(monotonicClock())
                        when (message) {
                            is Ping -> sendMessage(Pong)
                            is Pong -> Unit
                            else -> getDevice(deviceId)?.let { onMessage(it, message) }
                        }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.w(TAG, "Read error for $deviceId", ex)
                } finally {
                    close()
                }
            }
        }
    }

    /** Closes every resource even if closing one resource fails. Safe to call repeatedly. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { sslSocket?.close() }
            runCatching { readChannel?.cancel(kotlinx.io.IOException("Connection closed")) }
            runCatching { writeChannel?.cancel(kotlinx.io.IOException("Connection closed")) }
            scope.cancel()
            sslSocket = null
            readChannel = null
            writeChannel = null
        }
        notifyClosed()
    }

    private fun notifyClosed() {
        val listener = closeListener ?: return
        if (closeNotified.compareAndSet(false, true)) listener(this)
    }

    private companion object {
        const val TAG = "DeviceConnection"
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
