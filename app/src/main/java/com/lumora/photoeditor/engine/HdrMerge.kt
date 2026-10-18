package com.lumora.photoeditor.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * HDR merge via exposure fusion (Mertens et al. 2007, pro-spec Phase 4
 * "Merge to HDR"). NOT AI and NOT a neural model: for N bracketed frames
 * it builds per-pixel weight maps from three quality measures —
 * contrast (Laplacian response), saturation (RGB spread) and
 * well-exposedness (Gaussian around 0.5) — normalizes the weights across
 * frames, then blends with multi-resolution (Laplacian/Gaussian) pyramids
 * so transitions are seamless and halos are avoided.
 *
 * Honest scope: bracketed frames must be aligned (tripod or near-static
 * scene); there is no ghost/deghost pass yet. To keep phone memory sane,
 * the merge runs at a capped working resolution (default max side
 * 1280 px) via box downsampling — the fused result is a full preview-size
 * HDR composite, not a full-resolution raw merge.
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
object HdrMerge {

    /** Default cap on the longer side of the working resolution. */
    const val DEFAULT_MAX_DIM = 1280

    /** Default number of pyramid levels. */
    const val DEFAULT_LEVELS = 4

    /** Working resolution for a source of [w]x[h] under cap [maxDim]. */
    fun workingSize(w: Int, h: Int, maxDim: Int = DEFAULT_MAX_DIM): Pair<Int, Int> {
        val longer = max(w, h)
        if (longer <= maxDim || maxDim <= 0) return w to h
        val s = maxDim.toFloat() / longer.toFloat()
        return max(1, (w * s).toInt()) to max(1, (h * s).toInt())
    }

    /**
     * Fuses [frames] (same-size ARGB pixel arrays) into one composite.
     * Returns ARGB pixels at the working resolution — use [workingSize] to
     * know the returned geometry. A single frame returns a copy unchanged.
     */
    fun fuse(
        frames: List<IntArray>,
        w: Int,
        h: Int,
        levels: Int = DEFAULT_LEVELS,
        maxDim: Int = DEFAULT_MAX_DIM,
    ): IntArray {
        require(frames.isNotEmpty()) { "need at least one frame" }
        frames.forEach { require(it.size == w * h) { "frame size mismatch" } }
        if (frames.size == 1) return frames[0].copyOf()

        val (dw, dh) = workingSize(w, h, maxDim)
        val small = frames.map { if (dw == w && dh == h) it else boxDown(it, w, h, dw, dh) }
        val n = small.size

        // --- 1. weight maps (contrast × saturation × well-exposedness) ---
        val weights = small.map { px -> weightMap(px, dw, dh) }
        normalizeWeights(weights, dw * dh)
        // Mild smoothing of the normalized weight maps: softens abrupt
        // frame-selection boundaries (e.g. hard clip edges) before the
        // multi-resolution blend, reducing visible banding.
        val smoothWeights = weights.map { blur(blur(it, dw, dh), dw, dh) }
        normalizeWeights(smoothWeights, dw * dh)

        // --- 2. pyramids ---
        val lv = levels.coerceAtLeast(1)
        val weightPyr = smoothWeights.map { gaussianPyramid(it, dw, dh, lv) }
        val lapPyr = small.map { laplacianPyramid(argbToF3(it, dw, dh), dw, dh, lv) }

        // --- 3. blend per level, per channel ---
        val blended = Array(lv) { l ->
            val (lw, lh) = pyrSize(dw, dh, l)
            Array(3) { FloatArray(lw * lh) }
        }
        for (l in 0 until lv) {
            val wp = weightPyr
            for (k in 0 until n) {
                val wk = wp[k][l]
                val lk = lapPyr[k][l]
                for (c in 0..2) {
                    val acc = blended[l][c]
                    val src = lk[c]
                    for (i in src.indices) acc[i] += wk[i] * src[i]
                }
            }
        }

        // --- 4. collapse ---
        val out3 = collapse(blended, dw, dh, lv)
        return f3ToArgb(out3, dw, dh)
    }

    // ------------------------------------------------------------------
    // Weight maps
    // ------------------------------------------------------------------

    private fun weightMap(px: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val sigma2 = 2f * 0.2f * 0.2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                // contrast: |Laplacian| of luma, reflect border
                val luma = { xx: Int, yy: Int ->
                    val cc = px[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
                    val rr = ((cc shr 16) and 0xFF) / 255f
                    val gg = ((cc shr 8) and 0xFF) / 255f
                    val bb = (cc and 0xFF) / 255f
                    0.299f * rr + 0.587f * gg + 0.114f * bb
                }
                val l0 = luma(x, y)
                val lap = kotlin.math.abs(
                    luma(x - 1, y) + luma(x + 1, y) + luma(x, y - 1) + luma(x, y + 1) - 4f * l0,
                )
                val contrast = lap + 1e-6f
                // saturation: std-dev of channels
                val mean = (r + g + b) / 3f
                val sat = sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f) + 1e-6f
                // well-exposedness: gaussian around 0.5 per channel
                fun well(v: Float): Float {
                    val d = v - 0.5f
                    return exp(-(d * d) / sigma2)
                }
                val expo = well(r) * well(g) * well(b) + 1e-6f
                // Clipped pixels carry no usable information in this frame;
                // penalize the total weight (not just contrast) so weight
                // shifts to the unclipped frames before the clip edge.
                out[i] = contrast * sat * expo * clipSoft(r, g, b)
            }
        }
        return out
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Soft 1→0 attenuation for clipped channels (tames contrast spikes at
     *  hard clip edges, where one frame goes from "best" to "useless"). */
    private fun clipSoft(r: Float, g: Float, b: Float): Float {
        fun clipV(v: Float): Float =
            max(smoothstep(0.90f, 1.0f, v), smoothstep(0.10f, 0.0f, v))
        val c = max(clipV(r), max(clipV(g), clipV(b)))
        return 1f - 0.8f * c
    }

    private fun normalizeWeights(weights: List<FloatArray>, size: Int) {
        for (i in 0 until size) {
            var sum = 0f
            for (wgt in weights) sum += wgt[i]
            // All weight terms are strictly positive by construction, so a
            // zero sum is impossible short of float underflow; the fallback
            // only guards that theoretical case. (A larger epsilon here
            // would wrongly "uniform" legitimate tiny weights in flat
            // regions, where each term sits near 1e-6^3.)
            val inv = if (sum > 0f) 1f / sum else 1f / weights.size
            for (wgt in weights) wgt[i] *= inv
        }
    }

    // ------------------------------------------------------------------
    // Pyramids (separable 5-tap binomial, reflect borders)
    // ------------------------------------------------------------------

    private fun pyrSize(w: Int, h: Int, level: Int): Pair<Int, Int> {
        var pw = w; var ph = h
        repeat(level) { pw = max(1, (pw + 1) / 2); ph = max(1, (ph + 1) / 2) }
        return pw to ph
    }

    private fun blurH(src: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (t in -2..2) {
                    val xx = (x + t).coerceIn(0, w - 1)
                    acc += k[t + 2] * src[y * w + xx]
                }
                out[y * w + x] = acc / 16f
            }
        }
        return out
    }

    private fun blurV(src: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (t in -2..2) {
                    val yy = (y + t).coerceIn(0, h - 1)
                    acc += k[t + 2] * src[yy * w + x]
                }
                out[y * w + x] = acc / 16f
            }
        }
        return out
    }

    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray = blurV(blurH(src, w, h), w, h)

    private fun reduce(src: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val b = blur(src, w, h)
        val nw = max(1, (w + 1) / 2); val nh = max(1, (h + 1) / 2)
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) out[y * nw + x] = b[(y * 2).coerceAtMost(h - 1) * w + (x * 2).coerceAtMost(w - 1)]
        return Triple(out, nw, nh)
    }

    private fun expand(src: FloatArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        // zero-insert then blur ×4 (standard expand), crop to target size
        val uw = w * 2; val uh = h * 2
        val up = FloatArray(uw * uh)
        for (y in 0 until h) for (x in 0 until w) up[(y * 2) * uw + x * 2] = 4f * src[y * w + x]
        val b = blur(up, uw, uh)
        val out = FloatArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) out[y * tw + x] = b[y * uw + x]
        return out
    }

    private fun gaussianPyramid(src: FloatArray, w: Int, h: Int, levels: Int): List<FloatArray> {
        val pyr = ArrayList<FloatArray>(levels)
        var cur = src; var cw = w; var ch = h
        repeat(levels) {
            pyr.add(cur)
            if (it < levels - 1) {
                val (nxt, nw, nh) = reduce(cur, cw, ch)
                cur = nxt; cw = nw; ch = nh
            }
        }
        return pyr
    }

    /** Laplacian pyramid of an RGB float image: List per level of 3 channel arrays. */
    private fun laplacianPyramid(src: Array<FloatArray>, w: Int, h: Int, levels: Int): List<Array<FloatArray>> {
        val pyr = ArrayList<Array<FloatArray>>(levels)
        var cur = src; var cw = w; var ch = h
        for (l in 0 until levels) {
            if (l == levels - 1) {
                pyr.add(cur)
            } else {
                val lap = Array(3) { FloatArray(cw * ch) }
                for (c in 0..2) {
                    val (nxt, nw, nh) = reduce(cur[c], cw, ch)
                    val exp = expand(nxt, nw, nh, cw, ch)
                    val s = cur[c]
                    for (i in s.indices) lap[c][i] = s[i] - exp[i]
                }
                pyr.add(lap)
            }
            if (l < levels - 1) {
                val nxt = Array(3) { FloatArray(0) }
                var nw = cw; var nh = ch
                for (c in 0..2) {
                    val (r, rw, rh) = reduce(cur[c], cw, ch)
                    nxt[c] = r; nw = rw; nh = rh
                }
                cur = nxt; cw = nw; ch = nh
            }
        }
        return pyr
    }

    private fun collapse(blended: Array<Array<FloatArray>>, w: Int, h: Int, levels: Int): Array<FloatArray> {
        var cur = blended[levels - 1]
        var (cw, ch) = pyrSize(w, h, levels - 1)
        for (l in levels - 2 downTo 0) {
            val (tw, th) = pyrSize(w, h, l)
            val nxt = Array(3) { FloatArray(tw * th) }
            for (c in 0..2) {
                val exp = expand(cur[c], cw, ch, tw, th)
                val lap = blended[l][c]
                for (i in exp.indices) nxt[c][i] = exp[i] + lap[i]
            }
            cur = nxt; cw = tw; ch = th
        }
        return cur
    }

    // ------------------------------------------------------------------
    // Conversion helpers
    // ------------------------------------------------------------------

    private fun argbToF3(px: IntArray, w: Int, h: Int): Array<FloatArray> {
        val out = Array(3) { FloatArray(w * h) }
        for (i in px.indices) {
            val c = px[i]
            out[0][i] = ((c shr 16) and 0xFF) / 255f
            out[1][i] = ((c shr 8) and 0xFF) / 255f
            out[2][i] = (c and 0xFF) / 255f
        }
        return out
    }

    private fun f3ToArgb(f3: Array<FloatArray>, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = (f3[0][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (f3[1][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (f3[2][i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun boxDown(src: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val y0 = (y * h / dh); val y1 = max(y0 + 1, ((y + 1) * h / dh))
            for (x in 0 until dw) {
                val x0 = (x * w / dw); val x1 = max(x0 + 1, ((x + 1) * w / dw))
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in y0 until min(y1, h)) for (xx in x0 until min(x1, w)) {
                    val c = src[yy * w + xx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    n++
                }
                val nn = max(1, n)
                out[y * dw + x] = (0xFF shl 24) or
                    ((r / nn).toInt().coerceIn(0, 255) shl 16) or
                    ((g / nn).toInt().coerceIn(0, 255) shl 8) or
                    (b / nn).toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
