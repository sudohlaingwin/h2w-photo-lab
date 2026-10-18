package com.lumora.photoeditor.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Deconvolution sharpening kernel (pro-spec Phase 3, Detail panel).
 *
 * Inverts mild capture blur (slightly soft lens, light defocus) with a
 * Wiener inverse filter: for a gaussian blur of stddev [sigma] the kernel
 * is IDFT( conj(H) / (|H|^2 + NSR) ), where H is the DFT of the blur and
 * NSR is the noise-to-signal regularizer that caps the gain so noise and
 * ringing stay bounded. Because a gaussian is separable, the 1D inverse is
 * outer-producted into a 5x5 kernel applied in one GPU pass.
 *
 * Honest limits, stated in the UI too:
 * - This is NOT magic deblur: it only recovers mild softness (sigma up to
 *   ~1.5px). Heavy blur or motion blur cannot be inverted by a 5x5 kernel.
 * - It is NOT AI and NOT iterative Richardson-Lucy; it is a single
 *   closed-form Wiener inverse, so it is fast enough to run live.
 * - Ringing is bounded by the fixed NSR (0.01) and by blending the kernel
 *   with the identity by [amount]; the kernel is renormalized to sum to 1
 *   so there is never a global brightness shift.
 *
 * Pure Kotlin with zero Android imports so it can be unit-tested with the
 * standalone kotlinc harness (see AGENTS.md).
 */
object DeconvKernel {

    const val SIZE = 5
    const val TAPS = SIZE * SIZE

    /** Fixed Wiener noise-to-signal ratio: caps inverse gain, bounds ringing. */
    const val NSR = 0.01

    /**
     * 5x5 deconvolution kernel, row-major, blended with the identity kernel
     * by [amount] 0..1 and renormalized to sum to exactly 1.
     * [sigma] is the assumed capture-blur stddev in pixels (0.5..1.5).
     */
    fun kernel25(amount: Float, sigma: Float): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        val w1 = wiener1d(sigma.coerceIn(0.3f, 2f))
        val k = FloatArray(TAPS)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                // w1 is fftshift-ordered (index 0 = center); map tap offset
                // dy = y-2 onto it.
                val wy = w1[(y - SIZE / 2 + SIZE) % SIZE]
                val wx = w1[(x - SIZE / 2 + SIZE) % SIZE]
                val wiener = wy * wx
                val delta = if (y == SIZE / 2 && x == SIZE / 2) 1f else 0f
                k[y * SIZE + x] = delta + (wiener - delta) * a
            }
        }
        // Renormalize: the Wiener inverse is near-DC-preserving but not
        // exact (W[0] = 1/(1+NSR)); exact sum 1 means zero brightness shift.
        var sum = 0.0
        for (v in k) sum += v
        if (sum != 0.0) {
            val inv = (1.0 / sum).toFloat()
            for (i in k.indices) k[i] *= inv
        }
        return k
    }

    /**
     * 1D Wiener inverse of a gaussian blur, [SIZE] taps, fftshift-ordered
     * (index 0 is the center tap). Public for tests.
     */
    fun wiener1d(sigma: Float): FloatArray {
        val n = SIZE
        val s = sigma.coerceIn(0.3f, 2f).toDouble()
        // Gaussian blur kernel in fftshift order: the DFT treats index 0 as
        // zero lag, so the peak must sit at index 0 —
        // h = [g(0), g(1), g(2), g(-2), g(-1)]. A peak at index n/2 would
        // read as a 2-sample delay and the inverse would come back shifted,
        // smearing the image by a pixel instead of deblurring it.
        val h = DoubleArray(n) { i ->
            val x = if (i <= n / 2) i.toDouble() else (i - n).toDouble()
            exp(-x * x / (2.0 * s * s))
        }
        val hSum = h.sum()
        for (i in h.indices) h[i] /= hSum
        // DFT of h.
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (kIdx in 0 until n) {
            var r = 0.0; var ii = 0.0
            for (m in 0 until n) {
                val ang = -2.0 * PI * kIdx * m / n
                r += h[m] * cos(ang)
                ii += h[m] * sin(ang)
            }
            re[kIdx] = r; im[kIdx] = ii
        }
        // Wiener filter W = conj(H) / (|H|^2 + NSR).
        val wRe = DoubleArray(n)
        val wIm = DoubleArray(n)
        for (kIdx in 0 until n) {
            val denom = re[kIdx] * re[kIdx] + im[kIdx] * im[kIdx] + NSR
            wRe[kIdx] = re[kIdx] / denom
            wIm[kIdx] = -im[kIdx] / denom
        }
        // Inverse DFT -> real spatial taps, still fftshift-ordered
        // (index 0 = center tap; the sequence is even because W is real).
        return FloatArray(n) { m ->
            var v = 0.0
            for (kIdx in 0 until n) {
                val ang = 2.0 * PI * kIdx * m / n
                v += wRe[kIdx] * cos(ang) - wIm[kIdx] * sin(ang)
            }
            (v / n).toFloat()
        }
    }
}
