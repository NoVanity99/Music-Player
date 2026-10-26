package com.novanity.musicplayer.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.DeviceFolder
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.data.friendlyPath
import com.novanity.musicplayer.data.pathIsIn
import com.novanity.musicplayer.data.treeUriToPath
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.songCount

@Composable
fun SettingsScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val included by vm.includedFolders.collectAsStateWithLifecycle()
    val deviceFolders by vm.deviceFolders.collectAsStateWithLifecycle()
    val lib by vm.grouped.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            actions.toast("That location isn't on this phone's storage. Pick a folder on internal storage or an SD card.")
        } else {
            vm.addFolder(path)
            actions.toast("Added ${friendlyPath(path)}")
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }
        }

        // ---- Music folders ----
        item { GroupLabel("Music folders") }
        item {
            SettingsCard {
                Text(
                    if (included.isEmpty()) {
                        "Playing music from every folder on this phone."
                    } else {
                        "Only showing music from these folders (and their subfolders)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                if (included.isEmpty()) {
                    SettingRow(Icons.Rounded.PhoneAndroid, "All folders", "${songCount(lib?.songs?.size ?: 0)} in your library")
                } else {
                    included.sorted().forEach { path ->
                        val count = deviceFolders.filter { pathIsIn(it.path, path) }.sumOf { it.songCount }
                        SettingRow(
                            icon = Icons.Rounded.Folder,
                            title = friendlyPath(path),
                            subtitle = songCount(count),
                            trailing = {
                                IconButton(onClick = { vm.removeFolder(path) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove folder")
                                }
                            },
                        )
                    }
                }
                SettingRow(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Add a folder",
                    subtitle = "Browse your phone's storage",
                    onClick = { pickFolder.launch(null) },
                    accent = true,
                )
                SettingRow(
                    icon = Icons.Rounded.Checklist,
                    title = "Choose from detected folders",
                    subtitle = "${deviceFolders.size} folders with music found",
                    onClick = { choosing = true },
                    accent = true,
                )
                if (included.isNotEmpty()) {
                    SettingRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = "Use all folders",
                        subtitle = "Clear the folder selection",
                        onClick = { vm.setIncludedFolders(emptySet()) },
                    )
                }
            }
        }

        // ---- Appearance ----
        item { GroupLabel("Appearance") }
        item {
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    val options = listOf(
                        ThemeMode.DARK to Icons.Rounded.DarkMode,
                        ThemeMode.LIGHT to Icons.Rounded.LightMode,
                        ThemeMode.SYSTEM to Icons.Rounded.PhoneAndroid,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, (mode, icon) ->
                            SegmentedButton(
                                selected = theme == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                label = { Text(if (mode == ThemeMode.SYSTEM) "Auto" else mode.label) },
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingRow(
                        icon = Icons.Rounded.Palette,
                        title = "Match wallpaper colors",
                        subtitle = "Use Material You dynamic color",
                        onClick = { vm.setDynamicColor(!dynamic) },
                        trailing = { Switch(checked = dynamic, onCheckedChange = { vm.setDynamicColor(it) }) },
                    )
                }
            }
        }

        // ---- Library ----
        item { GroupLabel("Library") }
        item {
            SettingsCard {
                SettingRow(Icons.Rounded.Refresh, "Rescan library", "Look for new or deleted songs", onClick = {
                    vm.refresh()
                    actions.toast("Rescanning…")
                })
                SettingRow(Icons.Rounded.Shield, "Private by design", "No internet access, ads or tracking. Everything stays on your phone.")
            }
        }
    }

    if (choosing) {
        FolderChooserDialog(
            folders = deviceFolders,
            selected = included,
            onConfirm = {
                vm.setIncludedFolders(it)
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun FolderChooserDialog(
    folders: List<DeviceFolder>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
        title = { Text("Choose music folders") },
        text = {
            if (folders.isEmpty()) {
                Text("No folders with music were found on this phone yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(folders, key = { it.path }) { folder ->
                        val checked = folder.path in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { picked = if (checked) picked - folder.path else picked + folder.path }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    folder.path.substringAfterLast('/').ifEmpty { folder.path },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${friendlyPath(folder.path)} • ${songCount(folder.songCount)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text(if (picked.isEmpty()) "Use all folders" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
