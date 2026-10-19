package com.lumora.photoeditor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * First-ever committed executed verification of [Waveform] / [waveformFromRgba] —
 * the per-column luminance envelope behind the scopes strip (Lightroom-style
 * waveform monitor). The data this produces feeds the "scopes dead" side of the
 * P0 black-preview watchdog contract (WatchdogPolicy SCOPES_DEAD_AFTER_FAILURES):
 * if this read were ever biased to all-black, the watchdog's dead-scopes
 * evidence would be meaningless, so the pinning matters beyond the display.
 *
 * Method recap: for each of 64 columns, the column's pixel x is sampled at up
 * to 256 evenly strided rows (rowStep = max(h/256, 1)); each sampled RGBA
 * pixel is reduced with Rec.709 luma (0.2126 R + 0.7152 G + 0.0722 B) into
 * per-column min/max/mean IntArrays. Reads are absolute — buffer position and
 * limit are untouched, preserving the caller-side buffer-reuse contract.
 *
 * The sharp edges worth pinning:
 * - the invalid-dims honest-empty contract (non-positive w/h return zeros, and
 *   no read is attempted, so a too-small buffer is safe);
 * - the stride sampling (h=600 samples rows 0,2,4.. — odd rows never read —
 *   and h<=256 rows are all read);
 * - absolute reads: position/limit bit-identical before and after;
 * - Rec.709 per-channel exactness: pure R/G/B frames pin the .toInt()
 *   truncation of each float coefficient (54/182/18), and the white frame
 *   documents the float-sum residual (254..255, NOT exactly 255 — pinning
 *   255 would be a false contract);
 * - min <= mean <= max and [0,255] bounds on random noise;
 * - determinism.
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/waveform-harness/run.sh): REAL engine/Waveform.kt + REAL test.
 */
class WaveformTest {

    /** Writes an RGBA pixel into [buf] at row-major index [i] (row*width+col). */
    private fun putPixel(buf: ByteBuffer, i: Int, r: Int, g: Int, b: Int, a: Int = 255) {
        val o = i * 4
        buf.put(o, r.toByte()); buf.put(o + 1, g.toByte())
        buf.put(o + 2, b.toByte()); buf.put(o + 3, a.toByte())
    }

    /** Builds a frame by per-pixel (row, col) color supplier. */
    private fun frame(w: Int, h: Int, color: (row: Int, col: Int) -> Triple<Int, Int, Int>): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h * 4)
        for (row in 0 until h) for (col in 0 until w) {
            val (r, g, b) = color(row, col)
            putPixel(buf, row * w + col, r, g, b)
        }
        return buf
    }

    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int): ByteBuffer =
        frame(w, h) { _, _ -> Triple(r, g, b) }

    private fun assertEmpty(w: Waveform) {
        assertEquals(Waveform.COLS, w.min.size)
        assertEquals(Waveform.COLS, w.max.size)
        assertEquals(Waveform.COLS, w.mean.size)
        val zeros = IntArray(Waveform.COLS)
        assertArrayEquals(zeros, w.min)
        assertArrayEquals(zeros, w.max)
        assertArrayEquals(zeros, w.mean)
    }

    @Test fun `COLS is 64 and empty() returns zero arrays`() {
        assertEquals(64, Waveform.COLS)
        assertEmpty(Waveform.empty())
    }

    @Test fun `invalid dims return empty without reading`() {
        val tiny = ByteBuffer.allocate(4) // far too small for any real frame
        assertEmpty(waveformFromRgba(tiny, 0, 16))
        assertEmpty(waveformFromRgba(tiny, 16, 0))
        assertEmpty(waveformFromRgba(tiny, 0, 0))
        assertEmpty(waveformFromRgba(tiny, -3, 16))
        assertEmpty(waveformFromRgba(tiny, 16, -3))
    }

    @Test fun `black frame pins exact zero`() {
        val w = waveformFromRgba(solid(64, 48, 0, 0, 0), 64, 48)
        assertArrayEquals(IntArray(64), w.min)
        assertArrayEquals(IntArray(64), w.max)
        assertArrayEquals(IntArray(64), w.mean)
    }

    @Test fun `pure red frame pins Rec709 R truncation to 54`() {
        val w = waveformFromRgba(solid(32, 32, 255, 0, 0), 32, 32)
        // 0.2126f * 255 = 54.21299.. -> toInt 54 (bit-exact, far from a boundary)
        assertArrayEquals(IntArray(64) { 54 }, w.min)
        assertArrayEquals(IntArray(64) { 54 }, w.max)
        assertArrayEquals(IntArray(64) { 54 }, w.mean)
    }

    @Test fun `pure green frame pins Rec709 G truncation to 182`() {
        val w = waveformFromRgba(solid(32, 32, 0, 255, 0), 32, 32)
        assertArrayEquals(IntArray(64) { 182 }, w.min)
        assertArrayEquals(IntArray(64) { 182 }, w.max)
        assertArrayEquals(IntArray(64) { 182 }, w.mean)
    }

    @Test fun `pure blue frame pins Rec709 B truncation to 18`() {
        val w = waveformFromRgba(solid(32, 32, 0, 0, 255), 32, 32)
        assertArrayEquals(IntArray(64) { 18 }, w.min)
        assertArrayEquals(IntArray(64) { 18 }, w.max)
        assertArrayEquals(IntArray(64) { 18 }, w.mean)
    }

    @Test fun `white frame documents the float-sum residual`() {
        val w = waveformFromRgba(solid(32, 32, 255, 255, 255), 32, 32)
        // (0.2126f + 0.7152f + 0.0722f) * 255 = 254.9999.. or 255.000x in float —
        // pinning exactly 255 would be a false contract; the display bound is
        // [0,255] so either is safe.
        for (c in 0 until 64) {
            assertTrue("min[$c]=${w.min[c]}", w.min[c] in 254..255)
            assertTrue("max[$c]=${w.max[c]}", w.max[c] in 254..255)
            assertTrue("mean[$c]=${w.mean[c]}", w.mean[c] in 254..255)
        }
    }

    @Test fun `vertical split pins per-column position`() {
        // w = 64: column c samples x = c; left half black, right half white.
        val w = waveformFromRgba(
            frame(64, 16) { _, col -> if (col < 32) Triple(0, 0, 0) else Triple(255, 255, 255) },
            64, 16
        )
        for (c in 0 until 32) {
            assertEquals("min[$c]", 0, w.min[c])
            assertEquals("max[$c]", 0, w.max[c])
            assertEquals("mean[$c]", 0, w.mean[c])
        }
        for (c in 32 until 64) {
            assertTrue("min[$c]=${w.min[c]}", w.min[c] in 254..255)
            assertTrue("max[$c]=${w.max[c]}", w.max[c] in 254..255)
            assertEquals("min/max agree[$c]", w.min[c], w.max[c])
        }
    }

    @Test fun `vertical gradient pins min max and mid mean`() {
        // 1px-wide, 64-tall gradient: row r has gray level round(r * 255 / 63).
        // rowStep = max(1, 64/256=0) = 1 -> all rows sampled.
        val w = waveformFromRgba(
            frame(1, 64) { row, _ -> val v = (row * 255 / 63); Triple(v, v, v) },
            1, 64
        )
        for (c in 0 until 64) {
            assertEquals("min[$c]", 0, w.min[c])
            assertTrue("max[$c]=${w.max[c]}", w.max[c] in 254..255)
            assertTrue("mean[$c]=${w.mean[c]} in 100..160", w.mean[c] in 100..160)
        }
    }

    @Test fun `tall frame strides rows`() {
        // h = 600 -> rowStep = 600/256 = 2: rows 0,2,4,... are sampled,
        // odd rows are NEVER read. Even rows black, odd rows white.
        val w = waveformFromRgba(
            frame(32, 600) { row, _ -> if (row % 2 == 0) Triple(0, 0, 0) else Triple(255, 255, 255) },
            32, 600
        )
        assertArrayEquals(IntArray(64), w.min)
        assertArrayEquals(IntArray(64), w.max)
        assertArrayEquals(IntArray(64), w.mean)
    }

    @Test fun `h 256 boundary reads every row`() {
        // h = 256 -> rowStep = max(1, 256/256=1) = 1 -> all rows sampled.
        // Only row 100 is white, rest black: max must see it, mean = white/256.
        val w = waveformFromRgba(
            frame(8, 256) { row, _ -> if (row == 100) Triple(255, 255, 255) else Triple(0, 0, 0) },
            8, 256
        )
        for (c in 0 until 64) {
            assertEquals("min[$c]", 0, w.min[c])
            assertTrue("max[$c]=${w.max[c]}", w.max[c] in 254..255)
            assertEquals("mean[$c]", w.max[c] / 256, w.mean[c])
        }
    }

    @Test fun `single row frame samples exactly one pixel per column`() {
        // h = 1: rowStep = 1, n = 1 -> min = max = mean = the row's luma.
        val w = waveformFromRgba(solid(8, 1, 0, 255, 0), 8, 1)
        assertArrayEquals(IntArray(64) { 182 }, w.min)
        assertArrayEquals(IntArray(64) { 182 }, w.max)
        assertArrayEquals(IntArray(64) { 182 }, w.mean)
    }

    @Test fun `narrow frame clamps column x into range`() {
        // w = 8 < COLS: columns c=0..55 map to x 0..6, c=56..63 to x=7.
        // Column pixel = its x index as gray level.
        val w = waveformFromRgba(
            frame(8, 8) { _, col -> Triple(col * 32, col * 32, col * 32) },
            8, 8
        )
        // Gray-level luma = v*(0.2126f+0.7152f+0.0722f) can land at v-epsilon in
        // float, so pin to +/-1 (still pins the column->x mapping exactly).
        for (c in 0 until 56) {
            val v = c * 8 / 64 * 32
            assertTrue("mean[$c]=${w.mean[c]} ~= $v", w.mean[c] in (v - 1)..(v + 1))
        }
        for (c in 56 until 64) {
            assertTrue("mean[$c]=${w.mean[c]} ~= 224", w.mean[c] in 223..225)
        }
    }

    @Test fun `buffer position and limit are not mutated`() {
        val buf = solid(16, 16, 100, 100, 100)
        // "Untouched" in the KDoc means NOT MUTATED by the call — absolute
        // gets still honor the limit as a read bound (standard NIO semantics;
        // a too-small limit legitimately throws instead of mis-reading).
        buf.position(5)
        assertEquals(buf.capacity(), buf.limit())
        val w = waveformFromRgba(buf, 16, 16)
        assertEquals(5, buf.position())
        assertEquals(buf.capacity(), buf.limit())
        // And the frame was still read correctly through those markers
        // (+/-1 for the gray-level float residual, as pinned above).
        for (c in 0 until 64) assertTrue("mean[$c]=${w.mean[c]}", w.mean[c] in 99..101)
    }

    @Test fun `random noise stays within bounds and order`() {
        val rnd = java.util.Random(1234L)
        val buf = ByteBuffer.allocate(96 * 64 * 4)
        repeat(96 * 64) { i ->
            putPixel(buf, i, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        val w = waveformFromRgba(buf, 96, 64)
        for (c in 0 until 64) {
            assertTrue("min[$c]>=0", w.min[c] >= 0)
            assertTrue("max[$c]<=255", w.max[c] <= 255)
            assertTrue("min<=mean<=max[$c]", w.min[c] <= w.mean[c] && w.mean[c] <= w.max[c])
        }
    }

    @Test fun `deterministic across calls`() {
        val buf = frame(40, 30) { row, col -> Triple(row * 3 % 256, col * 5 % 256, (row + col) * 7 % 256) }
        val a = waveformFromRgba(buf, 40, 30)
        val b = waveformFromRgba(buf, 40, 30)
        assertArrayEquals(a.min, b.min)
        assertArrayEquals(a.max, b.max)
        assertArrayEquals(a.mean, b.mean)
    }
}
