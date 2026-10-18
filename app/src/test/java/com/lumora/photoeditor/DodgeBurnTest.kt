package com.lumora.photoeditor

import com.lumora.photoeditor.engine.RetouchEngine
import com.lumora.photoeditor.engine.RetouchOp
import com.lumora.photoeditor.engine.RetouchRange
import com.lumora.photoeditor.engine.RetouchSpot
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Regression test for the DODGE/BURN brush sampling the wrong buffer.
 *
 * The brush must work on the composited pixel (the running source), not on
 * this spot's own output buffer (transparent -> black). Reading the output
 * buffer applied the gain to black: dodge painted a dark disc instead of
 * lifting the image, and burn worked through black instead of the pixel.
 */
class DodgeBurnTest {

    private val w = 64
    private val h = 64

    private fun flat(v: Int) = IntArray(w * h) { (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    private fun spot(op: RetouchOp, range: RetouchRange = RetouchRange.ALL) = RetouchSpot(
        op = op, x = 0.5f, y = 0.5f, srcX = 0.1f, srcY = 0.1f,
        size = 0.6f, feather = 0.4f, strength = 1f, range = range,
    )

    private fun lum(px: Int) =
        0.2126f * ((px shr 16) and 0xFF) + 0.7152f * ((px shr 8) and 0xFF) +
            0.0722f * (px and 0xFF)

    /** Straight-alpha "over" composite of the overlay onto the base, as the app does. */
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

    /** Dodge on mid-gray must clearly lift the disc center; burn must deepen it. */
    @Test fun dodgeLightens_burnDarkens() {
        val src = flat(128)
        val dodge = composite(src, RetouchEngine.compositeSpots(listOf(spot(RetouchOp.DODGE)), w, h, src))
        val burn = composite(src, RetouchEngine.compositeSpots(listOf(spot(RetouchOp.BURN)), w, h, src))
        val center = (h / 2) * w + (w / 2)
        val dodgeGain = lum(dodge[center]) - 128f
        val burnLoss = 128f - lum(burn[center])
        // Expected from gain = 2^0.9 in gamma space: ~+42 for dodge, ~-32 for burn.
        assertTrue("dodge must lift mid-gray (got +$dodgeGain)", dodgeGain > 20f)
        assertTrue("burn must deepen mid-gray (got -$burnLoss)", burnLoss > 15f)
    }

    /** Tonal-range gating must still work: dodging SHADOWS must spare bright pixels. */
    @Test fun shadowsRange_sparesHighlights() {
        val src = flat(225)
        val res = composite(
            src,
            RetouchEngine.compositeSpots(
                listOf(spot(RetouchOp.DODGE, RetouchRange.SHADOWS)), w, h, src),
        )
        val center = (h / 2) * w + (w / 2)
        assertTrue(
            "dodging SHADOWS must not touch highlights",
            abs(lum(res[center]) - 225f) < 1f,
        )
    }

    /** The disc edge stays soft: far outside the brush the image is untouched. */
    @Test fun outsideDisc_untouched() {
        val src = flat(128)
        val res = composite(
            src,
            RetouchEngine.compositeSpots(listOf(spot(RetouchOp.BURN)), w, h, src),
        )
        assertTrue("corner pixel must be untouched", abs(lum(res[0]) - 128f) < 1f)
    }
}
