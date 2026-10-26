package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryFilter
import com.novanity.musicplayer.ui.QuickPick
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.AlbumCard
import com.novanity.musicplayer.ui.components.ArtistCard
import com.novanity.musicplayer.ui.components.EmptyState
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.LikedArt
import com.novanity.musicplayer.ui.components.PlaylistArt
import com.novanity.musicplayer.ui.components.PlaylistTile
import com.novanity.musicplayer.ui.components.QuickPickTile
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.ShuffleGradient
import com.novanity.musicplayer.ui.components.SongCard
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val home by vm.home.collectAsStateWithLifecycle()
    val liked by vm.likedSongs.collectAsStateWithLifecycle()
    val grouped by vm.grouped.collectAsStateWithLifecycle()
    val accentWash = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    Box(Modifier.fillMaxSize()) {
        // Soft accent glow behind the header, like the big streaming apps.
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Brush.verticalGradient(listOf(accentWash, MaterialTheme.colorScheme.background)))
        )
        when {
            home.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            home.isEmpty -> Column(Modifier.statusBarsPadding()) {
                HomeHeader(onSettings = { vm.open(Destination.Settings) })
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "No music yet",
                    message = "Pick the folders where your music lives, or copy some audio files (MP3, FLAC, M4A, OGG…) to your phone.",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = { vm.open(Destination.Settings) }) { Text("Choose music folders") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = vm::refresh) { Text("Rescan") }
                        }
                    },
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item { Spacer(Modifier.statusBarsPadding()) }
                item { HomeHeader(onSettings = { vm.open(Destination.Settings) }) }
                item {
                    QuickPicksGrid(home.quickPicks) { pick ->
                        when (pick) {
                            is QuickPick.Liked -> vm.open(Destination.LikedSongs)
                            is QuickPick.ShuffleAll -> grouped?.songs?.let { actions.shuffle(it, "Your library") }
                            is QuickPick.AlbumPick -> vm.open(Destination.AlbumDetail(pick.album.id))
                            is QuickPick.PlaylistPick -> vm.open(Destination.PlaylistDetail(pick.playlist.id))
                        }
                    }
                }
                if (home.recentlyPlayed.isNotEmpty()) {
                    item { SectionHeader("Recently played", action = "See all") { vm.open(Destination.RecentlyPlayed) } }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(home.recentlyPlayed, key = { _, s -> s.id }) { i, song ->
                                SongCard(song, onClick = { actions.play(home.recentlyPlayed, i, "Recently played") })
                            }
                        }
                    }
                }
                if (home.recentlyAdded.isNotEmpty()) {
                    item { SectionHeader("Recently added", action = "See all") { vm.showLibrary(LibraryFilter.ALBUMS) } }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(home.recentlyAdded, key = { it.id }) { album ->
                                AlbumCard(album, onClick = { vm.open(Destination.AlbumDetail(album.id)) })
                            }
                        }
                    }
                }
                if (home.topArtists.isNotEmpty()) {
                    item { SectionHeader("Your artists", action = "See all") { vm.showLibrary(LibraryFilter.ARTISTS) } }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(home.topArtists, key = { it.name }) { artist ->
                                ArtistCard(artist, onClick = { vm.open(Destination.ArtistDetail(artist.name)) })
                            }
                        }
                    }
                }
                if (home.playlists.isNotEmpty()) {
                    item { SectionHeader("Your playlists", action = "See all") { vm.showLibrary(LibraryFilter.PLAYLISTS) } }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(home.playlists, key = { it.id }) { playlist ->
                                PlaylistTile(playlist, onClick = { vm.open(Destination.PlaylistDetail(playlist.id)) })
                            }
                        }
                    }
                }
                if (liked.isEmpty() && home.recentlyPlayed.isEmpty()) {
                    item {
                        Text(
                            "Tip: tap ♡ on the player to build your Liked Songs.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HomeHeader(onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(greeting(), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun QuickPicksGrid(picks: List<QuickPick>, onPick: (QuickPick) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        picks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pick ->
                    val title = when (pick) {
                        is QuickPick.Liked -> "Liked Songs"
                        is QuickPick.ShuffleAll -> "Shuffle all"
                        is QuickPick.AlbumPick -> pick.album.title
                        is QuickPick.PlaylistPick -> pick.playlist.name
                    }
                    QuickPickTile(
                        title = title,
                        onClick = { onPick(pick) },
                        modifier = Modifier.weight(1f),
                        art = { m ->
                            when (pick) {
                                is QuickPick.Liked -> LikedArt(m, shape = RectangleShape)
                                is QuickPick.ShuffleAll -> GradientArt(ShuffleGradient, Icons.Rounded.Shuffle, m, RectangleShape)
                                is QuickPick.AlbumPick -> AlbumArt(pick.album.songs.first(), m, shape = RectangleShape)
                                is QuickPick.PlaylistPick -> PlaylistArt(pick.playlist.covers, m, shape = RectangleShape)
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
