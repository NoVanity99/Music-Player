package com.novanity.musicplayer.desktop.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns the audio being played into [bands] bar heights (0..1) for the visualizer: a Hann-windowed
 * FFT of the latest samples, grouped into log-spaced bands from 40 Hz to 16 kHz, with fast rise and
 * slow fall so the bars move like a real spectrum meter. Fed from the engine thread.
 */
class SpectrumAnalyzer(private val bands: Int = 48, private val sampleRate: Int = OUT_RATE) {
    private val size = 2048
    private val ring = DoubleArray(size)
    private var writePos = 0
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val levels = FloatArray(bands)
    private var lastRun = 0L

    /** First FFT bin of each band (plus the end of the last one). */
    private val edges = IntArray(bands + 1) { b ->
        val f = 40.0 * (16_000.0 / 40.0).pow(b.toDouble() / bands)
        (f * size / sampleRate).toInt().coerceIn(1, size / 2)
    }

    private val _levels = MutableStateFlow(FloatArray(bands))
    val spectrum: StateFlow<FloatArray> = _levels.asStateFlow()

    /** Adds interleaved stereo samples in -32768..32767, already scaled by [gain]. */
    fun feed(samples: ShortArray, count: Int, gain: Double) {
        val scale = if (gain > 0.001) 1.0 / (32768.0 * gain) else 0.0
        var i = 0
        while (i + 1 < count) {
            ring[writePos] = (samples[i] + samples[i + 1]) * 0.5 * scale
            writePos = (writePos + 1) % size
            i += 2
        }
        val now = System.nanoTime()
        if (now - lastRun >= 33_000_000L) { // about 30 frames a second
            lastRun = now
            analyse()
        }
    }

    private fun analyse() {
        for (k in 0 until size) {
            re[k] = ring[(writePos + k) % size] * window[k]
            im[k] = 0.0
        }
        fft(re, im)
        for (b in 0 until bands) {
            var peak = 0.0
            for (k in edges[b] until max(edges[b + 1], edges[b] + 1)) peak = max(peak, hypot(re[k], im[k]))
            // dB relative to a full-scale sine, mapped from -70..0 dB to 0..1.
            val db = 20 * log10(peak / (size / 4.0) + 1e-9)
            val target = ((db + 70) / 70).coerceIn(0.0, 1.0).toFloat()
            levels[b] = if (target > levels[b]) levels[b] + (target - levels[b]) * 0.6f else max(target, levels[b] - 0.04f)
        }
        _levels.value = levels.copyOf()
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

}
