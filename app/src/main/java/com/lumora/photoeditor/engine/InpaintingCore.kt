package com.lumora.photoeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pure-Kotlin (Android-free) PatchMatch inpainting core used by the Remove
 * tool. Kept free of android.* imports so it can be exercised with a plain
 * kotlinc harness (see AGENTS.md).
 *
 * Algorithm: for each masked pixel (filled boundary-inward), find the
 * best-matching source patch from the known region:
 *  - pass 1: biased random search (24 candidates, nearer areas preferred)
 *  - passes 2-3: propagation — reuse already-filled neighbours' best matches
 *    (shifted by the pixel offset) plus small jitter, like classic
 *    PatchMatch. This is what gives coherent texture instead of the
 *    repetition/random-speckle look of pure random search.
 *
 * Quality notes: matching compares only target pixels that are already
 * known (masked pixels are excluded), and source patches must lie fully in
 * the known region, so filled content always comes from real pixels.
 * "Already known" is tracked with a dynamic mask that clears as the
 * boundary-inward fill front advances: without it, pixels deeper than
 * PATCH_RADIUS from the known region would see their whole comparison
 * patch as unknown, match nothing, and keep their original color —
 * the removed object would stay visible in the middle of large removals.
 */
object InpaintingCore {

    /** Working resolution cap; the Bitmap wrapper downscales to this. */
    const val WORK_MAX = 512

    /** Max fraction of the image the tool will attempt to fill. */
    const val MAX_FILL_FRACTION = 0.4f

    /** Mask dilation (px at working res) to avoid edge halos. */
    private const val DILATE_RADIUS = 2

    private const val PATCH_RADIUS = 4

    /** Mask pixel value threshold (alpha or red channel) marking "remove". */
    private const val MASK_THRESHOLD = 100

    /**
     * Builds the boolean fill mask from raw mask pixels.
     * @param maskPx ARGB pixels of the (working-res) mask
     */
    fun buildFillMask(maskPx: IntArray): BooleanArray {
        val toFill = BooleanArray(maskPx.size)
        for (i in maskPx.indices) {
            val a = (maskPx[i] ushr 24) and 0xFF
            val r = (maskPx[i] shr 16) and 0xFF
            if (max(a, r) > MASK_THRESHOLD) toFill[i] = true
        }
        return toFill
    }

    /**
     * Bounding box of the fill mask, expanded by [marginPx] on every side and
     * clamped to the image. Returns intArrayOf(x0, y0, x1, y1) with x1/y1
     * exclusive, or null when the mask is empty.
     *
     * Pure-Kotlin so the crop path in [Inpainting] can be unit-tested with a
     * plain kotlinc harness.
     */
    fun computeFillBounds(
        maskPx: IntArray, w: Int, h: Int, marginPx: Int,
    ): IntArray? {
        require(maskPx.size == w * h) { "size mismatch" }
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = maskPx[y * w + x]
                val a = (c ushr 24) and 0xFF
                val r = (c shr 16) and 0xFF
                if (max(a, r) > MASK_THRESHOLD) {
                    if (x < x0) x0 = x
                    if (y < y0) y0 = y
                    if (x + 1 > x1) x1 = x + 1
                    if (y + 1 > y1) y1 = y + 1
                }
            }
        }
        if (x1 < 0) return null
        return intArrayOf(
            (x0 - marginPx).coerceIn(0, w),
            (y0 - marginPx).coerceIn(0, h),
            (x1 + marginPx).coerceIn(0, w),
            (y1 + marginPx).coerceIn(0, h),
        )
    }

    /**
     * Separable box blur on a float alpha field, in place, with edge
     * clamping. A box kernel is separable, and clamping each axis in turn
     * equals clamping the 2D window, so this is exactly the old full
     * (2r+1)^2-window blur — at O(2*(2r+1)) cost per pixel instead of
     * O((2r+1)^2).
     *
     * Extracted so the Remove tool's feather blends (Inpainting.blendMasked /
     * blendCropped) share one implementation. Pure-Kotlin so it stays
     * harness-testable with a plain kotlinc harness.
     */
    fun boxBlurAlpha(a: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0 || w <= 0 || h <= 0) return
        require(a.size == w * h) { "size mismatch" }
        val tmp = FloatArray(w * h)
        val k = 2 * radius + 1
        // Horizontal pass: sliding window, edge-clamped.
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (dx in -radius..radius) sum += a[row + dx.coerceIn(0, w - 1)]
            tmp[row] = sum / k
            for (x in 1 until w) {
                sum += a[row + (x + radius).coerceIn(0, w - 1)] -
                    a[row + (x - radius - 1).coerceIn(0, w - 1)]
                tmp[row + x] = sum / k
            }
        }
        // Vertical pass back into [a].
        for (x in 0 until w) {
            var sum = 0f
            for (dy in -radius..radius) sum += tmp[dy.coerceIn(0, h - 1) * w + x]
            a[x] = sum / k
            for (y in 1 until h) {
                sum += tmp[(y + radius).coerceIn(0, h - 1) * w + x] -
                    tmp[(y - radius - 1).coerceIn(0, h - 1) * w + x]
                a[y * w + x] = sum / k
            }
        }
    }

    /**
     * Float-precision twin of [boxBlurRgb]: the same separable box blur, but
     * returned as interleaved RGB floats ([r, g, b] per pixel, n*3) instead
     * of packed ints.
     *
     * Why it exists: the Clone tool's tone field (smooth(dest) − smooth(src))
     * is the whole visual term of a stamp, and packing it to integers first
     * quantized that field in ±1 steps — faint contour bands across large
     * smooth gradients (skies, walls, skin) where the offset ramps slowly.
     * Keeping the field in float removes the quantization: the offset stays
     * smooth at sub-integer precision, so the stamp melts in with no steps.
     *
     * Pure-Kotlin so it stays harness-testable with a plain kotlinc harness.
     */
    fun boxBlurRgbFloat(src: IntArray, w: Int, h: Int, radius: Int): FloatArray {
        if (w <= 0 || h <= 0) return FloatArray(0)
        require(src.size == w * h) { "size mismatch" }
        val (r, g, b) = blurredChannels(src, w, h, radius)
        val n = w * h
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            out[i * 3] = r[i]
            out[i * 3 + 1] = g[i]
            out[i * 3 + 2] = b[i]
        }
        return out
    }

    /**
     * Shared float-channel blur behind [boxBlurRgb] and [boxBlurRgbFloat]:
     * unpacks packed RGB to float channels and applies the separable box
     * blur (radius <= 0 = identity, the unpacked channels). [boxBlurRgb]
     * packs them back to ints (its ±1 quantization); [boxBlurRgbFloat]
     * keeps them at full precision.
     */
    private fun blurredChannels(
        src: IntArray, w: Int, h: Int, radius: Int,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val p = src[i]
            r[i] = ((p shr 16) and 0xFF).toFloat()
            g[i] = ((p shr 8) and 0xFF).toFloat()
            b[i] = (p and 0xFF).toFloat()
        }
        if (radius > 0) {
            boxBlurAlpha(r, w, h, radius)
            boxBlurAlpha(g, w, h, radius)
            boxBlurAlpha(b, w, h, radius)
        }
        return Triple(r, g, b)
    }

    /**
     * Separable box blur on packed RGB (0xRRGGBB) pixels, with edge
     * clamping. Same window math as a naive full-(2r+1)^2-window blur —
     * the box kernel is separable and clamping each axis in turn equals
     * clamping the 2D window — at O(2*(2r+1)) cost per pixel instead of
     * O((2r+1)^2).
     *
     * This is the fast path for the retouch tone references (CLONE and
     * EYE_ENHANCE): blurR grows with brush size, so the old naive version
     * cost seconds per stroke on big brushes (e.g. an 80px radius = 25,921
     * samples per pixel). The new one does 162.
     *
     * Pure-Kotlin so it stays harness-testable with a plain kotlinc harness.
     */
    fun boxBlurRgb(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        require(src.size == w * h) { "size mismatch" }
        val (r, g, b) = blurredChannels(src, w, h, radius)
        val n = w * h
        val out = IntArray(n)
        for (i in 0 until n) {
            // roundToInt (NOT toInt): truncating the blurred float channels
            // darkens every pixel by ~0.5 LSB on average — a visible dulling
            // of the tone reference on wide blurs. Rounding is unbiased.
            out[i] = (r[i].roundToInt().coerceIn(0, 255) shl 16) or
                (g[i].roundToInt().coerceIn(0, 255) shl 8) or
                b[i].roundToInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Feathered alpha blend of two same-size packed-ARGB pixel arrays —
     * the seam blend the Remove tool applies between the inpainted region
     * and the untouched photo ([Inpainting.blendMasked] and
     * [Inpainting.blendCropped] share it).
     *
     * [alpha] is per-pixel 0..1 (0 = keep [origPx], 1 = take [newPx]); it
     * is coerced defensively. Kept here (Android-free) so the exact shipped
     * blend math is harness-testable.
     */
    fun blendPixelsAlpha(
        origPx: IntArray, newPx: IntArray, alpha: FloatArray,
    ): IntArray {
        require(origPx.size == newPx.size && newPx.size == alpha.size) {
            "size mismatch"
        }
        val outPx = IntArray(origPx.size)
        for (i in origPx.indices) {
            val a = alpha[i].coerceIn(0f, 1f)
            if (a <= 0f) {
                outPx[i] = origPx[i]
            } else if (a >= 1f) {
                outPx[i] = newPx[i]
            } else {
                val o = origPx[i]; val n = newPx[i]
                // roundToInt (NOT toInt): truncation biases every feather-band
                // pixel ~0.5 LSB darker — a faint dark ring around every
                // removal. Rounding is unbiased (same fix class as the
                // RetouchEngine heal/clone pack and the wavelet round-trip).
                val r = (((o shr 16) and 0xFF) * (1 - a) + ((n shr 16) and 0xFF) * a).roundToInt()
                val g = (((o shr 8) and 0xFF) * (1 - a) + ((n shr 8) and 0xFF) * a).roundToInt()
                val b = ((o and 0xFF) * (1 - a) + (n and 0xFF) * a).roundToInt()
                outPx[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return outPx
    }

    /**
     * Inpaints the masked region.
     * @param pixels ARGB pixels of the working-res image
     * @param toFill true where the pixel must be replaced (working res)
     * @return filled pixel array, same size as [pixels]
     */
    fun inpaint(
        pixels: IntArray,
        toFill: BooleanArray,
        w: Int,
        h: Int,
        onProgress: (Float) -> Unit = {},
    ): IntArray {
        val n = w * h
        require(pixels.size == n && toFill.size == n) { "size mismatch" }

        val dilated = dilate(toFill, w, h, DILATE_RADIUS)
        val dist = distanceTransform(dilated, w, h)
        val order = dilated.indices.filter { dilated[it] }.sortedBy { dist[it] }
        if (order.isEmpty()) return pixels.copyOf()

        // Dynamic unknown-mask for the TARGET side of patch comparisons.
        // Starts as the static dilated mask and clears each pixel as the
        // boundary-inward fill front reaches it, so deep pixels compare
        // against already-filled neighbours instead of finding their whole
        // patch "unknown" (score MAX_VALUE) forever. The SOURCE gate
        // (isPatchKnown) intentionally keeps the static `dilated` mask:
        // filled content must always come from real original pixels, never
        // from other filled pixels.
        val targetUnknown = dilated.copyOf()

        val result = pixels.copyOf()
        // Nearest-neighbour field: best source patch center per filled pixel.
        val nnfX = IntArray(n) { -1 }
        val nnfY = IntArray(n) { -1 }
        val rnd = Random(42)

        val total = order.size
        var done = 0

        // Pass 1: biased random search.
        for (p in order) {
            val px = p % w; val py = p / w
            var bestX = -1; var bestY = -1
            var bestScore = Float.MAX_VALUE
            repeat(24) {
                val rx = (px + (rnd.nextFloat() - 0.5f) * w * 0.6f)
                    .toInt().coerceIn(PATCH_RADIUS, w - 1 - PATCH_RADIUS)
                val ry = (py + (rnd.nextFloat() - 0.5f) * h * 0.6f)
                    .toInt().coerceIn(PATCH_RADIUS, h - 1 - PATCH_RADIUS)
                if (isPatchKnown(rx, ry, w, h, PATCH_RADIUS, dilated)) {
                    val score = patchDistance(px, py, rx, ry, w, h, result, targetUnknown)
                    if (score < bestScore) {
                        bestScore = score; bestX = rx; bestY = ry
                    }
                }
            }
            if (bestX >= 0) {
                nnfX[p] = bestX; nnfY[p] = bestY
                result[p] = result[bestY * w + bestX]
            } else {
                // No valid source patch: keep the static `dilated` mask
                // here on purpose. With the dynamic mask this fallback
                // would smear already-processed originals across a region
                // that has no real source content at all; the pinned
                // contract is a graceful no-op copy in that case.
                result[p] = averageKnownNeighbors(p, w, h, result, dilated)
            }
            // p is now filled: later pixels may compare against it.
            targetUnknown[p] = false
            done++
            if (done % 500 == 0) onProgress(done.toFloat() / total * 0.6f)
        }

        // Passes 2-3: propagation sweeps. A pixel inherits its already-filled
        // neighbours' best matches (shifted by the pixel offset) — this is
        // what makes large fills follow surrounding texture instead of
        // scattering random patches.
        val neighbors = arrayOf(intArrayOf(-1, 0), intArrayOf(0, -1),
            intArrayOf(1, 0), intArrayOf(0, 1))
        repeat(2) { sweep ->
            val seq = if (sweep % 2 == 0) order else order.asReversed()
            var sDone = 0
            for (p in seq) {
                val px = p % w; val py = p / w
                var bestX = nnfX[p]; var bestY = nnfY[p]
                var bestScore = if (bestX >= 0)
                    patchDistance(px, py, bestX, bestY, w, h, result, targetUnknown)
                else Float.MAX_VALUE

                fun tryCandidate(cx: Int, cy: Int) {
                    val rx = cx.coerceIn(PATCH_RADIUS, w - 1 - PATCH_RADIUS)
                    val ry = cy.coerceIn(PATCH_RADIUS, h - 1 - PATCH_RADIUS)
                    if (!isPatchKnown(rx, ry, w, h, PATCH_RADIUS, dilated)) return
                    val score = patchDistance(px, py, rx, ry, w, h, result, targetUnknown)
                    if (score < bestScore) {
                        bestScore = score; bestX = rx; bestY = ry
                    }
                }

                // Propagation from neighbours processed earlier in this sweep.
                val dirRange = if (sweep % 2 == 0) 0..1 else 2..3
                for (d in dirRange) {
                    val qx = px + neighbors[d][0]; val qy = py + neighbors[d][1]
                    if (qx < 0 || qx >= w || qy < 0 || qy >= h) continue
                    val q = qy * w + qx
                    if (nnfX[q] < 0) continue
                    // Shift the neighbour's source by the p-q offset.
                    tryCandidate(nnfX[q] - neighbors[d][0], nnfY[q] - neighbors[d][1])
                }
                // Small jitter around the current best + a few fresh randoms.
                if (bestX >= 0) {
                    repeat(4) {
                        tryCandidate(
                            bestX + (rnd.nextInt(5) - 2),
                            bestY + (rnd.nextInt(5) - 2),
                        )
                    }
                }
                repeat(4) {
                    tryCandidate(
                        (px + (rnd.nextFloat() - 0.5f) * w * 0.4f).toInt(),
                        (py + (rnd.nextFloat() - 0.5f) * h * 0.4f).toInt(),
                    )
                }

                if (bestX >= 0) {
                    nnfX[p] = bestX; nnfY[p] = bestY
                    result[p] = result[bestY * w + bestX]
                }
                sDone++
                if (sDone % 1000 == 0) {
                    onProgress(0.6f + (sweep * total + sDone).toFloat() /
                        (total * 2) * 0.3f)
                }
            }
        }
        return result
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = mask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val ny = (y + dy).coerceIn(0, h - 1)
                        out[ny * w + nx] = true
                    }
                }
            }
        }
        return out
    }

    /**
     * Approximate distance transform via BFS from known pixels.
     * Returns IntArray of distances (0 = known, higher = deeper in mask).
     */
    internal fun distanceTransform(mask: BooleanArray, w: Int, h: Int): IntArray {
        val dist = IntArray(w * h) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (i in mask.indices) {
            if (!mask[i]) {
                dist[i] = 0
                queue.add(i)
            }
        }
        val dirs = intArrayOf(-1, 1, -w, w)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val d = dist[p]
            val px = p % w
            for (dir in dirs) {
                val q = p + dir
                if (q < 0 || q >= w * h) continue
                val qx = q % w
                if (abs(qx - px) > 1) continue // wrapped around
                if (dist[q] > d + 1) {
                    dist[q] = d + 1
                    queue.add(q)
                }
            }
        }
        return dist
    }

    private fun isPatchKnown(
        cx: Int, cy: Int, w: Int, h: Int, radius: Int, mask: BooleanArray,
    ): Boolean {
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) return false
                if (mask[ny * w + nx]) return false
            }
        }
        return true
    }

    /**
     * Sum of squared differences between the target patch (tx,ty) and the
     * source patch (sx,sy), comparing only pixels where the target is known
     * (not in [mask]). Center-weighted.
     *
     * Bounds are checked on both axes explicitly: the old version indexed
     * `tpy * w + tpx` with only a `< size` guard, so target pixels near the
     * right/bottom edges compared wrapped-around pixels from the next row —
     * visibly wrong fills along image edges.
     */
    internal fun patchDistance(
        tx: Int, ty: Int, sx: Int, sy: Int, w: Int, h: Int,
        result: IntArray, mask: BooleanArray,
    ): Float {
        var sum = 0f
        var cnt = 0
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) {
            for (dx in -PATCH_RADIUS..PATCH_RADIUS) {
                val tpx = tx + dx; val tpy = ty + dy
                if (tpx < 0 || tpx >= w || tpy < 0 || tpy >= h) continue
                val ti = tpy * w + tpx
                if (mask[ti]) continue // target unknown: skip
                val spx = sx + dx; val spy = sy + dy
                if (spx < 0 || spx >= w || spy < 0 || spy >= h) continue
                val si = spy * w + spx

                val tc = result[ti]; val sc = result[si]
                val dr = ((tc shr 16) and 0xFF) - ((sc shr 16) and 0xFF)
                val dg = ((tc shr 8) and 0xFF) - ((sc shr 8) and 0xFF)
                val db = (tc and 0xFF) - (sc and 0xFF)
                val weight = 1f - (abs(dx) + abs(dy)).toFloat() / (PATCH_RADIUS * 2 + 1)
                sum += (dr * dr + dg * dg + db * db) * (0.5f + weight)
                cnt++
            }
        }
        return if (cnt == 0) Float.MAX_VALUE else sum / cnt
    }

    private fun averageKnownNeighbors(
        p: Int, w: Int, h: Int, result: IntArray, mask: BooleanArray,
    ): Int {
        var sr = 0; var sg = 0; var sb = 0; var cnt = 0
        val px = p % w; val py = p / w
        for (dy in -3..3) {
            for (dx in -3..3) {
                if (dx == 0 && dy == 0) continue
                val nx = px + dx; val ny = py + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val q = ny * w + nx
                if (mask[q]) continue
                val c = result[q]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
                cnt++
            }
        }
        return if (cnt == 0) result[p] else {
            (255 shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
        }
    }
}
