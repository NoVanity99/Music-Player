package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.ListeningStats
import com.novanity.musicplayer.data.MixKind
import com.novanity.musicplayer.data.StatsRange
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumCard
import com.novanity.musicplayer.ui.components.CardRow
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.SongRow

private val StatsColors = listOf(Color(0xFF7F5AF0), Color(0xFF2CB67D))

/** "Your stats": listening time, top artists/songs/albums, streak and when you listen. */
@Composable
internal fun StatsScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val stats by vm.stats.collectAsStateWithLifecycle()
    val range by vm.statsRange.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val lib by vm.grouped.collectAsStateWithLifecycle()

    CollectionScaffold("Your stats", StatsColors.first(), { vm.back() }, emptyList(), contentPadding) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(StatsColors.first().copy(alpha = 0.55f), Color.Transparent)))
                    .padding(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 8.dp),
            ) {
                Text("Your stats", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsRange.entries.forEach { r ->
                        FilterChip(selected = r == range, onClick = { vm.statsRange.value = r }, label = { Text(r.label) })
                    }
                }
            }
        }
        val s = stats
        when {
            s == null -> item {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            s.plays == 0 -> item { EmptyHint("Play some music and your stats will show up here.") }
            else -> {
                item { Tiles(s) }
                s.personality?.let { p -> item { HoursCard(s, p.title, p.blurb) } }
                item { SectionHeader("Top artists") }
                itemsIndexed(s.topArtists, key = { _, a -> "artist-${a.item}" }) { i, ranked ->
                    RankRow(i + 1, ranked.item, plays(ranked.plays)) {
                        if (lib?.artistsByName?.containsKey(ranked.item) == true) vm.open(Destination.ArtistDetail(ranked.item))
                    }
                }
                item { SectionHeader("Top songs") }
                itemsIndexed(s.topSongs, key = { _, r -> "song-${r.item.id}" }) { i, ranked ->
                    val song = ranked.item
                    val liked = song.id in likedIds
                    SongRow(
                        song = song,
                        liked = liked,
                        trackNumber = i + 1,
                        subtitle = "${song.artist} • ${plays(ranked.plays)}",
                        onClick = { actions.play(s.topSongs.map { it.item }, i, "Your top songs") },
                        menu = actions.songMenu(song, liked),
                    )
                }
                if (s.topAlbums.isNotEmpty()) {
                    item { SectionHeader("Top albums") }
                    item {
                        CardRow(s.topAlbums.map { it.item }, key = { it.id }) { album, m ->
                            AlbumCard(album, onClick = { vm.open(Destination.AlbumDetail(album.id)) }, modifier = m,
                                onPlay = { actions.play(album.songs, 0, album.title) })
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

@Composable
private fun Tiles(s: ListeningStats) {
    val tiles = listOf(
        formatMinutes(s.minutes) to "listened",
        "${s.plays}" to if (s.plays == 1) "play" else "plays",
        "${s.distinctSongs}" to if (s.distinctSongs == 1) "different song" else "different songs",
        "${s.streakDays}" to if (s.streakDays == 1) "day streak" else "day streak 🔥",
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val perRow = if (maxWidth >= 600.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (value, label) -> Tile(value, label, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatMinutes(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    minutes < 60 * 100 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes / 60}h"
}

/** The listening personality and a 24-hour bar chart of when you play music. */
@Composable
private fun HoursCard(s: ListeningStats, title: String, blurb: String) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val peak = s.byHour.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peakHour = s.byHour.indexOf(s.byHour.max())
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(StatsColors.first().copy(alpha = 0.35f), StatsColors.last().copy(alpha = 0.2f))))
            .padding(18.dp),
    ) {
        Text("Your listening personality", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            blurb + (s.busiestWeekday?.let { " ${it.name.lowercase().replaceFirstChar(Char::uppercase)}s are your busiest day." } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * 23) / 24
            s.byHour.forEachIndexed { hour, count ->
                val h = (size.height * count / peak).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (hour == peakHour) accent else dim,
                    topLeft = Offset(hour * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 3),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("12am", "6am", "12pm", "6pm", "12am").forEachIndexed { i, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> androidx.compose.ui.text.style.TextAlign.Start
                        4 -> androidx.compose.ui.text.style.TextAlign.End
                        else -> androidx.compose.ui.text.style.TextAlign.Center
                    },
                )
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun mixIcon(kind: MixKind): ImageVector = when (kind) {
    MixKind.FORGOTTEN -> Icons.Rounded.Favorite
    MixKind.NEVER_PLAYED -> Icons.Rounded.NewReleases
    MixKind.DEEP_CUTS -> Icons.Rounded.AutoAwesome
    MixKind.TIME_MACHINE -> Icons.Rounded.History
}

/** One of the automatic mixes, shown like a playlist. */
@Composable
internal fun MixScreen(kind: MixKind, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val songs = mixes.firstOrNull { it.kind == kind }?.songs.orEmpty()
    CollectionScaffold(kind.title, kind.colors.first(), { vm.back() }, actions.collectionMenu(songs), contentPadding) {
        item {
            Hero(
                kind.colors.first(),
                { m -> GradientArt(kind.colors, mixIcon(kind), m, RoundedCornerShape(10.dp)) },
                kind.title,
                kind.description,
                meta("Mix", songs),
            )
        }
        item { PlayShuffleRow(songs, actions, kind.title) }
        if (songs.isEmpty()) item { EmptyHint("Nothing in this mix right now. Check back after some more listening.") }
        songItems(songs, likedIds, actions, kind.title)
    }
}
