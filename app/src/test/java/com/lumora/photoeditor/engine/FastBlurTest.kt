package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever executed verification of [FastBlur] — the O(w*h) sliding-window
 * separable box blur behind Auto Retouch's "Detecting..." phase (local
 * background estimation for [BlemishDetect]) and the skin-mask feather.
 *
 * Pins the documented contract:
 * - float path matches the naive 2D (clamped-tap sum / win^2) double loop
 *   within float rounding (the running-window accumulation can drift a few
 *   ulps on wide frames, hence the 2e-4 bound; the tight 9x7 pin guards the
 *   ~1e-6 documented claim where the bound is provably tight);
 * - byte path is BIT-IDENTICAL to the naive (2r+1)^2 nested loop with a
 *   single integer division by win^2 (the horizontal pass stores exact sums,
 *   so separability introduces no rounding);
 * - replicate-edge semantics: out-of-bounds taps clamp to the edge pixel;
 * - radius 0 and negative radius are the identity;
 * - unsigned byte handling (values > 127 must not go negative);
 * - guard rails throw on bad dims instead of misbehaving.
 */
class FastBlurTest {

    // ---------- naive references, written straight from the KDoc contract ----------

    private fun naiveFloat(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val win2 = (2 * r + 1) * (2 * r + 1)
        val dst = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0.0
            for (dy in -r..r) for (dx in -r..r)
                sum += src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            dst[y * w + x] = (sum / win2).toFloat()
        }
        return dst
    }

    private fun naiveByte(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val win2 = (2 * r + 1) * (2 * r + 1)
        val dst = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in -r..r) for (dx in -r..r)
                sum += src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)].toInt() and 0xFF
            dst[y * w + x] = (sum / win2).toByte()
        }
        return dst
    }

    private fun noiseFloat(w: Int, h: Int, seed: Long): FloatArray {
        var s = seed
        return FloatArray(w * h) {
            s = s * 6364136223846793005L + 1442695040888963407L
            ((s ushr 33) % 1000).toFloat() / 1000f
        }
    }

    private fun noiseByte(w: Int, h: Int, seed: Long): ByteArray {
        var s = seed
        return ByteArray(w * h) {
            s = s * 6364136223846793005L + 1442695040888963407L
            ((s ushr 33) % 256).toByte()
        }
    }

    /** Max relative error of [actual] vs [expected]; denominator floored so near-zero pixels don't explode. */
    private fun maxRelErr(expected: FloatArray, actual: FloatArray): Float {
        assertEquals("length", expected.size, actual.size)
        var m = 0f
        for (i in expected.indices) {
            val denom = maxOf(abs(expected[i]), 1e-3f)
            m = maxOf(m, abs(expected[i] - actual[i]) / denom)
        }
        return m
    }

    private fun assertCloseNaive(src: FloatArray, w: Int, h: Int, r: Int, tol: Float, label: String) {
        val err = maxRelErr(naiveFloat(src, w, h, r), FastBlur.boxBlurFloat(src, w, h, r))
        assertTrue("$label: max relative error $err exceeds $tol", err <= tol)
    }

    // ---------- float path ----------

    @Test fun floatRadiusZeroIsIdentity() {
        // NOTE: not bit-exact by design — the sliding window still does its
        // add-then-subtract update per step (sum = prev + new - old), and each
        // step's rounding is ~ulp of the *neighbor* magnitude, so even a 1-wide
        // window drifts a few ulps in absolute terms (measured max 2.3e-8 on
        // the seed below). The contract is "identity within float rounding",
        // which the 1e-6 absolute bound pins with wide headroom; a real bug
        // (wrong window, off-by-one) would miss by >= 1e-3.
        val src = noiseFloat(17, 13, 7L)
        val out = FastBlur.boxBlurFloat(src, 17, 13, 0)
        var maxAbs = 0f
        for (i in src.indices) maxAbs = maxOf(maxAbs, abs(src[i] - out[i]))
        assertTrue("radius 0 drifted: $maxAbs", maxAbs <= 1e-6f)
    }

    @Test fun floatNegativeRadiusIsIdentity() {
        val src = noiseFloat(17, 13, 7L)
        val out = FastBlur.boxBlurFloat(src, 17, 13, -4)
        var maxAbs = 0f
        for (i in src.indices) maxAbs = maxOf(maxAbs, abs(src[i] - out[i]))
        assertTrue("negative radius drifted: $maxAbs", maxAbs <= 1e-6f)
    }

    @Test fun floatUniformStaysUniform() {
        val out = FastBlur.boxBlurFloat(FloatArray(23 * 19) { 0.37f }, 23, 19, 4)
        for (v in out) assertTrue("uniform drifted to $v", abs(v - 0.37f) <= 1e-5f)
    }

    @Test fun floatMatchesNaiveTightSmallFrame() {
        // 9x7: running-window error provably tiny here; pins the ~1e-6 documented claim.
        val src = noiseFloat(9, 7, 3L)
        for (r in 1..3) assertCloseNaive(src, 9, 7, r, 1e-5f, "r=$r")
    }

    @Test fun floatMatchesNaiveWideAndTall() {
        // Wide/tall frames exercise the ext-buffer realloc path (ext.size < h + 2r).
        val wide = noiseFloat(43, 5, 11L)
        assertCloseNaive(wide, 43, 5, 3, 2e-4f, "wide")
        val tall = noiseFloat(5, 43, 11L)
        assertCloseNaive(tall, 5, 43, 3, 2e-4f, "tall")
        val big = noiseFloat(31, 27, 7L)
        for (r in listOf(1, 2, 4)) assertCloseNaive(big, 31, 27, r, 2e-4f, "r=$r")
    }

    @Test fun floatMatchesNaiveOnGradient() {
        val w = 21; val h = 17
        val src = FloatArray(w * h) { i -> (i % w).toFloat() / w + (i / w).toFloat() / h }
        assertCloseNaive(src, w, h, 3, 2e-4f, "gradient")
    }

    @Test fun floatDegenerateRowsAndCols() {
        val row = noiseFloat(37, 1, 5L)
        assertCloseNaive(row, 37, 1, 2, 2e-4f, "single row")
        val col = noiseFloat(1, 37, 5L)
        assertCloseNaive(col, 1, 37, 2, 2e-4f, "single column")
        val one = floatArrayOf(0.5f)
        assertCloseNaive(one, 1, 1, 3, 0f, "1x1") // single pixel, any radius -> itself
    }

    @Test fun floatCornerReplicateEdge() {
        // Hot corner pixel, radius 1: the corner tap is hit 2x2=4 times
        // (dx,dy <= 0 clamp to the corner), so the corner reads 4*255/9.
        val w = 9; val h = 9
        val src = FloatArray(w * h); src[0] = 255f
        val out = FastBlur.boxBlurFloat(src, w, h, 1)
        fun at(x: Int, y: Int) = out[y * w + x]
        assertEquals(1020f / 9f, at(0, 0), 1e-2f)    // 4 taps hit the hot pixel
        assertEquals(510f / 9f, at(1, 0), 1e-2f)     // 2 taps hit the hot pixel
        assertEquals(510f / 9f, at(0, 1), 1e-2f)
        assertEquals(255f / 9f, at(1, 1), 1e-2f)     // 1 tap hits it
        assertEquals(0f, at(2, 0), 0f)
        assertEquals(0f, at(8, 8), 0f)
    }

    // ---------- byte path ----------

    @Test fun byteRadiusZeroIsIdentity() {
        val src = noiseByte(17, 13, 7L)
        assertArrayEquals(src, FastBlur.boxBlurByte(src, 17, 13, 0))
    }

    @Test fun byteNegativeRadiusIsIdentity() {
        val src = noiseByte(17, 13, 7L)
        assertArrayEquals(src, FastBlur.boxBlurByte(src, 17, 13, -9))
    }

    @Test fun byteUniformIsBitExactAndUnsigned() {
        // 200 (> 127) would go negative if the `and 0xFF` unsigned handling regressed.
        val w = 23; val h = 19
        val out = FastBlur.boxBlurByte(ByteArray(w * h) { 200.toByte() }, w, h, 5)
        for (b in out) assertEquals(200, b.toInt() and 0xFF)
    }

    @Test fun byteMatchesNaiveBitExact() {
        val img = noiseByte(29, 23, 11L)
        for (r in listOf(1, 3, 6))
            assertArrayEquals("r=$r", naiveByte(img, 29, 23, r), FastBlur.boxBlurByte(img, 29, 23, r))
        val wide = noiseByte(41, 7, 13L)
        assertArrayEquals(naiveByte(wide, 41, 7, 4), FastBlur.boxBlurByte(wide, 41, 7, 4))
        val tall = noiseByte(7, 41, 13L)
        assertArrayEquals(naiveByte(tall, 7, 41, 4), FastBlur.boxBlurByte(tall, 7, 41, 4))
        val row = noiseByte(37, 1, 5L)
        assertArrayEquals(naiveByte(row, 37, 1, 2), FastBlur.boxBlurByte(row, 37, 1, 2))
        val col = noiseByte(1, 37, 5L)
        assertArrayEquals(naiveByte(col, 1, 37, 2), FastBlur.boxBlurByte(col, 1, 37, 2))
    }

    @Test fun byteImpulseResponse() {
        // Single hot pixel in a zero field, radius 2 (win=5): 25 pixels get 255/25=10, rest 0.
        val w = 15; val h = 15
        val src = ByteArray(w * h); src[7 * w + 7] = 255.toByte()
        val out = FastBlur.boxBlurByte(src, w, h, 2)
        for (y in 0 until h) for (x in 0 until w) {
            val inWin = x in 5..9 && y in 5..9
            assertEquals("($x,$y)", if (inWin) 10 else 0, out[y * w + x].toInt() and 0xFF)
        }
    }

    @Test fun byteCornerReplicateEdge() {
        // Hand-computed pins of the replicate-edge convention (bit-exact by contract).
        val w = 9; val h = 9
        val src = ByteArray(w * h); src[0] = 255.toByte()
        val out = FastBlur.boxBlurByte(src, w, h, 1)
        fun at(x: Int, y: Int) = out[y * w + x].toInt() and 0xFF
        assertEquals(113, at(0, 0)) // 4 taps clamp onto the hot corner: 1020/9
        assertEquals(56, at(1, 0))  // 2 taps hit it: 510/9
        assertEquals(56, at(0, 1))
        assertEquals(28, at(1, 1)) // 1 tap: 255/9
        assertEquals(0, at(2, 0))
        assertEquals(0, at(0, 2))
        assertEquals(0, at(2, 2))
        assertEquals(0, at(8, 8))
    }

    @Test fun byteLargeRadiusNoOverflow() {
        // radius 20: horizontal sums hit 255*41=10455, vertical 428655 — must stay Int-clean.
        val w = 32; val h = 32
        val out = FastBlur.boxBlurByte(ByteArray(w * h) { 255.toByte() }, w, h, 20)
        for (b in out) assertEquals(255, b.toInt() and 0xFF)
    }

    // ---------- guards & determinism ----------

    @Test fun badDimsThrow() {
        try {
            FastBlur.boxBlurFloat(FloatArray(4), 0, 2, 1); fail("expected IAE")
        } catch (e: IllegalArgumentException) { /* expected */ }
        try {
            FastBlur.boxBlurByte(ByteArray(4), 3, 3, 1); fail("expected IAE")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun deterministic() {
        val f = noiseFloat(25, 25, 21L)
        assertArrayEquals(FastBlur.boxBlurFloat(f, 25, 25, 3), FastBlur.boxBlurFloat(f, 25, 25, 3), 0f)
        val b = noiseByte(25, 25, 21L)
        assertArrayEquals(FastBlur.boxBlurByte(b, 25, 25, 3), FastBlur.boxBlurByte(b, 25, 25, 3))
    }
}
