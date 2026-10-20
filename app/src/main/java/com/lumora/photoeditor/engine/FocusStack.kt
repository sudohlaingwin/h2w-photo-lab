package com.lumora.photoeditor.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Focus stacking (pro-spec Phase 4): merges N frames shot at different focus
 * distances into one all-in-focus composite. NOT AI and NOT a neural model:
 * for each frame it measures per-pixel sharpness with the modified Laplacian
 * (second-derivative energy on luminance — the classic, peer-reviewed focus
 * measure), smooths those measure maps, converts them to per-pixel weights via
 * a softmax across frames, and blends with multi-resolution
 * (Laplacian/Gaussian) pyramids so the focus boundary is seamless and
 * halo-free — the same pyramid trick HDR merge uses.
 *
 * Honest scope: frames must be aligned (tripod or static scene); there is no
 * alignment pass here. The composite runs at a capped working resolution
 * (default max side 1280 px) via box downsampling, so a full-res stack is a
 * preview-size composite, not a 12MP-per-frame pyramid (memory safety).
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
object FocusStack {

    /** Default cap on the longer side of the working resolution. */
    const val DEFAULT_MAX_DIM = 1280

    /** Default number of pyramid levels. */
    const val DEFAULT_LEVELS = 4

    /** Softmax temperature over the normalized focus measures (0..1). */
    private const val SOFTMAX_TAU = 0.15f

    /** Box-blur radius (px) applied to each focus-measure map before softmax. */
    private const val MEASURE_BLUR_RADIUS = 4

    /** Working resolution for a source of [w]x[h] under cap [maxDim]. */
    fun workingSize(w: Int, h: Int, maxDim: Int = DEFAULT_MAX_DIM): Pair<Int, Int> {
        val longer = max(w, h)
        if (longer <= maxDim || maxDim <= 0) return w to h
        val s = maxDim.toFloat() / longer.toFloat()
        return max(1, (w * s).toInt()) to max(1, (h * s).toInt())
    }

    /**
     * Stacks [frames] (same-size ARGB pixel arrays) into one all-in-focus
     * composite at the working resolution — see [workingSize]. A single
     * frame returns a copy unchanged.
     */
    fun stack(
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
        val lv = levels.coerceAtLeast(1)

        // --- 1. focus measures -> smoothed -> softmax weights ---
        val weights = small.map { px ->
            boxBlur(focusMeasure(px, dw, dh), dw, dh, MEASURE_BLUR_RADIUS)
        }
        softmaxAcross(weights, dw * dh)

        // --- 2. pyramids ---
        val weightPyr = weights.map { gaussianPyramid(it, dw, dh, lv) }
        val lapPyr = small.map { laplacianPyramid(argbToF3(it, dw, dh), dw, dh, lv) }

        // --- 3. blend per level, per channel ---
        val blended = Array(lv) { l ->
            val (lw, lh) = pyrSize(dw, dh, l)
            Array(3) { FloatArray(lw * lh) }
        }
        for (l in 0 until lv) {
            for (c in 0..2) {
                val out = blended[l][c]
                for (k in 0 until n) {
                    val wp = weightPyr[k][l]
                    val lp = lapPyr[k][l][c]
                    for (i in out.indices) out[i] += wp[i] * lp[i]
                }
            }
        }

        // --- 4. reconstruct ---
        val acc = Array(3) { blended[lv - 1][it].copyOf() }
        val sizes = Array(lv) { pyrSize(dw, dh, it) }
        for (l in lv - 2 downTo 0) {
            val (lw, lh) = sizes[l]
            val (cw, ch) = sizes[l + 1]
            for (c in 0..2) {
                val up = expand(acc[c], cw, ch, lw, lh)
                val lap = blended[l][c]
                for (i in up.indices) up[i] += lap[i]
                acc[c] = up
            }
        }
        val r = acc[0]; val g = acc[1]; val b = acc[2]
        return IntArray(dw * dh) { i ->
            (0xFF shl 24) or
                (r[i].coerceIn(0f, 255f).roundToInt() shl 16) or
                (g[i].coerceIn(0f, 255f).roundToInt() shl 8) or
                b[i].coerceIn(0f, 255f).roundToInt()
        }
    }

    /**
     * Modified Laplacian focus measure: |2L - L(x-1) - L(x+1)| +
     * |2L - L(y-1) - L(y+1)| on luminance. Sharp detail (edges, texture)
     * gives large second derivatives; defocused blur drives them toward
     * zero. Edges are handled by replication.
     */
    fun focusMeasure(px: IntArray, w: Int, h: Int): FloatArray {
        val lum = FloatArray(w * h) { i ->
            val p = px[i]
            (0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)) / 255f
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = lum[i]
                val l = lum[y * w + max(0, x - 1)]
                val r = lum[y * w + min(w - 1, x + 1)]
                val u = lum[max(0, y - 1) * w + x]
                val d = lum[min(h - 1, y + 1) * w + x]
                out[i] = kotlin.math.abs(2f * c - l - r) +
                    kotlin.math.abs(2f * c - u - d)
            }
        }
        return out
    }

    /** Softmax across the frame list, in place, per pixel. */
    private fun softmaxAcross(maps: List<FloatArray>, size: Int) {
        val n = maps.size
        for (i in 0 until size) {
            var mx = 0f
            for (k in 0 until n) mx = max(mx, maps[k][i])
            var sum = 0f
            for (k in 0 until n) {
                val e = exp(((maps[k][i] - mx) / SOFTMAX_TAU).toDouble()).toFloat()
                maps[k][i] = e
                sum += e
            }
            val inv = 1f / max(sum, 1e-12f)
            for (k in 0 until n) maps[k][i] *= inv
        }
    }

    private fun argbToF3(px: IntArray, w: Int, h: Int): Array<FloatArray> {
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            r[i] = ((p shr 16) and 0xFF).toFloat()
            g[i] = ((p shr 8) and 0xFF).toFloat()
            b[i] = (p and 0xFF).toFloat()
        }
        return arrayOf(r, g, b)
    }

    private fun boxDown(px: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val sx = w.toFloat() / dw; val sy = h.toFloat() / dh
        for (y in 0 until dh) {
            val y0 = (y * sy).toInt(); val y1 = min(h, ((y + 1) * sy).toInt()).coerceAtLeast(y0 + 1)
            for (x in 0 until dw) {
                val x0 = (x * sx).toInt(); val x1 = min(w, ((x + 1) * sx).toInt()).coerceAtLeast(x0 + 1)
                var r = 0L; var g = 0L; var b = 0L; var c = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val p = px[yy * w + xx]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; c++
                }
                out[y * dw + x] = (0xFF shl 24) or
                    ((r / c).toInt() shl 16) or ((g / c).toInt() shl 8) or (b / c).toInt()
            }
        }
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f; var c = 0
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    s += src[y * w + xx]; c++
                }
                tmp[y * w + x] = s / c
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f; var c = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    s += tmp[yy * w + x]; c++
                }
                out[y * w + x] = s / c
            }
        }
        return out
    }

    private fun pyrSize(w: Int, h: Int, l: Int): Pair<Int, Int> =
        max(1, w shr l) to max(1, h shr l)

    private fun gaussianPyramid(src: FloatArray, w: Int, h: Int, levels: Int): List<FloatArray> {
        val pyr = mutableListOf<FloatArray>()
        var cur = src; var cw = w; var ch = h
        repeat(levels) {
            pyr.add(cur)
            val (nw, nh) = max(1, cw / 2) to max(1, ch / 2)
            if (nw == cw && nh == ch) {
                // Degenerate (already 1x1): repeat the coarsest level so the
                // caller always gets exactly `levels` entries.
                repeat(levels - pyr.size) { pyr.add(cur) }
                return pyr
            }
            val down = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var s = 0f; var c = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val sx = min(cw - 1, x * 2 + dx); val sy = min(ch - 1, y * 2 + dy)
                    s += cur[sy * cw + sx]; c++
                }
                down[y * nw + x] = s / c
            }
            cur = down; cw = nw; ch = nh
        }
        return pyr
    }

    private fun expand(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = (y.toFloat() / 2f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(sh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until dw) {
                val fx = (x.toFloat() / 2f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(sw - 1, x0 + 1); val tx = fx - x0
                val a = src[y0 * sw + x0]; val c = src[y0 * sw + x1]
                val e = src[y1 * sw + x0]; val g = src[y1 * sw + x1]
                out[y * dw + x] = (a * (1 - tx) + c * tx) * (1 - ty) +
                    (e * (1 - tx) + g * tx) * ty
            }
        }
        return out
    }

    private fun laplacianPyramid(f3: Array<FloatArray>, w: Int, h: Int, levels: Int): List<Array<FloatArray>> {
        val g = f3.map { gaussianPyramid(it, w, h, levels) }
        return (0 until levels).map { l ->
            val (lw, lh) = pyrSize(w, h, l)
            Array(3) { c ->
                if (l == levels - 1) g[c][l].copyOf()
                else {
                    val (nw, nh) = pyrSize(w, h, l + 1)
                    val up = expand(g[c][l + 1], nw, nh, lw, lh)
                    FloatArray(lw * lh) { i -> g[c][l][i] - up[i] }
                }
            }
        }
    }
}
