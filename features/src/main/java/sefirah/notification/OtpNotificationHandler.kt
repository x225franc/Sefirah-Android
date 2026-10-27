package sefirah.notification

import android.app.Notification
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import sefirah.clipboard.ClipboardFeature
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.util.OtpDetector
import sefirah.domain.util.OtpCopyDeduplicator
import javax.inject.Inject
import javax.inject.Singleton

/** Posted notifications only: do not replay old OTPs when connecting a PC. */
@Singleton
class OtpNotificationHandler @Inject constructor(
    preferencesRepository: PreferencesRepository,
    private val clipboardFeature: ClipboardFeature,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var enabled = false
    private val duplicates = OtpCopyDeduplicator()
    private var expiryJob: Job? = null

    init {
        scope.launch {
            preferencesRepository.readAutoCopyOtpEnabled().collect {
                enabled = it
                if (!it) {
                    expiryJob?.cancel()
                    duplicates.clear()
                }
            }
        }
    }

    fun onNotificationPosted(sbn: StatusBarNotification) {
        // Listener callbacks and preference updates run on the main thread.
        if (!enabled) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        try {
            val code = OtpDetector.detect(notificationText(sbn.notification)) ?: return
            val now = SystemClock.elapsedRealtime()
            if (duplicates.isDuplicate(sbn.packageName, code, now)) return
            if (clipboardFeature.copyVerificationCode(code)) {
                duplicates.record(sbn.packageName, code, now)
                expiryJob?.cancel()
                expiryJob = scope.launch {
                    delay(120_000L)
                    duplicates.clear()
                }
            }
        } catch (_: Exception) {
            // Exception logging can expose notification content; intentionally omit it.
        }
    }

    private fun notificationText(notification: Notification): String {
        val extras = notification.extras
        val parts = linkedSetOf<String>()
        fun add(value: CharSequence?) {
            value?.toString()?.trim()?.take(4096)?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (bigText != null && text != null && bigText.contains(text)) add(bigText)
        else { add(text); add(bigText) }
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeLast(8)?.forEach { add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // MessagingStyle contains history: consider only the most recent message.
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: emptyArray()
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)
                .lastOrNull()?.let { add(it.text) }
        }
        return parts.joinToString("\n").take(16_384)
    }
}
