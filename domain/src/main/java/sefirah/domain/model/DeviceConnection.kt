package sefirah.domain.model

import android.util.Log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sefirah.domain.util.MessageSerializer
import javax.net.ssl.SSLSocket

class DeviceConnection(
    val deviceId: String,
    var sslSocket: SSLSocket? = null,
    var readChannel: ByteReadChannel? = null,
    var writeChannel: ByteWriteChannel? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var listeningJob: Job? = null

    /** Wall-clock time of the last message (including heartbeats) received on this connection. */
    @Volatile
    var lastActivityMillis: Long = System.currentTimeMillis()
        private set

    fun sendMessage(message: SocketMessage) {
        scope.launch {
            mutex.withLock {
                try {
                    writeChannel?.let { channel ->
                        MessageSerializer.serialize(message)?.let { jsonMessage ->
                            channel.writeStringUtf8(jsonMessage)
                            channel.writeStringUtf8("\n")
                            channel.flush()
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to send message to $deviceId", ex)
                }
            }
        }
    }

    /**
     * Starts listening for messages from the device.
     * @param scope The coroutine scope to launch the listener in
     * @param getDevice Function to get the device by deviceId
     * @param onMessage Callback to handle received messages
     * @param onClose Callback when connection closes
     */
    fun startListening(
        getDevice: suspend (String) -> BaseRemoteDevice?,
        onMessage: (BaseRemoteDevice, SocketMessage) -> Unit,
        onClose: (DeviceConnection) -> Unit,
    ) {
        // Stop existing listener if any
        listeningJob?.cancel()

        val channel = readChannel ?: return

        listeningJob = scope.launch {
            try {
                while (isActive && !channel.isClosedForRead) {
                    try {
                        channel.readUTF8Line()?.let { line ->
                            MessageSerializer.deserialize(line)?.let { socketMessage ->
                                lastActivityMillis = System.currentTimeMillis()

                                if (socketMessage is Ping) {
                                    sendMessage(Pong)
                                    return@let
                                }
                                if (socketMessage is Pong) {
                                    return@let
                                }

                                val device = getDevice(deviceId) ?: return@let
                                onMessage(device, socketMessage)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Read error for $deviceId", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session error for $deviceId", e)
            } finally {
                onClose(this@DeviceConnection)
            }
        }
    }

    /**
     * Closes all connection resources and stops listening.
     */
    fun close() {
        listeningJob?.cancel()
        listeningJob = null
        scope.cancel()
        try {
            sslSocket?.close()
            readChannel?.cancel(kotlinx.io.IOException())
            writeChannel?.cancel(kotlinx.io.IOException())
        } catch (_: Exception) {
        }
        sslSocket = null
        readChannel = null
        writeChannel = null
    }

    private companion object {
        const val TAG = "DeviceConnection"
    }
}
