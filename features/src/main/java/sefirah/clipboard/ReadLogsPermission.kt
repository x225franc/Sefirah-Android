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

    /** Lets the notification listener see the content of notifications Android marks as sensitive. */
    fun sensitiveNotificationsCommand(context: Context): String =
        "adb shell appops set ${context.packageName} RECEIVE_SENSITIVE_NOTIFICATIONS allow"

    /** MacroDroid's own package name; grants it the same READ_LOGS access as this app for its
     * log-based triggers. Unrelated to Sefirah's own permissions, kept here only so it rides along
     * on the same "commands to run after every reinstall" clipboard copy. */
    private const val MACRODROID_PACKAGE = "com.arlosoft.macrodroid"

    fun macroDroidReadLogsCommand(): String =
        "adb shell pm grant $MACRODROID_PACKAGE $PERMISSION"

    fun allCommands(context: Context): String =
        adbCommand(context) + "\n" + sensitiveNotificationsCommand(context) + "\n" + macroDroidReadLogsCommand()
}
