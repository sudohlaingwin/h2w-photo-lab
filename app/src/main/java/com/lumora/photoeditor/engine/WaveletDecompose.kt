package com.lumora.photoeditor.engine

/**
 * À trous ("with holes") wavelet decomposition for professional skin retouching.
 *
 * Clean-room implementation inspired by GIMP's Wavelet Decompose plugin
 * (which itself implements the Starck et al. à trous algorithm).
 * No GIMP/GPL code is used — this is written from the published algorithm:
 * separable B3-spline [1,4,6,4,1]/16 kernel with 2^(j-1)-1 zeros inserted
 * between taps at each scale j (the "holes"), so every scale keeps full
 * image resolution while doubling effective support.
 *
 * Why it's magic for skin retouching (frequency separation with 5-7 bands
 * instead of 2):
 * - Fine scales  → pores, peach-fuzz texture (keep!)
 * - Mid scales   → blemishes, blotchiness (smooth these)
 * - Residual     → overall skin tone and lighting (even out gently)
 *
 * Reconstruction is exact: original = residual + Σ detail scales.
 */
object WaveletDecompose {

    /** B3-spline scaling kernel. */
    private val KERNEL = floatArrayOf(1f, 4f, 6f, 4f, 1f)
    private const val KERNEL_SUM = 16f

    /**
     * Decomposes [src] (ARGB ints, [w]x[h]) into [numScales] detail layers
     * plus a residual. Returns Pair(detailLayers, residual) where each layer
     * is a FloatArray of RGB triplets (3*w*h floats, centered around 0 for
     * detail layers).
     */
    fun decompose(src: IntArray, w: Int, h: Int, numScales: Int = 5): Pair<List<FloatArray>, FloatArray> {
        require(w > 0 && h > 0 && src.size >= w * h)
        val scales = numScales.coerceIn(1, 7)

        // Work in float RGB.
        var current = FloatArray(w * h * 3)
        for (i in src.indices) {
            val p = src[i]
            current[i * 3] = ((p shr 16) and 0xFF).toFloat()
            current[i * 3 + 1] = ((p shr 8) and 0xFF).toFloat()
            current[i * 3 + 2] = (p and 0xFF).toFloat()
        }

        val details = mutableListOf<FloatArray>()
        for (j in 1..scales) {
            val step = 1 shl (j - 1) // 1, 2, 4, 8, ...
            val blurred = separableBlurHoles(current, w, h, step)
            // Detail = current - blurred
            val detail = FloatArray(w * h * 3)
            for (i in detail.indices) {
                detail[i] = current[i] - blurred[i]
            }
            details.add(detail)
            current = blurred
        }
        return Pair(details, current) // current is now the residual c_N
    }

    /**
     * Reconstructs the image from detail layers and residual.
     * [smoothing] per-scale factors in [0,1]: 1 = keep detail fully,
     * 0 = remove that scale's detail entirely (max smoothing).
     * Returns ARGB IntArray.
     */
    fun reconstruct(
        details: List<FloatArray>,
        residual: FloatArray,
        w: Int, h: Int,
        smoothing: FloatArray? = null,
    ): IntArray {
        val n = w * h
        val rgb = FloatArray(n * 3) { residual[it] }
        for ((s, detail) in details.withIndex()) {
            val keep = smoothing?.getOrNull(s)?.coerceIn(0f, 1f) ?: 1f
            if (keep >= 0.999f) {
                for (i in 0 until n * 3) rgb[i] += detail[i]
            } else if (keep > 0.001f) {
                for (i in 0 until n * 3) rgb[i] += detail[i] * keep
            }
            // keep <= 0.001: skip this scale entirely (full smoothing)
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val r = rgb[i * 3].toInt().coerceIn(0, 255)
            val g = rgb[i * 3 + 1].toInt().coerceIn(0, 255)
            val b = rgb[i * 3 + 2].toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * Separable convolution with the B3-spline kernel, inserting (step-1)
     * zeros between taps ("holes"). Mirrored edge handling.
     */
    private fun separableBlurHoles(src: FloatArray, w: Int, h: Int, step: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0..2) {
                    var acc = 0f
                    for (k in -2..2) {
                        val xx = mirror(x + k * step, w)
                        acc += src[(y * w + xx) * 3 + c] * KERNEL[k + 2]
                    }
                    tmp[(y * w + x) * 3 + c] = acc / KERNEL_SUM
                }
            }
        }
        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0..2) {
                    var acc = 0f
                    for (k in -2..2) {
                        val yy = mirror(y + k * step, h)
                        acc += tmp[(yy * w + x) * 3 + c] * KERNEL[k + 2]
                    }
                    out[(y * w + x) * 3 + c] = acc / KERNEL_SUM
                }
            }
        }
        return out
    }

    /** Mirrored edge handling: -1 → 0, w → w-1, w+1 → w-2, etc. */
    private fun mirror(i: Int, n: Int): Int {
        var v = i
        while (v < 0 || v >= n) {
            v = if (v < 0) -v else 2 * n - 2 - v
        }
        return v
    }

    /**
     * Convenience: smooth skin in one call.
     * [textureKeep] 0..1 — how much fine detail (scales 0-1: pores) to keep.
     * [blemishSmooth] 0..1 — how much to smooth mid scales (2-3: blemishes).
     * [toneEven] 0..1 — how much to even the residual (large-scale tone).
     * Returns retouched ARGB IntArray.
     */
    fun retouchSkin(
        src: IntArray, w: Int, h: Int,
        textureKeep: Float = 1f,
        blemishSmooth: Float = 0.5f,
        toneEven: Float = 0.3f,
        numScales: Int = 5,
    ): IntArray {
        val (details, residual) = decompose(src, w, h, numScales)
        val smoothing = FloatArray(numScales) { s ->
            when (s) {
                0, 1 -> textureKeep.coerceIn(0f, 1f)
                2, 3 -> (1f - blemishSmooth).coerceIn(0f, 1f)
                else -> (1f - toneEven * 0.5f).coerceIn(0f, 1f)
            }
        }
        // Gently even the residual toward local average for toneEven
        val evenResidual = if (toneEven > 0.01f) {
            val blurred = separableBlurHoles(residual, w, h, 8)
            val out = FloatArray(residual.size)
            val t = toneEven.coerceIn(0f, 1f) * 0.5f
            for (i in out.indices) {
                out[i] = residual[i] * (1f - t) + blurred[i] * t
            }
            out
        } else residual
        return reconstruct(details, evenResidual, w, h, smoothing)
    }
}
