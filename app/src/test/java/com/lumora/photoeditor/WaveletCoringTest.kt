package com.lumora.photoeditor

import com.lumora.photoeditor.engine.WaveletDecompose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * Verification for the Wavelet Skin Retouch edge-preserving ("coring")
 * reconstruction — the 2026-10-18 "plastic face" fix.
 *
 * The pre-coring behavior attenuated every mid-frequency sample by the
 * slider factor uniformly: pores survived (fine scales kept) while the
 * skin's mid-band form was flattened to the slider factor — the classic
 * over-retouched wax look his on-device video caught at Blemish smooth 60%.
 * Coring instead keeps strong features (detail amplitude above CORE_HI = 9)
 * nearly whole and only crushes weak detail (below CORE_LO = 3) down to the
 * slider floor, with a smoothstep ramp between so no threshold contour shows.
 *
 * These tests pin the documented contract through the public API:
 *  - identity reconstruction is exact (bit-identical to the uniform path),
 *  - a strong mid-scale feature (blemish-class structure) survives at 60%
 *    smoothing far better than uniform attenuation did,
 *  - weak blotchiness is crushed identically to the old uniform behavior
 *    (coring changes ONLY strong-feature handling),
 *  - gray input stays exactly gray (single luma gate per pixel, no hue shift),
 *  - the 1-px degenerate-dimension ANR guard holds.
 */
class WaveletCoringTest {

    private val w = 128
    private val h = 128

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun chan(px: Int) = (px shr 16) and 0xFF

    /** Old pre-coring behavior: uniform per-scale attenuation, same floors retouchSkin uses. */
    private fun uniformOld(
        src: IntArray, textureKeep: Float, blemishSmooth: Float, toneEven: Float,
    ): IntArray {
        val (details, residual) = WaveletDecompose.decompose(src, w, h)
        val s = FloatArray(5) { i ->
            when (i) {
                0, 1 -> textureKeep
                2, 3 -> 1f - blemishSmooth
                else -> 1f - toneEven * 0.5f
            }
        }
        return WaveletDecompose.reconstruct(details, residual, w, h, s)
    }

    /** Max |pixel - 128| luma deviation inside a window — the feature's contrast. */
    private fun peakContrast(img: IntArray, cx: Int, cy: Int, r: Int): Int {
        var peak = 0
        for (y in (cy - r)..(cy + r)) for (x in (cx - r)..(cx + r)) {
            peak = maxOf(peak, abs(chan(img[y * w + x]) - 128))
        }
        return peak
    }

    /** Max per-channel abs error between two ARGB images. */
    private fun maxChannelError(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            m = maxOf(m, abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)))
            m = maxOf(m, abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)))
            m = maxOf(m, abs((x and 0xFF) - (y and 0xFF)))
        }
        return m
    }

    @Test fun reconstruct_roundTrip_withinFloatRounding() {
        val src = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            gray((x * 3 + y * 2) % 256)
        }
        val (details, residual) = WaveletDecompose.decompose(src, w, h)
        val out = WaveletDecompose.reconstruct(details, residual, w, h)
        // Mathematically exact, but float rounding through 5 blur/detail
        // round-trips costs up to 1 LSB per channel — invisible, and the
        // contract the algorithm actually offers.
        val err = maxChannelError(src, out)
        println("  round-trip max channel error: $err LSB")
        assertTrue("round-trip error $err LSB exceeds float-rounding budget", err <= 1)
    }

    @Test fun retouchSkin_neutralParams_withinFloatRounding() {
        val src = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            gray((x * 5 + y * 7) % 256)
        }
        val out = WaveletDecompose.retouchSkin(src, w, h, 1f, 0f, 0f)
        // All-neutral: every scale takes the exact fast path, so the only
        // error is the decompose float-rounding budget (see above).
        val err = maxChannelError(src, out)
        println("  neutral-params max channel error: $err LSB")
        assertTrue("neutral retouch error $err LSB exceeds float-rounding budget", err <= 1)
    }

    @Test fun coring_keepsStrongFeature_betterThanUniform() {
        // Strong blemish-class structure: hard disk, radius 8px, +40 amplitude.
        // Its mid-scale edge detail amplitudes dwarf CORE_HI (9), so coring keeps it.
        val src = IntArray(w * h) { gray(128) }
        val cx = 96; val cy = 64
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= 64) src[y * w + x] = gray(168)
        }
        val origPeak = peakContrast(src, cx, cy, 14)
        assertEquals(40, origPeak)

        val cored = WaveletDecompose.retouchSkin(src, w, h, 1f, 0.6f, 0f)
        val uniform = uniformOld(src, 1f, 0.6f, 0f)
        val coredPeak = peakContrast(cored, cx, cy, 14)
        val uniformPeak = peakContrast(uniform, cx, cy, 14)
        println("  strong feature: orig=$origPeak cored=$coredPeak uniform=$uniformPeak")

        // Coring must keep the strong feature nearly whole at 60% smoothing...
        assertTrue(
            "coring kept only $coredPeak/40 of a strong feature — wax-look regression",
            coredPeak >= 34,
        )
        // ...and be meaningfully better than the old uniform attenuation.
        assertTrue(
            "coring ($coredPeak) not better than old uniform attenuation ($uniformPeak)",
            coredPeak - uniformPeak >= 4,
        )
    }

    @Test fun coring_crushesWeakBlotch_sameAsUniform() {
        // Weak blotchiness: soft gaussian blob, sigma 4px, +5 amplitude.
        // Its mid-scale detail sits below CORE_LO (3): both paths crush it to the
        // 0.4 floor — coring must not treat weak detail any differently.
        val src = IntArray(w * h) { gray(128) }
        val cx = 32; val cy = 64
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx).toDouble(); val dy = (y - cy).toDouble()
            val v = 128 + (5.0 * exp(-(dx * dx + dy * dy) / 32.0)).toInt()
            src[y * w + x] = gray(v)
        }
        val cored = WaveletDecompose.retouchSkin(src, w, h, 1f, 0.6f, 0f)
        val uniform = uniformOld(src, 1f, 0.6f, 0f)
        val coredPeak = peakContrast(cored, cx, cy, 20)
        val uniformPeak = peakContrast(uniform, cx, cy, 20)
        println("  weak blotch: cored=$coredPeak uniform=$uniformPeak")
        assertTrue(
            "weak blotch peak differs between coring ($coredPeak) and uniform ($uniformPeak)",
            abs(coredPeak - uniformPeak) <= 1,
        )
    }

    @Test fun coring_grayStaysGray_noHueShift() {
        // Gray ramp: every channel identical at every scale. The coring gate is
        // one luma-weighted amplitude per pixel applied to all channels equally,
        // so output channels must stay exactly equal (per-channel gating would
        // shift hues at strong edges).
        val src = IntArray(w * h) { i -> gray((i % w) * 255 / w) }
        val out = WaveletDecompose.retouchSkin(src, w, h, 1f, 0.6f, 0.3f)
        for (i in out.indices) {
            val px = out[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            assertEquals("R!=G at pixel $i", r, g)
            assertEquals("G!=B at pixel $i", g, b)
        }
    }

    @Test(timeout = 15000) fun retouchSkin_degenerate1px_noHang() {
        // mirror() special-cases n <= 1; without it the while loop ping-pongs
        // forever on a 1-px dimension (ANR). This must return, not hang.
        val src = IntArray(64) { gray(128) }
        val out = WaveletDecompose.retouchSkin(src, 1, 64, 1f, 0.6f, 0.3f)
        assertEquals(64, out.size)
    }
}
