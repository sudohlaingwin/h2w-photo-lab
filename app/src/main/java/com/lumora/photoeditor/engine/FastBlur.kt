package com.lumora.photoeditor.engine

/**
 * Sliding-window separable box blurs — O(w*h) instead of O(w*h*radius).
 *
 * These exist because the Auto Retouch "Detecting…" phase was visibly slow on
 * the user's phone: [BlemishDetect] estimated its local background with a
 * naive 17-tap x 2-pass box blur (~34 float ops per pixel), and the skin-mask
 * feather used a naive (2r+1)^2 2D blur (up to 169 int ops per pixel).
 * The sliding window does ~4-6 ops per pixel with IDENTICAL replicate-edge
 * semantics: every out-of-bounds tap clamps to the edge pixel and the sum is
 * divided by the full window, exactly like the naive double loops did.
 *
 * Pure Kotlin, no Android imports — unit-testable on the JVM
 * (see hidden_files/fastblur-harness).
 */
internal object FastBlur {

    /**
     * Separable box blur over a FloatArray image, replicate edges.
     * Output matches the naive implementation (sum of clamped taps divided
     * by the full (2r+1) window) to within float rounding (~1e-6 relative).
     */
    fun boxBlurFloat(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        require(w >= 1 && h >= 1 && src.size >= w * h) { "bad dims w=$w h=$h" }
        val r = radius.coerceAtLeast(0)
        val win = 2 * r + 1
        val inv = 1f / win
        val tmp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        // Horizontal pass: extended row with replicated edges, then a
        // running window sum (drop leftmost, add new rightmost per step).
        var ext = FloatArray(w + 2 * r)
        for (y in 0 until h) {
            val row = y * w
            for (i in ext.indices) ext[i] = src[row + (i - r).coerceIn(0, w - 1)]
            var sum = 0f
            for (k in 0 until win) sum += ext[k]
            for (x in 0 until w) {
                tmp[row + x] = sum * inv
                if (x + 1 < w) sum += ext[x + win] - ext[x]
            }
        }
        // Vertical pass over the horizontally-blurred image.
        if (ext.size < h + 2 * r) ext = FloatArray(h + 2 * r)
        for (x in 0 until w) {
            for (i in 0 until h + 2 * r) ext[i] = tmp[(i - r).coerceIn(0, h - 1) * w + x]
            var sum = 0f
            for (k in 0 until win) sum += ext[k]
            for (y in 0 until h) {
                dst[y * w + x] = sum * inv
                if (y + 1 < h) sum += ext[y + win] - ext[y]
            }
        }
        return dst
    }

    /**
     * Separable box blur over unsigned bytes (ByteArray), replicate edges.
     * Integer arithmetic: the horizontal pass stores exact window SUMS, the
     * vertical pass sums those and divides once by win^2 — so the result is
     * bit-identical to the naive (2r+1)^2 nested loop with integer division.
     */
    fun boxBlurByte(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        require(w >= 1 && h >= 1 && src.size >= w * h) { "bad dims w=$w h=$h" }
        val r = radius.coerceAtLeast(0)
        val win = 2 * r + 1
        val win2 = win * win
        val tmp = IntArray(w * h) // exact horizontal window sums
        val dst = ByteArray(w * h)
        var ext = IntArray(w + 2 * r)
        for (y in 0 until h) {
            val row = y * w
            for (i in ext.indices) ext[i] = src[row + (i - r).coerceIn(0, w - 1)].toInt() and 0xFF
            var sum = 0
            for (k in 0 until win) sum += ext[k]
            for (x in 0 until w) {
                tmp[row + x] = sum
                if (x + 1 < w) sum += ext[x + win] - ext[x]
            }
        }
        if (ext.size < h + 2 * r) ext = IntArray(h + 2 * r)
        for (x in 0 until w) {
            for (i in 0 until h + 2 * r) ext[i] = tmp[(i - r).coerceIn(0, h - 1) * w + x]
            var sum = 0
            for (k in 0 until win) sum += ext[k]
            for (y in 0 until h) {
                dst[y * w + x] = (sum / win2).toByte()
                if (y + 1 < h) sum += ext[y + win] - ext[y]
            }
        }
        return dst
    }
}
