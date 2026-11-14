package com.novanity.musicplayer.ui.screens

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.CloudStatus
import com.novanity.musicplayer.data.DownloadState
import com.novanity.musicplayer.data.SongSource
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryViewModel
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.SectionHeader
import com.novanity.musicplayer.ui.components.SongRow

private val CloudColors = listOf(Color(0xFF2563EB), Color(0xFF06B6D4))

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${(bytes / 10_000_000) / 100.0} GB"
    bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
    bytes > 0 -> "${(bytes / 1000).coerceAtLeast(1)} KB"
    else -> "0 MB"
}

/** One line saying what the cloud connection is doing, with an icon or spinner. */
@Composable
fun CloudStatusLine(status: CloudStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        CloudStatus.NotConfigured -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
        CloudStatus.Loading -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        is CloudStatus.Online -> "Connected • ${if (status.songs == 1) "1 song" else "${status.songs} songs"} in the cloud" to MaterialTheme.colorScheme.primary
        is CloudStatus.Offline -> status.message to MaterialTheme.colorScheme.onSurfaceVariant
        is CloudStatus.Error -> status.message to MaterialTheme.colorScheme.error
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            CloudStatus.Loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            is CloudStatus.Online -> Icon(Icons.Rounded.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
            is CloudStatus.Offline -> Icon(Icons.Rounded.CloudOff, null, tint = color, modifier = Modifier.size(18.dp))
            is CloudStatus.Error -> Icon(Icons.Rounded.ErrorOutline, null, tint = color, modifier = Modifier.size(18.dp))
            CloudStatus.NotConfigured -> Icon(Icons.Rounded.CloudQueue, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Server address and access key fields with Connect; used in Settings and on the first-run screen. */
@Composable
fun CloudConnectForm(vm: LibraryViewModel, modifier: Modifier = Modifier, onConnected: () -> Unit = {}) {
    val config by vm.cloudConfig.collectAsStateWithLifecycle()
    val status by vm.cloudStatus.collectAsStateWithLifecycle()
    var url by rememberSaveable(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var key by rememberSaveable(config.key) { mutableStateOf(config.key) }
    var showKey by remember { mutableStateOf(false) }
    val changed = url.trim().trimEnd('/') != config.serverUrl || key.trim() != config.key
    Column(modifier) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            placeholder = { Text("https://vanity-music.you.workers.dev") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Access key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showKey) "Hide key" else "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.connectCloud(url, key); onConnected() },
                enabled = url.isNotBlank() && key.isNotBlank() && (changed || status !is CloudStatus.Online),
            ) { Text(if (config.isSet && !changed) "Try again" else "Connect") }
            if (config.isSet) {
                OutlinedButton(onClick = { vm.refreshCloud() }, enabled = status !is CloudStatus.Loading) { Text("Refresh") }
                TextButton(onClick = { vm.disconnectCloud() }) { Text("Disconnect") }
            }
        }
        if (config.isSet) {
            Spacer(Modifier.height(10.dp))
            CloudStatusLine(status)
        }
    }
}

/** First run: the app plays music from the user's cloud library, so ask for its address and key. */
@Composable
fun CloudSetupScreen(vm: LibraryViewModel) {
    val accentWash = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accentWash, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GradientArt(CloudColors, Icons.Rounded.CloudQueue, Modifier.size(112.dp), RoundedCornerShape(30.dp))
            Spacer(Modifier.height(28.dp))
            Text("Connect your music", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Vanity Player streams your own music from your cloud server. Enter the server address and access key to get started.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            CloudConnectForm(vm, Modifier.fillMaxWidth())
        }
    }
}

/** Banner for Home when the cloud library is set up but can't be used right now. */
@Composable
fun CloudProblemBanner(vm: LibraryViewModel) {
    val status by vm.cloudStatus.collectAsStateWithLifecycle()
    val message = when (val s = status) {
        is CloudStatus.Offline -> s.message
        is CloudStatus.Error -> s.message
        else -> return
    }
    val isError = status is CloudStatus.Error
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        val toSettings = isError && !vm.cloudBuiltIn
        TextButton(onClick = { if (toSettings) vm.open(Destination.Settings) else vm.refreshCloud() }) {
            Text(if (toSettings) "Settings" else "Retry")
        }
    }
}

/** Downloads: what's saved on the device, what's in progress, and how much space it takes. */
@Composable
internal fun DownloadsScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val allSongs by vm.songs.collectAsStateWithLifecycle()
    val songs = allSongs.orEmpty()
    val states by vm.downloadStates.collectAsStateWithLifecycle()
    val used by vm.downloadStorageUsed.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val downloaded = remember(songs) { songs.filter { it.source == SongSource.DOWNLOADED } }
    val inProgress = remember(songs, states) {
        songs.filter { it.source == SongSource.CLOUD && states[it.cloudId].let { s -> s != null && s != DownloadState.Done } }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val menu = buildList {
        if (inProgress.isNotEmpty()) add(MenuAction("Cancel all downloads") { vm.cancelAllDownloads() })
        if (downloaded.isNotEmpty()) add(MenuAction("Delete all downloads") { confirmDelete = true })
    }
    CollectionScaffold("Downloads", CloudColors.first(), { vm.back() }, actions.collectionMenu(downloaded) + menu, contentPadding) {
        item {
            Hero(
                CloudColors.first(),
                { m -> GradientArt(CloudColors, Icons.Rounded.DownloadDone, m, RoundedCornerShape(10.dp)) },
                "Downloads",
                "Play these without internet",
                "${if (downloaded.size == 1) "1 song" else "${downloaded.size} songs"} • ${formatBytes(used)} used",
            )
        }
        if (downloaded.isNotEmpty()) item { PlayShuffleRow(downloaded, actions, "Downloads") }
        if (inProgress.isNotEmpty()) {
            item { SectionHeader("In progress", action = "Cancel all") { vm.cancelAllDownloads() } }
            itemsIndexed(inProgress, key = { _, s -> "q-${s.id}" }) { _, song ->
                val state = states[song.cloudId]
                SongRow(
                    song = song,
                    liked = song.id in likedIds,
                    subtitle = when (state) {
                        is DownloadState.Downloading -> "Downloading • ${(state.progress * 100).toInt()}%"
                        is DownloadState.Failed -> state.message
                        else -> "Waiting"
                    },
                    onClick = {},
                    menu = actions.songMenu(song, song.id in likedIds),
                )
            }
            if (downloaded.isNotEmpty()) item { SectionHeader("On this device") }
        }
        if (downloaded.isEmpty() && inProgress.isEmpty()) {
            item { EmptyHint("Nothing downloaded yet. Open the menu on a cloud song, album or playlist and choose Download.") }
        }
        songItems(downloaded, likedIds, actions, "Downloads")
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all downloads?") },
            text = { Text("This frees ${formatBytes(used)}. The songs stay in your cloud library and can be downloaded again.") },
            confirmButton = { TextButton(onClick = { vm.deleteAllDownloads(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Row of download actions for Settings. */
@Composable
internal fun DownloadsSummaryRow(actions: SongActions, modifier: Modifier = Modifier) {
    val vm = actions.library
    val allSongs by vm.songs.collectAsStateWithLifecycle()
    val songs = allSongs.orEmpty()
    val used by vm.downloadStorageUsed.collectAsStateWithLifecycle()
    val count = remember(songs) { songs.count { it.source == SongSource.DOWNLOADED } }
    val cloudOnly = remember(songs) { songs.filter { it.source == SongSource.CLOUD } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().clickable { vm.open(Destination.Downloads) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${if (count == 1) "1 song" else "$count songs"} • ${formatBytes(used)} used on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Manage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (cloudOnly.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { actions.downloadAll(cloudOnly) }) {
                Text("Download all (${cloudOnly.size} songs, ${formatBytes(cloudOnly.sumOf { it.sizeBytes })})")
            }
        }
    }
}
