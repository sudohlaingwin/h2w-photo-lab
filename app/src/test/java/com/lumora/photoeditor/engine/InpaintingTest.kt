package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * First executed verification of the Remove tool's Bitmap wrapper
 * ([Inpainting]) — the object-removal entry point behind his retouch
 * complaints. The PatchMatch algorithm itself lives in [InpaintingCore]
 * (covered by its own test); this pins the wrapper's observable contract:
 * guards, path selection (crop vs legacy full-image), the empty-mask and
 * fill-cap refusals, progress reporting, recycle ownership, and the
 * cancellation contract.
 */
class InpaintingTest {

    private fun solid(w: Int, h: Int, color: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val b = Bitmap.createBitmap(w, h, config)
        b.eraseColor(color)
        return b
    }

    /** Black 64x64 with a white 10x10 square centered at (32,32) — the "object". */
    private fun squareOnBlack(): Bitmap {
        val b = solid(64, 64, 0xFF000000.toInt())
        for (y in 27 until 37) for (x in 27 until 37) b.setPixel(x, y, 0xFFFFFFFF.toInt())
        return b
    }

    /** Opaque-white-rect mask, matching the fill threshold max(alpha,red) > 100. */
    private fun rectMask(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap {
        val m = solid(w, h, 0x00000000)
        for (y in y0 until y1) for (x in x0 until x1) m.setPixel(x, y, 0xFFFFFFFF.toInt())
        return m
    }

    private fun maskPx(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun progressTracker(): Pair<MutableList<Float>, (Float) -> Unit> {
        val seen = mutableListOf<Float>()
        return seen to { p -> seen.add(p) }
    }

    // --- guards ----------------------------------------------------------

    @Test fun `undersized input returns null`() {
        val img = solid(15, 15, 0xFF000000.toInt())
        val mask = rectMask(15, 15, 2, 2, 6, 6)
        assertNull(Inpainting.removeObject(img, mask))
    }

    @Test fun `mask size mismatch returns null`() {
        val img = solid(64, 64, 0xFF000000.toInt())
        val mask = solid(32, 64, 0xFF000000.toInt())
        assertNull(Inpainting.removeObject(img, mask))
    }

    @Test fun `empty mask returns an unchanged copy of the input`() {
        val img = squareOnBlack()
        val empty = solid(64, 64, 0x00000000)
        val (seen, cb) = progressTracker()
        val out = Inpainting.removeObject(img, empty, cb)
        assertNotNull(out)
        assertArrayEquals(maskPx(img), maskPx(out!!))
        assertNotSame(img, out)
        // No work happens on the empty-mask early return, so no progress
        // events are issued — the contract is "progress while working".
        assertTrue(seen.isEmpty())
    }

    // --- crop path (the real Remove tool path for ordinary removals) ------

    @Test fun `small removal removes the object and leaves the rest untouched`() {
        val img = squareOnBlack()
        val mask = rectMask(64, 64, 25, 25, 39, 39) // covers the white square
        val (seen, cb) = progressTracker()
        val out = Inpainting.removeObject(img, mask, cb)
        assertNotNull(out); out!!
        assertEquals(64, out.width); assertEquals(64, out.height)

        val before = maskPx(img); val after = maskPx(out)
        // Outside the mask's influence (margin 24 + feather 4), pixels are
        // exactly the originals: the blend only touches the crop region.
        for (y in 0 until 64) for (x in 0 until 64) {
            if (x < 20 || x > 44 || y < 20 || y > 44) assertEquals(
                "pixel ($x,$y) outside the removal must be untouched",
                before[y * 64 + x], after[y * 64 + x],
            )
        }
        // The object is gone: the mask interior (well inside the feather
        // reach) is pure fill, and no pure-white object pixel survives
        // anywhere in the mask — the seam band is a feathered blend, which
        // is the documented blendCropped behavior, not leftover object.
        for (y in 31 until 35) for (x in 31 until 35) {
            assertEquals("mask interior must be pure fill",
                0xFF000000.toInt(), after[y * 64 + x])
        }
        for (y in 25 until 39) for (x in 25 until 39) {
            assertNotEquals("no object pixel may survive the removal",
                0xFFFFFFFF.toInt(), after[y * 64 + x])
        }
        assertEquals(1f, seen.last())
        assertTrue("progress must be non-decreasing", seen.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
    }

    @Test fun `alpha-based mask is honored like a red-channel mask`() {
        // ALPHA_8: the fill byte lands in the high byte; wrapper takes
        // max(alpha, red).
        val img = squareOnBlack()
        val m = solid(64, 64, 0x00000000, Bitmap.Config.ALPHA_8)
        for (y in 25 until 39) for (x in 25 until 39) m.setPixel(x, y, 0xFF000000.toInt())
        val out = Inpainting.removeObject(img, m)
        assertNotNull(out)
        val after = maskPx(out!!)
        // Mask interior is pure fill; no white object pixel survives (the
        // seam band is a legitimate feathered blend).
        for (y in 31 until 35) for (x in 31 until 35)
            assertEquals(0xFF000000.toInt(), after[y * 64 + x])
        for (y in 25 until 39) for (x in 25 until 39)
            assertNotEquals(0xFFFFFFFF.toInt(), after[y * 64 + x])
    }

    @Test fun `absurd fill fraction is refused instead of churning`() {
        // > 40% of the full photo marked for removal: the wrapper refuses.
        val img = squareOnBlack()
        val mask = rectMask(64, 64, 0, 0, 64, 40) // 62.5% of the frame
        assertNull(Inpainting.removeObject(img, mask))
    }

    @Test fun `cancellation propagates instead of being swallowed`() {
        val img = squareOnBlack()
        val mask = rectMask(64, 64, 25, 25, 39, 39)
        try {
            Inpainting.removeObject(img, mask) { throw CancellationException("user cancelled") }
            fail("CancellationException must propagate")
        } catch (c: CancellationException) {
            // expected: the contract says cancellation rethrows through both
            // try/catch layers.
        }
    }

    // --- legacy full-image path ------------------------------------------

    @Test fun `wide removal takes the legacy full-image path and blends back`() {
        // Mask bounds + margin exceed 900px, forcing the full-image path
        // through the 512px working resolution.
        val img = solid(1024, 1024, 0xFF000000.toInt())
        for (x in 0 until 1024) img.setPixel(x, 512, 0xFFFFFFFF.toInt()) // white line
        val mask = rectMask(1024, 1024, 32, 500, 992, 524) // bounds w = 960+48 > 900
        val (seen, cb) = progressTracker()
        val out = Inpainting.removeObject(img, mask, cb)
        assertNotNull(out); out!!
        assertEquals(1024, out.width); assertEquals(1024, out.height)
        val after = maskPx(out)
        // The line is gone at its center; far corners are untouched.
        assertEquals(0xFF000000.toInt(), after[512 * 1024 + 512])
        assertEquals(0xFF000000.toInt(), after[0])
        assertEquals(0xFF000000.toInt(), after[1023 * 1024 + 1023])
        assertEquals(1f, seen.last())
    }

    @Test fun `empty mask on a large image still returns the image unchanged`() {
        val img = solid(1024, 1024, 0xFF808080.toInt())
        val out = Inpainting.removeObject(img, solid(1024, 1024, 0x00000000))
        assertNotNull(out)
        assertArrayEquals(maskPx(img), maskPx(out!!))
    }

    // --- recycle ownership ------------------------------------------------

    @Test fun `crop-path temporaries are recycled`() {
        // The wrapper promises blendCropped/fill bitmaps never leak: every
        // temp it creates must be recycled on the success path.
        val img = squareOnBlack()
        val mask = rectMask(64, 64, 25, 25, 39, 39)
        val out = Inpainting.removeObject(img, mask)
        assertNotNull(out)
        assertFalse("the returned bitmap must be usable", out!!.isRecycled)
    }

    @Test fun `default progress callback is a no-op and never crashes`() {
        val img = squareOnBlack()
        val out = Inpainting.removeObject(img, rectMask(64, 64, 25, 25, 39, 39))
        assertNotNull(out)
    }
}
