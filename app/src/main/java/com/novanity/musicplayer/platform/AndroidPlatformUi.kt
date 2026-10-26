package com.novanity.musicplayer.platform

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import java.io.File

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka", "aiff", "aif", "ape")

class AndroidPlatformUi(private val context: Context) : PlatformUi {
    override val isDesktop = false
    override val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    override val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Discord's mobile app exposes no local Rich Presence connection, so this stays off on Android.
    override val supportsDiscordPresence = false
    override val supportsEqualizer = true

    override val equalizer: EqualizerInfo? get() = AndroidEqualizer.info

    override fun dynamicColorScheme(dark: Boolean): ColorScheme? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null

    override fun storageRoots(): List<StorageRoot> {
        val internal = Environment.getExternalStorageDirectory().absolutePath
        val removable = context.getExternalFilesDirs(null).drop(1).filterNotNull()
            .map { it.absolutePath.substringBefore("/Android/") }
            .distinct()
        return listOf(StorageRoot("Internal storage", internal, false)) +
            removable.mapIndexed { i, p -> StorageRoot(if (removable.size == 1) "SD card" else "SD card ${i + 1}", p, true) }
    }

    override fun listDirectory(path: String): DirectoryListing? {
        val children = File(path).listFiles() ?: return null
        return DirectoryListing(
            folders = children.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .map { FolderEntry(it.name, it.absolutePath) },
            audioFileCount = children.count { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS },
        )
    }

    @Composable
    override fun rememberLyricsAccessRequest(onGranted: () -> Unit): ((folder: String) -> Unit)? {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onGranted()
            }
        }
        return { folder -> launcher.launch(AndroidLyricsProvider.folderHintUri(folder)) }
    }

    @Composable
    override fun rememberSystemFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::treeUriToPath)?.let(onPicked)
        }
        return { launcher.launch(null) }
    }
}

/**
 * Converts a folder picked with ACTION_OPEN_DOCUMENT_TREE into the absolute path MediaStore
 * reports, e.g. "primary:Music/Rock" -> "/storage/emulated/0/Music/Rock".
 */
fun treeUriToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = docId.substringBefore(':')
    val relative = docId.substringAfter(':', "").trim('/')
    val root = when {
        volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
        volume.equals("home", ignoreCase = true) -> "${Environment.getExternalStorageDirectory().absolutePath}/Documents"
        else -> "/storage/$volume" // SD card / USB drive
    }
    return if (relative.isEmpty()) root else "$root/$relative"
}
