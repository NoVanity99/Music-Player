package com.novanity.musicplayer.desktop.audio

import kotlin.math.abs

/**
 * Reads a decoder for a seamless join: with [trim] on, the near-silent padding at the very start
 * and very end of a file (what encoders add, a few hundredths of a second) is left out, so the
 * last note of one song runs straight into the first note of the next. Silence inside a song, and
 * anything longer than [MAX_PAD] at the edges, is music and is kept. With [trim] off it passes
 * the audio through untouched.
 */
class GaplessReader(size: Int) {
    private val tmp = ShortArray(size)
    private val q = ShortArray(MAX_PAD + size * 3)
    private var qn = 0
    private var eof = false
    private var skipLead = false
    private var skipped = 0

    /** Start over for a new decoder; [trimStart] drops its leading padding. */
    fun reset(trimStart: Boolean) {
        qn = 0
        eof = false
        skipLead = trimStart
        skipped = 0
    }

    private fun silent(s: Short) = abs(s.toInt()) <= THRESHOLD

    /** True once the file has ended and everything has been handed out. */
    val finished: Boolean get() = eof && qn == 0

    /** Fills [dst] from [offset]; returns how many samples were written (check [finished] when it's short). */
    fun fill(d: Decoder, dst: ShortArray, offset: Int, trim: Boolean): Int {
        val want = dst.size - offset
        while (true) {
            // Looking ahead (to tell padding from music) is only needed while trimming.
            if (!eof && qn < want + (if (trim) MAX_PAD else 0)) {
                val got = d.fill(tmp)
                if (got == 0) eof = true else {
                    var from = 0
                    if (skipLead) {
                        while (from < got && skipped < MAX_PAD && silent(tmp[from])) { from++; skipped++ }
                        from -= from % OUT_CHANNELS
                        if (from < got || skipped >= MAX_PAD) skipLead = false
                    }
                    tmp.copyInto(q, qn, from, got)
                    qn += got - from
                }
            }
            var hold = 0
            if (trim) {
                // The run of silence at the end of what we have: padding if the file ends here.
                while (hold < qn && hold <= MAX_PAD && silent(q[qn - 1 - hold])) hold++
                if (hold > MAX_PAD) hold = 0 else hold += (qn - hold) % OUT_CHANNELS
                if (eof) { qn -= hold.coerceAtMost(qn); hold = 0 }
            }
            val emit = minOf(want, qn - hold).coerceAtLeast(0)
            if (emit > 0 || eof) {
                q.copyInto(dst, offset, 0, emit)
                q.copyInto(q, 0, emit, qn)
                qn -= emit
                return emit
            }
        }
    }

    companion object {
        /** About -66 dB: inaudible. */
        private const val THRESHOLD = 16
        /** The most padding to remove from either end: 60 ms. */
        const val MAX_PAD = OUT_RATE * OUT_CHANNELS * 60 / 1000
    }
}
