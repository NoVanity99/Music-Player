package com.novanity.musicplayer.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateDpAsState
import com.novanity.musicplayer.data.AccentColor
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

    val openGroups = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val isAdmin by vm.isAdmin.collectAsStateWithLifecycle()
    var reporting by remember { mutableStateOf(false) }
    if (reporting) ReportBugDialog(vm, appInfo = (if (platform.isDesktop) "Windows" else "Android") + (actions.updates?.let { " \u2022 version ${it.currentVersion}" } ?: "")) { reporting = false }

    androidx.compose.runtime.CompositionLocalProvider(LocalOpenGroups provides openGroups) {
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
            item { SettingsCard("Account") { AccountSettingsRow(vm, actions::toast) } }
            item { GroupLabel("Profile") }
            item { SettingsCard("Profile") { ProfileLookSettings(vm) } }
        }

        // ---- Customize ----
        item { GroupLabel("Customize") }
        item { SettingsCard("Customize") { CustomizeSettings(vm, platform.isDesktop) } }

        // ---- Cloud library ----
        if (vm.cloudAvailable) {
            item { GroupLabel("Cloud library") }
            item {
                SettingsCard("Cloud library") {
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
                        Spacer(Modifier.height(6.dp))
                        val downloadLiked by vm.downloadLiked.collectAsStateWithLifecycle()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Download liked songs", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Liking a song also saves it for offline play",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = downloadLiked, onCheckedChange = vm::setDownloadLiked)
                        }
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
            SettingsCard("Playback") {
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
                val advanced by vm.advancedTransitions.collectAsStateWithLifecycle()
                SettingRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Advanced transitions on certain albums",
                    subtitle = "On albums made to flow together, each song runs straight into the next with no gap",
                    onClick = { vm.setAdvancedTransitions(!advanced) },
                    trailing = { Switch(checked = advanced, onCheckedChange = vm::setAdvancedTransitions) },
                )
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
            SettingsCard("Appearance") {
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
                // Accent colour: a row of swatches, the chosen one ringed.
                val accent by vm.accent.collectAsStateWithLifecycle()
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text("Accent colour", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AccentColor.entries.forEach { option ->
                            val selected = option == accent
                            val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .padding(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(option.argb))
                                    .clickable(onClickLabel = option.label) { vm.setAccent(option) },
                            )
                        }
                    }
                }
                val pureBlack by vm.pureBlack.collectAsStateWithLifecycle()
                SettingRow(
                    icon = Icons.Rounded.DarkMode,
                    title = "Pure black",
                    subtitle = "True-black backgrounds in the dark theme",
                    onClick = { vm.setPureBlack(!pureBlack) },
                    trailing = { Switch(checked = pureBlack, onCheckedChange = vm::setPureBlack) },
                )
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
                SettingsCard("Appearance") {
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
                SettingsCard("Updates") {
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
                SettingsCard("Integrations") {
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
                SettingsCard("Integrations") {
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
            SettingsCard("Library") {
                if (vm.canShowBios) {
                    val bios by vm.artistBiosEnabled.collectAsStateWithLifecycle()
                    SettingRow(
                        icon = Icons.Rounded.Person,
                        title = "Artist bios",
                        subtitle = "Show a short bio from Wikipedia on artist pages",
                        onClick = { vm.setArtistBiosEnabled(!bios) },
                        trailing = { Switch(checked = bios, onCheckedChange = vm::setArtistBiosEnabled) },
                    )
                }
                if (vm.canChooseArtistPictures) {
                    val pictures by vm.artistPicturesEnabled.collectAsStateWithLifecycle()
                    SettingRow(
                        icon = Icons.Rounded.Person,
                        title = "Artist pictures",
                        subtitle = "Find a picture for each artist online. Off, or when none is found, an album cover is used",
                        onClick = { vm.setArtistPicturesEnabled(!pictures) },
                        trailing = { Switch(checked = pictures, onCheckedChange = vm::setArtistPicturesEnabled) },
                    )
                }
                SettingRow(Icons.Rounded.Shield, "Private by design", "No ads or tracking. The music comes only from our cloud and is safety checked.")
            }
        }

        // ---- Help ----
        item { GroupLabel("Help") }
        item {
            SettingsCard("Help") {
                SettingRow(Icons.Rounded.BugReport, "Report a bug", "Tell us what went wrong", onClick = { reporting = true })
                if (isAdmin) {
                    SettingRow(Icons.Rounded.Shield, "Bug reports", "Read what people have reported", onClick = { vm.open(com.novanity.musicplayer.ui.Destination.BugReports) })
                }
            }
        }
    }
    }
}

/** Which settings sections are open. They start closed, so the page is a short list. */
private val LocalOpenGroups = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>> { error("no settings groups") }

private val GroupInfo: Map<String, Pair<ImageVector, String>> = mapOf(
    "Account" to (Icons.Rounded.Person to "Your sign-in"),
    "Cloud library" to (Icons.Rounded.Sync to "Library status and downloads"),
    "Playback" to (Icons.Rounded.GraphicEq to "Crossfade, transitions, speed and equalizer"),
    "Appearance" to (Icons.Rounded.Palette to "Theme, colours and the visualizer"),
    "Updates" to (Icons.Rounded.Sync to "Version and automatic updates"),
    "Integrations" to (Icons.Rounded.Extension to "Discord"),
    "Library" to (Icons.Rounded.Shield to "Artist bios and privacy"),
    "Help" to (Icons.Rounded.BugReport to "Report a bug"),
    "Profile" to (Icons.Rounded.Person to "Banner, picture frame, name style and flair that others see"),
    "Customize" to (Icons.Rounded.Extension to "Choose what the player shows and how it behaves"),
)

/** A section heading you click to open or close. */
@Composable
private fun GroupLabel(text: String) {
    val open = LocalOpenGroups.current
    val isOpen = open[text] == true
    val turn by androidx.compose.animation.core.animateFloatAsState(if (isOpen) 90f else 0f, label = "chevron")
    val (icon, about) = GroupInfo[text] ?: (Icons.Rounded.Settings to "")
    Surface(
        onClick = { open[text] = !isOpen },
        shape = RoundedCornerShape(16.dp),
        color = if (isOpen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleMedium)
                if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = if (isOpen) "Close" else "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
    }
}

/** The settings of one section, shown while that section is open. */
@Composable
private fun SettingsCard(group: String, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = LocalOpenGroups.current[group] == true,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
        }
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


/** A box to describe a problem; it's sent to the admin along with the app version. */
@Composable
private fun ReportBugDialog(vm: com.novanity.musicplayer.ui.LibraryViewModel, appInfo: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Report a bug") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4000); problem = null },
                    placeholder = { Text("What happened, and what were you doing when it did?") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    problem ?: "Sent with your username and \u201C$appInfo\u201D.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && text.trim().length >= 5,
                onClick = {
                    sending = true
                    scope.launch {
                        val failed = vm.reportBug(text, appInfo)
                        sending = false
                        if (failed == null) { vm.notify("Thanks. Your report was sent."); onDismiss() } else problem = failed
                    }
                },
            ) { Text(if (sending) "Sending\u2026" else "Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}


/** A small heading inside a settings card. */
@Composable
private fun CardHeading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

/** Your profile's look: a live preview, then banner, picture frame, name style and flair. Others see it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileLookSettings(vm: com.novanity.musicplayer.ui.LibraryViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val account by vm.accountState.collectAsStateWithLifecycle()
    val name = (account as? com.novanity.musicplayer.data.AccountState.SignedIn)?.user?.username ?: "You"
    val look = profile.look
    fun set(next: com.novanity.musicplayer.data.ProfileLook) = vm.setProfile(profile.copy(look = next))
    val looks = com.novanity.musicplayer.ui.components.ProfileLooks
    val selected = MaterialTheme.colorScheme.primary
    Column {
        // Preview: what friends and anyone opening your profile will see.
        Row(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp))
                .background(com.novanity.musicplayer.ui.components.bannerBrush(look)).background(Color.Black.copy(alpha = 0.25f)).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.novanity.musicplayer.ui.components.FramedAvatar(name, vm.avatarUrl(profile.avatar), look, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                com.novanity.musicplayer.ui.components.StyledName(name, look, MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White))
                Text("This is how others see you", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        }
        CardHeading("Banner")
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            looks.banners.forEach { b ->
                Column(Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable { set(look.copy(banner = b.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    com.novanity.musicplayer.ui.components.BannerSwatch(
                        b,
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                            .then(if (look.banner == b.id) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
                    )
                    Text(b.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (look.banner == b.id) selected else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        CardHeading("Picture frame")
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            looks.frames.forEach { f ->
                Column(
                    Modifier.width(84.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (look.frame == f.id) selected.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { set(look.copy(frame = f.id)) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    com.novanity.musicplayer.ui.components.FramedAvatar(name, vm.avatarUrl(profile.avatar), look.copy(frame = f.id), 44.dp)
                    Text(f.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        CardHeading("Name style")
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            looks.names.forEach { n ->
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (look.name == n.id) selected.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { set(look.copy(name = n.id)) }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    com.novanity.musicplayer.ui.components.StyledName(n.label, look.copy(name = n.id, flair = ""), MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                }
            }
        }
        CardHeading("Flair")
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            looks.flairs.forEach { e ->
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (look.flair == e) selected.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { set(look.copy(flair = e)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(e.ifEmpty { "None" }, style = if (e.isEmpty()) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium)
                }
            }
        }
        TextButton(onClick = { set(com.novanity.musicplayer.data.ProfileLook()) }, modifier = Modifier.padding(8.dp)) { Text("Reset my profile look") }
    }
}

/** Switches for the parts of the player: Home rows, sidebar entries, and small details. */
@Composable
private fun CustomizeSettings(vm: com.novanity.musicplayer.ui.LibraryViewModel, desktop: Boolean) {
    val hidden by vm.hiddenModules.collectAsStateWithLifecycle()
    val startTab by vm.startTab.collectAsStateWithLifecycle()
    val m = com.novanity.musicplayer.data.Modules
    @Composable
    fun toggle(id: String, title: String, subtitle: String? = null) {
        val on = id !in hidden
        Row(Modifier.fillMaxWidth().clickable { vm.setModuleShown(id, !on) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { vm.setModuleShown(id, it) })
        }
    }
    Column {
        CardHeading("Opens on")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("home" to "Home", "search" to "Search", "library" to "Your Library").forEach { (id, label) ->
                androidx.compose.material3.FilterChip(selected = startTab == id, onClick = { vm.setStartTab(id) }, label = { Text(label) })
            }
        }
        CardHeading("Home page rows")
        toggle(m.HOME_QUICK, "Quick picks", "The tiles at the top")
        toggle(m.HOME_MIXES, "Made for you")
        toggle(m.HOME_RECENT, "Recently played")
        toggle(m.HOME_REPEAT, "On repeat")
        toggle(m.HOME_ADDED, "Recently added")
        toggle(m.HOME_ARTISTS, "Your artists")
        toggle(m.HOME_PLAYLISTS, "Your playlists")
        if (desktop) {
            CardHeading("Sidebar")
            toggle(m.NAV_PLAYLISTS, "Your playlists in the sidebar")
            toggle(m.NAV_DOWNLOADS, "Downloads")
            toggle(m.NAV_ACCOUNT, "Account")
            toggle(m.NAV_FRIENDS, "Friends")
            toggle(m.NAV_STATS, "Your stats")
            toggle(m.NAV_SHORTCUTS, "Shortcuts")
        }
        CardHeading("Details")
        toggle(m.ROW_DURATION, "Song length next to the artist")
        toggle(m.BAR_ALBUM, "Album name in the now-playing bar")
        toggle(m.CARD_CENTER, "Centre text under artwork")
        if (desktop) toggle(m.CARD_HOVER, "Cards react when you point at them")
        TextButton(onClick = { vm.resetModules() }, modifier = Modifier.padding(8.dp)) { Text("Reset these options") }
    }
}
