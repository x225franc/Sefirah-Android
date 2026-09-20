package sefirah.clipboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Detects clipboard changes by tailing the system log (needs READ_LOGS, see [ReadLogsPermission]).
 *
 * Since Android 10 the system only dispatches OnPrimaryClipChangedListener to apps that may read the
 * clipboard; for every other registered listener ClipboardService logs
 * "Denying clipboard access to <package>, application is not in focus ...".
 * [ClipboardFeature] keeps a listener registered, so that line naming *our* package is emitted on each
 * clipboard change, whatever app or content (sensitive or not) produced it.
 *
 * Only that exact ClipboardService line counts: a looser match also catches the many system lines that
 * merely mention our ClipboardChangeActivity or accessibility service. Matches and line counts are logged
 * (tag [TAG]) to help adapt [matches] if another ROM words the denial differently.
 */
@Singleton
class LogcatClipboardWatcher @Inject constructor(
    private val context: Context,
) {
    @Volatile private var running = false
    @Volatile private var process: Process? = null
    private var readerThread: Thread? = null
    private var onChange: (() -> Unit)? = null

    private val packageName = context.packageName

    @Synchronized
    fun start(onChange: () -> Unit): Boolean {
        this.onChange = onChange
        if (running) return true
        if (!ReadLogsPermission.isGranted(context)) {
            Log.w(TAG, "READ_LOGS not granted; run: ${ReadLogsPermission.adbCommand(context)}")
            return false
        }
        running = true
        readerThread = thread(name = "sefirah-logcat-clipboard", isDaemon = true) { runLoop() }
        Log.i(TAG, "Logcat clipboard watcher started")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        process?.destroy()
        process = null
        readerThread?.interrupt()
        readerThread = null
        Log.i(TAG, "Logcat clipboard watcher stopped")
    }

    private fun runLoop() {
        var backoffMs = MIN_BACKOFF_MS
        while (running) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                readOnce()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "logcat reader failed", e)
            }
            if (!running) break
            backoffMs = if (SystemClock.elapsedRealtime() - startedAt > STABLE_RUN_MS) {
                MIN_BACKOFF_MS
            } else {
                (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            Log.w(TAG, "logcat process ended, restarting in ${backoffMs}ms")
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun readOnce() {
        // Only lines from "now" on, so a restart never replays an old clipboard event.
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val proc = ProcessBuilder("logcat", "-b", "system", "-v", "brief", "-T", since)
            .redirectErrorStream(true)
            .start()
        process = proc

        var lines = 0L
        var hits = 0L
        var lastReportAt = SystemClock.elapsedRealtime()
        proc.inputStream.bufferedReader().use { reader ->
            while (running) {
                val line = reader.readLine() ?: break
                lines++
                if (matches(line)) {
                    hits++
                    Log.d(TAG, "clipboard change line: $line")
                    onChange?.invoke()
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportAt >= REPORT_INTERVAL_MS) {
                    Log.d(TAG, "alive: $lines lines read, $hits matches in the last ${REPORT_INTERVAL_MS / 1000}s")
                    lines = 0
                    hits = 0
                    lastReportAt = now
                }
            }
        }
        proc.destroy()
    }

    // Seen on OnePlus (Android 15+): "E/ClipboardService( 3841): op = 29 Denying clipboard access to <pkg>, ..."
    private fun matches(line: String): Boolean =
        line.contains("ClipboardService") && line.contains("Denying clipboard access to $packageName")

    private companion object {
        const val TAG = "LogcatClipboard"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val STABLE_RUN_MS = 60_000L
        const val REPORT_INTERVAL_MS = 300_000L
    }
}
