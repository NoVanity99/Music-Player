package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.desktop.audio.OUT_RATE
import com.novanity.musicplayer.desktop.audio.SpectrumAnalyzer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumAnalyzerTest {
    /** Feeds [seconds] of a stereo sine at [hz] in engine-sized chunks. */
    private fun feedTone(a: SpectrumAnalyzer, hz: Double, amplitude: Double, seconds: Double) {
        val chunk = ShortArray(2048)
        var t = 0
        repeat((seconds * OUT_RATE / 1024).toInt()) {
            for (i in 0 until 1024) {
                val s = (amplitude * 32767 * sin(2 * PI * hz * (t + i) / OUT_RATE)).toInt().toShort()
                chunk[2 * i] = s
                chunk[2 * i + 1] = s
            }
            t += 1024
            a.feed(chunk, chunk.size, 1.0)
            Thread.sleep(1) // let the 30 fps throttle run the analysis now and then
        }
    }

    @Test
    fun toneLightsUpItsBand() {
        val a = SpectrumAnalyzer(bands = 48)
        feedTone(a, 1000.0, 0.8, 1.5)
        val levels = a.spectrum.value
        val loudest = levels.indices.maxBy { levels[it] }
        // Band edges run from 40 Hz to 16 kHz on a log scale.
        val expected = (48 * kotlin.math.ln(1000.0 / 40.0) / kotlin.math.ln(16_000.0 / 40.0)).toInt()
        assertTrue(loudest in expected - 1..expected + 1, "loudest band $loudest, expected about $expected")
        assertTrue(levels[loudest] > 0.8f)
        assertTrue(levels[0] < 0.3f && levels[47] < 0.3f, "far-away bands stay low")
    }

    @Test
    fun silenceMeansNoBars() {
        val a = SpectrumAnalyzer()
        feedTone(a, 440.0, 0.0, 0.5)
        assertEquals(0f, a.spectrum.value.max())
    }

    @Test
    fun volumeDoesNotShrinkTheBars() {
        // The engine passes samples after the volume is applied; the analyzer undoes it.
        val loud = SpectrumAnalyzer().also { feedTone(it, 500.0, 0.8, 1.0) }.spectrum.value.max()
        val quietGain = 0.1
        val quiet = SpectrumAnalyzer().also { a ->
            val chunk = ShortArray(2048)
            var t = 0
            repeat((1.0 * OUT_RATE / 1024).toInt()) {
                for (i in 0 until 1024) {
                    val s = (0.8 * quietGain * 32767 * sin(2 * PI * 500.0 * (t + i) / OUT_RATE)).toInt().toShort()
                    chunk[2 * i] = s; chunk[2 * i + 1] = s
                }
                t += 1024
                a.feed(chunk, chunk.size, quietGain)
                Thread.sleep(1)
            }
        }.spectrum.value.max()
        assertTrue(kotlin.math.abs(loud - quiet) < 0.05f, "loud $loud vs quiet $quiet")
    }
}
