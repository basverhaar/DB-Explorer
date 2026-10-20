package com.dbexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dbexplorer.data.FacetCounts
import com.dbexplorer.data.FacetEntity
import com.dbexplorer.data.Filters
import com.dbexplorer.data.MediaType
import com.dbexplorer.data.PlayedFilter
import com.dbexplorer.data.SortField
import java.util.Calendar
import kotlin.math.roundToInt

private const val MIN_YEAR = 1900

/** Genres shown before "Show all". */
private const val GENRES_COLLAPSED = 12

/** Platform generation groups shown before "Show all" (current + previous). */
private const val PLATFORM_GROUPS_COLLAPSED = 2

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filters: Filters,
    genres: List<FacetEntity>,
    platforms: List<FacetEntity>,
    resultCount: Int?,
    liveCounts: FacetCounts?,
    onChange: (Filters) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allGenres by rememberSaveable { mutableStateOf(false) }
    var allPlatforms by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column {
            // Scrollable filters; takes the space left above the pinned buttons.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortField.entries.forEach { field ->
                        FilterChip(
                            selected = filters.sort == field,
                            onClick = { onChange(filters.copy(sort = field, descending = field != SortField.TITLE)) },
                            label = { Text(field.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (filters.descending) "Highest / newest / Z first" else "Lowest / oldest / A first",
                        Modifier.weight(1f),
                    )
                    Switch(checked = filters.descending, onCheckedChange = { onChange(filters.copy(descending = it)) })
                }

                Section("Show")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayedFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filters.played == option,
                            onClick = { onChange(filters.copy(played = option)) },
                            label = { Text(playedLabel(filters.type, option)) },
                        )
                    }
                }

                if (platforms.isNotEmpty()) {
                    Section("Platforms" + if (filters.platforms.isNotEmpty()) " (any of ${filters.platforms.size})" else "")
                    val groups = groupPlatforms(platforms)
                    groups.forEachIndexed { i, (group, items) ->
                        // Collapsed: newest generations, plus anything already selected further down.
                        val visible = if (allPlatforms || i < PLATFORM_GROUPS_COLLAPSED) items
                        else items.filter { it.name in filters.platforms }
                        if (visible.isEmpty()) return@forEachIndexed
                        Text(
                            group,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            visible.forEach { p ->
                                OptionChip(p, filters.platforms, liveCounts?.platforms) { on ->
                                    onChange(filters.copy(platforms = if (on) filters.platforms - p.name else filters.platforms + p.name))
                                }
                            }
                        }
                    }
                    if (groups.size > PLATFORM_GROUPS_COLLAPSED) {
                        ShowMore(allPlatforms, "platforms", groups.drop(PLATFORM_GROUPS_COLLAPSED).sumOf { it.second.size }) {
                            allPlatforms = !allPlatforms
                        }
                    }
                }

                if (genres.isNotEmpty()) {
                    Section("Genres" + if (filters.genres.isNotEmpty()) " (must match all ${filters.genres.size})" else "")
                    val visible = if (allGenres) genres
                    else genres.take(GENRES_COLLAPSED) + genres.drop(GENRES_COLLAPSED).filter { it.name in filters.genres }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visible.forEach { g ->
                            OptionChip(g, filters.genres, liveCounts?.genres) { on ->
                                onChange(filters.copy(genres = if (on) filters.genres - g.name else filters.genres + g.name))
                            }
                        }
                    }
                    if (genres.size > GENRES_COLLAPSED) {
                        ShowMore(allGenres, "genres", genres.size - GENRES_COLLAPSED) { allGenres = !allGenres }
                    }
                }

                val from = filters.minYear ?: MIN_YEAR
                val to = filters.maxYear ?: maxYear
                Section("Release year: " + if (filters.minYear == null && filters.maxYear == null) "any" else "$from – $to")
                RangeSlider(
                    value = from.toFloat()..to.toFloat(),
                    onValueChange = { r ->
                        val lo = r.start.roundToInt()
                        val hi = r.endInclusive.roundToInt()
                        onChange(
                            filters.copy(
                                minYear = lo.takeIf { it > MIN_YEAR },
                                maxYear = hi.takeIf { it < maxYear },
                            )
                        )
                    },
                    valueRange = MIN_YEAR.toFloat()..maxYear.toFloat(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("This year" to 0, "Last 5 years" to 5, "Last 10 years" to 10, "Before 2000" to -1).forEach { (label, n) ->
                        val now = maxYear - 1
                        val (lo, hi) = if (n < 0) null to 1999 else (now - n) to null
                        FilterChip(
                            selected = filters.minYear == lo && filters.maxYear == hi,
                            onClick = { onChange(filters.copy(minYear = lo, maxYear = hi)) },
                            label = { Text(label) },
                        )
                    }
                }

                Section("Minimum user rating: " + if (filters.minRating > 0) String.format("%.1f", filters.minRating) else "any")
                Slider(
                    value = filters.minRating.toFloat(),
                    onValueChange = { onChange(filters.copy(minRating = (it * 2).roundToInt() / 2.0)) },
                    valueRange = 0f..10f,
                    steps = 19,
                )

                Section("Minimum critic score: " + if (filters.minMetascore > 0) "${filters.minMetascore}" else "any")
                Slider(
                    value = filters.minMetascore.toFloat(),
                    onValueChange = { onChange(filters.copy(minMetascore = (it / 5).roundToInt() * 5)) },
                    valueRange = 0f..100f,
                    steps = 19,
                )

                Section(if (filters.type == MediaType.GAME) "Minimum reviews" else "Minimum votes")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1_000, 10_000, 50_000, 100_000, 500_000).forEach { n ->
                        FilterChip(
                            selected = filters.minVotes == n,
                            onClick = { onChange(filters.copy(minVotes = n)) },
                            label = { Text(if (n == 0) "Any" else compact(n)) },
                        )
                    }
                }

                if (filters.type == MediaType.GAME) {
                    Section("Price (Steam)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<Pair<String, Double?>>("Any" to null, "Free" to 0.0, "≤ $5" to 5.0, "≤ $10" to 10.0, "≤ $20" to 20.0, "≤ $40" to 40.0)
                            .forEach { (label, max) ->
                                FilterChip(
                                    selected = filters.maxPrice == max,
                                    onClick = { onChange(filters.copy(maxPrice = max)) },
                                    label = { Text(label) },
                                )
                            }
                    }
                }
            }

            // Pinned to the bottom of the sheet so they never need scrolling to.
            Surface(tonalElevation = 3.dp) {
                Column {
                    HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = { onChange(filters.cleared()) }, Modifier.weight(1f)) { Text("Reset filters") }
                        Button(onClick = onDismiss, Modifier.weight(1f)) {
                            Text(
                                when (resultCount) {
                                    null -> "Show results"
                                    0 -> "No results"
                                    1 -> "Show 1 result"
                                    else -> "Show ${grouped(resultCount)} results"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A genre/platform chip. Its number is how many results picking it would give with the
 * other filters as they are now; options that would give nothing are greyed out.
 */
@Composable
private fun OptionChip(
    facet: FacetEntity,
    selected: Set<String>,
    live: Map<String, Int>?,
    onToggle: (wasOn: Boolean) -> Unit,
) {
    val on = facet.name in selected
    val n = if (live != null) live[facet.name] ?: 0 else facet.count
    FilterChip(
        selected = on,
        onClick = { onToggle(on) },
        enabled = on || n > 0,
        label = { Text("${facet.name}  ${compact(n)}") },
    )
}

@Composable
private fun ShowMore(expanded: Boolean, what: String, hidden: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(if (expanded) "Show fewer $what" else "Show all $what (+$hidden)")
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}
