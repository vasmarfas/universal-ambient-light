package com.vasmarfas.UniversalAmbientLight

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.vasmarfas.UniversalAmbientLight.common.util.GithubRelease

@Composable
fun UpdateDialog(
    release: GithubRelease,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Available") },
        text = {
            val description = release.body.ifEmpty { "Bug fixes and improvements" }
            val truncatedDesc = if (description.length > 300) description.take(300) + "..." else description
            
            Text("Version ${release.tagName} available\n\n$truncatedDesc")
        },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text("Update Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}
