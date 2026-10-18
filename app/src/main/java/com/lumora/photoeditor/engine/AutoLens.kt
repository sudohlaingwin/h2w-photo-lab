package com.lumora.photoeditor.engine

/**
 * Automatic lens-correction analysis — pro-premium program Phase A#2, slice 1.
 *
 * Optical vignetting (dark corners from the lens/sensor stack) is the one lens
 * defect we can measure reliably from the photo itself, deterministically, with
 * no machine learning and no lens database: it is radial, grows outward, and the
 * shader's vignette block is its exact inverse (negative amount lifts edges).
 *
 * Method: on a small downscale, bin Rec.709 luminance by shader-style radial
 * distance vd = |uv - 0.5| * 1.42 and fit the shader gain model
 *   gain = 1 - vAmt * 0.85 * vMask
 * (vMask = the shader's own smoothstep with the auto profile's midpoint/feather)
 * against the per-sample target gain Lc/luma, by least squares. Guards keep
 * naturally dark-edge content (night skies, dark backgrounds) untouched:
 * - enough signal in the center (Lc >= 0.15),
 * - mean outward falloff >= 6% (corner/center <= 1/1.06),
 * - falloff must ACCELERATE outward (outer gain > inner gain), the optical
 *   vignette signature — flat dark edges from scene content fail this.
 *
 * Fully pure Kotlin (IntArray ARGB pixels) so the real code is unit-testable
 * with a standalone kotlinc harness; the app calls it on a ~96px downscale
 * from [com.lumora.photoeditor.ui.state.EditorViewModel.autoLensCorrect].
 */
data class VignetteEstimate(
    /** -100..0 in slider units; negative lifts the corners. */
    val amount: Float,
    /** 0..100 slider units. */
    val midpoint: Float,
    /** 0..100 slider units. */
    val feather: Float,
)

object AutoLens {
    /** Auto profile: mid falloff start, wide blend — matches the shader ramp. */
    const val AUTO_MIDPOINT = 40f
    const val AUTO_FEATHER = 60f

    /** Minimum mean corner falloff that triggers a correction (6%). */
    private const val MIN_FALLOFF = 1.06f
    /** Maximum correction strength — beyond this is noise amplification, not optics. */
    private const val MAX_CORRECTION = 60f
    /** Below this the correction is invisible; don't touch the photo. */
    private const val MIN_CORRECTION = 3f

    fun analyzeVignette(px: IntArray, w: Int, h: Int): VignetteEstimate? {
        if (w < 8 || h < 8 || px.size < w * h) return null
        val mid = AUTO_MIDPOINT / 100f
        val fea = AUTO_FEATHER / 100f
        // Same ramp the shader uses (roundness = 0 for the auto profile).
        val e0 = mid * 0.9f
        val e1 = e0 + (1f - fea * 0.85f) + 0.05f

        var centerSum = 0.0; var centerN = 0
        var innerSum = 0.0; var innerN = 0
        var outerSum = 0.0; var outerN = 0
        for (y in 0 until h) {
            val v = y.toFloat() / (h - 1).coerceAtLeast(1)
            for (x in 0 until w) {
                val u = x.toFloat() / (w - 1).coerceAtLeast(1)
                val dx = u - 0.5f
                val dy = v - 0.5f
                val vd = kotlin.math.sqrt(dx * dx + dy * dy) * 1.42f
                val l = lumaOf(px[y * w + x])
                if (l < 0.02f) continue // near-black carries no gain signal
                when {
                    vd < 0.30f -> { centerSum += l; centerN++ }
                    vd in 0.55f..0.75f -> { innerSum += l; innerN++ }
                    vd > 0.80f -> { outerSum += l; outerN++ }
                }
            }
        }
        if (centerN < 20 || innerN < 20 || outerN < 20) return null
        val lc = (centerSum / centerN).toFloat()
        if (lc < 0.15f) return null // not enough signal to trust a correction
        val gInner = lc / (innerSum / innerN).toFloat()
        val gOuter = lc / (outerSum / outerN).toFloat()
        if (gOuter < MIN_FALLOFF) return null
        if (gOuter < gInner + 0.02f) return null // must accelerate outward

        // Least-squares fit of the shader gain model against Lc/luma.
        var num = 0.0
        var den = 0.0
        var fitN = 0
        for (y in 0 until h) {
            val v = y.toFloat() / (h - 1).coerceAtLeast(1)
            for (x in 0 until w) {
                val u = x.toFloat() / (w - 1).coerceAtLeast(1)
                val dx = u - 0.5f
                val dy = v - 0.5f
                val vd = kotlin.math.sqrt(dx * dx + dy * dy) * 1.42f
                val mask = smoothstep(e0, e1, vd)
                if (mask < 0.02f) continue
                val l = lumaOf(px[y * w + x])
                if (l < 0.02f) continue
                val g = (lc / l).coerceAtMost(3f)
                val k = 0.85 * mask
                num += k * (1.0 - g)
                den += k * k
                fitN++
            }
        }
        if (fitN < 20 || den <= 0.0) return null
        val vAmt = (num / den).toFloat() // shader units, negative for dark edges
        val amount = (vAmt * 100f).coerceIn(-MAX_CORRECTION, 0f)
        if (amount > -MIN_CORRECTION) return null
        return VignetteEstimate(amount, AUTO_MIDPOINT, AUTO_FEATHER)
    }

    private fun lumaOf(p: Int): Float {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
