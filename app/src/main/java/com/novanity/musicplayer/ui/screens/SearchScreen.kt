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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryFilter
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.AlbumCard
import com.novanity.musicplayer.ui.components.ArtistCard
import com.novanity.musicplayer.ui.components.CollectionRow
import com.novanity.musicplayer.ui.components.EmptyState
import com.novanity.musicplayer.ui.components.FolderGradient
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.PlaylistArt
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.SongRow
import com.novanity.musicplayer.ui.components.songCount

private data class BrowseTile(val title: String, val colors: List<Color>, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun SearchScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { Spacer(Modifier.statusBarsPadding()) }
        item {
            Text(
                "Search",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 16.dp),
            )
        }
        item { SearchBox(query, vm::setSearchQuery) }

        if (query.isBlank()) {
            item { SectionHeader("Browse your library") }
            val tiles = listOf(
                BrowseTile("Songs", listOf(Color(0xFFFF375F), Color(0xFFB0124A)), Icons.Rounded.MusicNote) { vm.showLibrary(LibraryFilter.SONGS) },
                BrowseTile("Albums", listOf(Color(0xFFFF9F0A), Color(0xFFC2410C)), Icons.Rounded.Album) { vm.showLibrary(LibraryFilter.ALBUMS) },
                BrowseTile("Artists", listOf(Color(0xFF0A84FF), Color(0xFF1E3A8A)), Icons.Rounded.Person) { vm.showLibrary(LibraryFilter.ARTISTS) },
                BrowseTile("Playlists", listOf(Color(0xFFBF5AF2), Color(0xFF5B21B6)), Icons.AutoMirrored.Rounded.QueueMusic) { vm.showLibrary(LibraryFilter.PLAYLISTS) },
                BrowseTile("Liked Songs", listOf(Color(0xFF5B2BFF), Color(0xFF8E7BFF)), Icons.Rounded.Favorite) { vm.open(Destination.LikedSongs) },
                BrowseTile("Recently played", listOf(Color(0xFF30B158), Color(0xFF0F5132)), Icons.Rounded.History) { vm.open(Destination.RecentlyPlayed) },
                BrowseTile("Folders", listOf(Color(0xFF64D2FF), Color(0xFF0E7490)), Icons.Rounded.Folder) { vm.showLibrary(LibraryFilter.FOLDERS) },
            )
            items(tiles.chunked(2)) { row ->
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { BrowseCard(it, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            return@LazyColumn
        }

        if (results.isEmpty) {
            item {
                EmptyState(
                    Icons.Rounded.SearchOff,
                    "No results for “${results.query}”",
                    "Check the spelling, or try searching for an artist, album or song.",
                    modifier = Modifier.height(360.dp),
                )
            }
            return@LazyColumn
        }

        // Top result, Spotify-style: the best artist, album or song match.
        val topArtist = results.artists.firstOrNull()?.takeIf { it.name.startsWith(results.query, ignoreCase = true) }
        val topAlbum = results.albums.firstOrNull()?.takeIf { it.title.startsWith(results.query, ignoreCase = true) }
        val topSong = results.songs.firstOrNull()
        if (topArtist != null || topAlbum != null || topSong != null) {
            item { SectionHeader("Top result") }
            item {
                Surface(
                    onClick = {
                        when {
                            topArtist != null -> vm.open(Destination.ArtistDetail(topArtist.name))
                            topAlbum != null -> vm.open(Destination.AlbumDetail(topAlbum.id))
                            topSong != null -> actions.play(listOf(topSong), source = "Search")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val (song, title, kind) = when {
                            topArtist != null -> Triple(topArtist.songs.first(), topArtist.name, "Artist")
                            topAlbum != null -> Triple(topAlbum.songs.first(), topAlbum.title, "Album • ${topAlbum.artist}")
                            else -> Triple(topSong!!, topSong.title, "Song • ${topSong.artist}")
                        }
                        AlbumArt(song, Modifier.size(92.dp), shape = if (topArtist != null) CircleShape else RoundedCornerShape(8.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(kind, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (results.songs.isNotEmpty()) {
            item { SectionHeader("Songs") }
            val shown = results.songs.take(6)
            itemsIndexed(shown, key = { _, s -> "song-${s.id}" }) { i, song ->
                val liked = song.id in likedIds
                SongRow(
                    song = song,
                    liked = liked,
                    onClick = { actions.play(results.songs, i, "Search") },
                    menu = actions.songMenu(song, liked),
                )
            }
        }
        if (results.artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(results.artists, key = { it.name }) { ArtistCard(it, onClick = { vm.open(Destination.ArtistDetail(it.name)) }) }
                }
            }
        }
        if (results.albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(results.albums, key = { it.id }) { AlbumCard(it, onClick = { vm.open(Destination.AlbumDetail(it.id)) }) }
                }
            }
        }
        if (results.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists") }
            items(results.playlists, key = { "pl-${it.id}" }) { p ->
                CollectionRow(
                    title = p.name,
                    subtitle = "Playlist • ${songCount(p.songCount)}",
                    art = { m -> PlaylistArt(p.covers, m) },
                    onClick = { vm.open(Destination.PlaylistDetail(p.id)) },
                )
            }
        }
        if (results.folders.isNotEmpty()) {
            item { SectionHeader("Folders") }
            items(results.folders, key = { "f-${it.path}" }) { f ->
                CollectionRow(
                    title = f.name,
                    subtitle = "Folder • ${songCount(f.songs.size)}",
                    art = { m -> GradientArt(FolderGradient, Icons.Rounded.Folder, m) },
                    onClick = { vm.open(Destination.FolderDetail(f.path)) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Songs, artists, albums") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

@Composable
private fun BrowseCard(tile: BrowseTile, modifier: Modifier) {
    Surface(onClick = tile.onClick, shape = RoundedCornerShape(10.dp), modifier = modifier.height(96.dp)) {
        Box(Modifier.background(Brush.linearGradient(tile.colors))) {
            Text(
                tile.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(12.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 8.dp)
                    .rotate(22f)
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tile.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
    }
}
