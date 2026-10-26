package com.novanity.musicplayer.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talks to a fake Discord client over a Unix socket, speaking the real IPC framing. */
class DiscordPresenceTest {

    private class FakeDiscord(val channel: SocketChannel) {
        fun read(): Pair<Int, JsonObject> {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            while (header.hasRemaining()) check(channel.read(header) >= 0)
            header.flip()
            val op = header.int
            val body = ByteBuffer.allocate(header.int)
            while (body.hasRemaining()) check(channel.read(body) >= 0)
            return op to Json.parseToJsonElement(String(body.array())).jsonObject
        }

        fun write(op: Int, json: JsonObject) {
            val bytes = json.toString().toByteArray()
            val b = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(bytes.size).put(bytes)
            b.flip()
            while (b.hasRemaining()) channel.write(b)
        }

        /** Reads one SET_ACTIVITY and acknowledges it (optionally as an error). */
        fun setActivity(error: Boolean = false): JsonObject {
            val (op, msg) = read()
            assertEquals(1, op)
            assertEquals("SET_ACTIVITY", msg["cmd"]!!.jsonPrimitive.content)
            write(1, buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("nonce", msg["nonce"]!!.jsonPrimitive.content)
                if (error) put("evt", "ERROR")
            })
            return msg["args"]!!.jsonObject
        }
    }

    @Test
    fun handshakeSetAndClear() {
        val dir = Files.createTempDirectory("ipc")
        val path = dir.resolve("discord-ipc-0")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
        val logLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val presence = DiscordPresence("1234", socketPaths = { listOf(dir.resolve("missing").toString(), path.toString()) }, log = { logLines += it })
        try {
            presence.update(PresenceActivity("A", "Some Artist", "Album", startMs = 1_000, endMs = 181_000))
            val discord = FakeDiscord(server.accept())

            val (op, hello) = discord.read()
            assertEquals(0, op)
            assertEquals("1234", hello["client_id"]!!.jsonPrimitive.content)
            discord.write(1, buildJsonObject {
                put("cmd", "DISPATCH"); put("evt", "READY")
                put("data", buildJsonObject { put("user", buildJsonObject { put("username", "jordan") }) })
            })

            // First try includes status_display_type; pretend this Discord is too old for it.
            val first = discord.setActivity(error = true)["activity"]!!.jsonObject
            assertEquals(2, first["status_display_type"]!!.jsonPrimitive.int)
            val activity = discord.setActivity()["activity"]!!.jsonObject
            assertNull(activity["status_display_type"])
            assertEquals(2, activity["type"]!!.jsonPrimitive.int)
            assertEquals("A​", activity["details"]!!.jsonPrimitive.content) // padded to Discord's 2-char minimum
            assertEquals("Some Artist", activity["state"]!!.jsonPrimitive.content)
            assertEquals(181_000, activity["timestamps"]!!.jsonObject["end"]!!.jsonPrimitive.long)
            assertEquals("logo", activity["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)
            waitFor { presence.status.value == "Showing on Discord as jordan" }
            assertTrue(logLines.any { "rejected the activity" in it }, "the error is logged")

            // A song with a cover found online shows it, with the logo as a badge.
            presence.update(PresenceActivity("Song", "Artist", "Album", 0, null, coverUrl = "https://img.example/cover.jpg"))
            val assets = discord.setActivity()["activity"]!!.jsonObject["assets"]!!.jsonObject
            assertEquals("https://img.example/cover.jpg", assets["large_image"]!!.jsonPrimitive.content)
            assertEquals("logo", assets["small_image"]!!.jsonPrimitive.content)

            // Pausing clears it (after the rate-limit gap).
            presence.update(null)
            val cleared = discord.setActivity()
            assertFalse("activity" in cleared)
            assertTrue("pid" in cleared)
        } finally {
            presence.close()
            server.close()
            Files.deleteIfExists(path)
        }
    }

    /** Some Discord builds hang up on fields they don't like: reconnect and send a simpler activity. */
    @Test
    fun hangUpFallsBackToSimplerActivity() {
        val dir = Files.createTempDirectory("ipc")
        val path = dir.resolve("discord-ipc-0")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
        val presence = DiscordPresence("1234", socketPaths = { listOf(path.toString()) }, log = {})
        fun handshake(): FakeDiscord = FakeDiscord(server.accept()).also {
            assertEquals(0, it.read().first)
            it.write(1, buildJsonObject { put("cmd", "DISPATCH"); put("evt", "READY") })
        }
        try {
            presence.update(PresenceActivity("Song", "Artist", "Album", 0, null, coverUrl = "https://img.example/c.jpg"))
            for (expectStatusDisplay in listOf(true, false)) {
                val discord = handshake()
                val (op, msg) = discord.read()
                assertEquals(1, op)
                val activity = msg["args"]!!.jsonObject["activity"]!!.jsonObject
                assertEquals(expectStatusDisplay, "status_display_type" in activity)
                discord.write(2, buildJsonObject { put("code", 4000); put("message", "bad activity") })
                discord.channel.close()
            }
            // Third connection: no status_display_type and the logo instead of the cover URL.
            val discord = handshake()
            val last = discord.setActivity()["activity"]!!.jsonObject
            assertEquals("logo", last["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)
            waitFor { presence.status.value.startsWith("Showing on Discord") }
        } finally {
            presence.close()
            server.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun noDiscordIsSilent() {
        val presence = DiscordPresence("1234", socketPaths = { emptyList() }, log = {})
        presence.update(PresenceActivity("Song", "Artist", "", 0, null))
        waitFor { presence.status.value.startsWith("Discord isn't running") }
        presence.close() // no exceptions, returns promptly
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun fieldLimits() {
        assertEquals("Unknown", DiscordPresence.field("  "))
        assertEquals(128, DiscordPresence.field("x".repeat(300)).length)
    }
}
