package com.novanity.musicplayer.ui.components

import android.content.Context
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.novanity.musicplayer.data.friendlyPath
import com.novanity.musicplayer.data.treeUriToPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
private data class StorageRoot(val label: String, val path: String, val removable: Boolean)

private fun storageRoots(context: Context): List<StorageRoot> {
    val internal = Environment.getExternalStorageDirectory().absolutePath
    val removable = context.getExternalFilesDirs(null).drop(1).filterNotNull()
        .map { it.absolutePath.substringBefore("/Android/") }
        .distinct()
    return listOf(StorageRoot("Internal storage", internal, false)) +
        removable.mapIndexed { i, p -> StorageRoot(if (removable.size == 1) "SD card" else "SD card ${i + 1}", p, true) }
}

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka", "aiff", "aif", "ape")

@Immutable
private data class Listing(val folders: List<File>, val audioCount: Int, val readable: Boolean)

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
    val context = LocalContext.current
    val roots = remember { storageRoots(context) }
    var current by rememberSaveable { mutableStateOf(startPath?.takeIf { File(it).isDirectory }) }

    val listing by produceState<Listing?>(null, current) {
        value = null
        val dir = current?.let(::File) ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val children = dir.listFiles()
            if (children == null) {
                Listing(emptyList(), 0, readable = false)
            } else {
                Listing(
                    folders = children.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() },
                    audioCount = children.count { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS },
                    readable = true,
                )
            }
        }
    }

    fun up() {
        val c = current ?: return
        current = if (roots.any { it.path == c }) null else File(c).parent
    }

    // Fallback: Android's own picker, in case direct browsing isn't possible on this device.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeUriToPath)?.let(onPick)
    }

    Dialog(
        onDismissRequest = { if (current != null) up() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
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
                    val l = listing
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
                        l == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        !l.readable -> EmptyState(
                            Icons.Rounded.Folder,
                            "Can't open this folder",
                            "Android won't let apps look inside this folder. Try another one, or use the system picker.",
                            action = { TextButton(onClick = { systemPicker.launch(null) }) { Text("Use system picker") } },
                        )
                        else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            if (l.audioCount > 0) {
                                item {
                                    Text(
                                        "${l.audioCount} audio ${if (l.audioCount == 1) "file" else "files"} directly in this folder",
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
                        TextButton(onClick = { systemPicker.launch(null) }) { Text("System picker") }
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
