package com.novanity.musicplayer.data

import com.novanity.musicplayer.platform.StorageRoot

/** Storage roots used to shorten paths for display. Set by each platform at startup. */
object PathLabels {
    var roots: List<StorageRoot> = emptyList()
}

/** True if [path] is [folder] or one of its subfolders. Paths always use '/' separators. */
fun pathIsIn(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")

fun Song.isIn(folder: String): Boolean = pathIsIn(folderPath, folder)

/** Short, friendly label for an absolute path ("/storage/emulated/0/Music/Rock" -> "Music/Rock"). */
fun friendlyPath(path: String): String {
    // Android's internal storage is "/storage/emulated/<user>".
    val emulated = Regex("^/storage/emulated/\\d+").find(path)?.value
    if (emulated != null) {
        return if (path == emulated) "Internal storage" else path.removePrefix("$emulated/")
    }
    val root = PathLabels.roots.filter { pathIsIn(path, it.path.trimEnd('/')) }.maxByOrNull { it.path.length }
    if (root != null) {
        val rel = path.removePrefix(root.path.trimEnd('/')).trimStart('/')
        return if (rel.isEmpty()) root.label else if (root.removable) "${root.label} • $rel" else rel
    }
    return path
}
