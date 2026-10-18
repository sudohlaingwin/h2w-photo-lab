package com.lumora.photoeditor.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Automatic straightening via horizon detection.
 *
 * Deterministic pixel math, no machine learning, no AI: a Sobel edge map is
 * built from a small downscaled luma image, near-horizontal edge lines vote
 * (magnitude-squared weighted) into a 1-degree orientation histogram, and
 * the dominant peak — parabolically interpolated for sub-degree precision —
 * is taken as the horizon angle. When no confident horizon exists the
 * detector returns null and the UI must say so honestly instead of guessing.
 *
 * [detectHorizon] is pure Kotlin (no android.* imports) so it is directly
 * harness-testable; see hidden_files/autostraight-harness/.
 */
object Straighten {

    /** A detected horizon. [angleDeg] is the undirected line angle in y-down
     *  source-image degrees, folded to (-45, 45]. [confidence] is 0..1. */
    data class AutoLevel(val angleDeg: Float, val confidence: Float)

    /** Rotation that levels a horizon detected at [angleDeg] in source coords. */
    data class LevelRotation(val rotate: Float, val quarterTurns: Int)

    private const val MAX_SIDE = 240
    private const val WINDOW_DEG = 45f
    private const val BINS = 90 // 1-degree bins over -45..45

    fun detectHorizon(px: IntArray, w: Int, h: Int): AutoLevel? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        // Downscale to a small working image (speed; horizons are coarse features).
        val scale = min(1f, MAX_SIDE / max(w, h).toFloat())
        val sw = max(8, (w * scale).toInt())
        val sh = max(8, (h * scale).toInt())
        val luma = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val sy = min(h - 1, (y / scale).toInt())
            for (x in 0 until sw) {
                val sx = min(w - 1, (x / scale).toInt())
                val p = px[sy * w + sx]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                luma[y * sw + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        // First pass: mean gradient magnitude for an adaptive edge threshold.
        var magSum = 0f
        var magCount = 0
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                magSum += sobelMag(luma, sw, x, y)
                magCount++
            }
        }
        if (magCount == 0) return null
        val threshold = max(15f, 2f * magSum / magCount)
        // Light 3x3 blur before Sobel: phone photos carry sensor noise, and
        // softening makes the gradient direction follow the true edge line
        // instead of rasterization stair-steps.
        val blur = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val y0 = max(0, y - 1); val y1 = min(sh - 1, y + 1)
            for (x in 0 until sw) {
                val x0 = max(0, x - 1); val x1 = min(sw - 1, x + 1)
                blur[y * sw + x] = (luma[y0 * sw + x0] + 2f * luma[y0 * sw + x] + luma[y0 * sw + x1] +
                    2f * luma[y * sw + x0] + 4f * luma[y * sw + x] + 2f * luma[y * sw + x1] +
                    luma[y1 * sw + x0] + 2f * luma[y1 * sw + x] + luma[y1 * sw + x1]) / 16f
            }
        }
        // Second pass: vote near-horizontal edge lines into the histogram.
        val bins = FloatArray(BINS)
        var windowEdge = 0f
        var totalEdge = 0f
        var edgeCount = 0
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val gx = sobelGx(blur, sw, x, y)
                val gy = sobelGy(blur, sw, x, y)
                val mag = abs(gx) + abs(gy)
                if (mag < threshold) continue
                // Edge line runs perpendicular to the gradient; fold undirected.
                var a = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat() - 90f
                a = ((a + 90f) % 180f + 180f) % 180f - 90f
                val weight = mag * mag
                totalEdge += weight
                if (abs(a) <= WINDOW_DEG) {
                    bins[(a + WINDOW_DEG).toInt().coerceIn(0, BINS - 1)] += weight
                    windowEdge += weight
                    edgeCount++
                }
            }
        }
        if (edgeCount < 150) return null
        // Light 3-bin smoothing (circular inside the window) to kill bin jitter.
        val smooth = FloatArray(BINS)
        for (i in 0 until BINS) {
            smooth[i] = (bins[(i + BINS - 1) % BINS] + 2f * bins[i] + bins[(i + 1) % BINS]) / 4f
        }
        var peak = 0
        for (i in 1 until BINS) if (smooth[i] > smooth[peak]) peak = i
        val peakW = smooth[peak]
        val meanBin = windowEdge / BINS
        if (meanBin <= 0f) return null
        // Confidence gates: the peak must clearly dominate the window, and the
        // near-horizontal window must hold a real share of all edge energy —
        // otherwise this is a busy texture, not a horizon.
        if (peakW < 4f * meanBin) return null
        if (windowEdge < 0.15f * totalEdge) return null
        // Parabolic interpolation for sub-degree precision.
        val l = smooth[(peak + BINS - 1) % BINS]
        val c = smooth[peak]
        val r = smooth[(peak + 1) % BINS]
        val denom = l - 2f * c + r
        val delta = if (denom < -1e-6f) 0.5f * (l - r) / denom else 0f
        val angle = (peak + delta - WINDOW_DEG).coerceIn(-WINDOW_DEG, WINDOW_DEG)
        val confidence = (peakW / (8f * meanBin)).coerceIn(0f, 1f)
        return AutoLevel(angle, confidence)
    }

    /**
     * Rotation that levels a horizon detected at [thetaSrcDeg] in source
     * coordinates, accounting for flips. Displayed line angle is
     * theta + totalRotate and flips negate the source angle (mod 180° for
     * undirected lines), so replacing the rotation with -theta levels it —
     * the same math as the guided straighten, folded into quarter turns +
     * the ±45° slider residual.
     */
    fun levelRotation(thetaSrcDeg: Float, flipH: Boolean, flipV: Boolean): LevelRotation {
        var theta = thetaSrcDeg
        if (flipH) theta = -theta
        if (flipV) theta = -theta
        theta = ((theta + 90f) % 180f + 180f) % 180f - 90f
        val k = (-theta / 90f).roundToInt()
        val rotate = (-theta - 90f * k).coerceIn(-45f, 45f)
        return LevelRotation(rotate, k)
    }

    private fun sobelGx(l: FloatArray, sw: Int, x: Int, y: Int): Float {
        val i = y * sw + x
        return (l[i - sw + 1] + 2f * l[i + 1] + l[i + sw + 1]) -
            (l[i - sw - 1] + 2f * l[i - 1] + l[i + sw - 1])
    }

    private fun sobelGy(l: FloatArray, sw: Int, x: Int, y: Int): Float {
        val i = y * sw + x
        return (l[i + sw - 1] + 2f * l[i + sw] + l[i + sw + 1]) -
            (l[i - sw - 1] + 2f * l[i - sw] + l[i - sw + 1])
    }

    private fun sobelMag(l: FloatArray, sw: Int, x: Int, y: Int): Float =
        abs(sobelGx(l, sw, x, y)) + abs(sobelGy(l, sw, x, y))
}
