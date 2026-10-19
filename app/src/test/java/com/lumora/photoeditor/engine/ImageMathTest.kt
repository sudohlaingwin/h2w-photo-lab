package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * First-ever executed verification of [ImageMath] — the pure-Kotlin mirror
 * of the GLSL pipeline math that "must stay behaviorally identical to
 * Shaders.FRAGMENT". Pins the observable contract of every function:
 * tone math (luma/exposure/WB/parametric/contrast), color math
 * (HSL round-trips, vibrance/saturation), mask geometry (linear/radial/
 * brush falloff), and the deterministic selection engines (luminance /
 * depth / color range, sky heuristic, depth estimator). Bitmap-backed
 * wrappers execute in the kotlinc harness against signature-identical
 * android.graphics stubs; Gradle compiles this file against android.jar.
 */
class ImageMathTest {

    // ------------------------------------------------------------------
    // luma / exposure
    // ------------------------------------------------------------------

    @Test fun lumaRec709Weights() {
        assertEquals(0.2126f, ImageMath.luma(1f, 0f, 0f), 1e-6f)
        assertEquals(0.7152f, ImageMath.luma(0f, 1f, 0f), 1e-6f)
        assertEquals(0.0722f, ImageMath.luma(0f, 0f, 1f), 1e-6f)
        assertEquals(1f, ImageMath.luma(1f, 1f, 1f), 1e-6f)
        assertEquals(0f, ImageMath.luma(0f, 0f, 0f), 1e-6f)
    }

    @Test fun exposureZeroIsIdentity() {
        assertEquals(0.37f, ImageMath.exposure(0.37f, 0f), 1e-6f)
    }

    @Test fun exposureDoublesPerEv() {
        assertEquals(0.5f, ImageMath.exposure(0.25f, 1f), 1e-6f)
        assertEquals(0.25f, ImageMath.exposure(0.5f, -1f), 1e-6f)
        assertEquals(2f.pow(2.5f), ImageMath.exposure(1f, 2.5f), 1e-5f)
    }

    // ------------------------------------------------------------------
    // whiteBalance / pickWhiteBalance / autoWhiteBalance / presets
    // ------------------------------------------------------------------

    @Test fun whiteBalanceZeroIsIdentity() {
        assertArrayEquals(
            floatArrayOf(0.3f, 0.5f, 0.7f),
            ImageMath.whiteBalance(0.3f, 0.5f, 0.7f, 0f, 0f), 1e-6f)
    }

    @Test fun whiteBalanceTempWarmsAndTintShiftsGreen() {
        // temp=+1: r*1.42, g unchanged, b*0.58
        assertArrayEquals(
            floatArrayOf(1.42f, 0.5f, 0.29f),
            ImageMath.whiteBalance(1f, 0.5f, 0.5f, 1f, 0f), 1e-5f)
        // tint=+1: g*0.70 only (Lightroom convention: + = magenta)
        assertArrayEquals(
            floatArrayOf(0.4f, 0.35f, 0.6f),
            ImageMath.whiteBalance(0.4f, 0.5f, 0.6f, 0f, 1f), 1e-5f)
    }

    @Test fun pickWhiteBalanceDarkSampleIsNull() {
        assertNull(ImageMath.pickWhiteBalance(0.01f, 0.02f, 0.015f, 0f, 0f))
        assertNull(ImageMath.pickWhiteBalance(0f, 0f, 0f, 0.5f, -0.5f))
    }

    @Test fun pickWhiteBalanceNeutralSampleKeepsCurrentGains() {
        // A neutral displayed pixel means the current gains are already
        // right: the solver must return them back.
        val (t, ti) = ImageMath.pickWhiteBalance(0.5f, 0.5f, 0.5f, 0.3f, -0.2f)!!
        assertEquals(0.3f, t, 1e-3f)
        assertEquals(-0.2f, ti, 1e-3f)
    }

    @Test fun pickWhiteBalanceRoundTripNeutralizes() {
        // Warm source through identity gains, then solve: applying the
        // solved gains must land on neutral gray.
        val (t, ti) = ImageMath.pickWhiteBalance(0.8f, 0.6f, 0.4f, 0f, 0f)!!
        val out = ImageMath.whiteBalance(0.8f, 0.6f, 0.4f, t, ti)
        assertEquals(0.5333f, out[0], 2e-3f)
        assertEquals(0.5333f, out[1], 2e-3f)
        assertEquals(0.5333f, out[2], 2e-3f)
    }

    @Test fun wbPresetsSanity() {
        val ps = ImageMath.WB_PRESETS
        assertEquals(6, ps.size)
        assertEquals("Daylight", ps[0].label)
        assertEquals(0f, ps[0].temp, 0f)
        assertEquals(0f, ps[0].tint, 0f)
        assertTrue(ps.single { it.label == "Tungsten" }.temp < 0f) // cooling
        assertTrue(ps.single { it.label == "Fluorescent" }.tint > 0f) // -green
        assertEquals(ps.size, ps.map { it.label }.toSet().size) // unique labels
        ps.forEach {
            assertTrue(it.temp in -1f..1f)
            assertTrue(it.tint in -1f..1f)
        }
    }

    @Test fun autoWhiteBalanceDarkIsNull() {
        assertNull(ImageMath.autoWhiteBalance(0.01f, 0.01f, 0.01f))
    }

    @Test fun autoWhiteBalanceNeutralIsIdentity() {
        val (t, ti) = ImageMath.autoWhiteBalance(0.5f, 0.5f, 0.5f)!!
        assertEquals(0f, t, 1e-6f)
        assertEquals(0f, ti, 1e-6f)
    }

    @Test fun autoWhiteBalanceIsExactInverseOfWhiteBalance() {
        // Warm average -> cooling temp; applying the solved gains to the
        // average itself must produce exactly neutral gray.
        val (t, ti) = ImageMath.autoWhiteBalance(0.8f, 0.6f, 0.4f)!!
        assertTrue(t < 0f)
        val out = ImageMath.whiteBalance(0.8f, 0.6f, 0.4f, t, ti)
        assertEquals(0.5333f, out[0], 2e-3f)
        assertEquals(0.5333f, out[1], 2e-3f)
        assertEquals(0.5333f, out[2], 2e-3f)
    }

    // ------------------------------------------------------------------
    // parametric / contrast
    // ------------------------------------------------------------------

    @Test fun parametricZeroIsIdentity() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0.5f, 0.8f),
            ImageMath.parametric(0.2f, 0.5f, 0.8f, 0f, 0f, 0f, 0f), 1e-6f)
    }

    @Test fun parametricWhitesBoostBrightPixel() {
        // l=1: highlights weight 1, whites weight 1 -> ev = 0.9
        val f = 2f.pow(0.9f)
        assertArrayEquals(
            floatArrayOf(f, f, f),
            ImageMath.parametric(1f, 1f, 1f, 0f, 0f, 1f, 0f), 1e-4f)
    }

    @Test fun parametricShadowsLiftDarkPixel() {
        val out = ImageMath.parametric(0.05f, 0.05f, 0.05f, 0f, 1f, 0f, 0f)
        assertTrue(out[0] > 0.05f) // lifted, not darkened
        assertEquals(out[0], out[1], 1e-6f) // grayscale stays grayscale
        assertEquals(out[1], out[2], 1e-6f)
    }

    @Test fun contrastZeroIsIdentityMidGrayFixed() {
        assertEquals(0.7f, ImageMath.contrast(0.7f, 0f), 1e-6f)
        assertEquals(0.5f, ImageMath.contrast(0.5f, 1f), 1e-6f)
        assertEquals(0.5f, ImageMath.contrast(0.5f, -0.7f), 1e-6f)
    }

    @Test fun contrastMinusOneFlattens() {
        assertEquals(0.5f, ImageMath.contrast(0.9f, -1f), 1e-6f)
        assertEquals(0.5f, ImageMath.contrast(0.1f, -1f), 1e-6f)
    }

    @Test fun contrastFullBoost() {
        assertEquals(1f, ImageMath.contrast(0.75f, 1f), 1e-6f)
        assertEquals(0f, ImageMath.contrast(0.25f, 1f), 1e-6f)
    }

    // ------------------------------------------------------------------
    // HSL
    // ------------------------------------------------------------------

    @Test fun rgb2hslPrimaries() {
        assertArrayEquals(floatArrayOf(0f, 1f, 0.5f), ImageMath.rgb2hsl(1f, 0f, 0f), 1e-5f)
        assertArrayEquals(floatArrayOf(1f / 3f, 1f, 0.5f), ImageMath.rgb2hsl(0f, 1f, 0f), 1e-5f)
        assertArrayEquals(floatArrayOf(2f / 3f, 1f, 0.5f), ImageMath.rgb2hsl(0f, 0f, 1f), 1e-5f)
    }

    @Test fun rgb2hslAchromatic() {
        val g = ImageMath.rgb2hsl(0.5f, 0.5f, 0.5f)
        assertEquals(0f, g[1], 1e-6f)
        assertEquals(0.5f, g[2], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), ImageMath.rgb2hsl(1f, 1f, 1f), 1e-6f)
    }

    @Test fun hsl2rgbRed() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), ImageMath.hsl2rgb(0f, 1f, 0.5f), 1e-5f)
    }

    @Test fun rgbHslRoundTrip() {
        val colors = arrayOf(
            floatArrayOf(0.2f, 0.5f, 0.8f),
            floatArrayOf(0.9f, 0.1f, 0.3f),
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(0.15f, 0.85f, 0.4f),
        )
        for (c in colors) {
            val hsl = ImageMath.rgb2hsl(c[0], c[1], c[2])
            assertArrayEquals(c, ImageMath.hsl2rgb(hsl[0], hsl[1], hsl[2]), 1e-4f)
        }
    }

    @Test fun hsl2rgbIntoMatchesAndReusesBuffer() {
        val out = FloatArray(3)
        val ret = ImageMath.hsl2rgbInto(0.6f, 0.7f, 0.4f, out)
        assertSame(out, ret)
        assertArrayEquals(ImageMath.hsl2rgb(0.6f, 0.7f, 0.4f), out, 1e-6f)
        // achromatic fast path writes gray
        val g = FloatArray(3)
        ImageMath.hsl2rgbInto(0.9f, 0f, 0.25f, g)
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, 0.25f), g, 1e-6f)
    }

    @Test fun smoothstepEdges() {
        assertEquals(0f, ImageMath.smoothstep(0f, 1f, 0f), 1e-6f)
        assertEquals(1f, ImageMath.smoothstep(0f, 1f, 1f), 1e-6f)
        assertEquals(0.5f, ImageMath.smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertEquals(0f, ImageMath.smoothstep(0f, 1f, -2f), 1e-6f)
        assertEquals(1f, ImageMath.smoothstep(0f, 1f, 2f), 1e-6f)
    }

    @Test fun hslMixerIdentityAdjustments() {
        val zero = Array(8) { Triple(0f, 0f, 0f) }
        assertArrayEquals(
            floatArrayOf(0.2f, 0.5f, 0.8f),
            ImageMath.hslMixer(0.2f, 0.5f, 0.8f, zero), 1e-3f)
        assertArrayEquals(
            floatArrayOf(0.5f, 0.5f, 0.5f),
            ImageMath.hslMixer(0.5f, 0.5f, 0.5f, zero), 1e-6f)
    }

    @Test fun hslMixerRedHueShift() {
        val adj = Array(8) { Triple(0f, 0f, 0f) }
        adj[0] = Triple(1f, 0f, 0f) // full +hue on the red band
        val out = ImageMath.hslMixer(1f, 0f, 0f, adj)
        // red band center d=0 -> w=1 -> h = 0 + 1*0.09
        assertArrayEquals(ImageMath.hsl2rgb(0.09f, 1f, 0.5f), out, 1e-5f)
        assertTrue(out[1] > 0.3f) // visibly shifted away from pure red
    }

    // ------------------------------------------------------------------
    // vibrance / saturation
    // ------------------------------------------------------------------

    @Test fun vibranceZeroIsIdentityAndGrayUnchanged() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0.5f, 0.8f),
            ImageMath.vibrance(0.2f, 0.5f, 0.8f, 0f), 1e-6f)
        assertArrayEquals(
            floatArrayOf(0.4f, 0.4f, 0.4f),
            ImageMath.vibrance(0.4f, 0.4f, 0.4f, 1f), 1e-6f)
    }

    @Test fun saturationZeroIsIdentity() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0.5f, 0.8f),
            ImageMath.saturation(0.2f, 0.5f, 0.8f, 0f), 1e-6f)
    }

    @Test fun saturationFullBoostOnRed() {
        // f = 2, l = 0.2126
        val l = 0.2126f
        assertArrayEquals(
            floatArrayOf(l + (1f - l) * 2f, l - l * 2f, l - l * 2f),
            ImageMath.saturation(1f, 0f, 0f, 1f), 1e-5f)
    }

    @Test fun saturationGrayUnchanged() {
        assertArrayEquals(
            floatArrayOf(0.6f, 0.6f, 0.6f),
            ImageMath.saturation(0.6f, 0.6f, 0.6f, 0.8f), 1e-6f)
    }

    // ------------------------------------------------------------------
    // mask geometry
    // ------------------------------------------------------------------

    @Test fun linearGradientFactor() {
        assertEquals(0.3f, ImageMath.linearGradientFactor(0.3f, 0.9f, listOf(0f, 0f, 1f, 0f)), 1e-6f)
        assertEquals(0.75f, ImageMath.linearGradientFactor(0.5f, 0.25f, listOf(0f, 1f, 0f, 0f)), 1e-6f)
        assertEquals(0f, ImageMath.linearGradientFactor(-1f, 0.5f, listOf(0f, 0f, 1f, 0f)), 1e-6f)
        assertEquals(1f, ImageMath.linearGradientFactor(2f, 0.5f, listOf(0f, 0f, 1f, 0f)), 1e-6f)
        // degenerate: zero-length line and short shape -> 0
        assertEquals(0f, ImageMath.linearGradientFactor(0.5f, 0.5f, listOf(1f, 1f, 1f, 1f)), 1e-6f)
        assertEquals(0f, ImageMath.linearGradientFactor(0.5f, 0.5f, listOf(0f, 0f, 1f)), 1e-6f)
    }

    @Test fun radialGradientFactor() {
        val circle = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0f, 1f)
        assertEquals(1f, ImageMath.radialGradientFactor(0.5f, 0.5f, circle), 1e-6f)
        assertEquals(0.5f, ImageMath.radialGradientFactor(0.75f, 0.5f, circle), 1e-4f)
        assertEquals(0f, ImageMath.radialGradientFactor(1.5f, 0.5f, circle), 1e-6f)
        assertEquals(0f, ImageMath.radialGradientFactor(0.5f, 0.5f, listOf(0.5f, 0.5f)), 1e-6f)
        // 90-degree rotation swaps the ellipse axes
        val rot = listOf(0.5f, 0.5f, 0.5f, 0.25f, 90f, 1f)
        assertEquals(0.5f, ImageMath.radialGradientFactor(0.5f, 0.75f, rot), 1e-4f)
    }

    @Test fun brushFalloffShape() {
        assertEquals(1f, ImageMath.brushFalloff(0f, 0.5f), 1e-6f)
        assertEquals(0f, ImageMath.brushFalloff(1f, 0.5f), 1e-6f)
        assertEquals(0.5f, ImageMath.brushFalloff(0.5f, 1f), 1e-4f)
        assertEquals(0f, ImageMath.brushFalloff(2f, 1f), 1e-6f) // clamped
    }

    // ------------------------------------------------------------------
    // luminance range
    // ------------------------------------------------------------------

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val gray128 = 0xFF808080.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test fun luminanceRangeFullBandSelectsAll() {
        val px = intArrayOf(black, white, gray128, red)
        assertArrayEquals(
            intArrayOf(255, 255, 255, 255),
            ImageMath.luminanceRangeMask(px, 2, 2, 0f, 1f, 0.5f))
    }

    @Test fun luminanceRangeBandSelectsMidOnly() {
        val px = intArrayOf(black, white, gray128, red)
        // gray128 luma ~0.502 in band; red luma 0.2126 below; black/white out
        assertArrayEquals(
            intArrayOf(0, 0, 255, 0),
            ImageMath.luminanceRangeMask(px, 2, 2, 0.4f, 0.6f, 0f))
    }

    @Test fun luminanceRangeDegenerateIsEmpty() {
        val px = intArrayOf(white, white, white, white)
        assertArrayEquals(intArrayOf(0, 0, 0, 0),
            ImageMath.luminanceRangeMask(px, 2, 2, 0.6f, 0.4f, 0f))
        assertArrayEquals(intArrayOf(0, 0, 0, 0),
            ImageMath.luminanceRangeMask(intArrayOf(white, white), 2, 2, 0f, 1f, 0f))
    }

    // ------------------------------------------------------------------
    // depth
    // ------------------------------------------------------------------

    @Test fun estimateDepthFlatFieldIsZero() {
        val px = IntArray(8 * 8) { gray128 }
        val d = ImageMath.estimateDepth(px, 8, 8)
        assertEquals(64, d.size)
        d.forEach { assertEquals(0f, it, 1e-6f) }
    }

    @Test fun estimateDepthTooSmallIsZero() {
        val d = ImageMath.estimateDepth(IntArray(4) { white }, 2, 2)
        d.forEach { assertEquals(0f, it, 1e-6f) }
        val short = ImageMath.estimateDepth(IntArray(3) { white }, 4, 4)
        short.forEach { assertEquals(0f, it, 1e-6f) }
    }

    @Test fun estimateDepthSharpCenterIsNearest() {
        val px = IntArray(5 * 5) { black }
        px[2 * 5 + 2] = white
        val d = ImageMath.estimateDepth(px, 5, 5)
        assertEquals(1f, d.maxOrNull()!!, 1e-5f) // normalized by field max
        assertEquals(1f, d[2 * 5 + 2], 1e-5f) // sharpest pixel = nearest
    }

    @Test fun depthBandMaskSelectsMidBand() {
        val depth = floatArrayOf(0f, 0.5f, 1f)
        assertArrayEquals(
            intArrayOf(0, 255, 0),
            ImageMath.depthBandMask(depth, 3, 1, 0.4f, 0.6f, 0f))
    }

    @Test fun depthRangeMaskFlatFieldIsEmpty() {
        val px = IntArray(8 * 8) { gray128 }
        val m = ImageMath.depthRangeMask(px, 8, 8, 0.4f, 0.6f, 0f)
        assertTrue(m.all { it == 0 })
    }

    // ------------------------------------------------------------------
    // color range
    // ------------------------------------------------------------------

    @Test fun colorDistanceIdenticalIsZeroAndSymmetric() {
        assertEquals(0f, ImageMath.colorDistance01(1f, 0f, 0f, 1f, 0f, 0f), 1e-6f)
        val a = ImageMath.colorDistance01(0.9f, 0.1f, 0.2f, 0.1f, 0.8f, 0.3f)
        val b = ImageMath.colorDistance01(0.1f, 0.8f, 0.3f, 0.9f, 0.1f, 0.2f)
        assertEquals(a, b, 1e-6f)
    }

    @Test fun colorDistanceBlackWhite() {
        // redmean maxes out here: d2 = 2.5*1 + 4*1 + 2.5*1 = 9 -> 3/3 = 1
        assertEquals(1f,
            ImageMath.colorDistance01(0f, 0f, 0f, 1f, 1f, 1f), 1e-5f)
    }

    @Test fun colorRangeMaskSelectsTargetOnly() {
        val px = intArrayOf(red, 0xFF0000FF.toInt(), 0xFF00FF00.toInt())
        assertArrayEquals(
            intArrayOf(255, 0, 0),
            ImageMath.colorRangeMask(px, 3, 1, 1f, 0f, 0f, 0f, 0f))
    }

    @Test fun colorRangeMaskSamplesIsUnion() {
        val px = intArrayOf(red, 0xFF0000FF.toInt(), 0xFF00FF00.toInt())
        val samples = listOf(ColorSample(1f, 0f, 0f), ColorSample(0f, 0f, 1f))
        val m = ImageMath.colorRangeMaskSamples(px, 3, 1, samples, 0f, 0f)
        assertEquals(255, m[0]) // red selected by first swatch
        assertEquals(255, m[1]) // blue selected by second swatch
        assertEquals(0, m[2]) // green selected by neither
        // adding a swatch can only widen the selection
        val single = ImageMath.colorRangeMaskSamples(
            px, 3, 1, listOf(ColorSample(1f, 0f, 0f)), 0f, 0f)
        for (i in m.indices) assertTrue(m[i] >= single[i])
    }

    @Test fun colorRangeMaskEmptySamplesIsEmpty() {
        val px = intArrayOf(red, red, red)
        assertArrayEquals(intArrayOf(0, 0, 0),
            ImageMath.colorRangeMaskSamples(px, 3, 1, emptyList(), 0.5f, 0.5f))
        assertArrayEquals(intArrayOf(0, 0, 0),
            ImageMath.colorRangeMaskSamples(intArrayOf(red), 3, 1,
                listOf(ColorSample(1f, 0f, 0f)), 0.5f, 0.5f))
    }

    // ------------------------------------------------------------------
    // Bitmap-backed wrappers (execute in the kotlinc harness)
    // ------------------------------------------------------------------

    private fun solidBitmap(w: Int, h: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { color }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun alphaAt(b: Bitmap, x: Int, y: Int): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Color.alpha(px[y * b.width + x])
    }

    @Test fun meanLumaUniformGray() {
        val b = solidBitmap(64, 64, 0xFFC8C8C8.toInt())
        assertEquals(200f / 255f, ImageMath.meanLuma(b), 0.02f)
    }

    @Test fun meanLumaBlackAndWhite() {
        assertEquals(0f, ImageMath.meanLuma(solidBitmap(16, 16, black)), 1e-3f)
        assertEquals(1f, ImageMath.meanLuma(solidBitmap(16, 16, white)), 1e-3f)
    }

    @Test fun luminanceRangeBitmapAlphaConvention() {
        // left half black, right half white
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val px = IntArray(64) { i -> if (i % 8 < 4) black else white }
        src.setPixels(px, 0, 8, 0, 0, 8, 8)
        val m = ImageMath.luminanceRangeBitmap(src, 0.6f, 1f, 0f)
        assertEquals(Bitmap.Config.ALPHA_8, m.config)
        assertEquals(0, alphaAt(m, 64, 128)) // deep in the black half
        assertEquals(255, alphaAt(m, 192, 128)) // deep in the white half
    }

    @Test fun colorRangeBitmapSamplesAlphaConvention() {
        val src = solidBitmap(32, 32, red)
        val m = ImageMath.colorRangeBitmapSamples(
            src, listOf(ColorSample(1f, 0f, 0f)), 0f, 0f)
        assertEquals(Bitmap.Config.ALPHA_8, m.config)
        assertEquals(255, alphaAt(m, 128, 128))
    }

    @Test fun selectSkyAutoTopBiasAndAlphaConfig() {
        // clearly blue-dominant bright color: must select top over bottom
        val src = solidBitmap(64, 64, 0xFF3380E6.toInt())
        val m = ImageMath.selectSkyAuto(src)
        assertEquals(Bitmap.Config.ALPHA_8, m.config)
        val top = alphaAt(m, 32, 0)
        val bottom = alphaAt(m, 32, 63)
        assertTrue("top=$top bottom=$bottom", top > 200)
        assertTrue("top=$top bottom=$bottom", bottom < 40)
        assertTrue(top > bottom)
    }

    @Test fun depthRangeBitmapEdgeGivesFullSelection() {
        // hard vertical edge -> strong Laplacian -> depth 1 somewhere ->
        // lo=0.5/hi=1 band must contain a fully-selected pixel
        val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val px = IntArray(512 * 512) { i -> if (i % 512 < 256) black else white }
        src.setPixels(px, 0, 512, 0, 0, 512, 512)
        val m = ImageMath.depthRangeBitmap(src, 0.5f, 1f, 0f)
        assertEquals(Bitmap.Config.ALPHA_8, m.config)
        val all = IntArray(m.width * m.height)
        m.getPixels(all, 0, m.width, 0, 0, m.width, m.height)
        assertEquals(255, all.maxOf { Color.alpha(it) })
    }

    @Test fun depthRangeBitmapFlatFieldIsEmpty() {
        val src = solidBitmap(64, 64, gray128)
        val m = ImageMath.depthRangeBitmap(src, 0.5f, 1f, 0f)
        val all = IntArray(m.width * m.height)
        m.getPixels(all, 0, m.width, 0, 0, m.width, m.height)
        assertEquals(0, all.maxOf { Color.alpha(it) })
    }
}
