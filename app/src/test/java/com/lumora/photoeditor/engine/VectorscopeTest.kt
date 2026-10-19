package com.lumora.photoeditor.engine

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [vectorscopeFromRgba], the chroma
 * (hue/saturation) distribution behind the scopes strip (Lightroom-style
 * vectorscope). It feeds the "scopes dead" evidence side of the P0
 * black-preview watchdog contract (WatchdogPolicy SCOPES_DEAD_AFTER_FAILURES):
 * a live preview renders a vectorscope that moves with the photo's chroma;
 * a dead GL pipeline leaves it empty — so this observable contract is the
 * load-bearing part.
 *
 * Pins the documented chroma math: u = (R-B)/255, v = (2G-R-B)/510, with
 * pure red at (1,-0.5) -> cell (47,12), green at (0,1) -> cell (24,47),
 * blue at (-1,-0.5) -> cell (0,12), and gray collapsing to the center dot.
 */
class VectorscopeTest {

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

    private fun cell(v: Vectorscope, gx: Int, gy: Int) = v.counts[gy * Vectorscope.GRID + gx]

    @Test fun `GRID is 48 and empty() is all zeros`() {
        assertEquals(48, Vectorscope.GRID)
        val e = Vectorscope.empty()
        assertEquals(48 * 48, e.counts.size)
        assertEquals(48 * 48, e.rSum.size)
        assertEquals(48 * 48, e.gSum.size)
        assertEquals(48 * 48, e.bSum.size)
        assertTrue(e.counts.all { it == 0 })
        assertTrue(e.rSum.all { it == 0L })
        // empty cells report color 0
        assertEquals(0, e.colorAt(0, 0))
        assertEquals(0, e.colorAt(24, 24))
        assertEquals(0, e.colorAt(47, 47))
    }

    @Test fun `invalid dims return empty without reading the buffer`() {
        val tiny = ByteBuffer.allocate(4) // 1 pixel; real reads would blow past it
        listOf(
            Triple(0, 16, "w=0"), Triple(-4, 16, "w<0"),
            Triple(16, 0, "h=0"), Triple(16, -1, "h<0"), Triple(0, 0, "0x0"),
        ).forEach { (w, h, label) ->
            val v = vectorscopeFromRgba(tiny, w, h)
            assertTrue("$label counts", v.counts.all { it == 0 })
        }
    }

    @Test fun `gray collapses to the center dot`() {
        val v = vectorscopeFromRgba(rgbaBuf(16, 16) { _, _ -> intArrayOf(128, 128, 128, 255) }, 16, 16)
        // u = 0, v = 0 -> gx = gy = ((0+1)/2*48).toInt() = 24
        assertEquals(256, cell(v, 24, 24))
        assertEquals(256, v.counts.sum())
        assertEquals((0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128, v.colorAt(24, 24))
    }

    @Test fun `pure red lands at documented chroma (1, -0-5) - cell (47, 12)`() {
        val v = vectorscopeFromRgba(rgbaBuf(16, 16) { _, _ -> intArrayOf(255, 0, 0, 255) }, 16, 16)
        // u = 1.0 -> gx = 48 -> coerced 47; v = -0.5 -> gy = (0.5/2*48) = 12
        assertEquals(256, cell(v, 47, 12))
        assertEquals(256, v.counts.sum())
        assertEquals((0xFF shl 24) or (255 shl 16), v.colorAt(47, 12))
    }

    @Test fun `pure green lands at (0, 1) - cell (24, 47)`() {
        val v = vectorscopeFromRgba(rgbaBuf(8, 8) { _, _ -> intArrayOf(0, 255, 0, 255) }, 8, 8)
        // u = 0 -> gx = 24; v = 1.0 -> gy = 48 -> coerced 47
        assertEquals(64, cell(v, 24, 47))
        assertEquals(64, v.counts.sum())
    }

    @Test fun `pure blue lands at (-1, -0-5) - cell (0, 12)`() {
        val v = vectorscopeFromRgba(rgbaBuf(8, 8) { _, _ -> intArrayOf(0, 0, 255, 255) }, 8, 8)
        // u = -1 -> gx = 0; v = -0.5 -> gy = 12
        assertEquals(64, cell(v, 0, 12))
        assertEquals(64, v.counts.sum())
    }

    @Test fun `pure magenta lands at (0, -1) - cell (24, 0)`() {
        val v = vectorscopeFromRgba(rgbaBuf(8, 8) { _, _ -> intArrayOf(255, 0, 255, 255) }, 8, 8)
        // u = 0 -> gx = 24; v = -1.0 -> gy = 0
        assertEquals(64, cell(v, 24, 0))
        assertEquals(64, v.counts.sum())
    }

    @Test fun `near-black pixels are skipped - hue noise must not smear the center`() {
        val v = vectorscopeFromRgba(rgbaBuf(16, 16) { _, _ -> intArrayOf(10, 10, 10, 255) }, 16, 16)
        assertEquals(0, v.counts.sum())
        // boundary: max channel exactly 16 is still skipped, 17 is counted
        val v16 = vectorscopeFromRgba(rgbaBuf(4, 4) { _, _ -> intArrayOf(16, 16, 16, 255) }, 4, 4)
        assertEquals(0, v16.counts.sum())
        val v17 = vectorscopeFromRgba(rgbaBuf(4, 4) { _, _ -> intArrayOf(17, 0, 0, 255) }, 4, 4)
        assertEquals(16, v17.counts.sum())
    }

    @Test fun `skin tone reference constants match the documented warm-skin derivation`() {
        assertEquals(145f / 255f, SKIN_TONE_U, 1e-6f)   // (255-110)/255
        assertEquals(-25f / 510f, SKIN_TONE_V, 1e-6f)  // (2*170-255-110)/510
    }

    @Test fun `warm skin tone falls near the skin-tone line angle`() {
        val v = vectorscopeFromRgba(rgbaBuf(16, 16) { _, _ -> intArrayOf(255, 170, 110, 255) }, 16, 16)
        // u = 0.569 -> gx = (1.569/2*48) = 37.65 -> 37; v = -0.049 -> gy = (0.951/2*48) = 22.8 -> 22
        assertEquals(256, cell(v, 37, 22))
    }

    @Test fun `large frame strides to exactly the documented sample budget`() {
        // 400x400 = 160000 px -> stride = 160000/24576 = 6 -> samples i in {0,6,...,159996}
        val v = vectorscopeFromRgba(rgbaBuf(400, 400) { _, _ -> intArrayOf(200, 100, 50, 255) }, 400, 400)
        assertEquals(26667, v.counts.sum())
        assertEquals(26667L * 200L, v.rSum.sum())
    }

    @Test fun `buffer position and limit are not mutated`() {
        val buf = rgbaBuf(16, 16) { _, _ -> intArrayOf(100, 150, 200, 255) }
        buf.position(7)
        buf.limit(buf.capacity())
        val v = vectorscopeFromRgba(buf, 16, 16)
        assertEquals(7, buf.position())
        assertEquals(buf.capacity(), buf.limit())
        // correct read must still land through the markers
        assertEquals(256, v.counts.sum())
    }

    @Test fun `colorAt averages the accumulated cell color`() {
        val v = vectorscopeFromRgba(rgbaBuf(8, 8) { _, _ -> intArrayOf(100, 150, 200, 255) }, 8, 8)
        // u = -100/255 -> gx = ((0.6078)/2*48) = 14; v = 0 -> gy = 24
        assertEquals(64, cell(v, 14, 24))
        assertEquals((0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200, v.colorAt(14, 24))
    }

    @Test fun `deterministic across runs`() {
        val rnd = java.util.Random(7)
        val buf = rgbaBuf(40, 40) { _, _ ->
            intArrayOf(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), 255)
        }
        val a = vectorscopeFromRgba(buf, 40, 40)
        val b = vectorscopeFromRgba(buf, 40, 40)
        assertTrue(a.counts.contentEquals(b.counts))
        assertTrue(a.rSum.contentEquals(b.rSum))
    }
}
