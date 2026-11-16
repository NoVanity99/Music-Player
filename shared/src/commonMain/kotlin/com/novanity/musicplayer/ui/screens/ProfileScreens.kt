package com.novanity.musicplayer.ui.screens

import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.automirrored.rounded.Chat
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
private fun ProfileHeader(
    name: String,
    caption: String,
    admin: Boolean = false,
    avatarUrl: String? = null,
    onPicture: (() -> Unit)? = null,
    look: com.novanity.musicplayer.data.ProfileLook? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val background = MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxWidth()
            // Their banner, fading into the page so the text under it always reads.
            .background(com.novanity.musicplayer.ui.components.bannerBrush(look), alpha = 0.7f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, background)))
            .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.novanity.musicplayer.ui.components.FramedAvatar(name, avatarUrl, look, 96.dp, if (onPicture != null) Modifier.clip(CircleShape).clickable(onClick = onPicture) else Modifier)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(caption.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.novanity.musicplayer.ui.components.StyledName(name, look, MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), Modifier.weight(1f, fill = false))
                if (admin) {
                    Spacer(Modifier.width(12.dp))
                    com.novanity.musicplayer.ui.components.AdminBadge()
                }
            }
            below?.invoke()
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
    val isAdmin by vm.isAdmin.collectAsStateWithLifecycle()
    var editingBio by remember { mutableStateOf(false) }
    val pickPicture = com.novanity.musicplayer.platform.LocalPlatform.current.rememberImagePicker { bytes -> vm.setAvatar(bytes) }
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
        item {
            // Click the picture (or "Change picture") to choose one from this device.
            ProfileHeader(name, "Your account", isAdmin, vm.avatarUrl(profile.avatar), pickPicture, profile.look) {
                if (pickPicture != null) {
                    Row {
                        TextButton(onClick = pickPicture, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (profile.avatar == null) "Add a profile picture" else "Change picture") }
                        if (profile.avatar != null) {
                            Spacer(Modifier.width(16.dp))
                            TextButton(onClick = { vm.removeAvatar() }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
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
            AccountRow(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists", if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists", { vm.open(Destination.MyPlaylists) }) {
                VisibilityChip(profile.private.playlists) { update { it.copy(private = it.private.copy(playlists = !it.private.playlists)) } }
            }
        }
        item {
            AccountRow(Icons.Rounded.Person, "Your artists", if (artists.size == 1) "1 artist" else "${artists.size} artists", { vm.open(Destination.MyArtists) }) {
                VisibilityChip(profile.private.artists) { update { it.copy(private = it.private.copy(artists = !it.private.artists)) } }
            }
        }
        if (vm.socialAvailable) {
            item {
                val social by vm.socialState.collectAsStateWithLifecycle()
                val listening = social.friends.count { it.playing }
                AccountRow(
                    Icons.Rounded.Person, "Friends",
                    (if (social.friends.size == 1) "1 friend" else "${social.friends.size} friends") +
                        (if (listening > 0) " \u2022 $listening listening now" else "") +
                        (if (social.requests.isNotEmpty()) " \u2022 ${social.requests.size} new request${if (social.requests.size == 1) "" else "s"}" else ""),
                    { vm.open(Destination.Friends) },
                )
            }
            // Your friends, with what each is playing, right on your account page.
            item {
                val social by vm.socialState.collectAsStateWithLifecycle()
                Column {
                    social.friends.take(6).forEach { friend ->
                        val song = vm.songForCloudId(friend.songId)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.open(Destination.Profile(friend.username)) }.padding(start = 70.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.novanity.musicplayer.ui.components.FramedAvatar(friend.username, vm.avatarUrl(friend.avatar), friend.look, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(friend.username, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    if (friend.admin) {
                                        Spacer(Modifier.width(8.dp))
                                        com.novanity.musicplayer.ui.components.AdminBadge()
                                    }
                                }
                                Text(
                                    if (song != null && friend.playing) "Listening to ${song.title} \u2022 ${song.artist}" else if (friend.online) "Online \u2022 not listening to anything" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (song != null && friend.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            val total = plays.values.sum()
            AccountRow(Icons.Rounded.Insights, "Your stats", if (total == 1) "1 play" else "$total plays", { vm.open(Destination.Stats) }) {
                VisibilityChip(profile.private.stats) { update { it.copy(private = it.private.copy(stats = !it.private.stats)) } }
            }
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
        item { ProfileHeader(profile?.username ?: username, "Profile", profile?.admin == true, vm.avatarUrl(profile?.avatar), look = profile?.look) }
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

        item { SectionHeader("Stats") }
        val stats = profile.stats
        if (stats == null) {
            item { hidden("Their stats") }
        } else {
            val played = stats.songs.mapNotNull { sp -> vm.songForCloudId(sp.songId)?.let { it to sp.plays } }
            val topArtists = played.groupBy { it.first.artist }.mapValues { e -> e.value.sumOf { it.second } }.entries.sortedByDescending { it.value }.take(5)
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(if (stats.plays == 1) "1 play" else "${stats.plays} plays", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (topArtists.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("TOP ARTISTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        topArtists.forEachIndexed { i, (artist, n) ->
                            Row(Modifier.fillMaxWidth().clickable { vm.open(Destination.ArtistDetail(artist)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                                Text(artist, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (n == 1) "1 play" else "$n plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (played.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("TOP SONGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val topSongs = played.take(5).map { it.first }
            if (topSongs.isNotEmpty()) songItems(topSongs, likedIds, actions, "${profile.username}'s top songs")
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

// ---------- Friends ----------

@Composable
private fun Initial(name: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(ProfileTint, MaterialTheme.colorScheme.primary)), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** Your friends, what each is listening to right now, and requests. */
@Composable
internal fun FriendsPage(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val social by vm.socialState.collectAsStateWithLifecycle()
    val share by vm.shareListening.collectAsStateWithLifecycle()
    var add by rememberSaveable { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshSocial() }

    CollectionScaffold("Friends", ProfileTint, { vm.back() }, emptyList(), contentPadding) {
        item {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(ProfileTint.copy(alpha = 0.55f), Color.Transparent)))
                    .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 8.dp),
            ) {
                Text("Friends", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("See what your friends are playing, listen along, and send them messages and music.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            val go = { if (add.isNotBlank()) { vm.addFriend(add); add = "" } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = add,
                    onValueChange = { add = it.take(40) },
                    placeholder = { Text("Add a friend by username") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { go() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = go, enabled = add.isNotBlank()) { Text("Send request") }
            }
        }
        if (social.requests.isNotEmpty()) {
            item { SectionHeader("Friend requests") }
            items(social.requests, key = { "req-$it" }) { name ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Initial(name)
                    Spacer(Modifier.width(14.dp))
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.answerFriend(name, false) }) { Text("Decline") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { vm.answerFriend(name, true) }) { Text("Accept") }
                }
            }
        }
        item { SectionHeader(if (social.friends.isEmpty()) "Your friends" else "Your friends  •  ${social.friends.size}") }
        if (social.friends.isEmpty()) {
            item {
                Text(
                    "No friends yet. Send a request with their username; once they accept, they show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        items(social.friends, key = { "fr-${it.username}" }) { friend ->
            val song = vm.songForCloudId(friend.songId)
            val inSession = social.session != null
            Row(
                Modifier.fillMaxWidth().clickable { vm.open(Destination.Profile(friend.username)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    com.novanity.musicplayer.ui.components.FramedAvatar(friend.username, vm.avatarUrl(friend.avatar), friend.look, 44.dp)
                    // A green dot while they have the player open.
                    if (friend.online) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp).background(Color(0xFF30D158), CircleShape))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.novanity.musicplayer.ui.components.StyledName(friend.username, friend.look, MaterialTheme.typography.titleSmall, Modifier.weight(1f, fill = false))
                        if (friend.admin) {
                            Spacer(Modifier.width(8.dp))
                            com.novanity.musicplayer.ui.components.AdminBadge()
                        }
                    }
                    Text(
                        when {
                            song != null && friend.playing -> "Listening to ${song.title} • ${song.artist}"
                            song != null -> "Paused on ${song.title} • ${song.artist}"
                            friend.online -> "Online \u2022 not listening to anything"
                            else -> "Offline \u2022 not listening to anything"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (song != null && friend.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (song != null && friend.playing && !inSession) {
                    Button(onClick = { vm.askToListen(friend.username) }) { Text("Listen along") }
                    Spacer(Modifier.width(4.dp))
                }
                // Bring a friend into your session (not while you're a guest in someone else's).
                if (friend.online && social.session?.isGuest != true && friend.username !in social.session?.members.orEmpty()) {
                    androidx.compose.material3.OutlinedButton(onClick = { vm.inviteToListen(friend.username) }) { Text("Invite") }
                    Spacer(Modifier.width(4.dp))
                }
                // Messages, with how many are unread.
                val unread = social.unreadFrom(friend.username)
                androidx.compose.material3.IconButton(onClick = { vm.open(Destination.Chat(friend.username)) }) {
                    androidx.compose.material3.BadgedBox(badge = { if (unread > 0) androidx.compose.material3.Badge { Text(if (unread > 9) "9+" else "$unread") } }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Chat, contentDescription = if (unread > 0) "Messages, $unread unread" else "Messages")
                    }
                }
                TextButton(onClick = { vm.removeFriend(friend.username) }) { Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (social.sent.isNotEmpty()) {
            item { SectionHeader("Waiting for an answer") }
            items(social.sent, key = { "sent-$it" }) { name ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Initial(name)
                    Spacer(Modifier.width(14.dp))
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.removeFriend(name) }) { Text("Cancel") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { vm.setShareListening(!share) }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show friends what I'm listening to", style = MaterialTheme.typography.titleSmall)
                    Text("Turn off to listen privately. Friends can still see when you're online.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = share, onCheckedChange = vm::setShareListening)
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Small cards that slide in at the top of the window: a friend asking to listen along (Accept or
 * Decline; it goes away by itself after a while), and a pill while you're in a shared session.
 */
@Composable
fun SocialOverlay(vm: com.novanity.musicplayer.ui.LibraryViewModel, modifier: Modifier = Modifier) {
    val social by vm.socialState.collectAsStateWithLifecycle()
    val request = social.listenRequests.firstOrNull()
    val session = social.session
    Column(modifier.padding(top = 46.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = session != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
        ) {
            val s = session ?: remember { mutableStateOf(session) }.value
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 8.dp) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            s == null -> ""
                            s.isGuest -> "Listening with ${s.host}"
                            s.members.size == 1 -> "${s.members.first()} is listening with you"
                            else -> "${s.members.size} friends are listening with you"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    if (s?.isGuest == true) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.leaveSession() }) { Text("Leave session", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    } else {
                        Spacer(Modifier.width(12.dp).height(40.dp))
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = request != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
        ) {
            if (request != null) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 12.dp) {
                    Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Initial(request.from, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(request.from, style = MaterialTheme.typography.titleSmall)
                            Text(if (request.isInvite) "invited you to listen along with them" else "wants to listen along with you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { vm.answerListen(request, false) }) { Text("Decline") }
                        Button(onClick = { vm.answerListen(request, true) }) { Text(if (request.isInvite) "Join" else "Accept") }
                    }
                }
            }
        }
    }
}


// ---------- Pages opened from Account (each with its own back button to the account page) ----------

@Composable
private fun PageTitle(title: String, caption: String) {
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(ProfileTint.copy(alpha = 0.55f), Color.Transparent)))
            .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** All your playlists on one page. */
@Composable
internal fun MyPlaylistsPage(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    CollectionScaffold("Your playlists", ProfileTint, { vm.back() }, emptyList(), contentPadding) {
        item { PageTitle("Your playlists", if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists") }
        if (playlists.isEmpty()) {
            item { Text("No playlists yet. Create one from Your Library.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(playlists, key = { it.id }) { p ->
            com.novanity.musicplayer.ui.components.CollectionRow(
                title = p.name,
                subtitle = "Playlist \u2022 ${songCount(p.songCount)}",
                art = { m -> com.novanity.musicplayer.ui.components.PlaylistArt(p.covers, m) },
                onClick = { vm.open(Destination.PlaylistDetail(p.id)) },
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** The artists you've hearted on one page. */
@Composable
internal fun MyArtistsPage(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val favorites by vm.favoriteArtists.collectAsStateWithLifecycle()
    val artists = remember(lib, favorites) { lib?.artists.orEmpty().filter { it.name in favorites }.sortedBy { it.name.lowercase() } }
    CollectionScaffold("Your artists", ProfileTint, { vm.back() }, emptyList(), contentPadding) {
        item { PageTitle("Your artists", if (artists.size == 1) "1 artist" else "${artists.size} artists") }
        if (artists.isEmpty()) {
            item { Text("Tap the heart on an artist's page to keep them here.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(artists, key = { it.name }) { artist ->
            com.novanity.musicplayer.ui.components.CollectionRow(
                title = artist.name,
                subtitle = "${if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums"} \u2022 ${songCount(artist.songs.size)}",
                art = { m -> com.novanity.musicplayer.ui.components.ArtistPicture(artist, com.novanity.musicplayer.ui.components.SLOT_AVATAR, m) },
                onClick = { vm.open(Destination.ArtistDetail(artist.name)) },
                menu = actions.collectionMenu(artist.songs),
                heart = true,
            )
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}


// ---------- Bug reports (admin) ----------

/** What people have reported, newest first. Only the admin's account can open this. */
@Composable
internal fun BugReportsPage(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    var reload by remember { mutableStateOf(0) }
    val reports by produceState<List<com.novanity.musicplayer.data.BugReport>?>(null, reload) { value = vm.bugReports() ?: emptyList() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    CollectionScaffold("Bug reports", ProfileTint, { vm.back() }, emptyList(), contentPadding) {
        item { PageTitle("Bug reports", reports?.let { if (it.size == 1) "1 report" else "${it.size} reports" } ?: "Loading\u2026") }
        val list = reports
        if (list != null && list.isEmpty()) {
            item { Text("Nothing has been reported.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(list.orEmpty(), key = { it.id }) { r ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.username, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).clickable { vm.open(Destination.Profile(r.username)) })
                        TextButton(onClick = { scope.launch { vm.deleteBugReport(r.id); reload++ } }) { Text("Done") }
                    }
                    Text(r.message, style = MaterialTheme.typography.bodyMedium)
                    if (r.app.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(r.app, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
