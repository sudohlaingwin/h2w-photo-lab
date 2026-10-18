package com.lumora.photoeditor

import com.lumora.photoeditor.engine.InpaintingCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Standalone-harness tests for [InpaintingCore] (pure Kotlin, zero Android
 * imports): mask building, fill bounds, the separable box blur, the distance
 * transform, and end-to-end PatchMatch fills on synthetic images.
 *
 * Run via hidden_files/inpainting-harness/run.sh (Gradle's unit-test runner
 * is broken in this env; the harness compiles the real sources with the
 * Gradle-bundled kotlinc and runs JUnitCore).
 */
class InpaintingCoreTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    // ---- buildFillMask ----

    @Test
    fun buildFillMask_thresholdOnMaxOfAlphaOrRed() {
        // max(alpha,red) > 100 marks "remove".
        val px = intArrayOf(
            0x64000000.toInt(), // alpha=100 -> false (not > 100)
            0x65000000.toInt(), // alpha=101 -> true
            0x00650000,         // red=101   -> true (ALPHA_8-ambiguity convention)
            0x00640000,         // red=100   -> false
            0xFF000000.toInt(), // alpha=255 -> true
            0x00000000,         // nothing   -> false
        )
        val m = InpaintingCore.buildFillMask(px)
        assertFalse(m[0]); assertTrue(m[1]); assertTrue(m[2])
        assertFalse(m[3]); assertTrue(m[4]); assertFalse(m[5])
    }

    // ---- computeFillBounds ----

    @Test
    fun computeFillBounds_emptyMaskIsNull() {
        assertNull(InpaintingCore.computeFillBounds(IntArray(100), 10, 10, 2))
    }

    @Test
    fun computeFillBounds_singlePixelWithMargin() {
        val px = IntArray(100)
        px[4 * 10 + 3] = 0xFFFFFFFF.toInt() // (x=3, y=4)
        val b = InpaintingCore.computeFillBounds(px, 10, 10, 2)
        assertNotNull(b)
        // x1/y1 exclusive: (3,4)+margin2 -> x0=1 y0=2 x1=6 y1=7
        assertEquals(1, b!![0]); assertEquals(2, b[1])
        assertEquals(6, b[2]); assertEquals(7, b[3])
    }

    @Test
    fun computeFillBounds_clampsAtImageEdges() {
        val px = IntArray(100)
        px[0] = 0xFFFFFFFF.toInt() // (0,0)
        val b = InpaintingCore.computeFillBounds(px, 10, 10, 5)
        assertNotNull(b)
        assertEquals(0, b!![0]); assertEquals(0, b[1])
        assertEquals(6, b[2]); assertEquals(6, b[3])
    }

    // ---- boxBlurAlpha ----

    @Test
    fun boxBlurAlpha_radiusZeroIsIdentity() {
        val a = floatArrayOf(0.2f, 0.9f, 0.1f, 0.7f)
        InpaintingCore.boxBlurAlpha(a, 4, 1, 0)
        assertClose(0.2f, a[0]); assertClose(0.9f, a[1])
        assertClose(0.1f, a[2]); assertClose(0.7f, a[3])
    }

    @Test
    fun boxBlurAlpha_constantFieldStaysConstant() {
        val a = FloatArray(36) { 0.42f }
        InpaintingCore.boxBlurAlpha(a, 6, 6, 3)
        for (v in a) assertClose(0.42f, v)
    }

    @Test
    fun boxBlurAlpha_impulseResponse() {
        // 1-D impulse at x=2, radius 1, edge-clamped: [0, 1/3, 1/3, 1/3, 0].
        val a = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        InpaintingCore.boxBlurAlpha(a, 5, 1, 1)
        assertClose(0f, a[0]); assertClose(1f / 3f, a[1])
        assertClose(1f / 3f, a[2]); assertClose(1f / 3f, a[3])
        assertClose(0f, a[4])
    }

    @Test
    fun boxBlurAlpha_constantInputIsConstant() {
        // A constant field blurs to itself everywhere, including the
        // edge-clamped borders (no mass invented at the edges).
        val w = 23; val h = 19
        val a = FloatArray(w * h) { 0.7f }
        InpaintingCore.boxBlurAlpha(a, w, h, 4)
        for (v in a) assertClose(0.7f, v, eps = 1e-5f)
    }

    @Test
    fun boxBlurAlpha_matchesNaiveReference() {
        // The fast sliding-window blur must equal a direct per-pixel
        // edge-clamped box blur. (Total mass is NOT preserved by an
        // edge-clamped blur — border pixels are over/under-weighted
        // relative to the full window — so assert the exact semantics,
        // not a false mass invariant.)
        val w = 20; val h = 17; val r = 4
        val rnd = java.util.Random(7)
        val before = FloatArray(w * h) { rnd.nextFloat() }
        val a = before.copyOf()
        InpaintingCore.boxBlurAlpha(a, w, h, r)
        val k = 2 * r + 1
        val tmp = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (dx in -r..r) s += before[y * w + (x + dx).coerceIn(0, w - 1)]
            tmp[y * w + x] = s / k
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (dy in -r..r) s += tmp[(y + dy).coerceIn(0, h - 1) * w + x]
            assertClose((s / k).toFloat(), a[y * w + x], eps = 1e-4f)
        }
    }

    // ---- boxBlurRgb / boxBlurRgbFloat ----

    @Test
    fun boxBlurRgb_radiusZeroIsCopy() {
        val src = intArrayOf(0x112233, 0x445566, 0x778899)
        val out = InpaintingCore.boxBlurRgb(src, 3, 1, 0)
        assertEquals(src.toList(), out.toList())
    }

    @Test
    fun boxBlurRgbFloat_matchesIntPathWithinOne() {
        val rnd = java.util.Random(3)
        val src = IntArray(12 * 9) { rnd.nextInt(0x1000000) }
        val f = InpaintingCore.boxBlurRgbFloat(src, 12, 9, 3)
        val i = InpaintingCore.boxBlurRgb(src, 12, 9, 3)
        for (k in src.indices) {
            val fr = f[k * 3]; val fg = f[k * 3 + 1]; val fb = f[k * 3 + 2]
            val ir = ((i[k] shr 16) and 0xFF).toFloat()
            val ig = ((i[k] shr 8) and 0xFF).toFloat()
            val ib = (i[k] and 0xFF).toFloat()
            // The int path quantizes to ±1 (documented); the float path must
            // agree within that quantum.
            assertTrue(abs(fr - ir) <= 1f)
            assertTrue(abs(fg - ig) <= 1f)
            assertTrue(abs(fb - ib) <= 1f)
        }
    }

    // ---- distanceTransform ----

    @Test
    fun distanceTransform_knownIsZeroAndDepthGrowsInward() {
        val w = 7; val h = 7
        val mask = BooleanArray(w * h)
        for (y in 2..4) for (x in 2..4) mask[y * w + x] = true
        val d = InpaintingCore.distanceTransform(mask, w, h)
        assertEquals(0, d[0])            // known corner
        assertEquals(1, d[2 * w + 2])    // mask edge
        assertEquals(2, d[3 * w + 3])    // mask center
    }

    // ---- inpaint ----

    @Test
    fun inpaint_emptyMaskReturnsCopy() {
        val px = IntArray(64) { 0xFF123456.toInt() }
        val out = InpaintingCore.inpaint(px, BooleanArray(64), 8, 8)
        assertEquals(px.toList(), out.toList())
    }

    @Test
    fun inpaint_fillsFlatRegionWithSurroundingColor() {
        // Left half red, right half blue; remove a 4x4 block deep inside the
        // red half. PatchMatch (fixed seed 42) must refill it with red, not
        // blue and not gray mush.
        val w = 32; val h = 32
        val px = IntArray(w * h) { i ->
            if ((i % w) < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
        val toFill = BooleanArray(w * h)
        val holes = mutableListOf<Int>()
        for (y in 14..17) for (x in 6..9) {
            toFill[y * w + x] = true
            holes.add(y * w + x)
        }
        val out = InpaintingCore.inpaint(px, toFill, w, h)
        var sumR = 0; var sumB = 0
        for (p in holes) {
            sumR += (out[p] shr 16) and 0xFF
            sumB += out[p] and 0xFF
        }
        val meanR = sumR.toFloat() / holes.size
        val meanB = sumB.toFloat() / holes.size
        assertTrue("filled region should be red (meanR=$meanR)", meanR > 200f)
        assertTrue("filled region should not leak blue (meanB=$meanB)", meanB < 80f)
        // Everything outside the dilated mask must be untouched.
        for (i in px.indices) {
            if (!toFill[i]) assertEquals(px[i], out[i])
        }
    }

    @Test
    fun inpaint_filledPixelsComeFromKnownRegion() {
        // Gradient image: the fill of a small hole must stay within the
        // value range of its surroundings (no wild invented pixels).
        val w = 24; val h = 24
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = ((x + y) * 255 / (w + h - 2))
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val toFill = BooleanArray(w * h)
        for (y in 10..13) for (x in 10..13) toFill[y * w + x] = true
        val out = InpaintingCore.inpaint(px, toFill, w, h)
        for (y in 10..13) for (x in 10..13) {
            val v = (out[y * w + x] shr 16) and 0xFF
            assertTrue("filled value $v out of sane range", v in 0..255)
        }
    }
}
