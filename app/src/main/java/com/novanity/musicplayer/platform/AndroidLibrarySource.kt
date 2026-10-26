package com.novanity.musicplayer.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.novanity.musicplayer.data.Song

/** Library from Android's MediaStore, plus an on-disk check that indexes files it missed. */
class AndroidLibrarySource(private val context: Context) : LibrarySource {
    override suspend fun scan(included: Set<String>, excluded: Set<String>): List<Song> = MediaStoreScanner.scan(context)

    override suspend fun indexNewFiles(included: Set<String>, excluded: Set<String>): Boolean {
        val missing = FolderSync.findUnindexed(context, included, excluded)
        if (missing.isEmpty()) return false
        FolderSync.index(context, missing)
        return true
    }

    override fun observeChanges(onChange: () -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        context.contentResolver.registerContentObserver(MediaStoreScanner.collection, true, observer)
    }
}
