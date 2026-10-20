package com.lumora.photoeditor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of [InpaintingCore] — the pure-Kotlin PatchMatch
 * inpainting core behind the Remove tool (and the shared blur/feather
 * math behind Clone-stamp tone fields and remove feathering), the engine
 * the user's "retouch must be excellent" complaints sit on.
 *
 * Pins the observable contract: mask build/threshold rule, fill-bounds
 * box (+ margin clamping), separable box blur (alpha, packed-RGB and
 * float-RGB twins — incl. the roundToInt-unbiased pins), alpha blend
 * rounding, distance-transform Manhattan semantics, patch-distance
 * known-only/edge-wrap safety (the shipped right/bottom-edge fix), and
 * the inpaint loop's guard shape, deterministic fill, real-pixel-only
 * guarantee, graceful all-unknown no-op, and progress-monotonicity.
 *
 * Harness: hidden_files/inpaintingcore-test-harness/run.sh (kotlinc 1.9.23 +
 * JUnit 4.13.2 on the REAL source + this REAL repo test — Gradle's unit-test
 * runner is broken in this env).
 */
class InpaintingCoreTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun frame(w: Int, h: Int, c: Int): IntArray = IntArray(w * h) { c }

    private fun expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    // ---- buildFillMask ----------------------------------------------------

    @Test
    fun `buildFillMask opaque white is fill`() {
        assertTrue(InpaintingCore.buildFillMask(intArrayOf(0xFFFFFFFF.toInt()))[0])
    }

    @Test
    fun `buildFillMask transparent red is fill via red channel`() {
        // Threshold rule is max(alpha, red) > 100: a transparent red stroke
        // (alpha=0) still counts as mask.
        assertTrue(InpaintingCore.buildFillMask(intArrayOf(0x00FF0000))[0])
    }

    @Test
    fun `buildFillMask alpha exactly at threshold is not fill`() {
        assertFalse(InpaintingCore.buildFillMask(intArrayOf(0x64000000))[0])
    }

    @Test
    fun `buildFillMask alpha just above threshold is fill`() {
        assertTrue(InpaintingCore.buildFillMask(intArrayOf(0x65000000))[0])
    }

    @Test
    fun `buildFillMask transparent black is not fill`() {
        assertFalse(InpaintingCore.buildFillMask(intArrayOf(0x00000000))[0])
    }

    @Test
    fun `buildFillMask empty in empty out`() {
        assertEquals(0, InpaintingCore.buildFillMask(IntArray(0)).size)
    }

    // ---- computeFillBounds ------------------------------------------------

    private fun maskPx(w: Int, h: Int, vararg pts: Pair<Int, Int>): IntArray {
        val m = IntArray(w * h)
        for ((x, y) in pts) m[y * w + x] = 0xFFFFFFFF.toInt()
        return m
    }

    @Test
    fun `computeFillBounds single pixel with margin`() {
        val b = InpaintingCore.computeFillBounds(maskPx(10, 10, 3 to 3), 10, 10, 2)
        assertArrayEquals(intArrayOf(1, 1, 6, 6), b)
    }

    @Test
    fun `computeFillBounds clamps margin at top-left edge`() {
        val b = InpaintingCore.computeFillBounds(maskPx(10, 10, 0 to 0), 10, 10, 5)
        assertArrayEquals(intArrayOf(0, 0, 6, 6), b)
    }

    @Test
    fun `computeFillBounds clamps margin at bottom-right edge`() {
        val b = InpaintingCore.computeFillBounds(maskPx(10, 10, 9 to 9), 10, 10, 5)
        assertArrayEquals(intArrayOf(4, 4, 10, 10), b)
    }

    @Test
    fun `computeFillBounds spans two pixels with zero margin`() {
        val b = InpaintingCore.computeFillBounds(maskPx(10, 10, 2 to 2, 5 to 7), 10, 10, 0)
        assertArrayEquals(intArrayOf(2, 2, 6, 8), b)
    }

    @Test
    fun `computeFillBounds empty mask returns null`() {
        assertNull(InpaintingCore.computeFillBounds(IntArray(100), 10, 10, 2))
    }

    @Test
    fun `computeFillBounds size mismatch throws`() {
        expectThrows { InpaintingCore.computeFillBounds(IntArray(10), 10, 10, 2) }
    }

    // ---- boxBlurAlpha -----------------------------------------------------

    @Test
    fun `boxBlurAlpha radius zero is a silent no-op`() {
        // Contract: radius <= 0 returns without touching (or validating).
        val a = floatArrayOf(1f, 2f, 3f)
        InpaintingCore.boxBlurAlpha(a, 999, 999, 0) // wrong dims, no throw
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), a, 0f)
    }

    @Test
    fun `boxBlurAlpha impulse becomes exactly uniform`() {
        // 3x3, center=9, radius=1, edge-clamped: every pixel sees the
        // impulse exactly once -> 9/9 = 1.0f everywhere, total preserved.
        val a = FloatArray(9); a[4] = 9f
        InpaintingCore.boxBlurAlpha(a, 3, 3, 1)
        assertArrayEquals(FloatArray(9) { 1f }, a, 0f)
        assertEquals(9f, a.sum(), 0f)
    }

    @Test
    fun `boxBlurAlpha uniform field stays uniform`() {
        val a = FloatArray(16) { 0.7f }
        InpaintingCore.boxBlurAlpha(a, 4, 4, 2)
        for (v in a) assertEquals(0.7f, v, 1e-6f)
    }

    @Test
    fun `boxBlurAlpha size mismatch throws`() {
        expectThrows { InpaintingCore.boxBlurAlpha(FloatArray(5), 3, 3, 1) }
    }

    // ---- boxBlurRgb / boxBlurRgbFloat ------------------------------------

    @Test
    fun `boxBlurRgb radius zero returns equal distinct copy`() {
        val src = intArrayOf(argb(10, 20, 30), argb(40, 50, 60))
        val out = InpaintingCore.boxBlurRgb(src, 2, 1, 0)
        assertArrayEquals(src, out)
        assertTrue(out !== src)
    }

    @Test
    fun `boxBlurRgb radius zero skips size validation`() {
        val src = intArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(src, InpaintingCore.boxBlurRgb(src, 3, 3, 0))
    }

    @Test
    fun `boxBlurRgb uniform stays uniform at wide radius`() {
        val c = argb(0x7F, 0x40, 0x80)
        val out = InpaintingCore.boxBlurRgb(frame(3, 3, c), 3, 3, 2)
        // Alpha is 0 by the documented 0xRRGGBB contract; RGB is preserved.
        assertArrayEquals(frame(3, 3, c and 0x00FFFFFF), out)
    }

    @Test
    fun `boxBlurRgb impulse rounds up not truncates`() {
        // 3x3, center=9, radius=1, clamped: 114/9 = 12.667 -> roundToInt = 13
        // (0x0D). A toInt truncation would give 0x0C — pins the unbiased
        // rounding the KDoc documents. (Alpha is 0 by the 0xRRGGBB contract.)
        val src = IntArray(9); src[4] = argb(114, 0, 0)
        val out = InpaintingCore.boxBlurRgb(src, 3, 3, 1)
        assertArrayEquals(frame(3, 3, 0x0D shl 16), out)
    }

    @Test
    fun `boxBlurRgb size mismatch throws`() {
        expectThrows { InpaintingCore.boxBlurRgb(IntArray(5), 3, 3, 1) }
    }

    @Test
    fun `boxBlurRgbFloat empty dims return empty without reading`() {
        assertEquals(0, InpaintingCore.boxBlurRgbFloat(IntArray(0), 0, 0, 3).size)
    }

    @Test
    fun `boxBlurRgbFloat radius zero is exact identity`() {
        val src = intArrayOf(argb(114, 37, 200), argb(1, 2, 3))
        val out = InpaintingCore.boxBlurRgbFloat(src, 2, 1, 0)
        assertArrayEquals(
            floatArrayOf(114f, 37f, 200f, 1f, 2f, 3f), out, 0f
        )
    }

    @Test
    fun `boxBlurRgbFloat impulse spreads one ninth`() {
        // r=128 at center, radius=1: every output pixel gets 128/9 = 14.222...
        val src = IntArray(9); src[4] = argb(128, 0, 0)
        val out = InpaintingCore.boxBlurRgbFloat(src, 3, 3, 1)
        for (i in 0 until 9) {
            assertEquals(128f / 9f, out[i * 3], 1e-3f)
            assertEquals(0f, out[i * 3 + 1], 0f)
            assertEquals(0f, out[i * 3 + 2], 0f)
        }
    }

    // ---- blendPixelsAlpha -------------------------------------------------

    @Test
    fun `blendPixelsAlpha zero alpha keeps original`() {
        val o = intArrayOf(argb(10, 20, 30)); val n = intArrayOf(argb(200, 210, 220))
        val out = InpaintingCore.blendPixelsAlpha(o, n, floatArrayOf(0f))
        assertArrayEquals(o, out)
        assertTrue(out !== o)
    }

    @Test
    fun `blendPixelsAlpha full alpha takes new`() {
        val o = intArrayOf(argb(10, 20, 30)); val n = intArrayOf(argb(200, 210, 220))
        assertArrayEquals(n, InpaintingCore.blendPixelsAlpha(o, n, floatArrayOf(1f)))
    }

    @Test
    fun `blendPixelsAlpha coerces out of range alpha`() {
        val o = intArrayOf(argb(10, 20, 30)); val n = intArrayOf(argb(200, 210, 220))
        assertArrayEquals(o, InpaintingCore.blendPixelsAlpha(o, n, floatArrayOf(-0.5f)))
        assertArrayEquals(n, InpaintingCore.blendPixelsAlpha(o, n, floatArrayOf(1.5f)))
    }

    @Test
    fun `blendPixelsAlpha midpoint rounds to 128 not 127`() {
        // 0*0.5 + 255*0.5 = 127.5 -> roundToInt = 128. Truncation would make
        // a faint dark ring on every feather band; this pins the fix.
        val out = InpaintingCore.blendPixelsAlpha(
            intArrayOf(argb(0, 0, 0)), intArrayOf(argb(255, 255, 255)),
            floatArrayOf(0.5f)
        )
        assertEquals(argb(128, 128, 128), out[0])
    }

    @Test
    fun `blendPixelsAlpha quarter alpha rounds half up`() {
        // 255*0.25 = 63.75 -> 64.
        val out = InpaintingCore.blendPixelsAlpha(
            intArrayOf(argb(0, 0, 0)), intArrayOf(argb(255, 255, 255)),
            floatArrayOf(0.25f)
        )
        assertEquals(argb(64, 64, 64), out[0])
    }

    @Test
    fun `blendPixelsAlpha size mismatch throws`() {
        expectThrows {
            InpaintingCore.blendPixelsAlpha(IntArray(4), IntArray(4), FloatArray(3))
        }
    }

    // ---- distanceTransform -----------------------------------------------

    @Test
    fun `distanceTransform known pixels are zero`() {
        val m = BooleanArray(9) // all known
        assertArrayEquals(IntArray(9), InpaintingCore.distanceTransform(m, 3, 3))
    }

    @Test
    fun `distanceTransform manhattan distance to nearest known`() {
        // 5x5, center 3x3 masked, border known: every masked pixel is
        // Manhattan-1 from the border.
        val w = 5; val h = 5
        val m = BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w; x in 1..3 && y in 1..3
        }
        val d = InpaintingCore.distanceTransform(m, w, h)
        for (i in m.indices) {
            val x = i % w; val y = i / w
            // Manhattan distance to the nearest known pixel = distance to
            // the border: the center (2,2) is 2 away, edge-middles are 1.
            val expected = if (m[i]) minOf(x, y, w - 1 - x, h - 1 - y) else 0
            assertEquals("at ($x,$y)", expected, d[i])
        }
    }

    @Test
    fun `distanceTransform single known corner grows by one per step`() {
        val w = 5; val h = 5
        val m = BooleanArray(w * h) { true }.also { it[0] = false }
        val d = InpaintingCore.distanceTransform(m, w, h)
        assertEquals(0, d[0])
        assertEquals(8, d[4 * w + 4]) // (4,4): 4+4
        assertEquals(2, d[1 * w + 1]) // (1,1): 1+1
    }

    // ---- patchDistance ----------------------------------------------------

    @Test
    fun `patchDistance identical patches score zero`() {
        val c = argb(100, 100, 100)
        val img = frame(9, 9, c)
        assertEquals(
            0f,
            InpaintingCore.patchDistance(4, 4, 2, 2, 9, 9, img, BooleanArray(81)),
            0f
        )
    }

    @Test
    fun `patchDistance fully masked target is MAX_VALUE`() {
        val img = frame(9, 9, argb(100, 100, 100))
        assertEquals(
            Float.MAX_VALUE,
            InpaintingCore.patchDistance(4, 4, 2, 2, 9, 9, img, BooleanArray(81) { true }),
            0f
        )
    }

    @Test
    fun `patchDistance pins center weight of single-pixel difference`() {
        // Only the patch center is known (rest masked). dr=dg=db=1 there.
        // Weight at (0,0): 1 - 0/9 = 1 -> (0.5 + 1) -> (1+1+1)*1.5 = 4.5.
        val img = frame(9, 9, argb(100, 100, 100))
        img[4 * 9 + 4] = argb(101, 101, 101)
        val mask = BooleanArray(81) { true }.also { it[4 * 9 + 4] = false }
        assertEquals(
            4.5f,
            InpaintingCore.patchDistance(4, 4, 0, 0, 9, 9, img, mask),
            1e-4f
        )
    }

    @Test
    fun `patchDistance right edge target never reads wrapped pixels`() {
        // 9x9 uniform gray; one red pixel at (1,8). Target patch centered at
        // (8,3): its legitimate window is tpx 4..8, tpy 0..7 — all gray.
        // Under the OLD <size-only guard, tpy=7/tpx=10 wrapped to index 73
        // = pixel (1,8) and compared the red pixel; the fixed version skips
        // out-of-bounds target columns, so the score is exactly 0.
        // (1,8) is deliberately OUTSIDE the source window too (spy 0..7),
        // so a nonzero score could only come from a wrapped target read.
        val gray = argb(100, 100, 100)
        val img = frame(9, 9, gray)
        img[8 * 9 + 1] = argb(255, 0, 0)
        assertEquals(
            0f,
            InpaintingCore.patchDistance(8, 3, 4, 3, 9, 9, img, BooleanArray(81)),
            0f
        )
    }

    @Test
    fun `patchDistance pins exact center-weighted share of one red source pixel`() {
        // Red pixel at (0,1) IS inside the source window (spx 0..8, spy 0..7)
        // for source center (4,3): offset dx=-4, dy=-2 -> |dx|+|dy| = 6 ->
        // weight (0.5 + 1 - 6/9) = 5/6. diff = (155,-100,-100) -> SSD 44025.
        // Score = 44025 * 5/6 / 40 compared pixels = 917.1875 (real
        // arithmetic; float tolerance for the 1/3 rounding).
        val gray = argb(100, 100, 100)
        val img = frame(9, 9, gray)
        img[1 * 9 + 0] = argb(255, 0, 0)
        assertEquals(
            917.1875f,
            InpaintingCore.patchDistance(8, 3, 4, 3, 9, 9, img, BooleanArray(81)),
            0.01f
        )
    }

    @Test
    fun `patchDistance differing patches score huge and symmetric`() {
        // Half-dark / half-light frame: compare a dark patch against a light
        // one (patchDistance works within one image).
        val w = 18
        val img = IntArray(w * 9) { i ->
            if (i % w < 9) argb(0, 0, 0) else argb(255, 255, 255)
        }
        val mask = BooleanArray(w * 9)
        val a = InpaintingCore.patchDistance(4, 4, 13, 4, w, 9, img, mask)
        val b = InpaintingCore.patchDistance(13, 4, 4, 4, w, 9, img, mask)
        assertTrue("dark-vs-light score should be huge, was $a", a > 1e5f)
        assertTrue("light-vs-dark score should be huge, was $b", b > 1e5f)
        assertEquals(a, b, 1e-3f * a)
    }

    // ---- inpaint ----------------------------------------------------------

    @Test
    fun `inpaint size mismatch throws`() {
        expectThrows { InpaintingCore.inpaint(IntArray(100), BooleanArray(90), 10, 10) }
    }

    @Test
    fun `inpaint empty mask returns equal distinct copy`() {
        val px = frame(8, 8, argb(50, 60, 70))
        val out = InpaintingCore.inpaint(px, BooleanArray(64), 8, 8)
        assertArrayEquals(px, out)
        assertTrue(out !== px)
    }

    @Test
    fun `inpaint fills uniform hole with exact background`() {
        // Deterministic Random(42); on a uniform frame every patch score is
        // 0 and every fill copies a real background pixel.
        val bg = argb(128, 128, 128)
        val px = frame(24, 24, bg)
        val toFill = BooleanArray(24 * 24)
        for (y in 10..12) for (x in 10..12) toFill[y * 24 + x] = true
        val out = InpaintingCore.inpaint(px, toFill, 24, 24)
        // Dilation radius 2 -> fill region 8..14 on both axes.
        for (y in 0 until 24) for (x in 0 until 24) {
            val filled = x in 8..14 && y in 8..14
            assertEquals(
                bg, out[y * 24 + x]
            )
            if (!filled) assertEquals(px[y * 24 + x], out[y * 24 + x])
        }
    }

    @Test
    fun `inpaint filled pixels always come from real pixels`() {
        // Two-color frame, hole fully inside the red half: every filled
        // pixel must be exactly red — no speckle, no blue bleed, no
        // synthesized colors. The patch-copy path picks the zero-score red
        // patch; the averageKnownNeighbors fallback averages red-only
        // neighborhoods. Either way the output palette stays exact.
        val red = argb(255, 0, 0); val blue = argb(0, 0, 255)
        val w = 32; val h = 32
        val px = IntArray(w * h) { i -> if (i % w < 16) red else blue }
        val toFill = BooleanArray(w * h)
        for (y in 6 until 26) for (x in 4..7) toFill[y * w + x] = true
        val out = InpaintingCore.inpaint(px, toFill, w, h)
        // Dilation radius 2 -> fill region x 2..9, y 4..28, all inside red.
        // Every filled pixel is a verbatim copy of a real known pixel (the
        // PatchMatch argmin over fully-known patches) or a red-only
        // neighborhood average — never a synthesized color.
        for (y in 4 until 28) for (x in 2..9) {
            val c = out[y * w + x]
            assertTrue(
                "filled pixel at ($x,$y) is synthetic: ${Integer.toHexString(c)}",
                c == red || c == blue
            )
        }
        // The blue half is untouched.
        for (i in px.indices) {
            if (i % w >= 20) assertEquals(px[i], out[i])
        }
    }

    @Test
    fun `inpaint is deterministic across runs`() {
        val red = argb(255, 0, 0); val blue = argb(0, 0, 255)
        val w = 32; val h = 32
        val px = IntArray(w * h) { i -> if (i % w < 16) red else blue }
        val toFill = BooleanArray(w * h)
        for (y in 6 until 26) for (x in 14..17) toFill[y * w + x] = true
        val a = InpaintingCore.inpaint(px, toFill, w, h)
        val b = InpaintingCore.inpaint(px, toFill, w, h)
        assertArrayEquals(a, b)
    }

    @Test
    fun `inpaint with no known patches degrades to a no-op copy`() {
        // 1px known border: no full 9x9 known patch exists (PATCH_RADIUS=4),
        // and dilation covers the border too — averageKnownNeighbors finds
        // nothing, so the output must equal the input exactly.
        val w = 20; val h = 20
        val px = IntArray(w * h) { i -> argb(i % 256, (i * 3) % 256, (i * 7) % 256) }
        val toFill = BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w
            x in 1 until w - 1 && y in 1 until h - 1
        }
        val out = InpaintingCore.inpaint(px, toFill, w, h)
        assertArrayEquals(px, out)
    }

    @Test
    fun `inpaint progress is monotonic and bounded`() {
        val w = 40; val h = 40
        val px = frame(w, h, argb(90, 90, 90))
        val toFill = BooleanArray(w * h)
        for (y in 10 until 30) for (x in 10 until 30) toFill[y * w + x] = true
        val seen = mutableListOf<Float>()
        InpaintingCore.inpaint(px, toFill, w, h) { seen.add(it) }
        assertTrue("expected progress callbacks, got none", seen.isNotEmpty())
        for (v in seen) assertTrue("progress out of [0,1]: $v", v in 0f..1f)
        for (i in 1 until seen.size) {
            assertTrue("progress not monotonic at $i", seen[i] >= seen[i - 1])
        }
    }

    @Test
    fun `constants hold their documented values`() {
        assertEquals(512, InpaintingCore.WORK_MAX)
        assertEquals(0.4f, InpaintingCore.MAX_FILL_FRACTION, 0f)
    }
}
