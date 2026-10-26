package com.novanity.musicplayer.desktop

import java.io.File

/** Where the desktop app keeps its database, settings and caches. */
object DesktopPaths {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    val dataDir: File by lazy {
        val base = when {
            isWindows -> System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Roaming")
            System.getProperty("os.name").lowercase().contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        }
        File(base, "MusicPlayer").apply { mkdirs() }
    }

    val cacheDir: File by lazy { File(dataDir, "cache").apply { mkdirs() } }

    /** Absolute path with '/' separators (how the shared code expects paths). */
    fun normalize(file: File): String = file.absolutePath.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
}
