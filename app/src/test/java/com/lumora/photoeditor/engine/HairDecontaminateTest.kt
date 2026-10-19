package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [HairDecontaminate] — the deterministic
 * inward-fill despill behind the pro-spec Phase 3 "Decontaminate colors"
 * (feathered mask edges bleed the old background color into the result; this
 * pulls edge pixels toward the nearest fully-selected interior color). The
 * contract has several sharp edges worth pinning: only the transition band
 * (1..254) may change, the donor is the NEAREST fully-selected pixel under
 * the Chebyshev metric, the contamination weight is exactly
 * (1 - mask/255) * strength, and the spiral offsets must stay ordered by
 * distance — a drift there would make the "nearest" donor wrong and paint
 * hair fringes with the wrong color.
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/hairdecontaminate-harness/run.sh).
 */
class HairDecontaminateTest {

    private val RED = 0xFFFF0000.toInt()
    private val BLACK = 0xFF000000.toInt()

    /** 3x3 frame: RED interior at center, black fringe at (0,0), all else unselected. */
    private fun fringeFrame(fringeMask: Int): Pair<IntArray, IntArray> {
        val src = IntArray(9) { BLACK }
        src[4] = RED
        val mask = IntArray(9)
        mask[4] = 255
        mask[0] = fringeMask
        return src to mask
    }

    private fun channel(pixel: Int, shift: Int) = (pixel ushr shift) and 0xFF

    // ---------- despill: contract guards ----------

    @Test(expected = IllegalArgumentException::class)
    fun sizeMismatchSrcThrows() {
        HairDecontaminate.despill(IntArray(8), 3, 3, IntArray(9), 1f, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sizeMismatchMaskThrows() {
        HairDecontaminate.despill(IntArray(9), 3, 3, IntArray(8), 1f, 2)
    }

    @Test
    fun strengthZeroReturnsUnchangedCopy() {
        val (src, mask) = fringeFrame(128)
        val out = HairDecontaminate.despill(src, 3, 3, mask, 0f, 2)
        assertTrue("strength 0 must not touch anything", out.contentEquals(src))
        assertTrue("must return a copy, not the input", out !== src)
    }

    @Test
    fun strengthIsClampedToUnitRange() {
        // Negative strength == 0 (no-op); strength > 1 == 1 (full pull).
        val (srcA, maskA) = fringeFrame(100)
        val (srcB, maskB) = fringeFrame(100)
        val (srcC, maskC) = fringeFrame(100)
        val neg = HairDecontaminate.despill(srcA, 3, 3, maskA, -0.5f, 2)
        assertTrue(neg.contentEquals(srcA))
        val one = HairDecontaminate.despill(srcB, 3, 3, maskB, 1f, 2)
        val over = HairDecontaminate.despill(srcC, 3, 3, maskC, 1.5f, 2)
        assertTrue("strength 1.5 must equal strength 1", over.contentEquals(one))
    }

    @Test
    fun fullySelectedAndUnselectedPixelsUntouched() {
        // Interior (255) and exterior (0) keep their EXACT colors — even with
        // a semi-transparent source pixel, since fully-selected pixels are
        // documented as "never touched".
        val src = intArrayOf(0x80112233.toInt(), RED, BLACK, 0xFF445566.toInt())
        val allSelected = intArrayOf(255, 255, 255, 255)
        val noneSelected = intArrayOf(0, 0, 0, 0)
        assertTrue(HairDecontaminate.despill(src, 2, 2, allSelected, 1f, 2).contentEquals(src))
        assertTrue(HairDecontaminate.despill(src, 2, 2, noneSelected, 1f, 2).contentEquals(src))
    }

    @Test
    fun inputArrayIsNotMutated() {
        val (src, mask) = fringeFrame(200)
        val snapshot = src.copyOf()
        HairDecontaminate.despill(src, 3, 3, mask, 1f, 2)
        assertTrue("src must not be mutated in place", src.contentEquals(snapshot))
    }

    // ---------- despill: the despill behavior ----------

    @Test
    fun fringePixelDespilledTowardInteriorColor() {
        // mask=128 -> contamination t = 1 - 128/255 = 127/255 ~ 0.498, so the
        // black fringe pixel should come back half-red.
        val (src, mask) = fringeFrame(128)
        val out = HairDecontaminate.despill(src, 3, 3, mask, 1f, 2)
        val r = channel(out[0], 16)
        assertTrue("fringe r should be ~127, was $r", r in 126..128)
        assertEquals(0, channel(out[0], 8))
        assertEquals(0, channel(out[0], 0))
        // Everyone else is untouched.
        assertEquals(src[4], out[4])
        assertEquals(src[1], out[1])
    }

    @Test
    fun contaminationWeightScalesWithMask() {
        // Worst at the outer edge (mask -> 0), zero at the interior (255):
        // a 64-mask fringe must pull much further toward red than 192-mask.
        val (srcLo, maskLo) = fringeFrame(64)
        val (srcHi, maskHi) = fringeFrame(192)
        val rLo = channel(HairDecontaminate.despill(srcLo, 3, 3, maskLo, 1f, 2)[0], 16)
        val rHi = channel(HairDecontaminate.despill(srcHi, 3, 3, maskHi, 1f, 2)[0], 16)
        assertTrue("outer-edge fringe (mask 64, r=$rLo) must despill further than inner (mask 192, r=$rHi)",
            rLo > rHi + 40)
        assertTrue("both must be strictly partial blends", rHi in 1..254 && rLo in 1..254)
    }

    @Test
    fun noDonorWithinRadiusLeavesPixelUnchanged() {
        // Fringe pixel with NO fully-selected pixel anywhere nearby: the
        // search exhausts the radius and the pixel keeps its exact color.
        val src = IntArray(16) { BLACK }
        val mask = IntArray(16)
        mask[0] = 128 // fringe at a corner, nothing selected
        val out = HairDecontaminate.despill(src, 4, 4, mask, 1f, 2)
        assertTrue(out.contentEquals(src))
    }

    @Test
    fun donorBeyondRadiusIsNotUsed() {
        // A fully-selected pixel outside the search radius must NOT donate —
        // hair despill must stay local or it smears color across the image.
        val src = IntArray(7) { BLACK }
        src[5] = RED
        val mask = IntArray(7)
        mask[0] = 128
        mask[5] = 255 // distance 5 from the fringe pixel
        val out = HairDecontaminate.despill(src, 7, 1, mask, 1f, 2)
        assertTrue("donor 5px away with radius 2 must not donate", out.contentEquals(src))
    }

    @Test
    fun radiusZeroIsCoercedToOne() {
        val (srcA, maskA) = fringeFrame(150)
        val (srcB, maskB) = fringeFrame(150)
        val r0 = HairDecontaminate.despill(srcA, 3, 3, maskA, 1f, 0)
        val r1 = HairDecontaminate.despill(srcB, 3, 3, maskB, 1f, 1)
        assertTrue("radius 0 must behave as radius 1", r0.contentEquals(r1))
    }

    @Test
    fun nearestDonorWins() {
        // Two interior donors: RED 2px away, BLUE 3px away. The spiral finds
        // the nearest (Chebyshev) first — the fringe must turn red, not blue.
        val src = IntArray(7) { BLACK }
        src[1] = RED
        src[6] = 0xFF0000FF.toInt()
        val mask = IntArray(7)
        mask[1] = 255
        mask[3] = 128
        mask[6] = 255
        val out = HairDecontaminate.despill(src, 7, 1, mask, 1f, 3)[3]
        val r = channel(out, 16)
        val b = channel(out, 0)
        assertTrue("fringe must despill toward nearer RED donor (r=$r, b=$b)", r > 100 && b < 10)
    }

    @Test
    fun outputAlphaIsForcedOpaque() {
        // lerpArgb always writes 0xFF alpha: even a semi-transparent fringe
        // source pixel comes back fully opaque after despill.
        val src = IntArray(9) { BLACK }
        src[0] = 0x80000000.toInt()
        src[4] = RED
        val mask = IntArray(9)
        mask[4] = 255
        mask[0] = 200
        val out = HairDecontaminate.despill(src, 3, 3, mask, 1f, 2)
        assertEquals(0xFF, channel(out[0], 24))
    }

    @Test
    fun isDeterministic() {
        val (srcA, maskA) = fringeFrame(173)
        val (srcB, maskB) = fringeFrame(173)
        val a = HairDecontaminate.despill(srcA, 3, 3, maskA, 0.7f, 2)
        val b = HairDecontaminate.despill(srcB, 3, 3, maskB, 0.7f, 2)
        assertTrue("despill must be bit-identical across runs", a.contentEquals(b))
    }

    // ---------- spiralOffsets ----------

    @Test
    fun spiralOffsetsZeroIsSingleOrigin() {
        assertTrue(HairDecontaminate.spiralOffsets(0).contentEquals(intArrayOf(0, 0)))
    }

    @Test
    fun spiralOffsetsOneHasDocumentedOrder() {
        // (0,0) first, then the Chebyshev-1 ring in deterministic dx-then-dy
        // order: the exact sequence the nearest-donor search relies on.
        val expected = intArrayOf(
            0, 0,
            -1, -1, 0, -1, 1, -1,
            -1, 0, 1, 0,
            -1, 1, 0, 1, 1, 1,
        )
        assertTrue(HairDecontaminate.spiralOffsets(1).contentEquals(expected))
    }

    @Test
    fun spiralOffsetsRadius2SizeAndChebyshevOrdering() {
        val o = HairDecontaminate.spiralOffsets(2)
        assertEquals("radius 2 must yield 25 (dx,dy) pairs", 50, o.size)
        var prev = -1
        for (i in o.indices step 2) {
            val d = maxOf(kotlin.math.abs(o[i]), kotlin.math.abs(o[i + 1]))
            assertTrue("Chebyshev distance must be non-decreasing (broke at pair $i)", d >= prev)
            prev = d
        }
        // Every pair of the 5x5 square appears exactly once.
        val seen = HashSet<Pair<Int, Int>>()
        for (i in o.indices step 2) seen.add(o[i] to o[i + 1])
        assertEquals(25, seen.size)
        assertTrue(seen.contains(2 to -2))
        assertTrue(seen.contains(0 to 0))
    }

    @Test
    fun spiralOffsetsIsDeterministic() {
        assertTrue(HairDecontaminate.spiralOffsets(3).contentEquals(HairDecontaminate.spiralOffsets(3)))
    }
}
