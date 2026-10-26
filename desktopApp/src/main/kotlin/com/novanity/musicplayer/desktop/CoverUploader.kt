package com.novanity.musicplayer.desktop

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * Puts a song's own cover online so Discord can show it (Discord only displays images from the
 * web). Uploads to Litterbox, an anonymous host that deletes files after 72 hours. Each image is
 * uploaded once and its link reused until shortly before it expires; nothing else is sent.
 */
class CoverUploader(
    private val cacheFile: File = File(DesktopPaths.cacheDir, "discord-covers.properties"),
    private val now: () -> Long = System::currentTimeMillis,
    private val upload: (ByteArray) -> String? = ::uploadToLitterbox,
) {
    private val cache = Properties().apply { runCatching { cacheFile.inputStream().use(::load) } }

    /** Blocking; call off the main thread. Null when the upload fails. */
    @Synchronized
    fun urlFor(jpeg: ByteArray): String? {
        val key = sha256(jpeg)
        cache.getProperty(key)?.split('|')?.let { (url, expires) ->
            if ((expires.toLongOrNull() ?: 0L) - now() > REUSE_MARGIN_MS) return url
        }
        val url = runCatching { upload(jpeg) }.getOrNull()?.trim()
            ?.takeIf { it.startsWith("https://") && it.length <= 256 && it.none(Char::isWhitespace) }
        if (url == null) {
            DiscordLog.write("Cover upload failed")
            return null
        }
        DiscordLog.write("Uploaded cover: $url")
        cache.setProperty(key, "$url|${now() + LIFETIME_MS}")
        // Forget expired links so the file doesn't grow forever.
        cache.stringPropertyNames().filter { (cache.getProperty(it).substringAfter('|').toLongOrNull() ?: 0L) < now() }.forEach(cache::remove)
        runCatching { cacheFile.outputStream().use { cache.store(it, "Discord cover links (Litterbox, 72 h)") } }
        return url
    }

    companion object {
        private const val LIFETIME_MS = 72 * 60 * 60 * 1000L
        /** Upload again when less than this is left, so the link outlives the song. */
        private const val REUSE_MARGIN_MS = 6 * 60 * 60 * 1000L

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

        /** Litterbox API: multipart POST with reqtype=fileupload, time=72h, fileToUpload; replies with the URL. */
        private fun uploadToLitterbox(jpeg: ByteArray): String? {
            val boundary = "----vanity" + UUID.randomUUID().toString().replace("-", "")
            fun field(name: String, value: String) =
                "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
            val body = field("reqtype", "fileupload") + field("time", "72h") +
                ("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"cover.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n").toByteArray() + jpeg + "\r\n--$boundary--\r\n".toByteArray()
            val request = HttpRequest.newBuilder(URI("https://litterbox.catbox.moe/resources/internals/api.php"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("User-Agent", "VanityPlayer")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.body().takeIf { response.statusCode() == 200 }
        }
    }
}
