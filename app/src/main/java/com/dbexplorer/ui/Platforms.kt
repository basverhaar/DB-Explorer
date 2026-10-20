package com.dbexplorer.ui

import com.dbexplorer.data.FacetEntity

/** Console generations, newest first, so the platform you own now is at the top. */
val PLATFORM_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Current generation" to listOf("Nintendo Switch 2", "PlayStation 5", "Xbox Series S/X", "PC"),
    "Previous generation" to listOf("Nintendo Switch", "PlayStation 4", "Xbox One", "Wii U", "Nintendo 3DS", "PS Vita"),
    "Older consoles" to listOf(
        "PlayStation 3", "Xbox 360", "Wii", "Nintendo DS", "PSP",
        "PlayStation 2", "Xbox", "GameCube", "Game Boy Advance", "Dreamcast",
        "PlayStation", "Nintendo 64", "SEGA Saturn", "Game Boy Color", "Game Boy",
        "SNES", "Genesis", "NES",
    ),
    "Computers, mobile & VR" to listOf("macOS", "Linux", "iOS", "Android", "Meta Quest", "Web", "Stadia"),
)

private val PLATFORM_RANK: Map<String, Int> =
    PLATFORM_GROUPS.flatMap { it.second }.withIndex().associate { (i, name) -> name to i }

/** Sorts platform names by generation; unknown platforms go last. */
fun sortPlatforms(names: List<String>): List<String> =
    names.sortedBy { PLATFORM_RANK[it] ?: Int.MAX_VALUE }

/** Groups the platforms present in the catalogue under generation headings. */
fun groupPlatforms(facets: List<FacetEntity>): List<Pair<String, List<FacetEntity>>> {
    val byName = facets.associateBy { it.name }
    val groups = PLATFORM_GROUPS.mapNotNull { (title, names) ->
        names.mapNotNull { byName[it] }.takeIf { it.isNotEmpty() }?.let { title to it }
    }
    val known = PLATFORM_RANK.keys
    val other = facets.filter { it.name !in known }.sortedByDescending { it.count }
    return if (other.isEmpty()) groups else groups + ("Other" to other)
}
