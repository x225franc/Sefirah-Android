package sefirah.worker

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import kotlin.system.exitProcess

/** Shizuku UserService — execs {@link WorkerStarter}'s native starter. */
@Suppress("unused")
class WorkerLauncherUserService : IWorkerLauncherService.Stub {

    private lateinit var context: Context

    constructor()

    @Keep
    constructor(context: Context) {
        this.context = context.applicationContext
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun startWorker() {
        if (!::context.isInitialized) {
            Log.e(TAG, "startWorker missing context")
            return
        }
        val starter = WorkerStarter.starterFile(context)
        if (!starter.isFile || starter.length() == 0L) {
            Log.e(TAG, "Starter missing or empty: ${starter.absolutePath}")
            return
        }
        if (isWorkerProcessRunning()) {
            Log.i(TAG, "Worker already running — skip start")
            return
        }
        try {
            val cmd = WorkerStarter.command(context)
            runShell(cmd)
            Log.i(TAG, "Started worker via $cmd")
        } catch (e: Exception) {
            Log.e(TAG, "startWorker failed", e)
        }
    }

    override fun grantPermission(permission: String): Boolean {
        if (!::context.isInitialized) {
            Log.e(TAG, "grantPermission missing context")
            return false
        }
        val process = Runtime.getRuntime().exec(arrayOf("pm", "grant", context.packageName, permission))
        return try {
            val code = process.waitFor()
            if (code != 0) {
                val err = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "pm grant $permission exit=$code err=$err")
            }
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "pm grant $permission failed", e)
            false
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun isWorkerProcessRunning(): Boolean {
        val process = Runtime.getRuntime().exec(arrayOf("pidof", WorkerManager.NICE_NAME))
        return try {
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor() == 0 && out.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "pidof failed", e)
            false
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun runShell(script: String) {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", script))
        try {
            val code = process.waitFor()
            if (code != 0) {
                val err = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "shell exit=$code err=$err")
            }
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "WorkerLauncherUS"
    }
}
