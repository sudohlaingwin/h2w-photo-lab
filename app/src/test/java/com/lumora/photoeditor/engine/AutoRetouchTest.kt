package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of [AutoRetouch] — the auto-retouch detection
 * layer behind the one-tap Auto Retouch (blemish detection, skin masking,
 * under-eye detection). This is the code the user's "not excellent
 * retouch" complaints sit on; it had ZERO executed verification on its
 * contract (the detection core [BlemishDetect] is pinned separately by
 * BlemishDetectTest; [RetouchIntel] by RetouchIntelTest).
 *
 * Pins the observable contract:
 *  - [AutoRetouch.skinProbability]: YCrCb ellipsoid heuristic — textbook
 *    skin scores high, sky/gray/dark/white score exactly 0, off-center
 *    colors score lower, output always in 0..1, deterministic.
 *  - [AutoRetouch.detectBlemishes]: size guards, no false positives on a
 *    uniform frame, exactly one spot for a synthetic dark blemish,
 *    deterministic.
 *  - [AutoRetouch.skinMask]: byte-exact skinness*255 mapping at feather=0,
 *    zero mask for non-skin, output dims, feather path sanity.
 *  - [AutoRetouch.detectUnderEyes]: size guards, empty when no skin,
 *    empty when no dark band (no false positives), two candidates
 *    (left/right) with sane UV/radius for a synthetic face with dark
 *    bands, deterministic.
 *
 * Runs against a harness Bitmap stub (NOT committed) whose createBitmap /
 * createScaledBitmap / getPixels / setPixels / recycle implement exactly
 * the API surface the engine touches, backed by a plain IntArray.
 */
class AutoRetouchTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun argb(r: Int, g: Int, b: Int): Int =
        (255 shl 24) or (r shl 16) or (g shl 8) or b

    private fun bitmapOf(w: Int, h: Int, fill: (x: Int, y: Int) -> Int): Bitmap {
        val px = IntArray(w * h) { i -> fill(i % w, i / w) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun alphas(bmp: Bitmap): IntArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return IntArray(w * h) { i -> (px[i] ushr 24) and 0xFF }
    }

    // ------------------------------------------------------------------
    // skinProbability: the YCrCb ellipsoid heuristic
    // ------------------------------------------------------------------

    @Test
    fun skinProbability_textbookSkin_scoresHigh() {
        // (200,150,120): Cr~155.4, Cb~104.6 — near the ellipsoid center.
        val p = AutoRetouch.skinProbability(200, 150, 120)
        assertTrue("textbook skin must score > 0.9, got $p", p > 0.9f)
    }

    @Test
    fun skinProbability_blueSky_isZero() {
        assertEquals(0f, AutoRetouch.skinProbability(60, 120, 220))
    }

    @Test
    fun skinProbability_gray_isZero() {
        // Gray rejection: max-min channel spread < 15.
        assertEquals(0f, AutoRetouch.skinProbability(128, 128, 128))
    }

    @Test
    fun skinProbability_dark_isZero() {
        // Brightness floor: Y < 40.
        assertEquals(0f, AutoRetouch.skinProbability(10, 10, 10))
    }

    @Test
    fun skinProbability_white_isZero() {
        // Brightness ceiling: Y > 250 (also gray-rejected).
        assertEquals(0f, AutoRetouch.skinProbability(255, 255, 255))
    }

    @Test
    fun skinProbability_offCenterSkin_scoresLower() {
        // Pushing red up moves Cr away from the 155 center: smooth falloff.
        val center = AutoRetouch.skinProbability(200, 150, 120)
        val shifted = AutoRetouch.skinProbability(240, 150, 120)
        assertTrue(
            "off-center skin ($shifted) must score below center ($center)",
            shifted < center,
        )
        assertTrue("off-center skin must still be positive", shifted > 0f)
    }

    @Test
    fun skinProbability_rangeSweep_staysInUnitInterval() {
        for (r in 0..255 step 17) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 17) {
                    val p = AutoRetouch.skinProbability(r, g, b)
                    assertTrue("out of range at ($r,$g,$b): $p", p in 0f..1f)
                }
            }
        }
    }

    @Test
    fun skinProbability_isDeterministic() {
        assertEquals(
            AutoRetouch.skinProbability(200, 150, 120),
            AutoRetouch.skinProbability(200, 150, 120),
        )
    }

    // ------------------------------------------------------------------
    // detectBlemishes: Bitmap wrapper around BlemishDetect
    // ------------------------------------------------------------------

    @Test
    fun detectBlemishes_tooNarrow_returnsEmpty() {
        val bmp = bitmapOf(16, 64) { _, _ -> argb(128, 128, 128) }
        assertTrue(AutoRetouch.detectBlemishes(bmp).isEmpty())
    }

    @Test
    fun detectBlemishes_tooShort_returnsEmpty() {
        val bmp = bitmapOf(64, 16) { _, _ -> argb(128, 128, 128) }
        assertTrue(AutoRetouch.detectBlemishes(bmp).isEmpty())
    }

    @Test
    fun detectBlemishes_uniformGray_noFalsePositives() {
        val bmp = bitmapOf(64, 64) { _, _ -> argb(128, 128, 128) }
        assertTrue(AutoRetouch.detectBlemishes(bmp).isEmpty())
    }

    @Test
    fun detectBlemishes_darkSpot_exactlyOneSpot() {
        // Dark disc (contrast 30 — inside BlemishDetect's healable band;
        // contrast 90+ is treated as structure, not a blemish).
        val bmp = bitmapOf(64, 64) { x, y ->
            val dx = x - 32; val dy = y - 32
            if (dx * dx + dy * dy <= 25) argb(98, 98, 98) else argb(128, 128, 128)
        }
        val spots = AutoRetouch.detectBlemishes(bmp)
        assertEquals(1, spots.size)
        val s = spots[0]
        // The detector reports the scan-grid extremum, so the UV is grid-
        // quantized: the contract is that the reported spot overlaps the
        // actual blemish (disc radius 5/64 ~= 0.078 UV), not that it lands
        // on the exact disc center.
        val dist = kotlin.math.hypot(s.x - 0.5f, s.y - 0.5f)
        assertTrue("spot at (${s.x},${s.y}) must overlap the disc", dist < 0.08f)
        assertTrue("radius=${s.radius}", s.radius > 0f && s.radius < 0.15f)
    }

    @Test
    fun detectBlemishes_isDeterministic() {
        val bmp = bitmapOf(64, 64) { x, y ->
            val dx = x - 32; val dy = y - 32
            if (dx * dx + dy * dy <= 25) argb(98, 98, 98) else argb(128, 128, 128)
        }
        val a = AutoRetouch.detectBlemishes(bmp)
        val b = AutoRetouch.detectBlemishes(bmp)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].x, b[i].x)
            assertEquals(a[i].y, b[i].y)
            assertEquals(a[i].radius, b[i].radius)
        }
    }

    // ------------------------------------------------------------------
    // skinMask: skinness*255 byte mapping
    // ------------------------------------------------------------------

    @Test
    fun skinMask_allSkin_allHighAlpha() {
        val bmp = bitmapOf(64, 64) { _, _ -> argb(200, 150, 120) }
        val mask = AutoRetouch.skinMask(bmp, feather = 0f)
        assertEquals(64, mask.width)
        assertEquals(64, mask.height)
        val expected = (AutoRetouch.skinProbability(200, 150, 120) * 255).toInt()
        assertTrue("expected alpha $expected > 200", expected > 200)
        val a = alphas(mask)
        assertTrue(a.all { it == expected })
    }

    @Test
    fun skinMask_allBlue_allZero() {
        val bmp = bitmapOf(64, 64) { _, _ -> argb(60, 120, 220) }
        val mask = AutoRetouch.skinMask(bmp, feather = 0f)
        assertTrue(alphas(mask).all { it == 0 })
    }

    @Test
    fun skinMask_halfSkin_halfBlue_splitMask() {
        val bmp = bitmapOf(64, 64) { _, y ->
            if (y < 32) argb(200, 150, 120) else argb(60, 120, 220)
        }
        val mask = AutoRetouch.skinMask(bmp, feather = 0f)
        val a = alphas(mask)
        val top = IntArray(32 * 64) { i -> a[i] }
        val bottom = IntArray(32 * 64) { i -> a[32 * 64 + i] }
        assertTrue(top.all { it > 200 })
        assertTrue(bottom.all { it == 0 })
    }

    @Test
    fun skinMask_feathered_keepsDimsAndRange() {
        val bmp = bitmapOf(64, 64) { _, y ->
            if (y < 32) argb(200, 150, 120) else argb(60, 120, 220)
        }
        val mask = AutoRetouch.skinMask(bmp) // default feather 0.5
        assertEquals(64, mask.width)
        assertEquals(64, mask.height)
        assertTrue(alphas(mask).all { it in 0..255 })
    }

    // ------------------------------------------------------------------
    // detectUnderEyes: dark-band-in-skin detection
    // ------------------------------------------------------------------

    /** 128x128 synthetic face: skin rect + darker under-eye band. */
    private fun syntheticFace(): Bitmap = bitmapOf(128, 128) { x, y ->
        val inFace = x in 32..96 && y in 16..80
        if (!inFace) return@bitmapOf argb(20, 30, 60)
        if (y in 44..47) argb(140, 105, 84) else argb(200, 150, 120)
    }

    @Test
    fun detectUnderEyes_tooSmall_returnsEmpty() {
        val bmp = bitmapOf(32, 32) { _, _ -> argb(200, 150, 120) }
        assertTrue(AutoRetouch.detectUnderEyes(bmp).isEmpty())
    }

    @Test
    fun detectUnderEyes_noSkin_returnsEmpty() {
        val bmp = bitmapOf(128, 128) { _, _ -> argb(60, 120, 220) }
        assertTrue(AutoRetouch.detectUnderEyes(bmp).isEmpty())
    }

    @Test
    fun detectUnderEyes_uniformSkin_noFalsePositives() {
        val bmp = bitmapOf(128, 128) { x, y ->
            if (x in 32..96 && y in 16..80) argb(200, 150, 120)
            else argb(20, 30, 60)
        }
        assertTrue(AutoRetouch.detectUnderEyes(bmp).isEmpty())
    }

    @Test
    fun detectUnderEyes_syntheticFace_findsLeftAndRight() {
        val eyes = AutoRetouch.detectUnderEyes(syntheticFace())
        assertEquals(2, eyes.size)
        val xs = eyes.map { it.x }.sorted()
        assertTrue("left cx=${xs[0]}", kotlin.math.abs(xs[0] - 0.375f) < 0.05f)
        assertTrue("right cx=${xs[1]}", kotlin.math.abs(xs[1] - 0.625f) < 0.05f)
        for (e in eyes) {
            assertTrue("cy=${e.y}", e.y > 0.3f && e.y < 0.45f)
            assertTrue("radius=${e.radius}", e.radius in 0.02f..0.08f)
        }
    }

    @Test
    fun detectUnderEyes_isDeterministic() {
        val a = AutoRetouch.detectUnderEyes(syntheticFace())
        val b = AutoRetouch.detectUnderEyes(syntheticFace())
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].x, b[i].x)
            assertEquals(a[i].y, b[i].y)
            assertEquals(a[i].radius, b[i].radius)
        }
    }
}
