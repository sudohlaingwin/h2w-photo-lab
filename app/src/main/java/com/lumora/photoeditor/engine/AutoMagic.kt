package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One-tap Auto Enhance: scene-aware analysis that computes Lightroom-style
 * auto settings (exposure, contrast, highlights, shadows, whites, blacks,
 * vibrance, white balance). Pure CPU, no ML — deterministic.
 *
 * v2: Scene classification (night/low-key, high-key, backlit, portrait,
 * normal) drives adaptive targets. Night photos are NOT brightened to
 * daylight; backlit subjects get shadow rescue; portraits protect skin tones.
 */
/**
 * Suggested auto-enhance settings (public stable name; implementation in
 * [AutoEnhanceCore] so the analysis stays JVM-testable).
 */
typealias AutoSettings = AutoEnhanceCore.Settings

object AutoEnhance {

    /**
     * Analyzes [bmp] (any size; internally downsamples) and returns
     * suggested settings. [strength] 0..1 blends between current and full auto.
     *
     * Near-uniform frames (blank wall, capped lens) intentionally return no
     * corrections — there is no meaningful "correct" exposure for them (see
     * [AutoEnhanceCore] for the guard). Previously they got wild corrections
     * (solid gray -> contrast +35, whites +50, blacks -50, vibrance +28).
     */
    fun analyze(bmp: Bitmap, strength: Float = 1f): AutoSettings {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return AutoSettings()

        // Downsample to max 256px for speed
        val scale = min(1f, 256f / max(w, h))
        val sw = max(1, (w * scale).toInt())
        val sh = max(1, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        return AutoEnhanceCore.analyzePixels(
            pixels, sw, sh, strength, AutoRetouch::skinProbability)
    }
}

/**
 * Auto Retouch: detects blemishes (small dark spots) and skin regions.
 * Returns detected blemish points (in 0..1 UV) for auto-healing, and a
 * skin mask bitmap for auto-smoothing.
 */
object AutoRetouch {

    data class Blemish(val x: Float, val y: Float, val radius: Float)

    /**
     * Detects blemishes: small dark OR bright spots with high local contrast.
     * Detection core lives in [BlemishDetect] (pure Kotlin, JVM-testable);
     * this wrapper only handles the Bitmap downsampling.
     * @param maxSpots cap on returned spots (strongest first)
     * @return list of blemishes in 0..1 UV coordinates
     */
    fun detectBlemishes(
        bmp: Bitmap,
        maxSpots: Int = 25,
        thresholdScale: Float = 1f,
    ): List<Blemish> {
        val w = bmp.width; val h = bmp.height
        if (w < 32 || h < 32) return emptyList()

        // Work at max 512px for speed
        val scale = min(1f, 512f / max(w, h))
        val sw = max(32, (w * scale).toInt())
        val sh = max(32, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        return BlemishDetect.detect(pixels, sw, sh, maxSpots, thresholdScale)
            .map { Blemish(x = it.x, y = it.y, radius = it.radius) }
    }

    /**
     * Builds a skin mask bitmap (white = skin, black = not skin).
     * Uses YCrCb skin-tone ellipsoid heuristic.
     * @param feather 0..1 softness of mask edges
     * @return a NEW bitmap. The caller owns it and must recycle() it when
     * done (after it has been persisted via [AiMaskStore.save], which
     * copies the pixels).
     */
    fun skinMask(bmp: Bitmap, feather: Float = 0.5f): Bitmap {
        val w = bmp.width; val h = bmp.height
        // Work at max 256px for the mask (upsampled by GPU)
        val scale = min(1f, 256f / max(w, h))
        val sw = max(16, (w * scale).toInt())
        val sh = max(16, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        val mask = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val maskPixels = ByteArray(sw * sh)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val skinness = skinProbability(r, g, b)
            maskPixels[i] = (skinness * 255).toInt().coerceIn(0, 255).toByte()
        }

        // Feathering blur — sliding-window separable (O(w*h), bit-identical to
        // the old naive (2r+1)^2 loop, which cost up to 169 int ops/px and
        // stalled the Auto Retouch "Detecting…" phase).
        if (feather > 0.01f) {
            val radius = (feather * 6).toInt().coerceIn(1, 6)
            FastBlur.boxBlurByte(maskPixels, sw, sh, radius).copyInto(maskPixels)
        }

        // ALPHA_8 bitmap from bytes
        val intPixels = IntArray(sw * sh) { i ->
            (maskPixels[i].toInt() and 0xFF) shl 24
        }
        mask.setPixels(intPixels, 0, sw, 0, 0, sw, sh)
        return mask
    }

    /** Under-eye dark circle candidate. */
    data class UnderEye(val x: Float, val y: Float, val radius: Float)

    /**
     * Detects under-eye dark circles: looks for horizontally-elongated dark
     * regions within skin areas in the upper-middle of the face.
     *
     * Pro approach: sample the skin mask to find the face region, then scan
     * for dark (low luminance relative to surrounding skin) patches that are
     * wider than tall — the signature of under-eye shadows.
     *
     * @return up to 2 candidates (left/right eye) in 0..1 UV.
     */
    fun detectUnderEyes(bmp: Bitmap): List<UnderEye> {
        val w = bmp.width; val h = bmp.height
        if (w < 64 || h < 64) return emptyList()

        val scale = min(1f, 256f / max(w, h))
        val sw = max(32, (w * scale).toInt())
        val sh = max(32, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        // Build luminance + skin maps
        val lum = FloatArray(sw * sh)
        val skin = FloatArray(sw * sh)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            skin[i] = skinProbability(r, g, b)
        }

        // Find the face: largest skin blob in upper 2/3. Simplified: use the
        // bounding box of strong skin pixels.
        var minX = sw; var maxX = 0; var minY = sh; var maxY = 0
        var skinCount = 0
        for (y in 0 until (sh * 0.7f).toInt()) {
            for (x in 0 until sw) {
                if (skin[y * sw + x] > 0.6f) {
                    skinCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        // Need a reasonable face-sized skin region
        if (skinCount < sw * sh * 0.03f) return emptyList()
        val faceW = maxX - minX + 1
        val faceH = maxY - minY + 1
        if (faceW < sw * 0.15f || faceH < sh * 0.15f) return emptyList()

        // Under-eyes live in the upper-middle of the face (roughly 35-55% down
        // from face top). Scan for dark horizontal bands.
        val eyeY0 = (minY + faceH * 0.35f).toInt()
        val eyeY1 = (minY + faceH * 0.55f).toInt()
        val candidates = mutableListOf<UnderEye>()

        // Split face into left/right halves, find darkest horizontal band in each
        for (half in 0..1) {
            val hx0 = if (half == 0) minX else (minX + maxX) / 2
            val hx1 = if (half == 0) (minX + maxX) / 2 else maxX
            var bestY = -1
            var bestDarkness = 0f
            // For each row, compute mean darkness vs surrounding skin
            for (y in eyeY0..eyeY1) {
                var darkSum = 0f; var count = 0
                var surroundSum = 0f; var surroundCount = 0
                for (x in hx0..hx1) {
                    val idx = y * sw + x
                    if (skin[idx] < 0.4f) continue
                    darkSum += lum[idx]; count++
                    // Surrounding: rows above and below
                    for (dy in listOf(-4, 4)) {
                        val ny = (y + dy).coerceIn(0, sh - 1)
                        val nidx = ny * sw + x
                        if (skin[nidx] > 0.4f) {
                            surroundSum += lum[nidx]; surroundCount++
                        }
                    }
                }
                if (count > 3 && surroundCount > 3) {
                    val darkness = (surroundSum / surroundCount) - (darkSum / count)
                    // Darker than surroundings by meaningful amount, and
                    // actually dark in absolute terms
                    if (darkness > 0.08f && darkSum / count < 0.55f && darkness > bestDarkness) {
                        bestDarkness = darkness
                        bestY = y
                    }
                }
            }
            if (bestY >= 0) {
                val cx = (hx0 + hx1) / 2f / sw
                val cy = bestY.toFloat() / sh
                val radius = (hx1 - hx0) * 0.35f / sw
                candidates.add(UnderEye(cx, cy, radius.coerceIn(0.02f, 0.08f)))
            }
        }
        return candidates
    }

    /**
     * Skin probability 0..1 using YCrCb heuristic.
     * Classic ranges: Cr 133-173, Cb 77-127, plus brightness constraints.
     */
    fun skinProbability(r: Int, g: Int, b: Int): Float {
        // Convert to YCrCb (ITU-R BT.601)
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
        val cb = 128f - 0.1687f * r - 0.3313f * g + 0.5f * b

        // Must be reasonably bright and not grayscale
        if (y < 40 || y > 250) return 0f
        val maxRg = max(r, g); val minRg = min(r, b)
        if (maxRg - minRg < 15) return 0f  // too gray

        // Elliptical distance in Cr-Cb space (center ~155, 110)
        val dCr = (cr - 155f) / 28f
        val dCb = (cb - 110f) / 22f
        val dist = sqrt(dCr * dCr + dCb * dCb)

        // Smooth falloff: 1.0 at center, 0.0 at dist >= 1.5
        return (1f - (dist / 1.5f)).coerceIn(0f, 1f).let {
            it * it * (3f - 2f * it)  // smoothstep
        }
    }
}

/**
 * Creative effects: light leak, sun flare, film dust, fade.
 * Applied as GPU-friendly overlays or parametric adjustments.
 */
object CreativeEffects {

    /** Light leak presets: position (0..1 UV) + color tint. */
    enum class LightLeak(
        val x: Float, val y: Float,
        val r: Float, val g: Float, val b: Float,
    ) {
        SUNSET(0.85f, 0.15f, 1.0f, 0.45f, 0.15f),
        GOLDEN(0.15f, 0.85f, 1.0f, 0.75f, 0.35f),
        ROSE(0.5f, 0.1f, 1.0f, 0.35f, 0.55f),
        TEAL(0.85f, 0.85f, 0.25f, 0.85f, 0.9f),
    }

    /**
     * Combined creative-effects overlay for the GPU pipeline: rgb carries the
     * light-leak glow at FULL intensity (the leak amount rides as the uFxLeak
     * uniform so slider drags never regenerate the bitmap), alpha carries the
     * film-dust specks (uFxDust uniform scales them). Returns null when the
     * effect is inactive so the caller can bind a dummy texture instead.
     * Pure deterministic math — same generators as the standalone overlays.
     */
    fun fxOverlay(w: Int, h: Int, fx: FxParams): Bitmap? {
        val fxc = fx.coerced()
        if (!fxc.isActive()) return null
        val pixels = IntArray(w * h) // transparent black
        if (fxc.leak != LightLeakStyle.NONE && fxc.leakAmount > 0f) {
            // Style names intentionally mirror LightLeak's.
            fillLeak(pixels, w, h, LightLeak.valueOf(fxc.leak.name), 1f, opaque = false)
        }
        if (fxc.dust > 0f) stampDust(pixels, w, h, fxc.dustSeed, fxc.dust / 100f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * Generates a light-leak overlay bitmap (additive).
     * @param w,h output size (use small, e.g. 256px, GPU upscales)
     * @param leak which leak preset
     * @param intensity 0..1
     */
    fun lightLeakOverlay(
        w: Int, h: Int,
        leak: LightLeak,
        intensity: Float,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        fillLeak(pixels, w, h, leak, intensity, opaque = true)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * Generates a film-dust/scratch overlay (sparse white/black specks).
     * @param seed random seed for reproducibility
     * @param density 0..1 amount of dust
     */
    fun filmDustOverlay(w: Int, h: Int, seed: Long, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) // transparent by default (0)
        stampDust(pixels, w, h, seed, density)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * Light-leak pixel fill. When [opaque] the leak is written with full
     * alpha (standalone overlay use); when false alpha stays 0 and only rgb
     * carries the additive glow (GPU fx-texture use — the shader reads leak
     * from rgb and dust from alpha, so the two must not share the channel).
     */
    private fun fillLeak(
        pixels: IntArray, w: Int, h: Int,
        leak: LightLeak, intensity: Float, opaque: Boolean,
    ) {
        val cx = leak.x * w
        val cy = leak.y * h
        val maxDist = sqrt((w * w + h * h).toFloat())
        val s = intensity.coerceIn(0f, 1f)
        val alpha = if (opaque) 255 shl 24 else 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy) / maxDist
                // Falloff: strong at corner, fading to center
                val falloff = (1f - dist * 1.4f).coerceIn(0f, 1f)
                val glow = falloff * falloff * s
                // Add some streaks (horizontal bands)
                val streak = (0.5f + 0.5f * kotlin.math.sin(y * 0.05f + leak.x * 10f)) * 0.15f * glow
                val r = (leak.r * (glow + streak) * 255).toInt().coerceIn(0, 255)
                val g = (leak.g * (glow + streak) * 255).toInt().coerceIn(0, 255)
                val b = (leak.b * (glow + streak) * 255).toInt().coerceIn(0, 255)
                pixels[y * w + x] = alpha or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Stamps film-dust specks + scratches into [pixels] (alpha channel). */
    private fun stampDust(pixels: IntArray, w: Int, h: Int, seed: Long, density: Float) {
        val rnd = java.util.Random(seed)
        val count = (w * h * density * 0.002f).toInt().coerceAtLeast(1)
        repeat(count) {
            val x = rnd.nextInt(w)
            val y = rnd.nextInt(h)
            val size = if (rnd.nextFloat() < 0.1f) 2 else 1
            val white = rnd.nextFloat() < 0.7f
            val alpha = (rnd.nextFloat() * 120 + 40).toInt()
            val color = if (white) {
                (alpha shl 24) or (255 shl 16) or (255 shl 8) or 255
            } else {
                (alpha shl 24) // black speck
            }
            for (dy in 0 until size) {
                for (dx in 0 until size) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    pixels[ny * w + nx] = color
                }
            }
        }
        // A few vertical scratches
        val scratches = (density * 3).toInt()
        repeat(scratches) {
            val x = rnd.nextInt(w)
            val alpha = (rnd.nextFloat() * 60 + 20).toInt()
            val white = rnd.nextFloat() < 0.5f
            for (y in 0 until h step 2) {
                if (rnd.nextFloat() < 0.7f) {
                    val c = if (white) {
                        (alpha shl 24) or (255 shl 16) or (255 shl 8) or 255
                    } else (alpha shl 24)
                    pixels[y * w + x] = c
                }
            }
        }
    }
}

/**
 * One-click Auto Mask: analyzes the image and suggests intelligent masks.
 *
 * Pro-level "look at the photo" logic:
 * - Portrait (significant skin): suggests a Skin mask for selective skin work
 *   and a Subject mask (skin + center-weighted) for subject pop.
 * - Landscape (sky in top region): suggests a Sky mask for sky adjustments.
 *
 * All detectors are pure Kotlin on downscaled pixels — fast enough for
 * one-tap use (<300ms on 512px).
 */
object AutoMask {

    /** Result of analyzing one image for auto-mask suggestions. */
    data class Analysis(
        /** 0..1 fraction of image that is skin-like. */
        val skinRatio: Float,
        /** 0..1 fraction of top-third that is sky-like. */
        val skyRatio: Float,
        /** True if this looks like a portrait (skin prominent, centered). */
        val isPortrait: Boolean,
        /** True if significant sky in the upper region. */
        val hasSky: Boolean,
    )

    /**
     * Analyzes [bmp] and returns what masks make sense.
     * Works on a 256px downscale for speed.
     */
    fun analyze(bmp: Bitmap): Analysis {
        val w = bmp.width; val h = bmp.height
        if (w < 32 || h < 32) return Analysis(0f, 0f, false, false)

        val scale = min(1f, 256f / max(w, h))
        val sw = max(16, (w * scale).toInt())
        val sh = max(16, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        var skinCount = 0
        var skinCenterCount = 0
        var skyCount = 0
        val topRows = (sh * 0.4f).toInt().coerceAtLeast(1)
        var topTotal = 0

        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val p = pixels[y * sw + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // Skin check
                if (AutoRetouch.skinProbability(r, g, b) > 0.5f) {
                    skinCount++
                    // Center-weighted: portraits have subject near center
                    val dx = (x - sw / 2f) / (sw / 2f)
                    val dy = (y - sh / 2f) / (sh / 2f)
                    if (dx * dx + dy * dy < 1.0f) skinCenterCount++
                }

                // Sky check: top 40%, blue-dominant and reasonably saturated
                if (y < topRows) {
                    topTotal++
                    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                    if (b > r && b > g && (mx - mn) > 25 && b > 100) {
                        skyCount++
                    }
                }
            }
        }

        val total = (sw * sh).toFloat()
        val skinRatio = skinCount / total
        val skyRatio = if (topTotal > 0) skyCount.toFloat() / topTotal else 0f
        // Portrait: meaningful skin that's concentrated near center
        val isPortrait = skinRatio > 0.06f &&
            skinCenterCount.toFloat() / max(1, skinCount) > 0.55f
        val hasSky = skyRatio > 0.18f

        return Analysis(skinRatio, skyRatio, isPortrait, hasSky)
    }

    /**
     * Builds a sky mask bitmap (white = sky). Uses blue-dominance heuristic
     * on the top 60% with vertical falloff.
     */
    fun skyMask(bmp: Bitmap, feather: Float = 0.5f): Bitmap {
        val w = bmp.width; val h = bmp.height
        val scale = min(1f, 256f / max(w, h))
        val sw = max(16, (w * scale).toInt())
        val sh = max(16, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        val mask = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val mp = ByteArray(sw * sh)
        for (y in 0 until sh) {
            // Vertical falloff: full strength at top, zero below 65%
            val vWeight = (1f - (y.toFloat() / sh) / 0.65f).coerceIn(0f, 1f)
            for (x in 0 until sw) {
                val p = pixels[y * sw + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val skyLike = if (b > r && b > g && (mx - mn) > 20 && b > 90) {
                    ((mx - mn).toFloat() / 120f).coerceIn(0f, 1f)
                } else 0f
                mp[y * sw + x] = (skyLike * vWeight * 255).toInt().coerceIn(0, 255).toByte()
            }
        }
        // Feather with box blur
        if (feather > 0.01f) {
            val radius = (feather * 5).toInt().coerceIn(1, 5)
            val blurred = ByteArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                var sum = 0; var cnt = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, sw - 1)
                    val ny = (y + dy).coerceIn(0, sh - 1)
                    sum += mp[ny * sw + nx].toInt() and 0xFF; cnt++
                }
                blurred[y * sw + x] = (sum / cnt).toByte()
            }
            mask.setPixels(IntArray(sw * sh) { i -> (blurred[i].toInt() and 0xFF) shl 24 },
                0, sw, 0, 0, sw, sh)
        } else {
            mask.setPixels(IntArray(sw * sh) { i -> (mp[i].toInt() and 0xFF) shl 24 },
                0, sw, 0, 0, sw, sh)
        }
        return mask
    }

    /**
     * Builds a soft subject mask bitmap (white = subject) with the
     * classical-CV [SubjectSelect] engine. Returns null when no confident
     * subject is found — callers must surface an honest "no subject found"
     * message rather than a wrong mask.
     *
     * The bitmap is small (<= 192px); the mask pipeline upscales it at
     * render time, same as [skyMask].
     */
    fun subjectMask(bmp: Bitmap, feather: Float = 0.5f): Bitmap? {
        val w = bmp.width; val h = bmp.height
        if (w < 32 || h < 32) return null
        val scale = min(1f, 192f / max(w, h))
        val sw = max(24, (w * scale).toInt())
        val sh = max(24, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        val sel = try {
            SubjectSelect.select(pixels, sw, sh)
        } catch (t: Throwable) {
            null
        } ?: return null
        return alphaMaskBitmap(sel, sw, sh, feather)
    }

    /**
     * Packs a 0..1 float mask into an ALPHA_8 bitmap with an optional
     * feather (box blur). Shared by the auto-mask builders.
     */
    private fun alphaMaskBitmap(
        alpha: FloatArray, sw: Int, sh: Int, feather: Float,
    ): Bitmap {
        val mask = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val mp = ByteArray(sw * sh)
        for (i in alpha.indices) {
            mp[i] = (alpha[i].coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255).toByte()
        }
        if (feather > 0.01f) {
            val radius = (feather * 5).toInt().coerceIn(1, 5)
            val blurred = ByteArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                var sum = 0; var cnt = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, sw - 1)
                    val ny = (y + dy).coerceIn(0, sh - 1)
                    sum += mp[ny * sw + nx].toInt() and 0xFF; cnt++
                }
                blurred[y * sw + x] = (sum / cnt).toByte()
            }
            mask.setPixels(IntArray(sw * sh) { i -> (blurred[i].toInt() and 0xFF) shl 24 },
                0, sw, 0, 0, sw, sh)
        } else {
            mask.setPixels(IntArray(sw * sh) { i -> (mp[i].toInt() and 0xFF) shl 24 },
                0, sw, 0, 0, sw, sh)
        }
        return mask
    }
}

/**
 * RetouchIntel — adaptive photo-condition profiler for Photoshop-grade
 * one-tap retouch.
 *
 * A professional retoucher assesses the photo BEFORE touching it: Is it a
 * noisy high-ISO shot? Harsh midday contrast? Soft studio light? Low light?
 * Each condition demands different retouch decisions — aggressive smoothing
 * ruins clean studio skin, while gentle settings leave high-ISO noise
 * untouched, and blemish detection tuned for soft light fires false
 * positives on harsh-contrast texture.
 *
 * This profiler measures those conditions from the pixels and derives the
 * adaptive parameters Auto Retouch uses. It is heuristic intelligence
 * (careful image analysis + encoded retoucher judgment), not a trained
 * neural network — it adapts per-photo instead of using one fixed recipe.
 */
object RetouchIntel {

    /** Photo condition categories, like a retoucher's first assessment. */
    enum class Condition {
        /** Clean, soft light — preserve pores, catch subtle blemishes. */
        CLEAN,
        /** Everyday photo — balanced defaults. */
        NORMAL,
        /** High-ISO / heavy grain — stronger smoothing justified. */
        NOISY,
        /** Dark scene — conservative brightening, noise-aware detection. */
        LOW_LIGHT,
        /** Harsh light, deep shadows — higher detection thresholds. */
        HIGH_CONTRAST,
    }

    /**
     * Measured photo profile. All 0..1.
     * @param noiseLevel high-frequency grain (0 = clean, 1 = very noisy)
     * @param contrast global RMS contrast
     * @param brightness mean luminance
     */
    data class PhotoProfile(
        val noiseLevel: Float,
        val contrast: Float,
        val brightness: Float,
        val condition: Condition,
    )

    /** Adaptive retouch recipe derived from a [PhotoProfile]. */
    data class RetouchRecipe(
        /** Multiplier on blemish detection thresholds (higher = stricter). */
        val blemishThresholdScale: Float,
        /** Skin-softening clarity (negative). */
        val skinClarity: Float,
        /** Skin-softening sharpness reduction (negative). */
        val skinSharpness: Float,
        /** Heal spot strength 0..1. */
        val healStrength: Float,
        /** Under-eye brightening strength 0..1. */
        val underEyeStrength: Float,
        /** Max blemish spots to heal. */
        val maxBlemishes: Int,
    )

    /**
     * Analyzes the photo at small scale (~160px). Fast: single pass.
     */
    fun analyze(bmp: Bitmap): PhotoProfile {
        val w = bmp.width; val h = bmp.height
        if (w < 32 || h < 32) {
            return PhotoProfile(0.3f, 0.4f, 0.5f, Condition.NORMAL)
        }
        val scale = min(1f, 160f / max(w, h))
        val sw = max(32, (w * scale).toInt())
        val sh = max(32, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small != bmp) small.recycle()

        val lum = FloatArray(sw * sh)
        var sum = 0.0
        for (i in pixels.indices) {
            val p = pixels[i]
            val l = 0.2126 * ((p shr 16) and 0xFF) +
                    0.7152 * ((p shr 8) and 0xFF) +
                    0.0722 * (p and 0xFF)
            lum[i] = l.toFloat()
            sum += l
        }
        val mean = (sum / lum.size).toFloat()

        // Contrast: RMS deviation from mean, normalized.
        var varSum = 0.0
        for (l in lum) { val d = l - mean; varSum += d * d }
        val contrast = (kotlin.math.sqrt(varSum / lum.size) / 90.0).coerceIn(0.0, 1.0)

        // Noise: mean absolute Laplacian response. Flat areas of a clean
        // photo give near-zero; grain/noise gives high response. Normalized
        // so typical phone photos land ~0.15-0.45.
        var lapSum = 0.0
        var lapCount = 0
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val i = y * sw + x
                val lap = kotlin.math.abs(
                    4f * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - sw] - lum[i + sw]
                )
                lapSum += lap
                lapCount++
            }
        }
        val noise = ((lapSum / max(1, lapCount)) / 28.0).coerceIn(0.0, 1.0)
        val brightness = (mean / 255.0).coerceIn(0.0, 1.0)

        // Classify like a retoucher would at first glance.
        val condition = when {
            noise > 0.52f -> Condition.NOISY
            brightness < 0.30f -> Condition.LOW_LIGHT
            contrast > 0.60f -> Condition.HIGH_CONTRAST
            noise < 0.20f && contrast < 0.42f -> Condition.CLEAN
            else -> Condition.NORMAL
        }
        return PhotoProfile(noise.toFloat(), contrast.toFloat(), brightness.toFloat(), condition)
    }

    /**
     * Derives the retouch recipe from the profile — the "retoucher's
     * judgment" step. Each condition gets tuned parameters instead of one
     * fixed recipe for all photos.
     */
    fun recipeFor(profile: PhotoProfile): RetouchRecipe {
        return when (profile.condition) {
            Condition.CLEAN -> RetouchRecipe(
                // Clean light: catch subtle blemishes, preserve pores.
                blemishThresholdScale = 0.85f,
                skinClarity = -30f, skinSharpness = -18f,
                healStrength = 0.85f, underEyeStrength = 0.65f,
                maxBlemishes = 25,
            )
            Condition.NOISY -> RetouchRecipe(
                // Grain looks like blemishes — be strict; smooth more.
                blemishThresholdScale = 1.5f,
                skinClarity = -55f, skinSharpness = -30f,
                healStrength = 0.95f, underEyeStrength = 0.70f,
                maxBlemishes = 15,
            )
            Condition.LOW_LIGHT -> RetouchRecipe(
                // Shadow noise + don't blow out lifted areas.
                blemishThresholdScale = 1.25f,
                skinClarity = -45f, skinSharpness = -25f,
                healStrength = 0.90f, underEyeStrength = 0.60f,
                maxBlemishes = 18,
            )
            Condition.HIGH_CONTRAST -> RetouchRecipe(
                // Harsh texture mimics spots — strict detection.
                blemishThresholdScale = 1.35f,
                skinClarity = -40f, skinSharpness = -22f,
                healStrength = 0.90f, underEyeStrength = 0.75f,
                maxBlemishes = 20,
            )
            Condition.NORMAL -> RetouchRecipe(
                blemishThresholdScale = 1.0f,
                skinClarity = -45f, skinSharpness = -25f,
                healStrength = 0.90f, underEyeStrength = 0.75f,
                maxBlemishes = 20,
            )
        }
    }

    /** Human-readable condition name for UI feedback (English; UI localizes). */
    fun conditionLabel(condition: Condition): String = when (condition) {
        Condition.CLEAN -> "clean light"
        Condition.NORMAL -> "balanced"
        Condition.NOISY -> "high-ISO grain"
        Condition.LOW_LIGHT -> "low light"
        Condition.HIGH_CONTRAST -> "harsh contrast"
    }
}

/**
 * SmartMask — intelligent auto-fix for auto-detected mask regions.
 *
 * When Auto Mask isolates sky / skin / background, SmartMask analyzes each
 * region's actual condition (brightness, saturation, contrast) and derives
 * corrective [LocalAdjust] values — the way an experienced editor would fix
 * that region on first sight:
 * - Blown-out sky → pull exposure/highlights, recover detail
 * - Dull gray sky → add depth with dehaze + saturation
 * - Harsh skin shadows → gentle lift; noisy skin → adaptive smoothing
 * - Distracting bright background → darken to focus on subject
 *
 * Like [RetouchIntel], this is adaptive heuristic intelligence per-photo,
 * not a trained neural network. Corrections are conservative by design —
 * they fix clear problems without imposing a "look".
 */
object SmartMask {

    /** Measured condition of a masked region. All 0..1. */
    data class RegionStats(
        val brightness: Float,
        val saturation: Float,
        val contrast: Float,
    )

    /**
     * Measures brightness/saturation/contrast inside a mask region.
     * @param bmp source photo, @param mask ALPHA_8 region mask (same aspect).
     */
    fun analyzeRegion(bmp: Bitmap, mask: Bitmap): RegionStats {
        val w = bmp.width; val h = bmp.height
        if (w < 16 || h < 16) return RegionStats(0.5f, 0.3f, 0.4f)
        // Work small for speed; UV mapping is resolution-independent.
        val scale = min(1f, 160f / max(w, h))
        val sw = max(16, (w * scale).toInt())
        val sh = max(16, (h * scale).toInt())
        val smallBmp = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val smallMask = Bitmap.createScaledBitmap(mask, sw, sh, true)
        val px = IntArray(sw * sh)
        val mp = IntArray(sw * sh)
        smallBmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        smallMask.getPixels(mp, 0, sw, 0, 0, sw, sh)
        if (smallBmp != bmp) smallBmp.recycle()
        if (smallMask != mask) smallMask.recycle()

        var wSum = 0.0; var bSum = 0.0; var sSum = 0.0
        val lumList = mutableListOf<Float>()
        for (i in px.indices) {
            val mAlpha = ((mp[i] ushr 24) and 0xFF) / 255.0
            if (mAlpha < 0.25) continue // outside region
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx > 0.0) (mx - mn) / mx else 0.0
            wSum += mAlpha
            bSum += lum * mAlpha
            sSum += sat * mAlpha
            if (mAlpha > 0.6) lumList.add(lum.toFloat())
        }
        if (wSum < 1.0) return RegionStats(0.5f, 0.3f, 0.4f)
        val brightness = (bSum / wSum).toFloat().coerceIn(0f, 1f)
        val saturation = (sSum / wSum).toFloat().coerceIn(0f, 1f)
        // Contrast: std-dev of well-covered pixels.
        var mean = 0f
        for (l in lumList) mean += l
        mean = if (lumList.isNotEmpty()) mean / lumList.size else brightness
        var v = 0f
        for (l in lumList) { val d = l - mean; v += d * d }
        val contrast = if (lumList.isNotEmpty())
            (sqrt(v / lumList.size) / 0.35f).coerceIn(0f, 1f) else 0.4f
        return RegionStats(brightness, saturation, contrast)
    }

    /**
     * Sky correction: recover blown highlights, add depth to dull skies.
     * Conservative — never more than ±0.4 exposure.
     */
    fun skyFix(stats: RegionStats): com.lumora.photoeditor.engine.LocalAdjust {
        var exposure = 0f; var highlights = 0f; var saturation = 0f
        var dehaze = 0f; var temp = 0f
        // Blown-out sky: pull back.
        if (stats.brightness > 0.78f) {
            exposure = -0.35f; highlights = -45f
        } else if (stats.brightness > 0.65f) {
            exposure = -0.18f; highlights = -28f
        } else if (stats.brightness > 0.55f) {
            highlights = -15f
        }
        // Dull / hazy sky: add depth and color.
        if (stats.saturation < 0.22f) {
            saturation = 18f; dehaze = 14f
            // Slight cool push for natural sky blue.
            temp = -6f
        } else if (stats.contrast < 0.30f) {
            dehaze = 10f; saturation = 8f
        }
        return com.lumora.photoeditor.engine.LocalAdjust(
            exposure = exposure, highlights = highlights,
            saturation = saturation, dehaze = dehaze, temp = temp,
        )
    }

    /**
     * Skin correction: adaptive smoothing from [RetouchIntel] plus gentle
     * fixes for harsh light on skin.
     */
    fun skinFix(
        stats: RegionStats,
        recipe: RetouchIntel.RetouchRecipe,
    ): com.lumora.photoeditor.engine.LocalAdjust {
        var shadows = 0f; var exposure = 0f
        // Harsh shadows on skin: lift gently.
        if (stats.contrast > 0.55f) shadows = 18f
        // Dark skin region: slight lift (conservative).
        if (stats.brightness < 0.38f) exposure = 0.12f
        return com.lumora.photoeditor.engine.LocalAdjust(
            clarity = recipe.skinClarity,
            sharpness = recipe.skinSharpness,
            shadows = shadows,
            exposure = exposure,
        )
    }

    /**
     * Background correction: if the background is brighter than the subject
     * region it distracts — darken it gently to focus attention. Also softens
     * slightly for subject separation (Lightroom-style).
     * @param bgStats background region stats, @param subjectBrightness 0..1
     */
    fun backgroundFix(
        bgStats: RegionStats,
        subjectBrightness: Float,
    ): com.lumora.photoeditor.engine.LocalAdjust {
        var exposure = 0f; var highlights = 0f
        // Background markedly brighter than subject → pull it back.
        val gap = bgStats.brightness - subjectBrightness
        if (gap > 0.18f) {
            exposure = -0.30f; highlights = -25f
        } else if (gap > 0.10f) {
            exposure = -0.15f; highlights = -12f
        }
        // Gentle softening for subject separation.
        return com.lumora.photoeditor.engine.LocalAdjust(
            exposure = exposure, highlights = highlights,
            clarity = -12f,
        )
    }

    /**
     * Subject correction: a conservative "pop" — gentle clarity for
     * presence, shadow lift when the subject is dark. Never more than
     * +0.1 exposure: the mask selects the subject, it must not re-light it.
     */
    fun subjectFix(stats: RegionStats): com.lumora.photoeditor.engine.LocalAdjust {
        var clarity = 10f
        var shadows = 0f
        var exposure = 0f
        if (stats.brightness < 0.35f) {
            shadows = 15f; exposure = 0.10f
        } else if (stats.brightness < 0.45f) {
            shadows = 10f
        }
        // Flat-looking subject: a touch more clarity for separation.
        if (stats.contrast < 0.25f) clarity = 16f
        return com.lumora.photoeditor.engine.LocalAdjust(
            clarity = clarity, shadows = shadows, exposure = exposure,
        )
    }
}
