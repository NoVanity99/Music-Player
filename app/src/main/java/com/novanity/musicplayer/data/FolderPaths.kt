package com.novanity.musicplayer.data

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Converts a folder picked with the system folder picker (ACTION_OPEN_DOCUMENT_TREE) into the
 * absolute path MediaStore reports for files in it, e.g. "primary:Music/Rock" ->
 * "/storage/emulated/0/Music/Rock". Returns null for providers that aren't local storage.
 */
fun treeUriToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = docId.substringBefore(':')
    val relative = docId.substringAfter(':', "").trim('/')
    val root = when {
        volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
        volume.equals("home", ignoreCase = true) -> "${Environment.getExternalStorageDirectory().absolutePath}/Documents"
        else -> "/storage/$volume" // SD card / USB drive, e.g. /storage/1A2B-3C4D
    }
    return if (relative.isEmpty()) root else "$root/$relative"
}

/** Short, friendly label for an absolute path ("/storage/emulated/0/Music/Rock" -> "Music/Rock"). */
fun friendlyPath(path: String): String {
    val internal = Environment.getExternalStorageDirectory().absolutePath
    val emulated = Regex("^/storage/emulated/\\d+").find(path)?.value
    return when {
        emulated != null && path == emulated -> "Internal storage"
        emulated != null -> path.removePrefix("$emulated/")
        path == internal -> "Internal storage"
        path.startsWith("$internal/") -> path.removePrefix("$internal/")
        path.startsWith("/storage/") -> "SD card • " + path.removePrefix("/storage/").substringAfter('/', "")
            .ifEmpty { path.removePrefix("/storage/") }
        else -> path
    }
}
