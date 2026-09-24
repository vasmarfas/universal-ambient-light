package com.vasmarfas.UniversalAmbientLight.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.R

/** Плашка над кнопками главного экрана: каким телевизором сейчас управляет телефон. */
@Composable
fun RemoteBanner(
    snapshot: RemoteSession.Snapshot,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = snapshot.tv ?: return
    val connection = snapshot.connection
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            if (connection == RemoteSession.Connection.CONNECTING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Tv, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.remote_banner_title, tv.name),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (connection) {
                        RemoteSession.Connection.CONNECTED -> stringResource(R.string.remote_banner_connected)
                        RemoteSession.Connection.CONNECTING -> stringResource(R.string.remote_banner_connecting)
                        else -> snapshot.problem ?: stringResource(R.string.remote_error_offline)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connection == RemoteSession.Connection.OFFLINE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (connection == RemoteSession.Connection.OFFLINE) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.remote_banner_retry)) }
            }
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.remote_banner_disconnect)) }
        }
    }
}
