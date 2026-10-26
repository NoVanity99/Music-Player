package com.novanity.musicplayer.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.novanity.musicplayer.data.Song

/** Simple persistent key-value storage (SharedPreferences on Android, a properties file on desktop). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getStringSet(key: String): Set<String>?
    fun putStringSet(key: String, value: Set<String>)
}

/** Where songs come from: MediaStore on Android, a folder walk with tag reading on desktop. */
interface LibrarySource {
    /** Every song this source can see in [included] folders (filtering happens in the repository). */
    suspend fun scan(included: Set<String>, excluded: Set<String>): List<Song>

    /**
     * Looks for files in the chosen folders that [scan] doesn't know about yet and makes them
     * visible. Returns true if a rescan is worthwhile.
     */
    suspend fun indexNewFiles(included: Set<String>, excluded: Set<String>): Boolean

    /** Calls [onChange] when the underlying library changes (optional). */
    fun observeChanges(onChange: () -> Unit) {}
}

/** Decodes a song's artwork to an image of roughly [sizePx] square. */
fun interface ArtworkProvider {
    suspend fun load(song: Song, sizePx: Int): ImageBitmap?
}

@Immutable
data class StorageRoot(val label: String, val path: String, val removable: Boolean)

@Immutable
data class FolderEntry(val name: String, val path: String)

@Immutable
data class DirectoryListing(val folders: List<FolderEntry>, val audioFileCount: Int)

/** Platform capabilities the shared UI needs. */
interface PlatformUi {
    /** True on Windows/desktop: enables the wide layout, hover affordances and keyboard hints. */
    val isDesktop: Boolean
    val supportsDynamicColor: Boolean
    val supportsBlur: Boolean
    val supportsDiscordPresence: Boolean
    val supportsEqualizer: Boolean

    fun dynamicColorScheme(dark: Boolean): ColorScheme?

    fun storageRoots(): List<StorageRoot>

    /** Lists subfolders of [path]; null if it can't be read. Called off the main thread. */
    fun listDirectory(path: String): DirectoryListing?

    /** Returns a launcher for the OS's own folder picker; the callback gets an absolute path. */
    @Composable
    fun rememberSystemFolderPicker(onPicked: (String) -> Unit): () -> Unit
}

val LocalPlatform = staticCompositionLocalOf<PlatformUi> { error("No PlatformUi provided") }
