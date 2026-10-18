package com.lumora.photoeditor

import com.lumora.photoeditor.engine.RgbParade
import com.lumora.photoeditor.engine.rgbParadeFromRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Unit tests for [com.lumora.photoeditor.engine.RgbParade] channel envelopes. */
class RgbParadeTest {

    private fun rgba(w: Int, h: Int, r: Int, g: Int, b: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h * 4)
        for (i in 0 until w * h) {
            buf.put(i * 4, r.toByte()); buf.put(i * 4 + 1, g.toByte())
            buf.put(i * 4 + 2, b.toByte()); buf.put(i * 4 + 3, 255.toByte())
        }
        return buf
    }

    /** Column index 0 samples x = 0, so column-0 values equal the fill color. */
    private fun col0(p: RgbParade, k: Int) = Triple(p.min[k][0], p.max[k][0], p.mean[k][0])

    @Test
    fun `degenerate dims produce an empty parade`() {
        val p = rgbParadeFromRgba(rgba(4, 4, 0, 0, 0), 0, 0)
        assertTrue(p.min.all { it.all { v -> v == 0 } })
        assertTrue(p.max.all { it.all { v -> v == 0 } })
        assertTrue(p.mean.all { it.all { v -> v == 0 } })
    }

    @Test
    fun `uniform color pins every channel envelope flat`() {
        val p = rgbParadeFromRgba(rgba(32, 16, 200, 100, 50), 32, 16)
        assertEquals(Triple(200, 200, 200), col0(p, 0))
        assertEquals(Triple(100, 100, 100), col0(p, 1))
        assertEquals(Triple(50, 50, 50), col0(p, 2))
    }

    @Test
    fun `each channel envelope is independent`() {
        // red channel pinned high, green/blue dark: luma waveform would just
        // read "bright", the parade must show the split.
        val p = rgbParadeFromRgba(rgba(64, 32, 255, 0, 0), 64, 32)
        assertEquals(Triple(255, 255, 255), col0(p, 0))
        assertEquals(Triple(0, 0, 0), col0(p, 1))
        assertEquals(Triple(0, 0, 0), col0(p, 2))
    }

    @Test
    fun `vertical gradient spans the column envelope`() {
        val w = 32; val h = 64
        val buf = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val v = (y * 255 / (h - 1)).toByte()
            buf.put(o, v); buf.put(o + 1, 0); buf.put(o + 2, 0); buf.put(o + 3, 255.toByte())
        }
        val p = rgbParadeFromRgba(buf, w, h)
        val (lo, hi, mean) = col0(p, 0)
        assertTrue("min should be near 0, was $lo", lo <= 8)
        assertTrue("max should be near 255, was $hi", hi >= 247)
        assertTrue("mean should sit mid-scale, was $mean", mean in 100..155)
        // green channel stayed 0 everywhere
        assertEquals(Triple(0, 0, 0), col0(p, 1))
    }

    @Test
    fun `buffer position is untouched by sampling`() {
        val buf = rgba(16, 16, 10, 20, 30)
        buf.position(42)
        rgbParadeFromRgba(buf, 16, 16)
        assertEquals(42, buf.position())
    }

    @Test
    fun `envelopes stay within byte range on noisy input`() {
        val w = 48; val h = 24
        val buf = ByteBuffer.allocate(w * h * 4)
        var seed = 12345L
        fun rnd(): Int {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            return (seed % 256).toInt()
        }
        for (i in 0 until w * h) {
            buf.put(i * 4, rnd().toByte()); buf.put(i * 4 + 1, rnd().toByte())
            buf.put(i * 4 + 2, rnd().toByte()); buf.put(i * 4 + 3, 255.toByte())
        }
        val p = rgbParadeFromRgba(buf, w, h)
        for (k in 0..2) for (c in 0 until RgbParade.COLS) {
            assertTrue(p.min[k][c] in 0..255)
            assertTrue(p.max[k][c] in 0..255)
            assertTrue(p.mean[k][c] in 0..255)
            assertTrue(p.min[k][c] <= p.max[k][c])
            assertTrue(p.mean[k][c] in p.min[k][c]..p.max[k][c])
        }
    }
}
