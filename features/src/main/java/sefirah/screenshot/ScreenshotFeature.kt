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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends each new screenshot to the connected desktop, through the same image path as the clipboard
 * ([ClipboardFeature.sendImageUri]). Some ROMs never put screenshots in the clipboard, so watching
 * MediaStore covers them. Off by default; needs image read access (see [mediaPermission]).
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

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleScan()
        }
    }

    private var observing = false
    private var scanJob: Job? = null
    private var lastSeenId = 0L

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
                    lastSeenId = maxImageId()
                    context.contentResolver.registerContentObserver(imagesUri, true, observer)
                    observing = true
                    Log.i(TAG, "Watching screenshots (baseline id $lastSeenId)")
                } else if (!want && observing) {
                    context.contentResolver.unregisterContentObserver(observer)
                    observing = false
                    scanJob?.cancel()
                    Log.i(TAG, "Stopped watching screenshots")
                }
            }
        }
    }

    /** MediaStore fires several times per new file; wait for it to settle, then scan once. */
    private fun scheduleScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SETTLE_DELAY_MS)
            mutex.withLock { scanNewImages() }
        }
    }

    private suspend fun scanNewImages() {
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

        try {
            context.contentResolver.query(
                imagesUri,
                projection,
                "${MediaStore.Images.Media._ID} > ?",
                arrayOf(lastSeenId.toString()),
                "${MediaStore.Images.Media._ID} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val location = cursor.getString(2).orEmpty()
                    val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cursor.getInt(3) == 1

                    // The observer fires again once the file is finalized, so stop here and retry then.
                    if (pending) {
                        Log.d(TAG, "Image $id still pending, waiting")
                        break
                    }
                    lastSeenId = id
                    if (!isScreenshot(name, location)) continue

                    Log.d(TAG, "New screenshot $id ($location$name)")
                    if (!clipboardFeature.sendImageUri(ContentUris.withAppendedId(imagesUri, id))) {
                        Log.w(TAG, "Screenshot $id was not sent")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to MediaStore images", e)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot scan failed", e)
        }
    }

    private fun maxImageId(): Long = try {
        context.contentResolver.query(
            imagesUri,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "Could not read baseline image id", e)
        0L
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
