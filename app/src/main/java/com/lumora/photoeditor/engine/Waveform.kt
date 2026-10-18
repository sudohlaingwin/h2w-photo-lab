package com.lumora.photoeditor.engine

import java.nio.ByteBuffer

/**
 * Luma waveform monitor data: the per-column luminance envelope of the live
 * preview render (Lightroom's waveform scope). Unlike the global [Histogram],
 * which merges the whole frame into one distribution, the waveform keeps
 * horizontal position — a blown sky reads as a bright band at the top of its
 * columns, crushed shadows as a band pinned to the bottom.
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
data class Waveform(
    /** Per-column minimum luma, 0..255. */
    val min: IntArray,
    /** Per-column maximum luma, 0..255. */
    val max: IntArray,
    /** Per-column mean luma, 0..255. */
    val mean: IntArray,
) {
    companion object {
        const val COLS = 64
        fun empty() = Waveform(IntArray(COLS), IntArray(COLS), IntArray(COLS))
    }
}

/** Cap on rows sampled per column — bounds the cost to COLS * MAX_ROWS_PER_COL reads. */
private const val MAX_ROWS_PER_COL = 256

/**
 * Builds a [Waveform] from raw RGBA framebuffer bytes.
 *
 * Uses absolute [ByteBuffer.get] reads only, so the buffer's position/limit are
 * untouched and the caller's buffer-reuse contract (see EditorViewModel.onFrameBytes)
 * is preserved. Worst case is COLS * 256 ≈ 16k pixel reads — well under a
 * millisecond, safe to run on the GL callback thread.
 *
 * @param buf RGBA bytes, row-major, top row first (glReadPixels layout).
 */
fun waveformFromRgba(buf: ByteBuffer, w: Int, h: Int): Waveform {
    if (w <= 0 || h <= 0) return Waveform.empty()
    val cols = Waveform.COLS
    val mins = IntArray(cols) { 255 }
    val maxs = IntArray(cols)
    val means = IntArray(cols)
    val rowStep = maxOf(1, h / MAX_ROWS_PER_COL)
    for (c in 0 until cols) {
        val x = (c * w / cols).coerceIn(0, w - 1)
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            val o = (y * w + x) * 4
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
            if (lum < mins[c]) mins[c] = lum
            if (lum > maxs[c]) maxs[c] = lum
            sum += lum
            n++
            y += rowStep
        }
        means[c] = if (n > 0) (sum / n).toInt() else 0
    }
    return Waveform(mins, maxs, means)
}
