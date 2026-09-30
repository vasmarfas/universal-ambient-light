package com.vasmarfas.UniversalAmbientLight.ui.devices

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.HueClient
import com.vasmarfas.UniversalAmbientLight.common.network.HueClient.Companion.LinkButtonException
import com.vasmarfas.UniversalAmbientLight.common.network.NanoleafClient
import com.vasmarfas.UniversalAmbientLight.common.network.NanoleafClient.Companion.PairingException
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Подключение к мосту Hue или панелям Nanoleaf: человек жмёт кнопку на устройстве, потом
 * «Подключить». Ключ доступа ложится в настройки, с телефона-пульта - в настройки телевизора.
 */
@Composable
fun PairDialog(
    prefs: Preferences,
    type: OutputType,
    onPaired: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val hue = type == OutputType.HUE
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair() {
        val host = prefs.getString(R.string.pref_key_host)?.trim().orEmpty()
        if (host.isEmpty()) {
            error = resources.getString(R.string.pair_need_host)
            return
        }
        val port = prefs.getInt(R.string.pref_key_port, NanoleafClient.DEFAULT_PORT)
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(
                        if (hue) {
                            val credentials = HueClient.pair(host, Build.MODEL)
                            listOf(
                                R.string.pref_key_hue_username to credentials.username,
                                R.string.pref_key_hue_clientkey to credentials.clientKey
                            )
                        } else {
                            listOf(R.string.pref_key_nanoleaf_token to NanoleafClient.pair(host, port))
                        }
                    )
                } catch (e: IOException) {
                    Result.failure(e)
                }
            }
            busy = false
            result.onSuccess { values ->
                for ((key, value) in values) prefs.putString(key, value)
                onPaired()
            }.onFailure { e ->
                error = when (e) {
                    is LinkButtonException -> resources.getString(R.string.hue_pair_button_not_pressed)
                    is PairingException -> resources.getString(R.string.nanoleaf_pair_rejected)
                    else -> resources.getString(R.string.pair_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (hue) R.string.hue_pair_title else R.string.nanoleaf_pair_title)) },
        text = {
            Column {
                Text(stringResource(if (hue) R.string.hue_pair_text else R.string.nanoleaf_pair_text))
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.pair_in_progress))
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { pair() }) {
                Text(stringResource(R.string.action_pair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
