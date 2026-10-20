package com.lumora.photoeditor.engine

import java.nio.ByteBuffer

/**
 * Vectorscope data: the chroma (hue/saturation) distribution of the live
 * preview render (Lightroom's vectorscope). Unlike the luma [Waveform],
 * the vectorscope says nothing about position or brightness — neutral gray
 * collapses to the center dot, skin tones ride along the skin-tone line,
 * and over-saturated patches drift past the 75% graticule ring.
 *
 * Chroma coordinates: u = (R − B) / 255 (horizontal, red right / blue left),
 * v = (2G − R − B) / 510 (vertical, green up / magenta down). So pure red
 * sits at (1, −0.5), green at (0, 1), blue at (−1, −0.5).
 *
 * The skin-tone reference angle is derived from warm skin (255, 170, 110):
 * u = 0.569, v = −0.049, i.e. a line through the center at about −5° in this
 * chroma space. It is marked on the scope so skin saturation can be judged.
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
data class Vectorscope(
    /** Per-cell pixel count, row-major over GRID × GRID. */
    val counts: IntArray,
    /** Per-cell accumulated R/G/B, for the average dot color; entry valid when count > 0. */
    val rSum: LongArray,
    val gSum: LongArray,
    val bSum: LongArray,
) {
    companion object {
        const val GRID = 48
        fun empty() = Vectorscope(IntArray(GRID * GRID), LongArray(GRID * GRID),
            LongArray(GRID * GRID), LongArray(GRID * GRID))
    }

    /** Average packed ARGB color of the given cell (opaque); 0 when empty. */
    fun colorAt(gx: Int, gy: Int): Int {
        val idx = gy * GRID + gx
        val n = counts[idx]
        if (n <= 0) return 0
        // Round-half-up integer averaging (not truncation): plain (sum/n)
        // biases the cell color up to ~1 level dark per channel.
        val r = ((rSum[idx] + n / 2) / n).toInt().coerceIn(0, 255)
        val g = ((gSum[idx] + n / 2) / n).toInt().coerceIn(0, 255)
        val b = ((bSum[idx] + n / 2) / n).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Reference skin chroma (see KDoc): marks the skin-tone line angle. */
val SKIN_TONE_U = (255 - 110) / 255f          // 0.569
val SKIN_TONE_V = (2 * 170 - 255 - 110) / 510f // −0.049

/** Cap on sampled pixels — bounds the cost to <= MAX_SCOPE_SAMPLES reads. */
private const val MAX_SCOPE_SAMPLES = 24576

/**
 * Builds a [Vectorscope] from raw RGBA framebuffer bytes.
 *
 * Uses absolute [ByteBuffer.get] reads only, so the buffer's position/limit
 * are untouched and the caller's buffer-reuse contract (see
 * EditorViewModel.onFrameBytes) is preserved. Strided sampling keeps this to
 * <= MAX_SCOPE_SAMPLES pixel reads — well under a millisecond, safe to run
 * on the GL callback thread next to [waveformFromRgba].
 *
 * Near-black pixels (max channel <= 16) are skipped: their hue is noise and
 * they would only smear the center.
 *
 * @param buf RGBA bytes, row-major, top row first (glReadPixels layout).
 */
fun vectorscopeFromRgba(buf: ByteBuffer, w: Int, h: Int): Vectorscope {
    if (w <= 0 || h <= 0) return Vectorscope.empty()
    val grid = Vectorscope.GRID
    val counts = IntArray(grid * grid)
    val rSum = LongArray(grid * grid)
    val gSum = LongArray(grid * grid)
    val bSum = LongArray(grid * grid)
    val total = w * h
    val stride = maxOf(1, total / MAX_SCOPE_SAMPLES)
    var i = 0
    while (i < total) {
        val o = i * 4
        val r = buf.get(o).toInt() and 0xFF
        val g = buf.get(o + 1).toInt() and 0xFF
        val b = buf.get(o + 2).toInt() and 0xFF
        if (maxOf(r, g, b) > 16) {
            val u = (r - b) / 255f
            val v = (2 * g - r - b) / 510f
            val gx = ((u + 1f) / 2f * grid).toInt().coerceIn(0, grid - 1)
            val gy = ((v + 1f) / 2f * grid).toInt().coerceIn(0, grid - 1)
            val idx = gy * grid + gx
            counts[idx]++
            rSum[idx] += r.toLong()
            gSum[idx] += g.toLong()
            bSum[idx] += b.toLong()
        }
        i += stride
    }
    return Vectorscope(counts, rSum, gSum, bSum)
}
