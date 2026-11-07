package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.CaptureLauncher
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.SettingsBackup
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BackupActions"

class BackupActions(val save: () -> Unit, val restore: () -> Unit)

/**
 * Сохранение и восстановление настроек из [prefs]. В режиме пульта это зеркало ТВ, так что
 * файл ложится на телефон, а восстановленное уходит на телевизор обычной пачкой правок.
 *
 * Звать вне блока с ключом ревизии в [SettingsScreen]: снимок с ТВ, пришедший, пока открыт
 * выбор файла, пересоздал бы лаунчеры, и результат выбора потерялся бы.
 */
@Composable
internal fun rememberBackupActions(prefs: Preferences, onRestored: () -> Unit): BackupActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val remote = LocalRemote.current
    val scope = rememberCoroutineScope()

    fun saveTo(label: String, target: () -> Uri) {
        val text = SettingsBackup.encode(prefs.preferences)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    SettingsBackup.write(context, target(), text)
                    true
                } catch (e: Exception) {
                    // MediaProvider и чужие провайдеры файлов бросают не только IOException
                    Log.w(TAG, "Failed to save settings", e)
                    false
                }
            }
            val message = if (saved) {
                AnalyticsHelper.logSettingChanged(context, "settings_backup", "saved")
                resources.getString(R.string.backup_saved, label)
            } else {
                resources.getString(R.string.backup_save_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackup.MIME_TYPE)
    ) { uri ->
        if (uri != null) saveTo(uri.lastPathSegment.orEmpty()) { uri }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val values = withContext(Dispatchers.IO) {
                try {
                    SettingsBackup.decode(SettingsBackup.read(context, uri))
                } catch (e: Exception) {
                    // Как и при сохранении: файл отдаёт чужой провайдер
                    Log.w(TAG, "Failed to read settings", e)
                    null
                }
            }
            if (values == null) {
                Toast.makeText(context, R.string.backup_read_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val methods = remote?.caps?.methods ?: CaptureLauncher.availableMethods(context)
            val methodRestored = SettingsBackup.restore(prefs.preferences, values, methods)
            onRestored()
            AnalyticsHelper.logSettingChanged(context, "settings_backup", "restored")
            val message = if (methodRestored) {
                R.string.backup_restored
            } else {
                R.string.backup_restored_without_method
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun openPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.backup_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    return BackupActions(
        save = {
            val name = SettingsBackup.fileName()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveTo("${Environment.DIRECTORY_DOWNLOADS}/$name") {
                    SettingsBackup.createInDownloads(context, name)
                }
            } else {
                openPicker { createFile.launch(name) }
            }
        },
        restore = { openPicker { openFile.launch("*/*") } },
    )
}
