package com.lumora.photoeditor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of [LaplaceHeal] — the pure-Kotlin Laplace
 * (plane-fit tone field) seamless-clone engine behind the Heal tool,
 * the engine the user's "retouch must be excellent" complaints sit on.
 *
 * Pins the observable contract: blemish removal through the source patch
 * (the money behavior), the overlay write's round-to-nearest packing
 * (referenced by the source KDoc — a truncation would darken every heal
 * stroke up to one level), tone matching to the destination ring (the
 * "seamless" half of Poisson cloning), the feathered disc mask
 * (opaque center, partial feather edge, untouched outside), the guard
 * shape (strength<=0, short buffers, degenerate frame), the whole-patch
 * source-offset slide ([clampSourceOffset]), bbox-scoped overlay writes,
 * "over" compositing onto existing content, and determinism.
 *
 * Harness: hidden_files/laplaceheal-test-harness/run.sh (kotlinc 1.9.23 +
 * JUnit 4.13.2 on the REAL source + this REAL repo test — Gradle's
 * unit-test runner is broken in this env).
 */
class LaplaceHealTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun alphaOf(p: Int): Int = (p ushr 24) and 0xFF

    /** 200x200 uniform gray with a dark disc "blemish" at (100,100). */
    private fun blemishedFrame(blemishValue: Int, blemishRadius: Int = 12): IntArray {
        val w = 200; val h = 200
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val dx = x - 100; val dy = y - 100
            if (dx * dx + dy * dy <= blemishRadius * blemishRadius) argb(blemishValue, blemishValue, blemishValue)
            else argb(128, 128, 128)
        }
    }

    private fun healBlemish(
        blemishValue: Int,
        strength: Float,
        out: IntArray,
    ) {
        val image = blemishedFrame(blemishValue)
        LaplaceHeal.heal(
            image, out, 200, 200,
            100f, 100f, 20f,
            50f, 0f, // source patch: clean area 50px to the right
            strength = strength,
        )
    }

    private fun centerPixel(out: IntArray): Int = out[100 * 200 + 100]

    // --- Money behavior ----------------------------------------------------

    @Test
    fun heal_removesBlemishCenterAtFullStrength() {
        val out = IntArray(200 * 200)
        healBlemish(blemishValue = 40, strength = 1f, out)
        // D=40, h=S-D=88, ring plane is exactly 0 -> 40+88=128, fully opaque.
        assertEquals(argb(128, 128, 128), centerPixel(out))
    }

    @Test
    fun heal_overlayWriteRoundsToNearest_noDarkBias() {
        val out = IntArray(200 * 200)
        healBlemish(blemishValue = 41, strength = 0.5f, out)
        // 41 + (128-41)*0.5 = 84.5 exactly in float32. Round-to-nearest
        // packs 85; truncation would pack 84 (one level dark). This is the
        // pin the source KDoc references.
        assertEquals(argb(85, 85, 85), centerPixel(out))
    }

    @Test
    fun heal_matchesToneToDestinationRing() {
        // Left half 120, right half 128; heal in the left half sourcing
        // from the right: ring h=8 constant -> plane (8,0,0) ->
        // healed = D + (8-8) = D, i.e. no visible patch brightness.
        val w = 200; val h = 100
        val image = IntArray(w * h) { i ->
            val v = if (i % w < 100) 120 else 128; argb(v, v, v)
        }
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 40f, 50f, 15f, 80f, 0f)
        assertEquals(argb(120, 120, 120), out[50 * w + 40])
    }

    // --- Mask shape --------------------------------------------------------

    @Test
    fun heal_discCornerOutsideDiscStaysTransparent() {
        val out = IntArray(200 * 200)
        healBlemish(blemishValue = 40, strength = 1f, out)
        // (80,80) is inside the bbox but at d=1.41 radii -> mask 0 -> skipped.
        assertEquals(0, out[80 * 200 + 80])
    }

    @Test
    fun heal_featherEdgeIsPartiallyTransparent() {
        val out = IntArray(200 * 200)
        healBlemish(blemishValue = 40, strength = 1f, out)
        // (119,100): d=0.95 -> mask ~0.028 -> low but nonzero alpha,
        // and the pixel is clean (outside the blemish) so rgb is exact.
        val p = out[100 * 200 + 119]
        val a = alphaOf(p)
        assertTrue("feather edge alpha should be small but nonzero, was $a", a in 1..40)
        assertEquals(128, (p shr 16) and 0xFF)
    }

    @Test
    fun heal_uniformImageReproducesExactPixels() {
        val w = 100; val h = 100
        val image = IntArray(w * h) { argb(200, 200, 200) }
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 50f, 50f, 10f, 0f, 0f)
        // h=0 everywhere -> plane 0 -> healed=D exactly.
        assertEquals(argb(200, 200, 200), out[50 * w + 50])
    }

    // --- Guards ------------------------------------------------------------

    @Test
    fun heal_strengthZero_leavesOutUntouched() {
        val out = IntArray(200 * 200)
        healBlemish(blemishValue = 40, strength = 0f, out)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun heal_negativeStrength_leavesOutUntouched() {
        val out = IntArray(200 * 200)
        healBlemish(blemishValue = 40, strength = -0.5f, out)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun heal_imageBufferTooSmall_noWrite() {
        val w = 200; val h = 200
        val image = IntArray(w * h - 1) { argb(128, 128, 128) }
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 100f, 100f, 20f, 50f, 0f)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun heal_outBufferTooSmall_noWrite() {
        val w = 200; val h = 200
        val image = IntArray(w * h) { argb(128, 128, 128) }
        val out = IntArray(w * h - 1)
        LaplaceHeal.heal(image, out, w, h, 100f, 100f, 20f, 50f, 0f)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun heal_degenerateFrame_noWrite() {
        val image = IntArray(1) { argb(128, 128, 128) }
        val out = IntArray(1)
        LaplaceHeal.heal(image, out, 1, 1, 0.5f, 0.5f, 5f, 0f, 0f)
        assertEquals(0, out[0])
    }

    @Test
    fun heal_tinyRadius_usesFallbackMeanField() {
        // r=1: the only mask>0 pixel is the center, so the ring is empty
        // (<8 samples -> mean fallback). Must not crash and must heal.
        val w = 100; val h = 100
        val image = IntArray(w * h) { argb(200, 200, 200) }
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 50f, 50f, 1f, 0f, 0f)
        assertEquals(argb(200, 200, 200), out[50 * w + 50])
    }

    // --- clampSourceOffset -------------------------------------------------

    @Test
    fun clampSourceOffset_passThroughInFrame() {
        assertEquals(
            Pair(10f, -5f),
            LaplaceHeal.clampSourceOffset(10f, -5f, 20, 40, 10, 30, 100, 100),
        )
    }

    @Test
    fun clampSourceOffset_clampsOffFrame() {
        // dx range [-20, 59], dy range [-10, 69] for this rect.
        assertEquals(
            Pair(-20f, 69f),
            LaplaceHeal.clampSourceOffset(-100f, 100f, 20, 40, 10, 30, 100, 100),
        )
    }

    @Test
    fun clampSourceOffset_allowsExactlyToEdge() {
        assertEquals(
            Pair(59f, -10f),
            LaplaceHeal.clampSourceOffset(59f, -10f, 20, 40, 10, 30, 100, 100),
        )
    }

    @Test
    fun heal_sourceOffsetSlidesWholePatchNearBorder() {
        // srcDx=-500 would run the patch off the left edge; the slide
        // clamps it so every sample stays a real pixel.
        val w = 100; val h = 100
        val image = IntArray(w * h) { argb(150, 150, 150) }
        val out = IntArray(w * h)
        LaplaceHeal.heal(image, out, w, h, 10f, 50f, 8f, -500f, 0f)
        assertEquals(argb(150, 150, 150), out[50 * w + 10])
    }

    // --- Overlay compositing -------------------------------------------------

    @Test
    fun heal_existingContentCompositedUnder() {
        val w = 100; val h = 100
        val image = IntArray(w * h) { argb(128, 128, 128) }
        val out = IntArray(w * h) { argb(0, 0, 255) } // opaque blue underneath
        LaplaceHeal.heal(image, out, w, h, 50f, 50f, 10f, 0f, 0f)
        // mask=1 at center -> the healed disc fully replaces the old content.
        assertEquals(argb(128, 128, 128), out[50 * w + 50])
    }

    @Test
    fun heal_bboxScopedOut_writesTranslatedIndex() {
        val image = blemishedFrame(40)
        val out = IntArray(50 * 50)
        LaplaceHeal.heal(
            image, out, 200, 200,
            100f, 100f, 20f, 50f, 0f,
            outOx = 80, outOy = 80, outW = 50, outH = 50,
        )
        // Image (100,100) -> (100-80)*50 + (100-80) = 1020.
        assertEquals(argb(128, 128, 128), out[1020])
        // Image (80,80) is outside the disc -> untouched at index 0.
        assertEquals(0, out[0])
    }

    @Test
    fun heal_isDeterministic() {
        val out1 = IntArray(200 * 200)
        val out2 = IntArray(200 * 200)
        healBlemish(blemishValue = 40, strength = 0.7f, out1)
        healBlemish(blemishValue = 40, strength = 0.7f, out2)
        assertArrayEquals(out1, out2)
    }
}
