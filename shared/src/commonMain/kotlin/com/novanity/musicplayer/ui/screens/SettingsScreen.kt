package com.novanity.musicplayer.ui.screens

import com.novanity.musicplayer.data.CloudStatus
import androidx.compose.material.icons.rounded.Person
import com.novanity.musicplayer.platform.LocalPlatform
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.TextButton
import com.novanity.musicplayer.update.UpdateState
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.data.friendlyPath
import com.novanity.musicplayer.data.isIn
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.songCount

@Composable
fun SettingsScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val lib by vm.grouped.collectAsStateWithLifecycle()
    val platform = LocalPlatform.current

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

        // ---- Account ----
        if (vm.accountsAvailable) {
            item { GroupLabel("Account") }
            item { SettingsCard { AccountSettingsRow(vm, actions::toast) } }
        }

        // ---- Cloud library ----
        if (vm.cloudAvailable) {
            item { GroupLabel("Cloud library") }
            item {
                SettingsCard {
                    Column(Modifier.padding(16.dp)) {
                        if (vm.cloudBuiltIn) {
                            // The server is built in: just the status and a way to reload the song list.
                            val status by vm.cloudStatus.collectAsStateWithLifecycle()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CloudStatusLine(status, Modifier.weight(1f))
                                TextButton(onClick = { vm.refreshCloud() }, enabled = status !is CloudStatus.Loading) { Text("Refresh") }
                            }
                        } else {
                            Text(
                                "Stream your own music from your Vanity Player server and download it for offline play.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            CloudConnectForm(vm)
                        }
                        Spacer(Modifier.height(14.dp))
                        DownloadsSummaryRow(actions)
                    }
                }
            }
        }

        // ---- Playback ----
        item { GroupLabel("Playback") }
        item {
            val crossfade by vm.crossfadeSeconds.collectAsStateWithLifecycle()
            val speed by actions.player.speed.collectAsStateWithLifecycle()
            var showSpeed by remember { mutableStateOf(false) }
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Crossfade", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (crossfade == 0) "Off" else "$crossfade s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("Blend the end of each song into the next.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.Slider(
                        value = crossfade.toFloat(),
                        onValueChange = { vm.setCrossfadeSeconds(kotlin.math.round(it).toInt()) },
                        valueRange = 0f..12f,
                    )
                }
                SettingRow(Icons.Rounded.Speed, "Playback speed", if (speed == 1f) "Normal" else "${speed}\u00D7", onClick = { showSpeed = true })
                if (LocalPlatform.current.supportsEqualizer) {
                    SettingRow(Icons.Rounded.GraphicEq, "Equalizer", "Presets and band levels", onClick = { vm.open(com.novanity.musicplayer.ui.Destination.Equalizer) })
                }
            }
            if (showSpeed) com.novanity.musicplayer.ui.components.SpeedDialog(actions.player) { showSpeed = false }
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
                if (LocalPlatform.current.supportsDynamicColor) {
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

        if (actions.player.spectrum != null) {
            item {
                val on by vm.visualizerEnabled.collectAsStateWithLifecycle()
                SettingsCard {
                    SettingRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "Visualizer",
                        subtitle = "Animated spectrum bars in the full-screen player",
                        onClick = { vm.setVisualizerEnabled(!on) },
                        trailing = { Switch(checked = on, onCheckedChange = vm::setVisualizerEnabled) },
                    )
                }
            }
        }

        // ---- Updates ----
        actions.updates?.let { updates ->
            item { GroupLabel("Updates") }
            item {
                val state by updates.state.collectAsStateWithLifecycle()
                val auto by updates.autoCheck.collectAsStateWithLifecycle()
                SettingsCard {
                    SettingRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = "Version ${updates.currentVersion}",
                        subtitle = when (val s = state) {
                            UpdateState.Checking -> "Checking for updates\u2026"
                            is UpdateState.UpToDate -> "You're up to date"
                            is UpdateState.Available -> "Version ${s.info.version} is available"
                            is UpdateState.Downloading -> "Downloading ${s.info.version}\u2026 ${(s.progress * 100).toInt()}%"
                            is UpdateState.Verifying -> "Verifying\u2026"
                            is UpdateState.Installing -> "Starting the installer\u2026"
                            is UpdateState.NeedsPermission -> "Allow installs from this app to continue"
                            is UpdateState.Failed -> s.message
                            UpdateState.Idle -> "Updates come from this app's GitHub releases"
                        },
                        trailing = {
                            when (state) {
                                UpdateState.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                is UpdateState.Available, is UpdateState.NeedsPermission -> TextButton(onClick = updates::showNotes) { Text("View") }
                                else -> TextButton(onClick = { updates.check(userInitiated = true) }) { Text("Check now") }
                            }
                        },
                    )
                    SettingRow(
                        icon = Icons.Rounded.Autorenew,
                        title = "Check automatically",
                        subtitle = "Look for a new version each time the app starts",
                        onClick = { updates.setAutoCheck(!auto) },
                        trailing = { Switch(checked = auto, onCheckedChange = updates::setAutoCheck) },
                    )
                }
            }
        }

        // ---- Integrations ----
        if (platform.supportsDiscordPresence) {
            item { GroupLabel("Integrations") }
            item {
                val discord by vm.discordPresence.collectAsStateWithLifecycle()
                val discordStatus = platform.discordStatus?.collectAsStateWithLifecycle()?.value
                SettingsCard {
                    SettingRow(
                        icon = Icons.Rounded.Gamepad,
                        title = "Discord Rich Presence",
                        subtitle = "Show what you're listening to on your Discord profile. To show the cover, the song's own artwork is uploaded to a temporary image host (deleted after 72 hours)." +
                            if (discord && discordStatus != null) "\n$discordStatus" else "",
                        onClick = { vm.setDiscordPresence(!discord) },
                        trailing = { Switch(checked = discord, onCheckedChange = vm::setDiscordPresence) },
                    )
                }
            }
        } else {
            item { GroupLabel("Integrations") }
            item {
                SettingsCard {
                    SettingRow(
                        icon = Icons.Rounded.Gamepad,
                        title = "Discord Rich Presence",
                        subtitle = "Available in the Windows app. Discord's phone app doesn't let other apps " +
                            "set your status, so it can't be shown from Android.",
                    )
                }
            }
        }

        // ---- Library ----
        item { GroupLabel("Library") }
        item {
            SettingsCard {
                if (vm.canShowBios) {
                    val bios by vm.artistBiosEnabled.collectAsStateWithLifecycle()
                    SettingRow(
                        icon = Icons.Rounded.Person,
                        title = "Artist bios & albums",
                        subtitle = "Show a short bio from Wikipedia and the artist's albums from MusicBrainz on artist pages",
                        onClick = { vm.setArtistBiosEnabled(!bios) },
                        trailing = { Switch(checked = bios, onCheckedChange = vm::setArtistBiosEnabled) },
                    )
                }
                SettingRow(Icons.Rounded.Shield, "Private by design", "No ads or tracking. Your music comes only from your own cloud server.")
            }
        }
    }
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
