package com.lumora.photoeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Panorama stitching (pro-spec Phase 4, Lightroom "Photomerge" parity):
 * stitches 2+ library photos taken left-to-right into one wide panorama.
 * NOT AI and NOT a neural model: adjacent frames are aligned with a
 * coarse-to-fine translation estimate (zero-mean sum-of-absolute-differences
 * template match on a downscaled luminance version — robust to small
 * exposure differences between frames), then composited with a linear
 * cross-fade across each overlap, and finally auto-cropped to the largest
 * fully-covered rectangle so no black borders remain.
 *
 * Honest scope: translation-only alignment (no rotation/perspective warp,
 * no lens-distortion correction, no exposure leveling beyond the matcher's
 * zero-mean step, no deghosting). Frames should overlap by roughly 20-50%
 * and be shot left-to-right in the order selected. The composite is built
 * at a capped working resolution (see [DEFAULT_MAX_DIM]) and the canvas
 * width is capped ([MAX_CANVAS_WIDTH]) for memory safety.
 *
 * Pure Kotlin, zero Android imports — harness-testable on the JVM.
 */
object PanoramaStitch {

    /** Default cap on the longer side of each frame before stitching. */
    const val DEFAULT_MAX_DIM = 1600

    /** Long-side cap of the downscaled copy used for shift estimation. */
    const val MATCH_LONG_SIDE = 320

    /** Hard cap on the output canvas width (memory safety). */
    const val MAX_CANVAS_WIDTH = 6000

    /** Working resolution for a source of [w]x[h] under cap [maxDim]. */
    fun workingSize(w: Int, h: Int, maxDim: Int = DEFAULT_MAX_DIM): Pair<Int, Int> {
        val longer = max(w, h)
        if (longer <= maxDim || maxDim <= 0) return w to h
        val s = maxDim.toFloat() / longer.toFloat()
        return max(1, (w * s).toInt()) to max(1, (h * s).toInt())
    }

    /**
     * Stitch result carrying the composited pixels AND their dimensions
     * (the panorama is auto-cropped to the largest fully-covered rectangle,
     * so the output size differs from the raw canvas — callers need it to
     * size the output Bitmap).
     */
    data class StitchResult(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Stitches [frames] (same-size ARGB pixel arrays, left-to-right in
     * selection order) into one panorama. A single frame returns a copy
     * unchanged. Same contract as [stitch] but also reports the
     * post-auto-crop dimensions.
     */
    fun stitchFull(
        frames: List<IntArray>,
        w: Int,
        h: Int,
        maxDim: Int = DEFAULT_MAX_DIM,
    ): StitchResult {
        require(frames.isNotEmpty()) { "need at least one frame" }
        frames.forEach { require(it.size == w * h) { "frame size mismatch" } }
        if (frames.size == 1) return StitchResult(frames[0].copyOf(), w, h)

        val (dw, dh) = workingSize(w, h, maxDim)
        val small = frames.map { if (dw == w && dh == h) it else boxDown(it, w, h, dw, dh) }

        // --- 1. pairwise shift estimates at match resolution ---
        val (mw, mh) = matchSize(dw, dh)
        val match = small.map { lumDown(it, dw, dh, mw, mh) }
        val shifts = mutableListOf<Pair<Int, Int>>() // (dx, dy) in work-res px
        val sx = dw.toFloat() / mw
        val sy = dh.toFloat() / mh
        for (k in 0 until match.size - 1) {
            val (mdx, mdy) = estimateShift(match[k], match[k + 1], mw, mh)
            shifts.add((mdx * sx).roundToInt() to (mdy * sy).roundToInt())
        }

        // --- 2. place frames, accumulate weighted RGB + coverage ---
        val xOff = IntArray(small.size); val yOff = IntArray(small.size)
        var minY = 0; var maxY = dh; var canvasW = dw
        for (k in 1 until small.size) {
            val (pdx, pdy) = shifts[k - 1]
            xOff[k] = (xOff[k - 1] + pdx).coerceAtLeast(0)
            yOff[k] = yOff[k - 1] + pdy
            canvasW = max(canvasW, xOff[k] + dw)
            minY = min(minY, yOff[k]); maxY = max(maxY, yOff[k] + dh)
        }
        canvasW = min(canvasW, MAX_CANVAS_WIDTH)
        val canvasH = (maxY - minY).coerceAtLeast(1)
        val yBase = -minY
        val accR = FloatArray(canvasW * canvasH)
        val accG = FloatArray(canvasW * canvasH)
        val accB = FloatArray(canvasW * canvasH)
        val accW = FloatArray(canvasW * canvasH)
        val cover = IntArray(canvasW * canvasH)

        for (k in small.indices) {
            val px = small[k]
            val ox = xOff[k]; val oy = yOff[k] + yBase
            // Overlap with the previous frame (for the cross-fade ramp):
            // the previous frame's right edge, in this frame's coordinates.
            val prevRight = if (k == 0) Int.MIN_VALUE else xOff[k - 1] + dw
            for (y in 0 until dh) {
                val cy = oy + y
                if (cy !in 0 until canvasH) continue
                val rowBase = cy * canvasW
                for (x in 0 until dw) {
                    val cx = ox + x
                    if (cx !in 0 until canvasW) continue
                    val p = px[y * dw + x]
                    var wgt = 1f
                    if (k > 0 && prevRight > ox && cx < prevRight) {
                        // Inside the overlap band with the previous frame:
                        // this frame ramps 0 -> 1 left to right.
                        val band = (prevRight - ox).coerceAtLeast(1)
                        wgt = ((cx - ox + 1).toFloat() / band).coerceIn(0f, 1f)
                        // Feather the very edge so tiny misalignments don't
                        // leave a hard line even at weight 0.
                        if (wgt <= 0f) continue
                    }
                    val i = rowBase + cx
                    accR[i] += ((p shr 16) and 0xFF) * wgt
                    accG[i] += ((p shr 8) and 0xFF) * wgt
                    accB[i] += (p and 0xFF) * wgt
                    accW[i] += wgt
                    if (wgt > 0f) cover[i]++
                }
            }
        }

        // --- 3. normalize to ARGB (round, not truncate: float error on a
        // uniform field must not turn 128 into 127) ---
        val canvas = IntArray(canvasW * canvasH) { i ->
            val inv = if (accW[i] > 1e-6f) 1f / accW[i] else 0f
            val r = (accR[i] * inv).roundToInt().coerceIn(0, 255)
            val g = (accG[i] * inv).roundToInt().coerceIn(0, 255)
            val b = (accB[i] * inv).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // --- 4. auto-crop to the largest fully-covered rectangle ---
        val bounds = cropBounds(cover, canvasW, canvasH)
        return if (bounds == null) StitchResult(canvas, canvasW, canvasH)
        else {
            val (x0, y0, x1, y1) = bounds
            val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
            StitchResult(
                IntArray(cw * ch) { i ->
                    canvas[(y0 + i / cw) * canvasW + (x0 + i % cw)]
                }, cw, ch)
        }
    }

    /**
     * Original [stitch] entry point: identical output to [stitchFull],
     * pixels only (dimensions drop out — kept for API compatibility).
     */
    fun stitch(
        frames: List<IntArray>,
        w: Int,
        h: Int,
        maxDim: Int = DEFAULT_MAX_DIM,
    ): IntArray {
        val r = stitchFull(frames, w, h, maxDim)
        return r.pixels
    }

    /**
     * Estimates the translation of frame B relative to frame A at match
     * resolution: returns (dx, dy) where B's left edge sits dx px right of
     * A's left edge and B sits dy px below A. Zero-mean SAD template match
     * of A's right-edge strip against B, coarse-to-fine; falls back to a
     * 25%-overlap guess when the scene is too flat to discriminate.
     */
    fun estimateShift(a: FloatArray, b: FloatArray, w: Int, h: Int): Pair<Int, Int> {
        val stripW = max(8, (w * 0.20f).roundToInt())
        val maxDx = max(1, (w * 0.60f).roundToInt())
        val maxDy = max(2, (h * 0.08f).roundToInt())
        // Keep the B patch inside the frame: bx0 = w - stripW - dx >= 0.
        val dxLim = min(maxDx, w - stripW).coerceAtLeast(0)
        // Strip: right edge of A.
        val sx0 = w - stripW
        // Coarse pass: dx step 2, dy step 1 (dy range is small), 2px sampling
        // stride. The dy sweep MUST include 0: starting at -maxDy with an
        // even step skips 0 when maxDy is odd, and the true shift can then
        // never win the coarse pass.
        var bestDx = dxLim / 2; var bestDy = 0; var best = Float.MAX_VALUE
        var sum = 0f; var cnt = 0
        var dx = 0
        while (dx <= dxLim) {
            var dy = -maxDy
            while (dy <= maxDy) {
                val s = scoreShift(a, b, w, h, sx0, stripW, dx, dy, 2)
                sum += s; cnt++
                if (s < best) { best = s; bestDx = dx; bestDy = dy }
                dy += 1
            }
            dx += 2
        }
        // Refine pass: ±2 px around the coarse winner at full resolution.
        // Freeze the coarse winner: the inner loop's range is re-evaluated
        // on every outer iteration, so using the mutable bestDx/bestDy in
        // the bounds lets the window chase the moving best and drift away
        // from the coarse peak (e.g. (40,0) -> (40,5) on a 3-frame pano).
        val cDx = bestDx; val cDy = bestDy
        best = Float.MAX_VALUE
        for (rdx in max(0, cDx - 2)..min(dxLim, cDx + 2)) {
            for (rdy in max(-maxDy, cDy - 2)..min(maxDy, cDy + 2)) {
                val s = scoreShift(a, b, w, h, sx0, stripW, rdx, rdy, 1)
                sum += s; cnt++
                if (s < best) { best = s; bestDx = rdx; bestDy = rdy }
            }
        }
        val avg = if (cnt > 0) sum / cnt else best
        // Confidence: a discriminative match beats the average clearly.
        // Flat/textureless strips give best ~= avg -> fall back.
        return if (avg > 1e-6f && best / avg < 0.92f) bestDx to bestDy
        else (w * 0.75f).roundToInt() to 0
    }

    /**
     * Zero-mean SAD between A's strip (right edge, [sx0, sx0+stripW)) and B's
     * patch for a relative placement of (dx, dy): B pixel (bx, by) lands on
     * canvas (bx + dx, by + dy), so A's canvas pixel (x, y) corresponds to B
     * pixel (x - dx, y - dy). Samples whose B row falls outside the frame
     * are SKIPPED (not clamped — clamping compares against wrong content and
     * inflates the true shift's score). Sampling [stride] px.
     */
    private fun scoreShift(
        a: FloatArray, b: FloatArray, w: Int, h: Int,
        sx0: Int, stripW: Int, dx: Int, dy: Int, stride: Int,
    ): Float {
        val bx0 = w - stripW - dx
        var meanA = 0f; var meanB = 0f; var n = 0
        var y = 1
        while (y < h - 1) {
            val by = y - dy
            if (by in 0 until h) {
                var x = 0
                while (x < stripW) {
                    meanA += a[y * w + sx0 + x]
                    meanB += b[by * w + bx0 + x]
                    n++; x += stride
                }
            }
            y += stride
        }
        if (n == 0) return Float.MAX_VALUE
        meanA /= n; meanB /= n
        var sad = 0f
        y = 1
        while (y < h - 1) {
            val by = y - dy
            if (by in 0 until h) {
                var x = 0
                while (x < stripW) {
                    val da = a[y * w + sx0 + x] - meanA
                    val db = b[by * w + bx0 + x] - meanB
                    sad += abs(da - db)
                    x += stride
                }
            }
            y += stride
        }
        return sad / n
    }

    /** Match-resolution size for a [dw]x[dh] working frame. */
    fun matchSize(dw: Int, dh: Int): Pair<Int, Int> {
        val longer = max(dw, dh)
        if (longer <= MATCH_LONG_SIDE) return dw to dh
        val s = MATCH_LONG_SIDE.toFloat() / longer
        return max(8, (dw * s).toInt()) to max(8, (dh * s).toInt())
    }

    /** Luminance downscale of ARGB [px] to [dw2]x[dh2]. */
    fun lumDown(px: IntArray, w: Int, h: Int, dw2: Int, dh2: Int): FloatArray {
        val out = FloatArray(dw2 * dh2)
        val sx = w.toFloat() / dw2; val sy = h.toFloat() / dh2
        for (y in 0 until dh2) {
            val sy0 = min(h - 1, (y * sy).toInt())
            for (x in 0 until dw2) {
                val p = px[sy0 * w + min(w - 1, (x * sx).toInt())]
                out[y * dw2 + x] =
                    (0.299f * ((p shr 16) and 0xFF) +
                        0.587f * ((p shr 8) and 0xFF) +
                        0.114f * (p and 0xFF)) / 255f
            }
        }
        return out
    }

    private fun boxDown(px: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val sx = w.toFloat() / dw; val sy = h.toFloat() / dh
        for (y in 0 until dh) {
            val y0 = (y * sy).toInt()
            val y1 = min(h, ((y + 1) * sy).toInt()).coerceAtLeast(y0 + 1)
            for (x in 0 until dw) {
                val x0 = (x * sx).toInt()
                val x1 = min(w, ((x + 1) * sx).toInt()).coerceAtLeast(x0 + 1)
                var r = 0L; var g = 0L; var b = 0L; var c = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val p = px[yy * w + xx]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF
                    b += p and 0xFF; c++
                }
                out[y * dw + x] = (0xFF shl 24) or
                    ((r / c).toInt() shl 16) or ((g / c).toInt() shl 8) or (b / c).toInt()
            }
        }
        return out
    }

    /**
     * Crop bounds of the largest fully-covered rectangle as
     * (x0, y0, x1, y1) inclusive, or null when no fully-covered row/column
     * range exists (degenerate coverage — caller keeps the full canvas).
     */
    private fun cropBounds(cover: IntArray, w: Int, h: Int): IntArray? {
        var y0 = 0
        while (y0 < h) {
            var full = true
            for (x in 0 until w) if (cover[y0 * w + x] == 0) { full = false; break }
            if (full) break
            y0++
        }
        var y1 = h - 1
        while (y1 >= y0) {
            var full = true
            for (x in 0 until w) if (cover[y1 * w + x] == 0) { full = false; break }
            if (full) break
            y1--
        }
        if (y1 < y0) return null
        var x0 = 0
        while (x0 < w) {
            var full = true
            for (y in y0..y1) if (cover[y * w + x0] == 0) { full = false; break }
            if (full) break
            x0++
        }
        var x1 = w - 1
        while (x1 >= x0) {
            var full = true
            for (y in y0..y1) if (cover[y * w + x1] == 0) { full = false; break }
            if (full) break
            x1--
        }
        if (x1 < x0) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /**
     * Crops to the largest fully-covered rectangle: rows fully covered
     * across the whole canvas width, then columns fully covered across the
     * kept rows. Returns the original array when nothing can be cropped.
     */
    fun autoCrop(
        canvas: IntArray, cover: IntArray, w: Int, h: Int,
    ): IntArray {
        val bounds = cropBounds(cover, w, h) ?: return canvas
        val (x0, y0, x1, y1) = bounds
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return canvas
        val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
        return IntArray(cw * ch) { i ->
            canvas[(y0 + i / cw) * w + (x0 + i % cw)]
        }
    }
}
