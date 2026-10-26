package com.novanity.musicplayer.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where PCM goes: the default sound device, or a silent clock if there isn't one. */
interface AudioOutput {
    /** Blocks until [bytes] are queued. */
    fun write(bytes: ByteArray, length: Int)
    fun pause()
    fun resume()
    fun flush()
    /** Audio queued but not yet heard, in ms. */
    fun latencyMs(): Long
    fun close()

    companion object {
        val FORMAT = AudioFormat(OUT_RATE.toFloat(), 16, OUT_CHANNELS, true, false)

        fun open(): AudioOutput = runCatching {
            val line = AudioSystem.getSourceDataLine(FORMAT)
            // ~200 ms buffer: responsive seeks/pauses without underruns.
            line.open(FORMAT, OUT_RATE / 5 * FORMAT.frameSize)
            line.start()
            LineOutput(line)
        }.getOrElse { SilentOutput() }
    }
}

private class LineOutput(private val line: SourceDataLine) : AudioOutput {
    private var paused = false
    override fun write(bytes: ByteArray, length: Int) {
        var off = 0
        while (off < length) off += line.write(bytes, off, length - off)
    }
    override fun pause() { if (!paused) { line.stop(); paused = true } }
    override fun resume() { if (paused) { line.start(); paused = false } }
    override fun flush() = line.flush()
    override fun latencyMs(): Long = (line.bufferSize - line.available()).toLong() / AudioOutput.FORMAT.frameSize * 1000 / OUT_RATE
    override fun close() { runCatching { line.drain(); line.close() } }
}

/** No sound device (e.g. a server): consume audio in real time so playback timing still works. */
private class SilentOutput : AudioOutput {
    private var nextDeadline = 0L
    override fun write(bytes: ByteArray, length: Int) {
        val now = System.nanoTime()
        if (nextDeadline < now) nextDeadline = now
        nextDeadline += length.toLong() / AudioOutput.FORMAT.frameSize * 1_000_000_000L / OUT_RATE
        val sleepNs = nextDeadline - System.nanoTime() - 20_000_000L
        if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
    }
    override fun pause() { nextDeadline = 0 }
    override fun resume() {}
    override fun flush() { nextDeadline = 0 }
    override fun latencyMs() = 0L
    override fun close() {}
}
