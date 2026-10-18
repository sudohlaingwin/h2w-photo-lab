package com.lumora.photoeditor

import com.lumora.photoeditor.engine.RetouchEngine
import com.lumora.photoeditor.engine.RetouchOp
import com.lumora.photoeditor.engine.RetouchSpot
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for overlapping retouch strokes.
 *
 * Guards the bug where every spot sampled the pristine source image, so a
 * second stroke over the same area silently discarded the first stroke's
 * work inside the overlap (e.g. a second skin/under-eye brush reintroduced
 * what the first one had just fixed). Spots must now accumulate: each spot
 * samples the composite of the source plus all previously applied spots.
 */
class RetouchAccumulationTest {

    private val w = 96
    private val h = 96

    /** Flat dark image: UNDER_EYE's lift is strong and measurable here. */
    private fun dark(): IntArray =
        IntArray(w * h) { (0xFF shl 24) or (40 shl 16) or (40 shl 8) or 40 }

    private fun spot(op: RetouchOp, x: Float, y: Float) = RetouchSpot(
        op = op, x = x, y = y, srcX = 0.1f, srcY = 0.1f,
        size = 0.25f, feather = 0.6f, strength = 1f,
    )

    private fun lum(px: Int) =
        0.2126f * ((px shr 16) and 0xFF) + 0.7152f * ((px shr 8) and 0xFF) +
            0.0722f * (px and 0xFF)

    /** Straight-alpha "over" composite of the overlay onto the base. */
    private fun composite(base: IntArray, over: IntArray): IntArray {
        val out = IntArray(base.size)
        for (i in base.indices) {
            val o = over[i]
            val a = ((o ushr 24) and 0xFF) / 255f
            if (a <= 0f) {
                out[i] = base[i]
                continue
            }
            fun ch(shift: Int): Int {
                val b = ((base[i] shr shift) and 0xFF).toFloat()
                val v = ((o shr shift) and 0xFF).toFloat()
                return (b * (1f - a) + v * a).toInt().coerceIn(0, 255)
            }
            out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    @Test fun overlappingUnderEye_accumulatesInsteadOfResetting() {
        val src = dark()
        val one = RetouchEngine.compositeSpots(
            listOf(spot(RetouchOp.UNDER_EYE, 0.5f, 0.5f)), w, h, src)
        val two = RetouchEngine.compositeSpots(
            listOf(
                spot(RetouchOp.UNDER_EYE, 0.5f, 0.5f),
                spot(RetouchOp.UNDER_EYE, 0.52f, 0.5f),
            ), w, h, src)
        val c1 = composite(src, one)
        val c2 = composite(src, two)
        val center = 48 * w + 48
        // Old behavior: the second stroke sampled the pristine source and
        // wrote the same values, so two strokes == one stroke here.
        assertTrue(
            "second overlapping stroke must build on the first " +
                "(one=${lum(c1[center])} two=${lum(c2[center])})",
            lum(c2[center]) > lum(c1[center]) + 1f,
        )
    }

    @Test fun distantSpots_stayIndependent() {
        val src = dark()
        val a = spot(RetouchOp.UNDER_EYE, 0.25f, 0.5f)
        val b = spot(RetouchOp.UNDER_EYE, 0.75f, 0.5f)
        val both = composite(src, RetouchEngine.compositeSpots(listOf(a, b), w, h, src))
        val onlyA = composite(src, RetouchEngine.compositeSpots(listOf(a), w, h, src))
        // near a's center the far-away second spot must change nothing
        val ax = (0.25f * w).toInt()
        assertEquals(
            lum(onlyA[48 * w + ax]), lum(both[48 * w + ax]), 0.5f,
        )
    }

    @Test fun emptySpots_yieldTransparentOverlay() {
        val out = RetouchEngine.compositeSpots(emptyList(), w, h, dark())
        assertTrue(out.all { it == 0 })
    }

    @Test fun overlappingHeal_accumulatesCoverage() {
        // dark blemish on a mid-gray field
        val src = IntArray(w * h) { (0xFF shl 24) or (120 shl 16) or (120 shl 8) or 120 }
        for (y in 40..56) for (x in 40..56) {
            val dx = x - 48; val dy = y - 48
            if (dx * dx + dy * dy <= 64) src[y * w + x] = (0xFF shl 24)
        }
        val spots = listOf(
            RetouchSpot(RetouchOp.HEAL, 0.5f, 0.5f, 0.15f, 0.5f, 0.2f, 0.6f, 1f),
            RetouchSpot(RetouchOp.HEAL, 0.53f, 0.5f, 0.15f, 0.5f, 0.2f, 0.6f, 1f),
        )
        val out = RetouchEngine.compositeSpots(spots, w, h, src)
        val done = composite(src, out)
        val before = lum(src[48 * w + 48])
        val after = lum(done[48 * w + 48])
        assertTrue(
            "overlapping heals must still remove the blemish " +
                "(before=$before after=$after)",
            abs(after - 120f) < abs(before - 120f),
        )
    }

    // --- Heal tone-field robustness (cycle 157) -----------------------------

    /**
     * Guards the Laplace-heal ghost bug: the tone plane was fit over the
     * feather-band ring with plain least squares, but a brush sized close
     * to the blemish puts the blemish's own edge inside that ring — the
     * outliers dragged the plane and left a faint ghost of the blemish in
     * the disc center (residual ~10/255 on a flat field). The fit is now
     * robust (median/MAD outlier rejection + refit), so the center must
     * heal to the background tone.
     */
    @Test fun singleHeal_centerFullyHealed_noGhost() {
        // dark blemish (r=6) on a mid-gray field, brush sized close to it
        val src = IntArray(w * h) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        for (y in 42..54) for (x in 42..54) {
            val dx = x - 48; val dy = y - 48
            if (dx * dx + dy * dy <= 36) src[y * w + x] = (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30
        }
        val spot = RetouchSpot(RetouchOp.HEAL, 0.5f, 0.5f, 0.15f, 0.5f, 0.16f, 0.6f, 1f)
        val done = composite(src, RetouchEngine.compositeSpots(listOf(spot), w, h, src))
        var sum = 0f; var n = 0
        for (y in 47..49) for (x in 47..49) {
            sum += abs(lum(done[y * w + x]) - 128f); n++
        }
        val residual = sum / n
        assertTrue(
            "heal center must reach the background tone (residual=$residual)",
            residual < 8f,
        )
    }

    // --- Skin-brush texture keep (cycle 122) --------------------------------

    /** High-frequency deterministic grain: real detail for the skin brush. */
    private fun textured(): IntArray =
        IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val grain = ((x * 37 + y * 91) % 51) - 25 // -25..+25
            val v = (128 + grain).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

    private fun skinSpot(textureKeep: Float? = null): RetouchSpot {
        val base = RetouchSpot(
            op = RetouchOp.SMOOTH_SKIN, x = 0.5f, y = 0.5f,
            srcX = 0.5f, srcY = 0.5f, size = 0.5f, feather = 0.6f, strength = 1f,
        )
        return if (textureKeep == null) base else base.copy(textureKeep = textureKeep)
    }

    @Test fun skinSpot_defaultTextureKeep_matchesLegacyBehavior() {
        // The new field defaults to 0.45f = the old hardcoded value, so spots
        // created before the slider existed (or without it) render bit-
        // identical to explicitly passing 0.45f — including deserialized
        // old saved states.
        val src = textured()
        val def = RetouchEngine.compositeSpots(listOf(skinSpot()), w, h, src)
        val explicit = RetouchEngine.compositeSpots(listOf(skinSpot(0.45f)), w, h, src)
        assertTrue(def.contentEquals(explicit))
    }

    @Test fun skinTextureKeep_oneRetainsMoreDetailThanZero() {
        val src = textured()
        // Mean absolute change over the spot interior (feathered rim
        // excluded): textureKeep=0 smooths away the grain, textureKeep=1
        // must leave the original pixels essentially untouched.
        fun meanErr(textureKeep: Float): Float {
            val over = RetouchEngine.compositeSpots(
                listOf(skinSpot(textureKeep)), w, h, src)
            var sum = 0f; var n = 0
            for (y in 40..56) for (x in 40..56) {
                val i = y * w + x
                val o = over[i]
                val a = ((o ushr 24) and 0xFF) / 255f
                val s = ((src[i] shr 16) and 0xFF).toFloat()
                val done = s * (1f - a) + ((o shr 16) and 0xFF) * a
                sum += abs(done - s); n++
            }
            return sum / n
        }
        val errSmooth = meanErr(0f)
        val errDetailed = meanErr(1f)
        assertTrue(
            "textureKeep=1 must keep the original detail (err=$errDetailed) " +
                "better than textureKeep=0 (err=$errSmooth)",
            errDetailed < errSmooth * 0.5f,
        )
        assertTrue("textureKeep=0 must still smooth the grain (err=$errSmooth)",
            errSmooth > 1f)
    }
}
