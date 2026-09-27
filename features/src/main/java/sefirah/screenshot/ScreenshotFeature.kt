package sefirah.screenshot

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sefirah.Feature
import sefirah.clipboard.ClipboardFeature
import sefirah.common.util.getFileProviderUri
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.model.DevicePreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends each new screenshot to the connected desktop, through the same image path as the clipboard
 * ([ClipboardFeature.sendImageUri]). Off by default; needs image read access (see [mediaPermission]).
 *
 * Two watch strategies, picked by [canWatchFilesDirectly]:
 * - With "All files access" (MANAGE_EXTERNAL_STORAGE, already requested for the storage-integration
 *   feature): [FileObserver] on the known screenshot folders directly. Kernel-level (inotify), scoped
 *   to those folders only - doesn't fire for photos other apps write elsewhere.
 * - Without it: falls back to a [ContentObserver] on all of MediaStore.Images, since Android has no
 *   folder-scoped MediaStore observer. This fires for every image any app writes (WhatsApp, browsers,
 *   ...), not just screenshots - unavoidable in that mode. Each wake-up is still kept cheap: the
 *   changed row's id comes straight from the notification, so only that one row is looked up by
 *   primary key instead of re-scanning the collection.
 */
@Singleton
class ScreenshotFeature @Inject constructor(
    deviceManager: DeviceManager,
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val clipboardFeature: ClipboardFeature,
) : Feature(deviceManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var watchMode: WatchMode? = null

    @Volatile private var syncEnabled = false

    init {
        scope.launch {
            preferencesRepository.readScreenshotSyncEnabled().collect {
                syncEnabled = it
                refresh()
            }
        }
    }

    // Gated by the device's clipboard sync: screenshots leave through the same channel.
    override fun isPrefEnabled(prefs: DevicePreferences) = prefs.clipboardSync

    override suspend fun onStart(deviceId: String) = refresh()

    override suspend fun onStop(deviceId: String) = refresh()

    /** Starts/stops watching, and switches strategy, from the current state. Safe to call anytime. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                val want = syncEnabled && enabledDevices.isNotEmpty() && hasMediaAccess(context)
                val desiredMode = if (!want) null else if (canWatchFilesDirectly()) Mode.FILES else Mode.MEDIA_STORE

                if (watchMode?.mode == desiredMode) return@withLock

                watchMode?.stop()
                watchMode = when (desiredMode) {
                    Mode.FILES -> FileWatchMode().also { it.start() }
                    Mode.MEDIA_STORE -> MediaStoreWatchMode().also { it.start() }
                    null -> null
                }
            }
        }
    }

    private fun canWatchFilesDirectly(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private enum class Mode { FILES, MEDIA_STORE }

    private sealed class WatchMode(val mode: Mode) {
        abstract fun start()
        abstract fun stop()
    }

    /** Folders known to hold screenshots across OEMs. Only the ones that exist are watched. */
    private fun screenshotDirCandidates(): List<File> {
        val base = Environment.getExternalStorageDirectory()
        return listOf(
            File(base, "Pictures/Screenshots"),
            File(base, "DCIM/Screenshots"),
            File(base, "Pictures/ScreenShots"),
        )
    }

    /** Kernel-level (inotify) watch on the screenshot folders themselves - never wakes up for
     * unrelated photos. New folders that appear after [start] (e.g. the very first screenshot ever
     * taken on the device) are only picked up on the next [refresh], not live. */
    private inner class FileWatchMode : WatchMode(Mode.FILES) {
        private var observers: List<FileObserver> = emptyList()
        private val pendingFiles = ConcurrentHashMap.newKeySet<File>()
        private var scanJob: Job? = null

        override fun start() {
            observers = screenshotDirCandidates()
                .filter { it.isDirectory }
                .map { dir ->
                    @Suppress("DEPRECATION") // single-path ctor works on every minSdk we support
                    object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path.isNullOrEmpty()) return
                            pendingFiles.add(File(dir, path))
                            scheduleScan()
                        }
                    }
                }
            observers.forEach { it.startWatching() }
            Log.i(TAG, "Watching ${observers.size} screenshot folder(s) directly")
        }

        override fun stop() {
            observers.forEach { it.stopWatching() }
            observers = emptyList()
            scanJob?.cancel()
            pendingFiles.clear()
        }

        private fun scheduleScan() {
            scanJob?.cancel()
            scanJob = scope.launch {
                delay(SETTLE_DELAY_MS)
                mutex.withLock { sendPendingFiles() }
            }
        }

        private suspend fun sendPendingFiles() {
            val files = pendingFiles.toList()
            if (files.isEmpty()) return
            pendingFiles.removeAll(files.toSet())

            for (file in files) {
                if (!file.isFile || !isScreenshot(file.name, file.parent.orEmpty())) continue

                Log.d(TAG, "New screenshot ${file.name}")
                val uri = try {
                    getFileProviderUri(context, file)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not build a content URI for $file", e)
                    continue
                }
                if (!clipboardFeature.sendImageUri(uri)) {
                    Log.w(TAG, "Screenshot $file was not sent")
                }
            }
        }
    }

    /** MediaStore.Images observer, for when "All files access" isn't granted. */
    private inner class MediaStoreWatchMode : WatchMode(Mode.MEDIA_STORE) {
        private val imagesUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private val pendingIds = ConcurrentHashMap.newKeySet<Long>()
        private var scanJob: Job? = null

        private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val id = uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: return
                pendingIds.add(id)
                scheduleScan()
            }
        }

        override fun start() {
            context.contentResolver.registerContentObserver(imagesUri, true, observer)
            Log.i(TAG, "Watching screenshots via MediaStore (no all-files access)")
        }

        override fun stop() {
            context.contentResolver.unregisterContentObserver(observer)
            scanJob?.cancel()
            pendingIds.clear()
        }

        private fun scheduleScan() {
            scanJob?.cancel()
            scanJob = scope.launch {
                delay(SETTLE_DELAY_MS)
                mutex.withLock { scanPendingImages() }
            }
        }

        /** Looks up only the rows named in [pendingIds] - one primary-key lookup per changed image. */
        private suspend fun scanPendingImages() {
            val ids = pendingIds.toList()
            if (ids.isEmpty()) return
            pendingIds.removeAll(ids.toSet())

            val projection = buildList {
                add(MediaStore.Images.Media._ID)
                add(MediaStore.Images.Media.DISPLAY_NAME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Images.Media.RELATIVE_PATH)
                    add(MediaStore.Images.Media.IS_PENDING)
                } else {
                    @Suppress("DEPRECATION") add(MediaStore.Images.Media.DATA)
                }
            }.toTypedArray()

            for (id in ids) {
                try {
                    context.contentResolver.query(
                        ContentUris.withAppendedId(imagesUri, id),
                        projection,
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use // deleted/moved since the notification fired

                        val name = cursor.getString(1).orEmpty()
                        val location = cursor.getString(2).orEmpty()
                        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cursor.getInt(3) == 1

                        // The observer fires again once the file is finalized, so re-queue and retry then.
                        if (pending) {
                            Log.d(TAG, "Image $id still pending, will retry")
                            pendingIds.add(id)
                            return@use
                        }
                        if (!isScreenshot(name, location)) return@use

                        Log.d(TAG, "New screenshot $id ($location$name)")
                        if (!clipboardFeature.sendImageUri(ContentUris.withAppendedId(imagesUri, id))) {
                            Log.w(TAG, "Screenshot $id was not sent")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No access to MediaStore images", e)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot check failed for image $id", e)
                }
            }
        }
    }

    // Folder or file name varies by maker (Pictures/Screenshots, DCIM/Screenshots, "Screenshot_...", ...)
    private fun isScreenshot(name: String, location: String): Boolean {
        val haystack = "$location$name".lowercase()
        return SCREENSHOT_MARKERS.any { it in haystack }
    }

    companion object {
        private const val TAG = "ScreenshotFeature"
        private const val SETTLE_DELAY_MS = 150L
        private val SCREENSHOT_MARKERS = listOf("screenshot", "screen_shot", "screen shot", "screencap", "capture d")

        /** Runtime permission to request for reading images, by Android version. */
        fun mediaPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasMediaAccess(context: Context): Boolean =
            context.checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
    }
}
