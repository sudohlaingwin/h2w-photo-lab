package com.lumora.photoeditor.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * First-ever executed verification of [WaveletDecompose] — the à trous
 * ("with holes") B3-spline wavelet decomposition behind the retouch
 * module's Wavelet Skin Retouch (frequency separation with 5 scales
 * instead of 2: pores → blemishes → tone).
 *
 * Pins the documented contract:
 * - decompose: guard rails (bad dims / short src throw), numScales coerced
 *   to [1,7], layer shapes (numScales RGB-triplet detail layers + one
 *   residual), details centered on 0 (constant image -> exact-zero details),
 *   the exact B3-spline kernel [1,4,6,4,1]/16 verified by hand-computed
 *   single-pixel pins (center 219.140625, distance-2 neighbor -5.9765625 —
 *   all dyadic rationals, exactly representable in float);
 * - reconstruct: exact identity (telescoping sum, round-to-nearest packing —
 *   bit-exact, no systematic darkening), smoothing coercion to [0,1],
 *   short smoothing arrays default missing scales to 1, the 0.001 skip
 *   boundary (<=0.001 skips == 0, just above applies), output always opaque
 *   with channels in [0,255], the >=0.999 fast path bit-identical to null
 *   smoothing;
 * - retouchSkin: all-keep is the bit-exact identity, slider clamping,
 *   determinism, and the 2026-10-18 "plastic face" coring contract —
 *   amplitude-adaptive keeps: strong features (step edge, detail amp >> 9)
 *   survive a 0.5 crush far better than uniform attenuation (edge error 17
 *   vs 69), while weak detail (amp < 3, +/-2 noise) is crushed to the floor
 *   exactly like the uniform path (max diff 0);
 * - the 1-px degenerate dimension: mirror() never hangs (the 2*n-2 == 0
 *   ping-pong ANR guard) and reconstruction stays bit-exact.
 */
class WaveletDecomposeTest {

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun r(px: Int): Int = (px shr 16) and 0xFF

    private fun patternImage(w: Int, h: Int): IntArray =
        IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            gray((x * 37 + y * 91 + x * y) % 256)
        }

    private fun maxAbsDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) {
            val dr = abs(r(a[i]) - r(b[i]))
            val dg = abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF))
            val db = abs((a[i] and 0xFF) - (b[i] and 0xFF))
            m = maxOf(m, dr, dg, db)
        }
        return m
    }

    // ---------------- decompose guards & shape ----------------

    @Test fun decompose_rejectsBadDims() {
        assertThrows(IllegalArgumentException::class.java) {
            WaveletDecompose.decompose(IntArray(4), 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WaveletDecompose.decompose(IntArray(4), 2, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WaveletDecompose.decompose(IntArray(3), 2, 2) // short src
        }
    }

    @Test fun decompose_coercesNumScales() {
        val src = patternImage(8, 8)
        val (d0, _) = WaveletDecompose.decompose(src, 8, 8, 0)
        assertEquals(1, d0.size)
        val (d99, _) = WaveletDecompose.decompose(src, 8, 8, 99)
        assertEquals(7, d99.size)
        val (d5, _) = WaveletDecompose.decompose(src, 8, 8)
        assertEquals(5, d5.size)
    }

    @Test fun decompose_layerShapes() {
        val w = 6; val h = 5
        val (details, residual) = WaveletDecompose.decompose(patternImage(w, h), w, h, 4)
        assertEquals(4, details.size)
        for (d in details) assertEquals(w * h * 3, d.size)
        assertEquals(w * h * 3, residual.size)
    }

    @Test fun decompose_constantImage_hasZeroDetails() {
        val w = 8; val h = 8
        val (details, residual) = WaveletDecompose.decompose(IntArray(w * h) { gray(128) }, w, h, 5)
        for (d in details) for (v in d) assertEquals(0f, v, 0f)
        for (v in residual) assertEquals(128f, v, 1e-4f)
    }

    @Test fun decompose_b3splineKernel_handComputedPins() {
        // Single white pixel on black, 9x9: scale-1 step = 1, no edge mirroring
        // within 2 taps of center (4,4). Blur is separable [1,4,6,4,1]/16 twice:
        // center blurred = 255*36/256 = 35.859375 -> detail 219.140625 (exact float).
        val w = 9; val h = 9
        val src = IntArray(w * h) { gray(0) }
        src[4 * w + 4] = gray(255)
        val (details, _) = WaveletDecompose.decompose(src, w, h, 1)
        val d = details[0]
        assertEquals(219.140625f, d[(4 * w + 4) * 3], 1e-3f)
        // Distance-2 neighbor (6,4): horizontal tap k=-2 only: 255*1/16 = 15.9375,
        // vertical k=0: 15.9375*6/16 = 5.9765625 -> detail -5.9765625.
        assertEquals(-5.9765625f, d[(4 * w + 6) * 3], 1e-3f)
        // Vertical neighbor (4,6) symmetric.
        assertEquals(-5.9765625f, d[(6 * w + 4) * 3], 1e-3f)
        // All three channels identical for a gray pixel.
        assertEquals(d[(4 * w + 4) * 3], d[(4 * w + 4) * 3 + 1], 0f)
        assertEquals(d[(4 * w + 4) * 3], d[(4 * w + 4) * 3 + 2], 0f)
    }

    @Test fun decompose_detailSumsToZero() {
        // Kernel sums to 1 -> blur preserves total -> detail sums to ~0.
        val w = 9; val h = 9
        val src = IntArray(w * h) { gray(0) }
        src[4 * w + 4] = gray(255)
        val (details, _) = WaveletDecompose.decompose(src, w, h, 1)
        var sum = 0.0
        for (v in details[0]) sum += v
        assertEquals(0.0, sum, 1e-2)
    }

    // ---------------- reconstruct ----------------

    @Test fun reconstruct_identityIsBitExact() {
        val w = 16; val h = 16
        val src = patternImage(w, h)
        val (details, residual) = WaveletDecompose.decompose(src, w, h, 5)
        val out = WaveletDecompose.reconstruct(details, residual, w, h)
        assertEquals(0, maxAbsDiff(src, out))
    }

    @Test fun reconstruct_allOneSmoothing_matchesNullSmoothing() {
        val w = 12; val h = 10
        val src = patternImage(w, h)
        val (details, residual) = WaveletDecompose.decompose(src, w, h, 4)
        val a = WaveletDecompose.reconstruct(details, residual, w, h, null)
        val b = WaveletDecompose.reconstruct(details, residual, w, h, FloatArray(4) { 1f })
        assertArrayEquals(a, b) // >=0.999 fast path is bit-identical
    }

    @Test fun reconstruct_smoothingCoercedToUnitRange() {
        val w = 12; val h = 10
        val src = patternImage(w, h)
        val (details, residual) = WaveletDecompose.decompose(src, w, h, 3)
        val neg = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(-0.5f, -1f, -9f))
        val zero = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0f, 0f, 0f))
        assertArrayEquals(zero, neg)
        val over = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(1.5f, 9f, 100f))
        val one = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(1f, 1f, 1f))
        assertArrayEquals(one, over)
    }

    @Test fun reconstruct_shortSmoothingDefaultsMissingToOne() {
        val w = 12; val h = 10
        val src = patternImage(w, h)
        val (details, residual) = WaveletDecompose.decompose(src, w, h, 3)
        val short = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0f))
        val full = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0f, 1f, 1f))
        assertArrayEquals(full, short)
    }

    @Test fun reconstruct_skipBoundary() {
        // keep <= 0.001 skips the scale entirely (== 0); just above applies.
        val w = 12; val h = 10
        val src = patternImage(w, h)
        val (details, residual) = WaveletDecompose.decompose(src, w, h, 1)
        val atBoundary = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0.001f))
        val zero = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0f))
        assertArrayEquals(zero, atBoundary)
        val above = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0.0011f))
        assertTrue(maxAbsDiff(zero, above) > 0)
    }

    @Test fun reconstruct_outputAlwaysOpaqueAndBounded() {
        val w = 8; val h = 8
        val src = IntArray(w * h) { i -> if (i % 2 == 0) gray(0) else gray(255) }
        val (details, residual) = WaveletDecompose.decompose(src, w, h, 5)
        val out = WaveletDecompose.reconstruct(details, residual, w, h, floatArrayOf(0f, 0f, 0f, 0f, 0f))
        for (px in out) {
            assertEquals(0xFF, (px ushr 24) and 0xFF)
            assertTrue(r(px) in 0..255)
        }
    }

    // ---------------- retouchSkin ----------------

    @Test fun retouchSkin_allKeep_isBitExactIdentity() {
        val w = 16; val h = 12
        val src = patternImage(w, h)
        val out = WaveletDecompose.retouchSkin(src, w, h,
            textureKeep = 1f, blemishSmooth = 0f, toneEven = 0f)
        assertEquals(0, maxAbsDiff(src, out))
    }

    @Test fun retouchSkin_slidersClamped() {
        val w = 12; val h = 10
        val src = patternImage(w, h)
        val a = WaveletDecompose.retouchSkin(src, w, h, 1f, 5f, 0f)
        val b = WaveletDecompose.retouchSkin(src, w, h, 1f, 1f, 0f)
        assertArrayEquals(b, a) // blemishSmooth 5 coerces to 1
        val c = WaveletDecompose.retouchSkin(src, w, h, -2f, 0.5f, 0f)
        val d = WaveletDecompose.retouchSkin(src, w, h, 0f, 0.5f, 0f)
        assertArrayEquals(d, c) // textureKeep -2 coerces to 0
    }

    @Test fun retouchSkin_deterministic() {
        val w = 14; val h = 11
        val src = patternImage(w, h)
        val a = WaveletDecompose.retouchSkin(src, w, h)
        val b = WaveletDecompose.retouchSkin(src, w, h)
        assertArrayEquals(a, b)
    }

    @Test fun retouchSkin_coringKeepsStrongFeatures_crushesWeakDetail() {
        // THE money test for the 2026-10-18 "plastic face" fix: with the fine
        // scales crushed to a 0.5 floor, a sharp step edge (detail amp >> 9)
        // must survive far better through the cored path than through plain
        // uniform attenuation — while weak +/-2 noise is crushed to the floor
        // exactly like the uniform path (no hue/edge artifacts from gating).
        val w = 32; val h = 8
        val step = IntArray(w * h) { i -> gray(if (i % w < w / 2) 0 else 255) }
        val cored = WaveletDecompose.retouchSkin(step, w, h,
            textureKeep = 0.5f, blemishSmooth = 1f, toneEven = 0f)
        val (details, residual) = WaveletDecompose.decompose(step, w, h, 5)
        val uniform = WaveletDecompose.reconstruct(details, residual, w, h,
            floatArrayOf(0.5f, 0.5f, 0f, 0f, 1f))
        // Edge columns x=15 (black side) and x=16 (white side), row 4.
        val edgeIdx = listOf(4 * w + 15, 4 * w + 16)
        for (idx in edgeIdx) {
            val coredErr = abs(r(cored[idx]) - r(step[idx]))
            val uniformErr = abs(r(uniform[idx]) - r(step[idx]))
            assertTrue("cored edge error $coredErr should beat uniform $uniformErr",
                coredErr < uniformErr)
        }
        assertTrue(abs(r(cored[4 * w + 15]) - 0) <= 20)   // measured 17
        assertTrue(abs(r(uniform[4 * w + 15]) - 0) >= 60) // measured 69
        // Weak-detail control: +/-2 checker, same crush — cored == uniform.
        val w2 = 16; val h2 = 16
        val noise = IntArray(w2 * h2) { i -> gray(128 + if (i % 2 == 0) 2 else -2) }
        val coredN = WaveletDecompose.retouchSkin(noise, w2, h2,
            textureKeep = 0.5f, blemishSmooth = 1f, toneEven = 0f)
        val (d2, r2) = WaveletDecompose.decompose(noise, w2, h2, 5)
        val uniformN = WaveletDecompose.reconstruct(d2, r2, w2, h2,
            floatArrayOf(0.5f, 0.5f, 0f, 0f, 1f))
        assertEquals(0, maxAbsDiff(coredN, uniformN))
    }

    @Test fun retouchSkin_defaultParams_changeNoisyImageWithinBounds() {
        val w = 24; val h = 18
        val src = patternImage(w, h)
        val out = WaveletDecompose.retouchSkin(src, w, h)
        assertTrue(maxAbsDiff(src, out) > 0) // default smoothing does something
        for (px in out) {
            assertEquals(0xFF, (px ushr 24) and 0xFF)
            assertTrue(r(px) in 0..255)
        }
    }

    // ---------------- degenerate dimensions ----------------

    @Test fun decompose_1pxDimensions_neverHangAndStayExact() {
        // mirror() guard: n <= 1 returns 0 (the 2*n-2 == 0 ping-pong ANR fix).
        for ((w, h) in listOf(1 to 1, 1 to 7, 7 to 1, 2 to 2)) {
            val src = patternImage(w, h)
            val (details, residual) = WaveletDecompose.decompose(src, w, h, 7)
            assertEquals(7, details.size)
            val out = WaveletDecompose.reconstruct(details, residual, w, h)
            assertEquals("identity at ${w}x$h", 0, maxAbsDiff(src, out))
            // All-keep retouchSkin is the identity; the defaults call only
            // has to terminate (default blemishSmooth/toneEven legitimately
            // alter pixels — smoothing is the feature, not a bug).
            val skinKeep = WaveletDecompose.retouchSkin(src, w, h, 1f, 0f, 0f)
            assertEquals("retouchSkin all-keep at ${w}x$h", 0, maxAbsDiff(src, skinKeep))
            WaveletDecompose.retouchSkin(src, w, h) // must not hang
        }
    }

    @Test fun retouchSkin_1px_neverHang() {
        val src = intArrayOf(gray(200))
        val out = WaveletDecompose.retouchSkin(src, 1, 1, 0.2f, 0.9f, 0.8f)
        assertEquals(1, out.size)
        assertEquals(0xFF, (out[0] ushr 24) and 0xFF)
    }
}
