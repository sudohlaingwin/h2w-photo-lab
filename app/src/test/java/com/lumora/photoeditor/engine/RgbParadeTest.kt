package com.lumora.photoeditor.engine

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [rgbParadeFromRgba], the per-column
 * R/G/B min/max/mean envelopes behind the scopes strip (Lightroom-style RGB
 * parade monitor). It feeds the "scopes dead" evidence side of the P0
 * black-preview watchdog contract (WatchdogPolicy SCOPES_DEAD_AFTER_FAILURES):
 * a live preview renders per-column envelopes that move with the photo; a
 * dead GL pipeline leaves them flat — so this observable contract is the
 * load-bearing part.
 */
class RgbParadeTest {

    private fun rgbaBuf(w: Int, h: Int, fill: (x: Int, y: Int) -> IntArray): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val c = fill(x, y)
            val o = (y * w + x) * 4
            buf.put(o, c[0].toByte()); buf.put(o + 1, c[1].toByte())
            buf.put(o + 2, c[2].toByte()); buf.put(o + 3, c[3].toByte())
        }
        return buf
    }

    @Test fun `COLS is 64 and empty() is all zeros`() {
        assertEquals(64, RgbParade.COLS)
        val e = RgbParade.empty()
        for (k in 0..2) {
            assertEquals(64, e.min[k].size)
            assertEquals(64, e.max[k].size)
            assertEquals(64, e.mean[k].size)
            assertTrue(e.min[k].all { it == 0 })
            assertTrue(e.max[k].all { it == 0 })
            assertTrue(e.mean[k].all { it == 0 })
        }
        // data-class structural equality works (used by the watchdog diffing)
        assertEquals(RgbParade.empty(), e)
    }

    @Test fun `invalid dims return empty without reading the buffer`() {
        val tiny = ByteBuffer.allocate(4) // 1 pixel; 0-dim reads would blow past it
        assertEquals(RgbParade.empty(), rgbParadeFromRgba(tiny, 0, 16))
        assertEquals(RgbParade.empty(), rgbParadeFromRgba(tiny, -4, 16))
        assertEquals(RgbParade.empty(), rgbParadeFromRgba(tiny, 16, 0))
        assertEquals(RgbParade.empty(), rgbParadeFromRgba(tiny, 16, -1))
        assertEquals(RgbParade.empty(), rgbParadeFromRgba(tiny, 0, 0))
    }

    @Test fun `solid frame pins min=max=mean per channel on every column`() {
        val buf = rgbaBuf(16, 16) { _, _ -> intArrayOf(10, 20, 30, 255) }
        val p = rgbParadeFromRgba(buf, 16, 16)
        for (c in 0 until 64) {
            assertEquals(10, p.min[0][c]); assertEquals(10, p.max[0][c]); assertEquals(10, p.mean[0][c])
            assertEquals(20, p.min[1][c]); assertEquals(20, p.max[1][c]); assertEquals(20, p.mean[1][c])
            assertEquals(30, p.min[2][c]); assertEquals(30, p.max[2][c]); assertEquals(30, p.mean[2][c])
        }
    }

    @Test fun `per-column x mapping - red ramp across columns`() {
        // h=1: exactly one row sampled per column, so mean[c] pins x=c exactly.
        val buf = rgbaBuf(64, 1) { x, _ -> intArrayOf(x, 0, 0, 255) }
        val p = rgbParadeFromRgba(buf, 64, 1)
        for (c in 0 until 64) {
            assertEquals("col $c", c, p.min[0][c])
            assertEquals("col $c", c, p.max[0][c])
            assertEquals("col $c", c, p.mean[0][c])
            assertEquals(0, p.max[1][c]); assertEquals(0, p.max[2][c])
        }
    }

    @Test fun `vertical split pins per-channel envelopes per half`() {
        val buf = rgbaBuf(64, 16) { x, _ ->
            if (x < 32) intArrayOf(255, 0, 0, 255) else intArrayOf(0, 0, 255, 255)
        }
        val p = rgbParadeFromRgba(buf, 64, 16)
        for (c in 0 until 64) {
            val left = c < 32 // x = c for w=64
            assertEquals("R col $c", if (left) 255 else 0, p.min[0][c])
            assertEquals("R col $c", if (left) 255 else 0, p.max[0][c])
            assertEquals("B col $c", if (left) 0 else 255, p.min[2][c])
            assertEquals("B col $c", if (left) 0 else 255, p.max[2][c])
            assertEquals(0, p.max[1][c]) // green zero everywhere
        }
    }

    @Test fun `yellow channel-clip shows R and G pinned while B stays low`() {
        // The parade's whole point: a luma-only waveform would read this as
        // "bright"; the parade must show red+green pinned and blue near zero.
        val buf = rgbaBuf(32, 32) { _, _ -> intArrayOf(255, 255, 8, 255) }
        val p = rgbParadeFromRgba(buf, 32, 32)
        for (c in 0 until 64) {
            assertEquals(255, p.min[0][c]); assertEquals(255, p.max[0][c])
            assertEquals(255, p.min[1][c]); assertEquals(255, p.max[1][c])
            assertEquals(8, p.max[2][c])
        }
    }

    @Test fun `tall frame stride-samples - odd rows never read`() {
        // h=600 -> rowStep=2; even rows white, odd rows black.
        val buf = rgbaBuf(8, 600) { _, y ->
            if (y % 2 == 0) intArrayOf(255, 255, 255, 255) else intArrayOf(0, 0, 0, 255)
        }
        val p = rgbParadeFromRgba(buf, 8, 600)
        for (c in 0 until 64) {
            assertEquals("col $c min R", 255, p.min[0][c])
            assertEquals("col $c mean R", 255, p.mean[0][c])
        }
        // h=256 boundary: rowStep=1, every row read -> black row dominates mean.
        val buf2 = rgbaBuf(8, 256) { _, y ->
            if (y == 0) intArrayOf(255, 255, 255, 255) else intArrayOf(0, 0, 0, 255)
        }
        val p2 = rgbParadeFromRgba(buf2, 8, 256)
        for (c in 0 until 64) {
            assertEquals(255, p2.max[0][c])
            assertEquals(0, p2.min[0][c]) // the 255 black rows pin the floor
            assertEquals(0, p2.mean[0][c]) // 255/256 truncates to 0 — pin the exact math
        }
    }

    @Test fun `w=1 clamps all columns to the single pixel`() {
        val buf = rgbaBuf(1, 4) { _, _ -> intArrayOf(40, 50, 60, 255) }
        val p = rgbParadeFromRgba(buf, 1, 4)
        for (c in 0 until 64) {
            assertEquals(40, p.mean[0][c]); assertEquals(50, p.mean[1][c]); assertEquals(60, p.mean[2][c])
        }
    }

    @Test fun `buffer position and limit are not mutated`() {
        val buf = rgbaBuf(16, 16) { _, _ -> intArrayOf(1, 2, 3, 255) }
        buf.position(7)
        buf.limit(buf.capacity())
        rgbParadeFromRgba(buf, 16, 16)
        assertEquals(7, buf.position())
        assertEquals(buf.capacity(), buf.limit())
        // A correct read must still land through the markers.
        val p = rgbParadeFromRgba(buf, 16, 16)
        assertEquals(1, p.mean[0][0]); assertEquals(2, p.mean[1][0]); assertEquals(3, p.mean[2][0])
    }

    @Test fun `envelope ordering holds on a noisy frame`() {
        val rnd = java.util.Random(42)
        val buf = rgbaBuf(48, 48) { _, _ ->
            intArrayOf(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), 255)
        }
        val p = rgbParadeFromRgba(buf, 48, 48)
        for (k in 0..2) for (c in 0 until 64) {
            assertTrue("k=$k c=$c", p.min[k][c] <= p.mean[k][c])
            assertTrue("k=$k c=$c", p.mean[k][c] <= p.max[k][c])
            assertTrue("k=$k c=$c", p.min[k][c] in 0..255)
            assertTrue("k=$k c=$c", p.max[k][c] in 0..255)
        }
    }

    @Test fun `deterministic across runs`() {
        val rnd = java.util.Random(7)
        val buf = rgbaBuf(40, 40) { _, _ ->
            intArrayOf(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), 255)
        }
        assertEquals(rgbParadeFromRgba(buf, 40, 40), rgbParadeFromRgba(buf, 40, 40))
    }
}
