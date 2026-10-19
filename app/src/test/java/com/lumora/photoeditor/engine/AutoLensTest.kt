package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Executed verification for engine/AutoLens.kt (pure Kotlin, zero imports —
 * runs on the standalone kotlinc harness; Gradle's test workers are broken
 * in this env).
 *
 * This pins the math behind the Optics panel's automatic vignette correction
 * (shipped 2026-10-17, pro-premium program Phase A#2 slice 1): on a ~96px
 * downscale, Rec.709 luminance is binned by shader-style radial distance
 * vd = |uv - 0.5| * 1.42 and the shader gain model gain = 1 - vAmt*0.85*vMask
 * is fit by least squares, with guards that keep naturally dark-edge content
 * (night skies, dark backgrounds, flat dark edges) untouched.
 */
class AutoLensTest {

    // ---- Synthetic-image helpers ----

    /** ARGB gray pixel (Rec.709 luma of gray == the channel value). */
    private fun gray(luma: Float): Int {
        val c = (luma * 255f).toInt().coerceIn(0, 255)
        return -0x1000000 or (c shl 16) or (c shl 8) or c
    }

    private fun radialDist(x: Int, y: Int, w: Int, h: Int): Float {
        val u = x.toFloat() / (w - 1).coerceAtLeast(1)
        val v = y.toFloat() / (h - 1).coerceAtLeast(1)
        val dx = u - 0.5f
        val dy = v - 0.5f
        return sqrt(dx * dx + dy * dy) * 1.42f
    }

    private fun buildImage(w: Int, h: Int, lumaAt: (vd: Float) -> Float): IntArray =
        IntArray(w * h) { i -> gray(lumaAt(radialDist(i % w, i / w, w, h))) }

    // ---- Input validation ----

    @Test fun rejectsTinyImage() {
        assertNull(AutoLens.analyzeVignette(IntArray(16), 4, 4))
    }

    @Test fun rejectsUndersizedBuffer() {
        assertNull(AutoLens.analyzeVignette(IntArray(100), 32, 32))
    }

    // ---- Guards: naturally dark-edge content stays untouched ----

    @Test fun allBlackReturnsNull() {
        val px = buildImage(32, 32) { 0f }
        assertNull(AutoLens.analyzeVignette(px, 32, 32))
    }

    @Test fun darkUniformBelowSignalFloorReturnsNull() {
        // lc = 0.1 < 0.15: not enough center signal to trust a correction.
        val px = buildImage(32, 32) { 0.1f }
        assertNull(AutoLens.analyzeVignette(px, 32, 32))
    }

    @Test fun uniformGrayReturnsNull() {
        // No falloff: corner/center == 1 < 1.06.
        val px = buildImage(32, 32) { 0.5f }
        assertNull(AutoLens.analyzeVignette(px, 32, 32))
    }

    @Test fun flatDarkEdgesWithoutAccelerationReturnNull() {
        // Falloff EXISTS (gOuter = 1.2 >= 1.06) but is FLAT — inner and
        // outer gains are equal, so the optical-vignette signature
        // (accelerating outward) fails and the photo is left alone.
        val px = buildImage(32, 32) { vd -> if (vd < 0.55f) 0.6f else 0.5f }
        assertNull(AutoLens.analyzeVignette(px, 32, 32))
    }

    // ---- Detection: the shader-model round trip ----

    @Test fun recoversSyntheticVignetteAmount() {
        // Bake EXACTLY the shader model with known vAmt = -0.3:
        //   l = lc / (1 - vAmt*0.85*mask),  mask = smoothstep(e0, e1, vd)
        // with the auto profile's e0 = 0.4*0.9, e1 = e0 + (1-0.6*0.85) + 0.05.
        // Least squares must recover vAmt -> amount -30 slider units.
        val lc = 0.5f
        val vAmt = -0.3f
        val e0 = 0.4f * 0.9f
        val e1 = e0 + (1f - 0.6f * 0.85f) + 0.05f
        fun mask(vd: Float): Float {
            val t = ((vd - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        val px = buildImage(96, 96) { vd -> lc / (1f - vAmt * 0.85f * mask(vd)) }
        val est = AutoLens.analyzeVignette(px, 96, 96)
        assertNotNull(est)
        // 8-bit quantization + binning noise: allow ±1.5 slider units.
        assertEquals(-30f, est!!.amount, 1.5f)
        assertEquals(AutoLens.AUTO_MIDPOINT, est.midpoint, 0.0f)
        assertEquals(AutoLens.AUTO_FEATHER, est.feather, 0.0f)
        assertTrue("amount must be a corner-lifting negative", est.amount < 0f)
    }

    @Test fun extremeVignetteCappedAtMaxCorrection() {
        // Corners nearly black: the fit wants far more than the -60 cap —
        // noise amplification, not optics — so it clamps exactly to -60.
        val px = buildImage(96, 96) { vd ->
            when {
                vd < 0.55f -> 0.7f
                vd < 0.80f -> 0.5f   // inner bin gain 1.4: acceleration guard passes
                else -> 0.05f       // outer bin gain 14: way past the cap
            }
        }
        val est = AutoLens.analyzeVignette(px, 96, 96)
        assertNotNull(est)
        assertEquals(-60f, est!!.amount, 0.0f)
    }

    @Test fun weakVignetteBelowMinCorrectionReturnsNull() {
        // vAmt = -0.01 -> amount -1: below the 3-unit visibility floor and
        // below the 6% falloff trigger — an invisible correction is no
        // correction.
        val lc = 0.5f
        val vAmt = -0.01f
        val e0 = 0.4f * 0.9f
        val e1 = e0 + (1f - 0.6f * 0.85f) + 0.05f
        fun mask(vd: Float): Float {
            val t = ((vd - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        val px = buildImage(96, 96) { vd -> lc / (1f - vAmt * 0.85f * mask(vd)) }
        assertNull(AutoLens.analyzeVignette(px, 96, 96))
    }

    // ---- Contract surface ----

    @Test fun estimateRangesAreSliderUnits() {
        val est = VignetteEstimate(-25f, 40f, 60f)
        assertTrue(est.amount in -100f..0f)
        assertTrue(est.midpoint in 0f..100f)
        assertTrue(est.feather in 0f..100f)
    }
}
