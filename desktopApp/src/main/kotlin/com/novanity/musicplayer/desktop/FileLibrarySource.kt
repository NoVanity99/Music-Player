package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.pathIsIn
import com.novanity.musicplayer.platform.LibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Level
import java.util.logging.Logger

/** Audio formats FFmpeg can play that we look for in the chosen folders. */
val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "ape", "alac", "mka", "wv", "dsf", "mpc",
)

private const val MIN_DURATION_MS = 30_000L

/** Cached tag data per file, so a rescan only reads files that changed. */
@Serializable
private data class CachedTrack(
    val path: String,
    val size: Long,
    val modified: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val track: Int,
    val disc: Int,
    val created: Long,
)

@Serializable
private data class TrackCache(val version: Int = 1, val tracks: List<CachedTrack> = emptyList())

/**
 * Desktop library: walks the user's chosen folders, reads tags (jaudiotagger, with an FFmpeg
 * fallback for formats it doesn't know), and keeps a JSON cache.
 */
class FileLibrarySource(private val cacheFile: File) : LibrarySource {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        quietLibraries()
    }

    override suspend fun scan(included: Set<String>, excluded: Set<String>): List<Song> = withContext(Dispatchers.IO) {
        val files = ArrayList<File>()
        included.forEach { walk(File(it), excluded, files, 0) }

        val cache = loadCache().associateBy { it.path }
        val tracks = coroutineScope {
            files.chunked(64).map { chunk ->
                async(Dispatchers.IO) {
                    chunk.mapNotNull { f ->
                        val path = DesktopPaths.normalize(f)
                        val hit = cache[path]
                        if (hit != null && hit.size == f.length() && hit.modified == f.lastModified()) hit else readTrack(f, path)
                    }
                }
            }.awaitAll().flatten()
        }
        saveCache(tracks)
        tracks.filter { it.durationMs >= MIN_DURATION_MS }.map { it.toSong() }
    }

    /** The scan above already reads the folders on disk, so a rescan is how we find new files. */
    override suspend fun indexNewFiles(included: Set<String>, excluded: Set<String>): Boolean = true

    private fun walk(dir: File, excluded: Set<String>, out: MutableList<File>, depth: Int) {
        if (depth > 32) return
        val path = DesktopPaths.normalize(dir)
        if (excluded.any { pathIsIn(path, it) }) return
        val children = dir.listFiles() ?: return
        if (children.any { it.name == ".nomedia" }) return
        for (f in children) {
            if (f.name.startsWith(".") || f.isHidden) continue
            if (f.isDirectory) walk(f, excluded, out, depth + 1)
            else if (f.extension.lowercase() in AUDIO_EXTENSIONS) out += f
        }
    }

    private fun readTrack(file: File, path: String): CachedTrack? {
        val created = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
        }.getOrDefault(file.lastModified())
        val base = CachedTrack(
            path = path, size = file.length(), modified = file.lastModified(),
            title = file.nameWithoutExtension, artist = "", album = "", albumArtist = "",
            durationMs = 0, track = 0, disc = 0, created = created,
        )
        val tagged = readWithTagger(file, base)
        // jaudiotagger can "succeed" with empty tags or no length (e.g. Opus); let FFmpeg fill gaps.
        if (tagged != null && tagged.durationMs > 0 && tagged.artist.isNotEmpty()) return tagged
        val probed = readWithFfmpeg(file, base) ?: return tagged?.takeIf { it.durationMs > 0 }
        return if (tagged == null) probed else probed.copy(
            title = if (tagged.title != base.title) tagged.title else probed.title,
            artist = tagged.artist.ifEmpty { probed.artist },
            album = tagged.album.ifEmpty { probed.album },
            albumArtist = tagged.albumArtist.ifEmpty { probed.albumArtist },
            track = if (tagged.track > 0) tagged.track else probed.track,
            disc = if (tagged.disc > 0) tagged.disc else probed.disc,
            durationMs = if (tagged.durationMs > 0) tagged.durationMs else probed.durationMs,
        )
    }

    private fun readWithTagger(file: File, base: CachedTrack): CachedTrack? = try {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        fun field(key: FieldKey) = runCatching { tag?.getFirst(key) }.getOrNull()?.trim().orEmpty()
        base.copy(
            title = field(FieldKey.TITLE).ifEmpty { base.title },
            artist = field(FieldKey.ARTIST),
            album = field(FieldKey.ALBUM),
            albumArtist = field(FieldKey.ALBUM_ARTIST),
            durationMs = (audio.audioHeader.preciseTrackLength * 1000).toLong(),
            track = field(FieldKey.TRACK).substringBefore('/').toIntOrNull() ?: 0,
            disc = field(FieldKey.DISC_NO).substringBefore('/').toIntOrNull() ?: 0,
        )
    } catch (_: Throwable) {
        null
    }

    private fun readWithFfmpeg(file: File, base: CachedTrack): CachedTrack? = try {
        FFmpegFrameGrabber(file).use { g ->
            g.start()
            // Container tags plus audio-stream tags (Ogg/Opus keep them on the stream).
            val meta = (g.metadata.orEmpty() + g.audioMetadata.orEmpty()).mapKeys { it.key.lowercase() }
            base.copy(
                title = meta["title"]?.trim()?.ifEmpty { null } ?: base.title,
                artist = meta["artist"].orEmpty().trim(),
                album = meta["album"].orEmpty().trim(),
                albumArtist = (meta["album_artist"] ?: meta["albumartist"]).orEmpty().trim(),
                durationMs = g.lengthInTime / 1000,
                track = meta["track"]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                disc = meta["disc"]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            )
        }
    } catch (_: Throwable) {
        null // not a playable audio file
    }

    private fun CachedTrack.toSong(): Song {
        val folder = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val albumName = album.ifEmpty { "Unknown album" }
        val albumKey = if (album.isEmpty()) "folder:$folder" else "${albumArtist.ifEmpty { artist }.lowercase()}|${album.lowercase()}"
        return Song(
            id = stableId(path),
            uri = path,
            title = title,
            artist = artist.ifEmpty { albumArtist }.ifEmpty { "Unknown artist" },
            album = albumName,
            albumId = stableId(albumKey),
            durationMs = durationMs,
            track = track,
            disc = disc,
            dateAdded = created / 1000,
            folderPath = folder,
            filePath = path,
        )
    }

    private fun loadCache(): List<CachedTrack> = runCatching {
        json.decodeFromString<TrackCache>(cacheFile.readText()).tracks
    }.getOrDefault(emptyList())

    private fun saveCache(tracks: List<CachedTrack>) {
        runCatching {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(TrackCache.serializer(), TrackCache(tracks = tracks)))
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        }
    }

    companion object {
        // Strong reference: java.util.logging keeps loggers weakly, which would drop our level.
        private val taggerLogger: Logger = Logger.getLogger("org.jaudiotagger")

        /** Silences jaudiotagger's very chatty INFO logging and FFmpeg's console output. */
        fun quietLibraries() {
            taggerLogger.level = Level.OFF
            runCatching { org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET) }
        }

        /** 63-bit FNV-1a hash: stable across runs, so playlists/likes keep pointing at the same file. */
        fun stableId(key: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in key) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h and Long.MAX_VALUE
        }
    }
}
