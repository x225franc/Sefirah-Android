package sefirah.clipboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import sefirah.common.R
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Invisible activity that briefly takes focus so the app can read the clipboard on Android 10+
 */
@AndroidEntryPoint
class ClipboardChangeActivity : FragmentActivity() {
    @Inject lateinit var clipboardFeature: ClipboardFeature
    private var sending = false
    private var forceRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunning = true
        forceRequested = intent.getBooleanExtra(EXTRA_FORCE_SEND, true)
        setContentView(
            View(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                alpha = 0f
            },
        )
        window.attributes = window.attributes.apply {
            dimAmount = 0f
            flags = flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sending) return
        sendWhenFocused()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forceRequested = forceRequested || intent.getBooleanExtra(EXTRA_FORCE_SEND, true)
        if (hasWindowFocus() && !sending) sendWhenFocused()
    }

    private fun sendWhenFocused() {
        sending = true

        lifecycleScope.launch {
            try {
                do {
                    val force = forceRequested
                    forceRequested = false
                    val sent = clipboardFeature.sendPrimaryClipboard(force)
                    if (force && !sent) Toast.makeText(this@ClipboardChangeActivity, R.string.clipboard_send_failed, Toast.LENGTH_LONG).show()
                } while (forceRequested)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FORCE_SEND = "sefirah.clipboard.FORCE_SEND"
        @Volatile
        var isRunning: Boolean = false
            private set

        fun launch(context: Context, forceSend: Boolean = false) {
            if (isRunning && !forceSend) return
            val intent = Intent(context, ClipboardChangeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FORCE_SEND, forceSend)
            }
            context.startActivity(intent)
        }
    }
}
