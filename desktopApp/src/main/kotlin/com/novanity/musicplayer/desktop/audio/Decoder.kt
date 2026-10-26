package com.novanity.musicplayer.desktop.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.ShortBuffer

const val OUT_RATE = 48_000
const val OUT_CHANNELS = 2

/**
 * Decodes one file to 48 kHz interleaved stereo 16-bit PCM (FFmpeg resamples/downmixes), with an
 * optional atempo filter for playback speed. Not thread-safe: used only by the engine thread.
 */
class Decoder private constructor(private val grabber: FFmpegFrameGrabber, speed: Float) : AutoCloseable {
    /** Track length from the container (0 if unknown). */
    val durationMs: Long = (grabber.lengthInTime / 1000).coerceAtLeast(0)

    /** Source-time position of the next sample [fill] will return. */
    var positionMs: Long = 0
        private set

    var speed: Float = speed
        private set

    private var filter: FFmpegFrameFilter? = null
    private var leftover: ShortArray? = null
    private var leftoverPos = 0
    private var eof = false

    init {
        configureFilter()
    }

    private fun configureFilter() {
        filter?.runCatching { stop(); release() }
        filter = null
        if (speed != 1f) {
            filter = FFmpegFrameFilter("atempo=${"%.3f".format(java.util.Locale.ROOT, speed)}", OUT_CHANNELS).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                sampleRate = OUT_RATE
                start()
            }
        }
    }

    fun setSpeed(value: Float) {
        if (value == speed) return
        speed = value
        leftover = null
        configureFilter()
    }

    fun seek(ms: Long) {
        grabber.setTimestamp(ms.coerceAtLeast(0) * 1000L)
        positionMs = ms.coerceAtLeast(0)
        leftover = null
        eof = false
        configureFilter()
    }

    private fun Frame.shorts(): ShortArray? {
        val buf = samples?.firstOrNull() as? ShortBuffer ?: return null
        val dup = buf.duplicate()
        return ShortArray(dup.remaining()).also { dup.get(it) }
    }

    /** Next decoded (and tempo-filtered) block, or null at the end of the file. */
    private fun nextBlock(): ShortArray? {
        while (!eof) {
            val frame = grabber.grabSamples()
            if (frame == null) {
                eof = true
                break
            }
            val raw = frame.shorts() ?: continue
            // Source-time position at the end of this frame.
            val frameStartMs = frame.timestamp / 1000
            positionMs = frameStartMs + raw.size / OUT_CHANNELS * 1000L / OUT_RATE
            val f = filter ?: return raw
            f.push(frame)
            val out = generateSequence { f.pullSamples() }.mapNotNull { it.shorts() }.toList()
            if (out.isNotEmpty()) return concat(out)
        }
        // Drain whatever the tempo filter still holds.
        val f = filter ?: return null
        runCatching { f.push(null) }
        val out = generateSequence { runCatching { f.pullSamples() }.getOrNull() }.mapNotNull { it.shorts() }.toList()
        return if (out.isEmpty()) null else concat(out).also { filter = null }
    }

    private fun concat(parts: List<ShortArray>): ShortArray {
        if (parts.size == 1) return parts[0]
        val out = ShortArray(parts.sumOf { it.size })
        var p = 0
        parts.forEach { it.copyInto(out, p); p += it.size }
        return out
    }

    /** Fills [dst] with up to dst.size samples; returns how many were written (0 = finished). */
    fun fill(dst: ShortArray): Int {
        var written = 0
        while (written < dst.size) {
            var block = leftover
            if (block == null || leftoverPos >= block.size) {
                block = nextBlock() ?: break
                leftover = block
                leftoverPos = 0
            }
            val n = minOf(dst.size - written, block.size - leftoverPos)
            block.copyInto(dst, written, leftoverPos, leftoverPos + n)
            leftoverPos += n
            written += n
        }
        return written
    }

    override fun close() {
        filter?.runCatching { stop(); release() }
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }

    companion object {
        fun open(path: String, speed: Float): Decoder {
            val grabber = FFmpegFrameGrabber(path).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                sampleRate = OUT_RATE
                audioChannels = OUT_CHANNELS
            }
            try {
                grabber.start()
                if (grabber.audioStream < 0) error("No audio stream")
            } catch (t: Throwable) {
                runCatching { grabber.release() }
                throw t
            }
            return Decoder(grabber, speed)
        }
    }
}
