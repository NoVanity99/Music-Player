package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.ChatMessage
import com.novanity.musicplayer.data.SharedMusic
import com.novanity.musicplayer.data.nowMillis
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.FramedAvatar
import com.novanity.musicplayer.ui.components.StyledName
import com.novanity.musicplayer.ui.components.clearOfWindowControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Your conversation with a friend: messages, and music either of you shared (tap Play to listen).
 * New messages are fetched every few seconds while it's open.
 */
@Composable
internal fun ChatPage(friend: String, actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val social by vm.socialState.collectAsStateWithLifecycle()
    val who = social.friends.firstOrNull { it.username.equals(friend, ignoreCase = true) }
    val messages = remember(friend) { mutableStateListOf<ChatMessage>() }
    var seenId by remember(friend) { mutableStateOf(0L) }
    var loaded by remember(friend) { mutableStateOf(false) }
    var failed by remember(friend) { mutableStateOf(false) }
    var draft by rememberSaveable(friend) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val page = vm.conversation(friend, messages.lastOrNull()?.id ?: 0)
        if (page == null) {
            if (!loaded) failed = true
            return
        }
        failed = false
        loaded = true
        val known = messages.mapTo(HashSet()) { it.id }
        val fresh = page.messages.filter { it.id !in known }
        if (fresh.isNotEmpty()) messages.addAll(fresh)
        seenId = page.seenId
    }

    DisposableEffect(friend) {
        vm.setOpenChat(friend)
        onDispose { vm.setOpenChat(null) }
    }
    LaunchedEffect(friend) {
        while (true) {
            refresh()
            delay(3_000)
        }
    }
    // Keep the newest message in view as messages arrive.
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        scope.launch {
            val problem = vm.sendMessage(friend, text)
            if (problem == null) {
                draft = ""
                refresh()
            } else vm.notify(problem)
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        // Top bar: back, and who you're talking to (tap for their profile).
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp).clearOfWindowControls(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { vm.open(Destination.Profile(friend)) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FramedAvatar(friend, vm.avatarUrl(who?.avatar), who?.look ?: com.novanity.musicplayer.data.ProfileLook(), 38.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    StyledName(friend, who?.look ?: com.novanity.musicplayer.data.ProfileLook(), MaterialTheme.typography.titleMedium)
                    val now = who?.songId?.let { vm.songForCloudId(it) }
                    Text(
                        when {
                            now != null && who.playing -> "Listening to ${now.title}"
                            who?.online == true -> "Online"
                            else -> "Offline"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (now != null && who.playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                failed -> Note("Couldn't load your messages. Check your connection.")
                loaded && messages.isEmpty() -> Note("No messages yet. Say hi, or share a song with $friend from any song, album or playlist menu.")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        Bubble(m, actions)
                        // "Seen" under the last of your messages your friend has read.
                        if (m.mine && m.id == seenId && messages.lastOrNull { it.mine }?.id == m.id) {
                            Text("Seen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1000) },
                placeholder = { Text("Message $friend") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).onEnterSend { send() },
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { send() }, enabled = draft.isNotBlank() && !sending) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }
    }
}

/** Enter sends on a keyboard (Shift+Enter still makes a new line). */
private fun Modifier.onEnterSend(send: () -> Unit): Modifier = this.then(
    Modifier.onPreviewKeyEvent { e ->
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Enter && !e.isShiftPressed) {
            send()
            true
        } else false
    }
)

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Bubble(m: ChatMessage, actions: SongActions) {
    val mine = m.mine
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            m.share?.let { ShareCard(it, actions) }
            if (m.text.isNotBlank()) {
                if (m.share != null) Spacer(Modifier.height(8.dp))
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        Text(ago(m.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Music in a message: its cover, what it is, and Play / Add to queue. Tap to open the album. */
@Composable
private fun ShareCard(share: SharedMusic, actions: SongActions) {
    val vm = actions.library
    val songs = remember(share) { vm.songsOfShare(share) }
    val first = songs.firstOrNull()
    val opensAlbum = first != null && share.kind != "playlist"
    Row(
        Modifier
            .widthIn(min = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(enabled = opensAlbum) { first?.let { vm.open(Destination.AlbumDetail(it.albumId)) } }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(first, Modifier.size(56.dp), shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false).widthIn(max = 220.dp)) {
            Text(share.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(share.title.ifBlank { first?.title ?: "Music" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    songs.isEmpty() -> "Not in the library anymore"
                    share.kind == "song" -> share.subtitle
                    else -> listOf(share.subtitle, "${songs.size} ${if (songs.size == 1) "song" else "songs"}").filter { it.isNotBlank() }.distinct().joinToString(" • ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        if (songs.isNotEmpty()) {
            IconButton(onClick = {
                actions.player.addToQueue(songs)
                vm.notify(if (songs.size == 1) "Added to queue" else "Added ${songs.size} songs to the queue")
            }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Add to queue") }
            FilledIconButton(onClick = { actions.play(songs, 0, share.title) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play") }
        }
    }
}

private fun ago(at: Long): String {
    val s = ((nowMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "Just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        s < 7 * 86_400 -> "${s / 86_400} d ago"
        else -> "${s / (7 * 86_400)} w ago"
    }
}

/** "Share with a friend": pick who gets it, with an optional note. */
@Composable
internal fun ShareDialog(share: SharedMusic, actions: SongActions, onDismiss: () -> Unit) {
    val vm = actions.library
    val social by vm.socialState.collectAsStateWithLifecycle()
    var note by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshSocial() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share with a friend") },
        text = {
            Column {
                Text("${share.label} • ${share.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    placeholder = { Text("Add a message (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (social.friends.isEmpty()) {
                    Text("No friends yet. Add friends on the Friends page to share music with them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(social.friends, key = { it.username }) { friend ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                    vm.shareTo(friend.username, share, note.trim())
                                    onDismiss()
                                }.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FramedAvatar(friend.username, vm.avatarUrl(friend.avatar), friend.look, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                StyledName(friend.username, friend.look, MaterialTheme.typography.titleSmall, Modifier.weight(1f))
                                Text("Send", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
