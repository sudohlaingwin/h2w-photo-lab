package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Executed verification for RetouchIntel (engine/AutoMagic.kt) — the adaptive
 * "retoucher's judgment" layer that turns a measured [RetouchIntel.PhotoProfile]
 * into a per-condition [RetouchIntel.RetouchRecipe], plus the fast
 * single-pass photo analysis behind it.
 *
 * Why this seam matters: recipeFor is the entire intelligence of the Auto
 * Retouch path (blemish strictness, skin-smoothing strength, heal/under-eye
 * strengths). It is pure decision logic with zero Android dependencies beyond
 * the Bitmap stub, so it is pinned here against silent regression — a wrong
 * threshold silently degrades every auto-retouch result.
 *
 * Runs on the standalone kotlinc harness (Gradle's test workers are broken in
 * this env); android.graphics.Bitmap is the proven nearest-neighbor stub.
 */
class RetouchIntelTest {

    // ---- Synthetic-image helpers ----

    /** ARGB gray pixel (Rec.709 luma of gray == the channel value). */
    private fun gray(luma: Float): Int {
        val c = (luma * 255f).toInt().coerceIn(0, 255)
        return -0x1000000 or (c shl 16) or (c shl 8) or c
    }

    private fun bitmapOf(w: Int, h: Int, lumaAt: (x: Int, y: Int) -> Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i -> gray(lumaAt(i % w, i / w)) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    // ---- recipeFor: full condition coverage ----

    @Test
    fun `recipeFor covers every condition without throwing`() {
        for (c in RetouchIntel.Condition.values()) {
            val r = RetouchIntel.recipeFor(RetouchIntel.PhotoProfile(0.3f, 0.4f, 0.5f, c))
            assertNotNull("recipe for $c", r)
        }
    }

    @Test
    fun `every recipe stays inside valid ranges`() {
        for (c in RetouchIntel.Condition.values()) {
            val r = RetouchIntel.recipeFor(RetouchIntel.PhotoProfile(0.3f, 0.4f, 0.5f, c))
            assertTrue("$c: threshold scale positive", r.blemishThresholdScale > 0f)
            assertTrue("$c: skin clarity non-positive", r.skinClarity <= 0f)
            assertTrue("$c: skin sharpness non-positive", r.skinSharpness <= 0f)
            assertTrue("$c: heal strength 0..1", r.healStrength in 0f..1f)
            assertTrue("$c: under-eye strength 0..1", r.underEyeStrength in 0f..1f)
            assertTrue("$c: max blemishes positive", r.maxBlemishes > 0)
        }
    }

    @Test
    fun `noisy photos get the strictest blemish detection`() {
        // Grain mimics blemishes — the retoucher must be strict, not eager.
        val noisy = RetouchIntel.recipeFor(profile(RetouchIntel.Condition.NOISY))
        for (c in RetouchIntel.Condition.values()) {
            if (c == RetouchIntel.Condition.NOISY) continue
            val other = RetouchIntel.recipeFor(profile(c))
            assertTrue(
                "NOISY (${noisy.blemishThresholdScale}) strictest vs $c (${other.blemishThresholdScale})",
                noisy.blemishThresholdScale > other.blemishThresholdScale
            )
        }
    }

    @Test
    fun `noisy photos get the strongest skin smoothing`() {
        val noisy = RetouchIntel.recipeFor(profile(RetouchIntel.Condition.NOISY))
        for (c in RetouchIntel.Condition.values()) {
            if (c == RetouchIntel.Condition.NOISY) continue
            val other = RetouchIntel.recipeFor(profile(c))
            assertTrue(
                "NOISY clarity (${noisy.skinClarity}) stronger than $c (${other.skinClarity})",
                noisy.skinClarity < other.skinClarity
            )
        }
    }

    @Test
    fun `clean light catches subtle blemishes and preserves pores`() {
        val clean = RetouchIntel.recipeFor(profile(RetouchIntel.Condition.CLEAN))
        val normal = RetouchIntel.recipeFor(profile(RetouchIntel.Condition.NORMAL))
        // Lower threshold scale = MORE sensitive detection.
        assertTrue(
            "CLEAN (${clean.blemishThresholdScale}) more sensitive than NORMAL (${normal.blemishThresholdScale})",
            clean.blemishThresholdScale < normal.blemishThresholdScale
        )
        // Milder smoothing preserves pores: |-30| < |-45|.
        assertTrue(
            "CLEAN clarity (${clean.skinClarity}) milder than NORMAL (${normal.skinClarity})",
            clean.skinClarity > normal.skinClarity
        )
        assertTrue(
            "CLEAN sharpness (${clean.skinSharpness}) milder than NORMAL (${normal.skinSharpness})",
            clean.skinSharpness > normal.skinSharpness
        )
    }

    @Test
    fun `conditionLabel gives five distinct non-blank labels`() {
        val labels = RetouchIntel.Condition.values().map { RetouchIntel.conditionLabel(it) }
        assertEquals(5, labels.size)
        assertEquals(5, labels.toSet().size)
        labels.forEach { assertTrue("label '$it' non-blank", it.isNotBlank()) }
    }

    // ---- analyze: classification on synthetic inputs ----

    @Test
    fun `undersized bitmap returns the documented default profile`() {
        val p = RetouchIntel.analyze(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
        assertEquals(0.3f, p.noiseLevel, 1e-6f)
        assertEquals(0.4f, p.contrast, 1e-6f)
        assertEquals(0.5f, p.brightness, 1e-6f)
        assertEquals(RetouchIntel.Condition.NORMAL, p.condition)
    }

    @Test
    fun `solid black classifies as low light`() {
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { _, _ -> 0f })
        assertTrue("brightness ~0, was ${p.brightness}", p.brightness < 0.05f)
        assertEquals(RetouchIntel.Condition.LOW_LIGHT, p.condition)
    }

    @Test
    fun `solid white is not low light`() {
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { _, _ -> 1f })
        assertTrue("brightness ~1, was ${p.brightness}", p.brightness > 0.95f)
        assertNotEquals(RetouchIntel.Condition.LOW_LIGHT, p.condition)
    }

    @Test
    fun `flat mid-gray is clean light`() {
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { _, _ -> 0.5f })
        assertTrue("noise ~0, was ${p.noiseLevel}", p.noiseLevel < 0.05f)
        assertTrue("contrast ~0, was ${p.contrast}", p.contrast < 0.05f)
        assertEquals(RetouchIntel.Condition.CLEAN, p.condition)
    }

    @Test
    fun `smooth high-contrast gradient classifies as high contrast`() {
        // Linear ramp: Laplacian ~0 (no noise) but RMS contrast 255/sqrt(12)/90 ≈ 0.82.
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { x, _ -> x / 159f })
        assertTrue("noise low, was ${p.noiseLevel}", p.noiseLevel < 0.20f)
        assertTrue("contrast high, was ${p.contrast}", p.contrast > 0.60f)
        assertEquals(RetouchIntel.Condition.HIGH_CONTRAST, p.condition)
    }

    @Test
    fun `noisy mid-gray classifies as noisy`() {
        // Uniform ±40 noise on mid gray: mean-abs Laplacian ≈ 2.9σ ≈ 67 → 1.0 coerced.
        var seed = 123456789L
        fun rand(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFFFF).toFloat() / 0xFFFF
        }
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { _, _ ->
            (0.5f + (rand() - 0.5f) * 0.31f).coerceIn(0f, 1f)
        })
        assertTrue("noise high, was ${p.noiseLevel}", p.noiseLevel > 0.52f)
        assertEquals(RetouchIntel.Condition.NOISY, p.condition)
    }

    @Test
    fun `gentle sine variation lands on normal`() {
        // Smooth ±60 sine: contrast ≈ 42/90 ≈ 0.47, noise ~0 → NORMAL bucket.
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { x, _ ->
            (0.5f + 0.235f * sin(2 * PI * x / 160).toFloat()).coerceIn(0f, 1f)
        })
        assertTrue("noise low, was ${p.noiseLevel}", p.noiseLevel < 0.20f)
        assertTrue("contrast mid, was ${p.contrast}", p.contrast in 0.42f..0.60f)
        assertEquals(RetouchIntel.Condition.NORMAL, p.condition)
    }

    @Test
    fun `analysis feeds recipeFor end to end`() {
        // Flat gray → CLEAN → sensitive blemish detection + mild smoothing.
        val p = RetouchIntel.analyze(bitmapOf(160, 160) { _, _ -> 0.5f })
        val r = RetouchIntel.recipeFor(p)
        assertEquals(0.85f, r.blemishThresholdScale, 1e-6f)
        assertEquals(-30f, r.skinClarity, 1e-6f)
    }

    private fun profile(c: RetouchIntel.Condition) =
        RetouchIntel.PhotoProfile(0.3f, 0.4f, 0.5f, c)
}
