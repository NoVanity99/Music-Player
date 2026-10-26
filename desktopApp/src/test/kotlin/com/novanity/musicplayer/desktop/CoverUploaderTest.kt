package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.Song
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverUploaderTest {
    private val hour = 60 * 60 * 1000L

    @Test
    fun uploadsOnceAndReusesTheLinkUntilNearExpiry() {
        val dir = Files.createTempDirectory("covers").toFile()
        var clock = 1_000_000L
        val uploads = mutableListOf<Int>()
        fun uploader() = CoverUploader(File(dir, "c.properties"), now = { clock }) { bytes ->
            uploads += bytes.size
            "https://litter.catbox.moe/abc${uploads.size}.jpg\n"
        }
        val image = byteArrayOf(1, 2, 3)

        assertEquals("https://litter.catbox.moe/abc1.jpg", uploader().urlFor(image))
        // A new app session reads the saved link instead of uploading again.
        clock += 60 * hour
        assertEquals("https://litter.catbox.moe/abc1.jpg", uploader().urlFor(image))
        assertEquals(1, uploads.size)
        // With under 6 hours left, upload again so Discord never shows a dead link.
        clock += 7 * hour
        assertEquals("https://litter.catbox.moe/abc2.jpg", uploader().urlFor(image))
        // A different cover is a separate upload.
        assertEquals("https://litter.catbox.moe/abc3.jpg", uploader().urlFor(byteArrayOf(9)))
    }

    @Test
    fun badRepliesAreIgnored() {
        val dir = Files.createTempDirectory("covers").toFile()
        assertNull(CoverUploader(File(dir, "c.properties")) { "<html>error</html>" }.urlFor(byteArrayOf(1)))
        assertNull(CoverUploader(File(dir, "c.properties")) { throw java.io.IOException("offline") }.urlFor(byteArrayOf(1)))
    }

    @Test
    fun coverFromTheFolderBecomesA512pxJpeg() {
        val dir = Files.createTempDirectory("album").toFile()
        ImageIO.write(BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB), "png", File(dir, "cover.png"))
        val song = Song(1, "x", "T", "A", "Al", 1, 1000, 1, 1, 0, dir.absolutePath, File(dir, "song.mp3").absolutePath)
        val jpeg = assertNotNull(DesktopArtworkProvider().coverJpeg(song))
        assertTrue(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte(), "is a JPEG")
        val decoded = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)

        val empty = Files.createTempDirectory("noart").toFile()
        assertNull(DesktopArtworkProvider().coverJpeg(song.copy(folderPath = empty.absolutePath, filePath = File(empty, "s.mp3").absolutePath)))
    }
}
