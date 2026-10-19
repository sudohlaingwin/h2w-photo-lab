package com.lumora.photoeditor.engine

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

/**
 * First-ever executed verification of [HdrMerge], the deterministic
 * Mertens-style exposure-fusion HDR merge (pro-spec Phase 4).
 *
 * Strategy: every public entry point is pure, so the tests pin the
 * OBSERVABLE contract — geometry caps, guard failures, copy semantics,
 * and fusion behavior that falls straight out of the documented weight
 * model (contrast × saturation × well-exposedness × clip-softening).
 * Private machinery (weight maps, pyramids, blur, boxDown) is exercised
 * through [HdrMerge.fuse]; no white-box introspection.
 */
class HdrMergeTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun ch(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun solid(w: Int, h: Int, r: Float, g: Float, b: Float, alpha: Int = 0xFF): IntArray =
        IntArray(w * h) { (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b) }

    private fun gray(w: Int, h: Int, v: Float, alpha: Int = 0xFF): IntArray = solid(w, h, v, v, v, alpha)

    /** Gray frame from a raw 8-bit channel value — for tests that need
     *  exact 8-bit symmetry (e.g. 230 and 25 are symmetric about 127.5,
     *  while gray(0.9f)/gray(0.1f) are NOT: they quantize to 230 and 26). */
    private fun gray8(w: Int, h: Int, v: Int): IntArray =
        IntArray(w * h) { (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    private fun lumaOf(px: Int): Float {
        val r = ((px shr 16) and 0xFF) / 255f
        val g = ((px shr 8) and 0xFF) / 255f
        val b = (px and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun meanLuma(px: IntArray): Float = px.fold(0f) { a, c -> a + lumaOf(c) } / px.size

    private fun maxChanErr(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            m = maxOf(
                m,
                abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)),
                abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)),
                abs((x and 0xFF) - (y and 0xFF)),
            )
        }
        return m
    }

    // ------------------------------------------------------------------
    // workingSize geometry
    // ------------------------------------------------------------------

    @Test fun constantsAreDocumentedValues() {
        assertEquals(1280, HdrMerge.DEFAULT_MAX_DIM)
        assertEquals(4, HdrMerge.DEFAULT_LEVELS)
    }

    @Test fun workingSizeNoScaleWhenUnderCap() {
        assertEquals(800 to 600, HdrMerge.workingSize(800, 600))
        assertEquals(1280 to 1280, HdrMerge.workingSize(1280, 1280))
        assertEquals(400 to 300, HdrMerge.workingSize(400, 300, maxDim = 5000))
    }

    @Test fun workingSizeDownscalesLongerSidePreservingAspect() {
        assertEquals(1280 to 640, HdrMerge.workingSize(2000, 1000))
        assertEquals(640 to 1280, HdrMerge.workingSize(1000, 2000))
    }

    @Test fun workingSizeJustOverCap() {
        // 1280/1281 scaling: w -> 1280, h -> 1279 (integer truncation)
        assertEquals(1280 to 1279, HdrMerge.workingSize(1281, 1280))
    }

    @Test fun workingSizeDegenerateCapReturnsOriginal() {
        assertEquals(2000 to 1000, HdrMerge.workingSize(2000, 1000, maxDim = 0))
        assertEquals(2000 to 1000, HdrMerge.workingSize(2000, 1000, maxDim = -5))
    }

    // ------------------------------------------------------------------
    // fuse guards
    // ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun fuseEmptyFrameListThrows() {
        HdrMerge.fuse(emptyList(), 8, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fuseMismatchedFrameSizesThrow() {
        HdrMerge.fuse(listOf(gray(8, 8, 0.5f), gray(8, 7, 0.5f)), 8, 8)
    }

    // ------------------------------------------------------------------
    // fuse single-frame semantics
    // ------------------------------------------------------------------

    @Test fun fuseSingleFrameReturnsBitExactCopyNotAlias() {
        val f = gray(16, 16, 0.4f)
        val out = HdrMerge.fuse(listOf(f), 16, 16)
        assertTrue(out.contentEquals(f))
        assertNotSame(f, out)
    }

    @Test fun fuseSingleFrameDoesNotDownscale() {
        // Documented: single frame returns a copy unchanged (full res),
        // even when maxDim would cap a multi-frame merge.
        val f = gray(16, 16, 0.4f)
        val out = HdrMerge.fuse(listOf(f), 16, 16, maxDim = 8)
        assertEquals(256, out.size)
        assertTrue(out.contentEquals(f))
    }

    // ------------------------------------------------------------------
    // fuse identity / symmetry
    // ------------------------------------------------------------------

    @Test fun fuseIdenticalFramesReconstructsTheFrame() {
        val f = gray(32, 32, 0.5f)
        val out = HdrMerge.fuse(listOf(f, f), 32, 32)
        assertEquals(1024, out.size)
        // Weights are uniform, so collapse(weight * laplacian) rebuilds the
        // frame; only float error + 8-bit rounding remain.
        assertTrue("max channel error ${maxChanErr(f, out)} too large", maxChanErr(f, out) <= 2)
    }

    @Test fun fuseSymmetricBracketsFusesToTheMiddle() {
        // 230 and 25 are symmetric about 127.5 in 8-bit, so per-pixel
        // well-exposedness, saturation, contrast and clipSoft are identical
        // for both frames: weights are 0.5/0.5 and the composite is the
        // arithmetic mean = 0.5. (gray(0.9f)/gray(0.1f) would NOT work here:
        // they quantize to 230 and 26, asymmetric about 0.5, so the dark
        // frame legitimately earns ~12% more well-exposedness weight.)
        val hi = gray8(24, 24, 230)
        val lo = gray8(24, 24, 25)
        val out = HdrMerge.fuse(listOf(hi, lo), 24, 24)
        val center = out[12 * 24 + 12]
        assertEquals(0.5f, lumaOf(center), 0.01f)
        assertEquals(0.5f, meanLuma(out), 0.01f)
        // Order invariance: swapping bracket order must not change the
        // composite (weights normalize per pixel; accumulation commutes).
        assertTrue(out.contentEquals(HdrMerge.fuse(listOf(lo, hi), 24, 24)))
    }

    // ------------------------------------------------------------------
    // The weight model doing its documented job
    // ------------------------------------------------------------------

    @Test fun fuseWellExposedFrameDominates() {
        // Mid gray (well-exposedness ~1) vs near-black (~0.056): the mid
        // frame must carry the composite, matching the KDoc's
        // "well-exposedness" measure.
        val mid = gray(24, 24, 0.5f)
        val dark = gray(24, 24, 0.02f)
        val out = HdrMerge.fuse(listOf(mid, dark), 24, 24)
        assertEquals(0.5f, meanLuma(out), 0.05f)
    }

    @Test fun fuseClippedWhiteFrameLosesToWellExposed() {
        // Pure white is penalized twice: near-zero well-exposedness AND
        // the 0.2 clipSoft factor. Pins the KDoc's "weight shifts to the
        // unclipped frames" contract.
        val white = gray(24, 24, 1.0f)
        val mid = gray(24, 24, 0.5f)
        val out = HdrMerge.fuse(listOf(white, mid), 24, 24)
        assertEquals(0.5f, meanLuma(out), 0.03f)
    }

    @Test fun fuseClippedBlackFrameLosesToWellExposed() {
        val black = gray(24, 24, 0.0f)
        val mid = gray(24, 24, 0.5f)
        val out = HdrMerge.fuse(listOf(black, mid), 24, 24)
        assertEquals(0.5f, meanLuma(out), 0.03f)
    }

    @Test fun fuseSaturatedFrameBeatsFlatGray() {
        // Same mean luma region; the warm color's saturation weight
        // (~0.19) dwarfs gray's (~0.001) while well-exposedness stays sane.
        val warm = solid(24, 24, 0.7f, 0.3f, 0.3f)
        val grayFrame = gray(24, 24, 0.5f)
        val out = HdrMerge.fuse(listOf(warm, grayFrame), 24, 24)
        val center = out[12 * 24 + 12]
        val r = ((center shr 16) and 0xFF) / 255f
        val g = ((center shr 8) and 0xFF) / 255f
        assertTrue("saturated frame should dominate, r-g=$r-$g", r - g > 0.15f)
    }

    @Test fun fuseThreeFrameBracketsCenterOnMiddleExposure() {
        val lo = gray(24, 24, 0.1f)
        val mid = gray(24, 24, 0.5f)
        val hi = gray(24, 24, 0.9f)
        val out = HdrMerge.fuse(listOf(lo, mid, hi), 24, 24)
        assertEquals(0.5f, meanLuma(out), 0.04f)
    }

    // ------------------------------------------------------------------
    // Geometry / conversion invariants
    // ------------------------------------------------------------------

    @Test fun fuseOutputIsAlwaysOpaque() {
        // f3ToArgb forces alpha 0xFF; input alpha must never leak through.
        val a = gray(16, 16, 0.5f, alpha = 0x80)
        val b = gray(16, 16, 0.3f, alpha = 0x00)
        val out = HdrMerge.fuse(listOf(a, b), 16, 16)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test fun fuseDownscalesToWorkingResolution() {
        val a = gray(16, 16, 0.5f)
        val b = gray(16, 16, 0.5f)
        val out = HdrMerge.fuse(listOf(a, b), 16, 16, maxDim = 8)
        assertEquals(8 * 8, out.size)
        assertEquals(0.5f, meanLuma(out), 0.05f)
    }

    @Test fun fuseBoxDownPreservesHalvesOnExactRatio() {
        // 8x8 half-black/half-white -> 4x4; identical frames so the fused
        // value must equal the box-down value regardless of weights.
        val w = 8; val h = 8
        val frame = IntArray(w * h) { i -> if ((i % w) < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val out = HdrMerge.fuse(listOf(frame, frame.copyOf()), w, h, maxDim = 4)
        assertEquals(16, out.size)
        // Column 0 (of the 4x4) is pure black, column 3 pure white.
        assertTrue("left col leaked: ${lumaOf(out[0])}", lumaOf(out[0]) < 0.05f)
        assertTrue("right col leaked: ${lumaOf(out[3])}", lumaOf(out[3]) > 0.95f)
    }

    @Test fun fuseHandlesOddSizesAndLevels() {
        val a = gray(7, 5, 0.5f)
        val b = gray(7, 5, 0.4f)
        val l1 = HdrMerge.fuse(listOf(a, b), 7, 5, levels = 1)
        val l4 = HdrMerge.fuse(listOf(a, b), 7, 5, levels = 4)
        assertEquals(35, l1.size)
        assertEquals(35, l4.size)
        // 3x3: pyramids bottom out at 1x1 (pyrSize 3->2->1->1)
        val t1 = gray(3, 3, 0.5f); val t2 = gray(3, 3, 0.4f)
        assertEquals(9, HdrMerge.fuse(listOf(t1, t2), 3, 3, levels = 4).size)
    }

    @Test fun fuseHandlesSinglePixelFrames() {
        val a = gray(1, 1, 0.5f); val b = gray(1, 1, 0.5f)
        val out = HdrMerge.fuse(listOf(a, b), 1, 1, levels = 4)
        assertEquals(1, out.size)
        assertEquals(0.5f, lumaOf(out[0]), 0.05f)
    }

    @Test fun fuseLevelsBelowOneCoercesToOne() {
        val a = gray(16, 16, 0.5f); val b = gray(16, 16, 0.5f)
        val out = HdrMerge.fuse(listOf(a, b), 16, 16, levels = 0)
        assertEquals(256, out.size)
        assertEquals(0.5f, meanLuma(out), 0.03f)
    }

    // ------------------------------------------------------------------
    // Purity invariants
    // ------------------------------------------------------------------

    @Test fun fuseDoesNotMutateInputFrames() {
        val a = solid(24, 24, 0.7f, 0.2f, 0.3f)
        val b = solid(24, 24, 0.1f, 0.4f, 0.8f)
        val aCopy = a.copyOf(); val bCopy = b.copyOf()
        HdrMerge.fuse(listOf(a, b), 24, 24)
        assertTrue(a.contentEquals(aCopy))
        assertTrue(b.contentEquals(bCopy))
    }

    @Test fun fuseDoesNotAliasInputs() {
        val a = gray(16, 16, 0.5f); val b = gray(16, 16, 0.3f)
        val out = HdrMerge.fuse(listOf(a, b), 16, 16)
        assertNotSame(a, out)
        assertNotSame(b, out)
    }

    @Test fun fuseIsDeterministic() {
        val a = solid(24, 24, 0.7f, 0.2f, 0.3f)
        val b = solid(24, 24, 0.1f, 0.4f, 0.8f)
        assertTrue(HdrMerge.fuse(listOf(a, b), 24, 24)
            .contentEquals(HdrMerge.fuse(listOf(a, b), 24, 24)))
    }
}
