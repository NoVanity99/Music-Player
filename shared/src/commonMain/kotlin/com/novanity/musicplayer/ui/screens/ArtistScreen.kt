package com.novanity.musicplayer.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.delay

/** A release's year: from its tags, or else from when it was added. */
private fun Album.sortYear(): Int = if (year > 0) year else 1970 + (dateAdded / 31_556_952L).toInt()

/**
 * An artist: banner, Play/Shuffle with the bio beside them, then four rows of releases (custom
 * albums, released albums, EPs, singles; each oldest to newest, so the newest is on the right),
 * and their songs.
 */
@Composable
internal fun ArtistScreen(name: String, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val artist = lib?.artistsByName?.get(name) ?: return Missing(contentPadding, lib == null, { vm.back() })
    val everything = remember(artist) { artist.songs + artist.appearsOn }
    // Their own releases plus the ones they appear on, sorted so the newest ends up on the right.
    val byKind = remember(artist, lib) {
        everything.map { it.albumId }.distinct().mapNotNull { lib?.albumsById?.get(it) }
            .sortedWith(compareBy<Album> { it.sortYear() }.thenBy { it.dateAdded }.thenBy { it.title.lowercase() })
            .groupBy { it.kind }
    }
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val biosOn by vm.artistBiosEnabled.collectAsStateWithLifecycle()
    val bio by produceState<ArtistBio?>(null, name, biosOn) { value = if (biosOn) vm.artistBio(name) else null }
    val tint by rememberArtColor(artist.songs.first(), MaterialTheme.colorScheme.surfaceContainerHigh)
    val background = MaterialTheme.colorScheme.background

    CollectionScaffold(artist.name, tint, { vm.back() }, actions.collectionMenu(everything), contentPadding) {
        item {
            // Full-bleed image with the name over a fade. A banner on wide windows.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(if (maxWidth >= 600.dp) Modifier.fillMaxWidth().height(340.dp) else Modifier.fillMaxWidth().aspectRatio(1.05f)) {
                    AlbumArt(artist.songs.first(), Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp), large = true)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to background)))
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(artist.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val releases = byKind.values.sumOf { it.size }
                        Text(
                            "${if (releases == 1) "1 release" else "$releases releases"} • ${songCount(everything.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { PlayAndAbout(everything, bio, actions, artist.name) }
        AlbumKind.entries.forEachIndexed { index, kind ->
            item(key = "row-$kind") {
                Appear(delayMs = 60 * index) {
                    ReleaseRow(kind, byKind[kind].orEmpty(), actions)
                }
            }
        }
        item {
            SectionHeader("Songs", action = if (everything.size > 5) (if (expanded) "Show less" else "See all") else null) { expanded = !expanded }
        }
        songItems(if (expanded) everything else everything.take(5), likedIds, actions, artist.name)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Fades and slides its content in once, a moment after it first appears. */
@Composable
private fun Appear(delayMs: Int, content: @Composable () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!shown) {
            delay(delayMs.toLong())
            shown = true
        }
    }
    AnimatedVisibility(shown, enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 8 }) { content() }
}

/** Play and Shuffle, with the artist's bio beside them on wide windows and below them on phones. */
@Composable
private fun PlayAndAbout(songs: List<Song>, bio: ArtistBio?, actions: SongActions, source: String) {
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
        if (wide) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = side, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                buttons(Modifier.width(150.dp))
                if (bio != null) {
                    Spacer(Modifier.width(12.dp))
                    About(bio, Modifier.weight(1f), collapsedLines = 3)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons(Modifier.weight(1f)) }
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

/** One row of releases, scrolled so the newest (rightmost) is in view; a quiet placeholder when empty. */
@Composable
private fun ReleaseRow(kind: AlbumKind, albums: List<Album>, actions: SongActions) {
    val vm = actions.library
    Column {
        SectionHeader(if (albums.isEmpty()) kind.title else "${kind.title}  •  ${albums.size}")
        if (albums.isEmpty()) {
            EmptyRow(kind.empty)
        } else {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (albums.size - 1).coerceAtLeast(0))
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(
                        album,
                        onClick = { vm.open(Destination.AlbumDetail(album.id)) },
                        modifier = Modifier.width(CardWidth).animateItem(),
                        onPlay = { actions.play(album.songs, 0, album.title) },
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
