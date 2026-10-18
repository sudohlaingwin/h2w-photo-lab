package com.lumora.photoeditor

import com.lumora.photoeditor.engine.RetouchEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Regression tests for the Heal tool's automatic source picker
 * ([RetouchEngine.findHealSourcePx]).
 *
 * Guards three real quality bugs in the picker:
 *  1. The reference tone was sampled from the target disc itself, so a dark
 *     blemish pulled the reference toward dark and the picker preferred dark
 *     patches (sometimes a second blemish). The reference must come from the
 *     annulus AROUND the target — the tone the heal has to blend into.
 *  2. The innermost candidate ring (1.7r) overlapped the target disc
 *     (1.7r < 2r), so the "clean" source could include the blemish itself.
 *  3. Candidates were scored on luminance alone, so an equally bright patch
 *     with the wrong color cast (e.g. cooler skin) could win and leave a
 *     tinted heal disc. Scoring now includes chrominance distance.
 */
class HealSourcePickerTest {

    private val w = 200
    private val h = 200

    /** Uniform background with a dark blemish disc at the center. */
    private fun blemishImage(bg: Int = 180, spot: Int = 40, spotR: Float = 12f): IntArray {
        val cx = w / 2f; val cy = h / 2f
        return IntArray(w * h) { i ->
            val x = (i % w).toFloat(); val y = (i / w).toFloat()
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val v = if (d <= spotR) spot else bg
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** Mean chroma magnitude (sqrt(a^2 + b^2), a = R-B, b = 2G-R-B) of a disc. */
    private fun discChroma(img: IntArray, cx: Float, cy: Float, r: Float): Float {
        var sumA = 0f; var sumB = 0f; var n = 0
        val x0 = (cx - r).toInt().coerceIn(0, w - 1)
        val x1 = (cx + r).toInt().coerceIn(0, w - 1)
        val y0 = (cy - r).toInt().coerceIn(0, h - 1)
        val y1 = (cy + r).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy > r * r) continue
            val c = img[y * w + x]
            val cr = ((c shr 16) and 0xFF).toFloat()
            val cg = ((c shr 8) and 0xFF).toFloat()
            val cb = (c and 0xFF).toFloat()
            sumA += cr - cb; sumB += 2f * cg - cr - cb; n++
        }
        if (n == 0) return 0f
        val a = sumA / n; val b = sumB / n
        return sqrt(a * a + b * b)
    }

    @Test fun `picks clean surrounding tone, not the blemish`() {
        val img = blemishImage()
        // sizeFrac -> radius ~= 12px, matching the blemish disc.
        val found = RetouchEngine.findHealSourcePx(img, w, h, 0.5f, 0.5f, sizeFrac = 24f / w)
        assertNotNull("should find a source on uniform background", found)
        val (ux, uy) = found!!
        val sx = ux * w; val sy = uy * h
        // the source disc must not overlap the blemish disc
        val dist = sqrt((sx - w / 2f) * (sx - w / 2f) + (sy - h / 2f) * (sy - h / 2f))
        assertTrue("source overlaps the blemish (dist=$dist)", dist >= 2.2f * 12f)
        // the source tone must match the background, not the dark spot
        val c = img[sy.toInt().coerceIn(0, h - 1) * w + sx.toInt().coerceIn(0, w - 1)]
        val v = (c shr 16) and 0xFF
        assertTrue(
            "source tone $v should match background ~180, not blemish ~40",
            abs(v - 180) < abs(v - 40),
        )
    }

    @Test fun `rejects when no clean tone exists`() {
        // entirely black frame: annulus mean < 4 -> null (tone can't be judged)
        val black = IntArray(w * h) { 0xFF000000.toInt() }
        assertNull(RetouchEngine.findHealSourcePx(black, w, h, 0.5f, 0.5f, 0.1f))
    }

    @Test fun `prefers tint-matched patch over equally bright wrong-cast patch`() {
        // Background is neutral gray (chroma 0); a warm-tinted disc sits at
        // the first probe position (angle 0 on the 2.3r ring) with the SAME
        // luminance as the background (~180) but a strong warm cast
        // (chroma ~39). Luminance-only scoring picks the tinted disc and the
        // heal comes back tinted; chroma-aware scoring must prefer a neutral
        // patch instead.
        val r = 12f
        val ringR = 2.3f * r
        val warmCx = w / 2f + ringR; val warmCy = h / 2f
        val img = blemishImage()
        val warm = (0xFF shl 24) or (197 shl 16) or (176 shl 8) or 158
        for (i in img.indices) {
            val x = (i % w).toFloat(); val y = (i / w).toFloat()
            val d = sqrt((x - warmCx) * (x - warmCx) + (y - warmCy) * (y - warmCy))
            if (d <= 11f) img[i] = warm
        }
        // sanity: the warm patch really is equally bright but tinted
        val warmLuma = 0.2126f * 197 + 0.7152f * 176 + 0.0722f * 158
        assertTrue("test setup: warm patch luma $warmLuma should be ~180",
            abs(warmLuma - 180f) < 3f)
        assertTrue("test setup: warm patch should be strongly tinted",
            discChroma(img, warmCx, warmCy, 11f) > 25f)

        val found = RetouchEngine.findHealSourcePx(img, w, h, 0.5f, 0.5f, sizeFrac = 24f / w)
        assertNotNull("should find a source", found)
        val (ux, uy) = found!!
        val sx = ux * w; val sy = uy * h
        // must NOT be the tinted disc: its center is the first probe point
        val distToWarm = sqrt((sx - warmCx) * (sx - warmCx) + (sy - warmCy) * (sy - warmCy))
        assertTrue("picked the tinted patch (dist=$distToWarm)", distToWarm > 2f * r)
        // the picked patch must be (near-)neutral, like the surroundings
        val pickedChroma = discChroma(img, sx, sy, r)
        assertTrue("picked patch is tinted (chroma=$pickedChroma)", pickedChroma < 10f)
    }

    @Test fun `returns null for tiny or malformed input`() {
        assertNull(RetouchEngine.findHealSourcePx(IntArray(4 * 4), 4, 4, 0.5f, 0.5f, 0.1f))
        assertNull(RetouchEngine.findHealSourcePx(IntArray(0), 200, 200, 0.5f, 0.5f, 0.1f))
    }
}
