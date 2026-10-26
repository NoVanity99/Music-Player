package com.novanity.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.novanity.musicplayer.data.friendlyPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.IO
import com.novanity.musicplayer.platform.DirectoryListing
import com.novanity.musicplayer.platform.LocalPlatform

/**
 * Full-screen folder browser for picking where music lives. Uses direct file access (the app's
 * audio permission allows it), so folders like Download that Android's system picker refuses work too.
 */
@Composable
fun FolderBrowser(
    title: String,
    confirmLabel: String,
    startPath: String? = null,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatform.current
    val roots = remember { platform.storageRoots() }
    var current by rememberSaveable { mutableStateOf(startPath) }

    // null = loading; Result.failure = unreadable.
    val listing by produceState<Result<DirectoryListing>?>(null, current) {
        value = null
        val dir = current ?: return@produceState
        value = withContext(Dispatchers.IO) {
            platform.listDirectory(dir)?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
        }
    }

    fun up() {
        val c = current ?: return
        current = if (roots.any { it.path == c }) null else c.substringBeforeLast('/', "").ifEmpty { null }
    }

    // Fallback: the OS's own picker, in case direct browsing isn't possible.
    val systemPicker = platform.rememberSystemFolderPicker(onPick)

    Dialog(
        onDismissRequest = { if (current != null) up() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (current != null) up() else onDismiss() }) {
                        Icon(
                            if (current != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                            contentDescription = if (current != null) "Up one folder" else "Close",
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            current?.let(::friendlyPath) ?: "Pick a storage location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current != null) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    }
                }

                Box(Modifier.weight(1f)) {
                    val result = listing
                    val l = result?.getOrNull()
                    when {
                        current == null -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(roots, key = { it.path }) { root ->
                                BrowserRow(
                                    icon = if (root.removable) Icons.Rounded.SdCard else Icons.Rounded.PhoneAndroid,
                                    title = root.label,
                                    subtitle = root.path,
                                    onClick = { current = root.path },
                                )
                            }
                        }
                        result == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        l == null -> EmptyState(
                            Icons.Rounded.Folder,
                            "Can't open this folder",
                            "This folder can't be opened. Try another one, or use the system picker.",
                            action = { TextButton(onClick = systemPicker) { Text("Use system picker") } },
                        )
                        else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            if (l.audioFileCount > 0) {
                                item {
                                    Text(
                                        "${l.audioFileCount} audio ${if (l.audioFileCount == 1) "file" else "files"} directly in this folder",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            if (l.folders.isEmpty()) {
                                item {
                                    Text(
                                        "No subfolders here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(20.dp),
                                    )
                                }
                            }
                            items(l.folders, key = { it.path }) { dir ->
                                BrowserRow(Icons.Rounded.Folder, dir.name, null, onClick = { current = dir.path })
                            }
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = systemPicker) { Text("System picker") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { current?.let(onPick) }, enabled = current != null, shape = RoundedCornerShape(12.dp)) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
