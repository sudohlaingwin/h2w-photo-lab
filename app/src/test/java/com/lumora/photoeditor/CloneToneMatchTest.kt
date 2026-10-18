package com.lumora.photoeditor

import com.lumora.photoeditor.engine.RetouchEngine
import com.lumora.photoeditor.engine.InpaintingCore
import com.lumora.photoeditor.engine.RetouchOp
import com.lumora.photoeditor.engine.RetouchSpot
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cycle 134: the Clone tool is now tone-adaptive across the whole disc
 * (offset = smooth(dest) − smooth(src) at full strength, blended by the
 * feathered alpha) instead of copying the interior exactly and bending tone
 * only in a rim band. A stamp from a darker patch onto a lighter area must
 * land at the destination's tone while keeping the source's texture.
 *
 * These tests go through [RetouchEngine.compositeSpots] (pure IntArray,
 * JVM-runnable) and read the straight-alpha overlay it produces.
 */
class CloneToneMatchTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun chan(px: Int, shift: Int) = (px shr shift) and 0xFF

    /** Bilinear sample of one packed channel (test-side reference). */
    private fun bilinear(
        px: IntArray, w: Int, h: Int, fx: Double, fy: Double, shift: Int,
    ): Double {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val tx = (fx - x0).coerceIn(0.0, 1.0)
        val ty = (fy - y0).coerceIn(0.0, 1.0)
        fun v(x: Int, y: Int) = ((px[y * w + x] shr shift) and 0xFF).toDouble()
        val top = v(x0, y0) * (1 - tx) + v(x1, y0) * tx
        val bot = v(x0, y1) * (1 - tx) + v(x1, y1) * tx
        return top * (1 - ty) + bot * ty
    }

    /** Clone from UV (0.25, 0.5) to (0.75, 0.5) on a 200x100 image. */
    private fun cloneSpot(
        size: Float = 0.2f, // radius = 20px
        feather: Float = 0.5f,
        strength: Float = 1f,
    ) = RetouchSpot(
        op = RetouchOp.CLONE,
        x = 0.75f, y = 0.5f,
        srcX = 0.25f, srcY = 0.5f,
        size = size, feather = feather, strength = strength,
    )

    @Test
    fun cloneInteriorMatchesDestinationTone() {
        // Left half tone 100, right half tone 180.
        val w = 200; val h = 100
        val img = IntArray(w * h) { i ->
            val v = if (i % w < w / 2) 100 else 180
            argb(v, v, v)
        }
        val out = RetouchEngine.compositeSpots(listOf(cloneSpot()), w, h, img)
        // Interior block around the disc center (nd <= ~0.3, falloff == 1).
        var sum = 0L; var n = 0
        for (y in 47..53) for (x in 147..153) {
            val p = out[y * w + x]
            assertTrue("interior must be opaque", chan(p, 24) > 250)
            sum += chan(p, 16); n++
        }
        val mean = sum.toDouble() / n
        // Tone 100 source bent to the 180 destination tone.
        assertTrue("mean=$mean, expected ~180", abs(mean - 180) < 12)
    }

    @Test
    fun cloneKeepsSourceTexture() {
        // Source patch: checkerboard ±40 around tone 100 (8px cells);
        // destination: flat tone 180.
        val w = 200; val h = 100
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = if (x < w / 2) {
                val cell = (x / 8 + y / 8) % 2
                if (cell == 0) 140 else 60
            } else 180
            argb(v, v, v)
        }
        val out = RetouchEngine.compositeSpots(listOf(cloneSpot()), w, h, img)
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 45..55) for (x in 145..155) {
            val p = out[y * w + x]
            val v = chan(p, 16).toDouble()
            sum += v; sum2 += v * v; n++
        }
        val mean = sum / n
        val std = sqrt(sum2 / n - mean * mean)
        // Tone matched to destination...
        assertTrue("mean=$mean, expected ~180", abs(mean - 180) < 14)
        // ...while the checkerboard amplitude survives (not blurred flat).
        assertTrue("std=$std, expected ~40", abs(std - 40) < 12)
    }

    @Test
    fun cloneToneReferenceCoRegisteredWithTexture() {
        // The Clone stamp's texture is bilinearly sampled at the exact
        // fractional source coordinate (x+dx, y+dy); its tone correction
        // smooth(dest) − smooth(src) must be evaluated at that SAME point.
        // Sampling the tone field at the floored pixel (the old integer
        // index) shifted the correction by up to a pixel relative to the
        // stamped texture — a faint tone fringe wherever the source patch
        // crossed a hard edge.
        val w = 200; val h = 100
        // Source half (x < 100): sharp step edge at x = 62 (60 -> 200).
        // Dest half (x >= 100): flat 160.
        val img = IntArray(w * h) { i ->
            val x = i % w
            val v = if (x < 100) { if (x < 62) 60 else 200 } else 160
            argb(v, v, v)
        }
        // Clone from (60.3, 50) to (140, 50): dx = -79.7, fractional 0.7.
        val spot = RetouchSpot(
            op = RetouchOp.CLONE,
            x = 0.7f, y = 0.5f,
            srcX = 0.3015f, srcY = 0.5f,
            size = 0.12f, feather = 0.5f, strength = 1f,
        )
        val overlay = RetouchEngine.compositeSpots(listOf(spot), w, h, img)
        // Probe dest pixel (142, 50): disc interior (falloff == 1); its
        // source sample lands on the blur transition of the source step
        // edge, where floored vs bilinear tone sampling differ most
        // (~3 levels — the old code fails this assertion).
        val px = 142; val py = 50
        val o = overlay[py * w + px]
        assertTrue("probe must be fully covered", chan(o, 24) > 250)
        // Reference tone fields via the real box blur (translation-
        // invariant: identical to the engine's region blurs at these
        // interior sample points, both >= blurR inside their regions).
        val blurR = 6
        val fullBlur = InpaintingCore.boxBlurRgb(img, w, h, blurR)
        val dx = 0.3015f * w - 0.7f * w
        val trueFx = (px + dx).toDouble()
        val dRef = chan(fullBlur[py * w + px], 16).toDouble()
        val sRef = bilinear(fullBlur, w, h, trueFx, py.toDouble(), 16)
        // Texture at the sample point is locally constant 200 (both
        // bilinear neighbours are 200), so the engine's texture sample is
        // exactly 200 regardless of bilinear rounding details.
        val expected = (200.0 + (dRef - sRef)).roundToInt().coerceIn(0, 255)
        val actual = chan(o, 16)
        assertTrue("actual=$actual expected=$expected", abs(actual - expected) <= 2)
    }

    @Test
    fun cloneOutsideDiscStaysTransparent() {
        val w = 200; val h = 100
        val img = IntArray(w * h) { argb(120, 120, 120) }
        val out = RetouchEngine.compositeSpots(listOf(cloneSpot()), w, h, img)
        // Disc center (150, 50), radius 20: corner (10, 10) is far outside.
        assertEquals(0, chan(out[10 * w + 10], 24))
        assertEquals(0, chan(out[10 * w + 150], 24))
        // And untouched pixels carry no color contribution either.
        assertEquals(0, out[10 * w + 10])
    }

    @Test
    fun cloneZeroStrengthIsNoop() {
        val w = 200; val h = 100
        val img = IntArray(w * h) { i ->
            val v = if (i % w < w / 2) 100 else 180
            argb(v, v, v)
        }
        val out = RetouchEngine.compositeSpots(
            listOf(cloneSpot(strength = 0f)), w, h, img)
        assertTrue("overlay must be fully transparent", out.all { it == 0 })
    }

    @Test
    fun cloneTransitionBandHasNoHalo() {
        // Regression: the tone offset used to be weighted by the feathered
        // falloff `f` in the stamp color AND blended by `f` in the alpha, a
        // double weighting that left a residual of −Δ·f·(1−f) in the
        // transition band (peak at f = 0.5) — a visible halo ring on large
        // smooth areas. The offset now applies at full strength and only
        // the alpha melts the stamp in, so the band must sit at the
        // destination tone like the interior does.
        val w = 200; val h = 100
        // Smooth grain-free gradient: source side darker than dest side.
        val img = IntArray(w * h) { i ->
            val v = (100 + 80 * (i % w) / w).toInt()
            argb(v, v, v)
        }
        val spot = cloneSpot(size = 0.3f, feather = 0.6f) // radius 30px at (150, 50)
        val overlay = RetouchEngine.compositeSpots(listOf(spot), w, h, img)
        // Shader-exact composite of the straight-alpha overlay.
        var sumBand = 0.0; var nBand = 0
        var sumIn = 0.0; var nIn = 0
        val cx = 150f; val cy = 50f; val radius = 30f
        for (y in 0 until h) for (x in 0 until w) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / radius
            val o = overlay[y * w + x]
            val a = chan(o, 24) / 255.0
            if (a <= 0.0) continue
            val s = img[y * w + x]
            val r = chan(s, 16) * (1 - a) + chan(o, 16) * a
            val residual = r - chan(s, 16)
            if (d in 0.55..0.85) { sumBand += residual; nBand++ }
            else if (d < 0.4) { sumIn += residual; nIn++ }
        }
        assertTrue("band sampled", nBand > 100)
        val bandMean = sumBand / nBand
        val inMean = sumIn / nIn
        // Interior is the reference (fully tone-matched); the band must not
        // deviate from it systematically. Old code: bandMean ≈ −4.
        assertTrue("bandMean=$bandMean inMean=$inMean",
            abs(bandMean - inMean) < 2.5)
    }
}
