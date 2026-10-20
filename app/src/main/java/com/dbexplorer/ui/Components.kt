package com.dbexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.dbexplorer.data.MediaType
import java.text.NumberFormat

fun MediaType.icon() = when (this) {
    MediaType.MOVIE -> Icons.Filled.Movie
    MediaType.TV -> Icons.Filled.Tv
    MediaType.GAME -> Icons.Filled.SportsEsports
}

@Composable
fun Artwork(url: String?, type: MediaType, modifier: Modifier) {
    val placeholder = @Composable {
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(type.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (url.isNullOrBlank()) {
        placeholder()
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            loading = { placeholder() },
            error = { placeholder() },
        )
    }
}

@Composable
fun MetascoreBadge(score: Int, big: Boolean = false) {
    val size = if (big) 48.dp else 32.dp
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(metascoreColor(score)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            score.toString(),
            color = if (score in 50..74) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 20.sp else 14.sp,
        )
    }
}

fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format("%.1fK", n / 1000.0)
    else -> n.toString()
}

fun grouped(n: Int): String = NumberFormat.getIntegerInstance().format(n)
