package com.novanity.musicplayer.updatecore

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** Network, hashing and signature helpers for the updater. Blocking: call from a background thread. */
object UpdateCore {
    private const val USER_AGENT = "MusicPlayer-Updater"

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        // Follow redirects ourselves (GitHub release downloads redirect to its CDN), HTTPS only.
        repeat(6) {
            require(current.protocol == "https") { "Refusing non-HTTPS update URL" }
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/octet-stream")
            when (val code = c.responseCode) {
                in 200..299 -> return c
                301, 302, 303, 307, 308 -> {
                    current = URL(current, c.getHeaderField("Location") ?: throw IOException("Redirect without location"))
                    c.disconnect()
                }
                404 -> throw IOException("No release published yet")
                else -> throw IOException("HTTP $code")
            }
        }
        throw IOException("Too many redirects")
    }

    fun fetch(url: String, maxBytes: Int = 1_000_000): ByteArray {
        val c = open(url)
        try {
            return c.inputStream.use { input ->
                val bytes = input.readNBytesCompat(maxBytes + 1)
                if (bytes.size > maxBytes) throw IOException("Response too large")
                bytes
            }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [url] to [dest] (via a temp file), reporting progress 0..1. */
    fun download(url: String, dest: File, expectedSize: Long, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val c = open(url)
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var read = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (expectedSize > 0 && read > expectedSize * 2) throw IOException("Download larger than expected")
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (expectedSize > 0 && read != expectedSize) throw IOException("Incomplete download ($read of $expectedSize bytes)")
        } finally {
            c.disconnect()
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Couldn't save the update")
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verifies a base64 Ed25519 signature of [data] against a raw 32-byte public key (base64). */
    fun verifyEd25519(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean = runCatching {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val key = EdDSAPublicKey(EdDSAPublicKeySpec(Base64.getDecoder().decode(publicKeyBase64), spec))
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initVerify(key)
        engine.update(data)
        engine.verify(Base64.getDecoder().decode(signatureBase64.trim()))
    }.getOrDefault(false)

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
