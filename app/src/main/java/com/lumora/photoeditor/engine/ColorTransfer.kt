package com.lumora.photoeditor.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Look transfer — "match this photo's colors to a reference photo".
 *
 * Statistical color transfer after Reinhard, Adhikmin, Gooch & Shirley,
 * "Color Transfer between Images" (IEEE Computer Graphics and Applications,
 * 2001): both images' color statistics are computed in the decorrelated
 * lαβ space (log-LMS axes), then each source pixel is shifted/scaled so the
 * source distribution matches the reference distribution:
 *
 *   l' = (l - meanSrc_l) * (stdRef_l / stdSrc_l) + meanRef_l   (same for α, β)
 *
 * The resulting mapping is a pure per-pixel color function, so it bakes
 * exactly into a 3D LUT ([bakeLut]) that the GPU applies with hardware
 * trilinear filtering — no per-pixel CPU work at render time.
 *
 * This file has zero Android imports on purpose: stats, transfer and bake
 * are pure math, unit-testable on the JVM via the standalone kotlinc
 * harness (see AGENTS.md). The matrices below are the paper's published
 * constants; the harness verifies the RGB→lαβ→RGB round-trip is identity,
 * so any transcription error fails loudly there, not on the user's phone.
 *
 * Honest accounting of what this is and isn't:
 * - IS: deterministic statistical color matching. Offline, no ML, no fake AI.
 * - ISN'T: semantic relighting. It matches global color statistics, so a
 *   daylight photo matched to a neon-night reference gets neon-night *colors*
 *   (mean/hue/spread), not neon-night *lighting*. The Strength slider blends
 *   the match so the user controls how far it goes.
 */
object ColorTransfer {

    /** 3D LUT resolution per axis (17^3 = 4913 cells), same as [AiProRetouch]. */
    const val LUT_SIZE = 17

    // --- Reinhard et al. 2001 matrices (sRGB, D65) ---

    // RGB -> LMS (the paper's published constants).
    private const val L_R = 0.3811f; private const val L_G = 0.5783f; private const val L_B = 0.0402f
    private const val M_R = 0.1967f; private const val M_G = 0.7244f; private const val M_B = 0.0782f
    private const val S_R = 0.0241f; private const val S_G = 0.1288f; private const val S_B = 0.8444f

    /**
     * LMS -> RGB: the EXACT inverse of the matrix above, derived numerically
     * at init. The paper prints both matrices rounded to 4 decimals, but the
     * rounded pair is not exactly invertible (off-diagonal error ~7e-3 leaks
     * ~1.6/255 into the blue channel on RGB->lαβ->RGB round-trips, caught by
     * the harness). Deriving it keeps the round-trip an identity up to float
     * noise; it is still the paper's transform, just un-rounded.
     */
    private val INV: FloatArray = run {
        val a = L_R.toDouble(); val b = L_G.toDouble(); val c = L_B.toDouble()
        val d = M_R.toDouble(); val e = M_G.toDouble(); val f = M_B.toDouble()
        val g = S_R.toDouble(); val h = S_G.toDouble(); val i = S_B.toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(det != 0.0) { "singular LMS matrix" }
        val inv = doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
        FloatArray(9) { inv[it].toFloat() }
    }

    private val SQRT3 = sqrt(3f)
    private val SQRT6 = sqrt(6f)
    private val SQRT2 = sqrt(2f)

    /** Floor for LMS before log(): log(0) is undefined; pure black maps back to ~0. */
    private const val LOG_EPS = 1e-4f

    /**
     * Spread-ratio clamp bounds (see [transferPixel]). A ratio of 5 already
     * quintuples a channel's contrast — beyond that the result stops looking
     * like a color match and starts looking broken.
     */
    private const val MIN_RATIO = 0.2f
    private const val MAX_RATIO = 5f

    /**
     * lαβ color statistics of one image: [meanL, meanA, meanB, stdL, stdA, stdB].
     * Kept as a plain FloatArray (6) so [LookTransferParams] serializes it as a
     * JSON list with no custom serializer.
     */
    fun analyze(pixels: IntArray, w: Int, h: Int): FloatArray {
        val n = pixels.size
        if (n == 0 || w <= 0 || h <= 0) return FloatArray(6)
        // Stride-sample to ~50k pixels: statistics don't need full res.
        val step = max(1, sqrt(n / 50000f).toInt())
        var count = 0
        var sumL = 0.0; var sumA = 0.0; var sumB = 0.0
        for (i in pixels.indices step step) {
            val lab = rgbToLab(pixels[i])
            sumL += lab[0]; sumA += lab[1]; sumB += lab[2]
            count++
        }
        if (count == 0) return FloatArray(6)
        val meanL = (sumL / count).toFloat()
        val meanA = (sumA / count).toFloat()
        val meanB = (sumB / count).toFloat()
        var varL = 0.0; var varA = 0.0; var varB = 0.0
        for (i in pixels.indices step step) {
            val lab = rgbToLab(pixels[i])
            varL += (lab[0] - meanL) * (lab[0] - meanL)
            varA += (lab[1] - meanA) * (lab[1] - meanA)
            varB += (lab[2] - meanB) * (lab[2] - meanB)
        }
        return floatArrayOf(
            meanL, meanA, meanB,
            sqrt((varL / count).toFloat()), sqrt((varA / count).toFloat()), sqrt((varB / count).toFloat()),
        )
    }

    /** RGB 0..1 -> lαβ. Public for the harness round-trip check. */
    fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
        val lmsL = max(L_R * r + L_G * g + L_B * b, LOG_EPS)
        val lmsM = max(M_R * r + M_G * g + M_B * b, LOG_EPS)
        val lmsS = max(S_R * r + S_G * g + S_B * b, LOG_EPS)
        val lp = ln(lmsL); val mp = ln(lmsM); val sp = ln(lmsS)
        return floatArrayOf(
            (lp + mp + sp) / SQRT3,
            (lp + mp - 2f * sp) / SQRT6,
            (lp - mp) / SQRT2,
        )
    }

    private fun rgbToLab(pixel: Int): FloatArray = rgbToLab(
        ((pixel shr 16) and 0xFF) / 255f,
        ((pixel shr 8) and 0xFF) / 255f,
        (pixel and 0xFF) / 255f,
    )

    /** lαβ -> RGB 0..1. Public for the harness round-trip check. */
    fun labToRgb(l: Float, a: Float, b: Float): FloatArray {
        val lp = l / SQRT3 + a / SQRT6 + b / SQRT2
        val mp = l / SQRT3 + a / SQRT6 - b / SQRT2
        val sp = l / SQRT3 - 2f * a / SQRT6
        val lmsL = exp(lp); val lmsM = exp(mp); val lmsS = exp(sp)
        return floatArrayOf(
            (INV[0] * lmsL + INV[1] * lmsM + INV[2] * lmsS).coerceIn(0f, 1f),
            (INV[3] * lmsL + INV[4] * lmsM + INV[5] * lmsS).coerceIn(0f, 1f),
            (INV[6] * lmsL + INV[7] * lmsM + INV[8] * lmsS).coerceIn(0f, 1f),
        )
    }

    /**
     * Maps one RGB pixel (0..1) from the [src] distribution to the [dst]
     * distribution. Both stats are [analyze] outputs (6 floats).
     *
     * The per-channel spread ratio is clamped to [MIN_RATIO, MAX_RATIO]: the
     * paper's raw ratio explodes when the source is nearly flat in a channel
     * (e.g. an isoluminant or foggy photo matched to a contrasty reference),
     * which would blow highlights/shadows out. The mean shift — the part the
     * eye reads as "the look" — is unaffected by the clamp.
     */
    fun transferPixel(r: Float, g: Float, b: Float, src: FloatArray, dst: FloatArray): FloatArray {
        require(src.size == 6 && dst.size == 6) { "stats must be analyze() output (6 floats)" }
        val lab = rgbToLab(r, g, b)
        val out = FloatArray(3)
        for (c in 0..2) {
            // Guard: a flat source channel has ~0 std; an unclamped ratio
            // would paint garbage. Ratio 1 keeps the channel's own spread.
            val rawRatio = if (src[c + 3] > 1e-4f) dst[c + 3] / src[c + 3] else 1f
            val ratio = rawRatio.coerceIn(MIN_RATIO, MAX_RATIO)
            out[c] = (lab[c] - src[c]) * ratio + dst[c]
        }
        return labToRgb(out[0], out[1], out[2])
    }

    /**
     * Bakes the src->dst transfer into a 3D LUT with [n] cells per axis,
     * layout `lut[(((r*n)+g)*n+b)*3 + c]` — the exact layout
     * [GlEngine.uploadLookLut] expects (same as the AI-grade LUTs).
     */
    fun bakeLut(src: FloatArray, dst: FloatArray, n: Int = LUT_SIZE): FloatArray {
        require(n >= 2) { "LUT size must be >= 2" }
        val lut = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val mapped = transferPixel(r / (n - 1f), g / (n - 1f), b / (n - 1f), src, dst)
            val o = (((r * n) + g) * n + b) * 3
            lut[o] = mapped[0]; lut[o + 1] = mapped[1]; lut[o + 2] = mapped[2]
        }
        return lut
    }

    /**
     * Largest per-channel deviation of the src->dst LUT from identity, in
     * 0..1. Callers use it as the no-op honesty guard: when the reference
     * photo's colors already match, say so instead of adding an undo entry.
     */
    fun maxDeviationFromIdentity(src: FloatArray, dst: FloatArray, n: Int = 9): Float {
        var maxDev = 0f
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val ir = r / (n - 1f); val ig = g / (n - 1f); val ib = b / (n - 1f)
            val mapped = transferPixel(ir, ig, ib, src, dst)
            maxDev = max(maxDev, kotlin.math.abs(mapped[0] - ir))
            maxDev = max(maxDev, kotlin.math.abs(mapped[1] - ig))
            maxDev = max(maxDev, kotlin.math.abs(mapped[2] - ib))
        }
        return maxDev
    }
}
