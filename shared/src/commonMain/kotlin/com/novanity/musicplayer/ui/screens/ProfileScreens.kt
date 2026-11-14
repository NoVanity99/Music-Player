package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.AccountProfile
import com.novanity.musicplayer.data.AccountState
import com.novanity.musicplayer.data.PublicProfile
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryFilter
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.ArtistCard
import com.novanity.musicplayer.ui.components.CardRow
import com.novanity.musicplayer.ui.components.CardWidth
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.songCount

private val ProfileTint = Color(0xFF7C3AED)

/** The round initial and name at the top of a profile. */
@Composable
private fun ProfileHeader(name: String, caption: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ProfileTint.copy(alpha = 0.55f), Color.Transparent)))
            .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(84.dp).background(Brush.linearGradient(listOf(ProfileTint, MaterialTheme.colorScheme.primary)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(caption.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Public" / "Private" switch for one part of your profile. */
@Composable
private fun VisibilityChip(private: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (private) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        contentColor = if (private) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (private) Icons.Rounded.Lock else Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (private) "Private" else "Public", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AccountRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Your account: what you've saved, an optional bio, and what other people may see. */
@Composable
internal fun AccountPage(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val state by vm.accountState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val liked by vm.likedSongs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val artists by vm.favoriteArtists.collectAsStateWithLifecycle()
    val plays by vm.playCounts.collectAsStateWithLifecycle()
    val name = (state as? AccountState.SignedIn)?.user?.username ?: "You"
    var editingBio by remember { mutableStateOf(false) }
    var find by rememberSaveable { mutableStateOf("") }
    fun update(change: (AccountProfile) -> AccountProfile) = vm.setProfile(change(profile))

    if (editingBio) {
        var text by remember { mutableStateOf(profile.bio) }
        AlertDialog(
            onDismissRequest = { editingBio = false },
            title = { Text("Your bio") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(300) },
                    placeholder = { Text("A line or two about you (optional)") },
                    supportingText = { Text("${text.length}/300") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { update { it.copy(bio = text) }; editingBio = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingBio = false }) { Text("Cancel") } },
        )
    }

    CollectionScaffold("Account", ProfileTint, { vm.back() }, emptyList(), contentPadding) {
        item { ProfileHeader(name, "Your account") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.bio.ifBlank { "No bio yet." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (profile.bio.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                VisibilityChip(profile.private.bio) { update { it.copy(private = it.private.copy(bio = !it.private.bio)) } }
                TextButton(onClick = { editingBio = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (profile.bio.isBlank()) "Add bio" else "Edit")
                }
            }
        }
        item { SectionHeader("Yours") }
        item {
            AccountRow(Icons.Rounded.Favorite, "Liked songs", songCount(liked.size), { vm.open(Destination.LikedSongs) }) {
                VisibilityChip(profile.private.likes) { update { it.copy(private = it.private.copy(likes = !it.private.likes)) } }
            }
        }
        item {
            AccountRow(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists", if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists", { vm.showLibrary(LibraryFilter.PLAYLISTS) }) {
                VisibilityChip(profile.private.playlists) { update { it.copy(private = it.private.copy(playlists = !it.private.playlists)) } }
            }
        }
        item {
            AccountRow(Icons.Rounded.Person, "Your artists", if (artists.size == 1) "1 artist" else "${artists.size} artists", { vm.showFavoriteArtists() }) {
                VisibilityChip(profile.private.artists) { update { it.copy(private = it.private.copy(artists = !it.private.artists)) } }
            }
        }
        item {
            val total = plays.values.sum()
            AccountRow(Icons.Rounded.Insights, "Your stats", if (total == 1) "1 play" else "$total plays • only you can see these", { vm.open(Destination.Stats) })
        }
        item {
            Text(
                "Public parts can be seen by other people who look up your username. Private parts are only ever shown to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        item { SectionHeader("Find someone") }
        item {
            val go = { if (find.isNotBlank()) vm.open(Destination.Profile(find.trim())) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it.take(40) },
                    placeholder = { Text("Their username") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = go, enabled = find.isNotBlank()) { Text("View profile") }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** Someone else's profile: only what they've left public. */
@Composable
internal fun ProfilePage(username: String, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val result by produceState<Pair<PublicProfile?, String?>?>(null, username) { value = vm.lookUpProfile(username) }
    val profile = result?.first

    CollectionScaffold(profile?.username ?: username, ProfileTint, { vm.back() }, emptyList(), contentPadding) {
        item { ProfileHeader(profile?.username ?: username, "Profile") }
        if (result == null) {
            item { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@CollectionScaffold
        }
        if (profile == null) {
            item { Text(result?.second ?: "That profile can't be shown.", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
            return@CollectionScaffold
        }
        val hidden: @Composable (String) -> Unit = { what ->
            Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("$what are private.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!profile.bio.isNullOrBlank()) {
            item { Text(profile.bio!!, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }

        item { SectionHeader("Artists") }
        val artists = profile.artists
        val known = artists?.mapNotNull { lib?.artistsByName?.get(it) }.orEmpty()
        when {
            artists == null -> item { hidden("Their artists") }
            known.isEmpty() -> item { Text("None yet.", modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> item {
                CardRow(known, key = { it.name }, minCardWidth = CardWidth - 16.dp) { artist, m ->
                    ArtistCard(artist, onClick = { vm.open(Destination.ArtistDetail(artist.name)) }, modifier = m, onPlay = { actions.shuffle(artist.songs, artist.name) })
                }
            }
        }

        item { SectionHeader("Playlists") }
        val playlists = profile.playlists
        when {
            playlists == null -> item { hidden("Their playlists") }
            playlists.isEmpty() -> item { Text("None yet.", modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(playlists, key = { "pl-${it.name}-${it.songs.size}" }) { p ->
                val songs = remember(p, lib) { vm.songsForCloudIds(p.songs) }
                AccountRow(Icons.AutoMirrored.Rounded.QueueMusic, p.name, songCount(songs.size) + " • tap to play", { if (songs.isNotEmpty()) actions.play(songs, 0, p.name) })
            }
        }

        val likes = profile.likes
        item { SectionHeader(if (likes.isNullOrEmpty()) "Liked songs" else "Liked songs  •  ${likes.size}") }
        if (likes == null) {
            item { hidden("Their liked songs") }
        } else {
            val songs = vm.songsForCloudIds(likes)
            if (songs.isEmpty()) item { Text("None yet.", modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else songItems(songs, likedIds, actions, "${profile.username}'s liked songs")
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
