package sefirah.network

import android.util.Log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import sefirah.domain.interfaces.SocketFactory
import sefirah.network.util.SslHelper
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.net.InetSocketAddress as JavaInetSocketAddress

@Singleton
class SocketFactoryImpl @Inject constructor() : SocketFactory {
    val selectorManager = SelectorManager(Dispatchers.IO)

    override suspend fun tcpClientSocket(address: String, port: Int, certificate: ByteArray?): SSLSocket? {
        var socket: SSLSocket? = null
        return try {
            Log.d(TAG, "Connecting to $address:$port")
            val sslContext = SslHelper.sslContext(certificate)
            withContext(Dispatchers.IO) {
                (sslContext.socketFactory.createSocket() as SSLSocket).apply {
                    socket = this
                    connect(JavaInetSocketAddress(address, port), 3000)
                    soTimeout = 3000
                    startHandshake()
                    soTimeout = 0
                }
            }.also {
                Log.d(TAG, "Connected to ${it.remoteSocketAddress}")
            }
        } catch (e: Exception) {
            runCatching { socket?.close() }
            if (e is CancellationException) throw e
            Log.e(TAG, "Connection failed to $address:$port", e)
            null
        }
    }

    override suspend fun tcpServerSocket(range: IntRange, certificate: ByteArray?): SSLServerSocket? {
        val sslContext = SslHelper.sslContext(certificate)

        range.forEach { port ->
            try {
                val serverSocket = withContext(Dispatchers.IO) {
                    sslContext.serverSocketFactory.createServerSocket(port)
                } as SSLServerSocket

                serverSocket.needClientAuth = true

                Log.d(TAG, "Server socket created on ${serverSocket.inetAddress.address}:${port}")
                return serverSocket
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create server socket on port $port", e)
            }
        }
        Log.e(TAG, "Server socket creation failed")
        return null
    }

    override suspend fun udpSocket(port: Int): BoundDatagramSocket {
        return try {
            aSocket(selectorManager).udp().bind(InetSocketAddress("0.0.0.0", port)) {
                reuseAddress = true
                broadcast = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP server", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "SocketFactory"
    }
}
