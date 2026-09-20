package sefirah.clipboard

import android.content.Context
import android.content.pm.PackageManager

/**
 * READ_LOGS is a development permission: it can't be requested at runtime, only granted with
 * `adb shell pm grant` (or through Shizuku). Without it, `logcat` only returns this app's own lines.
 */
object ReadLogsPermission {
    const val PERMISSION = "android.permission.READ_LOGS"

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun adbCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} $PERMISSION"
}
