package com.lumora.photoeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One-tap Auto Denoise: analyzes the photo's noise and suggests noise-reduction
 * settings. Deterministic pixel math, NO machine learning, NO fake AI.
 *
 * License context (pro-premium program Phase B#3, "AI denoise"): the brief
 * calls for an on-device, license-clean model; a 2026-10-18 check found no
 * published denoise weights with a verified commercial license (open denoise
 * research is research-licensed or desktop-oriented), so this ships the best
 * classical++ approach and says so. [AiTrainingPolicy] pins the block list.
 *
 * Method: robust noise-floor estimation from the photo's flattest regions.
 * For each interior pixel the local gradient is measured; pixels below the
 * 20th gradient percentile are "flat enough". On those, the deviation from
 * the local 3x3 mean is collected separately for luminance and chroma
 * (r-luma / b-luma); the MAD (median absolute deviation, x1.4826) of each is
 * the noise sigma. The 3x3 local mean tracks slow brightness gradients
 * (skies, vignette), so only genuine fine-grained fluctuation counts.
 * [scaleComp] compensates noise attenuation from downsampling: the caller
 * passes sqrt(originalArea / analyzedArea) (1.0 at full res).
 *
 * The sigmas map conservatively to the real Detail NR sliders:
 * luminance NR starts at sigma ~2, chroma NR (safer, chroma blotches are
 * uglier) at sigma ~1.5; both are capped (60/70) because auto should never
 * smear a photo on its own. Clean photos (both sigmas < 1.2) return null —
 * nothing is touched. Photos with too few flat pixels to trust (64 minimum)
 * also return null. Applied values are ABSOLUTE slider targets, so repeated
 * taps converge instead of stacking.
 *
 * Known limitation (honest): fine regular texture (fabric, screens) can read
 * as noise to any classical estimator — this is the noise/texture ambiguity,
 * not a bug. The conservative mapping and caps keep it mild, and the result
 * is one undoable tap the user can dial down.
 *
 * This file has zero Android imports on purpose: the analysis is deterministic
 * pixel math, so it stays unit-testable on the JVM (see the standalone
 * kotlinc harness) and is pinned by SelfTest check 67.
 */
object AutoDenoise {

    /** Suggested NR in Detail slider units (0..100). */
    data class Settings(
        val luminanceNr: Float = 0f,
        val colorNr: Float = 0f,
    )

    /** Minimum flat-region samples for a trustworthy estimate. */
    private const val MIN_SAMPLES = 64

    /** Below these sigmas (0..255 units) the photo is considered clean. */
    private const val CLEAN_SIGMA = 1.2f

    /** Mapping floors: NR starts above these sigmas. */
    private const val LUM_FLOOR = 2f
    private const val CHROMA_FLOOR = 1.5f

    /** Conservative spans: sigma -> 0..cap over floor..floor+span. */
    private const val LUM_SPAN = 18f
    private const val CHROMA_SPAN = 14f

    /** Auto never pushes NR past these: it should not smear on its own. */
    private const val LUM_CAP = 60f
    private const val CHROMA_CAP = 70f

    /**
     * Analyzes ARGB [pixels] (row-major, [w] x [h]) and returns suggested NR
     * settings, or null when the photo is clean or the estimate can't be
     * trusted (too small, no flat regions, near-uniform frame).
     *
     * @param scaleComp sqrt(originalArea / analyzedArea) to undo
     *   downsampling's noise attenuation; 1.0 when analyzing at full res.
     */
    fun analyzePixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        scaleComp: Float = 1f,
    ): Settings? {
        val n = pixels.size
        if (n == 0 || w < 8 || h < 8 || n != w * h) return null
        val comp = max(1f, scaleComp)

        // Luma + chroma planes (chroma as signed r-luma / b-luma deviations).
        val luma = FloatArray(n)
        val cr = FloatArray(n)
        val cb = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            luma[i] = y
            cr[i] = r - y
            cb[i] = b - y
        }

        // Local gradient for interior pixels; the flattest quintile is the
        // noise-measurement population.
        val grad = FloatArray(n)
        var gradCount = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = abs(luma[i + 1] - luma[i - 1])
                val gy = abs(luma[i + w] - luma[i - w])
                grad[gradCount++] = gx + gy
            }
        }
        if (gradCount == 0) return null
        val sorted = grad.copyOfRange(0, gradCount).also { it.sort() }
        val threshold = sorted[(gradCount * 20 / 100).coerceIn(0, gradCount - 1)]

        // Collect |pixel - local 3x3 mean| over the flat population.
        val resL = FloatArray(gradCount)
        val resC = FloatArray(gradCount)
        var samples = 0
        var k = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val g = grad[k++]
                if (g > threshold) continue
                val i = y * w + x
                var sL = 0f; var sR = 0f; var sB = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val j = i + dy * w + dx
                        sL += luma[j]; sR += cr[j]; sB += cb[j]
                    }
                }
                resL[samples] = abs(luma[i] - sL / 9f)
                // Chroma residual: worst of the two chroma channels.
                resC[samples] = max(abs(cr[i] - sR / 9f), abs(cb[i] - sB / 9f))
                samples++
            }
        }
        if (samples < MIN_SAMPLES) return null

        val sigmaL = 1.4826f * medianOf(resL, samples) * comp
        val sigmaC = 1.4826f * medianOf(resC, samples) * comp
        return mapToSettings(sigmaL, sigmaC)
    }

    /**
     * Pure sigma -> slider-units mapping. Pinned by SelfTest check 67.
     * Returns null when both channels are below the clean threshold.
     */
    fun mapToSettings(sigmaLuma: Float, sigmaChroma: Float): Settings? {
        if (sigmaLuma < CLEAN_SIGMA && sigmaChroma < CLEAN_SIGMA) return null
        val lumNr = ((sigmaLuma - LUM_FLOOR) / LUM_SPAN * 100f)
            .coerceIn(0f, LUM_CAP).roundToInt().toFloat()
        val colNr = ((sigmaChroma - CHROMA_FLOOR) / CHROMA_SPAN * 100f)
            .coerceIn(0f, CHROMA_CAP).roundToInt().toFloat()
        if (lumNr < 0.5f && colNr < 0.5f) return null
        return Settings(lumNr, colNr)
    }

    /** Short human summary for the on-screen confirmation chip. */
    fun describe(s: Settings): String {
        val parts = mutableListOf<String>()
        if (s.luminanceNr >= 0.5f) parts += "Luminance ${s.luminanceNr.roundToInt()}"
        if (s.colorNr >= 0.5f) parts += "Color ${s.colorNr.roundToInt()}"
        return parts.joinToString(" · ")
    }

    private fun medianOf(a: FloatArray, count: Int): Float {
        val copy = a.copyOfRange(0, count)
        copy.sort()
        val mid = count / 2
        return if (count % 2 == 1) copy[mid]
        else (copy[mid - 1] + copy[mid]) / 2f
    }
}
