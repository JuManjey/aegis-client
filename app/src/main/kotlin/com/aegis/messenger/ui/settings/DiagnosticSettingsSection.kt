package com.aegis.messenger.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.aegis.core.common.diag.DiagnosticSettings
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Секция «Диагностика» — в самом низу настроек. Пользователь включает журнал
 * ПЕРЕД проблемным действием (обмен ключами, отправка), затем отправляет файл.
 * Журнал секретов не содержит (PSK, текст, mailbox-id), его можно отдать.
 */
@Composable
fun DiagnosticSettingsSection(
    settings: DiagnosticSettings,
    logSizeBytes: () -> Long,
    onSetEnabled: (Boolean) -> Unit,
    onClear: () -> Unit,
    prepareExport: suspend () -> File?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secondary = AegisTheme.extendedColors.textSecondary

    val size by produceState(initialValue = logSizeBytes(), settings.logEnabled) {
        while (true) {
            value = logSizeBytes()
            delay(1_500)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = runCatching { prepareExport() }.getOrNull()
            if (file == null) {
                Toast.makeText(context, context.getString(R.string.diag_export_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } != null
                }.getOrDefault(false)
            }
            val msg = if (ok) R.string.diag_export_saved else R.string.diag_export_fail
            Toast.makeText(context, context.getString(msg), Toast.LENGTH_SHORT).show()
        }
    }

    AegisHudCard(
        title = stringResource(R.string.diag_section_title),
        icon = Icons.Outlined.BugReport,
    ) {
        Text(
            text = stringResource(R.string.diag_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        AegisHudSwitchRow(
            title = stringResource(R.string.diag_toggle_title),
            subtitle = stringResource(R.string.diag_toggle_sub),
            checked = settings.logEnabled,
            onCheckedChange = onSetEnabled,
        )

        // Размер журнала — служебная величина, поэтому моноширинный хром-стиль:
        // подпись и число живут в одном ресурсе и на пару «лейбл → значение» не
        // делятся.
        Text(
            text = stringResource(R.string.diag_size, formatSize(size)),
            style = MaterialTheme.typography.labelMedium,
            color = secondary,
        )

        AegisHudButton(
            text = stringResource(R.string.diag_share),
            onClick = {
                scope.launch {
                    val file = runCatching { prepareExport() }.getOrNull()
                    if (file == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.diag_export_empty),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        shareLogFile(context, file)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = size > 0L || settings.logEnabled,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.diag_save_file),
            onClick = {
                scope.launch {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    saveLauncher.launch("aegis-diag-$stamp.log")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = size > 0L || settings.logEnabled,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.diag_clear),
            onClick = onClear,
            modifier = Modifier.fillMaxWidth(),
            enabled = size > 0L,
            danger = true,
        )
    }
}

private fun shareLogFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AEGIS diagnostic log")
        clipData = ClipData.newRawUri("aegis-diag.log", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.diag_share))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(chooser) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.diag_export_fail), Toast.LENGTH_SHORT).show()
        }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
