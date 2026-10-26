package com.novanity.musicplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.update.UpdateManager
import com.novanity.musicplayer.update.UpdateState

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** Renders the small subset of Markdown we use in release notes (headings and bullet lists). */
@Composable
fun ReleaseNotes(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
            when {
                line.startsWith("#") -> Text(
                    line.trimStart('#').trim(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row {
                    Text("•", modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(line.trimStart().drop(2).replace("**", ""), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(line.replace("**", ""), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Release notes + install button, shown when an update is found (and during the install). */
@Composable
fun UpdateDialog(updates: UpdateManager) {
    val prompt by updates.prompt.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    if (!prompt) return
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Verifying -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    } ?: return

    AlertDialog(
        onDismissRequest = { if (state !is UpdateState.Downloading && state !is UpdateState.Installing) updates.dismissPrompt() },
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text("Update to ${info.version}") },
        text = {
            Column {
                Text(
                    "You have ${updates.currentVersion} • download ${formatBytes(info.asset.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text("What's new", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ReleaseNotes(
                    info.notes.ifBlank { "Bug fixes and improvements." },
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp),
                )
                Spacer(Modifier.padding(6.dp))
                UpdateProgress(state, updates)
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available, is UpdateState.Failed -> Button(onClick = updates::install) { Text("Update now") }
                is UpdateState.NeedsPermission -> Button(onClick = updates::openPermissionSettings) { Text("Allow installs") }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                TextButton(onClick = updates::dismissPrompt) { Text("Later") }
            }
        },
    )
}

@Composable
fun UpdateProgress(state: UpdateState, updates: UpdateManager) {
    when (state) {
        is UpdateState.Downloading -> Column {
            Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        is UpdateState.Verifying -> Text("Verifying download…", style = MaterialTheme.typography.bodySmall)
        is UpdateState.Installing -> Text("Starting the installer…", style = MaterialTheme.typography.bodySmall)
        is UpdateState.NeedsPermission -> Text(
            "To install updates, allow this app to install apps: tap “Allow installs”, switch on “Allow from this source”, then come back.",
            style = MaterialTheme.typography.bodySmall,
        )
        is UpdateState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> {}
    }
}
