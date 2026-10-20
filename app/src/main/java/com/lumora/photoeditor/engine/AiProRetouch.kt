package com.lumora.photoeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AI Pro Retouch — one-tap pro color grade.
 *
 * Architecture (deliberately mirrors the "image-adaptive 3D LUT" family of
 * photo-enhancement models, e.g. Zeng et al.): the app holds a small bank of
 * basis 3D LUTs baked from its own curated pro recipes ([ProRecipes] —
 * license-clean, they ship with the app), analyzes each photo's condition,
 * predicts per-photo blend weights over the bank, fuses a single
 * image-adaptive LUT (fused = sum_j w_j * LUT_j), and applies it on the GPU
 * with hardware trilinear filtering.
 *
 * Honest accounting of what this is and isn't (v1):
 * - IS: on-device analysis of exposure / cast / scene driving a pro-grade
 *   LUT blend, applied as one undoable tap. Real, deterministic, offline.
 * - ISN'T (yet): a neural network trained on pro before/after pairs. The
 *   [WeightPredictor] seam exists exactly for that Step 2: when license-clean
 *   trained weights exist, a TFLite predictor implementing this interface
 *   replaces [heuristicPredictor] with no pipeline changes. See
 *   [AiTrainingPolicy]: PPR10K (2026-10-18, verified) and MIT-Adobe FiveK are
 *   both research-only — including derived data — and must never be trained
 *   on or shipped. Step 2's clean path is the user's own before/after pairs.
 *
 * This file has zero Android imports on purpose: stats, prediction and
 * fusion are pure math, unit-testable on the JVM via the standalone kotlinc
 * harness (see AGENTS.md). [AiLutBank] handles the Android/GL side
 * (baking the basis LUTs through the real shader, uploading the fused LUT).
 */
object AiProRetouch {

    /** 3D LUT resolution per axis. 17^3 = 4913 cells: smooth for pro grades, tiny in memory. */
    const val LUT_SIZE = 17

    /** Basis LUT order. [BASIS_NAMES] is the human-readable parallel. */
    const val BASIS_PORTRAIT = 0
    const val BASIS_LANDSCAPE = 1
    const val BASIS_NIGHT = 2
    const val BASIS_BACKLIT = 3
    const val BASIS_COUNT = 4
    val BASIS_NAMES = listOf("Portrait", "Landscape", "Night", "Backlit")

    /** Measured condition of one photo; the predictor's entire input. */
    data class PhotoStats(
        val meanLum: Float,   // 0..1 frame mean luminance
        val stdLum: Float,    // 0..1 luminance std-dev
        val darkRatio: Float, // fraction of pixels with lum <= 40/255
        val brightRatio: Float, // fraction with lum >= 215/255
        val p1: Int,          // 1st percentile luminance (0..255)
        val p99: Int,         // 99th percentile luminance (0..255)
        val skinRatio: Float, // fraction of skin-probable pixels
        val meanSat: Float,   // 0..1 mean saturation
        val castR: Float,     // illuminant cast estimate, red channel deviation
        val castB: Float,     // illuminant cast estimate, blue channel deviation
        /** True when the neutral-candidate subset covered >= 2% of pixels,
         *  i.e. the cast estimate comes from real neutral content rather
         *  than the whole-frame fallback. Only then is a per-photo WB
         *  correction trustworthy (a sunset's warm whole-frame mean must
         *  never be "corrected" away). */
        val wbReliable: Boolean,
    )

    /**
     * Predicts blend weights (size [BASIS_COUNT], sums to 1) from [PhotoStats].
     * v1 ships [heuristicPredictor]; Step 2 swaps in a trained TFLite model
     * implementing this same interface.
     */
    fun interface WeightPredictor {
        fun predict(stats: PhotoStats): FloatArray
    }

    /** Spatial (non-pointwise, can't live in a LUT) recipe components, blended by the same weights. */
    data class SpatialDelta(val clarity: Float = 0f, val texture: Float = 0f, val dehaze: Float = 0f)

    /** Per-photo retouch decision. */
    data class LutBlend(
        /** size [BASIS_COUNT], sums to 1, order = [BASIS_NAMES]. */
        val weights: FloatArray,
        /** 0..1 overall intensity. 0 = photo already balanced: caller must no-op (no undo entry). */
        val strength: Float,
        val sceneName: String,
        val spatial: SpatialDelta,
        /** Per-photo white-balance gains [gR, gG, gB], or null when the
         *  photo needs no cast correction (already neutral, or no reliable
         *  neutral content — see [computeWbGains]). Folded into the fused
         *  LUT at upload time. */
        val wbGains: FloatArray? = null,
    )

    /**
     * Skin probability 0..1 via the YCrCb heuristic — the same formula as
     * [AutoMagic.skinProbability], duplicated here (not referenced) so this
     * file keeps zero Android-touching dependencies and stays harness-safe.
     */
    fun skinProbability(r: Int, g: Int, b: Int): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
        val cb = 128f - 0.1687f * r - 0.3313f * g + 0.5f * b
        if (y < 40 || y > 250) return 0f
        val maxRg = max(r, g); val minRg = min(r, b)
        if (maxRg - minRg < 15) return 0f
        val dCr = (cr - 155f) / 28f
        val dCb = (cb - 110f) / 22f
        val dist = sqrt(dCr * dCr + dCb * dCb)
        return (1f - (dist / 1.5f)).coerceIn(0f, 1f).let {
            it * it * (3f - 2f * it)
        }
    }

    /** Single histogram pass over ARGB [pixels] (row-major, [w] x [h]). */
    fun computeStats(pixels: IntArray, w: Int, h: Int): PhotoStats {
        val n = pixels.size
        if (n == 0 || w <= 0 || h <= 0) {
            return PhotoStats(0.5f, 0f, 0f, 0f, 0, 255, 0f, 0f, 0f, 0f, false)
        }
        val hist = IntArray(256)
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var sumSat = 0f; var skinPixels = 0
        var wbR = 0L; var wbG = 0L; var wbB = 0L; var wbN = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r; sumG += g; sumB += b
            // Round-half-up (not truncation): this histogram drives the
            // image-adaptive grade weights; truncation biases it ~0.5 dark.
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            hist[lum]++
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx > 0) (mx - mn).toFloat() / mx else 0f
            sumSat += sat
            val sp = skinProbability(r, g, b)
            if (sp > 0.5f) skinPixels++
            if (sat <= 0.35f && sp <= 0.5f && lum in 30..225) {
                wbR += r; wbG += g; wbB += b; wbN++
            }
        }
        fun percentile(p: Float): Int {
            val target = (n * p).toInt(); var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        val meanR = sumR.toFloat() / n
        val meanG = sumG.toFloat() / n
        val meanB = sumB.toFloat() / n
        var binSum = 0.0; var binSum2 = 0.0
        for (i in 0..255) { val c = hist[i].toDouble(); binSum += i * c; binSum2 += i * i * c }
        val binMean = binSum / n
        val stdLum = sqrt(max(0.0, binSum2 / n - binMean * binMean)).toFloat() / 255f
        // Cast from the neutral-candidate subset (same discipline as AutoEnhanceCore).
        val (eR, eG, eB) = if (wbN >= n * 0.02f) {
            Triple(wbR.toFloat() / wbN, wbG.toFloat() / wbN, wbB.toFloat() / wbN)
        } else Triple(meanR, meanG, meanB)
        val eAvg = (eR + eG + eB) / 3f
        return PhotoStats(
            meanLum = (0.2126f * meanR + 0.7152f * meanG + 0.0722f * meanB) / 255f,
            stdLum = stdLum,
            darkRatio = hist.slice(0..40).sum().toFloat() / n,
            brightRatio = hist.slice(215..255).sum().toFloat() / n,
            p1 = percentile(0.01f),
            p99 = percentile(0.99f),
            skinRatio = skinPixels.toFloat() / n,
            meanSat = sumSat / n,
            castR = (eR - eAvg) / eAvg.coerceAtLeast(1f),
            castB = (eB - eAvg) / eAvg.coerceAtLeast(1f),
            wbReliable = wbN >= n * 0.02f,
        )
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Per-photo white-balance gains [gR, gG, gB], or null when no correction
     * is warranted.
     *
     * This is step 1 of every pro retouch: neutralize the illuminant cast
     * BEFORE the creative grade, so the recipe's own temp/tint lands on
     * neutral whites instead of amplifying a fluorescent green or tungsten
     * orange cast. The gains are exact inverses of the measured deviations:
     * with eR = eAvg*(1+castR) etc., eG = eAvg*(1-castR-castB), so
     * gR = 1/(1+castR), gG = 1/(1-castR-castB), gB = 1/(1+castB) maps the
     * neutral estimate to (eAvg, eAvg, eAvg).
     *
     * Honest guards (no fake "auto WB" on photos that don't want it):
     *  - wbReliable must hold: without real neutral content the whole-frame
     *    mean would "correct" intentional warmth (golden hour, neon night)
     *    into gray. Those photos get null — the grade alone.
     *  - |castR| + |castB| < 0.06: already neutral; don't touch it.
     *  - gains clamped to 0.6..1.67 (about +/-0.75 EV): a pathological cast
     *    estimate can never push a channel into clipping craziness.
     */
    fun computeWbGains(s: PhotoStats): FloatArray? {
        if (!s.wbReliable) return null
        if (abs(s.castR) + abs(s.castB) < 0.06f) return null
        fun gain(dev: Float): Float = (1f / (1f + dev)).coerceIn(0.6f, 1.67f)
        return floatArrayOf(
            gain(s.castR),
            gain(-s.castR - s.castB),
            gain(s.castB),
        )
    }

    /**
     * Folds per-photo white-balance gains into a fused 3D LUT by warping its
     * input lattice: warped[r,g,b] = lut(r*gR, g*gG, b*gB), trilinearly
     * sampled. The WB correction then rides inside the same GPU LUT sample
     * as the grade — zero extra shader cost, and it scales with the grade's
     * intensity mix like the rest of the retouch.
     *
     * Lattice layout matches [AiLutBank]'s bake: index (((r*n)+g)*n+b)*3.
     * Gains of exactly [1,1,1] reproduce the input LUT bit-for-bit.
     */
    fun warpLutForWb(lut: FloatArray, gains: FloatArray, n: Int): FloatArray {
        require(n >= 2) { "LUT size must be >= 2" }
        require(lut.size == n * n * n * 3) { "LUT has wrong size for n=$n" }
        require(gains.size == 3) { "gains must be [gR, gG, gB]" }
        val out = FloatArray(lut.size)
        val scale = (n - 1).toFloat()
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
            val sr = (r / scale * gains[0]).coerceIn(0f, 1f)
            val sg = (g / scale * gains[1]).coerceIn(0f, 1f)
            val sb = (b / scale * gains[2]).coerceIn(0f, 1f)
            val rgb = sampleLut(lut, n, sr, sg, sb)
            val o = (((r * n) + g) * n + b) * 3
            out[o] = rgb[0]; out[o + 1] = rgb[1]; out[o + 2] = rgb[2]
        }
        return out
    }

    /** Trilinear sample of an [n]^3 LUT at normalized [r],[g],[b] in 0..1. */
    private fun sampleLut(lut: FloatArray, n: Int, r: Float, g: Float, b: Float): FloatArray {
        val scale = (n - 1).toFloat()
        val fr = (r * scale).coerceIn(0f, scale)
        val fg = (g * scale).coerceIn(0f, scale)
        val fb = (b * scale).coerceIn(0f, scale)
        val r0 = fr.toInt().coerceIn(0, n - 2); val r1 = r0 + 1
        val g0 = fg.toInt().coerceIn(0, n - 2); val g1 = g0 + 1
        val b0 = fb.toInt().coerceIn(0, n - 2); val b1 = b0 + 1
        val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
        fun cell(ri: Int, gi: Int, bi: Int, c: Int): Float =
            lut[((((ri * n) + gi) * n + bi) * 3) + c]
        val out = FloatArray(3)
        for (c in 0..2) {
            val c000 = cell(r0, g0, b0, c); val c100 = cell(r1, g0, b0, c)
            val c010 = cell(r0, g1, b0, c); val c110 = cell(r1, g1, b0, c)
            val c001 = cell(r0, g0, b1, c); val c101 = cell(r1, g0, b1, c)
            val c011 = cell(r0, g1, b1, c); val c111 = cell(r1, g1, b1, c)
            val c00 = c000 + (c100 - c000) * tr
            val c10 = c010 + (c110 - c010) * tr
            val c01 = c001 + (c101 - c001) * tr
            val c11 = c011 + (c111 - c011) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[c] = c0 + (c1 - c0) * tb
        }
        return out
    }

    /**
     * v1 predictor: transparent scene scoring. Each score is a smooth 0..1
     * response to the stats that define its scene; weights are the normalized
     * scores. Nothing here pretends to be a neural network — it is a
     * documented heuristic, and the [WeightPredictor] seam is where the
     * trained model goes in Step 2.
     */
    val heuristicPredictor: WeightPredictor = WeightPredictor { s ->
        // Portrait: any meaningful face presence.
        val portrait = smoothstep(0.02f, 0.15f, s.skinRatio)
        // Night: mostly dark, low mean, no face (a shaded daylight portrait
        // must not get the night-mood treatment — same guard as AutoEnhanceCore).
        val night = smoothstep(0.45f, 0.65f, s.darkRatio) *
                (1f - smoothstep(0.24f, 0.34f, s.meanLum)) *
                (1f - smoothstep(0.02f, 0.05f, s.skinRatio))
        // Backlit: bimodal — dark subject + bright background.
        val backlit = smoothstep(0.3f, 0.45f, s.darkRatio) *
                smoothstep(0.25f, 0.4f, s.brightRatio) *
                smoothstep(140f, 180f, (s.p99 - s.p1).toFloat()) *
                (1f - 0.5f * smoothstep(0.02f, 0.1f, s.skinRatio))
        // Landscape: the daylight default — vivid, contrasty, no face, not night.
        // It is the "everything else" anchor so weights never collapse.
        val landscape = (0.35f + 0.65f * smoothstep(0.15f, 0.45f, s.meanSat)) *
                (1f - 0.7f * portrait) * (1f - night)
        val raw = floatArrayOf(portrait, landscape, night, backlit)
        val sum = raw.sum().coerceAtLeast(1e-6f)
        FloatArray(BASIS_COUNT) { i -> raw[i] / sum }
    }

    /**
     * Full per-photo decision. Returns strength 0 when the photo is blank
     * (uniform-frame guard, mirrors AutoEnhanceCore) or already balanced —
     * callers must treat strength 0 as "do nothing, no undo entry".
     */
    fun analyze(
        pixels: IntArray,
        w: Int,
        h: Int,
        predictor: WeightPredictor = heuristicPredictor,
    ): LutBlend {
        val s = computeStats(pixels, w, h)
        // Uniform frame: nothing meaningful to grade.
        if (s.stdLum < 0.02f) {
            return LutBlend(FloatArray(BASIS_COUNT) { 0.25f }, 0f, "Photo", SpatialDelta(), null)
        }
        val weights = predictor.predict(s)
        // How far from balanced? Drives intensity — a photo that's already
        // close needs only a whisper of grade.
        val exposureDev = if (s.meanLum > 0.01f) {
            abs(kotlin.math.log2(0.46f / s.meanLum.coerceAtLeast(0.01f))).coerceAtMost(2.5f) / 2.5f
        } else 1f
        val castDev = ((abs(s.castR) + abs(s.castB)) / 0.3f).coerceIn(0f, 1f)
        val flatDev = ((170 - (s.p99 - s.p1)).toFloat() / 170f).coerceIn(0f, 1f)
        val need = maxOf(exposureDev, castDev, flatDev)
        // Below the honesty threshold the photo is already balanced: no-op.
        if (need < 0.12f) {
            return LutBlend(weights, 0f, BASIS_NAMES[weights.indices.maxByOrNull { weights[it] } ?: 1], SpatialDelta(), null)
        }
        val strength = (0.35f + 0.65f * need).coerceIn(0f, 1f)
        val sceneIdx = weights.indices.maxByOrNull { weights[it] } ?: 1
        // Spatial recipe components blended by the same weights (these are
        // neighborhood ops — clarity/texture/dehaze can't live inside a LUT).
        val clarity = weights[BASIS_PORTRAIT] * 6f + weights[BASIS_LANDSCAPE] * 18f +
                weights[BASIS_NIGHT] * 10f + weights[BASIS_BACKLIT] * 12f
        val texture = weights[BASIS_PORTRAIT] * -8f + weights[BASIS_LANDSCAPE] * 12f
        val dehaze = weights[BASIS_PORTRAIT] * 0f + weights[BASIS_LANDSCAPE] * 20f +
                weights[BASIS_NIGHT] * 8f + weights[BASIS_BACKLIT] * 10f
        return LutBlend(
            weights = weights,
            strength = strength,
            sceneName = BASIS_NAMES[sceneIdx],
            spatial = SpatialDelta(clarity, texture, dehaze),
            // Pro step 1: neutralize the illuminant cast inside the fused
            // LUT before the creative grade lands on it. Null when the
            // photo is already neutral or has no reliable neutral content.
            wbGains = computeWbGains(s),
        )
    }

    /**
     * Fuses the basis LUTs into one image-adaptive LUT:
     * fused[i] = sum_j weights[j] * basis[j][i].
     * This is the exact fusion equation of the image-adaptive 3D LUT family;
     * only the weight source differs in v1 (heuristic vs trained CNN).
     */
    fun fuse(weights: FloatArray, basis: List<FloatArray>): FloatArray {
        require(weights.size == BASIS_COUNT) { "weights must have $BASIS_COUNT entries" }
        require(basis.size == BASIS_COUNT) { "basis must have $BASIS_COUNT LUTs" }
        val n = basis[0].size
        require(n % 3 == 0) { "basis LUT size must be a multiple of 3" }
        val out = FloatArray(n)
        for (j in 0 until BASIS_COUNT) {
            val w = weights[j]
            if (w == 0f) continue
            val lut = basis[j]
            require(lut.size == n) { "basis LUT $j has wrong size" }
            for (i in 0 until n) out[i] += w * lut[i]
        }
        for (i in 0 until n) out[i] = out[i].coerceIn(0f, 1f)
        return out
    }
}
