package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import androidx.compose.foundation.text.KeyboardActions
import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.Artist
import com.novanity.musicplayer.data.Folder
import com.novanity.musicplayer.data.SortOrder
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.data.db.PlaylistSummary
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryUi
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.CircleArt
import com.novanity.musicplayer.ui.components.ConfirmDialog
import com.novanity.musicplayer.ui.components.EmptyState
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.OverflowMenu
import com.novanity.musicplayer.ui.components.SongRow
import com.novanity.musicplayer.ui.components.TextInputDialog
import com.novanity.musicplayer.ui.components.songCount
import kotlinx.coroutines.launch

private val TABS = listOf("Songs", "Albums", "Artists", "Playlists", "Folders")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(actions: SongActions, currentSongId: Long?) {
    val vm = actions.library
    val lib by vm.library.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { SearchField(query, vm::setQuery) },
                    actions = {
                        SortMenu(actions)
                        SettingsMenu(actions)
                    },
                )
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
                    TABS.forEachIndexed { i, title ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                lib.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> HorizontalPager(state = pager, beyondViewportPageCount = 1) { page ->
                    when (page) {
                        3 -> PlaylistsTab(actions)
                        else -> if (lib.isEmpty) {
                            NoMusic(actions.library::refresh)
                        } else {
                            when (page) {
                                0 -> SongsTab(lib, query, actions, currentSongId)
                                1 -> AlbumsTab(lib.albums, query, actions)
                                2 -> ArtistsTab(lib.artists, query, actions)
                                else -> FoldersTab(lib.folders, query, actions)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search your music") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
    )
}

@Composable
private fun SortMenu(actions: SongActions) {
    val sort by actions.library.sortOrder.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text("Sort by ${order.label.lowercase()}") },
                    leadingIcon = { if (order == sort) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = {
                        actions.library.setSortOrder(order)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsMenu(actions: SongActions) {
    val vm = actions.library
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.Palette, contentDescription = "Appearance") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { RadioButton(selected = mode == theme, onClick = null) },
                    onClick = { vm.setThemeMode(mode) },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Dynamic color") },
                    leadingIcon = { Checkbox(checked = dynamic, onCheckedChange = null) },
                    onClick = { vm.setDynamicColor(!dynamic) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Rescan library") },
                onClick = {
                    vm.refresh()
                    open = false
                },
            )
        }
    }
}

@Composable
private fun NoMusic(onRescan: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.LibraryMusic,
        title = "No music found",
        message = "Copy some audio files (MP3, FLAC, M4A, OGG\u2026) to your phone, for example into the Music folder, then rescan.",
        action = { FilledTonalButton(onClick = onRescan) { Text("Rescan") } },
    )
}

@Composable
private fun NoResults(query: String) {
    EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing matches “$query”.")
}

@Composable
private fun SongsTab(lib: LibraryUi, query: String, actions: SongActions, currentSongId: Long?) {
    val songs = lib.songs
    if (songs.isEmpty()) return NoResults(query)
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "shuffle") {
            ListItem(
                modifier = Modifier.clickable { actions.player.playSongs(songs, shuffle = true) },
                leadingContent = { Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text("Shuffle all") },
                supportingContent = { Text(songCount(songs.size)) },
            )
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                highlighted = song.id == currentSongId,
                onClick = { actions.player.playSongs(songs, index) },
                menu = actions.songMenu(song),
            )
        }
    }
}

@Composable
private fun AlbumsTab(albums: List<Album>, query: String, actions: SongActions) {
    if (albums.isEmpty()) return NoResults(query)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            Card(onClick = { actions.library.open(Destination.AlbumDetail(album.id)) }) {
                AlbumArt(
                    album.songs.first(),
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                )
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistsTab(artists: List<Artist>, query: String, actions: SongActions) {
    if (artists.isEmpty()) return NoResults(query)
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.name }) { artist ->
            ListItem(
                modifier = Modifier.clickable { actions.library.open(Destination.ArtistDetail(artist.name)) },
                leadingContent = { CircleArt(artist.songs.first(), Modifier.size(48.dp)) },
                headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val albums = if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums"
                    Text("$albums • ${songCount(artist.songs.size)}")
                },
                trailingContent = { OverflowMenu(actions.collectionMenu(artist.songs)) },
            )
        }
    }
}

@Composable
private fun FoldersTab(folders: List<Folder>, query: String, actions: SongActions) {
    if (folders.isEmpty()) return NoResults(query)
    LazyColumn(Modifier.fillMaxSize()) {
        items(folders, key = { it.path }) { folder ->
            ListItem(
                modifier = Modifier.clickable { actions.library.open(Destination.FolderDetail(folder.path)) },
                leadingContent = {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("${songCount(folder.songs.size)} • ${folder.path}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { OverflowMenu(actions.collectionMenu(folder.songs)) },
            )
        }
    }
}

@Composable
private fun PlaylistsTab(actions: SongActions) {
    val vm = actions.library
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recentlyPlayed.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "new") {
            ListItem(
                modifier = Modifier.clickable { creating = true },
                leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(40.dp)) },
                headlineContent = { Text("New playlist") },
            )
        }
        item(key = "recent") {
            ListItem(
                modifier = Modifier.clickable { vm.open(Destination.RecentlyPlayed) },
                leadingContent = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(40.dp)) },
                headlineContent = { Text("Recently played") },
                supportingContent = { Text(songCount(recent.size)) },
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            ListItem(
                modifier = Modifier.clickable { vm.open(Destination.PlaylistDetail(playlist.id)) },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(songCount(playlist.songCount)) },
                trailingContent = {
                    OverflowMenu(
                        listOf(
                            MenuAction("Rename") { renaming = playlist },
                            MenuAction("Delete") { deleting = playlist },
                        )
                    )
                },
            )
        }
    }

    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = {
            vm.createPlaylist(it)
            creating = false
        }, onDismiss = { creating = false })
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
