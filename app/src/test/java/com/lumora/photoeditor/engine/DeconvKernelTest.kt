package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of [DeconvKernel] — the Wiener-inverse
 * 5x5 deconvolution kernel behind the Detail panel's deblur (pro-spec
 * Phase 3). The math (DFT of a gaussian, Wiener inverse, fftshift ordering,
 * outer product, identity blend, renormalization) is intricate enough that a
 * subtle slip — e.g. the documented peak-at-n/2 shift pitfall — would smear
 * the image by a pixel instead of deblurring it. These tests pin the
 * contract so a refactor cannot silently drift it.
 *
 * Runs under Gradle AND under the standalone kotlinc 1.9.23 harness
 * (hidden_files/deconvkernel-harness/run.sh).
 */
class DeconvKernelTest {

    private val dcGain = 1.0 / (1.0 + DeconvKernel.NSR) // W[0] for normalized H

    // ---------- wiener1d ----------

    @Test
    fun wiener1d_hasFiveFiniteTaps() {
        val w = DeconvKernel.wiener1d(1f)
        assertEquals(DeconvKernel.SIZE, w.size)
        assertTrue("all taps finite", w.all { it.isFinite() })
    }

    @Test
    fun wiener1d_isEvenlySymmetric() {
        // The inverse of a symmetric blur is even; this also pins the
        // fftshift-ordering contract — a peak at n/2 (the documented pitfall)
        // would come back shifted and break this symmetry.
        val w = DeconvKernel.wiener1d(1f)
        for (i in 1 until DeconvKernel.SIZE) {
            assertEquals("w[$i] == w[${DeconvKernel.SIZE - i}]", w[DeconvKernel.SIZE - i], w[i], 1e-6f)
        }
    }

    @Test
    fun wiener1d_dcGainIsOneOverOnePlusNsr() {
        // sum_m w[m] = W[0] = H[0] / (|H[0]|^2 + NSR) = 1 / (1 + NSR)
        // because the blur kernel is normalized (H[0] = 1).
        val w = DeconvKernel.wiener1d(1f)
        assertEquals(dcGain, w.sum().toDouble(), 1e-4)
    }

    @Test
    fun wiener1d_centerTapDominatesForUnitSigma() {
        // A deblur kernel must amplify the center tap above the DC-preserving
        // 1.0 (the blur it inverts attenuated contrast); the surround then
        // subtracts. Center must be the strict maximum.
        val w = DeconvKernel.wiener1d(1f)
        assertTrue("center > 1 for sigma=1, was ${w[0]}", w[0] > 1f)
        for (i in 1 until DeconvKernel.SIZE) {
            assertTrue("center is max: w[0]=${w[0]} vs w[$i]=${w[i]}", w[0] > w[i])
        }
    }

    @Test
    fun wiener1d_mildSigmaIsNearIdentity() {
        // sigma -> 0.3: the blur is so mild its Wiener inverse is ~delta.
        val w = DeconvKernel.wiener1d(0.3f)
        assertEquals(1f, w[0], 0.02f)
        for (i in 1 until DeconvKernel.SIZE) {
            assertEquals("off-center tap $i ~ 0", 0f, w[i], 0.02f)
        }
    }

    @Test
    fun wiener1d_sigmaIsClamped() {
        val lo = DeconvKernel.wiener1d(0.1f)
        val loEdge = DeconvKernel.wiener1d(0.3f)
        val hi = DeconvKernel.wiener1d(5f)
        val hiEdge = DeconvKernel.wiener1d(2f)
        assertTrue(lo.contentEquals(loEdge))
        assertTrue(hi.contentEquals(hiEdge))
    }

    // ---------- kernel25 ----------

    @Test
    fun kernel25_has25Taps() {
        assertEquals(DeconvKernel.TAPS, DeconvKernel.kernel25(1f, 1f).size)
    }

    @Test
    fun kernel25_sumsToExactlyOne() {
        // The documented no-brightness-shift guarantee: renormalization
        // forces an exact unit DC gain for every (amount, sigma).
        for (amount in floatArrayOf(0f, 0.25f, 0.5f, 1f)) {
            for (sigma in floatArrayOf(0.3f, 0.7f, 1f, 1.5f, 2f)) {
                val k = DeconvKernel.kernel25(amount, sigma)
                assertEquals("sum==1 for amount=$amount sigma=$sigma", 1f, k.sum(), 1e-5f)
            }
        }
    }

    @Test
    fun kernel25_amountZeroIsIdentity() {
        // amount=0 blends fully to the delta kernel: bit-exact identity.
        val k = DeconvKernel.kernel25(0f, 1f)
        for (y in 0 until DeconvKernel.SIZE) {
            for (x in 0 until DeconvKernel.SIZE) {
                val expected = if (y == 2 && x == 2) 1f else 0f
                assertEquals("tap ($x,$y)", expected, k[y * 5 + x], 0f)
            }
        }
    }

    @Test
    fun kernel25_amountIsClamped() {
        val zero = DeconvKernel.kernel25(0f, 1f)
        val neg = DeconvKernel.kernel25(-1f, 1f)
        val one = DeconvKernel.kernel25(1f, 1f)
        val over = DeconvKernel.kernel25(2f, 1f)
        assertTrue(neg.contentEquals(zero))
        assertTrue(over.contentEquals(one))
    }

    @Test
    fun kernel25_sigmaIsClamped() {
        assertTrue(DeconvKernel.kernel25(1f, 0.1f).contentEquals(DeconvKernel.kernel25(1f, 0.3f)))
        assertTrue(DeconvKernel.kernel25(1f, 5f).contentEquals(DeconvKernel.kernel25(1f, 2f)))
    }

    @Test
    fun kernel25_isRotationallySymmetric() {
        // Outer product of an even 1D kernel is 180-degree symmetric; the
        // identity blend and renormalization preserve it. A shifted (aliased)
        // inverse would break this.
        val k = DeconvKernel.kernel25(1f, 1f)
        for (y in 0 until DeconvKernel.SIZE) {
            for (x in 0 until DeconvKernel.SIZE) {
                assertEquals(
                    "k[$x,$y] == k[${4 - x},${4 - y}]",
                    k[(4 - y) * 5 + (4 - x)], k[y * 5 + x], 1e-6f,
                )
            }
        }
    }

    @Test
    fun kernel25_hasSharpeningCharacter() {
        // Full-strength deblur of sigma=1 blur: center boosted above the
        // identity's 1.0 and a subtracting surround (negative taps) — the
        // signature of an inverse filter, not a blur.
        val k = DeconvKernel.kernel25(1f, 1f)
        val center = k[2 * 5 + 2]
        assertTrue("center > 1, was $center", center > 1f)
        assertTrue("surround subtracts", k.min() < 0f)
    }

    @Test
    fun kernel25_halfAmountBlendsMonotonically() {
        // The identity blend is linear in `amount`: the half-strength center
        // tap sits strictly between identity (1.0) and full strength.
        val c0 = DeconvKernel.kernel25(0f, 1f)[2 * 5 + 2]
        val cHalf = DeconvKernel.kernel25(0.5f, 1f)[2 * 5 + 2]
        val c1 = DeconvKernel.kernel25(1f, 1f)[2 * 5 + 2]
        assertEquals(1f, c0, 0f)
        assertTrue("1 < half($cHalf) < full($c1)", cHalf > 1f && cHalf < c1)
    }

    @Test
    fun kernel25_flatFieldIsUnchangedEndToEnd() {
        // Unit DC gain means a flat field convolves to itself: the
        // end-to-end "never a global brightness shift" contract.
        val k = DeconvKernel.kernel25(1f, 1f)
        val w = 16
        val h = 16
        val field = FloatArray(w * h) { 100f }
        val out = FloatArray(w * h)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                var v = 0.0
                for (ky in 0 until 5) {
                    for (kx in 0 until 5) {
                        v += field[(y + ky - 2) * w + (x + kx - 2)] * k[ky * 5 + kx]
                    }
                }
                out[y * w + x] = v.toFloat()
            }
        }
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                assertEquals("flat field preserved at ($x,$y)", 100f, out[y * w + x], 0.01f)
            }
        }
    }

    @Test
    fun kernel25_isDeterministic() {
        val a = DeconvKernel.kernel25(0.7f, 1.2f)
        val b = DeconvKernel.kernel25(0.7f, 1.2f)
        assertTrue(a.contentEquals(b))
    }
}
