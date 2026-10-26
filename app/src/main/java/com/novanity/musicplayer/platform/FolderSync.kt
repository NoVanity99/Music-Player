package com.novanity.musicplayer.platform

import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.pathIsIn

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Walks the user's chosen folders on disk and asks Android's media scanner to index any audio files
 * it doesn't know about yet, so freshly copied songs show up without waiting for the system.
 */
object FolderSync {
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka", "aiff", "aif", "ape", "alac", "3gp",
    )
    private const val MAX_FILES = 5_000
    private const val MAX_DEPTH = 24

    /** Paths of audio files inside [included] (minus [excluded]) that MediaStore hasn't indexed. */
    suspend fun findUnindexed(context: Context, included: Set<String>, excluded: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            val known = MediaStoreScanner.knownAudioPaths(context)
            val found = ArrayList<String>()
            included.forEach { walk(File(it), excluded, known, found, 0) }
            found
        }

    private fun walk(dir: File, excluded: Set<String>, known: Set<String>, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        if (excluded.any { pathIsIn(dir.path, it) }) return
        val children = dir.listFiles() ?: return
        if (children.any { it.name == ".nomedia" }) return // Android deliberately hides these folders
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                walk(f, excluded, known, out, depth + 1)
            } else if (f.extension.lowercase() in AUDIO_EXTENSIONS && f.path !in known) {
                out += f.path
                if (out.size >= MAX_FILES) return
            }
        }
    }

    /** Indexes [paths] with the system media scanner; returns once it's done (or after a timeout). */
    suspend fun index(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(90_000) {
            suspendCancellableCoroutine { cont ->
                val remaining = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }
}
