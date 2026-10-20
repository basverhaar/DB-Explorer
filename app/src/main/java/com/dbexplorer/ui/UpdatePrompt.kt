package com.dbexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dbexplorer.sync.UpdateState
import kotlinx.coroutines.launch

/** Checks for a newer app version on launch and offers to install it. */
@Composable
fun UpdatePrompt() {
    val updater = app().updater
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { updater.check() } }

    when (val s = state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> if (s.update.versionCode > updater.dismissedVersion) {
            AlertDialog(
                onDismissRequest = { updater.dismiss() },
                title = { Text("Update available") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Version ${s.update.versionName} is ready (you have ${updater.installedName}).")
                        if (s.update.notes.isNotBlank()) {
                            Text("What's new: ${s.update.notes}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Your played/watched marks and settings are kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { scope.launch { updater.downloadAndInstall(s.update) } }) { Text("Update") }
                },
                dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Later") } },
            )
        }
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${s.percent}%")
                }
            },
            confirmButton = {},
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Allow installing the update") },
            text = {
                Text(
                    "Android asks you once to allow DB Explorer to install apps. " +
                        "Switch on \"Allow from this source\", come back, then tap Install."
                )
            },
            confirmButton = { TextButton(onClick = { updater.install(s.update, s.file) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Later") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Update failed") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { scope.launch { updater.downloadAndInstall(s.update) } }) { Text("Try again") }
            },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Close") } },
        )
    }
}
