package com.dbexplorer.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.dbexplorer.data.AppSettings
import com.dbexplorer.data.MediaType
import com.dbexplorer.sync.RefreshScheduler
import com.dbexplorer.sync.RefreshState
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = app().repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val settings by repo.settings.settings.collectAsState(initial = null)
    val status by repo.settings.status.collectAsState(initial = null)
    val counts by repo.dao.observeTypeCounts().collectAsState(initial = emptyList())
    val playedCount by repo.dao.observePlayedCount().collectAsState(initial = 0)
    val stateFlow = remember { RefreshScheduler.state(context) }
    val refresh by stateFlow.collectAsState(initial = RefreshState(false, false, null))

    fun save(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            val before = repo.settings.current()
            repo.settings.update(transform)
            val after = repo.settings.current()
            if (before.wifiOnly != after.wifiOnly) RefreshScheduler.schedulePeriodic(context, after.wifiOnly, replace = true)
            if (before.repo != after.repo) RefreshScheduler.refreshNow(context, after.wifiOnly, force = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        val st = status ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Database", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The database is built once a day on GitHub from IMDb, Steam, RAWG and Wikidata. " +
                            "This phone only downloads the finished file when there is a new one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (st.builtAt > 0) "Current data built " + DateUtils.getRelativeTimeSpanString(st.builtAt)
                        else "No database downloaded yet",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (st.lastCheck > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (st.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                null,
                                tint = if (st.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "  Last checked " + DateUtils.getRelativeTimeSpanString(st.lastCheck) + ": " + st.message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    when {
                        refresh.running -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(refresh.message ?: "Checking…", style = MaterialTheme.typography.bodySmall)
                        }
                        refresh.waitingForNetwork -> Text("Update queued — waiting for Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { RefreshScheduler.refreshNow(context, s.wifiOnly) }) { Text("Check for update") }
                            OutlinedButton(onClick = { RefreshScheduler.refreshNow(context, s.wifiOnly, force = true) }) {
                                Text("Re-download")
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Only download on Wi-Fi", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Updates are about 15–30 MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.wifiOnly, onCheckedChange = { v -> save { it.copy(wifiOnly = v) } })
            }

            Text("On this phone", style = MaterialTheme.typography.titleLarge)
            MediaType.entries.forEach { type ->
                val n = counts.firstOrNull { it.type == type.name }?.n ?: 0
                Text("${type.label}: ${grouped(n)}")
            }
            Text("Marked as played / watched: ${grouped(playedCount)}")

            if (st.serverSources.isNotBlank()) {
                Text("Server sources (last build)", style = MaterialTheme.typography.titleLarge)
                runCatching { JSONObject(st.serverSources) }.getOrNull()?.let { json ->
                    json.keys().forEach { name ->
                        val src = json.optJSONObject(name) ?: return@forEach
                        val ok = src.optBoolean("ok", true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                null,
                                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Text("  $name: ${src.optString("message")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Text("Advanced", style = MaterialTheme.typography.titleLarge)
            RepoField(s.repo) { v -> save { it.copy(repo = v) } }
            TextButton(onClick = { uri.openUri("https://github.com/${s.repo}/actions/workflows/database.yml") }) {
                Text("Open the database build on GitHub")
            }

            Text(
                "Data: IMDb non-commercial datasets (personal use), Steam & SteamSpy, RAWG, Wikidata. " +
                    "Review scores © Metacritic / OpenCritic / Rotten Tomatoes, via Wikidata and RAWG.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RepoField(value: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { text = value }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Database repository (owner/name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }, enabled = text.trim() != value && text.contains('/')) { Text("Save") }
            TextButton(onClick = { onSave(AppSettings.DEFAULT_REPO) }) { Text("Reset") }
        }
    }
}
