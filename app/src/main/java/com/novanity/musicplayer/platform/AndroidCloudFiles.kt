package com.novanity.musicplayer.platform

import com.novanity.musicplayer.data.CloudFiles
import com.novanity.musicplayer.data.CloudHttpException
import com.novanity.musicplayer.updatecore.UpdateCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads and covers in the app's private storage under [root]. */
class AndroidCloudFiles(private val root: File) : CloudFiles {
    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        relativePath: String,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val context = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            val dest = File(root, relativePath)
            try {
                UpdateCore.downloadWithHeaders(url, headers, dest, expectedSize, "VanityPlayer/Android", onProgress) { isCancelled() || !context.isActive }
            } catch (e: UpdateCore.HttpStatusException) {
                throw CloudHttpException(e.status)
            } catch (e: UpdateCore.DownloadCancelled) {
                throw CancellationException("Download cancelled")
            }
            dest.absolutePath
        }
    }

    override fun path(relativePath: String): String = File(root, relativePath).absolutePath
    override fun exists(path: String): Boolean = path.isNotEmpty() && File(path).isFile
    override fun size(path: String): Long = File(path).length()
    override fun delete(path: String) {
        // Only ever delete inside our own folder.
        val file = File(path)
        if (file.canonicalPath.startsWith(root.canonicalPath + File.separator)) file.delete()
    }

    override fun playableUri(path: String): String = android.net.Uri.fromFile(File(path)).toString()
}
