package sefirah.screenshot

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.model.DevicePreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends each new screenshot to the connected desktop, through the same image path as the clipboard
 * ([ClipboardFeature.sendImageUri]). Off by default; needs image read access (see [mediaPermission]).
 *
 * Watches all of MediaStore.Images via [ContentObserver], since Android has no folder-scoped
 * MediaStore observer - this fires for every image any app writes (WhatsApp, browsers, ...), not
 * just screenshots. A [android.os.FileObserver] directly on the screenshot folders was tried instead
 * (kernel-level, scoped, no wake-up for unrelated photos) but doesn't work: since Android 11,
 * `/storage/emulated/0` is served through a FUSE daemon that doesn't propagate inotify events for
 * that path, so it silently never fired - confirmed on-device (file written, zero FileObserver
 * callback). MediaStore notifications go through the ContentResolver/Binder instead of inotify, so
 * they aren't affected and remain the only reliable option here.
 *
 * Each wake-up is still kept cheap: the changed row's id comes straight from the notification, so
 * only that one row is looked up by primary key instead of re-scanning the whole collection.
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

    private val imagesUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val pendingIds = ConcurrentHashMap.newKeySet<Long>()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val id = uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: return
            pendingIds.add(id)
            scheduleScan()
        }
    }

    private var observing = false
    private var scanJob: Job? = null

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

    /** Registers or unregisters the MediaStore observer from the current state. Safe to call anytime. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                val want = syncEnabled && enabledDevices.isNotEmpty() && hasMediaAccess(context)
                if (want && !observing) {
                    context.contentResolver.registerContentObserver(imagesUri, true, observer)
                    observing = true
                    Log.i(TAG, "Watching screenshots")
                } else if (!want && observing) {
                    context.contentResolver.unregisterContentObserver(observer)
                    observing = false
                    scanJob?.cancel()
                    pendingIds.clear()
                    Log.i(TAG, "Stopped watching screenshots")
                }
            }
        }
    }

    /** MediaStore fires several times per new file; wait for it to settle, then check the batch once. */
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
