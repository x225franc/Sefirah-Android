package sefirah.clipboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sefirah.Feature
import sefirah.common.util.createTempFile
import sefirah.common.util.getFileProviderUri
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.model.ClipboardInfo
import sefirah.domain.model.DevicePreferences
import sefirah.domain.model.FileMetadata
import sefirah.transfer.FileTransferService
import sefirah.worker.WorkerManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardFeature @Inject constructor(
    deviceManager: DeviceManager,
    private val context: Context,
    private val networkManager: NetworkManager,
    private val preferencesRepository: PreferencesRepository,
    private val fileTransferService: Lazy<FileTransferService>,
    private val workerManager: WorkerManager,
    private val logcatWatcher: LogcatClipboardWatcher,
) : Feature(deviceManager) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        scope.launch { sendPrimaryClipboard() }
    }

    @Volatile private var logcatEnabled = false
    @Volatile private var listenerRegistered = false
    private var lastInboundSetAt = 0L
    private var lastLogcatTriggerAt = 0L
    private var lastSentText: String? = null
    private var lastSentAt = 0L
    private var lastSentClipStamp = 0L

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch {
                preferencesRepository.readClipboardWorkerEnabled().collect {
                    applyWorkerState(it)
                }
            }
            scope.launch {
                preferencesRepository.readLogcatClipboardEnabled().collect {
                    logcatEnabled = it
                    refreshDetectors()
                }
            }
        }
    }

    /**
     * App-side suppress for the non-worker clip listener (sync callbacks during setPrimaryClip).
     * Worker echo is handled in the shell worker via [WorkerManager.suppressNextOutbound].
     */
    @Volatile
    var suppressOutbound: Boolean = false
        private set

    override fun isPrefEnabled(prefs: DevicePreferences) = prefs.clipboardSync

    override suspend fun onStart(deviceId: String) {
        if (enabledDevices.size != 1) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            applyWorkerState(preferencesRepository.readClipboardWorkerEnabled().first())
        }
        refreshDetectors()
    }

    override suspend fun onStop(deviceId: String) {
        if (enabledDevices.isNotEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            workerManager.stopClipboardWatcher()
        }
        refreshDetectors()
    }

    /**
     * Starts/stops the in-app change detectors from the current state. Also called after READ_LOGS gets
     * granted. Below Android 10 the plain listener is enough; from 10 on the listener is only kept so
     * ClipboardService logs a denial line naming us, which [LogcatClipboardWatcher] turns into a trigger.
     */
    @Synchronized
    fun refreshDetectors() {
        val active = enabledDevices.isNotEmpty()
        val useLogcat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && logcatEnabled && active
        if (useLogcat) {
            if (!logcatWatcher.start(::onLogcatClipboardChange)) {
                Log.w(TAG, "Logcat detection enabled but READ_LOGS is missing")
            }
        } else {
            logcatWatcher.stop()
        }

        val wantListener = active && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || logcatEnabled)
        if (wantListener && !listenerRegistered) {
            clipboardManager.addPrimaryClipChangedListener(clipChangedListener)
            listenerRegistered = true
        } else if (!wantListener && listenerRegistered) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener)
            listenerRegistered = false
        }
    }

    private fun onLogcatClipboardChange() {
        scope.launch {
            val now = SystemClock.elapsedRealtime()
            when {
                workerManager.isWorkerAlive() -> return@launch // worker already reports changes
                now - lastInboundSetAt < INBOUND_ECHO_WINDOW_MS -> return@launch // our own setPrimaryClip
                now - lastLogcatTriggerAt < LOGCAT_COOLDOWN_MS || ClipboardChangeActivity.isRunning ->
                    return@launch
            }
            lastLogcatTriggerAt = now
            Log.d(TAG, "Clipboard change detected via logcat")

            // No filter on ClipDescription.EXTRA_IS_SENSITIVE on purpose: sensitive clips are synced too.
            if (sendPrimaryClipboard()) {
                Log.d(TAG, "Read clipboard without focus")
                return@launch
            }
            Log.d(TAG, "Background read returned nothing, launching focus activity")
            try {
                ClipboardChangeActivity.launch(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ClipboardChangeActivity", e)
            }
        }
    }

    /** Worker runs only when sync is active for a device and the worker pref is on. */
    private fun applyWorkerState(workerSync: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        scope.launch {
            if (enabledDevices.isNotEmpty() && workerSync) {
                workerManager.startClipboardWatcher()
            } else {
                workerManager.stopClipboardWatcher()
            }
        }
    }

    fun onWorkerClipboardText(text: String) {
        sendClipboard(ClipboardInfo("text/plain", text))
    }

    fun onWorkerClipboardImage(mimeType: String, fd: ParcelFileDescriptor) {
        scope.launch { sendClipboardImage(mimeType, fd) }
    }

    fun setClipboard(clipboard: ClipboardInfo) {
        try {
            val clip: ClipData = when {
                clipboard.clipboardType == "text/plain" ->
                    ClipData.newPlainText("Received clipboard", clipboard.content)

                clipboard.clipboardType.startsWith("image/") -> {
                    val imageBytes = Base64.decode(clipboard.content, Base64.DEFAULT)
                    val extension = clipboard.clipboardType.substringAfter('/').lowercase()

                    val tempFile = createTempFile(context, FileMetadata.CLIPBOARD_FILE_NAME, extension)
                    FileOutputStream(tempFile).use { it.write(imageBytes) }

                    val uri = getFileProviderUri(context, tempFile)
                    ClipData.newUri(context.contentResolver, "Received image", uri)
                }

                else -> ClipData.newPlainText("Received clipboard", clipboard.content)
            }
            suppressOutbound()
            lastInboundSetAt = SystemClock.elapsedRealtime()
            clipboardManager.setPrimaryClip(clip)
        } catch (ex: Exception) {
            Log.e(TAG, "Exception handling clipboard", ex)
        } finally {
            suppressOutbound = false
        }
    }

    fun setClipboardUri(uri: Uri) {
        try {
            suppressOutbound()
            lastInboundSetAt = SystemClock.elapsedRealtime()
            val clip = ClipData.newUri(context.contentResolver, "Received file", uri)
            clipboardManager.setPrimaryClip(clip)
        } catch (ex: Exception) {
            Log.e(TAG, "Exception setting clipboard URI", ex)
        } finally {
            suppressOutbound = false
        }
    }

    private fun suppressOutbound() {
        if (workerManager.isWorkerAlive()) {
            workerManager.suppressNextOutbound()
        } else {
            suppressOutbound = true
        }
    }

    fun sendClipboard(message: ClipboardInfo) {
        if (enabledDevices.isEmpty()) {
            return
        }
        val targets = enabledDevices.toList()
        Log.d(TAG, "broadcasting clipboard | ${message.clipboardType}")
        targets.forEach { deviceId ->
            networkManager.sendMessage(deviceId, message)
        }
    }


    @SuppressLint("Recycle")
    suspend fun sendPrimaryClipboard(): Boolean {
        if (suppressOutbound) return false

        val clip = try {
            clipboardManager.primaryClip
        } catch (e: SecurityException) {
            Log.d(TAG, "Clipboard read denied", e)
            null
        }
        if (clip == null || clip.itemCount == 0) {
            return false
        }

        // Several detectors (listener, logcat, accessibility) can fire for the same copy. Each
        // setPrimaryClip gets its own timestamp, so the same one means the same copy, however late.
        val stamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clip.description.timestamp else 0L
        if (stamp != 0L && stamp == lastSentClipStamp) {
            Log.d(TAG, "Clip $stamp already sent, skipping")
            return true
        }

        val item = clip.getItemAt(0)

        val text = item.text?.toString()
        if (!text.isNullOrEmpty()) {
            val now = SystemClock.elapsedRealtime()
            if (stamp == 0L && text == lastSentText && now - lastSentAt < DUPLICATE_WINDOW_MS) return true
            lastSentClipStamp = stamp
            lastSentText = text
            lastSentAt = now
            sendClipboard(ClipboardInfo("text/plain", text))
            return true
        }

        val uri = item.uri
        if (uri != null && clip.description.hasMimeType("image/*")) {
            val mime = clip.description.filterMimeTypes("image/*")?.firstOrNull()
                ?: context.contentResolver.getType(uri)
                ?: "image/png"
            val pfd = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open clipboard image: $uri", e)
                return false
            }
            if (pfd == null) {
                Log.e(TAG, "Failed to open clipboard image: $uri")
                return false
            }
            lastSentClipStamp = stamp
            sendClipboardImage(mime, pfd)
            return true
        }
        return false
    }

    /** Sends the image at [uri] (e.g. a screenshot from MediaStore) through the clipboard image path. */
    suspend fun sendImageUri(uri: Uri): Boolean {
        if (enabledDevices.isEmpty()) return false
        val mime = context.contentResolver.getType(uri) ?: "image/png"
        val pfd = try {
            withContext(Dispatchers.IO) { context.contentResolver.openFileDescriptor(uri, "r") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open image: $uri", e)
            null
        } ?: return false
        sendClipboardImage(mime, pfd)
        return true
    }

    /**
     * Image FD from the shell worker or [sendPrimaryClipboard].
     * Known size ≤2MB → base64 [ClipboardInfo]. Larger → [FileTransferService.sendFromPfd].
     */
    private suspend fun sendClipboardImage(mimeType: String, pfd: ParcelFileDescriptor) {
        var toClose = pfd
        try {
            val filePfd = withContext(Dispatchers.IO) { asRegularFilePfd(pfd) }
            toClose = filePfd
            val mime = mimeType.takeIf { it.startsWith("image/") } ?: "image/png"
            val size = filePfd.statSize

            if (enabledDevices.isEmpty()) {
                return
            }

            when {
                size in 0..DIRECT_TRANSFER_THRESHOLD -> {
                    val bytes = withContext(Dispatchers.IO) {
                        FileInputStream(filePfd.fileDescriptor).use { it.readBytes() }
                    }
                    if (bytes.isEmpty()) {
                        return
                    }
                    sendClipboard(
                        ClipboardInfo(mime, Base64.encodeToString(bytes, Base64.NO_WRAP)),
                    )
                }

                size > DIRECT_TRANSFER_THRESHOLD -> {
                    val metadata = FileMetadata(FileMetadata.CLIPBOARD_FILE_NAME, mime, size)
                    enabledDevices.forEach { deviceId ->
                        val dup = try {
                            filePfd.dup()
                        } catch (e: Exception) {
                            Log.e(TAG, "pfd.dup failed for $deviceId", e)
                            return@forEach
                        }
                        fileTransferService.get().sendFromPfd(
                            deviceId = deviceId,
                            pfd = dup,
                            metadata = metadata,
                            isClipboard = true,
                        )
                    }
                }

                else -> Log.e(TAG, "Clipboard image has unknown size after materialize; skip")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendClipboardImage failed", e)
        } finally {
            try {
                toClose.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * If [pfd] is a pipe (`statSize == -1`), copy into a cache file, then unlink the path
     * (fd stays valid until [ParcelFileDescriptor.close]). Takes ownership of [pfd].
     */
    @SuppressLint("Recycle")
    private fun asRegularFilePfd(pfd: ParcelFileDescriptor): ParcelFileDescriptor {
        if (pfd.statSize >= 0) {
            return pfd
        }
        val tmp = createTempFile(context, FileMetadata.CLIPBOARD_FILE_NAME, "img")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_CLIPBOARD_IMAGE_BYTES) {
                            throw IOException("Clipboard image exceeds $MAX_CLIPBOARD_IMAGE_BYTES bytes")
                        }
                        output.write(buf, 0, n)
                    }
                }
            }
            val filePfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            tmp.delete()
            return filePfd
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "ClipboardFeature"
        private const val DIRECT_TRANSFER_THRESHOLD = 2 * 1024 * 1024 // 2MB
        private const val MAX_CLIPBOARD_IMAGE_BYTES = 32L * 1024 * 1024
        private const val INBOUND_ECHO_WINDOW_MS = 3_000L
        private const val LOGCAT_COOLDOWN_MS = 1_500L
        private const val DUPLICATE_WINDOW_MS = 1_000L
    }
}
