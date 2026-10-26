package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.SortOrder
import com.novanity.musicplayer.data.friendlyPath
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryFilter
import com.novanity.musicplayer.ui.PlaylistCard
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.AlbumCard
import com.novanity.musicplayer.ui.components.CollectionRow
import com.novanity.musicplayer.ui.components.ConfirmDialog
import com.novanity.musicplayer.ui.components.EmptyState
import com.novanity.musicplayer.ui.components.FolderGradient
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.LikedArt
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.PlaylistArt
import com.novanity.musicplayer.ui.components.RecentGradient
import com.novanity.musicplayer.ui.components.ShuffleGradient
import com.novanity.musicplayer.ui.components.SongRow
import com.novanity.musicplayer.ui.components.TextInputDialog
import com.novanity.musicplayer.ui.components.songCount

@Composable
fun LibraryScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val lib by vm.library.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Your Library", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, contentDescription = "New playlist") }
            SortMenu(actions)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryFilter.entries) { filter ->
                FilterChip(
                    selected = vm.libraryFilter == filter,
                    onClick = { vm.libraryFilter = filter },
                    label = { Text(filter.label) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    border = null,
                )
            }
        }

        val layoutDirection = LocalLayoutDirection.current
        val listPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        )
        Box(Modifier.weight(1f)) {
            when {
                lib.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.libraryFilter == LibraryFilter.PLAYLISTS -> PlaylistsList(actions, listPadding, onCreate = { creating = true })
                lib.isEmpty -> EmptyState(
                    Icons.Rounded.LibraryMusic,
                    "No songs in your folders",
                    "Copy music into your chosen folder, or change folders in Settings.",
                    action = { Button(onClick = { vm.open(Destination.Settings) }) { Text("Music folder settings") } },
                )
                else -> when (vm.libraryFilter) {
                    LibraryFilter.SONGS -> SongsList(actions, listPadding)
                    LibraryFilter.ALBUMS -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = listPadding.calculateBottomPadding()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(lib.albums, key = { it.id }) { album ->
                            AlbumCard(album, onClick = { vm.open(Destination.AlbumDetail(album.id)) }, modifier = Modifier)
                        }
                    }
                    LibraryFilter.ARTISTS -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                        items(lib.artists, key = { it.name }) { artist ->
                            CollectionRow(
                                title = artist.name,
                                subtitle = "${if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums"} • ${songCount(artist.songs.size)}",
                                art = { m -> AlbumArt(artist.songs.first(), m, shape = CircleShape) },
                                onClick = { vm.open(Destination.ArtistDetail(artist.name)) },
                                menu = actions.collectionMenu(artist.songs),
                            )
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                        items(lib.folders, key = { it.path }) { folder ->
                            CollectionRow(
                                title = folder.name,
                                subtitle = "${songCount(folder.songs.size)} • ${friendlyPath(folder.path)}",
                                art = { m -> GradientArt(FolderGradient, Icons.Rounded.Folder, m) },
                                onClick = { vm.open(Destination.FolderDetail(folder.path)) },
                                menu = actions.collectionMenu(
                                    folder.songs,
                                    listOf(MenuAction("Ignore this folder", Icons.Rounded.FolderOff) { actions.ignoreFolder(folder.path) }),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = {
            vm.createPlaylist(it)
            creating = false
        }, onDismiss = { creating = false })
    }
}

@Composable
private fun SongsList(actions: SongActions, padding: PaddingValues) {
    val vm = actions.library
    val lib by vm.library.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val songs = lib.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "shuffle") {
            CollectionRow(
                title = "Shuffle all",
                subtitle = songCount(songs.size),
                art = { m -> GradientArt(ShuffleGradient, Icons.Rounded.Shuffle, m) },
                onClick = { actions.shuffle(songs, "Your library") },
            )
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            val liked = song.id in likedIds
            SongRow(
                song = song,
                liked = liked,
                onClick = { actions.play(songs, index, "Your library") },
                menu = actions.songMenu(song, liked),
            )
        }
    }
}

@Composable
private fun PlaylistsList(actions: SongActions, padding: PaddingValues, onCreate: () -> Unit) {
    val vm = actions.library
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val liked by vm.likedSongs.collectAsStateWithLifecycle()
    val recent by vm.recentlyPlayed.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<PlaylistCard?>(null) }
    var deleting by remember { mutableStateOf<PlaylistCard?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "liked") {
            CollectionRow(
                title = "Liked Songs",
                subtitle = "Playlist • ${songCount(liked.size)}",
                art = { m -> LikedArt(m) },
                onClick = { vm.open(Destination.LikedSongs) },
            )
        }
        item(key = "recent") {
            CollectionRow(
                title = "Recently played",
                subtitle = songCount(recent.size),
                art = { m -> GradientArt(RecentGradient, Icons.Rounded.History, m) },
                onClick = { vm.open(Destination.RecentlyPlayed) },
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            CollectionRow(
                title = playlist.name,
                subtitle = "Playlist • ${songCount(playlist.songCount)}",
                art = { m -> PlaylistArt(playlist.covers, m) },
                onClick = { vm.open(Destination.PlaylistDetail(playlist.id)) },
                menu = listOf(
                    MenuAction("Rename") { renaming = playlist },
                    MenuAction("Delete") { deleting = playlist },
                ),
            )
        }
        item(key = "create") {
            CollectionRow(
                title = "Create playlist",
                subtitle = "Build a mix from your library",
                art = { m ->
                    Box(m, contentAlignment = Alignment.Center) {
                        GradientArt(
                            listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHigh),
                            Icons.Rounded.Add,
                            Modifier.fillMaxSize(),
                        )
                    }
                },
                onClick = onCreate,
            )
        }
    }

    renaming?.let { p ->
        TextInputDialog("Rename playlist", p.name, "Rename", onConfirm = {
            vm.renamePlaylist(p.id, it)
            renaming = null
        }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        ConfirmDialog("Delete playlist?", "“${p.name}” will be deleted. Your songs stay on the device.", "Delete", onConfirm = {
            vm.deletePlaylist(p.id)
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

@Composable
private fun SortMenu(actions: SongActions) {
    val sort by actions.library.sortOrder.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    trailingIcon = { if (order == sort) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        actions.library.setSortOrder(order)
                        open = false
                    },
                )
            }
        }
    }
}

