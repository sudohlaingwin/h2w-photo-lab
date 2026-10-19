package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [RetouchEngine] — the core of every
 * spot-based retouch tool (heal, clone, skin smooth, dodge/burn, eye,
 * teeth, under-eye, red-eye).
 *
 * Why this file earns a cycle: the user judges this app on retouch quality
 * ("Wavelet Skin Retouch 'plastic face' at 60%" is the standing retouch
 * complaint), and these IntArray kernels are where a regression would hide.
 * The contracts pinned here are all directly observable and behavior-first:
 *  - [RetouchEngine.rangeWeight]: the darkroom range gate — full effect in
 *    the chosen tonal range, zero outside it, smooth ramps between;
 *  - [RetouchEngine.compositeInto]: guards (empty spots, undersized source,
 *    zero strength, REMOVE, off-image spots) leave everything transparent /
 *    untouched; range-gated dodge/burn are exact no-ops outside their range;
 *    full-strength dodge/burn move a mid-gray center the documented amount
 *    (~+42 / ~-32 levels at 0.9 EV) and never escape 0..255;
 *  - skin smoothing: textureKeep=1 is a true keep-all (documented
 *    collapse-to-original invariant), textureKeep=0 kills most noise
 *    variance — the plastic-face fix has a regression net;
 *  - color tools: red-eye leaves non-red pixels bit-exact and desaturates
 *    real red glare; teeth-whiten lifts the blue channel of yellow;
 *    eye-enhance lifts uniform gray by the documented 6%;
 *  - CLONE: the stamp carries the SOURCE texture while keeping the
 *    DESTINATION tone (the 2026-10-18 tone-match fix), with no change
 *    outside the spot bbox;
 *  - HEAL: a dark blemish on flat tone is removed to the surrounding tone;
 *  - [RetouchEngine.findHealSourcePx]: null guards, bounded UV output,
 *    deterministic.
 *
 * Bitmap-wrapping ([RetouchEngine.rasterize], [RetouchEngine.findHealSource])
 * is intentionally not covered here: plain Gradle/JVM unit tests have no
 * android.graphics implementation. The pure kernels above are 100% of the
 * decision-making code; the wrappers are getPixels/setPixels plumbing.
 */
class RetouchEngineTest {

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun gray(v: Int): Int =
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun flatGray(w: Int, h: Int, v: Int): IntArray =
        IntArray(w * h) { gray(v) }

    private fun lum(c: Int): Float =
        (0.2126f * ((c shr 16) and 0xFF) +
            0.7152f * ((c shr 8) and 0xFF) +
            0.0722f * (c and 0xFF)) / 255f

    /** Composite a single spot over [src] and return (overlay, composited). */
    private fun runSpot(
        spot: RetouchSpot, w: Int, h: Int, src: IntArray,
    ): Pair<IntArray, IntArray> {
        val compPx = src.copyOf()
        val overlay = RetouchEngine.compositeInto(listOf(spot), w, h, compPx)
        return overlay to compPx
    }

    private fun centerSpot(
        op: RetouchOp, w: Int, h: Int, size: Float,
        srcX: Float = 0.25f, srcY: Float = 0.25f,
        strength: Float = 1f, textureKeep: Float = 0.45f,
        range: RetouchRange = RetouchRange.ALL, feather: Float = 0.6f,
    ): RetouchSpot = RetouchSpot(
        op = op, x = 0.5f, y = 0.5f, srcX = srcX, srcY = srcY,
        size = size, feather = feather, strength = strength,
        textureKeep = textureKeep, range = range,
    )

    // ------------------------------------------------------------------
    // rangeWeight — the darkroom tonal-range gate
    // ------------------------------------------------------------------

    @Test fun t01_allRange_alwaysOne() {
        for (luma in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
            assertEquals(1f, RetouchEngine.rangeWeight(luma, RetouchRange.ALL), 1e-6f)
        }
    }

    @Test fun t02_shadows_fullInDeepShadows_zeroPastKnee() {
        assertEquals(1f, RetouchEngine.rangeWeight(0f, RetouchRange.SHADOWS), 1e-6f)
        assertEquals(1f, RetouchEngine.rangeWeight(0.15f, RetouchRange.SHADOWS), 1e-6f)
        assertEquals(0f, RetouchEngine.rangeWeight(0.45f, RetouchRange.SHADOWS), 1e-6f)
        assertEquals(0f, RetouchEngine.rangeWeight(0.8f, RetouchRange.SHADOWS), 1e-6f)
        // Smooth, strictly decreasing ramp through the lower midtones.
        val mid = RetouchEngine.rangeWeight(0.3f, RetouchRange.SHADOWS)
        assertTrue("expected partial weight at 0.3, got $mid", mid in 0.2f..0.8f)
        assertTrue(
            RetouchEngine.rangeWeight(0.2f, RetouchRange.SHADOWS) >
                RetouchEngine.rangeWeight(0.4f, RetouchRange.SHADOWS)
        )
    }

    @Test fun t03_midtones_bellCenteredOnMiddleGray() {
        assertEquals(0f, RetouchEngine.rangeWeight(0f, RetouchRange.MIDTONES), 1e-6f)
        assertEquals(0f, RetouchEngine.rangeWeight(1f, RetouchRange.MIDTONES), 1e-6f)
        assertEquals(1f, RetouchEngine.rangeWeight(0.5f, RetouchRange.MIDTONES), 1e-6f)
        val edge = RetouchEngine.rangeWeight(0.05f, RetouchRange.MIDTONES)
        assertTrue("expected near-zero at 0.05, got $edge", edge < 0.05f)
        val edge2 = RetouchEngine.rangeWeight(0.95f, RetouchRange.MIDTONES)
        assertTrue("expected near-zero at 0.95, got $edge2", edge2 < 0.05f)
    }

    @Test fun t04_highlights_zeroBelowKnee_fullAtTop() {
        assertEquals(0f, RetouchEngine.rangeWeight(0f, RetouchRange.HIGHLIGHTS), 1e-6f)
        assertEquals(0f, RetouchEngine.rangeWeight(0.55f, RetouchRange.HIGHLIGHTS), 1e-6f)
        assertEquals(1f, RetouchEngine.rangeWeight(0.85f, RetouchRange.HIGHLIGHTS), 1e-6f)
        assertEquals(1f, RetouchEngine.rangeWeight(1f, RetouchRange.HIGHLIGHTS), 1e-6f)
        val mid = RetouchEngine.rangeWeight(0.7f, RetouchRange.HIGHLIGHTS)
        assertEquals("smoothstep midpoint should be ~0.5", 0.5f, mid, 1e-5f)
    }

    // ------------------------------------------------------------------
    // compositeInto guards — never write garbage, never throw
    // ------------------------------------------------------------------

    @Test fun t05_emptySpots_fullyTransparentAndSourceUntouched() {
        val w = 16; val h = 16
        val src = flatGray(w, h, 128)
        val (overlay, compPx) = runSpotList(emptyList(), w, h, src)
        assertTrue(overlay.all { it == 0 })
        assertArrayEquals(src, compPx)
    }

    @Test fun t06_undersizedSource_fullyTransparent() {
        val w = 16; val h = 16
        val small = IntArray(w * h - 1) { gray(200) }
        val out = RetouchEngine.compositeInto(
            listOf(centerSpot(RetouchOp.DODGE, w, h, 0.5f)), w, h, small)
        assertTrue(out.all { it == 0 })
        // The undersized buffer must not have been written into.
        assertTrue(small.all { it == gray(200) })
    }

    @Test fun t07_zeroStrengthSpot_isNoOp() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val (overlay, compPx) = runSpot(
            centerSpot(RetouchOp.DODGE, w, h, 0.5f, strength = 0f), w, h, src)
        assertTrue(overlay.all { it == 0 })
        assertArrayEquals(src, compPx)
    }

    @Test fun t08_removeOp_isNoOp() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val (overlay, compPx) = runSpot(
            centerSpot(RetouchOp.REMOVE, w, h, 0.5f), w, h, src)
        assertTrue(overlay.all { it == 0 })
        assertArrayEquals(src, compPx)
    }

    @Test fun t09_offImageSpot_isNoOp() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val spot = RetouchSpot(
            op = RetouchOp.DODGE, x = 2f, y = 2f,
            srcX = 0f, srcY = 0f, size = 0.1f, strength = 1f)
        val (overlay, compPx) = runSpot(spot, w, h, src)
        assertTrue(overlay.all { it == 0 })
        assertArrayEquals(src, compPx)
    }

    // ------------------------------------------------------------------
    // dodge & burn — range gating + documented EV amounts
    // ------------------------------------------------------------------

    @Test fun t10_dodge_highlightsRangeOnDarkImage_isExactNoOp() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 30) // luma ~0.12: far below the 0.55 knee
        val (overlay, compPx) = runSpot(
            centerSpot(RetouchOp.DODGE, w, h, 0.5f, range = RetouchRange.HIGHLIGHTS),
            w, h, src)
        assertArrayEquals("dodge-highlights must not touch dark pixels", src, compPx)
        assertTrue(overlay.all { it == 0 })
    }

    @Test fun t11_burn_shadowsRangeOnBrightImage_isExactNoOp() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 240) // luma ~0.94: far above the 0.45 knee
        val (overlay, compPx) = runSpot(
            centerSpot(RetouchOp.BURN, w, h, 0.5f, range = RetouchRange.SHADOWS),
            w, h, src)
        assertArrayEquals("burn-shadows must not touch bright pixels", src, compPx)
        assertTrue(overlay.all { it == 0 })
    }

    @Test fun t12_dodge_allRange_brightensCenter_staysInGamut() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val (overlay, compPx) = runSpot(
            centerSpot(RetouchOp.DODGE, w, h, 0.5f), w, h, src)
        val c = compPx[16 * w + 16]
        val v = (c shr 16) and 0xFF
        // +0.9 EV on 128 -> ~170 (gamma 2.2 path); assert a sane band.
        assertTrue("expected dodge to brighten 128 -> ~170, got $v", v in 160..180)
        assertEquals(0xFF, (c ushr 24)) // composited pixels stay opaque
        // Outside the spot bbox the image is bit-exact.
        assertEquals(src[0], compPx[0])
        assertEquals(0, overlay[0])
    }

    @Test fun t13_burn_allRange_darkensCenter_staysInGamut() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val (_, compPx) = runSpot(
            centerSpot(RetouchOp.BURN, w, h, 0.5f), w, h, src)
        val c = compPx[16 * w + 16]
        val v = (c shr 16) and 0xFF
        // -0.9 EV on 128 -> ~96; assert a sane band, never below 0.
        assertTrue("expected burn to darken 128 -> ~96, got $v", v in 85..108)
        assertTrue("burn must not clip below 0", v >= 0)
    }

    @Test fun t14_rangeGating_crossCheck_oppositeRangesDoApply() {
        val w = 32; val h = 32
        // Dodge limited to SHADOWS on a dark image: must actually brighten.
        val (_, dark) = runSpot(
            centerSpot(RetouchOp.DODGE, w, h, 0.5f, range = RetouchRange.SHADOWS),
            w, h, flatGray(w, h, 30))
        val dv = (dark[16 * w + 16] shr 16) and 0xFF
        assertTrue("dodge-shadows should brighten dark pixels, got $dv", dv > 34)
        // Burn limited to HIGHLIGHTS on a bright image: must actually darken.
        val (_, bright) = runSpot(
            centerSpot(RetouchOp.BURN, w, h, 0.5f, range = RetouchRange.HIGHLIGHTS),
            w, h, flatGray(w, h, 240))
        val bv = (bright[16 * w + 16] shr 16) and 0xFF
        assertTrue("burn-highlights should darken bright pixels, got $bv", bv < 236)
    }

    // ------------------------------------------------------------------
    // skin smoothing — the keep-all invariant + the plastic-face net
    // ------------------------------------------------------------------

    @Test fun t15_smoothSkin_keepAll_collapsesToOriginal() {
        val w = 40; val h = 40
        // Gentle diagonal gradient, no noise: keep-all must be identity.
        val src = IntArray(w * h) { i -> gray(100 + ((i % w) + (i / w)) / 2) }
        val (_, compPx) = runSpot(
            centerSpot(RetouchOp.SMOOTH_SKIN, w, h, 0.5f, textureKeep = 1f),
            w, h, src)
        // Center 3x3 sits at falloff ~1; every channel must match within
        // float-pack rounding.
        for (dy in -1..1) for (dx in -1..1) {
            val i = (20 + dy) * w + (20 + dx)
            for (shift in listOf(16, 8, 0)) {
                val before = (src[i] shr shift) and 0xFF
                val after = (compPx[i] shr shift) and 0xFF
                assertTrue(
                    "keep-all changed channel by ${kotlin.math.abs(before - after)}",
                    kotlin.math.abs(before - after) <= 2)
            }
        }
    }

    @Test fun t16_smoothSkin_zeroKeep_killsMostNoiseVariance() {
        val w = 40; val h = 40
        val rnd = kotlin.random.Random(7)
        val src = IntArray(w * h) { gray(128 + rnd.nextInt(-21, 21)) }
        val (_, compPx) = runSpot(
            centerSpot(RetouchOp.SMOOTH_SKIN, w, h, 0.5f, textureKeep = 0f),
            w, h, src)
        fun variance(px: IntArray): Double {
            val vals = mutableListOf<Int>()
            for (y in 14..26) for (x in 14..26) vals += (px[y * w + x] shr 16) and 0xFF
            val mean = vals.average()
            return vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }
        val before = variance(src)
        val after = variance(compPx)
        assertTrue(
            "smoothing should kill most noise variance: $before -> $after",
            after < before * 0.6)
        // Tone is preserved: the mean must not drift far.
        val meanAfter = (14..26).flatMap { y -> (14..26).map { x -> (compPx[y * w + x] shr 16) and 0xFF } }.average()
        assertTrue("tone drifted too far: $meanAfter", kotlin.math.abs(meanAfter - 128) < 8)
    }

    // ------------------------------------------------------------------
    // color tools — red eye, teeth whiten, eye enhance, under-eye
    // ------------------------------------------------------------------

    @Test fun t17_redEye_leavesGreenAlone_desaturatesRedGlare() {
        val w = 32; val h = 32
        // Pure green: redness = 0 -> the tool must not touch it at all.
        val green = IntArray(w * h) { (0xFF shl 24) or (255 shl 8) }
        val (_, greenOut) = runSpot(
            centerSpot(RetouchOp.RED_EYE, w, h, 0.5f), w, h, green)
        assertArrayEquals(green, greenOut)
        // Pure red: the glare is tamed darker and desaturated.
        val red = IntArray(w * h) { (0xFF shl 24) or (255 shl 16) }
        val (_, redOut) = runSpot(
            centerSpot(RetouchOp.RED_EYE, w, h, 0.5f), w, h, red)
        val c = redOut[16 * w + 16]
        val r = (c shr 16) and 0xFF
        assertTrue("red glare should be tamed well below 255, got $r", r <= 60)
    }

    @Test fun t18_teethWhiten_liftsBlueOfYellow_staysInGamut() {
        val w = 32; val h = 32
        val yellow = IntArray(w * h) { (0xFF shl 24) or (255 shl 16) or (255 shl 8) }
        val (_, compPx) = runSpot(
            centerSpot(RetouchOp.TEETH_WHITEN, w, h, 0.5f), w, h, yellow)
        val c = compPx[16 * w + 16]
        val b = c and 0xFF
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        assertTrue("whitening should lift blue of yellow, got b=$b", b >= 40)
        assertTrue("whitening must reduce yellowness",
            ((r + g) / 2f - b) < 235f)
        for (shift in listOf(16, 8, 0)) {
            val v = (c shr shift) and 0xFF
            assertTrue("channel out of gamut: $v", v in 0..255)
        }
    }

    @Test fun t19_eyeEnhance_liftsUniformGray_byDocumentedSixPercent() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val (_, compPx) = runSpot(
            centerSpot(RetouchOp.EYE_ENHANCE, w, h, 0.5f), w, h, src)
        val v = (compPx[16 * w + 16] shr 16) and 0xFF
        // Documented: gentle lift x1.06 -> ~136 on uniform tone.
        assertTrue("expected ~136 on uniform 128, got $v", v in 132..140)
    }

    @Test fun t20_underEye_liftsDarkPixels_sparesWhite() {
        val w = 32; val h = 32
        val dark = flatGray(w, h, 60)
        val (_, darkOut) = runSpot(
            centerSpot(RetouchOp.UNDER_EYE, w, h, 0.5f), w, h, dark)
        val dv = (darkOut[16 * w + 16] shr 16) and 0xFF
        assertTrue("under-eye should lift dark pixels, got $dv", dv > 66)
        val white = flatGray(w, h, 255)
        val (_, whiteOut) = runSpot(
            centerSpot(RetouchOp.UNDER_EYE, w, h, 0.5f), w, h, white)
        assertArrayEquals("under-eye must spare pure white", white, whiteOut)
    }

    // ------------------------------------------------------------------
    // CLONE — source texture, destination tone (the 2026-10-18 fix)
    // ------------------------------------------------------------------

    @Test fun t21_clone_carriesSourceTextureAtDestinationTone() {
        val w = 64; val h = 64
        val src = flatGray(w, h, 120)
        // Checkerboard texture block (mean 120 = same tone as destination).
        for (y in 8..23) for (x in 8..23) {
            src[y * w + x] = gray(if ((x + y) % 2 == 0) 100 else 140)
        }
        val (overlay, compPx) = runSpot(
            centerSpot(RetouchOp.CLONE, w, h, 0.3f, srcX = 0.25f, srcY = 0.25f),
            w, h, src)
        // Center lands on a checkerboard pixel (16,16) -> 100; tone offset
        // ~0 because source and destination share the same mean tone.
        val c = compPx[32 * w + 32]
        val v = (c shr 16) and 0xFF
        assertTrue("clone center should carry the source texture (~100), got $v",
            v in 92..108)
        // The stamp has real texture now (was flat before the clone).
        fun centerVariance(px: IntArray): Double {
            val vals = mutableListOf<Int>()
            for (y in 28..36) for (x in 28..36) vals += (px[y * w + x] shr 16) and 0xFF
            val mean = vals.average()
            return vals.sumOf { (it - mean) * (it - mean) } / vals.size
        }
        assertTrue(centerVariance(compPx) > 200)
        // Far outside the spot bbox: bit-exact, overlay transparent.
        assertEquals(src[0], compPx[0])
        assertEquals(0, overlay[0])
    }

    // ------------------------------------------------------------------
    // HEAL — seamless blemish removal
    // ------------------------------------------------------------------

    @Test fun t22_heal_removesDarkBlemishOnFlatTone() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        // Dark blob at the target disc.
        for (y in 12..20) for (x in 12..20) {
            val d = kotlin.math.hypot((x - 16).toDouble(), (y - 16).toDouble())
            if (d <= 4.0) src[y * w + x] = gray(40)
        }
        val (_, compPx) = runSpot(
            centerSpot(RetouchOp.HEAL, w, h, 0.4f, srcX = 0.25f, srcY = 0.25f),
            w, h, src)
        val c = compPx[16 * w + 16]
        val v = (c shr 16) and 0xFF
        assertTrue("healed blemish should return to the surrounding tone, got $v",
            v >= 118)
        // Untouched corner stays bit-exact.
        assertEquals(gray(128), compPx[0])
    }

    // ------------------------------------------------------------------
    // findHealSourcePx — the automatic heal-source picker
    // ------------------------------------------------------------------

    @Test fun t23_findHealSourcePx_nullOnDegenerateInput() {
        val tiny = IntArray(4 * 4) { gray(128) }
        assertNull(RetouchEngine.findHealSourcePx(tiny, 4, 4, 0.5f, 0.5f, 0.2f))
        val wrongSize = IntArray(32 * 32 - 8) { gray(128) }
        assertNull(RetouchEngine.findHealSourcePx(wrongSize, 32, 32, 0.5f, 0.5f, 0.2f))
    }

    @Test fun t24_findHealSourcePx_uniformImage_boundedAndDeterministic() {
        val w = 32; val h = 32
        val src = flatGray(w, h, 128)
        val first = RetouchEngine.findHealSourcePx(src, w, h, 0.5f, 0.5f, 0.2f)
        val second = RetouchEngine.findHealSourcePx(src, w, h, 0.5f, 0.5f, 0.2f)
        assertNotNull(first)
        assertEquals("source picking must be deterministic", first, second)
        val (u, v) = first!!
        assertTrue("u out of bounds: $u", u in 0f..1f)
        assertTrue("v out of bounds: $v", v in 0f..1f)
    }

    // ------------------------------------------------------------------
    // private plumbing
    // ------------------------------------------------------------------

    private fun runSpotList(
        spots: List<RetouchSpot>, w: Int, h: Int, src: IntArray,
    ): Pair<IntArray, IntArray> {
        val compPx = src.copyOf()
        val overlay = RetouchEngine.compositeInto(spots, w, h, compPx)
        return overlay to compPx
    }
}
