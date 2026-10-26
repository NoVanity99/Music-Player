package com.novanity.musicplayer.platform

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.lyrics.Lyrics
import com.novanity.musicplayer.lyrics.LyricsProvider
import com.novanity.musicplayer.lyrics.LyricsResult
import java.io.File

/**
 * Lyrics from "Song.lrc"/"Song.txt" next to the audio file. Android 11+ hides non-media files like
 * .lrc from apps, so there we read them through folders the user approved with the system picker.
 */
class AndroidLyricsProvider(private val context: Context) : LyricsProvider {
    private val extensions = listOf("lrc", "txt", "LRC")

    override suspend fun load(song: Song): LyricsResult {
        if (song.filePath.isEmpty()) return LyricsResult.None
        val audio = File(song.filePath)
        val base = audio.nameWithoutExtension

        // 1) Direct file access (works up to Android 10, and on some devices beyond).
        extensions.forEach { ext ->
            val f = File(audio.parentFile, "$base.$ext")
            if (f.canRead()) runCatching { f.readText() }.getOrNull()?.let(::parsed)?.let { return it }
        }

        // 2) Through an approved folder (Storage Access Framework).
        val trees = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        var covered = false
        for (tree in trees) {
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            extensions.forEach { ext ->
                val docId = pathToDocId("${song.folderPath}/$base.$ext") ?: return@forEach
                if (!docId.startsWith(treeDocId.trimEnd('/'))) return@forEach
                covered = true
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()?.let(::parsed)?.let { return it }
            }
        }

        val needsAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !covered
        return if (needsAccess) LyricsResult.NeedsAccess(song.folderPath) else LyricsResult.None
    }

    private fun parsed(text: String): LyricsResult? = Lyrics.parse(text)?.let { LyricsResult.Found(it) }

    companion object {
        /** "/storage/emulated/0/Music/a.lrc" -> "primary:Music/a.lrc"; "/storage/1A2B-3C4D/x" -> "1A2B-3C4D:x". */
        fun pathToDocId(path: String): String? {
            val internal = Environment.getExternalStorageDirectory().absolutePath
            return when {
                path.startsWith("$internal/") -> "primary:" + path.removePrefix("$internal/")
                path.startsWith("/storage/") -> {
                    val rest = path.removePrefix("/storage/")
                    val volume = rest.substringBefore('/')
                    "$volume:" + rest.substringAfter('/', "")
                }
                else -> null
            }
        }

        fun folderHintUri(folder: String): Uri? = pathToDocId(folder)?.let {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", it)
        }
    }
}
