package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * First-ever executed verification of [ColorTransfer] — the deterministic
 * Reinhard-et-al. (2001) statistical look transfer behind pro-spec Phase 1
 * "match this photo's colors to a reference photo" (mean/std matching in the
 * decorrelated lαβ space, baked into a 3D LUT for the GPU).
 *
 * The sharp edges worth pinning: the paper's printed LMS→RGB matrix rounded
 * to 4 decimals is NOT exactly invertible (off-diagonal error ~7e-3 leaked
 * ~1.6/255 into the blue channel on RGB→lαβ→RGB round-trips — caught during
 * development, fixed by deriving the inverse numerically), so the round-trip
 * identity is the load-bearing test; the spread-ratio clamp [0.2, 5] must not
 * drift (an unclamped ratio on a near-flat source channel blows highlights
 * out); the flat-source ratio-1 guard must hold; and the bakeLut index
 * layout must match what GlEngine.uploadLookLut expects or the LUT lands
 * rotated on the GPU.
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/colortransfer-harness/run.sh).
 */
class ColorTransferTest {

    private val GRAY50 = 0xFF808080.toInt()
    private val GRAY75 = 0xFFBFBFBF.toInt()

    private fun uniformFrame(pixel: Int, w: Int, h: Int) = IntArray(w * h) { pixel }

    /** Independent mean-L of pure black through the documented LOG_EPS floor. */
    private fun blackMeanL() = sqrt(3f) * ln(1e-4f)

    // ---------- analyze: contract guards ----------

    @Test fun analyzeEmptyReturnsZeros() {
        val s = ColorTransfer.analyze(IntArray(0), 0, 0)
        assertEquals(6, s.size)
        s.forEach { assertEquals(0f, it, 0f) }
    }

    @Test fun analyzeInvalidDimsReturnsZeros() {
        val s = ColorTransfer.analyze(intArrayOf(GRAY50), -1, 1)
        assertEquals(6, s.size)
        s.forEach { assertEquals(0f, it, 0f) }
    }

    @Test fun analyzeSinglePixelHasZeroSpread() {
        val s = ColorTransfer.analyze(intArrayOf(GRAY50), 1, 1)
        assertEquals(6, s.size)
        assertEquals(0f, s[3], 0f); assertEquals(0f, s[4], 0f); assertEquals(0f, s[5], 0f)
        // Means must agree with the public conversion, not a second code path.
        val lab = ColorTransfer.rgbToLab(0x80 / 255f, 0x80 / 255f, 0x80 / 255f)
        assertEquals(lab[0], s[0], 1e-6f)
        assertEquals(lab[1], s[1], 1e-6f)
        assertEquals(lab[2], s[2], 1e-6f)
    }

    @Test fun analyzeUniformFrameHasZeroSpread() {
        val s = ColorTransfer.analyze(uniformFrame(GRAY50, 64, 64), 64, 64)
        assertEquals(0f, s[3], 0f); assertEquals(0f, s[4], 0f); assertEquals(0f, s[5], 0f)
        val lab = ColorTransfer.rgbToLab(0x80 / 255f, 0x80 / 255f, 0x80 / 255f)
        assertEquals(lab[0], s[0], 1e-5f)
    }

    @Test fun analyzeBlackPixelHonorsLogFloor() {
        // Pure black goes through max(LMS, 1e-4): log(1e-4) on all three axes.
        val s = ColorTransfer.analyze(intArrayOf(0xFF000000.toInt()), 1, 1)
        assertEquals(blackMeanL(), s[0], 1e-3f)
        assertEquals(0f, s[1], 1e-6f)   // symmetric logs cancel on the chroma axes
        assertEquals(0f, s[2], 1e-6f)
    }

    @Test fun analyzeStridedSamplingDoesNotBiasUniformImage() {
        // 1M pixels -> step = sqrt(1e6/5e4) = 4: sampled stats must still be exact.
        val s = ColorTransfer.analyze(uniformFrame(GRAY75, 1000, 1000), 1000, 1000)
        val lab = ColorTransfer.rgbToLab(0xBF / 255f, 0xBF / 255f, 0xBF / 255f)
        assertEquals(lab[0], s[0], 1e-5f)
        assertEquals(0f, s[3], 0f)
    }

    @Test fun analyzeIsDeterministic() {
        val px = IntArray(256) { 0xFF000000.toInt() or (it * 0x010101) }
        val a = ColorTransfer.analyze(px, 16, 16)
        val b = ColorTransfer.analyze(px, 16, 16)
        for (i in 0..5) assertEquals(a[i], b[i], 0f)
    }

    // ---------- rgbToLab / labToRgb: round-trip identity ----------

    @Test fun rgbLabRoundTripIsIdentity() {
        // Tolerance 0.002: the rounded-matrix bug the KDoc warns about leaked
        // ~1.6/255 (0.0063) into blue — this test fails loudly on it.
        val colors = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
            floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 1f),
            floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.85f, 0.65f, 0.55f),
            floatArrayOf(0.1f, 0.05f, 0.02f), floatArrayOf(0.23f, 0.71f, 0.44f),
        )
        for (c in colors) {
            val lab = ColorTransfer.rgbToLab(c[0], c[1], c[2])
            val back = ColorTransfer.labToRgb(lab[0], lab[1], lab[2])
            for (ch in 0..2) assertEquals("ch$ch of ${c.contentToString()}", c[ch], back[ch], 0.002f)
        }
    }

    @Test fun blackRoundTripsToNearZero() {
        val lab = ColorTransfer.rgbToLab(0f, 0f, 0f)
        val back = ColorTransfer.labToRgb(lab[0], lab[1], lab[2])
        back.forEach { assertTrue("black leaked: $it", it < 0.001f) }
    }

    @Test fun grayHasNearZeroChromaAxes() {
        // The paper's matrix rows don't sum to exactly 1, so achromatic input
        // carries a documented ~2e-3 residual — must stay small, not zero.
        val lab = ColorTransfer.rgbToLab(0.5f, 0.5f, 0.5f)
        assertTrue("alpha=${lab[1]}", kotlin.math.abs(lab[1]) < 0.005f)
        assertTrue("beta=${lab[2]}", kotlin.math.abs(lab[2]) < 0.002f)
    }

    @Test fun lutSizeConstantIs17() {
        assertEquals(17, ColorTransfer.LUT_SIZE)
    }

    // ---------- transferPixel: the mapping formula ----------

    @Test(expected = IllegalArgumentException::class)
    fun transferPixelRejectsShortSrcStats() {
        ColorTransfer.transferPixel(0.5f, 0.5f, 0.5f, FloatArray(5), FloatArray(6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferPixelRejectsShortDstStats() {
        ColorTransfer.transferPixel(0.5f, 0.5f, 0.5f, FloatArray(6), FloatArray(4))
    }

    @Test fun transferPixelIsIdentityWhenStatsMatch() {
        val s = ColorTransfer.analyze(uniformFrame(GRAY50, 32, 32), 32, 32)
        val pixels = arrayOf(
            floatArrayOf(0.2f, 0.4f, 0.8f), floatArrayOf(0.9f, 0.1f, 0.3f),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f),
        )
        for (p in pixels) {
            val out = ColorTransfer.transferPixel(p[0], p[1], p[2], s, s)
            for (ch in 0..2) assertEquals(p[ch], out[ch], 0.002f)
        }
    }

    @Test fun transferPixelAppliesPureMeanShiftOnFlatSource() {
        // Both stats flat -> ratio guard gives exactly 1; only the mean shift moves.
        val src = ColorTransfer.analyze(uniformFrame(GRAY50, 32, 32), 32, 32)
        val dst = ColorTransfer.analyze(uniformFrame(GRAY75, 32, 32), 32, 32)
        val lab = ColorTransfer.rgbToLab(0.5f, 0.5f, 0.5f)
        val expected = ColorTransfer.labToRgb(
            lab[0] - src[0] + dst[0],
            lab[1] - src[1] + dst[1],
            lab[2] - src[2] + dst[2],
        )
        val actual = ColorTransfer.transferPixel(0.5f, 0.5f, 0.5f, src, dst)
        for (ch in 0..2) assertEquals(expected[ch], actual[ch], 1e-5f)
        // And the shift really moved: brighter reference -> brighter pixel.
        assertTrue(actual[0] > 0.5f && actual[1] > 0.5f && actual[2] > 0.5f)
    }

    @Test fun transferPixelClampsSpreadRatio() {
        // Raw ratio 500 must clamp to MAX_RATIO=5; raw 0.01 must clamp to MIN_RATIO=0.2.
        val src = floatArrayOf(0f, 0f, 0f, 0.1f, 0.1f, 0.1f)
        val dstHi = floatArrayOf(0f, 0f, 0f, 50f, 50f, 50f)
        val dstLo = floatArrayOf(0f, 0f, 0f, 0.001f, 0.001f, 0.001f)
        val lab = ColorTransfer.rgbToLab(0.5f, 0.5f, 0.5f)
        val expectedHi = ColorTransfer.labToRgb(5f * lab[0], 5f * lab[1], 5f * lab[2])
        val expectedLo = ColorTransfer.labToRgb(0.2f * lab[0], 0.2f * lab[1], 0.2f * lab[2])
        val actualHi = ColorTransfer.transferPixel(0.5f, 0.5f, 0.5f, src, dstHi)
        val actualLo = ColorTransfer.transferPixel(0.5f, 0.5f, 0.5f, src, dstLo)
        for (ch in 0..2) {
            assertEquals(expectedHi[ch], actualHi[ch], 1e-5f)
            assertEquals(expectedLo[ch], actualLo[ch], 1e-5f)
        }
    }

    @Test fun transferPixelFlatSourceChannelKeepsOwnSpread() {
        // std <= 1e-4 -> ratio exactly 1, not 0 or infinity.
        val src = floatArrayOf(1f, 2f, 3f, 0f, 0.4f, 0.5f)
        val dst = floatArrayOf(4f, 5f, 6f, 9f, 0.8f, 1.0f)
        val lab = ColorTransfer.rgbToLab(0.3f, 0.5f, 0.7f)
        val expected = ColorTransfer.labToRgb(
            (lab[0] - 1f) * 1f + 4f,          // L: ratio 1 (flat source)
            (lab[1] - 2f) * 2f + 5f,          // alpha: 0.8/0.4 = 2
            (lab[2] - 3f) * 2f + 6f,          // beta: 1.0/0.5 = 2
        )
        val actual = ColorTransfer.transferPixel(0.3f, 0.5f, 0.7f, src, dst)
        for (ch in 0..2) assertEquals(expected[ch], actual[ch], 1e-5f)
    }

    @Test fun transferPixelOutputStaysInGamut() {
        // Extreme stats (max ratio + huge mean shift) must not escape 0..1.
        val src = floatArrayOf(0f, 0f, 0f, 0.1f, 0.1f, 0.1f)
        val dst = floatArrayOf(10f, 10f, 10f, 50f, 50f, 50f)
        val pixels = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0.2f, 0.9f, 0.4f),
        )
        for (p in pixels) {
            val out = ColorTransfer.transferPixel(p[0], p[1], p[2], src, dst)
            out.forEach { assertTrue("out of gamut: $it", it in 0f..1f) }
        }
    }

    // ---------- bakeLut: size, layout, identity ----------

    @Test(expected = IllegalArgumentException::class)
    fun bakeLutRejectsSizeOne() {
        val s = FloatArray(6)
        ColorTransfer.bakeLut(s, s, 1)
    }

    @Test fun bakeLutHasDocumentedSize() {
        val s = FloatArray(6)
        assertEquals(3 * 3 * 3 * 3, ColorTransfer.bakeLut(s, s, 3).size)
        assertEquals(17 * 17 * 17 * 3, ColorTransfer.bakeLut(s, s).size)
    }

    @Test fun bakeLutLayoutMatchesGlExpectation() {
        // n=2 identity LUT: cell (r,g,b) must hold the lattice point itself at
        // o = (((r*n)+g)*n+b)*3, or the GPU reads a rotated cube.
        val s = ColorTransfer.analyze(uniformFrame(GRAY50, 32, 32), 32, 32)
        val lut = ColorTransfer.bakeLut(s, s, 2)
        val n = 2
        for (r in 0..1) for (g in 0..1) for (b in 0..1) {
            val o = (((r * n) + g) * n + b) * 3
            assertEquals(r.toFloat(), lut[o], 0.002f)
            assertEquals(g.toFloat(), lut[o + 1], 0.002f)
            assertEquals(b.toFloat(), lut[o + 2], 0.002f)
        }
    }

    @Test fun bakeLutValuesStayInGamut() {
        val src = ColorTransfer.analyze(uniformFrame(GRAY50, 64, 64), 64, 64)
        val dst = ColorTransfer.analyze(uniformFrame(GRAY75, 64, 64), 64, 64)
        val lut = ColorTransfer.bakeLut(src, dst, 5)
        lut.forEach { assertTrue("lut out of gamut: $it", it in 0f..1f) }
    }

    // ---------- maxDeviationFromIdentity: the no-op honesty guard ----------

    @Test fun maxDeviationIsZeroWhenStatsMatch() {
        // Bound 2e-4, not 0: the black lattice corner carries the documented
        // LOG_EPS floor — rgbToLab(0,0,0) clamps LMS to 1e-4, and the
        // near-unity inverse maps it back to ~1.0003e-4, not bit-exact 0.
        val s = ColorTransfer.analyze(uniformFrame(GRAY50, 64, 64), 64, 64)
        assertTrue(ColorTransfer.maxDeviationFromIdentity(s, s) < 2e-4f)
    }

    @Test fun maxDeviationDetectsRealShift() {
        val src = ColorTransfer.analyze(uniformFrame(GRAY50, 64, 64), 64, 64)
        val dst = src.copyOf().also { it[0] = it[0] + 3.0f }  // big L mean shift
        val dev = ColorTransfer.maxDeviationFromIdentity(src, dst)
        assertTrue("dev=$dev", dev > 0.5f)
        assertTrue("dev=$dev", dev <= 1.0f)
    }
}
