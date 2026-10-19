package com.lumora.photoeditor

import com.lumora.photoeditor.engine.AiProRetouch
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Executed verification for the AI Pro Retouch v1 engine (pure Kotlin, zero
 * Android imports — runs on the standalone kotlinc harness; Gradle's test
 * workers are broken in this env).
 *
 * Locks the flagship one-tap behavior the user judges on his phone:
 * weight normalization, the four scene branches, the strength no-op guard,
 * the white-balance honesty guards, and the fuse/warp LUT contracts.
 */
class AiProRetouchTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun statsOf(pixels: IntArray, w: Int, h: Int) =
        AiProRetouch.computeStats(pixels, w, h)

    private fun weightsOf(pixels: IntArray, w: Int, h: Int): FloatArray =
        AiProRetouch.heuristicPredictor.predict(statsOf(pixels, w, h))

    private fun assertWeightsSane(w: FloatArray, what: String) {
        assertEquals("$what: 4 weights", 4, w.size)
        w.forEachIndexed { i, v ->
            assertTrue("$what: weight[$i]=$v in 0..1", v in 0f..1f)
        }
        assertEquals("$what: weights sum to 1", 1f, w.sum(), 1e-5f)
    }

    // ---- guards ----

    @Test fun emptyInput_analyzeReturnsStrengthZero() {
        val blend = AiProRetouch.analyze(IntArray(0), 0, 0)
        assertEquals(0f, blend.strength, 0f)
    }

    @Test fun uniformFrame_analyzeReturnsStrengthZero() {
        // stdLum < 0.02 guard: a flat frame has nothing to grade.
        val blend = AiProRetouch.analyze(IntArray(64 * 64) { gray(128) }, 64, 64)
        assertEquals(0f, blend.strength, 0f)
    }

    @Test fun uniformFrame_weightsStillSane() {
        assertWeightsSane(weightsOf(IntArray(64 * 64) { gray(200) }, 64, 64), "uniform")
    }

    // ---- scene branches ----

    @Test fun portraitScene_portraitWeightDominates() {
        // 30% skin-tone pixels on mid gray: face presence must win.
        val px = IntArray(64 * 64) { i ->
            if (i % 10 < 3) rgb(200, 150, 120) else gray(128)
        }
        val w = weightsOf(px, 64, 64)
        assertWeightsSane(w, "portrait")
        assertTrue("portrait weight ${w[0]} dominates", w[0] > w[1] && w[0] > w[2] && w[0] > w[3])
    }

    @Test fun nightScene_nightWeightDominates() {
        // 97% near-black, 3% bright accents, no skin.
        val rnd = java.util.Random(7)
        val px = IntArray(64 * 64) {
            if (rnd.nextFloat() < 0.03f) gray(220) else gray(12 + rnd.nextInt(10))
        }
        val w = weightsOf(px, 64, 64)
        assertWeightsSane(w, "night")
        assertTrue("night weight ${w[2]} dominates", w[2] > w[0] && w[2] > w[1] && w[2] > w[3])
    }

    @Test fun backlitScene_backlitWeightWins() {
        // Bimodal: 40% dark subject, 35% bright background, 25% mid.
        val px = IntArray(64 * 64) { i ->
            when (i % 20) {
                in 0..7 -> gray(20)
                in 8..14 -> gray(230)
                else -> gray(128)
            }
        }
        val w = weightsOf(px, 64, 64)
        assertWeightsSane(w, "backlit")
        assertTrue("backlit weight ${w[3]} wins", w[3] > w[0] && w[3] > w[1] && w[3] > w[2])
    }

    @Test fun landscapeScene_landscapeWeightDominates() {
        // Saturated daylight, no skin: the "everything else" anchor.
        val px = IntArray(64 * 64) { i ->
            if (i % 2 == 0) rgb(100, 150, 220) else rgb(80, 160, 80)
        }
        val w = weightsOf(px, 64, 64)
        assertWeightsSane(w, "landscape")
        assertTrue("landscape weight ${w[1]} dominates", w[1] > w[0] && w[1] > w[2] && w[1] > w[3])
    }

    // ---- strength / no-op honesty ----

    @Test fun balancedPhoto_analyzeIsNoOp() {
        // Full-range neutral gradient: close to 0.46 mean, no cast, wide
        // range — need < 0.12, so strength must be exactly 0 (no undo entry).
        val px = IntArray(64 * 64) { i -> gray((i * 255) / (64 * 64)) }
        val blend = AiProRetouch.analyze(px, 64, 64)
        assertEquals("balanced photo is a no-op", 0f, blend.strength, 0f)
    }

    @Test fun darkPhoto_analyzeAppliesBoundedGrade() {
        val rnd = java.util.Random(11)
        val px = IntArray(64 * 64) { gray(20 + rnd.nextInt(20)) }
        val blend = AiProRetouch.analyze(px, 64, 64)
        assertTrue("dark photo gets a grade", blend.strength > 0f)
        assertTrue("strength bounded", blend.strength <= 1f)
        assertWeightsSane(blend.weights, "dark photo")
        assertTrue("scene named", blend.sceneName.isNotEmpty())
    }

    // ---- white-balance honesty guards ----

    private fun stats(
        castR: Float, castB: Float, wbReliable: Boolean,
    ) = AiProRetouch.PhotoStats(
        meanLum = 0.5f, stdLum = 0.2f, darkRatio = 0.1f, brightRatio = 0.1f,
        p1 = 20, p99 = 230, skinRatio = 0f, meanSat = 0.3f,
        castR = castR, castB = castB, wbReliable = wbReliable,
    )

    @Test fun wbGains_unreliableNull() {
        assertNull(AiProRetouch.computeWbGains(stats(0.2f, 0.2f, wbReliable = false)))
    }

    @Test fun wbGains_smallCastNull() {
        // Already neutral: don't touch it.
        assertNull(AiProRetouch.computeWbGains(stats(0.02f, 0.02f, wbReliable = true)))
    }

    @Test fun wbGains_strongCast_clampedGains() {
        val g = AiProRetouch.computeWbGains(stats(0.15f, -0.10f, wbReliable = true))
        assertNotNull(g)
        g!!
        assertEquals(3, g.size)
        // Exact inverses of the measured deviations: 1/(1+castR),
        // 1/(1-castR-castB), 1/(1+castB).
        assertEquals(1f / 1.15f, g[0], 1e-5f)
        assertEquals(1f / 0.95f, g[1], 1e-5f)
        assertEquals(1f / 0.90f, g[2], 1e-5f)
        g.forEach { assertTrue("gain $it in 0.6..1.67", it in 0.6f..1.67f) }
    }

    // ---- fuse / warp LUT contracts ----

    private fun rampLut(n: Int = AiProRetouch.LUT_SIZE): FloatArray {
        val lut = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = (((r * n) + g) * n + b) * 3
            lut[o] = r / (n - 1f); lut[o + 1] = g / (n - 1f); lut[o + 2] = b / (n - 1f)
        }
        return lut
    }

    @Test fun fuse_oneHot_reproducesBasis() {
        val n = AiProRetouch.LUT_SIZE
        val basis = listOf(rampLut(n), FloatArray(n * n * n * 3),
            FloatArray(n * n * n * 3), FloatArray(n * n * n * 3))
        val fused = AiProRetouch.fuse(floatArrayOf(1f, 0f, 0f, 0f), basis)
        assertArrayEquals(basis[0], fused, 1e-6f)
    }

    @Test fun fuse_outputClampedToUnit() {
        val n = AiProRetouch.LUT_SIZE
        val hot = FloatArray(n * n * n * 3) { 5f } // out-of-range basis
        val fused = AiProRetouch.fuse(floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f),
            listOf(hot, hot, hot, hot))
        fused.forEach { assertTrue("fused $it in 0..1", it in 0f..1f) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun fuse_rejectsWrongWeightCount() {
        val n = AiProRetouch.LUT_SIZE
        val basis = List(4) { FloatArray(n * n * n * 3) }
        AiProRetouch.fuse(floatArrayOf(1f, 0f, 0f), basis)
    }

    @Test fun warp_identityGains_bitIdentical() {
        // Documented contract: gains of exactly [1,1,1] reproduce the input
        // LUT bit-for-bit (no resampling noise in the no-WB path).
        val lut = rampLut()
        val out = AiProRetouch.warpLutForWb(lut, floatArrayOf(1f, 1f, 1f), AiProRetouch.LUT_SIZE)
        assertArrayEquals(lut, out, 0f)
    }

    @Test fun warp_nonIdentityGains_changesLut() {
        val lut = rampLut()
        val out = AiProRetouch.warpLutForWb(lut, floatArrayOf(1.2f, 1f, 0.9f), AiProRetouch.LUT_SIZE)
        assertFalse("warped LUT differs", lut.contentEquals(out))
        out.forEach { assertTrue("warped $it in 0..1", it in 0f..1f) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun warp_rejectsWrongLutSize() {
        AiProRetouch.warpLutForWb(FloatArray(10), floatArrayOf(1f, 1f, 1f), AiProRetouch.LUT_SIZE)
    }

    // ---- skin heuristic sanity ----

    @Test fun skinProbability_skinTone_high() {
        assertTrue(AiProRetouch.skinProbability(200, 150, 120) > 0.5f)
    }

    @Test fun skinProbability_gray_zero() {
        // maxRg - minRg < 15 guard: neutral gray is never skin.
        assertEquals(0f, AiProRetouch.skinProbability(128, 128, 128), 0f)
    }

    @Test fun skinProbability_blue_zero() {
        assertEquals(0f, AiProRetouch.skinProbability(100, 150, 220), 0f)
    }
}
