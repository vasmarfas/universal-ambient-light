package com.vasmarfas.UniversalAmbientLight.ui.devices

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.HueClient
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Выбор зоны развлечений моста Hue. С зоной лампы получают цвет потоком, а какая лампа где
 * стоит, задано в приложении Hue; «Лампы по одной» - прежний режим с зонами экрана здесь.
 * [onPicked] получает номер зоны, null - лампы по одной. [onNoAreas] вызывается вместо
 * пустого списка, если зон на мосту нет.
 */
@Composable
fun HueAreaDialog(
    prefs: Preferences,
    onPicked: (String?) -> Unit,
    onRepair: () -> Unit,
    onDismiss: () -> Unit,
    onNoAreas: (() -> Unit)? = null,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var areas by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { prefs.getString(R.string.pref_key_hue_area).orEmpty() }
    val hasKey = remember { !prefs.getString(R.string.pref_key_hue_clientkey).isNullOrBlank() }

    LaunchedEffect(attempt) {
        areas = null
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                HueClient.entertainmentAreas(
                    prefs.getString(R.string.pref_key_host)?.trim().orEmpty(),
                    prefs.getString(R.string.pref_key_hue_username).orEmpty()
                )
            }
            if (loaded.isEmpty() && onNoAreas != null) onNoAreas() else areas = loaded
        } catch (e: IOException) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun pick(id: String, name: String) {
        prefs.putString(R.string.pref_key_hue_area, id)
        prefs.putString(R.string.pref_key_hue_area_name, name)
        onPicked(id.ifEmpty { null })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_title_hue_area)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.hue_area_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                val list = areas
                val failure = error
                when {
                    failure != null -> {
                        Text(
                            text = stringResource(R.string.hue_area_error, failure),
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { attempt++ }) {
                            Text(stringResource(R.string.scanner_retry_button))
                        }
                    }

                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.hue_area_loading))
                    }

                    else -> {
                        if (list.isEmpty()) {
                            Text(stringResource(R.string.hue_area_empty))
                        } else if (!hasKey) {
                            Text(
                                text = stringResource(R.string.hue_area_no_key),
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = onRepair) {
                                Text(stringResource(R.string.hue_area_repair))
                            }
                        }
                        AreaOption(
                            title = stringResource(R.string.hue_area_none),
                            summary = stringResource(R.string.hue_area_none_summary),
                            selected = selected.isEmpty(),
                            enabled = true,
                            onClick = { pick("", "") }
                        )
                        val areaSummary = stringResource(R.string.hue_area_summary)
                        for ((id, name) in list) {
                            AreaOption(
                                title = name,
                                summary = areaSummary,
                                selected = selected == id,
                                enabled = hasKey,
                                onClick = { pick(id, name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AreaOption(title: String, summary: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
