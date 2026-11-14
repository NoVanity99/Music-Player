package com.novanity.musicplayer.ui.screens

import com.novanity.musicplayer.ui.components.clearOfWindowControls
import com.novanity.musicplayer.data.Album
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.produceState
import com.novanity.musicplayer.data.ArtistBio
import com.novanity.musicplayer.ui.components.ArtistLinks
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.Album
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.friendlyPath
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.PlaylistTrack
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.AlbumCard
import com.novanity.musicplayer.ui.components.CardRow
import com.novanity.musicplayer.ui.components.ConfirmDialog
import com.novanity.musicplayer.ui.components.FolderGradient
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.LikedArt
import com.novanity.musicplayer.ui.components.LikedGradient
import com.novanity.musicplayer.ui.components.MostPlayedGradient
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.OverflowMenu
import com.novanity.musicplayer.ui.components.PlaylistArt
import com.novanity.musicplayer.ui.components.RecentGradient
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.SongRow
import com.novanity.musicplayer.ui.components.TextInputDialog
import com.novanity.musicplayer.ui.components.formatTotal
import com.novanity.musicplayer.ui.components.rememberArtColor
import com.novanity.musicplayer.ui.components.songCount
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun DetailScreen(destination: Destination, actions: SongActions, contentPadding: PaddingValues) {
    when (destination) {
        is Destination.AlbumDetail -> AlbumScreen(destination.albumId, actions, contentPadding)
        is Destination.ArtistDetail -> ArtistScreen(destination.name, actions, contentPadding)
        is Destination.PlaylistDetail -> PlaylistScreen(destination.playlistId, actions, contentPadding)
        is Destination.FolderDetail -> FolderScreen(destination.path, actions, contentPadding)
        Destination.LikedSongs -> LikedScreen(actions, contentPadding)
        Destination.RecentlyPlayed -> RecentScreen(actions, contentPadding)
        Destination.Settings -> SettingsScreen(actions, contentPadding)
        Destination.MostPlayed -> MostPlayedScreen(actions, contentPadding)
        Destination.Equalizer -> EqualizerScreen(actions, contentPadding)
        Destination.Stats -> StatsScreen(actions, contentPadding)
        Destination.Downloads -> DownloadsScreen(actions, contentPadding)
        is Destination.MixDetail -> MixScreen(destination.kind, actions, contentPadding)
    }
}

// ---------- Shared scaffolding ----------

/**
 * Scrolling page with a tinted hero header and a top bar that fades in once the hero scrolls away.
 */
@Composable
internal fun CollectionScaffold(
    title: String,
    tint: Color,
    onBack: () -> Unit,
    menu: List<MenuAction>,
    contentPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val background = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(lerp(background, tint, 0.35f).copy(alpha = barAlpha))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp)
                .clearOfWindowControls(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.35f * (1f - barAlpha)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White) }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .alpha(barAlpha),
            )
            OverflowMenu(menu, Icons.Rounded.MoreVert, tint = Color.White)
        }
    }
}

/**
 * Artwork + title block with a vertical wash of the art's color: centered on phones (Apple Music
 * style), side by side on wide windows (desktop style).
 */
@Composable
internal fun Hero(
    tint: Color,
    art: @Composable (Modifier) -> Unit,
    title: String,
    subtitle: String?,
    meta: String,
    onSubtitleClick: (() -> Unit)? = null,
    /** Shown instead of a plain [subtitle], e.g. clickable artist names. */
    subtitleContent: (@Composable (TextAlign) -> Unit)? = null,
    /** A small red pill beside the title, e.g. "ADVANCED TRANSITION". */
    badge: String? = null,
) {
    val background = MaterialTheme.colorScheme.background
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.35f), background))),
    ) {
        val wide = maxWidth >= 600.dp
        val subtitleText: @Composable (TextAlign) -> Unit = subtitleContent ?: { align ->
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = align,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
                )
            }
        }
        if (wide) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 72.dp, start = 32.dp, end = 32.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                art(Modifier.size(220.dp).shadow(24.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black))
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(meta.substringBefore(" \u2022").uppercase(), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (badge != null) {
                            Spacer(Modifier.width(14.dp))
                            RedPill(badge)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    subtitleText(TextAlign.Start)
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                art(
                    Modifier
                        .size(240.dp)
                        .shadow(24.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                )
                Spacer(Modifier.height(20.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (badge != null) {
                    Spacer(Modifier.height(8.dp))
                    RedPill(badge)
                    Spacer(Modifier.height(4.dp))
                }
                subtitleText(TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Play + Shuffle: full-width pills on phones, compact buttons on wide windows. */
@Composable
internal fun PlayShuffleRow(songs: List<Song>, actions: SongActions, source: String) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (wide) 32.dp else 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val buttonModifier = if (wide) Modifier.width(150.dp) else Modifier.weight(1f)
            Button(
                onClick = { actions.play(songs, 0, source) },
                enabled = songs.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = buttonModifier,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Play", style = MaterialTheme.typography.titleSmall)
            }
            FilledTonalButton(
                onClick = { actions.shuffle(songs, source) },
                enabled = songs.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = buttonModifier,
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Shuffle", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

internal fun meta(kind: String, songs: List<Song>) =
    "$kind • ${songCount(songs.size)} • ${formatTotal(songs.sumOf { it.durationMs })}"

@Composable
internal fun Missing(contentPadding: PaddingValues, loading: Boolean, onBack: () -> Unit) {
    CollectionScaffold("", MaterialTheme.colorScheme.background, onBack, emptyList(), contentPadding) {
        item {
            Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator() else Text("This is no longer in your library.")
            }
        }
    }
}

/** Generic song list body used by most collection pages. */
internal fun LazyListScope.songItems(
    songs: List<Song>,
    likedIds: Set<Long>,
    actions: SongActions,
    source: String,
    trackNumbers: Boolean = false,
    showAlbum: Boolean = true,
    discHeaders: Boolean = false,
) {
    // Only when the album really has more than one disc.
    val discs = discHeaders && songs.map { it.disc }.distinct().size > 1
    if (discs) {
        songs.forEachIndexed { index, song ->
            if (index == 0 || song.disc != songs[index - 1].disc) {
                item(key = "disc-${song.disc}-$index") { DiscHeader(song.disc, songs.count { it.disc == song.disc }) }
            }
            item(key = "${song.id}-$index") {
                val liked = song.id in likedIds
                SongRow(
                    modifier = Modifier.animateItem(),
                    song = song,
                    liked = liked,
                    trackNumber = if (trackNumbers) song.track else null,
                    subtitle = if (showAlbum) "${song.artist} • ${song.album}" else song.artist,
                    onClick = { actions.play(songs, index, source) },
                    menu = actions.songMenu(song, liked),
                )
            }
        }
        return
    }
    itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { index, song ->
        val liked = song.id in likedIds
        SongRow(
            modifier = Modifier.animateItem(), // rows glide when the list changes
            song = song,
            liked = liked,
            trackNumber = if (trackNumbers) song.track else null,
            subtitle = if (showAlbum) "${song.artist} • ${song.album}" else song.artist,
            onClick = { actions.play(songs, index, source) },
            menu = actions.songMenu(song, liked),
        )
    }
}

// ---------- Album ----------

/** Small white capitals in a red bubble. */
@Composable
private fun RedPill(text: String) {
    Text(
        text.uppercase(),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.background(Color(0xFFE5243B), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "Disc 2 • 11 songs" above each disc of a multi-disc album. */
@Composable
private fun DiscHeader(disc: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Album, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (disc > 0) "Disc $disc" else "Other tracks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(songCount(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AlbumScreen(albumId: Long, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val album = lib?.albumsById?.get(albumId) ?: return Missing(contentPadding, lib == null, { vm.back() })
    val tint by rememberArtColor(album.songs.first(), MaterialTheme.colorScheme.surfaceContainerHigh)
    // Shown while the setting is on, for the albums the owner marked.
    val advancedOn by vm.advancedTransitions.collectAsStateWithLifecycle()
    val ownerConfig = vm.ownerConfig?.collectAsStateWithLifecycle()?.value
    val advanced = remember(album, advancedOn, ownerConfig) { advancedOn && vm.hasAdvancedTransitions(album) }

    CollectionScaffold(album.title, tint, { vm.back() }, actions.collectionMenu(album.songs), contentPadding) {
        item {
            Hero(
                tint = tint,
                art = { m -> AlbumArt(album.songs.first(), m, shape = RoundedCornerShape(10.dp), large = true) },
                title = album.title,
                subtitle = album.artist,
                meta = meta("Album", album.songs),
                subtitleContent = if (album.artist == "Various artists") null else { align ->
                    ArtistLinks(album.artist, vm, MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = align)
                },
                badge = if (advanced) "Advanced transition" else null,
            )
        }
        item { PlayShuffleRow(album.songs, actions, album.title) }
        songItems(album.songs, likedIds, actions, album.title, trackNumbers = true, showAlbum = false, discHeaders = true)
    }
}

// ---------- Folder / Recently played / Liked ----------

@Composable
private fun FolderScreen(path: String, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val folder = lib?.foldersByPath?.get(path) ?: return Missing(contentPadding, lib == null, { vm.back() })
    val tint = FolderGradient.first()
    val menu = actions.collectionMenu(
        folder.songs,
        listOf(
            MenuAction("Ignore this folder", Icons.Rounded.FolderOff) {
                vm.back()
                actions.ignoreFolder(folder.path)
            },
        ),
    )
    CollectionScaffold(folder.name, tint, { vm.back() }, menu, contentPadding) {
        item {
            Hero(tint, { m -> GradientArt(FolderGradient, Icons.Rounded.Folder, m, RoundedCornerShape(10.dp)) }, folder.name, null,
                friendlyPath(folder.path) + "\n" + meta("Folder", folder.songs))
        }
        item { PlayShuffleRow(folder.songs, actions, folder.name) }
        songItems(folder.songs, likedIds, actions, folder.name)
    }
}

@Composable
private fun RecentScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val songs by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val tint = RecentGradient.first()
    CollectionScaffold("Recently played", tint, { vm.back() }, actions.collectionMenu(songs), contentPadding) {
        item {
            Hero(tint, { m -> GradientArt(RecentGradient, Icons.Rounded.History, m, RoundedCornerShape(10.dp)) }, "Recently played", null,
                songCount(songs.size))
        }
        item { PlayShuffleRow(songs, actions, "Recently played") }
        if (songs.isEmpty()) item { EmptyHint("Songs you play show up here.") }
        songItems(songs, likedIds, actions, "Recently played")
    }
}

@Composable
private fun MostPlayedScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val songs by vm.mostPlayed.collectAsStateWithLifecycle()
    val plays by vm.playCounts.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val tint = MostPlayedGradient.first()
    CollectionScaffold("Most played", tint, { vm.back() }, actions.collectionMenu(songs), contentPadding) {
        item {
            Hero(tint, { m -> GradientArt(MostPlayedGradient, Icons.Rounded.Whatshot, m, RoundedCornerShape(10.dp)) }, "Most played", null,
                "Playlist \u2022 ${songCount(songs.size)}")
        }
        item { PlayShuffleRow(songs, actions, "Most played") }
        if (songs.isEmpty()) item { EmptyHint("Your most played songs show up here once you've listened for a while.") }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            val liked = song.id in likedIds
            val n = plays[song.id] ?: 0
            SongRow(
                song = song,
                liked = liked,
                subtitle = "${song.artist} \u2022 ${if (n == 1) "1 play" else "$n plays"}",
                onClick = { actions.play(songs, index, "Most played") },
                menu = actions.songMenu(song, liked),
            )
        }
    }
}

@Composable
private fun LikedScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val songs by vm.likedSongs.collectAsStateWithLifecycle()
    val tint = LikedGradient.first()
    CollectionScaffold("Liked Songs", tint, { vm.back() }, actions.collectionMenu(songs), contentPadding) {
        item {
            Hero(tint, { m -> LikedArt(m, RoundedCornerShape(10.dp)) }, "Liked Songs", null, meta("Playlist", songs))
        }
        item { PlayShuffleRow(songs, actions, "Liked Songs") }
        if (songs.isEmpty()) item { EmptyHint("Tap the heart on any song to save it here.") }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SwipeToDismissBox(
                state = rememberSwipeToDismissBoxState(),
                backgroundContent = { SwipeBackground(Icons.Rounded.HeartBroken) },
                onDismiss = { actions.toggleLike(song, liked = true) },
            ) {
                SongRow(
                    song = song,
                    onClick = { actions.play(songs, index, "Liked Songs") },
                    menu = actions.songMenu(song, liked = true),
                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

// ---------- Playlist ----------

@Composable
private fun PlaylistScreen(playlistId: Long, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val playlistFlow = remember(playlistId) { vm.playlist(playlistId) }
    val tracksFlow = remember(playlistId) { vm.playlistTracks(playlistId) }
    val playlist by playlistFlow.collectAsState(initial = null)
    val tracks by tracksFlow.collectAsState(initial = null)
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()

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
    val covers = remember(songs) { songs.distinctBy { it.albumId }.take(4) }
    val tint by rememberArtColor(songs.firstOrNull(), MaterialTheme.colorScheme.surfaceContainerHigh)

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = items.indexOfFirst { it.entryId == from.key }
        val toIdx = items.indexOfFirst { it.entryId == to.key }
        if (fromIdx >= 0 && toIdx >= 0) items = items.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
    }
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val background = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "hero") {
                Hero(tint, { m -> PlaylistArt(covers, m, RoundedCornerShape(10.dp)) }, name, null, meta("Playlist", songs))
            }
            item(key = "buttons") { PlayShuffleRow(songs, actions, name) }
            if (tracks != null && items.isEmpty()) {
                item(key = "empty") { EmptyHint("This playlist is empty. Use “Add to playlist” from any song's menu.") }
            }
            itemsIndexed(items, key = { _, t -> t.entryId }) { index, track ->
                ReorderableItem(reorderState, key = track.entryId) { isDragging ->
                    SwipeToDismissBox(
                        state = rememberSwipeToDismissBoxState(),
                        backgroundContent = { SwipeBackground(Icons.Rounded.Delete) },
                        onDismiss = { vm.removeFromPlaylist(track.entryId) },
                    ) {
                        val liked = track.song.id in likedIds
                        SongRow(
                            song = track.song,
                            liked = liked,
                            highlight = isDragging,
                            onClick = { actions.play(songs, index, name) },
                            menu = actions.songMenu(
                                track.song,
                                liked,
                                listOf(MenuAction("Remove from playlist", Icons.Rounded.Delete) { vm.removeFromPlaylist(track.entryId) }),
                            ),
                            modifier = Modifier.background(MaterialTheme.colorScheme.background),
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
                                ) {
                                    Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(lerp(background, tint, 0.35f).copy(alpha = barAlpha))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp)
                .clearOfWindowControls(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) {
                Box(
                    Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.35f * (1f - barAlpha)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White) }
            }
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alpha(barAlpha))
            OverflowMenu(
                actions.collectionMenu(songs) + listOf(
                    MenuAction("Rename playlist") { renaming = true },
                    MenuAction("Delete playlist", Icons.Rounded.Delete) { deleting = true },
                ),
                tint = Color.White,
            )
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
fun SwipeBackground(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(icon, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
    }
}

