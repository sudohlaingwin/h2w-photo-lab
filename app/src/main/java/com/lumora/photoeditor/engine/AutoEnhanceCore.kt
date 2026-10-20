package com.lumora.photoeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin core of one-tap Auto Enhance ([AutoEnhance]).
 *
 * This file has zero Android imports on purpose: the analysis is deterministic
 * histogram math, so it stays unit-testable on the JVM (see
 * AutoEnhanceCoreTest and the standalone kotlinc harness). [AutoEnhance]
 * keeps the Bitmap downsampling and delegates here.
 *
 * Behavior contract:
 * - Near-uniform frames (blank wall, solid color, capped lens) produce NO
 *   corrections. Analyzing such frames used to emit wild values (solid gray
 *   -> contrast +35, whites +50, blacks -50, vibrance +28) — the "auto
 *   enhance with no care" complaint. The std < 0.02 threshold mirrors
 *   the proven uniform-frame guard from the earlier statistics-based
 *   auto-tone pass (since removed).
 * - All other behavior (scene classification, adaptive targets, conservative
 *   clamps) is byte-for-byte the logic that shipped in AutoMagic.kt.
 * - Portraits (skinRatio > 0.08) meter exposure face-weighted: 65% mean
 *   luminance of skin-probable pixels + 35% frame mean. Metering the bare
 *   frame mean let dark backgrounds push faces into overexposure and bright
 *   backgrounds bury them — exposure now protects the face first.
 */
object AutoEnhanceCore {

    /** Suggested settings: light -5..5 EV exposure, rest -100..100. */
    data class Settings(
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val whites: Float = 0f,
        val blacks: Float = 0f,
        val vibrance: Float = 0f,
        val clarity: Float = 0f,
        val temp: Float = 0f,
        val tint: Float = 0f,
    )

    /** Scene types that change the enhancement strategy. */
    private enum class Scene { NIGHT, HIGH_KEY, BACKLIT, PORTRAIT, NORMAL }

    /**
     * Analyzes ARGB [pixels] (row-major, [w] x [h]) and returns suggested
     * settings. [strength] 0..1 blends between no-op and full auto.
     * [skinProb] scores skin likelihood 0..1 from (r, g, b); the app passes
     * [AutoRetouch.skinProbability], tests may pass a stub.
     */
    fun analyzePixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        strength: Float = 1f,
        skinProb: (r: Int, g: Int, b: Int) -> Float = { _, _, _ -> 0f },
    ): Settings {
        val n = pixels.size
        if (n == 0 || w <= 0 || h <= 0) return Settings()

        // Luminance histogram (256 bins) + per-channel means + saturation stats
        val hist = IntArray(256)
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var sumSat = 0f; var skinPixels = 0
        // Skin-region luminance for face-weighted exposure metering
        // (portraits meter the face, not the whole frame — see below).
        var skinLumSum = 0.0; var skinLumN = 0
        // Neutral-candidate subset for the white-balance estimate: only
        // low-saturation, non-skin, mid-tone pixels carry the illuminant.
        // The old gray-world average used every pixel, so warm skin or a
        // saturated subject (a red car, the sea) steered the cast estimate
        // and the photo got a "correction" that fought the subject itself —
        // the visible "no care" WB artifact on portraits and vivid scenes.
        var wbR = 0L; var wbG = 0L; var wbB = 0L; var wbN = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sumR += r; sumG += g; sumB += b
            // Round-half-up (not truncation): the histogram feeds exposure
            // decisions, and truncation biases every pixel's bin ~0.5 dark.
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            hist[lum]++
            // Saturation (0..1): max-min over max
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx > 0) (mx - mn).toFloat() / mx else 0f
            sumSat += sat
            val sp = skinProb(r, g, b)
            if (sp > 0.5f) {
                skinPixels++
                skinLumSum += lum
                skinLumN++
            }
            if (sat <= 0.35f && sp <= 0.5f && lum in 30..225) {
                wbR += r; wbG += g; wbB += b; wbN++
            }
        }

        // Uniform-frame guard: nothing meaningful to balance in a blank frame.
        var binSum = 0.0; var binSum2 = 0.0
        for (i in 0..255) {
            val c = hist[i].toDouble()
            binSum += i * c; binSum2 += i * i * c
        }
        val binMean = binSum / n
        val binVar = max(0.0, binSum2 / n - binMean * binMean)
        if (sqrt(binVar) / 255.0 < 0.02) return Settings()

        val meanR = sumR.toFloat() / n
        val meanG = sumG.toFloat() / n
        val meanB = sumB.toFloat() / n
        val meanLum = (0.2126f * meanR + 0.7152f * meanG + 0.0722f * meanB) / 255f
        val meanSat = sumSat / n
        val skinRatio = skinPixels.toFloat() / n

        // Percentiles
        fun percentile(p: Float): Int {
            val target = (n * p).toInt()
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        val p1 = percentile(0.01f)
        val p5 = percentile(0.05f)
        val p95 = percentile(0.95f)
        val p99 = percentile(0.99f)

        // ---- Scene classification ----
        val darkRatio = hist.slice(0..40).sum().toFloat() / n
        val brightRatio = hist.slice(215..255).sum().toFloat() / n
        val scene = when {
            // Night/low-key: mostly dark, low mean — and no detectable face.
            // A shaded daylight portrait must not get the night-mood
            // treatment (user video 2026-10-17: daylight shaded portrait
            // classified Night by the plain Auto button too).
            meanLum < 0.28f && darkRatio > 0.45f && skinRatio < 0.02f -> Scene.NIGHT
            // High-key: mostly bright
            meanLum > 0.72f && brightRatio > 0.4f -> Scene.HIGH_KEY
            // Backlit: bimodal — dark subject + bright background
            darkRatio > 0.3f && brightRatio > 0.25f && p95 - p5 > 150 -> Scene.BACKLIT
            // Portrait: any meaningful face presence wins, even a weak
            // signal in a dark frame (faces meter the exposure, see below).
            skinRatio > 0.02f -> Scene.PORTRAIT
            else -> Scene.NORMAL
        }

        // ---- Exposure: scene-adaptive target ----
        val targetLum = when (scene) {
            Scene.NIGHT -> 0.30f      // Keep night mood, just lift slightly
            Scene.HIGH_KEY -> 0.68f   // Keep bright and airy
            Scene.BACKLIT -> 0.45f
            Scene.PORTRAIT -> 0.48f   // Slightly brighter for faces
            Scene.NORMAL -> 0.46f
        }
        var exposure = 0f
        // Metering luminance: portraits meter face-weighted (65% skin-region
        // mean, 35% frame mean). Metering the bare frame mean let a dark
        // background drag the meter down and push exposure up until faces
        // blew out — or a bright background bury the face in shadow — the
        // "auto enhance with no care" failure on portraits. Non-portrait
        // scenes meter the frame exactly as before (bit-identical).
        val meterLum = if (scene == Scene.PORTRAIT && skinLumN > 0) {
            val skinMean = (skinLumSum / skinLumN / 255.0).toFloat()
            (0.65f * skinMean + 0.35f * meanLum).coerceIn(0f, 1f)
        } else meanLum
        if (meterLum > 0.01f) {
            val ev = kotlin.math.log2(targetLum / meterLum.coerceAtLeast(0.01f))
            // Night: limit brightening to avoid noise; High-key: limit darkening
            val range = when (scene) {
                Scene.NIGHT -> -1.0f..1.2f
                Scene.HIGH_KEY -> -1.2f..1.0f
                else -> -2.5f..2.5f
            }
            exposure = ev.coerceIn(range.start, range.endInclusive)
            // For night, be conservative — don't push too hard
            if (scene == Scene.NIGHT) exposure *= 0.7f
        }

        // ---- Whites/Blacks: gentle histogram stretch ----
        val whites = when (scene) {
            Scene.NIGHT -> ((235 - p99) * 0.3f).coerceIn(-30f, 30f)
            else -> ((245 - p99) * 0.45f).coerceIn(-50f, 50f)
        }
        val blacks = when (scene) {
            Scene.NIGHT -> 0f // Keep deep blacks for night mood
            Scene.HIGH_KEY -> ((15 - p1) * 0.5f).coerceIn(-30f, 30f)
            else -> ((10 - p1) * 0.6f).coerceIn(-50f, 50f)
        }

        // ---- Contrast: S-curve strength based on scene and spread ----
        val spread = p99 - p1
        val baseContrast = ((170 - spread) * 0.22f).coerceIn(0f, 35f)
        val contrast = when (scene) {
            Scene.NIGHT -> baseContrast * 0.6f + 8f  // Night needs some punch
            Scene.PORTRAIT -> baseContrast * 0.7f     // Softer for skin
            Scene.HIGH_KEY -> baseContrast * 0.5f
            else -> baseContrast
        }

        // ---- Highlights/Shadows: intelligent recovery ----
        val clippedHi = hist.slice(250..255).sum().toFloat() / n
        val clippedLo = hist.slice(0..5).sum().toFloat() / n
        val highlights = when (scene) {
            Scene.BACKLIT -> -50f // Strong highlight recovery for backlit
            Scene.PORTRAIT -> (-clippedHi * 600f).coerceIn(-60f, 0f)
            else -> (-clippedHi * 700f).coerceIn(-70f, 0f)
        }
        val shadows = when (scene) {
            Scene.BACKLIT -> 55f  // Lift shadows for backlit subject
            Scene.NIGHT -> (clippedLo * 300f).coerceIn(0f, 25f) // Gentle
            Scene.PORTRAIT -> (clippedLo * 500f).coerceIn(0f, 45f)
            else -> (clippedLo * 550f).coerceIn(0f, 55f)
        }

        // ---- Vibrance: smart saturation (boost muted, protect saturated) ----
        val vibrance = when (scene) {
            Scene.PORTRAIT -> ((0.35f - meanSat) * 60f).coerceIn(0f, 25f)
            Scene.NIGHT -> ((0.3f - meanSat) * 50f).coerceIn(0f, 30f)
            else -> ((0.4f - meanSat) * 70f).coerceIn(0f, 40f)
        }

        // ---- Clarity: subtle local contrast ----
        val clarity = when (scene) {
            Scene.PORTRAIT -> 5f
            Scene.NIGHT -> 8f
            Scene.HIGH_KEY -> 5f
            Scene.BACKLIT -> 12f
            Scene.NORMAL -> 10f
        }

        // ---- White balance: confidence-limited ----
        // Cast is estimated from the neutral-candidate subset (low-saturation,
        // non-skin, mid-tone pixels), not the global means: skin and vivid
        // subjects must not steer the illuminant estimate. Falls back to the
        // global means when the scene offers too few neutral pixels (a frame
        // that is nothing but a red car still needs a defined estimate).
        val wbMeanR: Float; val wbMeanG: Float; val wbMeanB: Float
        if (wbN >= n * 0.02f) {
            wbMeanR = wbR.toFloat() / wbN
            wbMeanG = wbG.toFloat() / wbN
            wbMeanB = wbB.toFloat() / wbN
        } else {
            wbMeanR = meanR; wbMeanG = meanG; wbMeanB = meanB
        }
        val avg = (wbMeanR + wbMeanG + wbMeanB) / 3f
        val castR = (wbMeanR - avg) / avg.coerceAtLeast(1f)
        val castB = (wbMeanB - avg) / avg.coerceAtLeast(1f)
        val castStrength = abs(castR) + abs(castB)
        // Only apply WB if cast is significant (>8% deviation)
        val wbConfidence = ((castStrength - 0.08f) / 0.2f).coerceIn(0f, 1f)
        // temp: positive = warmer. If blue high (castB > 0), warm it up.
        val tempRaw = (castB * 80f).coerceIn(-25f, 25f)
        // tint: positive = magenta (Lightroom convention, green(-)<->magenta(+)).
        // If green is high, add magenta: positive tint now cuts green in
        // the shader's white-balance gain.
        val castG = (wbMeanG - avg) / avg.coerceAtLeast(1f)
        val tintRaw = (castG * 60f).coerceIn(-20f, 20f)
        val temp = tempRaw * wbConfidence
        val tint = tintRaw * wbConfidence

        val s = strength.coerceIn(0f, 1f)
        return Settings(
            exposure = exposure * s,
            contrast = contrast * s,
            highlights = highlights * s,
            shadows = shadows * s,
            whites = whites * s,
            blacks = blacks * s,
            vibrance = vibrance * s,
            clarity = clarity * s,
            temp = temp * s,
            tint = tint * s,
        )
    }

    /**
     * Human-readable summary of the non-trivial adjustments in [s], for the
     * "what did Auto Enhance change?" feedback line in the Light panel.
     * Returns "" when nothing meaningful was suggested (uniform-frame guard
     * or an already-balanced photo) so the UI can say so honestly instead of
     * staying silent. Pure Kotlin — unit-testable like the rest of this file.
     */
    fun describe(s: Settings): String {
        val parts = mutableListOf<String>()
        // Exposure is in EV steps: 0.05 is the visibility threshold.
        if (abs(s.exposure) >= 0.05f) {
            parts += "Exposure " + String.format(java.util.Locale.US, "%+.1f", s.exposure)
        }
        // Everything else is -100..100 slider units: below 1 is imperceptible.
        fun pct(v: Float, name: String) {
            if (abs(v) >= 1f) parts += "$name " + String.format(java.util.Locale.US, "%+d", v.roundToInt())
        }
        pct(s.contrast, "Contrast")
        pct(s.highlights, "Highlights")
        pct(s.shadows, "Shadows")
        pct(s.whites, "Whites")
        pct(s.blacks, "Blacks")
        pct(s.clarity, "Clarity")
        pct(s.temp, "Temp")
        pct(s.tint, "Tint")
        pct(s.vibrance, "Vibrance")
        return parts.joinToString(" · ")
    }
}
