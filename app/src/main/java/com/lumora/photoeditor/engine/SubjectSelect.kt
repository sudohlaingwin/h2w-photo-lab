package com.lumora.photoeditor.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classical-CV subject selection ("Select Subject" / "Select Background").
 *
 * Picks the photo's main subject with deterministic image analysis — no
 * neural network, no on-device ML model: local-contrast saliency + color
 * distinctiveness + a YCrCb skin boost, modulated by a center prior (the
 * photographer usually centers the subject) and border suppression (the
 * frame edge is almost always background), then edge-aware refinement so
 * the soft mask snaps to real object boundaries.
 *
 * This is the engine behind the Mask panel's "Subject AI" / "Background AI"
 * buttons and the Auto Mask "Subject (auto)" plan. It returns null — rather
 * than a wrong mask — when no confident subject exists (uniform frames,
 * smooth gradients, tiny specks, subjects filling the whole frame).
 *
 * Zero Android imports on purpose: pure pixel math, JVM-testable with the
 * standalone kotlinc harness (see AGENTS.md).
 */
object SubjectSelect {

    /**
     * Selects the main subject.
     *
     * @param pixels ARGB pixels, row-major, exactly [w]x[h]. Works on any
     *   size; callers downsample to ~192px max dimension first for speed.
     * @return soft subject mask 0..1 (same [w]x[h] layout), or null when no
     *   confident subject is found.
     */
    fun select(pixels: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 24 || h < 24 || pixels.size < w * h) return null
        val n = w * h

        // Luminance + global mean color.
        val lum = FloatArray(n)
        var mr = 0.0; var mg = 0.0; var mb = 0.0
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            mr += r; mg += g; mb += b
        }
        mr /= n; mg /= n; mb /= n

        // Local background estimate for the contrast term.
        val bgR = max(2, min(w, h) / 24)
        val bg = boxBlur(lum, w, h, bgR)

        // Local texture level: mean |lum - bg| in the same window. Dividing
        // the contrast term by the local texture desensitizes busy regions
        // (grass, fabric grain, noise): a high-contrast *pattern* is not a
        // subject, but the same absolute edge on a smooth backdrop is.
        val absDiff = FloatArray(n) { i -> kotlin.math.abs(lum[i] - bg[i]) }
        val texLevel = boxBlur(absDiff, w, h, bgR)

        // Per-pixel saliency.
        val sal = FloatArray(n)
        // Local-contrast term per pixel, kept for the edge-content gate
        // below: a selected region must contain real edges/texture.
        val edgeTerm = FloatArray(n)
        var sMean = 0.0
        val hw = w / 2f; val hh = h / 2f
        for (y in 0 until h) {
            val dy = (y - hh + 0.5f) / hh
            for (x in 0 until w) {
                val i = y * w + x
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // Local contrast, normalized by the local texture level:
                // an edge twice as strong as its surroundings pops;
                // texture-internal edges are discounted.
                val contrast = min(1f, absDiff[i] / max(30f, 2.5f * texLevel[i]))
                edgeTerm[i] = contrast
                // Color distinctiveness: distance from the image's mean
                // color (a red flower in a green field pops).
                val dr = r - mr; val dg = g - mg; val db = b - mb
                val colorDist = min(1f, sqrt(dr * dr + dg * dg + db * db).toFloat() / 220f)
                // Skin boost: people are subjects far more often than not.
                val skin = skinLikelihood(r, g, b)

                // Center prior: gaussian falloff, sigma = 0.5 of half-size.
                // A rule-of-thirds subject (dx = 0.66) still keeps ~57% of
                // its saliency; the prior only breaks ties.
                val dx = (x - hw + 0.5f) / hw
                val center = exp(-(dx * dx + dy * dy) / 0.5).toFloat()
                // Border suppression: the frame edge is background. Gentle —
                // subjects cropped by the frame edge still survive at 25%.
                val edge = min(min(x, y), min(w - 1 - x, h - 1 - y)).toFloat()
                val border = 0.25f + 0.75f * smoothstep(edge / 10f)

                val s = (0.40f * contrast + 0.60f * colorDist + 0.45f * skin) *
                    (0.35f + 0.65f * center) * border
                sal[i] = s
                sMean += s
            }
        }
        sMean /= n

        // Flat saliency = no subject (uniform frame, smooth gradient).
        var sVar = 0.0
        for (i in 0 until n) {
            val d = sal[i] - sMean
            sVar += d * d
        }
        if (sqrt(sVar / n) < 0.025) return null

        // Adaptive soft threshold at the 50th/90th saliency percentiles:
        // t_lo sits at the top of the background distribution and t_hi
        // inside the subject for any subject fraction in 3%..70%.
        val sorted = sal.clone()
        sorted.sort()
        val p50 = sorted[(n * 0.50).toInt()].toDouble()
        val p90 = sorted[(n * 0.90).toInt()].toDouble()
        if (p90 - p50 < 0.05) return null // no meaningful separation
        val mask = FloatArray(n)
        for (i in 0 until n) {
            mask[i] = smoothstep(((sal[i] - p50) / (p90 - p50)).toFloat())
        }

        // Edge-aware refinement (2 passes): re-average each pixel over its
        // 3x3 neighborhood weighted by luminance similarity, so the soft
        // mask snaps to real object boundaries instead of bleeding across
        // them. Weights use the CURRENT mask, luminance gates the mixing.
        var cur = mask
        repeat(2) {
            val next = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var wSum = 1f; var mSum = cur[i]
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val j = ny * w + nx
                    val wgt = exp(-kotlin.math.abs(lum[i] - lum[j]) / 30f).toFloat()
                    wSum += wgt
                    mSum += wgt * cur[j]
                }
                next[i] = mSum / wSum
            }
            cur = next
        }

        // Speck cleanup: binarize at 0.5 and apply a 3x3 morphological
        // open (erode then dilate) to drop isolated specks and thin
        // streaks, then re-apply the soft values where the open mask
        // survives — edges stay soft, noise is gone.
        val binary = BooleanArray(n) { cur[it] > 0.5f }
        val opened = dilate(erode(binary, w, h), w, h)
        var coverage = 0
        for (i in 0 until n) if (opened[i]) coverage++
        val frac = coverage.toFloat() / n
        // Reject specks (< 3%), and "subjects" that are really the whole
        // frame (> 70% — e.g. a wall filling the photo).
        if (frac < 0.03f || frac > 0.70f) return null

        // Confidence: the selected region must actually be more salient
        // than the rest, or the threshold just cut noise in half.
        var inSum = 0.0; var inCnt = 0
        var outSum = 0.0; var outCnt = 0
        for (i in 0 until n) {
            if (opened[i]) { inSum += sal[i]; inCnt++ }
            else { outSum += sal[i]; outCnt++ }
        }
        if (inCnt == 0 || outCnt == 0) return null
        if (inSum / inCnt < outSum / outCnt + 0.06) return null

        // Edge-content gate: the selected region must contain real edges or
        // texture. A smooth color gradient or vignetted backdrop can carve a
        // saliency bowl out of pure global color distance — the threshold
        // then keeps the bowl's peak as a plausible-looking centered blob
        // with no underlying structure. Rejecting it returns the honest
        // "no subject found" instead of a wrong mask. Measured separation
        // (standalone harness, 2026-10-17): false-positive gradients peak
        // at 0.013 mean edge term, the softest real subject (blurred disk)
        // scores 0.056 — the 0.03 bar clears both sides with ~2x headroom.
        var edgeSum = 0.0
        for (i in 0 until n) if (opened[i]) edgeSum += edgeTerm[i]
        if (edgeSum / inCnt < 0.03) return null

        // Final soft mask + a light feather for smooth composite edges.
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = if (opened[i]) cur[i] else 0f
        return boxBlur(out, w, h, 1)
    }

    /**
     * Skin likelihood 0..1 via the standard YCrCb elliptical heuristic
     * (same published ranges the app's AutoRetouch uses). Kept local so
     * this file stays free of Android imports and harnessable on its own.
     */
    private fun skinLikelihood(r: Int, g: Int, b: Int): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        if (y < 40 || y > 250) return 0f
        if (max(r, g) - min(r, b) < 15) return 0f // too gray
        val cr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
        val cb = 128f - 0.1687f * r - 0.3313f * g + 0.5f * b
        val dCr = (cr - 155f) / 28f
        val dCb = (cb - 110f) / 22f
        val dist = sqrt(dCr * dCr + dCb * dCb)
        val t = (1f - dist / 1.5f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Separable-free 3x3-ish box blur, edge-clamped. r = 1..n. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.clone()
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var cnt = 0
            for (dx in -r..r) {
                sum += src[y * w + (x + dx).coerceIn(0, w - 1)]
                cnt++
            }
            tmp[y * w + x] = sum / cnt
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var cnt = 0
            for (dy in -r..r) {
                sum += tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                cnt++
            }
            out[y * w + x] = sum / cnt
        }
        return out
    }

    private fun erode(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var all = true
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, w - 1)
                val ny = (y + dy).coerceIn(0, h - 1)
                if (!src[ny * w + nx]) { all = false; break }
            }
            out[y * w + x] = all
        }
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var any = false
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, w - 1)
                val ny = (y + dy).coerceIn(0, h - 1)
                if (src[ny * w + nx]) { any = true; break }
            }
            out[y * w + x] = any
        }
        return out
    }
}
