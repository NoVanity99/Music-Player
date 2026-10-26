package com.novanity.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.Audio.AudioColumns
import android.util.Log

/** Reads the device's audio library from MediaStore. Call off the main thread. */
object MediaStoreScanner {
    private const val TAG = "MediaStoreScanner"
    private const val MIN_DURATION_MS = 30_000

    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION") // DATA is still the only way to get an absolute folder path on all versions.
    fun scan(context: Context): List<Song> {
        val projection = buildList {
            add(BaseColumns._ID)
            add(AudioColumns.TITLE)
            add(AudioColumns.ARTIST)
            add(AudioColumns.ALBUM)
            add(AudioColumns.ALBUM_ID)
            add(AudioColumns.DURATION)
            add(AudioColumns.TRACK)
            add(AudioColumns.DATE_ADDED)
            add(AudioColumns.DISPLAY_NAME)
            add(AudioColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(AudioColumns.RELATIVE_PATH)
        }.toTypedArray()

        val selection = "${AudioColumns.IS_RINGTONE} = 0 AND ${AudioColumns.IS_NOTIFICATION} = 0 AND " +
            "${AudioColumns.IS_ALARM} = 0 AND ${AudioColumns.DURATION} >= ?"
        val args = arrayOf(MIN_DURATION_MS.toString())

        val cursor = try {
            context.contentResolver.query(collection, projection, selection, args, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read audio", e)
            null
        } ?: return emptyList()

        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            val titleCol = c.getColumnIndexOrThrow(AudioColumns.TITLE)
            val artistCol = c.getColumnIndexOrThrow(AudioColumns.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(AudioColumns.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(AudioColumns.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(AudioColumns.DURATION)
            val trackCol = c.getColumnIndexOrThrow(AudioColumns.TRACK)
            val dateCol = c.getColumnIndexOrThrow(AudioColumns.DATE_ADDED)
            val nameCol = c.getColumnIndexOrThrow(AudioColumns.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(AudioColumns.DATA)
            val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.getColumnIndex(AudioColumns.RELATIVE_PATH)
            } else -1

            val songs = ArrayList<Song>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.getString(nameCol).orEmpty()
                val data = c.getString(dataCol)
                val folder = when {
                    !data.isNullOrEmpty() -> data.substringBeforeLast('/', "")
                    relPathCol >= 0 -> c.getString(relPathCol).orEmpty().trimEnd('/')
                    else -> ""
                }.ifEmpty { "/" }
                val rawTrack = c.getInt(trackCol)
                songs += Song(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.'),
                    artist = c.getString(artistCol).cleanTag() ?: "Unknown artist",
                    album = c.getString(albumCol).cleanTag() ?: "Unknown album",
                    albumId = c.getLong(albumIdCol),
                    durationMs = c.getLong(durationCol),
                    // MediaStore encodes disc number as the thousands digit.
                    track = rawTrack % 1000,
                    disc = rawTrack / 1000,
                    dateAdded = c.getLong(dateCol),
                    folderPath = folder,
                    filePath = data.orEmpty(),
                )
            }
            songs
        }
    }

    /** Every audio file path MediaStore has indexed (no filters), used to spot new files on disk. */
    @Suppress("DEPRECATION")
    fun knownAudioPaths(context: Context): Set<String> = try {
        context.contentResolver.query(collection, arrayOf(AudioColumns.DATA), null, null, null)?.use { c ->
            val paths = HashSet<String>(c.count)
            while (c.moveToNext()) c.getString(0)?.let(paths::add)
            paths
        }.orEmpty()
    } catch (e: SecurityException) {
        emptySet()
    }

    private fun String?.cleanTag(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
}
