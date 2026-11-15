package com.novanity.musicplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.Artist
import com.novanity.musicplayer.data.ArtistImageOption
import com.novanity.musicplayer.data.ArtistImages
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.LibraryViewModel

/** The library, for the artist pictures drawn all over the app (lists, cards, the artist page). */
val LocalLibrary = staticCompositionLocalOf<LibraryViewModel?> { null }

const val SLOT_AVATAR = "avatar"
const val SLOT_BANNER = "banner"

/**
 * An artist's picture: the one the user chose, else one found online, else an album cover.
 * [slot] is [SLOT_AVATAR] for their round profile picture or [SLOT_BANNER] for the top of their page.
 */
@Composable
fun ArtistPicture(artist: Artist, slot: String, modifier: Modifier = Modifier, shape: Shape = CircleShape, large: Boolean = false) {
    val vm = LocalLibrary.current
    val fallback = artist.songs.firstOrNull() ?: artist.appearsOn.firstOrNull()
    if (vm == null) return AlbumArt(fallback, modifier, shape = shape, large = large)
    val version by vm.artistPicturesVersion.collectAsState()
    val enabled by vm.artistPicturesEnabled.collectAsState()
    var reference by remember(artist.name, slot, version, enabled) { mutableStateOf(vm.artistPictureNow(artist.name, slot)) }
    LaunchedEffect(artist.name, slot, version, enabled) { reference = vm.artistPicture(artist.name, slot) }
    PictureReference(reference, fallback, vm, modifier, shape, large, banner = slot == SLOT_BANNER)
}

/** Draws a picture reference: a web address, "album:<id>" for a library cover, or null for [fallback]. */
@Composable
private fun PictureReference(reference: String?, fallback: Song?, vm: LibraryViewModel, modifier: Modifier, shape: Shape, large: Boolean, banner: Boolean = false) {
    val ref = reference
    if (ref == null || ref.startsWith("album:")) {
        val song = ref?.removePrefix("album:")?.toLongOrNull()?.let { vm.grouped.value?.albumsById?.get(it)?.songs?.firstOrNull() } ?: fallback
        return AlbumArt(song, modifier, shape = shape, large = large)
    }
    val url = if (large) ref else ArtistImages.small(ref)
    var image by remember(url) { mutableStateOf<ImageBitmap?>(Avatars.cached(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (image == null) {
            image = Avatars.load(url)
            failed = image == null
        }
    }
    // No connection (or the picture is gone): the album cover, as before.
    if (failed) return AlbumArt(fallback, modifier, shape = shape, large = large)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val shown = image
        val alpha by animateFloatAsState(if (shown != null) 1f else 0f, tween(220), label = "artistPicture")
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
                contentScale = ContentScale.Crop,
                // Faces sit in the upper part of most pictures; keep them in a wide banner.
                alignment = if (banner) BiasAlignment(0f, -0.45f) else Alignment.Center,
            )
        }
    }
}

/**
 * Lets the user pick an artist's profile picture and banner: automatic, one of the artist's
 * album covers, or a picture found by searching.
 */
@Composable
fun ArtistPicturesDialog(artist: Artist, albums: List<Album>, onDismiss: () -> Unit) {
    val vm = LocalLibrary.current ?: return onDismiss()
    var slot by remember { mutableStateOf(SLOT_AVATAR) }
    var query by remember { mutableStateOf(artist.name) }
    var asked by remember { mutableStateOf(artist.name) }
    var results by remember { mutableStateOf<List<ArtistImageOption>?>(null) }
    val version by vm.artistPicturesVersion.collectAsState()
    val chosen = remember(slot, version) { vm.artistPictureChoice(artist.name, slot) }
    LaunchedEffect(asked) {
        results = null
        results = vm.searchArtistPictures(asked)
    }
    val fallback = artist.songs.firstOrNull() ?: artist.appearsOn.firstOrNull()
    val shape: Shape = if (slot == SLOT_AVATAR) CircleShape else RoundedCornerShape(10.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pictures for ${artist.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = slot == SLOT_AVATAR, onClick = { slot = SLOT_AVATAR }, label = { Text("Profile picture") })
                    FilterChip(selected = slot == SLOT_BANNER, onClick = { slot = SLOT_BANNER }, label = { Text("Banner") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    singleLine = true,
                    placeholder = { Text("Search for pictures") },
                    trailingIcon = { IconButton(onClick = { asked = query.trim() }) { Icon(Icons.Rounded.Search, contentDescription = "Search") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                val found = results
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "auto") {
                        Choice("Automatic", selected = chosen == null, shape = shape, onClick = { vm.setArtistPicture(artist.name, slot, null) }) { m ->
                            Box(m.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                    items(found.orEmpty(), key = { "web:" + it.url }) { option ->
                        Choice(option.label, selected = chosen == option.url, shape = shape, onClick = { vm.setArtistPicture(artist.name, slot, option.url) }) { m ->
                            PictureReference(option.url, fallback, vm, m, shape, large = false)
                        }
                    }
                    items(albums, key = { "album:" + it.id }) { album ->
                        val reference = "album:${album.id}"
                        Choice(album.title, selected = chosen == reference, shape = shape, onClick = { vm.setArtistPicture(artist.name, slot, reference) }) { m ->
                            AlbumArt(album.songs.firstOrNull(), m, shape = shape)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    found == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Looking for pictures…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    found.isEmpty() -> Text(
                        "No pictures found online for “$asked”. Try another spelling, or use an album cover.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, shape: Shape, onClick: () -> Unit, picture: @Composable (Modifier) -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
                .padding(if (selected) 5.dp else 0.dp),
        ) { picture(Modifier.fillMaxSize()) }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
