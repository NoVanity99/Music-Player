package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.desktop.audio.Decoder
import com.novanity.musicplayer.desktop.audio.DesktopEqualizer
import com.novanity.musicplayer.desktop.audio.GaplessReader
import com.novanity.musicplayer.desktop.audio.OUT_CHANNELS
import com.novanity.musicplayer.desktop.audio.OUT_RATE
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeamlessAudioTest {
    private val dir = Files.createTempDirectory("seamless").toFile()

    /** A 48 kHz stereo WAV: [leadMs] of silence, [toneMs] of a tone, [tailMs] of silence. */
    private fun wav(name: String, leadMs: Int, toneMs: Int, tailMs: Int): File {
        fun frames(ms: Int) = OUT_RATE * ms / 1000
        val total = frames(leadMs) + frames(toneMs) + frames(tailMs)
        val data = ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until total) {
            val inTone = i >= frames(leadMs) && i < frames(leadMs) + frames(toneMs)
            // Never crosses zero for long: a sample-exact way to tell tone from silence.
            val s = if (inTone) (8000 + 4000 * sin(2 * PI * 440 * i / OUT_RATE)).toInt().toShort() else 0
            data.putShort(s); data.putShort(s)
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(OUT_RATE).putInt(OUT_RATE * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(data.capacity())
        return File(dir, name).also { it.writeBytes(header.array() + data.array()) }
    }

    private fun readAll(file: File, reader: GaplessReader, trim: Boolean, trimStart: Boolean): ShortArray {
        val out = ArrayList<Short>()
        Decoder.open(file.path, 1f).use { d ->
            reader.reset(trimStart)
            val buf = ShortArray(4096)
            while (!reader.finished) {
                val n = reader.fill(d, buf, 0, trim)
                for (i in 0 until n) out += buf[i]
            }
        }
        return out.toShortArray()
    }

    @Test
    fun paddingAtTheJoinIsRemovedButMusicIsKept() {
        val reader = GaplessReader(4096)
        val first = wav("a.wav", leadMs = 0, toneMs = 500, tailMs = 30) // 30 ms of encoder-style padding
        val second = wav("b.wav", leadMs = 25, toneMs = 500, tailMs = 0)
        val a = readAll(first, reader, trim = true, trimStart = false)
        val b = readAll(second, reader, trim = true, trimStart = true)
        val tone = OUT_RATE / 2 * OUT_CHANNELS
        assertEquals(tone, a.size, "trailing padding removed, every tone sample kept")
        assertEquals(tone, b.size, "leading padding removed, every tone sample kept")
        assertTrue(abs(a.last().toInt()) > 1000 && abs(b.first().toInt()) > 1000, "the join is tone to tone")

        // Untouched when the feature is off.
        assertEquals(tone + OUT_RATE * 30 / 1000 * OUT_CHANNELS, readAll(first, reader, trim = false, trimStart = false).size)

        // A real pause (longer than padding) at the end of a song is music: it stays.
        val pause = wav("c.wav", leadMs = 0, toneMs = 300, tailMs = 400)
        assertTrue(readAll(pause, reader, trim = true, trimStart = false).size >= OUT_RATE * 640 / 1000 * OUT_CHANNELS, "at most the padding allowance is removed")
    }

    private fun rms(hz: Double, levels: List<Int>?): Double {
        val n = OUT_RATE * OUT_CHANNELS // one second
        val buf = ShortArray(n) { i -> (6000 * sin(2 * PI * hz * (i / OUT_CHANNELS) / OUT_RATE)).toInt().toShort() }
        DesktopEqualizer.Chain().process(buf, n, levels)
        return sqrt(buf.drop(n / 2).sumOf { it.toDouble() * it } / (n / 2))
    }

    @Test
    fun equalizerBoostsTheBandsItShould() {
        assertNull(DesktopEqualizer.levels(enabled = false, preset = 1, bands = emptyList()))
        assertNull(DesktopEqualizer.levels(enabled = true, preset = 0, bands = emptyList()), "flat does nothing")
        val bass = assertNotNull(DesktopEqualizer.levels(enabled = true, preset = DesktopEqualizer.info.presets.indexOf("Bass boost"), bands = emptyList()))
        val lowGain = rms(50.0, bass) / rms(50.0, null)
        val highGain = rms(5000.0, bass) / rms(5000.0, null)
        assertTrue(lowGain > highGain * 1.5, "bass is raised against treble: $lowGain vs $highGain")
        // Custom levels: cutting the top band makes 12 kHz quieter and leaves 200 Hz about where it was.
        val custom = assertNotNull(DesktopEqualizer.levels(true, -1, listOf(0, 0, 0, 0, -1200)))
        assertTrue(rms(12000.0, custom) < rms(12000.0, null) * 0.5)
        assertTrue(abs(rms(200.0, custom) / rms(200.0, null) - 1.0) < 0.1)
        assertEquals(5, DesktopEqualizer.info.bands.size)
    }
}
