package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.PlaylistTrack
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.ConfirmDialog
import com.novanity.musicplayer.ui.components.EmptyState
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.OverflowMenu
import com.novanity.musicplayer.ui.components.SongRow
import com.novanity.musicplayer.ui.components.TextInputDialog
import com.novanity.musicplayer.ui.components.formatDuration
import com.novanity.musicplayer.ui.components.songCount
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun DetailScreen(destination: Destination, actions: SongActions, currentSongId: Long?) {
    when (destination) {
        is Destination.PlaylistDetail -> PlaylistDetailScreen(destination.playlistId, actions, currentSongId)
        else -> CollectionDetail(destination, actions, currentSongId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    menu: List<MenuAction>,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = { OverflowMenu(menu) },
            )
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

@Composable
private fun CollectionHeader(
    title: String,
    subtitle: String,
    art: (@Composable () -> Unit),
    songs: List<Song>,
    actions: SongActions,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            art()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val total = songs.sumOf { it.durationMs }
                Text(
                    "${songCount(songs.size)} • ${formatDuration(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { actions.player.playSongs(songs) }, enabled = songs.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            FilledTonalButton(
                onClick = { actions.player.playSongs(songs, shuffle = true) },
                enabled = songs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle")
            }
        }
    }
}

private data class Info(
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
    val round: Boolean = false,
    val icon: Boolean = false,
)

@Composable
private fun CollectionDetail(destination: Destination, actions: SongActions, currentSongId: Long?) {
    val vm = actions.library
    val grouped by vm.grouped.collectAsStateWithLifecycle()
    val recent by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val lib = grouped

    val info: Info? = when (destination) {
        is Destination.AlbumDetail -> lib?.albumsById?.get(destination.albumId)?.let { Info(it.title, it.artist, it.songs) }
        is Destination.ArtistDetail -> lib?.artistsByName?.get(destination.name)?.let {
            Info(it.name, if (it.albumCount == 1) "1 album" else "${it.albumCount} albums", it.songs, round = true)
        }
        is Destination.FolderDetail -> lib?.foldersByPath?.get(destination.path)?.let { Info(it.name, it.path, it.songs) }
        Destination.RecentlyPlayed -> Info("Recently played", "Your latest listens", recent, icon = true)
        is Destination.PlaylistDetail -> null
    }

    DetailScaffold(info?.title.orEmpty(), vm::pop, info?.songs?.let { actions.collectionMenu(it) }.orEmpty()) { modifier ->
        if (info == null) {
            Box(modifier, contentAlignment = Alignment.Center) {
                if (lib == null) CircularProgressIndicator() else Text("This item is no longer on your device.")
            }
            return@DetailScaffold
        }
        val songs = info.songs
        LazyColumn(modifier) {
            item(key = "header") {
                CollectionHeader(
                    title = info.title,
                    subtitle = info.subtitle,
                    songs = songs,
                    actions = actions,
                    art = {
                        val shape = if (info.round) RoundedCornerShape(50) else RoundedCornerShape(12.dp)
                        if (info.icon) {
                            Box(
                                Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, shape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(48.dp)) }
                        } else {
                            AlbumArt(songs.firstOrNull(), Modifier.size(112.dp), shape = shape)
                        }
                    },
                )
            }
            itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { index, song ->
                SongRow(
                    song = song,
                    highlighted = song.id == currentSongId,
                    onClick = { actions.player.playSongs(songs, index) },
                    menu = actions.songMenu(song),
                    trackNumber = if (destination is Destination.AlbumDetail) song.track else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDetailScreen(playlistId: Long, actions: SongActions, currentSongId: Long?) {
    val vm = actions.library
    val playlistFlow = remember(playlistId) { vm.playlist(playlistId) }
    val tracksFlow = remember(playlistId) { vm.playlistTracks(playlistId) }
    val playlist by playlistFlow.collectAsState(initial = null)
    val tracks by tracksFlow.collectAsState(initial = null)

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // Local copy so drag-reordering is instant; synced from the DB when not dragging.
    var items by remember { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(tracks, dragging) {
        if (!dragging) tracks?.let { items = it }
    }

    val name = playlist?.name.orEmpty()
    val songs = items.map { it.song }
    DetailScaffold(
        title = name,
        onBack = vm::pop,
        menu = actions.collectionMenu(songs) + listOf(
            MenuAction("Rename playlist") { renaming = true },
            MenuAction("Delete playlist") { deleting = true },
        ),
    ) { modifier ->
        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            val fromIdx = items.indexOfFirst { it.entryId == from.key }
            val toIdx = items.indexOfFirst { it.entryId == to.key }
            if (fromIdx >= 0 && toIdx >= 0) {
                items = items.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            }
        }
        LazyColumn(modifier, state = listState) {
            item(key = "header") {
                CollectionHeader(
                    title = name,
                    subtitle = "Playlist",
                    songs = songs,
                    actions = actions,
                    art = {
                        Box(
                            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(48.dp)) }
                    },
                )
            }
            if (tracks != null && items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        "Empty playlist",
                        "Use “Add to playlist…” from any song's menu to add music here.",
                        modifier = Modifier.height(320.dp),
                    )
                }
            }
            itemsIndexed(items, key = { _, t -> t.entryId }) { index, track ->
                ReorderableItem(reorderState, key = track.entryId) { isDragging ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) {
                                vm.removeFromPlaylist(track.entryId)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = { DeleteBackground() },
                    ) {
                        SongRow(
                            song = track.song,
                            highlighted = track.song.id == currentSongId || isDragging,
                            onClick = { actions.player.playSongs(songs, index) },
                            menu = actions.songMenu(
                                track.song,
                                listOf(MenuAction("Remove from playlist") { vm.removeFromPlaylist(track.entryId) }),
                            ),
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                            trailing = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = { dragging = true },
                                        onDragStopped = {
                                            vm.reorderPlaylist(items.map { it.entryId })
                                            dragging = false
                                        },
                                    ),
                                ) { Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder") }
                            },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog("Rename playlist", name, "Rename", onConfirm = {
            vm.renamePlaylist(playlistId, it)
            renaming = false
        }, onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog("Delete playlist?", "“$name” will be deleted. Your songs stay on the device.", "Delete", onConfirm = {
            deleting = false
            vm.deletePlaylist(playlistId)
        }, onDismiss = { deleting = false })
    }
}

@Composable
fun DeleteBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
    }
}
