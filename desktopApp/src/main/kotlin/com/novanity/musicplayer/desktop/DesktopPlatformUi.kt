package com.novanity.musicplayer.desktop

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

class DesktopPlatformUi(private val parent: java.awt.Component?) : PlatformUi {
    override val isDesktop = true
    override val supportsDynamicColor = false
    override val supportsBlur = true
    override val supportsDiscordPresence = true
    override val supportsEqualizer = false

    override fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

    override fun storageRoots(): List<StorageRoot> = desktopStorageRoots()

    override fun listDirectory(path: String): DirectoryListing? {
        val children = File(path).listFiles() ?: return null
        return DirectoryListing(
            folders = children.filter { it.isDirectory && !it.isHidden && !it.name.startsWith(".") && !it.name.startsWith("$") }
                .sortedBy { it.name.lowercase() }
                .map { FolderEntry(it.name, DesktopPaths.normalize(it)) },
            audioFileCount = children.count { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS },
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
