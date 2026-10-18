package com.lumora.photoeditor

import com.lumora.photoeditor.engine.InpaintingCore
import com.lumora.photoeditor.engine.RetouchEngine
import com.lumora.photoeditor.engine.RetouchOp
import com.lumora.photoeditor.engine.RetouchSpot
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import kotlin.math.abs

/**
 * Cycle: the Clone tool's tone field (smooth(dest) − smooth(src)) now stays
 * in float through [InpaintingCore.boxBlurRgbFloat] instead of being packed
 * to integers first. Packing quantized the offset in ±1 steps — faint
 * contour bands across large smooth gradients (skies, walls, skin) where the
 * offset ramps slowly. These tests pin the fix at both levels:
 *  - unit: the float blur agrees with the int blur within the packing
 *    error, and keeps the sub-integer ramp the int version stalls on;
 *  - end-to-end: a clone stamp on a smooth gradient shows no banding steps
 *    in its fully-opaque interior.
 *
 * Pure-Kotlin, JVM-runnable via RetouchEngine.compositeSpots.
 */
@Ignore("DO NOT RE-ENABLE: float tone failed quality test maxLap=3.0>1.2, reverted 2026-10-18")
class CloneFloatToneTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun chan(px: Int, shift: Int) = (px shr shift) and 0xFF

    @Test
    fun floatBlurAgreesWithIntBlurWithinPackingError() {
        // Deterministic pseudo-random texture, no structure.
        val w = 64; val h = 48
        val img = IntArray(w * h) { i ->
            val v = (i * 1103515245 + 12345).ushr(16) and 0xFF
            argb(v, (v * 7 + 13) and 0xFF, (v * 13 + 71) and 0xFF)
        }
        val intBlur = InpaintingCore.boxBlurRgb(img, w, h, 6)
        val floatBlur = InpaintingCore.boxBlurRgbFloat(img, w, h, 6)
        assertEquals(w * h * 3, floatBlur.size)
        for (i in 0 until w * h) {
            val f = floatArrayOf(floatBlur[i * 3], floatBlur[i * 3 + 1], floatBlur[i * 3 + 2])
            val p = intBlur[i]
            val e = intArrayOf(chan(p, 16), chan(p, 8), chan(p, 0))
            for (c in 0..2) {
                // boxBlurRgb packs with toInt() (truncation of a value that
                // the blur keeps inside [0, 255]), so the float must sit
                // within 1 level of the packed int — same math, no pack.
                assertTrue(
                    "pixel $i channel $c: float=${f[c]} int=${e[c]}",
                    abs(f[c] - e[c]) < 1.0f,
                )
                assertTrue("channel out of range", f[c] in 0f..255f)
            }
        }
    }

    @Test
    fun floatBlurKeepsSubIntegerRampThatIntBlurStallsOn() {
        // Shallow horizontal gradient 100 -> 180 over 200px: slope 0.4/px,
        // the classic banding scenario (the tone offset ramps ~0.4 levels
        // per pixel across the clone disc).
        val w = 200; val h = 60
        val img = IntArray(w * h) { i ->
            val v = (100 + 80 * (i % w) / (w - 1).toFloat()).toInt()
            argb(v, v, v)
        }
        val radius = 8
        val intBlur = InpaintingCore.boxBlurRgb(img, w, h, radius)
        val floatBlur = InpaintingCore.boxBlurRgbFloat(img, w, h, radius)
        val row = h / 2
        // Away from the edge-clamped rims the box of a linear ramp is the
        // ramp itself.
        val xs = 24 until (w - 24)
        var intPlateaus = 0
        var floatMaxStep = 0f
        var floatMinStep = Float.MAX_VALUE
        var prevI = -1
        var prevF = -1f
        for (x in xs) {
            val iv = chan(intBlur[row * w + x], 16)
            val fv = floatBlur[(row * w + x) * 3]
            if (prevI >= 0) {
                if (iv == prevI) intPlateaus++
                val step = fv - prevF
                if (step > floatMaxStep) floatMaxStep = step
                if (step < floatMinStep) floatMinStep = step
                // The float ramp must be strictly increasing (a stalled
                // float ramp would mean the blur itself is broken).
                assertTrue("float ramp stalled or reversed at x=$x", step > 0f)
            }
            prevI = iv; prevF = fv
        }
        // The int version demonstrably stalls: plateaus prove this test is
        // sensitive to the quantization the fix removes.
        assertTrue("expected int plateaus, found $intPlateaus", intPlateaus > 10)
        // The float version ramps smoothly at sub-integer steps (~0.4/px).
        assertTrue("floatMaxStep=$floatMaxStep", floatMaxStep < 1.0f)
        assertTrue("floatMinStep=$floatMinStep", floatMinStep > 0.1f)
    }

    @Test
    fun cloneStampInteriorHasNoBandingSteps() {
        // End-to-end: clone a smooth gradient onto another smooth gradient.
        // Left half ramps 100->140, right half 180->220; stamp centered at
        // (150, 50) radius 20, source at (50, 50).
        val w = 200; val h = 100
        val img = IntArray(w * h) { i ->
            val x = i % w
            val v = if (x < w / 2) (100 + 40 * x / (w / 2 - 1).toFloat()).toInt()
            else (180 + 40 * (x - w / 2) / (w / 2 - 1).toFloat()).toInt()
            argb(v, v, v)
        }
        val spot = RetouchSpot(
            op = RetouchOp.CLONE,
            x = 0.75f, y = 0.5f,      // dest (150, 50)
            srcX = 0.25f, srcY = 0.5f, // source (50, 50)
            size = 0.2f,              // radius = 20px
            feather = 0.5f,
            strength = 1f,
        )
        val overlay = RetouchEngine.compositeSpots(listOf(spot), w, h, img)
        // Fully-opaque interior row through the disc center (d < 0.4, the
        // feathered falloff is exactly 1 there): the overlay rgb IS the
        // stamp color. A quantized tone field would show as ±1 stair steps
        // in the ramp — the discrete Laplacian spikes at every step edge.
        val cy = 50
        var maxLap = 0.0
        var n = 0
        for (x in 142..158) {
            val a0 = chan(overlay[cy * w + x - 1], 24)
            val a1 = chan(overlay[cy * w + x], 24)
            val a2 = chan(overlay[cy * w + x + 1], 24)
            if (a0 < 254 || a1 < 254 || a2 < 254) continue
            val v0 = chan(overlay[cy * w + x - 1], 16).toDouble()
            val v1 = chan(overlay[cy * w + x], 16).toDouble()
            val v2 = chan(overlay[cy * w + x + 1], 16).toDouble()
            val lap = abs(v0 - 2 * v1 + v2)
            if (lap > maxLap) maxLap = lap
            n++
        }
        assertTrue("no fully-opaque interior samples", n > 8)
        // The ramp itself is linear (Laplacian ~0); allow headroom for the
        // bilinear resample and float rounding, but no step edges.
        assertTrue("maxLap=$maxLap over $n samples", maxLap < 1.2)
    }
}
