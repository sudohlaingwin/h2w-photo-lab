package com.lumora.photoeditor.engine

import java.nio.ByteBuffer

/**
 * RGB parade monitor data: the per-column min/max/mean envelope for each of
 * the R, G, B channels of the live preview render (Lightroom's RGB parade).
 * The luma [Waveform] collapses the three channels into one brightness trace,
 * which hides channel-clipping a white trace can't show — e.g. a channel
 * clipping to yellow reads as "bright" on the waveform but the parade shows
 * the red and green envelopes pinned at the top while blue stays low.
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
data class RgbParade(
    /** Channel index 0=R, 1=G, 2=B; each entry holds COLS per-column values. */
    val min: Array<IntArray>,
    val max: Array<IntArray>,
    val mean: Array<IntArray>,
) {
    companion object {
        const val COLS = 64
        fun empty() = RgbParade(
            Array(3) { IntArray(COLS) },
            Array(3) { IntArray(COLS) },
            Array(3) { IntArray(COLS) },
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RgbParade) return false
        return min.contentDeepEquals(other.min) &&
            max.contentDeepEquals(other.max) &&
            mean.contentDeepEquals(other.mean)
    }

    override fun hashCode(): Int {
        var r = min.contentDeepHashCode()
        r = 31 * r + max.contentDeepHashCode()
        r = 31 * r + mean.contentDeepHashCode()
        return r
    }
}

/** Cap on rows sampled per column — bounds the cost to 3 * COLS * 256 reads. */
private const val RGB_PARADE_MAX_ROWS_PER_COL = 256

/**
 * Builds an [RgbParade] from raw RGBA framebuffer bytes.
 *
 * Uses absolute [ByteBuffer.get] reads only, so the buffer's position/limit are
 * untouched and the caller's buffer-reuse contract (see EditorViewModel.onFrameBytes)
 * is preserved. Same sampling budget class as [waveformFromRgba]: 3 channels x
 * 64 columns x 256 rows ≈ 49k pixel reads, well under a millisecond, safe to
 * run on the GL callback thread next to the waveform and vectorscope.
 *
 * @param buf RGBA bytes, row-major, top row first (glReadPixels layout).
 */
fun rgbParadeFromRgba(buf: ByteBuffer, w: Int, h: Int): RgbParade {
    if (w <= 0 || h <= 0) return RgbParade.empty()
    val cols = RgbParade.COLS
    val mins = Array(3) { IntArray(cols) { 255 } }
    val maxs = Array(3) { IntArray(cols) }
    val means = Array(3) { IntArray(cols) }
    val sums = Array(3) { LongArray(cols) }
    val rowStep = maxOf(1, h / RGB_PARADE_MAX_ROWS_PER_COL)
    for (c in 0 until cols) {
        val x = (c * w / cols).coerceIn(0, w - 1)
        var n = 0
        var y = 0
        while (y < h) {
            val o = (y * w + x) * 4
            val ch = intArrayOf(
                buf.get(o).toInt() and 0xFF,
                buf.get(o + 1).toInt() and 0xFF,
                buf.get(o + 2).toInt() and 0xFF,
            )
            for (k in 0..2) {
                val v = ch[k]
                if (v < mins[k][c]) mins[k][c] = v
                if (v > maxs[k][c]) maxs[k][c] = v
                sums[k][c] += v
            }
            n++
            y += rowStep
        }
        // Round-half-up integer averaging (not truncation): plain (sum/n)
        // biases the column mean up to ~1 level dark per channel.
        for (k in 0..2) means[k][c] = if (n > 0) ((sums[k][c] + n / 2) / n).toInt() else 0
    }
    return RgbParade(mins, maxs, means)
}
