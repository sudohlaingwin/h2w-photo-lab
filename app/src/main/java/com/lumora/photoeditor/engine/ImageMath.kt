package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * Pure-Kotlin mirror of the GLSL pipeline math. Used by unit tests and by
 * CPU-side paths (histogram estimation, mask rasterization helpers).
 * Must stay behaviorally identical to Shaders.FRAGMENT.
 */
object ImageMath {

    fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Mean channel value of [bmp] (0..1), measured on a 48px downscale so it
     * stays cheap on full-res photos. Pure CPU; used by the preview
     * watchdog's "is the source itself dark?" gate — a night photo
     * legitimately renders near-black and must never trip it.
     */
    fun meanLuma(bmp: Bitmap): Float {
        val sw = 48
        val sh = 48
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        return try {
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            var sum = 0L
            for (p in px) {
                sum += (p shr 16) and 0xFF
                sum += (p shr 8) and 0xFF
                sum += p and 0xFF
            }
            sum / (px.size * 3f * 255f)
        } finally {
            if (small !== bmp) small.recycle()
        }
    }

    fun exposure(c: Float, ev: Float): Float = c * 2f.pow(ev)

    fun whiteBalance(r: Float, g: Float, b: Float, temp: Float, tint: Float): FloatArray {
        // temp/tint in -1..1, mirrors shader
        return floatArrayOf(
            r * (1f + temp * 0.42f),
            g * (1f - tint * 0.30f),
            b * (1f - temp * 0.42f),
        )
    }

    /**
     * White-balance eyedropper solver: given a sampled displayed pixel
     * (r, g, b in 0..1) and the current temp/tint (in -1..1 shader domain),
     * returns the temp/tint that make the sampled point neutral gray, or null
     * when the sample is too dark to trust.
     *
     * The sample is treated as the pipeline output: source chromaticity is
     * estimated by dividing out the current WB gains, then new gains are
     * solved with green as the anchor (gr + gb = 2 keeps overall exposure
     * stable). Inverse of [whiteBalance] up to the anchor choice.
     */
    fun pickWhiteBalance(r: Float, g: Float, b: Float, temp: Float, tint: Float): Pair<Float, Float>? {
        if (max(r, max(g, b)) < 0.04f) return null
        val gr0 = 1f + temp * 0.42f
        val gg0 = 1f - tint * 0.30f
        val gb0 = 1f - temp * 0.42f
        val sr = r / max(gr0, 1e-4f)
        val sg = g / max(gg0, 1e-4f)
        val sb = b / max(gb0, 1e-4f)
        val a = (sg / max(sr, 1e-4f)).coerceIn(0.2f, 5f)
        val bb = (sg / max(sb, 1e-4f)).coerceIn(0.2f, 5f)
        val w = 2f / (a + bb)
        val t1 = (w * (a - bb) / 0.84f).coerceIn(-1f, 1f)
        val ti1 = ((1f - w) / 0.30f).coerceIn(-1f, 1f)
        return t1 to ti1
    }

    /**
     * White-balance scene presets (Lightroom parity). temp/tint are in the
     * -1..1 shader domain (the UI stores them x100, see LightParams).
     * Each preset neutralizes the cast typical of that light source:
     * tungsten-lit scenes look too orange -> cooling (negative temp),
     * shade looks too blue -> warming (positive temp), fluorescent carries
     * a green cast -> magenta correction (positive tint; the tint slider
     * follows the Lightroom convention, green(-) <-> magenta(+)).
     */
    data class WbPreset(val label: String, val temp: Float, val tint: Float)

    val WB_PRESETS: List<WbPreset> = listOf(
        WbPreset("Daylight", 0f, 0f),
        WbPreset("Cloudy", 0.18f, 0.02f),
        WbPreset("Shade", 0.36f, 0.04f),
        WbPreset("Tungsten", -0.42f, 0f),
        WbPreset("Fluorescent", -0.27f, 0.12f),
        WbPreset("Flash", 0.02f, 0f),
    )

    /**
     * Gray-world auto white balance: given the average scene color
     * (r, g, b in 0..1), returns the temp/tint (-1..1 shader domain) that
     * neutralize it — the exact inverse of [whiteBalance]. The temp gain
     * maps r and b onto their harmonic mean L; tint then lands green on L
     * as well, so the corrected average is exactly neutral. Deterministic
     * pixel math; no machine learning. Returns null when the average is
     * too dark to trust.
     */
    fun autoWhiteBalance(r: Float, g: Float, b: Float): Pair<Float, Float>? {
        if (max(r, max(g, b)) < 0.04f) return null
        val t = ((b - r) / (0.42f * max(r + b, 1e-4f))).coerceIn(-1f, 1f)
        val l = 2f * r * b / max(r + b, 1e-4f)
        val ti = ((1f - l / max(g, 1e-4f)) / 0.30f).coerceIn(-1f, 1f)
        return t to ti
    }

    /** Parametric tone: hi/sh/wh/bl in -1..1, mirrors shader weights. */
    fun parametric(r: Float, g: Float, b: Float, hi: Float, sh: Float, wh: Float, bl: Float): FloatArray {
        val l = luma(r, g, b)
        val wH = smoothstep(0.45f, 0.95f, l)
        val wS = 1f - smoothstep(0.05f, 0.55f, l)
        val wW = smoothstep(0.72f, 1.0f, l)
        val wB = 1f - smoothstep(0.0f, 0.28f, l)
        val ev = hi * 1.35f * wH + sh * 1.35f * wS + wh * 0.9f * wW + bl * 0.9f * wB
        val f = 2f.pow(ev)
        return floatArrayOf(r * f, g * f, b * f)
    }

    fun contrast(c: Float, amt: Float): Float {
        val f = (1f + amt).coerceIn(0f, 3f)
        return 0.5f + (c - 0.5f) * f
    }

    fun rgb2hsl(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        var h = 0f; var s = 0f
        val l = (mx + mn) * 0.5f
        val d = mx - mn
        if (d > 1e-6f) {
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when {
                mx == r -> (g - b) / d + (if (g < b) 6f else 0f)
                mx == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
        }
        return floatArrayOf(h, s, l)
    }

    private fun hue2rgb(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    fun hsl2rgb(h: Float, s: Float, l: Float): FloatArray {
        if (s < 1e-6f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        return floatArrayOf(
            hue2rgb(p, q, h + 1f / 3f),
            hue2rgb(p, q, h),
            hue2rgb(p, q, h - 1f / 3f),
        )
    }

    /**
     * Non-allocating twin of [hsl2rgb]: writes the RGB result into [out]
     * (size ≥ 3) and returns it. For hot paths (per-frame GL uniform uploads)
     * where the old version's floatArrayOf per call was pure GC churn.
     */
    fun hsl2rgbInto(h: Float, s: Float, l: Float, out: FloatArray): FloatArray {
        if (s < 1e-6f) {
            out[0] = l; out[1] = l; out[2] = l
            return out
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        out[0] = hue2rgb(p, q, h + 1f / 3f)
        out[1] = hue2rgb(p, q, h)
        out[2] = hue2rgb(p, q, h - 1f / 3f)
        return out
    }

    private val hslCenters = floatArrayOf(0f, 0.0833f, 0.1667f, 0.3333f, 0.5f, 0.6667f, 0.75f, 0.8333f)

    /** adjustments[i] = Triple(hueShift01, sat01, lum01). */
    fun hslMixer(r: Float, g: Float, b: Float, adjustments: Array<Triple<Float, Float, Float>>): FloatArray {
        val hsl = rgb2hsl(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        var (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
        for (i in 0 until 8) {
            val d = abs(((h - hslCenters[i] + 0.5f) % 1f + 1f) % 1f - 0.5f)
            val w = 1f - smoothstep(0.045f, 0.10f, d)
            if (w > 0.001f) {
                val (hs, ss, ls) = adjustments[i]
                h = (h + hs * 0.09f * w) % 1f
                if (h < 0) h += 1f
                s = (s * (1f + ss * w)).coerceIn(0f, 1f)
                l = (l + ls * 0.35f * w).coerceIn(0f, 1f)
            }
        }
        return hsl2rgb(h, s, l)
    }

    fun vibrance(r: Float, g: Float, b: Float, amt: Float): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = ((mx - mn) / max(mx + mn, 1e-4f)).coerceIn(0f, 1f)
        val f = (1f + amt * (1.15f - sat) * 1.2f).coerceIn(0f, 3f)
        val l = luma(r, g, b)
        return floatArrayOf(l + (r - l) * f, l + (g - l) * f, l + (b - l) * f)
    }

    fun saturation(r: Float, g: Float, b: Float, amt: Float): FloatArray {
        val f = (1f + amt).coerceIn(0f, 3f)
        val l = luma(r, g, b)
        return floatArrayOf(l + (r - l) * f, l + (g - l) * f, l + (b - l) * f)
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------
    // Mask geometry helpers (pure; rasterized by MaskEngine on Android)
    // ------------------------------------------------------------------

    /** Linear gradient mask factor at normalized point (x,y). shape=[x1,y1,x2,y2]. */
    fun linearGradientFactor(x: Float, y: Float, shape: List<Float>): Float {
        if (shape.size < 4) return 0f
        val (x1, y1, x2, y2) = shape
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-8f) return 0f
        val t = ((x - x1) * dx + (y - y1) * dy) / len2
        return t.coerceIn(0f, 1f)
    }

    /** Radial gradient mask factor. shape=[cx,cy,rx,ry,rotDeg,feather01]. */
    fun radialGradientFactor(x: Float, y: Float, shape: List<Float>): Float {
        if (shape.size < 6) return 0f
        val cx = shape[0]; val cy = shape[1]; val rx = shape[2]
        val ry = shape[3]; val rot = shape[4]; val feather = shape[5]
        val rad = Math.toRadians(rot.toDouble()).toFloat()
        val dx = x - cx; val dy = y - cy
        val lx = (dx * cos(rad) + dy * sin(rad)) / max(rx, 1e-6f)
        val ly = (-dx * sin(rad) + dy * cos(rad)) / max(ry, 1e-6f)
        val d = sqrt(lx * lx + ly * ly) // 0 center .. 1 edge
        val f = feather.coerceIn(0.01f, 1f)
        return (1f - smoothstep(1f - f, 1f, d)).coerceIn(0f, 1f)
    }

    /** Feather a brush stamp: 1 at center -> 0 at radius edge with smooth falloff. */
    fun brushFalloff(dist01: Float, feather: Float): Float {
        val f = feather.coerceIn(0.01f, 1f)
        return (1f - smoothstep(1f - f, 1f, dist01.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    /**
     * Honest heuristic sky selector (NOT AI — labeled "Auto" in the UI).
     * Selects bright, blue-dominant, low-saturation pixels biased to the
     * upper part of the frame, then feathers. Useful, but a heuristic.
     */
    fun selectSkyAuto(source: Bitmap): Bitmap {
        val w = 256
        val h = (256f * source.height / source.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        try {
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val sat = if (mx > 1e-4f) (mx - mn) / mx else 0f
                val blueDom = (b - r).coerceIn(0f, 1f)
                val bright = (mx * 0.6f + (r + g + b) / 3f * 0.4f)
                val upperBias = 1f - (y / h.toFloat()) * 0.75f
                var m = (blueDom * 1.6f - sat * 1.2f + (bright - 0.35f) * 1.4f) * upperBias
                m = m.coerceIn(0f, 1f)
                // smoothstep the selection for soft edges
                m = m * m * (3f - 2f * m)
                out[i] = ((m * 255f).toInt() shl 24)
            }
            val m1 = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            try {
                val full = Bitmap.createScaledBitmap(m1, source.width, source.height, true)
                try {
                    return full.copy(Bitmap.Config.ALPHA_8, false)
                } finally {
                    // copy() can throw (OOM on the full-res ALPHA_8 raster);
                    // never leave the full-res scratch bitmap behind.
                    if (full !== source) full.recycle()
                }
            } finally {
                m1.recycle()
            }
        } finally {
            // Was leaked on the happy path before: one 256xh ARGB_8888
            // bitmap per sky-select mask build. The !== guard mirrors
            // meanLuma: createScaledBitmap may return the source itself
            // when the sizes already match, and that is caller-owned.
            if (small !== source) small.recycle()
        }
    }

    /**
     * Luminance-range selection (Lightroom "Luminance Range" parity).
     * Deterministic pixel math — NOT AI. Selects the [lo]..[hi] band of
     * Rec.709 luminance with smoothstep-softened edges; [feather] (0..1)
     * controls the edge transition width. Pure function over ARGB pixels so
     * it can be unit-harnessed without Android.
     *
     * @return per-pixel mask strength 0..255.
     */
    fun luminanceRangeMask(
        px: IntArray, w: Int, h: Int,
        lo: Float, hi: Float, feather: Float,
    ): IntArray {
        val out = IntArray(w * h)
        if (lo >= hi || px.size < w * h) return out // degenerate -> empty selection
        val f = lo.coerceIn(0f, 1f); val t = hi.coerceIn(0f, 1f)
        val tw = (feather.coerceIn(0f, 1f) * 0.5f).coerceAtLeast(0.001f)
        for (i in 0 until w * h) {
            val c = px[i]
            val luma = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) +
                0.0722f * Color.blue(c)) / 255f
            // lower edge: fully selected at/above f (no cutoff when f <= 0)
            val lower = if (f <= 0.001f) 1f
            else smoothstep(f - tw / 2f, f + tw / 2f, luma)
            // upper edge: fully selected at/below t (no cutoff when t >= 1)
            val upper = if (t >= 0.999f) 1f
            else 1f - smoothstep(t - tw / 2f, t + tw / 2f, luma)
            out[i] = (min(lower, upper).coerceIn(0f, 1f) * 255f).toInt()
        }
        return out
    }

    /**
     * Bitmap wrapper for [luminanceRangeMask]: downscales the source (masks
     * are inherently smooth) and returns an ALPHA_8 raster with the mask
     * value in the alpha channel — the same convention as [selectSkyAuto],
     * so MaskEngine's stored-bitmap branch reads it directly.
     */
    fun luminanceRangeBitmap(
        source: Bitmap, lo: Float, hi: Float, feather: Float,
    ): Bitmap {
        val w = 256
        val h = (256f * source.height / source.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val m = luminanceRangeMask(px, w, h, lo, hi, feather)
        val out = IntArray(w * h) { i -> m[i] shl 24 }
        val m1 = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return m1.copy(Bitmap.Config.ALPHA_8, false).also { m1.recycle() }
    }

    /**
     * Focus-based depth estimate (Lightroom "Depth Range" parity — but note
     * the honesty caveat: the app has no camera depth map, so depth is
     * ESTIMATED from local focus sharpness. In-focus pixels are assumed near,
     * blurred pixels far. Deterministic pixel math — NOT AI, NOT a depth
     * sensor read. Works best on photos with a sharp subject and soft
     * background; on flat/blurry photos the map is flat.
     *
     * Method: |Laplacian| on Rec.709 luma (focus response), smoothed with a
     * sliding-window box blur to keep single hot pixels from dominating,
     * then normalized by the field max.
     *
     * @return per-pixel "nearness" 0..1 (1 = sharpest = assumed near).
     */
    fun estimateDepth(px: IntArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val out = FloatArray(n)
        if (px.size < n || n < 9) return out
        val lum = FloatArray(n) { i ->
            val c = px[i]
            (0.2126f * Color.red(c) + 0.7152f * Color.green(c) +
                0.0722f * Color.blue(c)) / 255f
        }
        val resp = FloatArray(n)
        var maxR = 0f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val xm = if (x > 0) x - 1 else x
            val xp = if (x < w - 1) x + 1 else x
            val ym = if (y > 0) y - 1 else y
            val yp = if (y < h - 1) y + 1 else y
            val lap = kotlin.math.abs(
                4f * lum[i] - lum[y * w + xm] - lum[y * w + xp] -
                    lum[ym * w + x] - lum[yp * w + x])
            resp[i] = lap
            if (lap > maxR) maxR = lap
        }
        // No texture anywhere: every pixel is equally "far" — return a flat map.
        if (maxR < 1e-4f) return out
        val smooth = FastBlur.boxBlurFloat(resp, w, h,
            (min(w, h) / 32).coerceAtLeast(1))
        val mx = smooth.maxOrNull() ?: 0f
        if (mx < 1e-6f) return out
        for (i in 0 until n) out[i] = (smooth[i] / mx).coerceIn(0f, 1f)
        return out
    }

    /**
     * Selects the [lo]..[hi] band of an estimated-depth field with
     * smoothstep-softened edges; [feather] (0..1) controls the edge
     * transition width. Same band semantics as [luminanceRangeMask].
     * Pure function over the depth field so it can be unit-harnessed without
     * Android.
     *
     * @return per-pixel mask strength 0..255.
     */
    fun depthBandMask(
        depth: FloatArray, w: Int, h: Int,
        lo: Float, hi: Float, feather: Float,
    ): IntArray {
        val out = IntArray(w * h)
        if (lo >= hi || depth.size < w * h) return out // degenerate -> empty
        val f = lo.coerceIn(0f, 1f); val t = hi.coerceIn(0f, 1f)
        val tw = (feather.coerceIn(0f, 1f) * 0.5f).coerceAtLeast(0.001f)
        for (i in 0 until w * h) {
            val d = depth[i].coerceIn(0f, 1f)
            // lower edge: fully selected at/above f (no cutoff when f <= 0)
            val lower = if (f <= 0.001f) 1f
            else smoothstep(f - tw / 2f, f + tw / 2f, d)
            // upper edge: fully selected at/below t (no cutoff when t >= 1)
            val upper = if (t >= 0.999f) 1f
            else 1f - smoothstep(t - tw / 2f, t + tw / 2f, d)
            out[i] = (min(lower, upper).coerceIn(0f, 1f) * 255f).toInt()
        }
        return out
    }

    /** Convenience: estimate depth then select the band (same signature shape
     *  as [luminanceRangeMask]). */
    fun depthRangeMask(
        px: IntArray, w: Int, h: Int,
        lo: Float, hi: Float, feather: Float,
    ): IntArray = depthBandMask(estimateDepth(px, w, h), w, h, lo, hi, feather)

    /**
     * Bitmap wrapper for [depthRangeMask]: downscales the source (masks are
     * inherently smooth) and returns an ALPHA_8 raster with the mask value
     * in the alpha channel — the same convention as [luminanceRangeBitmap],
     * so MaskEngine's stored-bitmap branch reads it directly.
     */
    fun depthRangeBitmap(
        source: Bitmap, lo: Float, hi: Float, feather: Float,
    ): Bitmap {
        val w = 256
        val h = (256f * source.height / source.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val m = depthRangeMask(px, w, h, lo, hi, feather)
        val out = IntArray(w * h) { i -> m[i] shl 24 }
        val m1 = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return m1.copy(Bitmap.Config.ALPHA_8, false).also { m1.recycle() }
    }

    /**
     * Perceptual-ish color distance (redmean approximation) between two RGB
     * colors, normalized to 0..1. Cheap, symmetric, and much better behaved
     * than plain Euclidean RGB for "select similar colors" tasks.
     */
    fun colorDistance01(
        r1: Float, g1: Float, b1: Float,
        r2: Float, g2: Float, b2: Float,
    ): Float {
        val rMean = (r1 + r2) * 0.5f
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        val d2 = (2f + rMean) * dr * dr + 4f * dg * dg + (2f + (1f - rMean)) * db * db
        return sqrt(d2) / 3f // max redmean distance is 3.0 on 0..1 RGB
    }

    /**
     * Color-range selection (Lightroom "Color Range" parity).
     * Deterministic pixel math — NOT AI. Selects pixels whose color is close
     * to [targetR]..[targetB] (0..1): [tolerance] (0..1) sets the selection
     * radius in color space, [feather] (0..1) the edge transition width.
     * Pure function over ARGB pixels so it can be unit-harnessed.
     *
     * @return per-pixel mask strength 0..255.
     */
    fun colorRangeMask(
        px: IntArray, w: Int, h: Int,
        targetR: Float, targetG: Float, targetB: Float,
        tolerance: Float, feather: Float,
    ): IntArray = colorRangeMaskSamples(
        px, w, h, listOf(ColorSample(targetR, targetG, targetB)),
        tolerance, feather)

    /**
     * Multi-sample color-range selection (Lightroom "refine with added
     * swatches" parity). Each pixel takes the MAXIMUM strength over all
     * [samples], i.e. the union of the per-sample selections, so adding a
     * swatch can only widen the selection — the same semantics as
     * Lightroom's add-swatch refinement.
     *
     * @return per-pixel mask strength 0..255.
     */
    fun colorRangeMaskSamples(
        px: IntArray, w: Int, h: Int,
        samples: List<ColorSample>,
        tolerance: Float, feather: Float,
    ): IntArray {
        val out = IntArray(w * h)
        if (px.size < w * h || samples.isEmpty()) return out
        // tolerance 0 still selects a ~2% color-distance core (perceptually
        // "the same color") instead of a degenerate 50%-at-exact-match band.
        val radius = 0.02f + tolerance.coerceIn(0f, 1f) * 0.73f
        val width = (0.03f + feather.coerceIn(0f, 1f) * 0.30f).coerceAtLeast(0.01f)
        // flattened, clamped targets: 3 per sample
        val ts = FloatArray(samples.size * 3)
        for (s in samples.indices) {
            ts[s * 3] = samples[s].r.coerceIn(0f, 1f)
            ts[s * 3 + 1] = samples[s].g.coerceIn(0f, 1f)
            ts[s * 3 + 2] = samples[s].b.coerceIn(0f, 1f)
        }
        for (i in 0 until w * h) {
            val c = px[i]
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            var d = 3f // min distance over samples
            for (s in 0 until ts.size step 3) {
                val cand = colorDistance01(r, g, b, ts[s], ts[s + 1], ts[s + 2])
                if (cand < d) d = cand
            }
            out[i] = ((1f - smoothstep(radius - width / 2f, radius + width / 2f, d))
                .coerceIn(0f, 1f) * 255f).toInt()
        }
        return out
    }

    /**
     * Bitmap wrapper for [colorRangeMask]: downscales the source (masks are
     * inherently smooth) and returns an ALPHA_8 raster with the mask value in
     * the alpha channel — the same convention as [selectSkyAuto], so
     * MaskEngine's stored-bitmap branch reads it directly.
     */
    fun colorRangeBitmap(
        source: Bitmap,
        targetR: Float, targetG: Float, targetB: Float,
        tolerance: Float, feather: Float,
    ): Bitmap = colorRangeBitmapSamples(
        source, listOf(ColorSample(targetR, targetG, targetB)),
        tolerance, feather)

    /**
     * Bitmap wrapper for [colorRangeMaskSamples]: downscales the source
     * (masks are inherently smooth) and returns an ALPHA_8 raster with the
     * mask value in the alpha channel — the same convention as
     * [selectSkyAuto], so MaskEngine's stored-bitmap branch reads it
     * directly.
     */
    fun colorRangeBitmapSamples(
        source: Bitmap,
        samples: List<ColorSample>,
        tolerance: Float, feather: Float,
    ): Bitmap {
        val w = 256
        val h = (256f * source.height / source.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val m = colorRangeMaskSamples(px, w, h, samples, tolerance, feather)
        val out = IntArray(w * h) { i -> m[i] shl 24 }
        val m1 = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return m1.copy(Bitmap.Config.ALPHA_8, false).also { m1.recycle() }
    }
}
