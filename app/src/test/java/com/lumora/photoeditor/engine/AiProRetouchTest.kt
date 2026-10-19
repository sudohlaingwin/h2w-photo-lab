package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever executed verification of [AiProRetouch] — the on-device
 * predictor + LUT fusion behind the "AI Pro Retouch" one-tap grade.
 *
 * Pins the documented contracts: skin-probability guard shape, stats
 * computation, heuristic weight normalization and scene dominance, the
 * analyze() no-op honesty guards (uniform frame / already-balanced photo
 * must return strength 0), WB-gain exact-inverse math and clamps, the
 * WB LUT-warp bit-identity for [1,1,1] gains, and the fusion equation
 * including the zero-weight skip and output clamping.
 */
class AiProRetouchTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f, msg: String = "") {
        assertTrue("$msg expected=$expected actual=$actual", abs(expected - actual) <= eps)
    }

    private fun baseStats() = AiProRetouch.PhotoStats(
        meanLum = 0.5f, stdLum = 0.2f, darkRatio = 0.1f, brightRatio = 0.1f,
        p1 = 40, p99 = 200, skinRatio = 0f, meanSat = 0.2f,
        castR = 0f, castB = 0f, wbReliable = true,
    )

    private fun grayFrame(value: Int, w: Int = 32, h: Int = 32): IntArray {
        val argb = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        return IntArray(w * h) { argb }
    }

    // ------------------------------------------------------------------
    // skinProbability
    // ------------------------------------------------------------------

    @Test
    fun skinProbability_typicalSkinPixel_isHigh() {
        // (210,160,130): warm midtone with strong R-G separation — textbook skin.
        val p = AiProRetouch.skinProbability(210, 160, 130)
        assertTrue("expected high skin probability, got $p", p > 0.7f)
    }

    @Test
    fun skinProbability_blueSky_isZero() {
        assertEquals(0f, AiProRetouch.skinProbability(60, 120, 220))
    }

    @Test
    fun skinProbability_tooDark_isZero() {
        assertEquals(0f, AiProRetouch.skinProbability(10, 10, 10))
    }

    @Test
    fun skinProbability_grayNoSeparation_isZero() {
        assertEquals(0f, AiProRetouch.skinProbability(128, 128, 128))
    }

    @Test
    fun skinProbability_alwaysInUnitRange() {
        for (r in 0..255 step 17) for (g in 0..255 step 23) for (b in 0..255 step 31) {
            val p = AiProRetouch.skinProbability(r, g, b)
            assertTrue("out of range at ($r,$g,$b): $p", p in 0f..1f)
        }
    }

    // ------------------------------------------------------------------
    // computeStats
    // ------------------------------------------------------------------

    @Test
    fun computeStats_emptyFrame_returnsNeutralDefaults_noThrow() {
        val s = AiProRetouch.computeStats(IntArray(0), 0, 0)
        assertEquals(0.5f, s.meanLum)
        assertEquals(0f, s.stdLum)
        assertEquals(0, s.p1)
        assertEquals(255, s.p99)
        assertEquals(false, s.wbReliable)
    }

    @Test
    fun computeStats_uniformGray128_exactStats() {
        val s = AiProRetouch.computeStats(grayFrame(128), 32, 32)
        assertClose(128f / 255f, s.meanLum, 1e-3f, "meanLum")
        assertClose(0f, s.stdLum, 1e-6f, "stdLum")
        assertEquals(128, s.p1)
        assertEquals(128, s.p99)
        assertEquals(0f, s.darkRatio)
        assertEquals(0f, s.brightRatio)
        assertEquals(0f, s.skinRatio)
        assertEquals(0f, s.meanSat)
        // Uniform mid gray is neutral content: reliable WB, zero cast.
        assertTrue(s.wbReliable)
        assertClose(0f, s.castR, 1e-6f, "castR")
        assertClose(0f, s.castB, 1e-6f, "castB")
    }

    @Test
    fun computeStats_wbCandidateBoundary_lum29Unreliable_lum30Reliable() {
        val dark = AiProRetouch.computeStats(grayFrame(29), 16, 16)
        assertEquals(false, dark.wbReliable)
        val edge = AiProRetouch.computeStats(grayFrame(30), 16, 16)
        assertEquals(true, edge.wbReliable)
    }

    @Test
    fun computeStats_determinism() {
        val px = grayFrame(90)
        val a = AiProRetouch.computeStats(px, 32, 32)
        val b = AiProRetouch.computeStats(px, 32, 32)
        assertEquals(a, b)
    }

    // ------------------------------------------------------------------
    // heuristicPredictor — contract: size 4, non-negative, sums to 1
    // ------------------------------------------------------------------

    @Test
    fun predictor_weightsAlwaysValidDistribution() {
        val rng = java.util.Random(42)
        repeat(200) {
            val s = baseStats().copy(
                meanLum = rng.nextFloat(), darkRatio = rng.nextFloat(),
                brightRatio = rng.nextFloat(), skinRatio = rng.nextFloat() * 0.5f,
                meanSat = rng.nextFloat(), p1 = rng.nextInt(100), p99 = 100 + rng.nextInt(156),
            )
            val w = AiProRetouch.heuristicPredictor.predict(s)
            assertEquals(4, w.size)
            assertTrue("negative weight: ${w.toList()}", w.all { it >= 0f })
            assertClose(1f, w.sum(), 1e-5f, "weights must sum to 1")
        }
    }

    @Test
    fun predictor_facePresence_dominatesPortrait() {
        val w = AiProRetouch.heuristicPredictor.predict(
            baseStats().copy(skinRatio = 0.2f, meanSat = 0.3f, p1 = 40, p99 = 200)
        )
        assertTrue("portrait should dominate, got ${w.toList()}", w[0] > 0.8f)
    }

    @Test
    fun predictor_darkFrame_selectsNight() {
        val w = AiProRetouch.heuristicPredictor.predict(
            baseStats().copy(darkRatio = 0.7f, meanLum = 0.2f, brightRatio = 0.02f,
                p1 = 10, p99 = 120)
        )
        assertEquals(1f, w[AiProRetouch.BASIS_NIGHT])
    }

    @Test
    fun predictor_flatDaylight_neverCollapsesToLandscape() {
        // "Everything else" anchor: even a scene that fires no other score
        // must yield a valid distribution leaning on Landscape.
        val w = AiProRetouch.heuristicPredictor.predict(
            baseStats().copy(meanSat = 0.1f, p1 = 60, p99 = 160)
        )
        assertEquals(1f, w[AiProRetouch.BASIS_LANDSCAPE])
    }

    @Test
    fun predictor_bimodalBacklit_firesBacklitStrongly() {
        val w = AiProRetouch.heuristicPredictor.predict(
            baseStats().copy(darkRatio = 0.45f, brightRatio = 0.35f,
                p1 = 20, p99 = 230, meanLum = 0.4f, meanSat = 0.15f)
        )
        assertTrue("backlit should be strong, got ${w.toList()}", w[3] > 0.6f)
        val argmax = w.indices.maxByOrNull { w[it] }!!
        assertEquals(AiProRetouch.BASIS_BACKLIT, argmax)
    }

    @Test
    fun predictor_determinism() {
        val s = baseStats().copy(skinRatio = 0.1f, darkRatio = 0.3f)
        val a = AiProRetouch.heuristicPredictor.predict(s)
        val b = AiProRetouch.heuristicPredictor.predict(s)
        assertTrue(a.contentEquals(b))
    }

    // ------------------------------------------------------------------
    // analyze() — the honesty no-op guards
    // ------------------------------------------------------------------

    @Test
    fun analyze_uniformFrame_strengthZero_noUndoEntry() {
        val r = AiProRetouch.analyze(grayFrame(128), 32, 32)
        assertEquals(0f, r.strength)
        assertEquals("Photo", r.sceneName)
        assertTrue(r.weights.all { it == 0.25f })
        assertNull(r.wbGains)
    }

    @Test
    fun analyze_balancedPhoto_strengthZero() {
        // mean ~0.46 (near the 0.46 anchor), neutral cast, decent spread:
        // already balanced -> must no-op rather than "improve" it.
        val w = 64; val h = 64
        val px = IntArray(w * h) { i -> if ((i / w) % 2 == 0) grayFrame(40, 1, 1)[0] else grayFrame(195, 1, 1)[0] }
        val r = AiProRetouch.analyze(px, w, h)
        assertEquals(0f, r.strength)
        assertNull(r.wbGains)
    }

    @Test
    fun analyze_underexposedPhoto_strengthInRange_sceneNight() {
        val w = 32; val h = 32
        val dark = grayFrame(20, 1, 1)[0]; val mid = grayFrame(60, 1, 1)[0]
        val px = IntArray(w * h) { i -> if (i < w * h * 4 / 5) dark else mid }
        val r = AiProRetouch.analyze(px, w, h)
        assertTrue("strength ${r.strength} out of range", r.strength in 0.8f..0.95f)
        assertEquals("Night", r.sceneName)
        assertClose(1f, r.weights.sum(), 1e-5f, "weights sum")
    }

    @Test
    fun analyze_emptyFrame_noThrow_strengthZero() {
        val r = AiProRetouch.analyze(IntArray(0), 0, 0)
        assertEquals(0f, r.strength)
    }

    // ------------------------------------------------------------------
    // computeWbGains
    // ------------------------------------------------------------------

    @Test
    fun wbGains_unreliableNeutralSubset_returnsNull() {
        val s = baseStats().copy(wbReliable = false, castR = 0.3f, castB = -0.2f)
        assertNull(AiProRetouch.computeWbGains(s))
    }

    @Test
    fun wbGains_alreadyNeutral_returnsNull() {
        assertNull(AiProRetouch.computeWbGains(baseStats()))
        assertNull(AiProRetouch.computeWbGains(baseStats().copy(castR = 0.03f, castB = 0.02f)))
    }

    @Test
    fun wbGains_castPresent_exactInverseGains() {
        val s = baseStats().copy(castR = 0.12f, castB = -0.02f)
        val g = AiProRetouch.computeWbGains(s)!!
        assertEquals(3, g.size)
        assertClose(1f / 1.12f, g[0], 1e-4f, "gR")
        assertClose(1f / 0.90f, g[1], 1e-4f, "gG")
        assertClose(1f / 0.98f, g[2], 1e-4f, "gB")
        // Exact-inverse property: gains map the measured neutral estimate
        // back to (1,1,1) — the KDoc's core claim.
        assertClose(1f, g[0] * (1f + 0.12f), 1e-4f, "gR inverse")
        assertClose(1f, g[1] * (1f - 0.12f + 0.02f), 1e-4f, "gG inverse")
        assertClose(1f, g[2] * (1f - 0.02f), 1e-4f, "gB inverse")
    }

    @Test
    fun wbGains_extremeCast_clampedToSafeRange() {
        val s = baseStats().copy(castR = 1.0f, castB = 0f)
        val g = AiProRetouch.computeWbGains(s)!!
        assertEquals(0.6f, g[0])   // 1/2 would be 0.5 -> clamped to 0.6
        assertEquals(1.67f, g[1])  // 1/0 -> +inf -> clamped to 1.67
        assertEquals(1f, g[2])
    }

    // ------------------------------------------------------------------
    // warpLutForWb
    // ------------------------------------------------------------------

    private fun identityLut(n: Int): FloatArray {
        val lut = FloatArray(n * n * n * 3)
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
            val o = (((r * n) + g) * n + b) * 3
            lut[o] = r / (n - 1).toFloat()
            lut[o + 1] = g / (n - 1).toFloat()
            lut[o + 2] = b / (n - 1).toFloat()
        }
        return lut
    }

    @Test
    fun warpLut_identityGains_reproduceBitForBit() {
        val lut = identityLut(4)
        val warped = AiProRetouch.warpLutForWb(lut, floatArrayOf(1f, 1f, 1f), 4)
        assertTrue("identity gains must reproduce the LUT exactly", lut.contentEquals(warped))
    }

    @Test
    fun warpLut_redGainHalf_samplesHalfRed() {
        val lut = identityLut(2)
        val warped = AiProRetouch.warpLutForWb(lut, floatArrayOf(0.5f, 1f, 1f), 2)
        // Cell (1,0,0) now samples the LUT at (0.5, 0, 0) -> red channel 0.5.
        val o = (((1 * 2) + 0) * 2 + 0) * 3
        assertClose(0.5f, warped[o], 1e-6f, "warped red")
        assertClose(0f, warped[o + 1], 1e-6f, "warped green")
        assertClose(0f, warped[o + 2], 1e-6f, "warped blue")
        // Cell (1,1,1) samples at (0.5, 1, 1).
        val o2 = (((1 * 2) + 1) * 2 + 1) * 3
        assertClose(0.5f, warped[o2], 1e-6f, "corner red")
        assertClose(1f, warped[o2 + 1], 1e-6f, "corner green")
        assertClose(1f, warped[o2 + 2], 1e-6f, "corner blue")
    }

    @Test(expected = IllegalArgumentException::class)
    fun warpLut_wrongLutSize_throws() {
        AiProRetouch.warpLutForWb(FloatArray(10), floatArrayOf(1f, 1f, 1f), 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun warpLut_badGainsSize_throws() {
        AiProRetouch.warpLutForWb(identityLut(2), floatArrayOf(1f, 1f), 2)
    }

    // ------------------------------------------------------------------
    // fuse
    // ------------------------------------------------------------------

    @Test
    fun fuse_uniformWeightsOnIdentityBasis_returnsIdentity() {
        val id = identityLut(3)
        val fused = AiProRetouch.fuse(
            floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f),
            listOf(id, id.copyOf(), id.copyOf(), id.copyOf()),
        )
        assertTrue(fused.contentEquals(id))
    }

    @Test
    fun fuse_singleWeight_returnsThatBasisExactly() {
        val a = FloatArray(3 * 3 * 3 * 3) { (it % 7) / 7f }
        val basis = listOf(a, FloatArray(a.size) { 0.5f }, FloatArray(a.size) { 0.1f }, FloatArray(a.size) { 0.9f })
        val fused = AiProRetouch.fuse(floatArrayOf(1f, 0f, 0f, 0f), basis)
        assertTrue(fused.contentEquals(a))
    }

    @Test
    fun fuse_outputClampedToUnitRange() {
        val hot = FloatArray(2 * 2 * 2 * 3) { 2.5f }
        val ok = FloatArray(hot.size) { 0.5f }
        val fused = AiProRetouch.fuse(floatArrayOf(1f, 0f, 0f, 0f), listOf(hot, ok, ok, ok))
        assertTrue("fused values must be clamped to [0,1]", fused.all { it == 1f })
    }

    @Test
    fun fuse_zeroWeightLutSkipped_noNaNPropagation() {
        val nan = FloatArray(2 * 2 * 2 * 3) { Float.NaN }
        val a = FloatArray(nan.size) { 0.3f }
        val fused = AiProRetouch.fuse(
            floatArrayOf(0f, 0f, 0f, 1f),
            listOf(nan, nan, nan, a),
        )
        assertTrue("zero-weighted NaN LUT must not pollute the fusion", fused.none { it.isNaN() })
        assertTrue(fused.contentEquals(a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fuse_wrongWeightCount_throws() {
        val id = identityLut(2)
        AiProRetouch.fuse(floatArrayOf(0.5f, 0.5f), listOf(id, id, id, id))
    }

    @Test
    fun constants_matchDocumentedContract() {
        assertEquals(17, AiProRetouch.LUT_SIZE)
        assertEquals(4, AiProRetouch.BASIS_COUNT)
        assertEquals(listOf("Portrait", "Landscape", "Night", "Backlit"), AiProRetouch.BASIS_NAMES)
        assertEquals(AiProRetouch.BASIS_COUNT, AiProRetouch.BASIS_NAMES.size)
    }
}
