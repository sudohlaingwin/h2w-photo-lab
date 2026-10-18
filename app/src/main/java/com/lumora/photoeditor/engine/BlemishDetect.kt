package com.lumora.photoeditor.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin blemish detector (no android.graphics) — the JVM-testable core
 * behind [AutoRetouch.detectBlemishes], which handles the Bitmap downsampling
 * and delegates here.
 *
 * Detects small high-contrast spots of BOTH polarities against a local
 * background estimate:
 *  - dark spots: moles, blemishes, dust shadows (bg − lum in 12..60)
 *  - bright spots: whiteheads, bright dust specks, shiny highlights
 *    (lum − bg in 12..60)
 *
 * Previously only dark spots were detected, so Auto Retouch silently
 * ignored every bright blemish.
 *
 * Non-maximum suppression: candidates are accepted strongest-first and each
 * accepted spot suppresses weaker candidates closer than the background
 * estimation scale (they are part of the same local deviation, not a
 * distinct blemish). Previously a single flat-bottomed blob emitted one
 * candidate per scan-grid point and ate the whole maxSpots budget with
 * overlapping heal stamps on the same blob.
 */
data class BlemishSpot(
    /** Center in 0..1 UV. */
    val x: Float,
    /** Center in 0..1 UV. */
    val y: Float,
    /** Radius as a fraction of image width. */
    val radius: Float,
)

object BlemishDetect {

    /** Minimum separation between two accepted spots, in working-scale px.
     *  Matches the background-estimation blur diameter: deviations closer
     *  than this cannot be meaningfully distinct blemishes. */
    private const val MIN_SEPARATION_PX = 12f

    /**
     * @param pixels ARGB pixels, exactly [w]x[h].
     * @param maxSpots cap on returned spots (strongest first).
     * @param thresholdScale scales the |diff| acceptance band (12..60):
     *   > 1 = stricter (noisy / harsh-contrast photos where grain mimics
     *   spots), < 1 = more sensitive (clean soft light, subtle blemishes).
     *   From [RetouchIntel] adaptive profiling.
     * @return blemishes in 0..1 UV coordinates.
     */
    fun detect(
        pixels: IntArray, w: Int, h: Int,
        maxSpots: Int = 25,
        thresholdScale: Float = 1f,
    ): List<BlemishSpot> {
        if (w < 16 || h < 16 || pixels.size < w * h || maxSpots <= 0) return emptyList()
        val scale = thresholdScale.coerceIn(0.5f, 2.5f)
        val loThresh = 12f * scale
        val hiThresh = 60f * scale

        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            lum[i] = 0.2126f * ((p shr 16) and 0xFF) +
                0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)
        }
        // Local background estimate (radius ~8px at the working scale).
        val bg = boxBlur(lum, w, h, 8)

        data class Candidate(val x: Int, val y: Int, val score: Float)
        val candidates = mutableListOf<Candidate>()

        // Scan for spots: |diff| in loThresh..hiThresh vs local background,
        // and a strict local extremum in the 5x5 neighborhood
        // (dark -> minimum, bright -> maximum).
        val step = 4
        var y = 8
        while (y < h - 8) {
            var x = 8
            while (x < w - 8) {
                val i = y * w + x
                val diff = bg[i] - lum[i] // > 0: darker than background
                if (diff >= loThresh && diff <= hiThresh) {
                    var isMin = true
                    outer@ for (dy in -2..2) {
                        for (dx in -2..2) {
                            if (dx == 0 && dy == 0) continue
                            if (lum[(y + dy) * w + (x + dx)] < lum[i] - 1f) {
                                isMin = false; break@outer
                            }
                        }
                    }
                    if (isMin) candidates.add(Candidate(x, y, diff))
                } else if (diff <= -loThresh && diff >= -hiThresh) {
                    var isMax = true
                    outer@ for (dy in -2..2) {
                        for (dx in -2..2) {
                            if (dx == 0 && dy == 0) continue
                            if (lum[(y + dy) * w + (x + dx)] > lum[i] + 1f) {
                                isMax = false; break@outer
                            }
                        }
                    }
                    if (isMax) candidates.add(Candidate(x, y, -diff))
                }
                x += step
            }
            y += step
        }

        // Strongest first; each accepted spot suppresses weaker candidates
        // inside its own footprint or the minimum separation — one blob,
        // one heal spot.
        val accepted = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (accepted.size >= maxSpots) break
            val rPx = spotRadiusPx(c.score, w)
            var suppressed = false
            for (a in accepted) {
                val dx = (c.x - a.x).toFloat()
                val dy = (c.y - a.y).toFloat()
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < max(rPx + spotRadiusPx(a.score, w), MIN_SEPARATION_PX)) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) accepted.add(c)
        }

        return accepted.map {
            val rPx = spotRadiusPx(it.score, w)
            BlemishSpot(
                x = it.x.toFloat() / w,
                y = it.y.toFloat() / h,
                radius = (rPx / w).coerceIn(0.008f, 0.03f),
            )
        }
    }

    /** Heal-spot radius in working-scale px from the detection score. */
    private fun spotRadiusPx(score: Float, w: Int): Float =
        (0.012f + score / 60f * 0.015f) * w

    // Sliding-window separable box blur (O(w*h), replicate edges) — the old
    // naive 17-tap x 2-pass version did ~34 float ops/px here and was the
    // main cost of the Auto Retouch "Detecting…" phase. Same output semantics.
    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        FastBlur.boxBlurFloat(src, w, h, radius)
}
