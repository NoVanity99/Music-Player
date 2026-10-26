package com.novanity.musicplayer.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.platform.ArtworkProvider
import org.jaudiotagger.audio.AudioFileIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/** Embedded cover art (via jaudiotagger), else a cover/folder image next to the file. */
class DesktopArtworkProvider : ArtworkProvider {
    private val folderNames = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
    private val imageExtensions = listOf("jpg", "jpeg", "png", "webp")

    override suspend fun load(song: Song, sizePx: Int): ImageBitmap? {
        val bytes = embedded(song.filePath) ?: folderImage(song.folderPath) ?: return null
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        return scale(image, sizePx).toComposeImageBitmap()
    }

    /** The song's cover as a square JPEG of at most [px] pixels (for Discord), or null if it has none. */
    fun coverJpeg(song: Song, px: Int = 512): ByteArray? {
        val bytes = embedded(song.filePath) ?: folderImage(song.folderPath) ?: return null
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        return scale(image, px).encodeToData(EncodedImageFormat.JPEG, 88)?.bytes
    }

    private fun embedded(path: String): ByteArray? = runCatching {
        AudioFileIO.read(File(path)).tag?.firstArtwork?.binaryData
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun folderImage(folder: String): ByteArray? {
        val files = File(folder).listFiles() ?: return null
        val match = files.filter { it.isFile && it.extension.lowercase() in imageExtensions }
            .minByOrNull { f ->
                val i = folderNames.indexOf(f.nameWithoutExtension.lowercase())
                if (i >= 0) i else Int.MAX_VALUE
            }
            ?.takeIf { it.nameWithoutExtension.lowercase() in folderNames }
        return match?.readBytes()
    }

    /** Center-crops to a square and scales down to at most [px] (never up). */
    private fun scale(image: Image, px: Int): Image {
        val side = minOf(image.width, image.height)
        val target = minOf(px, side)
        val src = Rect.makeXYWH(((image.width - side) / 2).toFloat(), ((image.height - side) / 2).toFloat(), side.toFloat(), side.toFloat())
        val surface = Surface.makeRasterN32Premul(target, target)
        surface.canvas.drawImageRect(
            image, src, Rect.makeWH(target.toFloat(), target.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
        )
        return surface.makeImageSnapshot()
    }
}
