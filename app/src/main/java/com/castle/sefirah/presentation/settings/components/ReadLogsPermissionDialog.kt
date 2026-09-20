package com.castle.sefirah.presentation.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sefirah.common.R

/**
 * Explains the one-time `pm grant` needed for READ_LOGS, with the exact command for this build's
 * package name and, when Shizuku is authorized, a button that runs it without a computer.
 */
@Composable
fun ReadLogsPermissionDialog(
    adbCommand: String,
    shizukuAvailable: Boolean,
    onGrantWithShizuku: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.read_logs_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.read_logs_dialog_message))
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SelectionContainer {
                        Text(
                            text = adbCommand,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.read_logs_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column {
                if (shizukuAvailable) {
                    TextButton(onClick = onGrantWithShizuku) {
                        Text(stringResource(R.string.read_logs_grant_shizuku))
                    }
                }
                TextButton(onClick = { copyToClipboard(context, adbCommand) }) {
                    Text(stringResource(R.string.read_logs_copy_command))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("adb", text))
    Toast.makeText(context, R.string.read_logs_copy_command, Toast.LENGTH_SHORT).show()
}
