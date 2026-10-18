package com.lumora.photoeditor.engine

/**
 * Laplace-based seamless cloning for the Heal tool.
 *
 * Clean-room implementation inspired by GIMP's Heal tool, which is modeled
 * on the "Photoshop Healing Brush" paper (Laplace reconstruction):
 *
 * 1. Copy source patch S over destination region D (like clone stamp).
 * 2. Compute difference h = S - D (tonal/lighting mismatch).
 * 3. Fit a plane h_smooth(x,y) = a + bx + cy to h over the disc's boundary
 *    ring (least squares). A plane is exactly harmonic (Δh_smooth = 0),
 *    so this IS the Laplace solution for locally-linear tone — and unlike
 *    Jacobi/Gauss-Seidel iteration it converges fully in O(N): iterative
 *    diffusion needs O(r²) iterations for the boundary tone to reach the
 *    disc center (not O(r)), which left large spots visibly under-healed.
 *    Fitting only ring pixels keeps blemish interiors and texture noise
 *    out of the tone field; the fit is robust (median/MAD outlier
 *    rejection + refit) so the blemish's own edge can't drag the plane
 *    when the brush is sized close to the blemish — that used to leave a
 *    faint ghost of the spot in the disc center.
 * 4. Healed = D + (h - h_smooth) * strength: keeps source texture, bends
 *    tone to match destination seamlessly at the boundary.
 *
 * The source patch is bilinearly resampled so fractional source offsets
 * don't shift the texture by a pixel (visible as a seam); the destination
 * is sampled exactly (it is the pixel being rewritten).
 *
 * The result is written into a straight-alpha overlay (rgb = healed color,
 * a = feathered disc coverage). Composited as mix(base, rgb, a) this
 * reproduces the classic in-place result exactly when base == image.
 *
 * No GIMP/GPL code is used — written from the published algorithm
 * (Pérez et al., "Poisson Image Editing", SIGGRAPH 2003; Healing Brush paper).
 */
object LaplaceHeal {

    /**
     * Heals a circular region into a straight-alpha overlay.
     *
     * @param image     Source image (ARGB ints, w x h). Read-only — the
     *                  overlay starts transparent, so healing "from the
     *                  overlay" would be a silent no-op; the real pixels
     *                  must be supplied here.
     * @param out       Straight-alpha overlay receiving the healed disc.
     *                  Normally ARGB ints, w x h — but may be a bbox-sized
     *                  sub-buffer; see [outOx]/[outOy]/[outW]/[outH].
     *                  Existing content is composited under (alpha "over"),
     *                  so overlapping spots behave sanely.
     * @param w,h       Image dimensions.
     * @param cx,cy     Center of heal region (pixel coords).
     * @param radius    Radius in pixels.
     * @param srcDx,srcDy Offset from destination to source patch
     *                  (source pixel = dest pixel + offset; fractional).
     * @param feather   0..1 edge softness.
     * @param strength  0..1 blend strength.
     */
    fun heal(
        image: IntArray, out: IntArray, w: Int, h: Int,
        cx: Float, cy: Float, radius: Float,
        srcDx: Float, srcDy: Float,
        feather: Float = 0.5f,
        strength: Float = 1f,
        // Bbox-scoped output: `out` may be a sub-buffer of stride outW
        // covering image rows outOy..outOy+outH-1, with image pixel (x, y)
        // at (y - outOy) * outW + (x - outOx). `image` is always full-frame
        // (w x h); all internal math stays in image coordinates, only the
        // final write index is translated. Defaults = legacy full-frame.
        outOx: Int = 0,
        outOy: Int = 0,
        outW: Int = w,
        outH: Int = h,
    ) {
        val r = radius.coerceAtLeast(1f)
        val x0 = (cx - r).toInt().coerceIn(0, w - 1)
        val x1 = (cx + r).toInt().coerceIn(0, w - 1)
        val y0 = (cy - r).toInt().coerceIn(0, h - 1)
        val y1 = (cy + r).toInt().coerceIn(0, h - 1)
        if (x1 <= x0 || y1 <= y0) return
        if (image.size < w * h || out.size < outW * outH) return

        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        val featherF = feather.coerceIn(0.05f, 1f)
        val strengthF = strength.coerceIn(0f, 1f)
        if (strengthF <= 0f) return

        // Slide the source patch so it stays fully inside the frame.
        // Sampling with the raw offset and clamping per-pixel (the old
        // sampleChannelBilinear behavior) smeared the edge column across
        // the patch when the source rect ran off-frame — a visible streak
        // when healing/cloning near image borders. Moving the whole patch
        // keeps every sampled pixel real; the tone plane fit below then
        // sees true ring values instead of duplicated edge pixels.
        val (sDx, sDy) = clampSourceOffset(srcDx, srcDy, x0, x1, y0, y1, w, h)

        // Masks: 1 inside the heal disc (feathered), 0 outside.
        val mask = FloatArray(rw * rh)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy) / r
            val m = if (d >= 1f) 0f else {
                val t = ((1f - d) / featherF).coerceIn(0f, 1f)
                t * t * (3f - 2f * t) // smoothstep
            }
            mask[(y - y0) * rw + (x - x0)] = m
        }

        // Tone field via plane fit (see class KDoc): for each channel, fit
        // h = S - D over the boundary ring (0.001 < mask < 0.15), then
        // evaluate the plane everywhere as the smooth correction field.
        // No iteration: a plane is exactly harmonic, converges fully, and
        // costs O(N) instead of O(r²) diffusion steps.
        val healed = Array(3) { FloatArray(rw * rh) }
        for (c in 0..2) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            val diff = FloatArray(rw * rh)
            // Ring samples for the tone-field plane fit: pixels in the
            // feather band (0.001 < mask < 0.15), in center-relative coords
            // (numerically stable). Collected first so the fit can reject
            // outliers — see robustFitPlane.
            val ringX = ArrayList<Double>(256)
            val ringY = ArrayList<Double>(256)
            val ringH = ArrayList<Double>(256)
            for (y in y0..y1) for (x in x0..x1) {
                val i = (y - y0) * rw + (x - x0)
                val m = mask[i]
                if (m <= 0.001f) continue
                val sVal = sampleChannelBilinear(image, w, h, x + sDx, y + sDy, shift)
                val dVal = ((image[y * w + x] shr shift) and 0xFF).toFloat()
                val h = sVal - dVal
                diff[i] = h
                healed[c][i] = dVal // default: unchanged (safety if skipped below)
                if (m < 0.15f) {
                    ringX.add((x - cx).toDouble())
                    ringY.add((y - cy).toDouble())
                    ringH.add(h.toDouble())
                }
            }

            // Tone field via a ROBUST plane fit over the ring samples
            // (see robustFitPlane): h_smooth ≈ a + b*x + cc*y.
            val (a, b, cc) = robustFitPlane(ringX, ringY, ringH)

            // healed = D + (h - h_smooth) * strength
            for (y in y0..y1) for (x in x0..x1) {
                val i = (y - y0) * rw + (x - x0)
                if (mask[i] <= 0.001f) continue
                val dVal = ((image[y * w + x] shr shift) and 0xFF).toFloat()
                val hSmooth = (a + b * (x - cx) + cc * (y - cy)).toFloat()
                healed[c][i] = (dVal + (diff[i] - hSmooth) * strengthF)
                    .coerceIn(0f, 255f)
            }
        }

        // Write the healed disc into the overlay (straight alpha, "over").
        // With rgb = healed and a = mask, mix(base, rgb, a) reproduces the
        // classic in-place result exactly when base == image.
        for (y in y0..y1) for (x in x0..x1) {
            val i = (y - y0) * rw + (x - x0)
            val m = mask[i]
            if (m <= 0.001f) continue
            // Translated write when `out` is bbox-scoped; identical to
            // y * w + x for the legacy full-frame buffer.
            val gi = (y - outOy) * outW + (x - outOx)
            val dst = out[gi]
            val da = ((dst ushr 24) and 0xFF) / 255f
            val na = (m + da * (1f - m)).coerceIn(0f, 1f)
            if (na <= 0f) continue
            val dr = ((dst shr 16) and 0xFF).toFloat()
            val dg = ((dst shr 8) and 0xFF).toFloat()
            val db = (dst and 0xFF).toFloat()
            val nr = (healed[0][i] * m + dr * da * (1f - m)) / na
            val ng = (healed[1][i] * m + dg * da * (1f - m)) / na
            val nb = (healed[2][i] * m + db * da * (1f - m)) / na
            out[gi] = ((na * 255f).toInt().coerceIn(0, 255) shl 24) or
                (nr.toInt().coerceIn(0, 255) shl 16) or
                (ng.toInt().coerceIn(0, 255) shl 8) or
                nb.toInt().coerceIn(0, 255)
        }
    }

    /**
     * Slides a source-patch offset so the sampled rect stays fully inside
     * the frame: the shifted ranges (x0+dx)..(x1+dx) and (y0+dy)..(y1+dy)
     * are kept within bounds.
     * Per-pixel edge clamping smears the border column across the patch
     * (visible streaks near image edges); moving the whole patch keeps
     * every sample a real pixel. In-frame offsets pass through unchanged.
     * The target interval is never empty since (x1-x0) <= (w-1).
     */
    fun clampSourceOffset(
        srcDx: Float, srcDy: Float,
        x0: Int, x1: Int, y0: Int, y1: Int, w: Int, h: Int,
    ): Pair<Float, Float> = Pair(
        srcDx.coerceIn(-x0.toFloat(), (w - 1 - x1).toFloat()),
        srcDy.coerceIn(-y0.toFloat(), (h - 1 - y1).toFloat()),
    )

    /**
     * Robust least-squares plane fit over the ring samples: h ≈ a + b*x + cc*y.
     *
     * The feather band that forms the ring can catch the blemish's own edge
     * when the brush is sized close to the blemish (the normal case): those
     * pixels carry h ≈ ±blemishDepth while the clean ring sits near the true
     * tone offset, and a plain least-squares fit gets dragged — leaving a
     * faint ghost of the blemish in the disc center. So this fits once on
     * all samples, rejects samples whose residual is far from the residual
     * median (median/MAD rule, immune to the minority of outliers), and
     * refits on the inliers. Falls back to the sample median as a flat field
     * (still harmonic) when the ring is too small or mostly blemish.
     */
    private fun robustFitPlane(
        xs: List<Double>, ys: List<Double>, hs: List<Double>,
    ): Triple<Double, Double, Double> {
        val n = hs.size
        if (n < 8) {
            val mean = if (n > 0) hs.sum() / n else 0.0
            return Triple(mean, 0.0, 0.0)
        }
        val first = fitPlane(xs, ys, hs)
        // Residuals of the first fit; the blemish-edge outliers stand far
        // off the clean ring's residuals.
        val res = DoubleArray(n) { i ->
            hs[i] - (first.first + first.second * xs[i] + first.third * ys[i])
        }
        val med = median(res)
        val mad = median(DoubleArray(n) { i -> kotlin.math.abs(res[i] - med) })
        val tol = maxOf(12.0, 4.0 * mad)
        val ix = ArrayList<Double>(n); val iy = ArrayList<Double>(n)
        val ih = ArrayList<Double>(n)
        for (i in 0 until n) {
            if (kotlin.math.abs(res[i] - med) <= tol) {
                ix.add(xs[i]); iy.add(ys[i]); ih.add(hs[i])
            }
        }
        return if (ih.size >= 8) fitPlane(ix, iy, ih)
        else Triple(median(hs.toDoubleArray()), 0.0, 0.0)
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * Least-squares plane fit: h ≈ a + b*x + cc*y over the given samples.
     * Returns (a, b, cc). Falls back to the sample mean (flat field) when the
     * system is degenerate — a flat field is still a valid harmonic
     * solution, just less precise on strong gradients.
     */
    private fun fitPlane(
        xs: List<Double>, ys: List<Double>, hs: List<Double>,
    ): Triple<Double, Double, Double> {
        val n = xs.size
        var sx = 0.0; var sy = 0.0; var sh = 0.0
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var sxh = 0.0; var syh = 0.0
        for (i in 0 until n) {
            val x = xs[i]; val y = ys[i]; val h = hs[i]
            sx += x; sy += y; sh += h
            sxx += x * x; sxy += x * y; syy += y * y
            sxh += x * h; syh += y * h
        }
        if (n < 8) {
            val mean = if (n > 0) sh / n else 0.0
            return Triple(mean, 0.0, 0.0)
        }
        // Normal equations: M * [a b cc]^T = v, solved via Cramer.
        val m00 = n.toDouble(); val m01 = sx;         val m02 = sy
        val m10 = sx;          val m11 = sxx;        val m12 = sxy
        val m20 = sy;          val m21 = sxy;        val m22 = syy
        val v0 = sh; val v1 = sxh; val v2 = syh
        fun det3(
            a00: Double, a01: Double, a02: Double,
            a10: Double, a11: Double, a12: Double,
            a20: Double, a21: Double, a22: Double,
        ) = a00 * (a11 * a22 - a12 * a21) -
            a01 * (a10 * a22 - a12 * a20) +
            a02 * (a10 * a21 - a11 * a20)
        val det = det3(
            m00, m01, m02,
            m10, m11, m12,
            m20, m21, m22,
        )
        // Degenerate (e.g. ring collapses to a line): fall back to the mean.
        if (kotlin.math.abs(det) < 1e-9 * (1.0 + kotlin.math.abs(m00 * m11 * m22))) {
            return Triple(sh / n, 0.0, 0.0)
        }
        val a = det3(v0, m01, m02, v1, m11, m12, v2, m21, m22) / det
        val b = det3(m00, v0, m02, m10, v1, m12, m20, v2, m22) / det
        val cc = det3(m00, m01, v0, m10, m11, v1, m20, m21, v2) / det
        return Triple(a, b, cc)
    }

    /** Bilinear resample of one channel at fractional coords (edge-clamped). */
    private fun sampleChannelBilinear(
        px: IntArray, w: Int, h: Int, fx: Float, fy: Float, shift: Int,
    ): Float {
        val ix = fx.toInt()
        val iy = fy.toInt()
        val x0 = ix.coerceIn(0, w - 1); val y0 = iy.coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1); val y1 = (y0 + 1).coerceIn(0, h - 1)
        val tx = (fx - ix).coerceIn(0f, 1f); val ty = (fy - iy).coerceIn(0f, 1f)
        fun v(x: Int, y: Int) = ((px[y * w + x] shr shift) and 0xFF).toFloat()
        val top = v(x0, y0) * (1f - tx) + v(x1, y0) * tx
        val bot = v(x0, y1) * (1f - tx) + v(x1, y1) * tx
        return top * (1f - ty) + bot * ty
    }
}
