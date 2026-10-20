package com.dbexplorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.dbexplorer.data.FacetCounts
import com.dbexplorer.data.FacetEntity
import com.dbexplorer.data.FacetKind
import com.dbexplorer.data.Filters
import com.dbexplorer.data.MediaType
import com.dbexplorer.data.PlayedFilter
import com.dbexplorer.data.Repository
import com.dbexplorer.data.SortField
import com.dbexplorer.data.TitleRow
import com.dbexplorer.sync.RefreshScheduler
import com.dbexplorer.sync.RefreshState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class BrowseViewModel(private val repo: Repository, type: MediaType) : ViewModel() {
    private val _filters = MutableStateFlow(Filters(type))
    val filters: StateFlow<Filters> = _filters.asStateFlow()
    private val settled = _filters.debounce(250)

    val pages: Flow<PagingData<TitleRow>> =
        settled.flatMapLatest { repo.pages(it) }.cachedIn(viewModelScope)

    val count: StateFlow<Int?> = settled.flatMapLatest { repo.count(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val total: StateFlow<Int?> = repo.count(Filters(type))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val genres: StateFlow<List<FacetEntity>> = repo.facets(FacetKind.GENRE, type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val platforms: StateFlow<List<FacetEntity>> = repo.facets(FacetKind.PLATFORM, type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Kept here so the scroll position survives opening a detail screen and switching tabs. */
    val listState = LazyListState()
    var scrolledFor: Filters? = null

    fun update(transform: (Filters) -> Filters) = _filters.update(transform)

    /** Live per-option counts, only computed while the filter sheet is open. */
    val sheetOpen = MutableStateFlow(false)
    val facetCounts: StateFlow<FacetCounts?> = combine(settled, sheetOpen) { f, open -> f to open }
        .mapLatest { (f, open) -> if (open) repo.facetCounts(f) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setPlayed(id: String, played: Boolean) {
        viewModelScope.launch { repo.setPlayed(id, played) }
    }
}

@Composable
fun BrowseScreen(type: MediaType, onOpen: (String) -> Unit) {
    val repo = app().repository
    val vm: BrowseViewModel = viewModel(key = "browse-${type.name}") { BrowseViewModel(repo, type) }
    val filters by vm.filters.collectAsState()
    val count by vm.count.collectAsState()
    val total by vm.total.collectAsState()
    val genres by vm.genres.collectAsState()
    val platforms by vm.platforms.collectAsState()
    val items = vm.pages.collectAsLazyPagingItems()
    val showFilters by vm.sheetOpen.collectAsState()
    val facetCounts by vm.facetCounts.collectAsState()
    val listState = vm.listState

    // Jump back to the top whenever the query changes (but not when returning to the screen).
    LaunchedEffect(filters) {
        if (vm.scrolledFor != null && vm.scrolledFor != filters) listState.scrollToItem(0)
        vm.scrolledFor = filters
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { q -> vm.update { it.copy(query = q) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Search ${type.label.lowercase()}") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { vm.update { it.copy(query = "") } }) { Icon(Icons.Filled.Clear, "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )

        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortChip(filters, onChange = { f -> vm.update { f } })
            FilterChip(
                selected = filters.activeCount > 0,
                onClick = { vm.sheetOpen.value = true },
                label = { Text(if (filters.activeCount > 0) "Filters (${filters.activeCount})" else "Filters") },
                leadingIcon = { Icon(Icons.Filled.FilterList, null, Modifier.size(18.dp)) },
            )
            if (filters.activeCount > 0) {
                TextButton(onClick = { vm.update { it.cleared() } }) { Text("Reset") }
            }
            Spacer(Modifier.weight(1f))
            count?.let {
                Text(
                    "${grouped(it)} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            total == 0 -> EmptyCatalogue(type)
            count == 0 -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing matches these filters.")
                    TextButton(onClick = { vm.update { Filters(type) } }) { Text("Clear search & filters") }
                }
            }
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = items.itemCount, key = items.itemKey { it.t.id }) { i ->
                    items[i]?.let { row ->
                        TitleCard(
                            row = row,
                            type = type,
                            onClick = { onOpen(row.t.id) },
                            onTogglePlayed = { vm.setPlayed(row.t.id, !row.played) },
                        )
                    }
                }
                // More rows are fetched page by page as you scroll; show that it is happening.
                if (items.loadState.append is LoadState.Loading) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            filters = filters,
            genres = genres,
            platforms = platforms,
            onChange = { f -> vm.update { f } },
            resultCount = count,
            liveCounts = facetCounts,
            onDismiss = { vm.sheetOpen.value = false },
        )
    }
}

@Composable
private fun SortChip(filters: Filters, onChange: (Filters) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(filters.sort.label) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) },
            trailingIcon = {
                Icon(
                    if (filters.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    if (filters.descending) "Descending" else "Ascending",
                    Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { field ->
                val selected = field == filters.sort
                DropdownMenuItem(
                    text = { Text(field.label, fontWeight = if (selected) FontWeight.Bold else null) },
                    trailingIcon = {
                        if (selected) Icon(
                            if (filters.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null,
                        )
                    },
                    onClick = {
                        open = false
                        onChange(
                            if (selected) filters.copy(descending = !filters.descending)
                            // Titles read naturally A→Z; everything else best-first.
                            else filters.copy(sort = field, descending = field != SortField.TITLE)
                        )
                    },
                )
            }
        }
    }
}

fun playedLabel(type: MediaType, filter: PlayedFilter) = when (filter) {
    PlayedFilter.ANY -> "All"
    PlayedFilter.DONE -> type.doneLabel
    PlayedFilter.NOT_DONE -> "Not ${type.doneLabel.lowercase()}"
}

@Composable
private fun TitleCard(row: TitleRow, type: MediaType, onClick: () -> Unit, onTogglePlayed: () -> Unit) {
    val t = row.t
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val artModifier = if (type == MediaType.GAME) {
                Modifier.width(112.dp).height(56.dp)
            } else {
                Modifier.width(56.dp).height(84.dp)
            }
            Artwork(t.imageUrl, type, artModifier.clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    yearText(row),
                    row.genreList.take(3).joinToString(", ").ifBlank { null },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (type == MediaType.GAME && row.platformList.isNotEmpty()) {
                    Text(
                        sortPlatforms(row.platformList).joinToString(" · ") { shortPlatform(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    t.rating?.let { r ->
                        Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Color(0xFFF5B82E))
                        Text(
                            " " + String.format("%.1f", r) + "  (${compact(t.votes)})",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (type == MediaType.GAME) {
                        priceText(t.price)?.let {
                            Text("   $it", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            t.metascore?.let { MetascoreBadge(it) }
            IconButton(onClick = onTogglePlayed) {
                if (row.played) {
                    Icon(Icons.Filled.CheckCircle, "Unmark ${type.doneLabel.lowercase()}", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.RadioButtonUnchecked, "Mark as ${type.doneLabel.lowercase()}", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** Compact platform names for list rows, e.g. "PlayStation 5" → "PS5". */
fun shortPlatform(name: String): String = when (name) {
    "PlayStation 5" -> "PS5"
    "PlayStation 4" -> "PS4"
    "PlayStation 3" -> "PS3"
    "PlayStation 2" -> "PS2"
    "PlayStation" -> "PS1"
    "PS Vita" -> "Vita"
    "Xbox Series S/X" -> "Xbox Series"
    "Nintendo Switch 2" -> "Switch 2"
    "Nintendo Switch" -> "Switch"
    "Nintendo 3DS" -> "3DS"
    "Nintendo DS" -> "DS"
    else -> name
}

fun yearText(row: TitleRow): String? {
    val y = row.t.year ?: return null
    val end = row.t.endYear
    return when {
        row.t.type != MediaType.TV.name -> y.toString()
        end == null -> "$y–"
        end == y -> y.toString()
        else -> "$y–$end"
    }
}

fun priceText(price: Double?): String? = when {
    price == null -> null
    price == 0.0 -> "Free"
    else -> String.format("$%.2f", price)
}

@Composable
private fun EmptyCatalogue(type: MediaType) {
    val context = LocalContext.current
    val stateFlow = remember { RefreshScheduler.state(context) }
    val state by stateFlow.collectAsState(initial = RefreshState(false, false, null))
    val settings by app().repository.settings.settings.collectAsState(initial = null)
    val status by app().repository.settings.status.collectAsState(initial = null)
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(type.icon(), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        when {
            state.running -> {
                Text(state.message ?: "Downloading the database…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.waitingForNetwork -> Text("Waiting for Wi-Fi to download the database. You can allow mobile data in Settings.")
            else -> {
                Text(
                    if (status?.ok == false) status?.message.orEmpty()
                    else "No ${type.label.lowercase()} downloaded yet."
                )
                TextButton(onClick = {
                    RefreshScheduler.refreshNow(context, settings?.wifiOnly ?: false, force = true)
                }) { Text("Download now") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun RefreshBanner() {
    val context = LocalContext.current
    val stateFlow = remember { RefreshScheduler.state(context) }
    val state by stateFlow.collectAsState(initial = RefreshState(false, false, null))
    if (state.running) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.message ?: "Checking for updates…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
