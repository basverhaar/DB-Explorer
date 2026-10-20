package com.dbexplorer.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dbexplorer.data.AppSettings
import com.dbexplorer.data.MediaType
import com.dbexplorer.data.Source
import com.dbexplorer.sync.RefreshScheduler
import com.dbexplorer.sync.RefreshState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = app().repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val settings by repo.settings.settings.collectAsState(initial = null)
    val statuses by repo.settings.statuses.collectAsState(initial = emptyList())
    val counts by repo.dao.observeTypeCounts().collectAsState(initial = emptyList())
    val stateFlow = remember { RefreshScheduler.state(context) }
    val refresh by stateFlow.collectAsState(initial = RefreshState(false, false, null))
    var confirmClear by remember { mutableStateOf(false) }

    fun save(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            val before = repo.settings.current()
            repo.settings.update(transform)
            val after = repo.settings.current()
            if (before.wifiOnly != after.wifiOnly) RefreshScheduler.scheduleDaily(context, after.wifiOnly, replace = true)
            // Pull in new data straight away when a source or key was added.
            val gained = (after.imdbEnabled && !before.imdbEnabled) ||
                (after.steamEnabled && !before.steamEnabled) ||
                (after.omdbKey.isNotBlank() && after.omdbKey != before.omdbKey) ||
                (after.rawgKey.isNotBlank() && after.rawgKey != before.rawgKey)
            if (gained) RefreshScheduler.refreshNow(context, after.wifiOnly)
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
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Refresh ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Daily refresh", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "All databases are refreshed automatically once a day in the background.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when {
                        refresh.running -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(refresh.message ?: "Refreshing…", style = MaterialTheme.typography.bodySmall)
                        }
                        refresh.waitingForNetwork -> Text("Refresh queued — waiting for Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                        else -> Button(onClick = { RefreshScheduler.refreshNow(context, s.wifiOnly) }) { Text("Refresh now") }
                    }
                    ToggleRow("Only download on Wi-Fi", "The IMDb download is about 200 MB.", s.wifiOnly) { v ->
                        save { it.copy(wifiOnly = v) }
                    }
                }
            }

            // ---- Sources ----
            Text("Sources", style = MaterialTheme.typography.titleLarge)
            ToggleRow("IMDb — movies & TV", "Official IMDb datasets, updated daily. No key needed.", s.imdbEnabled) { v ->
                save { it.copy(imdbEnabled = v) }
            }
            if (s.imdbEnabled) {
                Text("Keep titles with at least this many IMDb votes:", style = MaterialTheme.typography.bodySmall)
                Choices(listOf(250, 1000, 5000, 25000), s.imdbMinVotes) { v -> save { it.copy(imdbMinVotes = v) } }
            }
            ToggleRow("Steam — PC games", "Steam store & SteamSpy. Metascores from the Steam store. No key needed.", s.steamEnabled) { v ->
                save { it.copy(steamEnabled = v) }
            }
            if (s.steamEnabled) {
                Text("Keep games with at least this many Steam reviews:", style = MaterialTheme.typography.bodySmall)
                Choices(listOf(50, 200, 1000, 5000), s.steamMinReviews) { v -> save { it.copy(steamMinReviews = v) } }
            }

            // ---- Keys ----
            Text("Optional free API keys", style = MaterialTheme.typography.titleLarge)
            KeyField(
                label = "OMDb API key",
                help = "Posters, plots, cast and Metacritic Metascores for movies & shows (1,000 titles/day).",
                value = s.omdbKey,
                onSave = { v -> save { it.copy(omdbKey = v) } },
                onGetKey = { uri.openUri("https://www.omdbapi.com/apikey.aspx") },
            )
            KeyField(
                label = "RAWG API key",
                help = "Metacritic's top-rated games across all platforms, including consoles.",
                value = s.rawgKey,
                onSave = { v -> save { it.copy(rawgKey = v) } },
                onGetKey = { uri.openUri("https://rawg.io/apidocs") },
            )

            // ---- Status ----
            Text("Last refresh", style = MaterialTheme.typography.titleLarge)
            statuses.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (st.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        null,
                        tint = if (st.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(statusLabel(st.source), style = MaterialTheme.typography.titleSmall)
                        val time = if (st.lastSuccess > 0) {
                            "Updated " + DateUtils.getRelativeTimeSpanString(st.lastSuccess)
                        } else {
                            "Not downloaded yet"
                        }
                        Text("$time · ${st.message}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- Database ----
            Text("Database", style = MaterialTheme.typography.titleLarge)
            MediaType.entries.forEach { type ->
                val n = counts.firstOrNull { it.type == type.name }?.n ?: 0
                Text("${type.label}: ${grouped(n)}")
            }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear database") }

            Text(
                "Data: IMDb non-commercial datasets (personal use), Steam & SteamSpy, RAWG, OMDb. " +
                    "Metascores © Metacritic, obtained through Steam, RAWG and OMDb.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear database?") },
            text = { Text("All downloaded titles are deleted. They are downloaded again on the next refresh.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { repo.clearAll() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun statusLabel(source: String) = when (source) {
    Source.IMDB -> "IMDb"
    Source.STEAM -> "Steam catalogue"
    Source.STEAM_DETAILS -> "Steam details & Metascores"
    Source.RAWG -> "RAWG / Metacritic games"
    Source.OMDB -> "OMDb posters & Metascores"
    else -> source
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { n ->
            FilterChip(selected = n == selected, onClick = { onSelect(n) }, label = { Text(grouped(n)) })
        }
    }
}

@Composable
private fun KeyField(label: String, help: String, value: String, onSave: (String) -> Unit, onGetKey: () -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { text = value }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }, enabled = text.trim() != value) { Text("Save") }
            TextButton(onClick = onGetKey) { Text("Get a free key") }
        }
    }
}
