package com.lumora.photoeditor

import com.lumora.photoeditor.engine.ImageMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ImageMathTest {

    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-4f, msg: String = "") {
        assertTrue("$msg expected=$expected actual=$actual", abs(expected - actual) <= eps)
    }

    @Test fun exposure_identityAtZero() {
        assertNear(0.5f, ImageMath.exposure(0.5f, 0f))
    }

    @Test fun exposure_oneStopDoubles() {
        assertNear(1.0f, ImageMath.exposure(0.5f, 1f))
        assertNear(0.25f, ImageMath.exposure(0.5f, -1f))
    }

    @Test fun exposure_monotonic() {
        var prev = -1f
        for (ev in -5..5) {
            val v = ImageMath.exposure(0.4f, ev.toFloat())
            assertTrue("ev=$ev not monotonic", v > prev)
            prev = v
        }
    }

    @Test fun whiteBalance_identityAtZero() {
        val (r, g, b) = ImageMath.whiteBalance(0.4f, 0.5f, 0.6f, 0f, 0f).let {
            Triple(it[0], it[1], it[2])
        }
        assertNear(0.4f, r); assertNear(0.5f, g); assertNear(0.6f, b)
    }

    @Test fun whiteBalance_warmsAndCools() {
        val warm = ImageMath.whiteBalance(0.5f, 0.5f, 0.5f, 1f, 0f)
        assertTrue("warm temp should raise red", warm[0] > 0.5f)
        assertTrue("warm temp should lower blue", warm[2] < 0.5f)
        val cool = ImageMath.whiteBalance(0.5f, 0.5f, 0.5f, -1f, 0f)
        assertTrue(cool[0] < 0.5f)
        assertTrue(cool[2] > 0.5f)
        val magenta = ImageMath.whiteBalance(0.5f, 0.5f, 0.5f, 0f, 1f)
        // Lightroom convention: positive tint is magenta, i.e. it cuts
        // green (red/blue stay); negative tint adds green.
        assertTrue("positive (magenta) tint should lower green", magenta[1] < 0.5f)
        assertNear(0.5f, magenta[0]); assertNear(0.5f, magenta[2])
        val green = ImageMath.whiteBalance(0.5f, 0.5f, 0.5f, 0f, -1f)
        assertTrue("negative (green) tint should raise green", green[1] > 0.5f)
    }

    @Test fun parametric_shadowsAffectDarksNotBrights() {
        val dark = ImageMath.parametric(0.1f, 0.1f, 0.1f, 0f, 1f, 0f, 0f)
        val bright = ImageMath.parametric(0.9f, 0.9f, 0.9f, 0f, 1f, 0f, 0f)
        assertTrue("shadows+ should lift darks", dark[0] > 0.1f)
        assertNear(0.9f, bright[0], 0.02f, "shadows+ should barely touch brights")
    }

    @Test fun parametric_highlightsAffectBrightsNotDarks() {
        val dark = ImageMath.parametric(0.1f, 0.1f, 0.1f, -1f, 0f, 0f, 0f)
        val bright = ImageMath.parametric(0.9f, 0.9f, 0.9f, -1f, 0f, 0f, 0f)
        assertNear(0.1f, dark[0], 0.02f, "highlights- should barely touch darks")
        assertTrue("highlights- should lower brights", bright[0] < 0.9f)
    }

    @Test fun contrast_identityAndExtremes() {
        assertNear(0.3f, ImageMath.contrast(0.3f, 0f))
        assertNear(0.5f, ImageMath.contrast(0.3f, -1f), 1e-3f) // flat gray
        assertTrue(ImageMath.contrast(0.8f, 1f) > 0.8f)
        assertTrue(ImageMath.contrast(0.2f, 1f) < 0.2f)
    }

    @Test fun hsl_roundTrip() {
        val cases = listOf(
            floatArrayOf(0.8f, 0.2f, 0.1f),
            floatArrayOf(0.1f, 0.7f, 0.3f),
            floatArrayOf(0.2f, 0.3f, 0.9f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )
        for ((r, g, b) in cases) {
            val hsl = ImageMath.rgb2hsl(r, g, b)
            val back = ImageMath.hsl2rgb(hsl[0], hsl[1], hsl[2])
            assertNear(r, back[0], 1e-3f, "r roundtrip")
            assertNear(g, back[1], 1e-3f, "g roundtrip")
            assertNear(b, back[2], 1e-3f, "b roundtrip")
        }
    }

    @Test fun hslMixer_identityWithZeroAdjustments() {
        val none = Array(8) { Triple(0f, 0f, 0f) }
        val out = ImageMath.hslMixer(0.7f, 0.2f, 0.15f, none)
        assertNear(0.7f, out[0], 2e-3f)
        assertNear(0.2f, out[1], 2e-3f)
        assertNear(0.15f, out[2], 2e-3f)
    }

    @Test fun hslMixer_redSaturationBoost() {
        val adj = Array(8) { Triple(0f, 0f, 0f) }
        adj[0] = Triple(0f, 1f, 0f) // red +100 sat
        val before = ImageMath.rgb2hsl(0.6f, 0.25f, 0.2f)
        val out = ImageMath.hslMixer(0.6f, 0.25f, 0.2f, adj)
        val after = ImageMath.rgb2hsl(out[0], out[1], out[2])
        assertTrue("red sat boost should raise saturation", after[1] > before[1])
        // green pixel should be nearly unaffected by the red range
        val green = ImageMath.hslMixer(0.2f, 0.6f, 0.25f, adj)
        assertNear(0.2f, green[0], 0.03f)
        assertNear(0.6f, green[1], 0.03f)
    }

    @Test fun vibrance_identityAtZero() {
        val out = ImageMath.vibrance(0.6f, 0.3f, 0.2f, 0f)
        assertNear(0.6f, out[0], 1e-4f)
        assertNear(0.3f, out[1], 1e-4f)
    }

    @Test fun vibrance_increasesSaturation() {
        // Basic sanity: positive vibrance increases saturation of a muted color.
        val before = floatArrayOf(0.55f, 0.5f, 0.45f)
        val after = ImageMath.vibrance(before[0], before[1], before[2], 0.5f)
        fun satOf(o: FloatArray): Float {
            val mx = maxOf(o[0], o[1], o[2]); val mn = minOf(o[0], o[1], o[2])
            return (mx - mn) / (mx + mn).coerceAtLeast(1e-4f)
        }
        assertTrue(satOf(after) > satOf(before))
        // Zero amount is identity.
        val same = ImageMath.vibrance(before[0], before[1], before[2], 0f)
        assertNear(before[0], same[0], 1e-4f)
        assertNear(before[1], same[1], 1e-4f)
        assertNear(before[2], same[2], 1e-4f)
    }

    @Test fun linearGradient_factor() {
        val shape = listOf(0f, 0f, 1f, 0f)
        assertNear(0f, ImageMath.linearGradientFactor(0f, 0.5f, shape))
        assertNear(1f, ImageMath.linearGradientFactor(1f, 0.5f, shape))
        assertNear(0.5f, ImageMath.linearGradientFactor(0.5f, 0.9f, shape))
        // clamped outside
        assertNear(0f, ImageMath.linearGradientFactor(-2f, 0.5f, shape))
        assertNear(1f, ImageMath.linearGradientFactor(2f, 0.5f, shape))
    }

    @Test fun radialGradient_factor() {
        val shape = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0f, 0.5f)
        assertNear(1f, ImageMath.radialGradientFactor(0.5f, 0.5f, shape), 1e-3f)
        assertNear(0f, ImageMath.radialGradientFactor(1f, 0.5f, shape), 0.05f)
        // d=0.75 is midway through the feather falloff (0.5..1.0)
        val mid = ImageMath.radialGradientFactor(0.875f, 0.5f, shape)
        assertTrue("mid should be between", mid > 0.05f && mid < 0.95f)
    }

    @Test fun brushFalloff_shape() {
        assertNear(1f, ImageMath.brushFalloff(0f, 0.5f), 1e-3f)
        assertNear(0f, ImageMath.brushFalloff(1f, 0.5f), 1e-3f)
        // 0.75 is midway through the feather falloff (0.5..1.0)
        val mid = ImageMath.brushFalloff(0.75f, 0.5f)
        assertTrue(mid > 0f && mid < 1f)
        // harder feather -> falloff starts later, so value is higher at d=0.9
        assertTrue(ImageMath.brushFalloff(0.9f, 0.1f) > ImageMath.brushFalloff(0.9f, 0.9f))
    }

    @Test fun luma_rec709() {
        assertNear(1f, ImageMath.luma(1f, 1f, 1f))
        assertNear(0f, ImageMath.luma(0f, 0f, 0f))
        assertNear(0.2126f, ImageMath.luma(1f, 0f, 0f), 1e-4f)
        assertNear(0.7152f, ImageMath.luma(0f, 1f, 0f), 1e-4f)
    }

    @Test fun hsl2rgbInto_matchesHsl2rgb() {
        // The non-allocating twin must be bit-identical to the allocating
        // version, or the per-frame grade-wheel uploads would drift.
        val cases = listOf(
            Triple(0f, 0f, 0.5f),
            Triple(0.33f, 0.8f, 0.5f),
            Triple(0.66f, 1f, 0.25f),
            Triple(0.15f, 0.4f, 0.9f),
            Triple(0.9f, 1e-7f, 0.5f), // near-zero saturation branch
        )
        for ((h, s, l) in cases) {
            val expected = ImageMath.hsl2rgb(h, s, l)
            val out = FloatArray(3)
            val got = ImageMath.hsl2rgbInto(h, s, l, out)
            assertSame("must return the passed buffer", out, got)
            for (i in 0..2) assertNear(expected[i], got[i], 1e-6f, "h=$h s=$s l=$l ch=$i")
        }
    }

    @Test fun wheelToRgbInto_matchesWheelToRgb() {
        // GlEngine's per-frame grade-wheel path must produce exactly the
        // same colors as the old allocating version.
        val wheels = listOf(
            com.lumora.photoeditor.engine.GradeWheel(0f, 0f),
            com.lumora.photoeditor.engine.GradeWheel(210f, 60f),
            com.lumora.photoeditor.engine.GradeWheel(40f, 100f),
            com.lumora.photoeditor.engine.GradeWheel(370f, 45f), // wraps past 360
        )
        val out = FloatArray(3)
        for (w in wheels) {
            val expected = com.lumora.photoeditor.engine.GlEngine.wheelToRgb(w)
            val got = com.lumora.photoeditor.engine.GlEngine.wheelToRgbInto(w, out)
            for (i in 0..2) assertNear(expected[i], got[i], 1e-6f, "wheel=$w ch=$i")
        }
    }
}
