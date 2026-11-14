package com.novanity.musicplayer.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.novanity.musicplayer.platform.DirectoryListing
import com.novanity.musicplayer.platform.FolderEntry
import com.novanity.musicplayer.platform.PlatformUi
import com.novanity.musicplayer.platform.StorageRoot
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

class DesktopPlatformUi(
    private val parent: java.awt.Component?,
    override val discordStatus: kotlinx.coroutines.flow.StateFlow<String>? = null,
    override val openMiniPlayer: (() -> Unit)? = null,
) : PlatformUi {
    override val isDesktop = true
    override val supportsDynamicColor = false
    override val supportsBlur = true
    override val supportsDiscordPresence = true
    override val windowControlsWidth = WindowControlsWidth
    override val supportsEqualizer = true
    override val equalizer get() = com.novanity.musicplayer.desktop.audio.DesktopEqualizer.info

    @Composable
    override fun rememberImagePicker(onPicked: (ByteArray) -> Unit): (() -> Unit)? {
        val callback = rememberUpdatedState(onPicked)
        return {
            SwingUtilities.invokeLater {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                val chooser = JFileChooser(FileSystemView.getFileSystemView().defaultDirectory).apply {
                    dialogTitle = "Choose a profile picture"
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Pictures (JPEG, PNG)", "jpg", "jpeg", "png")
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.let { file -> runCatching { squareJpeg(file) }.getOrNull()?.let { callback.value(it) } }
                }
            }
        }
    }

    /** The middle square of a picture, shrunk to 320 px, as a JPEG. */
    private fun squareJpeg(file: File): ByteArray? {
        val source = javax.imageio.ImageIO.read(file) ?: return null
        val side = minOf(source.width, source.height)
        val out = java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(source, 0, 0, 320, 320, (source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2, java.awt.Color.BLACK, null)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "jpg", it) }.toByteArray()
    }

    override fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

    override fun storageRoots(): List<StorageRoot> = desktopStorageRoots()

    override fun listDirectory(path: String): DirectoryListing? {
        val children = File(path).listFiles() ?: return null
        return DirectoryListing(
            folders = children.filter { it.isDirectory && !it.isHidden && !it.name.startsWith(".") && !it.name.startsWith("$") }
                .sortedBy { it.name.lowercase() }
                .map { FolderEntry(it.name, DesktopPaths.normalize(it)) },
            audioFileCount = 0, // the app no longer scans local folders
        )
    }

    @Composable
    override fun rememberSystemFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onPicked)
        return {
            SwingUtilities.invokeLater {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                val chooser = JFileChooser(FileSystemView.getFileSystemView().defaultDirectory).apply {
                    dialogTitle = "Choose a music folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.let { callback.value(DesktopPaths.normalize(it)) }
                }
            }
        }
    }
}

/** Home, Music and every drive (C:, D:, ...). */
fun desktopStorageRoots(): List<StorageRoot> {
    val home = File(System.getProperty("user.home"))
    val roots = ArrayList<StorageRoot>()
    roots += StorageRoot("Home", DesktopPaths.normalize(home), false)
    File(home, "Music").takeIf { it.isDirectory }?.let { roots += StorageRoot("Music", DesktopPaths.normalize(it), false) }
    val fsv = FileSystemView.getFileSystemView()
    File.listRoots().forEach { r ->
        val name = runCatching { fsv.getSystemDisplayName(r) }.getOrNull()?.takeIf { it.isNotBlank() } ?: r.path
        roots += StorageRoot(name, DesktopPaths.normalize(r), removable = DesktopPaths.isWindows && !r.path.startsWith("C"))
    }
    return roots
}
