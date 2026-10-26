package com.novanity.musicplayer.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.novanity.musicplayer.ui.components.EmptyState

val audioPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun Context.hasAudioPermission() =
    ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Shows [content] once audio access is granted; otherwise explains why it's needed. */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(context.hasAudioPermission()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedOnce = true
        // If the system won't show a rationale after a denial, the dialog won't appear again.
        permanentlyDenied = !ok && context.findActivity()?.shouldShowRequestPermissionRationale(audioPermission) == false
    }

    // Re-check when returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = context.hasAudioPermission()
    }

    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) launcher.launch(audioPermission)
    }

    if (granted) {
        content()
        return
    }

    Column(Modifier.systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Rounded.LibraryMusic,
            title = "Let's find your music",
            message = "To play the songs stored on this phone, the app needs permission to read your audio files. " +
                "Nothing ever leaves your device — this app has no internet access at all.",
            action = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!permanentlyDenied) {
                        Button(onClick = { launcher.launch(audioPermission) }) { Text("Allow access") }
                        Spacer(Modifier.height(8.dp))
                    }
                    val openSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    if (permanentlyDenied) {
                        Button(onClick = openSettings) { Text("Open app settings") }
                        Spacer(Modifier.height(8.dp))
                        Text("Then tap Permissions → Music and audio → Allow.")
                    } else {
                        TextButton(onClick = openSettings) { Text("Open app settings") }
                    }
                }
            },
        )
    }
}
