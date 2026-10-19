package com.lumora.photoeditor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever executed verification of [FocusStack], the deterministic
 * modified-Laplacian + softmax + Laplacian-pyramid focus stacker behind
 * pro-spec Phase 4. Pins the load-bearing contracts:
 *
 *  1. [FocusStack.focusMeasure] really is the modified Laplacian
 *     (|2L-L(x-1)-L(x+1)| + |2L-L(y-1)-L(y+1)|, replication at edges) —
 *     verified against hand-computed 3x3 values, not just "nonzero".
 *  2. [FocusStack.stack] guard contract (empty list / size mismatch throw;
 *     single frame returns an equal-but-distinct copy).
 *  3. [FocusStack.workingSize] capping semantics.
 *  4. Identity contracts: stacking copies of the same constant frame is
 *     bit-exact (pyramid + softmax of equal weights reconstructs the
 *     constant); a smooth gradient survives with bounded error.
 *  5. The money test: a two-frame stack whose sharp halves are painted in
 *     different colors yields the sharp color in each half — the modified
 *     Laplacian must actually select the in-focus frame per pixel.
 */
class FocusStackTest {

    companion object {
        private const val R = 0xFFFF0000.toInt()
        private const val G = 0xFF00FF00.toInt()
        private const val B = 0xFF0000FF.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val GRAY = 0xFF808080.toInt()
    }

    private fun fill(w: Int, h: Int, color: Int) = IntArray(w * h) { color }

    private fun checker(w: Int, h: Int, c1: Int, c2: Int, cell: Int = 2): IntArray =
        IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (((x / cell) + (y / cell)) % 2 == 0) c1 else c2
        }

    // ------------------------------------------------------------------
    // workingSize
    // ------------------------------------------------------------------

    @Test
    fun workingSize_unchangedWhenUnderCap() {
        assertEquals(640 to 480, FocusStack.workingSize(640, 480, 1280))
    }

    @Test
    fun workingSize_capsLongSideAndKeepsAspect() {
        val (w, h) = FocusStack.workingSize(4000, 3000, 1280)
        assertEquals(1280, w)
        assertEquals(960, h)
    }

    @Test
    fun workingSize_capsPortraitByHeight() {
        val (w, h) = FocusStack.workingSize(3000, 4000, 1280)
        assertEquals(960, w)
        assertEquals(1280, h)
    }

    @Test
    fun workingSize_nonPositiveMaxDimReturnsOriginal() {
        assertEquals(4000 to 3000, FocusStack.workingSize(4000, 3000, 0))
        assertEquals(4000 to 3000, FocusStack.workingSize(4000, 3000, -5))
    }

    @Test
    fun workingSize_extremeDownscaleNeverBelowOne() {
        val (w, h) = FocusStack.workingSize(4000, 1, 2)
        assertTrue(w >= 1 && h >= 1)
    }

    // ------------------------------------------------------------------
    // stack guards
    // ------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun stack_emptyFrameListThrows() {
        FocusStack.stack(emptyList(), 8, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stack_sizeMismatchThrows() {
        FocusStack.stack(listOf(fill(8, 8, R), fill(9, 9, B)), 8, 8)
    }

    @Test
    fun stack_singleFrameReturnsEqualCopyNotSameRef() {
        val src = fill(8, 8, G)
        val out = FocusStack.stack(listOf(src), 8, 8)
        assertArrayEquals(src, out)
        assertTrue(out !== src)
    }

    @Test
    fun stack_outputSizeMatchesWorkingSize() {
        val frames = listOf(fill(2000, 1000, R), fill(2000, 1000, B))
        val out = FocusStack.stack(frames, 2000, 1000, levels = 2)
        val (dw, dh) = FocusStack.workingSize(2000, 1000)
        assertEquals(dw * dh, out.size)
    }

    // ------------------------------------------------------------------
    // focusMeasure: hand-computed 3x3 pin
    //
    // 3x3, center white, rest black. Luminance L: center 1, elsewhere 0
    // (replication at edges). Modified Laplacian per pixel:
    //   center (1,1): |2*1 - 0 - 0| + |2*1 - 0 - 0| = 4
    //   edge-mid (1,0): |2*0 - 0 - 0| + |2*0 - 0 - 1| = 1
    //   corner (0,0): |2*0 - 0 - 0| + |2*0 - 0 - 0| = 0
    // ------------------------------------------------------------------

    @Test
    fun focusMeasure_singleBrightPixelHandComputed() {
        val px = fill(3, 3, BLACK)
        px[1 * 3 + 1] = WHITE
        val m = FocusStack.focusMeasure(px, 3, 3)
        assertEquals(0f, m[0], 0f)                    // corner
        assertEquals(1f, m[1 * 3 + 1 - 3], 1e-6f)     // (1,0) top-mid
        assertEquals(4f, m[1 * 3 + 1], 1e-6f)         // center
    }

    @Test
    fun focusMeasure_flatFrameIsAllZero() {
        val m = FocusStack.focusMeasure(fill(16, 16, GRAY), 16, 16)
        assertTrue(m.all { it == 0f })
    }

    @Test
    fun focusMeasure_edgesReplicationNoCrashAt1x1() {
        val m = FocusStack.focusMeasure(intArrayOf(WHITE), 1, 1)
        assertEquals(1, m.size)
        assertEquals(0f, m[0], 0f) // all neighbours replicate the pixel itself
    }

    @Test
    fun focusMeasure_checkerBeatsFlatEverywhereInterior() {
        val sharp = checker(32, 32, WHITE, BLACK, cell = 2)
        val flat = fill(32, 32, GRAY)
        val ms = FocusStack.focusMeasure(sharp, 32, 32)
        val mf = FocusStack.focusMeasure(flat, 32, 32)
        // Interior of the checker: 24x24 window far from frame edges
        var minSharp = Float.MAX_VALUE
        for (y in 4 until 28) for (x in 4 until 28) {
            minSharp = minOf(minSharp, ms[y * 32 + x])
        }
        assertTrue("checker interior min measure $minSharp should exceed flat max ${mf.max()}", minSharp > mf.max())
    }

    @Test
    fun focusMeasure_nonNegativeAndBounded() {
        // Modified Laplacian on 0..1 luminance cannot exceed 4 per axis pair.
        val px = IntArray(16 * 16) { (it * 2654435761L).toInt() }
        val m = FocusStack.focusMeasure(px, 16, 16)
        assertTrue(m.all { it >= 0f && it <= 8.0001f })
    }

    // ------------------------------------------------------------------
    // Identity / fidelity contracts
    // ------------------------------------------------------------------

    @Test
    fun stack_identicalConstantFramesAreBitExact() {
        // Two frames -> softmax weights are exactly 0.5 each (1/3 would not
        // be), so the pyramid blend of a constant reconstructs it exactly.
        val frames = List(2) { fill(32, 32, 0xFF4A7FBC.toInt()) }
        val out = FocusStack.stack(frames, 32, 32)
        // Equal softmax weights (1/n each) of identical constant frames must
        // reconstruct the constant exactly through the pyramid blend.
        assertArrayEquals(frames[0], out)
    }

    @Test
    fun stack_identicalGradientFramesSurviveWithBoundedError() {
        val grad = IntArray(64 * 64) { i ->
            val x = i % 64
            val v = (x * 255 / 63)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = FocusStack.stack(listOf(grad.copyOf(), grad.copyOf()), 64, 64)
        var maxErr = 0
        for (i in grad.indices) {
            val e = abs(((out[i] shr 16) and 0xFF) - ((grad[i] shr 16) and 0xFF))
            maxErr = maxOf(maxErr, e)
        }
        // Bilinear-expand pyramid reconstruction of a smooth gradient: a few
        // levels of quantization drift is expected, but not a gross shift.
        assertTrue("max reconstruction error $maxErr exceeds bound", maxErr <= 6)
    }

    @Test
    fun stack_deterministic() {
        val f1 = checker(32, 32, R, B)
        val f2 = checker(32, 32, G, WHITE)
        val a = FocusStack.stack(listOf(f1, f2), 32, 32)
        val b = FocusStack.stack(listOf(f1, f2), 32, 32)
        assertArrayEquals(a, b)
    }

    @Test
    fun stack_singleLevelDegenerateStillWorks() {
        val frames = List(2) { fill(16, 16, G) }
        val out = FocusStack.stack(frames, 16, 16, levels = 1)
        assertArrayEquals(frames[0], out)
    }

    @Test
    fun stack_outputIsOpaque() {
        val frames = List(2) { checker(24, 24, R, B) }
        val out = FocusStack.stack(frames, 24, 24)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    // ------------------------------------------------------------------
    // The money test: per-pixel focus selection actually works.
    //
    // Frame A: sharp RED/BLACK checker on top half, flat GRAY bottom.
    // Frame B: flat GRAY top, sharp BLUE/BLACK checker on bottom half.
    // The composite must be red-dominated on top and blue-dominated at
    // the bottom — i.e. the modified Laplacian + softmax picked the
    // in-focus frame per region.
    // ------------------------------------------------------------------

    @Test
    fun stack_selectsSharpFramePerRegion() {
        val w = 64; val h = 64
        val frameA = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 2) checker(2, 2, R, BLACK)[(i % 2) + (y % 2) * 2]
            else GRAY
        }
        val frameB = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 2) GRAY
            else checker(2, 2, B, BLACK)[(i % 2) + (y % 2) * 2]
        }
        val out = FocusStack.stack(listOf(frameA, frameB), w, h)

        fun avgChannel(y0: Int, y1: Int, shift: Int): Double {
            var s = 0L; var c = 0
            for (y in y0 until y1) for (x in 8 until w - 8) {
                s += (out[y * w + x] shr shift) and 0xFF; c++
            }
            return s.toDouble() / c
        }
        val topR = avgChannel(4, h / 2 - 8, 16)
        val topB = avgChannel(4, h / 2 - 8, 0)
        val botR = avgChannel(h / 2 + 8, h - 4, 16)
        val botB = avgChannel(h / 2 + 8, h - 4, 0)

        assertTrue("top should be red-dominant (R=$topR B=$topB)", topR > 100 && topR > topB * 2)
        assertTrue("bottom should be blue-dominant (B=$botB R=$botR)", botB > 100 && botB > botR * 2)
    }
}
