package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.FolderBrowser
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.ShuffleGradient

/** First-run screen: the library stays empty until the user points the app at a music folder. */
@Composable
fun FolderSetupScreen(actions: SongActions) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    val accentWash = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accentWash, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GradientArt(ShuffleGradient, Icons.Rounded.FolderSpecial, Modifier.size(120.dp), RoundedCornerShape(32.dp))
            Spacer(Modifier.height(32.dp))
            Text("Where's your music?", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Pick the folder you keep your songs in. Only music from that folder (and its subfolders) " +
                    "will show up, and it's checked for new songs every time you open the app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = { browsing = true },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Choose a folder", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "You can add more folders, or ignore some, later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (browsing) {
        FolderBrowser(
            title = "Choose your music folder",
            confirmLabel = "Use this folder",
            onPick = {
                actions.library.addFolder(it)
                browsing = false
            },
            onDismiss = { browsing = false },
        )
    }
}
