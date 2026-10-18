package com.lumora.photoeditor

import com.lumora.photoeditor.engine.SKIN_TONE_U
import com.lumora.photoeditor.engine.SKIN_TONE_V
import com.lumora.photoeditor.engine.Vectorscope
import com.lumora.photoeditor.engine.vectorscopeFromRgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Unit tests for [com.lumora.photoeditor.engine.Vectorscope] chroma mapping. */
class VectorscopeTest {

    private fun rgba(w: Int, h: Int, r: Int, g: Int, b: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h * 4)
        for (i in 0 until w * h) {
            buf.put(i * 4, r.toByte()); buf.put(i * 4 + 1, g.toByte())
            buf.put(i * 4 + 2, b.toByte()); buf.put(i * 4 + 3, 255.toByte())
        }
        return buf
    }

    @Test
    fun `degenerate dims produce an empty scope`() {
        val v = vectorscopeFromRgba(rgba(4, 4, 0, 0, 0), 0, 0)
        assertTrue(v.counts.all { it == 0 })
    }

    @Test
    fun `uniform gray lands in the center cell`() {
        val v = vectorscopeFromRgba(rgba(16, 16, 128, 128, 128), 16, 16)
        val g = Vectorscope.GRID
        val total = v.counts.sum()
        assertTrue(total > 0)
        assertEquals(total, v.counts[(g / 2) * g + (g / 2)])
        assertEquals(0xFF808080.toInt(), v.colorAt(g / 2, g / 2))
    }

    @Test
    fun `pure red maps to right edge`() {
        val v = vectorscopeFromRgba(rgba(16, 16, 255, 0, 0), 16, 16)
        val g = Vectorscope.GRID
        assertEquals(v.counts.sum(), v.counts[12 * g + 47])
        assertEquals(0xFFFF0000.toInt(), v.colorAt(47, 12))
    }

    @Test
    fun `pure green maps to top edge`() {
        val v = vectorscopeFromRgba(rgba(16, 16, 0, 255, 0), 16, 16)
        val g = Vectorscope.GRID
        assertEquals(v.counts.sum(), v.counts[47 * g + 24])
    }

    @Test
    fun `near-black pixels are skipped`() {
        val v = vectorscopeFromRgba(rgba(16, 16, 10, 10, 10), 16, 16)
        assertTrue(v.counts.all { it == 0 })
    }

    @Test
    fun `buffer position and limit are preserved`() {
        val buf = rgba(8, 8, 200, 100, 50)
        buf.position(7); buf.limit(8 * 8 * 4)
        vectorscopeFromRgba(buf, 8, 8)
        assertEquals(7, buf.position())
        assertEquals(8 * 8 * 4, buf.limit())
    }

    @Test
    fun `skin tone constants match documented values`() {
        assertEquals(0.5686f, SKIN_TONE_U, 0.001f)
        assertEquals(-0.0490f, SKIN_TONE_V, 0.001f)
    }

    @Test
    fun `sampling is stride-bounded on large frames`() {
        val v = vectorscopeFromRgba(rgba(400, 300, 200, 120, 80), 400, 300)
        val stride = 400 * 300 / 24576
        assertEquals((400 * 300 + stride - 1) / stride, v.counts.sum())
    }
}
