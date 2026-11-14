package com.novanity.musicplayer.ui.screens

import com.novanity.musicplayer.ui.components.SongCard
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.AlbumKind
import com.novanity.musicplayer.data.ArtistBio
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.AlbumCard
import com.novanity.musicplayer.ui.components.CardWidth
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.rememberArtColor
import com.novanity.musicplayer.ui.components.songCount
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.graphicsLayer
import com.novanity.musicplayer.ui.components.ArtworkLoader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A release's year: from its tags, or else from when it was added. */
private fun Album.sortYear(): Int = if (year > 0) year else 1970 + (dateAdded / 31_556_952L).toInt()

/**
 * An artist: banner, Play/Shuffle with the bio beside them, then five rows (custom albums,
 * unreleased songs, released albums, EPs, singles), each with the newest on the left.
 */
@Composable
internal fun ArtistScreen(name: String, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val artist = lib?.artistsByName?.get(name) ?: return Missing(contentPadding, lib == null, { vm.back() })
    val everything = remember(artist) { artist.songs + artist.appearsOn }
    // Their own releases plus the ones they appear on, newest first (so the newest is on the left).
    val byKind = remember(artist, lib) {
        everything.map { it.albumId }.distinct().mapNotNull { lib?.albumsById?.get(it) }
            .sortedWith(compareByDescending<Album> { it.sortYear() }.thenByDescending { it.dateAdded }.thenBy { it.title.lowercase() })
            .groupBy { it.kind }
    }
    // Unreleased songs are shown one by one, newest first.
    val unreleased = remember(everything) {
        everything.filter { it.albumKind == AlbumKind.UNRELEASED }
            .sortedWith(compareByDescending<Song> { it.year }.thenByDescending { it.dateAdded }.thenBy { it.title.lowercase() })
    }
    val biosOn by vm.artistBiosEnabled.collectAsStateWithLifecycle()
    // A bio that's already on the device is there from the first frame; a new one is looked up.
    val savedBio = remember(name, biosOn) { vm.cachedArtistBio(name) }
    val bio by produceState(savedBio, name, biosOn) { value = if (biosOn) vm.artistBio(name) ?: savedBio else null }
    // Your own plays of this artist, for the line under the name and the "Your top" button.
    val plays by vm.playCounts.collectAsStateWithLifecycle()
    val yourTop = remember(everything, plays) {
        everything.filter { (plays[it.id] ?: 0) > 0 }.sortedByDescending { plays[it.id] ?: 0 }.take(25)
    }
    val yourPlays = remember(everything, plays) { everything.sumOf { plays[it.id] ?: 0 } }

    // Load all the artwork on the page first, then show the page in one go (rather than cover by
    // cover). Covers are already on the device, so this takes a moment at most; it never waits long.
    val art = remember(artist, byKind, unreleased) {
        (byKind.values.flatten().map { it.songs.first() } + unreleased).distinctBy { it.albumId }.take(60)
    }
    var ready by remember(name) {
        mutableStateOf(
            ArtworkLoader.cached(artist.songs.first(), ArtworkLoader.LARGE) != null &&
                art.all { ArtworkLoader.cached(it, ArtworkLoader.SMALL) != null }
        )
    }
    LaunchedEffect(name) {
        if (!ready) {
            withTimeoutOrNull(350) {
                coroutineScope {
                    launch { ArtworkLoader.load(artist.songs.first(), ArtworkLoader.LARGE) }
                    art.forEach { launch { ArtworkLoader.load(it, ArtworkLoader.SMALL) } }
                }
            }
            ready = true
        }
    }
    val pageAlpha by animateFloatAsState(if (ready) 1f else 0f, tween(180), label = "artistPage")
    val fade = Modifier.graphicsLayer { alpha = pageAlpha }
    val tint by rememberArtColor(artist.songs.first(), MaterialTheme.colorScheme.surfaceContainerHigh)
    val background = MaterialTheme.colorScheme.background

    CollectionScaffold(artist.name, tint, { vm.back() }, actions.collectionMenu(everything), contentPadding) {
        item {
            // Full-bleed image with the name over a fade. A banner on wide windows.
            BoxWithConstraints(Modifier.fillMaxWidth().then(fade)) {
                Box(if (maxWidth >= 600.dp) Modifier.fillMaxWidth().height(360.dp) else Modifier.fillMaxWidth().aspectRatio(1.05f)) {
                    AlbumArt(artist.songs.first(), Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp), large = true)
                    // A wash of the cover's colour, then a fade into the page, so the name always reads.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.18f), 0.35f to Color.Transparent, 0.72f to tint.copy(alpha = 0.35f), 1f to background)))
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text(artist.name, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val releases = byKind.filterKeys { it != AlbumKind.UNRELEASED }.values.sumOf { it.size }
                        Text(
                            "${if (releases == 1) "1 release" else "$releases releases"} • ${songCount(everything.size)}" +
                                if (yourPlays > 0) " • played ${if (yourPlays == 1) "once" else "$yourPlays times"} by you" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Box(fade) { PlayAndAbout(everything, yourTop, bio, actions, artist.name) } }
        AlbumKind.entries.forEach { kind ->
            item(key = "row-$kind") {
                Box(fade) {
                    if (kind == AlbumKind.UNRELEASED) UnreleasedRow(unreleased, actions, artist.name)
                    else ReleaseRow(kind, byKind[kind].orEmpty(), actions)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Play and Shuffle, with the artist's bio beside them on wide windows and below them on phones. */
@Composable
private fun PlayAndAbout(songs: List<Song>, yourTop: List<Song>, bio: ArtistBio?, actions: SongActions, source: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        val side = if (maxWidth >= 600.dp) 32.dp else 24.dp
        val buttons: @Composable (Modifier) -> Unit = { m ->
            Button(
                onClick = { actions.play(songs, 0, source) },
                enabled = songs.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = m,
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
                modifier = m,
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Shuffle", style = MaterialTheme.typography.titleSmall)
            }
        }
        // The songs by this artist you play the most, most played first.
        val top: @Composable (Modifier) -> Unit = { m ->
            if (yourTop.size >= 3) {
                OutlinedButton(
                    onClick = { actions.play(yourTop, 0, "Your top $source") },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 14.dp),
                    modifier = m,
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Your top ${yourTop.size}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
        }
        if (wide) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = side, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                buttons(Modifier.width(150.dp))
                top(Modifier)
                if (bio != null) {
                    Spacer(Modifier.width(12.dp))
                    About(bio, Modifier.weight(1f), collapsedLines = 3)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons(Modifier.weight(1f)) }
                if (yourTop.size >= 3) {
                    Spacer(Modifier.height(10.dp))
                    top(Modifier.fillMaxWidth())
                }
                if (bio != null) {
                    Spacer(Modifier.height(14.dp))
                    About(bio, Modifier.fillMaxWidth(), collapsedLines = 4)
                }
            }
        }
    }
}

/** The bio as plain text that sits on the page (no card): tap to read it all. */
@Composable
private fun About(bio: ArtistBio, modifier: Modifier, collapsedLines: Int) {
    var open by rememberSaveable(bio.sourceUrl) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }
            .animateContentSize(tween(220)),
    ) {
        Text(
            bio.text,
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            maxLines = if (open) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append(if (open) "Show less" else "Read more") }
                append("  •  Wikipedia")
            },
            style = MaterialTheme.typography.labelMedium,
            color = muted.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp).clickable(enabled = open) { runCatching { uriHandler.openUri(bio.sourceUrl) } },
        )
    }
}

/** The unreleased songs as a row of song cards (tap to play); a quiet placeholder when empty. */
@Composable
private fun UnreleasedRow(songs: List<Song>, actions: SongActions, source: String) {
    Column {
        SectionHeader(if (songs.isEmpty()) AlbumKind.UNRELEASED.title else "${AlbumKind.UNRELEASED.title}  \u2022  ${songs.size}")
        if (songs.isEmpty()) {
            EmptyRow(AlbumKind.UNRELEASED.empty)
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(songs, key = { it.id }) { song ->
                    SongCard(song, onClick = { actions.play(songs, songs.indexOf(song), source) }, modifier = Modifier.width(CardWidth).animateItem())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One row of releases, newest on the left; a quiet placeholder when empty. */
@Composable
private fun ReleaseRow(kind: AlbumKind, albums: List<Album>, actions: SongActions) {
    val vm = actions.library
    Column {
        SectionHeader(if (albums.isEmpty()) kind.title else "${kind.title}  •  ${albums.size}")
        if (albums.isEmpty()) {
            EmptyRow(kind.empty)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(
                        album,
                        onClick = { vm.open(Destination.AlbumDetail(album.id)) },
                        modifier = Modifier.width(CardWidth).animateItem(),
                        onPlay = { actions.play(album.songs, 0, album.title) },
                        subtitle = (if (album.year > 0) "${album.year} \u2022 " else "") + songCount(album.songs.size),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** "Nothing here yet", as a soft dashed outline the height of a small card. */
@Composable
private fun EmptyRow(text: String) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(84.dp)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                )
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Album, contentDescription = null, tint = outline, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
