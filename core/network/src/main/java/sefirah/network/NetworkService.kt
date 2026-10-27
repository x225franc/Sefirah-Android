package sefirah.network

import android.annotation.SuppressLint
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.streams.asByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sefirah.FeatureManager
import sefirah.actions.ActionFeature
import sefirah.apps.AppListHandler
import sefirah.clipboard.ClipboardFeature
import sefirah.common.notifications.AppNotifications
import sefirah.common.notifications.NotificationCenter
import sefirah.common.util.drawableToBase64Compressed
import sefirah.common.util.isContactsPermissionGranted
import sefirah.communication.bluetooth.BluetoothPairingHandler
import sefirah.communication.sms.SmsFeature
import sefirah.communication.utils.ContactsHelper
import sefirah.communication.utils.TelephonyHelper
import sefirah.database.AppRepository
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.domain.model.AddressEntry
import sefirah.domain.model.Authentication
import sefirah.domain.model.BaseRemoteDevice
import sefirah.domain.model.ClipboardInfo
import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.ConnectionState
import sefirah.domain.model.DeviceConnection
import sefirah.domain.model.DeviceInfo
import sefirah.domain.model.Disconnect
import sefirah.domain.model.DiscoveredDevice
import sefirah.domain.model.PairMessage
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.Ping
import sefirah.domain.model.PendingDeviceApproval
import sefirah.domain.model.SocketMessage
import sefirah.domain.util.MessageSerializer
import sefirah.domain.util.ConnectionRetryPolicy
import sefirah.domain.util.HeartbeatAction
import sefirah.domain.util.acceptIncomingConnection
import sefirah.domain.util.canResumeConnection
import sefirah.domain.util.canReplaceSilentConnection
import sefirah.domain.util.prepareConnectionReplacement
import sefirah.media.PlaybackFeature
import sefirah.media.RemotePlaybackFeature
import sefirah.network.extensions.cancelPairingVerificationNotification
import sefirah.network.extensions.handleMessage
import sefirah.network.extensions.startForegroundNotification
import sefirah.network.extensions.updateNotification
import sefirah.network.extensions.showPairingVerificationNotification
import sefirah.network.util.SslHelper
import sefirah.notification.NotificationFeature
import sefirah.playsound.PlaySoundFeature
import sefirah.status.DeviceControlHandler
import sefirah.status.RemoteDeviceStatusFeature
import sefirah.transfer.FileTransferService
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLSocket
import kotlin.properties.Delegates
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class NetworkService : Service() {
    @Inject lateinit var socketFactory: SocketFactory

    @Inject lateinit var appRepository: AppRepository

    @Inject lateinit var notificationFeature: NotificationFeature

    @Inject lateinit var notificationCenter: NotificationCenter

    @Inject lateinit var clipboardFeature: ClipboardFeature

    @Inject lateinit var networkDiscovery: NetworkDiscovery

    @Inject lateinit var connectionWatchdog: ConnectionWatchdog

    @Inject lateinit var remotePlaybackFeature: RemotePlaybackFeature

    @Inject lateinit var playbackFeature: PlaybackFeature

    @Inject lateinit var preferencesRepository: PreferencesRepository

    @Inject lateinit var smsFeature: SmsFeature

    @Inject lateinit var actionFeature: ActionFeature

    @Inject lateinit var remoteDeviceStatusFeature: RemoteDeviceStatusFeature

    @Inject lateinit var deviceManager: DeviceManager

    @Inject lateinit var fileTransferService: FileTransferService

    @Inject lateinit var featureManager: FeatureManager

    @Inject lateinit var deviceControlHandler: DeviceControlHandler

    @Inject lateinit var appListHandler: AppListHandler

    @Inject lateinit var bluetoothPairingHandler: BluetoothPairingHandler

    @Inject lateinit var playSoundFeature: PlaySoundFeature

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()

    fun emitPendingApproval(device: PendingDeviceApproval) {
        deviceManager.setPendingApproval(device)
    }

    fun clearPendingApproval(deviceId: String) {
        deviceManager.clearPendingApproval(deviceId)
    }

    inner class LocalBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    lateinit var notificationBuilder: NotificationCompat.Builder

    private var tcpServerSocket: javax.net.ssl.SSLServerSocket? = null
    private var serverAcceptJob: Job? = null

    private val connections = java.util.concurrent.ConcurrentHashMap<String, DeviceConnection>()
    private val connectionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val outgoingConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, ConnectionAttempt>()
    private val retryPolicies = java.util.concurrent.ConcurrentHashMap<String, ConnectionRetryPolicy>()
    private val manuallyDisconnected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var stopping = false

    private class ConnectionAttempt {
        var task: Deferred<Unit>? = null
        @Volatile var socket: SSLSocket? = null
        fun cancel() {
            task?.cancel()
            runCatching { socket?.close() }
        }
    }

    private fun connectionLock(deviceId: String) = connectionLocks.getOrPut(deviceId) { Mutex() }
    private fun retryPolicy(deviceId: String) = retryPolicies.getOrPut(deviceId) { ConnectionRetryPolicy() }

    private var tcpServerPort by Delegates.notNull<Int>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.CONNECT.name -> {
                val connectionDetails = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONNECTION_DETAILS, ConnectionDetails::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONNECTION_DETAILS)
                }

                scope.launch {
                    if (connectionDetails != null) {
                        // Check if device is already paired - if so, use connectPaired, otherwise connectTo
                        val pairedDevice = deviceManager.getPairedDevice(connectionDetails.deviceId)
                        if (pairedDevice != null) {
                            connectPaired(pairedDevice, userInitiated = true)
                        } else {
                            connectTo(connectionDetails)
                        }
                    } else {
                        val lastConnectedDevice = deviceManager.pairedDevices.value
                            .maxByOrNull { it.lastConnected ?: 0L }
                        
                        lastConnectedDevice?.let { device ->
                            connectPaired(device, userInitiated = true)
                        }
                    }
                }
            }

            Actions.APPROVE_DEVICE.name -> {
                scope.launch {
                    intent.getStringExtra(DEVICE_ID_EXTRA)?.let {
                        approveDeviceConnection(it)
                    }
                }
            }

            Actions.REJECT_DEVICE.name -> {
                scope.launch {
                    intent.getStringExtra(DEVICE_ID_EXTRA)?.let {
                        rejectDeviceConnection(it)
                    }
                }
            }

            Actions.DISCONNECT.name -> {
                scope.launch {
                    val deviceId = intent.getStringExtra(DEVICE_ID_EXTRA)
                    if (deviceId != null) {
                        disconnect(deviceId)
                    } else {
                        val lastConnectedDevice = deviceManager.pairedDevices.value
                            .maxByOrNull { it.lastConnected ?: 0L }

                        lastConnectedDevice?.let { device ->
                            disconnect(device.deviceId)
                        }
                    }
                }
            }

            Actions.CANCEL_TRANSFER.name -> {
                val transferId = intent.getStringExtra(FileTransferService.EXTRA_TRANSFER_ID)
                    ?: return START_STICKY
                fileTransferService.cancelTransfer(transferId)
            }

            Actions.SEND_FILES.name -> {
                val deviceId = intent.getStringExtra(DEVICE_ID_EXTRA) ?: return START_STICKY
                val clipData = intent.clipData ?: return START_STICKY
                val uris = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
                if (uris.isNotEmpty()) {
                    fileTransferService.sendFiles(deviceId, uris)
                }
            }
            Actions.SEND_CLIPBOARD.name -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    clipboardFeature.sendClipboard(ClipboardInfo("text/plain", text))
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        deviceControlHandler.start()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))

        startForegroundNotification(AppNotifications.DEVICE_CONNECTION_ID)

        scope.launch {
            tcpServerPort = startTcpServer()
            launch(start = CoroutineStart.UNDISPATCHED) { networkDiscovery.initialize(tcpServerPort) }
            connectionWatchdog.start(scope, { connectPaired(it) }, ::dropConnectedDevices, ::sendHeartbeatsAndPruneStale)
        }

        scope.launch {
            deviceManager.pairedDevices.collect { pairedDevices ->
                // Get connected devices
                val connectedDevices = pairedDevices.filter { it.connectionState.isConnected }

                if (connectedDevices.isNotEmpty()) {
                    val deviceNames = connectedDevices.joinToString(", ") { it.deviceName }
                    // Only pass deviceId for disconnect action when single device connected
                    val deviceId = if (connectedDevices.size == 1) connectedDevices.first().deviceId else null
                    updateNotification(deviceNames, deviceId, AppNotifications.DEVICE_CONNECTION_ID)
                } else {
                    updateNotification(null, null, AppNotifications.DEVICE_CONNECTION_ID)
                }
            }
        }
    }

    suspend fun startTcpServer(): Int {
        try {
            tcpServerSocket = socketFactory.tcpServerSocket(PORT_RANGE)
                ?: throw IllegalStateException("Failed to create TCP server socket")
            val port = tcpServerSocket!!.localPort
            Log.d(TAG, "TCP server started successfully on port $port")

            // Start accepting connections
            serverAcceptJob = scope.launch {
                while (tcpServerSocket?.isClosed == false) {
                    try {
                        val sslSocket = withContext(Dispatchers.IO) {
                            tcpServerSocket?.accept() as? SSLSocket
                        } ?: break

                        Log.d(TAG, "Accepted incoming connection from ${sslSocket.remoteSocketAddress}")
                        launch { handleIncomingConnection(sslSocket) }
                    } catch (e: Exception) {
                        if (tcpServerSocket?.isClosed == true) {
                            Log.d(TAG, "Server socket closed, stopping acceptance loop")
                            break
                        }
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
                Log.d(TAG, "TCP server stopped")
            }
            return port
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TCP server", e)
            throw e
        }
    }

    private suspend fun handleIncomingConnection(sslSocket: SSLSocket) {
        try {
            withContext(Dispatchers.IO) {
                sslSocket.soTimeout = 10_000
                sslSocket.startHandshake()
                sslSocket.soTimeout = 0
            }

            val readChannel = sslSocket.inputStream.toByteReadChannel()
            val writeChannel = sslSocket.outputStream.asByteWriteChannel()

            val authMessage = withTimeoutOrNull(10_000.milliseconds) {
                readChannel.readUTF8Line()?.let {
                    val message = MessageSerializer.deserialize(it)
                    message as? Authentication
                }
            } ?: run {
                Log.e(TAG, "Timeout waiting for Authentication message from incoming connection")
                sslSocket.close()
                return
            }
            val address = (sslSocket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""

            Log.d(TAG, "Received Authentication from ${authMessage.deviceId}: ${authMessage.deviceName}")

            val certificate = SslHelper.verifySessionCertificate(sslSocket.session, authMessage.publicKey)
            if (certificate == null) {
                Log.w(TAG, "No client certificate or public key mismatch with TLS peer; rejecting connection")
                sslSocket.close()
                return
            }

            when (val device = deviceManager.getDevice(authMessage.deviceId)) {
                is PairedDevice -> authenticatePairedDevice(
                    sslSocket,
                    readChannel,
                    writeChannel,
                    authMessage,
                    device,
                    address,
                    certificate
                )
                else -> authenticateNewDevice(
                    sslSocket,
                    readChannel,
                    writeChannel,
                    authMessage,
                    address,
                    certificate,
                    device
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming connection", e)
            sslSocket.close()
        }
    }

    private suspend fun authenticatePairedDevice(
        sslSocket: SSLSocket,
        readChannel: ByteReadChannel,
        writeChannel: ByteWriteChannel,
        authMessage: Authentication,
        device: PairedDevice,
        address: String,
        certificate: X509Certificate
    ) {

        if (!certificate.encoded.contentEquals(device.certificate)) {
            Log.w(TAG, "Certificate does not match pinned cert for paired device ${authMessage.deviceId}")
            sslSocket.close()
            return
        }

        connectionLock(device.deviceId).withLock {
            val current = deviceManager.getPairedDevice(device.deviceId) ?: return@withLock sslSocket.close()
            if (!canResumeConnection(
                    manuallyDisconnected.contains(device.deviceId) || current.connectionState.isForcedDisconnect,
                    authMessage.userInitiated,
                )) {
                sslSocket.close()
                return@withLock
            }

            val existing = connections[device.deviceId]
            val replaceSilent = existing != null && canReplaceSilentConnection(
                SystemClock.elapsedRealtime() - existing.lastActivityMillis
            )

            if (stopping ||
                (!replaceSilent && !acceptIncomingConnection(
                    deviceManager.localDevice.deviceId, device.deviceId,
                    attempts.containsKey(device.deviceId),
                    existing?.let { outgoingConnections.contains(device.deviceId) },
                ))
            ) {
                sslSocket.close()
                return@withLock
            }

            val connection = DeviceConnection(device.deviceId, sslSocket, readChannel, writeChannel)
            val updatedDevice = current.copy(
                deviceName = authMessage.deviceName,
                lastConnected = System.currentTimeMillis(),
                addresses = if (current.addresses.none { it.address == address }) {
                    current.addresses + AddressEntry(address)
                } else current.addresses,
                address = address,
                connectionState = ConnectionState.Connected,
            )
            val adopted = prepareConnectionReplacement(
                candidate = connection,
                authenticate = { it.sendMessageAndFlush(authenticationMessage()) },
                commit = { candidate ->
                    withContext(NonCancellable) {
                        attempts.remove(device.deviceId)?.cancel()
                        manuallyDisconnected.remove(device.deviceId)
                        retryPolicy(device.deviceId).reset()
                        deviceManager.addOrUpdatePairedDevice(updatedDevice)
                        installConnection(device.deviceId, candidate, outgoing = false)
                    }
                },
                discard = { it.close() },
            )
            if (!adopted) return@withLock
            finalizeConnection(updatedDevice, false)
        }
    }

    private suspend fun authenticateNewDevice(
        sslSocket: SSLSocket,
        readChannel: ByteReadChannel,
        writeChannel: ByteWriteChannel,
        authMessage: Authentication,
        address: String,
        certificate: X509Certificate,
        device: BaseRemoteDevice?
    ) {
        if (device != null) {
            deviceManager.removeDiscoveredDevice(device.deviceId)
        }

        sendAuthMessage(writeChannel)

        val verificationCode = SslHelper.getVerificationCode(certificate, SslHelper.certificate)

        val newDevice = DiscoveredDevice(
            authMessage.deviceId,
            authMessage.deviceName,
            address,
            listOfNotNull(address),
            certificate,
            verificationCode
        )

        val connection = DeviceConnection(newDevice.deviceId, sslSocket, readChannel, writeChannel)
        setConnection(newDevice.deviceId, connection)
        deviceManager.addOrUpdateDiscoveredDevice(newDevice)
    }

    suspend fun connectPaired(device: PairedDevice, userInitiated: Boolean = false) {
        val task = connectionLock(device.deviceId).withLock {
            val current = deviceManager.getPairedDevice(device.deviceId) ?: return@withLock null
            if (stopping || connections.containsKey(device.deviceId) || attempts.containsKey(device.deviceId)) return@withLock null
            val policy = retryPolicy(device.deviceId)
            if (!userInitiated && (
                current.connectionState.isForcedDisconnect ||
                    manuallyDisconnected.contains(device.deviceId) ||
                    !policy.canRetry(SystemClock.elapsedRealtime())
                )
            ) {
                return@withLock null
            }
            if (userInitiated) {
                manuallyDisconnected.remove(device.deviceId)
                policy.reset()
            }
            // Discovery may supply a new port/address, but never let its old state overwrite a user action.
            val target = current.copy(
                addresses = (device.addresses + current.addresses).distinctBy { it.address },
                port = device.port ?: current.port,
                connectionState = ConnectionState.Connecting(device.deviceId),
            )
            deviceManager.addOrUpdatePairedDevice(target)
            val attempt = ConnectionAttempt()
            attempts[device.deviceId] = attempt
            scope.async(start = CoroutineStart.LAZY) {
                connectPairedAttempt(target, attempt, userInitiated)
            }.also { attempt.task = it }
        }
        try {
            task?.await()
        } catch (e: CancellationException) {
            // Cancelling a dial from the button must not cancel the watchdog or discovery collector.
            if (task?.isCancelled != true) throw e
        }
    }

    private suspend fun connectPairedAttempt(device: PairedDevice, attempt: ConnectionAttempt, userInitiated: Boolean) {
        var connection: DeviceConnection? = null
        var installed = false
        try {
            val port = device.port ?: PORT_RANGE.first
            val sslSocket = run {
                for (ip in device.getAddressesToTry().distinct()) {
                    socketFactory.tcpClientSocket(ip, port, device.certificate)?.let { return@run it }
                }
                null
            } ?: throw java.io.IOException("All connection attempts failed")
            attempt.socket = sslSocket
            val readChannel = sslSocket.inputStream.toByteReadChannel()
            val writeChannel = sslSocket.outputStream.asByteWriteChannel()
            val candidate = DeviceConnection(device.deviceId, sslSocket, readChannel, writeChannel)
            connection = candidate
            check(candidate.sendMessageAndFlush(authenticationMessage(userInitiated))) { "Unable to send Authentication" }
            val authentication = withTimeoutOrNull(10_000L) {
                readChannel.readUTF8Line()?.let { MessageSerializer.deserialize(it) as? Authentication }
            } ?: throw java.io.IOException("No Authentication response")
            val certificate = SslHelper.verifySessionCertificate(sslSocket.session, authentication.publicKey)
            check(authentication.deviceId == device.deviceId && certificate != null && certificate.encoded.contentEquals(device.certificate)) {
                "Authentication identity does not match paired device"
            }

            connectionLock(device.deviceId).withLock {
                if (stopping || attempts[device.deviceId] !== attempt) return@withLock
                val current = deviceManager.getPairedDevice(device.deviceId) ?: return@withLock
                if (current.connectionState.isForcedDisconnect) return@withLock
                attempts.remove(device.deviceId)
                val updatedDevice = current.copy(
                    lastConnected = System.currentTimeMillis(),
                    connectionState = ConnectionState.Connected,
                    address = (sslSocket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress,
                )
                retryPolicy(device.deviceId).reset()
                deviceManager.addOrUpdatePairedDevice(updatedDevice)
                installConnection(device.deviceId, candidate, outgoing = true)
                installed = true
                finalizeConnection(updatedDevice, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Connection to ${device.deviceId} failed", e)
        } finally {
            if (!installed) {
                connection?.close()
                runCatching { attempt.socket?.close() }
            }
            withContext(NonCancellable) {
                connectionLock(device.deviceId).withLock {
                    if (attempts.remove(device.deviceId, attempt)) {
                        retryPolicy(device.deviceId).failed(SystemClock.elapsedRealtime())
                        deviceManager.getPairedDevice(device.deviceId)?.let { current ->
                            deviceManager.addOrUpdatePairedDevice(current.copy(connectionState = ConnectionState.Disconnected(current.connectionState.isForcedDisconnect)))
                        }
                    }
                }
            }
        }
    }


    suspend fun connectTo(connectionDetails: ConnectionDetails) {
        removeConnection(connectionDetails.deviceId)
        deviceManager.removeDiscoveredDevice(connectionDetails.deviceId)

        var sslSocket: SSLSocket? = null
        var installed = false
        try {
            sslSocket = run {
                connectionDetails.prefAddress?.let { prefAddress ->
                    socketFactory.tcpClientSocket(prefAddress, connectionDetails.port)?.let { return@run it }
                }
                for (ip in connectionDetails.addresses) {
                    socketFactory.tcpClientSocket(ip, connectionDetails.port)?.let { return@run it }
                }
                null
            } ?: run {
                Log.e(TAG, "All connection attempts failed")
                return
            }
            val socket = sslSocket

            val readChannel = socket.inputStream.toByteReadChannel()
            val writeChannel = socket.outputStream.asByteWriteChannel()

            sendAuthMessage(writeChannel)

            val authResponse = try {
                withTimeoutOrNull(3000.milliseconds) {
                    readChannel.readUTF8Line()?.let {
                        MessageSerializer.deserialize(it) as? Authentication
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while reading Authentication message: ${e.message}", e)
                null
            } ?: run {
                Log.e(TAG, "Timeout or null response waiting for Authentication message")
                readChannel.cancel()
                socket.close()
                return
            }

            val certificate = SslHelper.verifySessionCertificate(socket.session, authResponse.publicKey)
            if (certificate == null) {
                Log.e(TAG, "No server certificate or public key mismatch with TLS peer; rejecting")
                readChannel.cancel()
                socket.close()
                return
            }

            val address = (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""

            val verificationCode = SslHelper.getVerificationCode(certificate, SslHelper.certificate)

            val connectedDevice = DiscoveredDevice(
                authResponse.deviceId,
                authResponse.deviceName,
                address,
                connectionDetails.addresses,
                certificate,
                verificationCode,
                connectionDetails.port
            )

            val connection = DeviceConnection(connectedDevice.deviceId, socket, readChannel, writeChannel)
            setConnection(connectedDevice.deviceId, connection)
            installed = true

            deviceManager.addOrUpdateDiscoveredDevice(connectedDevice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in connectTo", e)
        } finally {
            if (!installed) {
                runCatching { sslSocket?.close() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendDeviceInfo(device: PairedDevice) {
        try {
            val wallpaper = try {
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                wallpaperManager.drawable?.let { drawable ->
                    drawableToBase64Compressed(drawable)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to access wallpaper", e)
                null
            }

            val localPhoneNumbers = try {
                TelephonyHelper.getAllPhoneNumbers(this).map { it.toDto() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get local phone numbers", e)
                emptyList()
            }

            val deviceInfo = DeviceInfo(deviceManager.localDevice.deviceName, wallpaper, localPhoneNumbers)
            sendMessage(device.deviceId, deviceInfo)
            Log.d(TAG, "DeviceInfo sent to ${device.deviceId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DeviceInfo to ${device.deviceId}", e)
        }
    }

    suspend fun disconnect(deviceId: String) {
        connectionLock(deviceId).withLock {
            val device = deviceManager.getPairedDevice(deviceId) ?: return@withLock
            disconnectDeviceLocked(device, forcedDisconnect = true, notifyPeer = true)
        }
    }

    suspend fun disconnectDevice(device: PairedDevice, forcedDisconnect: Boolean = false) {
        connectionLock(device.deviceId).withLock {
            val current = deviceManager.getPairedDevice(device.deviceId) ?: return@withLock
            disconnectDeviceLocked(current, forcedDisconnect)
        }
    }

    private suspend fun disconnectDeviceLocked(device: PairedDevice, forcedDisconnect: Boolean = false, notifyPeer: Boolean = false) = withContext(NonCancellable) {
        Log.i(TAG, "Disconnected ${device.deviceName}")
        val id = device.deviceId
        if (forcedDisconnect) manuallyDisconnected.add(id)
        attempts.remove(id)?.cancel()
        val connection = connections.remove(id)
        outgoingConnections.remove(id)
        // Publish the user's choice before closing: EOF and discovery cannot undo it.
        deviceManager.addOrUpdatePairedDevice(device.copy(
            connectionState = ConnectionState.Disconnected(forcedDisconnect || device.connectionState.isForcedDisconnect)
        ))
        try {
            if (notifyPeer) connection?.sendMessageAndFlush(Disconnect)
        } finally {
            connection?.close()
            featureManager.onDisconnect(id)
        }
    }

    suspend fun handlePairMessage(device: DiscoveredDevice, message: PairMessage) {
        if (message.pair) {
            // Check if this is a response to our pairing request
            if (device.isPairing) {
                val pairedDevice = PairedDevice(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    avatar = null,
                    lastConnected = System.currentTimeMillis(),
                    addresses = device.addresses.map { AddressEntry(it) },
                    address = device.address,
                    connectionState = ConnectionState.Connected,
                    certificate = device.certificate.encoded,
                    port = device.port,
                )

                deviceManager.removeDiscoveredDevice(device.deviceId)
                deviceManager.addOrUpdatePairedDevice(pairedDevice)
                Log.d(TAG, "Created PairedDevice ${device.deviceId} after pairing approval")

                finalizeConnection(pairedDevice, true)
            } else {
                // Remote device is requesting to pair with us
                val pendingApproval = PendingDeviceApproval(
                    device.deviceId,
                    device.deviceName,
                    device.verificationCode
                )
                emitPendingApproval(pendingApproval)
                showPairingVerificationNotification(pendingApproval)
            }
        } else {
            // Pairing rejected
            if (device.isPairing) {
                // They rejected our pairing request - update isPairing to false
                val updatedDevice = device.copy(isPairing = false)
                deviceManager.addOrUpdateDiscoveredDevice(updatedDevice)
                Log.d(TAG, "Pairing rejected by ${device.deviceId}")
            }
        }
    }

    suspend fun handleDeviceInfo(deviceInfo: DeviceInfo, device: PairedDevice) {
        val current = deviceManager.getPairedDevice(device.deviceId) ?: return
        val updatedDevice = current.copy(deviceName = deviceInfo.deviceName, avatar = deviceInfo.avatar)
        deviceManager.addOrUpdatePairedDevice(updatedDevice)
        Log.d(TAG, "DeviceInfo updated for ${device.deviceId}")
    }

    fun broadcastMessage(message: SocketMessage) {
        deviceManager.pairedDevices.value.forEach { device ->
            if (device.connectionState.isConnected) {
                sendMessage(device.deviceId, message)
            }
        }
    }

    /**
     * Starts listening for messages from a device connection.
     */
    private fun startListeningForDevice(connection: DeviceConnection) {
        connection.startListening(
            getDevice = { deviceManager.getDevice(it) },
            onMessage = { device, message ->
                if (message is Disconnect || message is DeviceInfo || message is PairMessage) {
                    connectionLock(connection.deviceId).withLock {
                        if (connections[connection.deviceId] === connection) {
                            val current = deviceManager.getDevice(connection.deviceId)
                            if (message is Disconnect && current is PairedDevice) {
                                disconnectDeviceLocked(current, forcedDisconnect = true)
                            } else if (current != null) {
                                handleMessage(current, message)
                            }
                        }
                    }
                } else {
                    scope.launch {
                        if (connections[connection.deviceId] === connection) handleMessage(device, message)
                    }
                }
            },
            onClose = { closed ->
                scope.launch {
                    val id = closed.deviceId
                    connectionLock(id).withLock {
                        if (connections[id] === closed) {
                            when (val device = deviceManager.getDevice(id)) {
                                is PairedDevice -> disconnectDeviceLocked(device)
                                else -> {
                                    removeConnection(id)
                                    deviceManager.removeDiscoveredDevice(id)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    suspend fun finalizeConnection(
        device: PairedDevice,
        isNewDevice: Boolean,
    ) {
        sendDeviceInfo(device)
        deviceControlHandler.sendDeviceStatus(device.deviceId)

        if (isNewDevice) {
            appListHandler.sendInstalledApps(device.deviceId)
            sendContacts(device)
        }

        networkDiscovery.saveCurrentNetworkAsTrusted()

        featureManager.onConnect(device.deviceId)
    }

    private fun authenticationMessage(userInitiated: Boolean = false): Authentication {
        val localDevice = deviceManager.localDevice
        return Authentication(
            localDevice.deviceId,
            localDevice.deviceName,
            SslHelper.publicKeyString,
            localDevice.model,
            userInitiated,
        )
    }

    private suspend fun sendAuthMessage(writeChannel: ByteWriteChannel) {
        val jsonMessage = MessageSerializer.serialize(authenticationMessage())
        writeChannel.writeStringUtf8("$jsonMessage\n")
        writeChannel.flush()
    }

    private fun sendContacts(device: PairedDevice) {
        try {
            if (!isContactsPermissionGranted(this)) return

            ContactsHelper().getAllContacts(this).forEach { contact ->
                sendMessage(device.deviceId, contact)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending contacts", e)
        }
    }

    /** Closes every live connection without telling the peer (the network under it is gone). */
    private suspend fun dropConnectedDevices() {
        retryPolicies.keys.forEach { id -> connectionLock(id).withLock { retryPolicy(id).reset() } }
        deviceManager.pairedDevices.value
            .filter { it.connectionState.isConnectedOrConnecting }
            .forEach { device ->
                Log.i(TAG, "Dropping stale connection to ${device.deviceName}")
                disconnectDevice(device)
            }
    }

    /**
     * Desktop normally supplies the heartbeat. Probe only after prolonged silence and allow
     * a full response window before dropping the socket, including after Android wakes up.
     */
    private suspend fun sendHeartbeatsAndPruneStale() {
        deviceManager.pairedDevices.value
            .filter { it.connectionState.isConnected }
            .forEach { device ->
                connectionLock(device.deviceId).withLock {
                    val current = deviceManager.getPairedDevice(device.deviceId) ?: return@withLock
                    if (!current.connectionState.isConnected) return@withLock
                    val connection = connections[device.deviceId]
                    when (connection?.heartbeatAction()) {
                        null, HeartbeatAction.EXPIRED -> disconnectDeviceLocked(current)
                        HeartbeatAction.PROBE -> connection.sendMessage(Ping)
                        HeartbeatAction.NONE -> Unit
                    }
                }
            }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            connectionWatchdog.retryNow("screen on")
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            connectionWatchdog.retryNow("wifi state changed")
        }
    }

    suspend fun approveDeviceConnection(deviceId: String) {
        clearPendingApproval(deviceId)
        cancelPairingVerificationNotification(deviceId)

        val discoveredDevice = deviceManager.getDiscoveredDevice(deviceId) ?: return

        val pairedDevice = PairedDevice(
            deviceId = discoveredDevice.deviceId,
            deviceName = discoveredDevice.deviceName,
            avatar = null,
            lastConnected = System.currentTimeMillis(),
            addresses = discoveredDevice.addresses.map { AddressEntry(it) },
            connectionState = ConnectionState.Connected,
            port = discoveredDevice.port,
            address = discoveredDevice.address,
            certificate = discoveredDevice.certificate.encoded
        )

        // Send pairing message before removing DiscoveredDevice
        sendMessage(deviceId, PairMessage(true))

        deviceManager.removeDiscoveredDevice(deviceId)
        deviceManager.addOrUpdatePairedDevice(pairedDevice)
        Log.d(TAG, "Approved $deviceId")

        finalizeConnection(pairedDevice, true)
    }

    suspend fun rejectDeviceConnection(deviceId: String) {
        val remoteDevice = deviceManager.getDiscoveredDevice(deviceId)

        if (remoteDevice != null) {
            // Send rejection message
            sendMessage(deviceId, PairMessage(pair = false))
        }

        // Clear pending approval and cancel notification
        clearPendingApproval(deviceId)
        cancelPairingVerificationNotification(deviceId)

        Log.d(TAG, "Rejected pairing request from device $deviceId")
    }

    // Connection management methods
    private suspend fun setConnection(deviceId: String, connection: DeviceConnection) {
        connectionLock(deviceId).withLock {
            if (stopping || connections.containsKey(deviceId)) {
                connection.close()
                return@withLock
            }
            installConnection(deviceId, connection, outgoing = false)
        }
    }

    /** Caller holds the device lock; publish replacement before closing the old reader. */
    private fun installConnection(deviceId: String, connection: DeviceConnection, outgoing: Boolean) {
        val previous = connections.put(deviceId, connection)
        if (outgoing) outgoingConnections.add(deviceId) else outgoingConnections.remove(deviceId)
        previous?.close()
        startListeningForDevice(connection)
    }

    private fun removeConnection(deviceId: String) {
        connections.remove(deviceId)?.close()
        outgoingConnections.remove(deviceId)
    }

    fun sendMessage(deviceId: String, message: SocketMessage) {
        connections[deviceId]?.sendMessage(message) ?: run {
            Log.w(TAG, "Cannot send message to $deviceId: no connection found")
        }
    }

    override fun onDestroy() {
        stopping = true
        connectionWatchdog.stop()
        attempts.values.forEach { it.cancel() }
        scope.cancel()
        serverAcceptJob?.cancel()
        try {
            tcpServerSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP server", e)
        }

        // Clean disconnect all peers
        runBlocking {
            deviceManager.pairedDevices.value.forEach { device ->
                if (device.connectionState.isConnectedOrConnecting) {
                    disconnectDevice(device)
                }
            }
            connections.keys.toList().forEach { removeConnection(it) }
        }

        unregisterReceiver(screenOnReceiver)
        unregisterReceiver(wifiStateReceiver)
        deviceControlHandler.stop()
        playSoundFeature.stop()
        networkDiscovery.shutdown()
        connectionWatchdog.stop()

        remotePlaybackFeature.release()
        smsFeature.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        enum class Actions {
            CONNECT,
            APPROVE_DEVICE,
            REJECT_DEVICE,
            DISCONNECT,
            CANCEL_TRANSFER,
            SEND_CLIPBOARD,
            SEND_FILES
        }

        val PORT_RANGE = 5150..5169
        const val TAG = "NetworkService"
        const val DEVICE_ID_EXTRA = "device_id"
        const val EXTRA_CONNECTION_DETAILS = "extra_connection_details"

    }
}
