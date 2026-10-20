package com.dbexplorer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dbexplorer.data.ExtraEntity
import com.dbexplorer.data.MediaType
import com.dbexplorer.data.Repository
import com.dbexplorer.data.Source
import com.dbexplorer.data.TitleRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(repo: Repository, id: String) : ViewModel() {
    val row: StateFlow<TitleRow?> = repo.dao.observeRow(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val extra: StateFlow<ExtraEntity?> = repo.dao.observeExtra(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch { repo.enrichIfNeeded(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val repo = app().repository
    val vm: DetailViewModel = viewModel(key = "detail-$id") { DetailViewModel(repo, id) }
    val row by vm.row.collectAsState()
    val extra by vm.extra.collectAsState()
    val settings by repo.settings.settings.collectAsState(initial = null)
    val uri = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row?.t?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        val r = row
        if (r == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.padding(32.dp))
            }
            return@Scaffold
        }
        val t = r.t
        val type = MediaType.valueOf(t.type)
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (type == MediaType.GAME) {
                Artwork(r.imageEff, type, Modifier.fillMaxWidth().aspectRatio(460f / 215f).clip(RoundedCornerShape(12.dp)))
                Header(r)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Artwork(r.imageEff, type, Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f)) { Header(r) }
                }
            }

            if (r.genreList.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.genreList.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }

            Scores(r)

            extra?.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            val facts = buildList<Pair<String, String>> {
                t.developer?.let { add("Developer" to it) }
                t.publisher?.let { add("Publisher" to it) }
                priceText(t.price)?.let { add("Price" to it) }
                t.owners?.let { add("Owners (est.)" to it) }
                t.platforms?.let { add("Platforms" to it) }
                extra?.info?.lines()?.forEach { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2 && none { it.first == parts[0] }) add(parts[0] to parts[1])
                }
                add("Source" to sourceLabel(t.source))
            }
            facts.forEach { (k, v) -> Fact(k, v) }

            if (settings?.omdbKey.isNullOrBlank() && t.source == Source.IMDB) {
                Text(
                    "Tip: add a free OMDb key in Settings to get posters, plots and Metascores for movies and shows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                links(r, extra).forEach { (label, url) ->
                    OutlinedButton(onClick = { runCatching { uri.openUri(url) } }) {
                        Text(label)
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 6.dp).size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(r: TitleRow) {
    val t = r.t
    Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    val line = listOfNotNull(
        when (MediaType.valueOf(t.type)) {
            MediaType.MOVIE -> "Movie"
            MediaType.TV -> "TV series"
            MediaType.GAME -> "Game"
        },
        yearText(r),
        t.runtime?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
    ).joinToString(" · ")
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Scores(r: TitleRow) {
    val t = r.t
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        r.metaEff?.let { score ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetascoreBadge(score, big = true)
                Text("  Metascore", style = MaterialTheme.typography.labelLarge)
            }
        }
        t.rating?.let { rating ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFF5B82E))
                    if (t.source == Source.STEAM) {
                        Text(" ${(rating * 10).toInt()}% positive", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(" ${String.format("%.1f", rating)} / 10", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(
                    "${grouped(t.votes)} " + if (t.source == Source.STEAM) "Steam reviews" else "ratings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.width(120.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun sourceLabel(source: String) = when (source) {
    Source.IMDB -> "IMDb datasets"
    Source.STEAM -> "Steam (via SteamSpy)"
    Source.RAWG -> "RAWG"
    else -> source
}

private fun links(r: TitleRow, extra: ExtraEntity?): List<Pair<String, String>> {
    val t = r.t
    val q = Uri.encode(t.title)
    return buildList<Pair<String, String>> {
        when (t.source) {
            Source.IMDB -> add("IMDb" to "https://www.imdb.com/title/${t.externalId}/")
            Source.STEAM -> add("Steam" to "https://store.steampowered.com/app/${t.externalId}/")
            Source.RAWG -> add("RAWG" to "https://rawg.io/games/${t.externalId}")
        }
        add("Metacritic" to (extra?.metacriticUrl ?: "https://www.metacritic.com/search/$q/"))
        if (t.type != MediaType.GAME.name) {
            add("Rotten Tomatoes" to "https://www.rottentomatoes.com/search?search=$q")
        } else if (t.source != Source.STEAM) {
            add("Steam search" to "https://store.steampowered.com/search/?term=$q")
        }
        add("YouTube trailer" to "https://www.youtube.com/results?search_query=$q+trailer")
    }
}
