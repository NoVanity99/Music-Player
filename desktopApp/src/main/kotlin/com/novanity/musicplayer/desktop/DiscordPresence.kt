package com.novanity.musicplayer.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What to show on the user's Discord profile. Times are unix milliseconds. */
data class PresenceActivity(
    val title: String,
    val artist: String,
    val album: String,
    val startMs: Long,
    val endMs: Long?,
    /** Public URL of the album cover; the app logo is shown when null. */
    val coverUrl: String? = null,
)

/**
 * Discord Rich Presence over Discord's local IPC (a named pipe on Windows, a Unix socket
 * elsewhere). Everything runs on one background thread; when Discord isn't running it quietly
 * retries every so often and never reports an error.
 */
class DiscordPresence(
    private val clientId: String,
    private val socketPaths: () -> List<String> = IpcPipe::candidates,
) {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var desired: PresenceActivity? = null
    private var closed = false

    private var pipe: IpcPipe? = null
    private var sent: PresenceActivity? = null
    private var lastSendAt = 0L
    private var lastConnectAttempt = 0L
    /** Older Discord builds may reject status_display_type; we drop it after the first error. */
    private var useStatusDisplay = true

    private val thread = Thread(::run, "discord-presence").apply { isDaemon = true; start() }

    /** Show [activity], or clear the presence with null. Cheap; only the latest value is sent. */
    fun update(activity: PresenceActivity?) = lock.withLock {
        if (activity != desired) {
            desired = activity
            changed.signal()
        }
    }

    /** Clears the presence and disconnects. Waits at most a second so quitting stays snappy. */
    fun close() {
        lock.withLock {
            closed = true
            changed.signal()
        }
        thread.join(1_000)
    }

    private fun run() {
        while (true) {
            val target = lock.withLock {
                // Wake on a change, or periodically to retry a missing Discord.
                if (!closed && desired == sent && (pipe != null || desired == null)) changed.await(15, TimeUnit.SECONDS)
                if (closed) null.also { shutdown(); return } else desired
            }
            try {
                step(target)
            } catch (_: Exception) {
                disconnect() // Discord quit or restarted: reconnect on the next round.
            }
        }
    }

    private fun step(target: PresenceActivity?) {
        if (target == sent && pipe != null) return
        if (pipe == null) {
            if (target == null) return // nothing to show, and Discord drops our status when we disconnect
            val sinceLast = System.currentTimeMillis() - lastConnectAttempt
            if (sinceLast < RETRY_MS) return pause(RETRY_MS - sinceLast)
            lastConnectAttempt = System.currentTimeMillis()
            pipe = connect() ?: return
        }
        // Discord allows about 5 updates per 20 s; space them out and send only the latest.
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastSendAt)
        if (wait > 0) return pause(wait)
        sendActivity(pipe!!, target)
        sent = target
        lastSendAt = System.currentTimeMillis()
    }

    /** Sleeps, but wakes early for a new activity or close(). */
    private fun pause(ms: Long) = lock.withLock { if (!closed) changed.await(ms, TimeUnit.MILLISECONDS) }.let { }

    /** Runs on the worker thread, so the pipe is never used from two threads at once. */
    private fun shutdown() {
        pipe?.let { p -> runCatching { if (sent != null) sendActivity(p, null) } }
        disconnect()
    }

    private fun connect(): IpcPipe? {
        for (candidate in socketPaths()) {
            val p = runCatching { IpcPipe.open(candidate) }.getOrNull() ?: continue
            try {
                p.send(OP_HANDSHAKE, buildJsonObject { put("v", 1); put("client_id", clientId) })
                val (op, reply) = p.receive()
                if (op == OP_FRAME && reply["evt"]?.jsonPrimitive?.content == "READY") return p
            } catch (_: Exception) {
            }
            p.close()
        }
        return null
    }

    private fun sendActivity(p: IpcPipe, activity: PresenceActivity?) {
        val nonce = UUID.randomUUID().toString()
        p.send(OP_FRAME, buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put("nonce", nonce)
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                if (activity != null) put("activity", activityJson(activity))
            }
        })
        while (true) {
            val (op, reply) = p.receive()
            when (op) {
                OP_PING -> p.send(OP_PONG, reply)
                OP_CLOSE -> throw IOException("Discord closed the connection")
                OP_FRAME -> if (reply["nonce"]?.jsonPrimitive?.content == nonce) {
                    if (reply["evt"]?.jsonPrimitive?.content == "ERROR" && useStatusDisplay && activity != null) {
                        useStatusDisplay = false
                        sendActivity(p, activity)
                    }
                    return
                }
            }
        }
    }

    private fun activityJson(a: PresenceActivity): JsonObject = buildJsonObject {
        put("type", 2) // "Listening to"
        if (useStatusDisplay) put("status_display_type", 2) // member list shows the song, not the app name
        put("details", field(a.title))
        put("state", field(a.artist))
        putJsonObject("timestamps") {
            put("start", a.startMs)
            a.endMs?.let { put("end", it) }
        }
        putJsonObject("assets") {
            val cover = a.coverUrl?.takeIf { it.startsWith("https://") && it.length <= 256 } // Discord's limits
            if (cover != null) {
                // Album cover from the web, with the app logo as the small badge.
                put("large_image", cover)
                put("small_image", "logo")
                put("small_text", "Vanity Player")
            } else {
                put("large_image", "logo") // uploaded under Rich Presence → Art Assets
            }
            if (a.album.isNotBlank()) put("large_text", field(a.album))
        }
    }

    private fun disconnect() {
        pipe?.close()
        pipe = null
        sent = null // Discord clears the status of a client that goes away
    }

    companion object {
        private const val OP_HANDSHAKE = 0
        private const val OP_FRAME = 1
        private const val OP_CLOSE = 2
        private const val OP_PING = 3
        private const val OP_PONG = 4
        private const val RETRY_MS = 15_000L
        private const val MIN_GAP_MS = 4_000L

        /** Discord wants 2–128 characters per text field. */
        internal fun field(text: String): String {
            val t = text.trim().ifEmpty { "Unknown" }
            return if (t.length < 2) t + "\u200B" else if (t.length > 128) t.take(127) + "…" else t
        }
    }
}

/** One Discord IPC connection. Frames are: opcode (int32 LE), length (int32 LE), JSON. */
internal abstract class IpcPipe {
    protected abstract fun write(bytes: ByteArray)
    protected abstract fun readFully(bytes: ByteArray)
    abstract fun close()

    fun send(op: Int, payload: JsonObject) {
        val body = payload.toString().encodeToByteArray()
        val frame = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op).putInt(body.size).put(body)
        write(frame.array())
    }

    fun receive(): Pair<Int, JsonObject> {
        val header = ByteArray(8).also(::readFully)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val op = buf.int
        val length = buf.int
        if (length !in 0..MAX_FRAME) throw IOException("Bad frame length $length")
        val body = ByteArray(length).also(::readFully)
        return op to Json.parseToJsonElement(body.decodeToString()).jsonObject
    }

    private class WindowsPipe(path: String) : IpcPipe() {
        private val file = RandomAccessFile(path, "rw")
        override fun write(bytes: ByteArray) = file.write(bytes)
        override fun readFully(bytes: ByteArray) = file.readFully(bytes)
        override fun close() { runCatching { file.close() } }
    }

    private class UnixPipe(path: String) : IpcPipe() {
        private val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path)) }
        override fun write(bytes: ByteArray) {
            val b = ByteBuffer.wrap(bytes)
            while (b.hasRemaining()) channel.write(b)
        }
        override fun readFully(bytes: ByteArray) {
            val b = ByteBuffer.wrap(bytes)
            while (b.hasRemaining()) if (channel.read(b) < 0) throw IOException("Discord closed the connection")
        }
        override fun close() { runCatching { channel.close() } }
    }

    companion object {
        private const val MAX_FRAME = 1 shl 20
        private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        fun candidates(): List<String> =
            if (isWindows) {
                (0..9).map { """\\.\pipe\discord-ipc-$it""" }
            } else {
                val base = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp")
                // Plain installs plus the Flatpak and Snap sandboxes.
                val dirs = base.flatMap { listOf(it, "$it/app/com.discordapp.Discord", "$it/snap.discord") }.distinct()
                dirs.flatMap { dir -> (0..9).map { "$dir/discord-ipc-$it" } }.filter { File(it).exists() }
            }

        fun open(path: String): IpcPipe = if (isWindows) WindowsPipe(path) else UnixPipe(path)
    }
}
