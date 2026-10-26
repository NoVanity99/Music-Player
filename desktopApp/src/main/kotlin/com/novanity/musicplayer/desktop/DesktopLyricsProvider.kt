package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.lyrics.Lyrics
import com.novanity.musicplayer.lyrics.LyricsProvider
import com.novanity.musicplayer.lyrics.LyricsResult
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/** "Song.lrc" / "Song.txt" next to the audio file, else lyrics embedded in its tags. */
class DesktopLyricsProvider : LyricsProvider {
    override suspend fun load(song: Song): LyricsResult {
        val audio = File(song.filePath)
        val base = audio.nameWithoutExtension
        val sidecar = audio.parentFile?.listFiles()?.firstOrNull { f ->
            f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true) && f.extension.lowercase() in setOf("lrc", "txt")
        }
        val text = sidecar?.let { runCatching { it.readText() }.getOrNull() }
            ?: runCatching { AudioFileIO.read(audio).tag?.getFirst(FieldKey.LYRICS) }.getOrNull()
        val lyrics = text?.takeIf { it.isNotBlank() }?.let(Lyrics::parse)
        return if (lyrics != null) LyricsResult.Found(lyrics) else LyricsResult.None
    }
}
