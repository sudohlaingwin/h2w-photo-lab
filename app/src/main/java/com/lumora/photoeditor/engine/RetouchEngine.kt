package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import kotlin.math.*

/**
 * Rasterizes [RetouchSpot]s into a straight-alpha RGBA overlay that the GPU
 * composites *before* the adjustment pipeline, so retouched pixels receive
 * exactly the same color treatment as the original ones.
 *
 * All operations preserve texture where it matters (heal/clone copy real
 * pixels; skin smoothing is edge-aware) and never alter facial geometry.
 */
object RetouchEngine {

    /**
     * Tonal-range weight for dodge & burn (the classic darkroom range
     * control). [luma01] is the pixel's Rec.709 luminance 0..1.
     * Pure function — no Android deps, harness-tested.
     */
    fun rangeWeight(luma01: Float, range: RetouchRange): Float = when (range) {
        RetouchRange.ALL -> 1f
        // Full effect in deep shadows, fading out through the lower midtones.
        RetouchRange.SHADOWS -> 1f - ImageMath.smoothstep(0.15f, 0.45f, luma01)
        // Bell centered on middle gray: ramps up out of the shadows,
        // fades out before the highlights.
        RetouchRange.MIDTONES ->
            ImageMath.smoothstep(0.05f, 0.35f, luma01) *
                (1f - ImageMath.smoothstep(0.65f, 0.95f, luma01))
        // Full effect in the highlights, fading in from the upper midtones.
        RetouchRange.HIGHLIGHTS -> ImageMath.smoothstep(0.55f, 0.85f, luma01)
    }

    /**
     * @param src source pixels at exactly [w]x[h] (the render resolution).
     * @return ARGB_8888 overlay, transparent where no spot applies.
     */
    fun rasterize(spots: List<RetouchSpot>, w: Int, h: Int, src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (spots.isEmpty()) {
            out.eraseColor(0)
            return out
        }
        // The pixel array doubles as the running composite: getPixels fills
        // it and compositeInto folds spots into it in place. No separate
        // pristine-source copy — one fewer full-frame IntArray (~48MB on a
        // 12MP export) in the heaviest transient allocation of the pipeline.
        val compPx = IntArray(w * h)
        src.getPixels(compPx, 0, w, 0, 0, w, h)
        out.setPixels(compositeInto(spots, w, h, compPx), 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Rasterizes spots into a straight-alpha overlay with correct
     * accumulation: each spot samples the running composite (the source
     * plus every previously applied spot), not the pristine source.
     * Previously every spot read the original pixels, so overlapping
     * strokes silently discarded earlier spots' work inside the overlap —
     * e.g. a second skin brush reintroduced the blemish the first one had
     * just removed. Single and non-overlapping spots produce bit-identical
     * output to the old path.
     *
     * Pure IntArray in/out so it stays unit-testable on the JVM;
     * [rasterize] is the Bitmap wrapper the app and exporter call.
     */
    internal fun compositeSpots(
        spots: List<RetouchSpot>, w: Int, h: Int, srcPx: IntArray,
    ): IntArray {
        if (srcPx.size < w * h) return IntArray(w * h)
        // Never mutates the caller's pixels: hand compositeInto its own
        // running-composite copy (unit tests rely on srcPx being untouched).
        return compositeInto(spots, w, h, srcPx.copyOf())
    }

    /**
     * Like [compositeSpots] but takes ownership of [compPx] as the running
     * composite: it must already hold the pristine source pixels and is
     * mutated in place as each spot is applied, so callers that already own
     * a scratch buffer (see [rasterize]) skip one full-frame copy (~48MB on
     * a 12MP export). Returns the same straight-alpha overlay [compositeSpots]
     * returns; [compPx] ends up holding source + all applied spots.
     */
    internal fun compositeInto(
        spots: List<RetouchSpot>, w: Int, h: Int, compPx: IntArray,
    ): IntArray {
        val outPx = IntArray(w * h) // stays 0 (transparent) outside spots
        if (spots.isEmpty() || compPx.size < w * h) return outPx
        // Each spot samples the running composite (the source plus every
        // previously applied spot), not the pristine source. Previously
        // every spot read the original pixels, so overlapping strokes
        // silently discarded earlier spots' work inside the overlap —
        // e.g. a second skin brush reintroduced the blemish the first one
        // had just removed. Single and non-overlapping spots produce
        // bit-identical output to the old path.
        //
        // Pure IntArray in/out so it stays unit-testable on the JVM;
        // [rasterize] is the Bitmap wrapper the app and exporter call.
        for (spot in spots) {
            // Non-finite brush parameters (NaN/±Inf from touch math or an AI
            // detector dividing by zero) previously painted BLACK discs:
            // NaN blend weights round to 0 in the pack sites, silently
            // corrupting the photo instead of failing visibly. Skipping is
            // strictly safer; valid spots are bit-identical to before.
            // (Pinned by RetouchSpotSanitizeTest.)
            if (!spot.isSane()) continue
            val b = spotBounds(spot, w, h) ?: continue
            val x0 = b[0]; val x1 = b[1]; val y0 = b[2]; val y1 = b[3]
            // This spot's contribution only, bbox-sized: applySpot writes
            // exclusively inside the spot bbox, so the old full-frame
            // scratch (~48MB on a 12MP photo, ~192MB transient with the
            // other three buffers on a full-res export) was almost entirely
            // zeros. A fresh zeroed buffer per spot also makes cross-spot
            // leakage impossible by construction (the shared buffer needed
            // a bbox-clear every iteration).
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val tmp = IntArray(bw * bh)
            applySpot(spot, w, h, compPx, tmp, x0, y0, bw, bh)
            // Fold this spot's contribution into the overlay stack and the
            // running composite. Only tmp carries this spot's writes, so
            // earlier spots are never applied twice.
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                val s = tmp[(y - y0) * bw + (x - x0)]
                val sa = (s ushr 24) and 0xFF
                if (sa == 0) continue
                outPx[i] = overStraight(outPx[i], s)
                val a = sa / 255f
                val sr = ((s shr 16) and 0xFF).toFloat()
                val sg = ((s shr 8) and 0xFF).toFloat()
                val sb = (s and 0xFF).toFloat()
                val cr = ((compPx[i] shr 16) and 0xFF).toFloat()
                val cg = ((compPx[i] shr 8) and 0xFF).toFloat()
                val cb = (compPx[i] and 0xFF).toFloat()
                // Pack with round-to-nearest (not toInt truncation): truncation
                // biased every blended photo pixel down by up to 1 level —
                // same defect class as the skin-chain pack sites below.
                compPx[i] = (0xFF shl 24) or
                    ((cr + (sr - cr) * a).roundToInt().coerceIn(0, 255) shl 16) or
                    ((cg + (sg - cg) * a).roundToInt().coerceIn(0, 255) shl 8) or
                    (cb + (sb - cb) * a).roundToInt().coerceIn(0, 255)
            }
        }
        return outPx
    }

    /**
     * Rejects spots whose parameters are not finite numbers. A NaN/±Inf
     * coordinate, size, source offset, or strength (touch math or an AI
     * detector dividing by zero) has no meaningful geometry; every downstream
     * kernel silently degrades it into a black disc (NaN blend weights round
     * to 0 in the pack sites), so the only safe handling is to skip the spot.
     */
    private fun RetouchSpot.isSane(): Boolean =
        x.isFinite() && y.isFinite() &&
            srcX.isFinite() && srcY.isFinite() &&
            size.isFinite() && feather.isFinite() &&
            strength.isFinite() && textureKeep.isFinite()

    /** Pixel bounds a spot touches ([x0, x1, y0, y1]), or null when degenerate. */
    private fun spotBounds(s: RetouchSpot, w: Int, h: Int): IntArray? {        val cx = s.x * w; val cy = s.y * h
        val radius = max(2f, s.size * w * 0.5f)
        val x0 = max(0, (cx - radius).toInt()); val x1 = min(w - 1, (cx + radius).toInt())
        val y0 = max(0, (cy - radius).toInt()); val y1 = min(h - 1, (cy + radius).toInt())
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, x1, y0, y1)
    }

    /**
     * A rectangular copy of [src] pixels with its frame origin, used to give
     * blur kernels real context past a spot's bounding box. Blurring a region
     * cropped exactly to the spot bbox makes kernels at the rim read
     * edge-clamped pixels (replicated edge) instead of the true neighboring
     * skin — the smoothed rim then mismatches the untouched skin just
     * outside it, a faint halo at the brush edge. Padding by at least the
     * kernel radius removes the artifact; at the image border the pad
     * clamps, which is the only correct behavior there.
     */
    private data class PaddedRegion(
        val px: IntArray, val x0: Int, val y0: Int, val w: Int, val h: Int,
    )

    /** Copies [src] over the clamped rect ([rx0]..[rx1], [ry0]..[ry1]). */
    private fun copyRegion(
        src: IntArray, w: Int, h: Int, rx0: Int, ry0: Int, rx1: Int, ry1: Int,
    ): PaddedRegion {
        val x0 = rx0.coerceIn(0, w - 1); val x1 = rx1.coerceIn(0, w - 1)
        val y0 = ry0.coerceIn(0, h - 1); val y1 = ry1.coerceIn(0, h - 1)
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val px = IntArray(rw * rh)
        for (y in y0..y1) {
            System.arraycopy(src, y * w + x0, px, (y - y0) * rw, rw)
        }
        return PaddedRegion(px, x0, y0, rw, rh)
    }

    /** [copyRegion] expanded by [pad] px on every side (clamped to the image). */
    private fun paddedRegion(
        src: IntArray, w: Int, h: Int,
        x0: Int, y0: Int, x1: Int, y1: Int, pad: Int,
    ): PaddedRegion = copyRegion(src, w, h, x0 - pad, y0 - pad, x1 + pad, y1 + pad)

    /** Straight-alpha "over": packed-pixel twin of [blendInto]'s math. */
    private fun overStraight(dst: Int, s: Int): Int {
        val sa = ((s ushr 24) and 0xFF) / 255f
        if (sa <= 0f) return dst
        val da = ((dst ushr 24) and 0xFF) / 255f
        val na = (sa + da * (1f - sa)).coerceIn(0f, 1f)
        if (na <= 0f) return 0
        fun mix(shift: Int): Int {
            val sv = ((s shr shift) and 0xFF).toFloat()
            val dv = ((dst shr shift) and 0xFF).toFloat()
            return ((sv * sa + dv * da * (1f - sa)) / na).toInt().coerceIn(0, 255)
        }
        return ((na * 255f).toInt().coerceIn(0, 255) shl 24) or
            (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun applySpot(
        s: RetouchSpot, w: Int, h: Int, src: IntArray, out: IntArray,
        ox: Int, oy: Int, ow: Int, oh: Int,
    ) {
        // `out` is a bbox-sized scratch buffer covering the spot bbox:
        // image pixel (x, y) lives at (y - oy) * ow + (x - ox). `src` (the
        // running composite) stays full-frame because tone references
        // (heal/clone source patches, blur padding) may read anywhere.
        val b = spotBounds(s, w, h) ?: return
        val x0 = b[0]; val x1 = b[1]; val y0 = b[2]; val y1 = b[3]
        val cx = s.x * w; val cy = s.y * h
        val radius = max(2f, s.size * w * 0.5f)
        val feather = s.feather.coerceIn(0.05f, 1f)
        val strength = s.strength.coerceIn(0f, 1f)
        if (strength <= 0f) return

        when (s.op) {
            RetouchOp.HEAL -> {
                // GIMP-style Laplace seamless cloning, writing the healed disc
                // into the straight-alpha overlay (rgb = healed color,
                // a = feathered disc coverage). The REAL image pixels must be
                // passed as the source: the overlay starts transparent, so
                // healing "from the overlay" was a silent no-op (the Heal tool
                // visibly did nothing).
                val sx = s.srcX * w; val sy = s.srcY * h
                val dx = sx - cx; val dy = sy - cy
                LaplaceHeal.heal(
                    image = src, out = out, w = w, h = h,
                    cx = cx, cy = cy, radius = radius,
                    srcDx = dx, srcDy = dy,
                    feather = feather, strength = strength,
                    // bbox-scoped scratch: image coords stay full-frame,
                    // only the output write index is translated.
                    outOx = ox, outOy = oy, outW = ow, outH = oh,
                )
            }
            RetouchOp.CLONE -> {
                val sx = s.srcX * w; val sy = s.srcY * h
                var dx = sx - cx; var dy = sy - cy
                // Clamp the patch, not the pixels: sliding the whole source
                // rect keeps every sample real instead of smearing the edge
                // column when the user picks a clone source near the frame
                // border (same rationale as LaplaceHeal.clampSourceOffset).
                dx = dx.coerceIn(-x0.toFloat(), (w - 1 - x1).toFloat())
                dy = dy.coerceIn(-y0.toFloat(), (h - 1 - y1).toFloat())
                // CLONE is tone-adaptive across the whole disc: the interior
                // keeps the source patch's texture exactly and carries the
                // FULL tone offset smooth(dest) − smooth(src). The feathered
                // falloff drives only the overlay alpha (blendInto's `f`),
                // never the color: weighting the offset by `f` as well used
                // to leave a residual of −Δ·f·(1−f) in the transition band
                // (peak at f = 0.5), a visible darker/lighter halo ring on
                // large smooth areas whenever source and destination lighting
                // differed. With the full offset in the color, the stamp
                // color already equals the destination tone, so any alpha
                // blend of it over the destination is seamless.
                val blurR = max(1, (radius * 0.5f).toInt())
                // Rim-context padding (see PaddedRegion): the low-frequency
                // tone references must see real pixels past the bbox edge,
                // not edge-clamped replicas. The source window follows the
                // (already frame-clamped) offset; copyRegion clamps the pad
                // at the image border.
                val dReg = paddedRegion(src, w, h, x0, y0, x1, y1, blurR)
                val sReg = copyRegion(src, w, h,
                    (x0 + dx).toInt() - blurR, (y0 + dy).toInt() - blurR,
                    (x1 + dx).toInt() + blurR, (y1 + dy).toInt() + blurR)
                // DO NOT CHANGE to boxBlurRgbFloat: the float-tone variant was
                // tried and REVERTED (2026-10-18) because CloneFloatToneTest
                // .cloneStampInteriorHasNoBandingSteps FAILED (maxLap=3.0 >
                // 1.2 threshold) — the float field did NOT eliminate banding
                // and may have regressed quality. The int-packed boxBlurRgb
                // is the verified working version. See upgrade-log 2026-10-18.
                // Tone references (boxBlurRgb): the offset smooth(dest) −
                // smooth(src) IS the stamp's tone. Applied at full strength
                // across the whole disc; the feathered alpha `f` alone melts
                // the stamp into the destination.
                val dBlur = InpaintingCore.boxBlurRgb(dReg.px, dReg.w, dReg.h, blurR)
                val sBlur = InpaintingCore.boxBlurRgb(sReg.px, sReg.w, sReg.h, blurR)
                // Full-disc tone match (no feather weighting on the color —
                // see the note above): the patch interior used to be an
                // exact copy and tone was bent only in a rim band, so a
                // stamp from a darker/lighter area still read as a pasted
                // patch. The offset is applied at full strength across the
                // whole disc; the feathered alpha `f` alone melts the stamp
                // into the destination.
                for (y in y0..y1) for (x in x0..x1) {
                    val f = falloff(x, y, cx, cy, radius, feather) * strength
                    if (f <= 0.003f) continue
                    val px = sampleBilinear(src, w, h, x + dx, y + dy)
                    var r = ((px shr 16) and 0xFF).toFloat()
                    var g = ((px shr 8) and 0xFF).toFloat()
                    var b = (px and 0xFF).toFloat()
                    val dPx = dBlur[(y - dReg.y0) * dReg.w + (x - dReg.x0)]
                    // The tone reference must be co-registered with the
                    // texture sample: the stamp's texture comes from
                    // bilinear(x+dx, y+dy), so its tone correction is
                    // smooth(src) at that SAME fractional point. The old
                    // integer index sampled the floored pixel — up to a
                    // pixel away from the stamped texture — leaving a faint
                    // tone fringe wherever the source patch crossed a hard
                    // edge. (Pinned by
                    // CloneToneMatchTest.cloneToneReferenceCoRegisteredWithTexture;
                    // sampleBilinear clamps, and the sample point always sits
                    // >= blurR inside the padded source region.)
                    val sPx = sampleBilinear(
                        sBlur, sReg.w, sReg.h, x + dx - sReg.x0, y + dy - sReg.y0)
                    r += ((dPx shr 16) and 0xFF) - ((sPx shr 16) and 0xFF)
                    g += ((dPx shr 8) and 0xFF) - ((sPx shr 8) and 0xFF)
                    b += (dPx and 0xFF) - (sPx and 0xFF)
                    blendInto(out, x - ox, y - oy, ow, r, g, b, f)
                }
            }
            RetouchOp.SMOOTH_SKIN -> {
                // Three-band frequency separation ("plastic face" fix, mirrors
                // the Wavelet Skin model's fine/mid/residual split, so both
                // skin tools share the same mental model):
                //  - tone: edge-aware bilateral smoothing at blotch scale
                //    (sigmaS = radius/5) — evens out blotchy tone. This stage
                //    applies at full strength, independent of textureKeep.
                //  - mid: content between pore scale and blotch scale
                //    (detailBlur - toneBlur) — the natural mid-frequency skin
                //    texture (pore fields, soft form). This is what the old
                //    two-band formula crushed at (1 - textureKeep), producing
                //    the waxy "plastic face" his 11:40 on-device video caught.
                //  - fine: original minus a SMALL edge-aware smoothing
                //    (sigmaS = radius/24; grain lives below this scale) —
                //    fine detail only, no blotch-scale content, no edge
                //    halos (bilateral, not box).
                // result = tone + mid * midKeep + fine * textureKeep, where
                // midKeep = textureKeep + 0.35 * (1 - textureKeep): mid-band
                // texture always survives longer than fine grain (blotch tone
                // is evened out before pores die), so:
                //  - textureKeep = 1: midKeep = 1 too, and tone + mid + fine
                //    collapses to the original pixel — a true keep-all.
                //  - textureKeep = 0: full smoothing of the fine grain with
                //    35% of the mid-band texture retained — soft skin that
                //    still reads as skin, never wax.
                // The bilateral kernel reaches ~sigmaS*1.5 px past each output
                // pixel, so the region is padded by 0.35*radius (>= the kernel
                // extent, including the downsample path) — rim pixels then
                // smooth against real neighboring skin instead of
                // edge-clamped replicas (see PaddedRegion).
                val pad = max(1, (radius * 0.35f).toInt())
                val region = paddedRegion(src, w, h, x0, y0, x1, y1, pad)
                val toneBlur = bilateral(region.px, region.w, region.h,
                    sigmaS = radius / 5f, sigmaR = 28f)
                val detailBlur = bilateral(region.px, region.w, region.h,
                    sigmaS = max(1.5f, radius / 24f), sigmaR = 28f)
                // User-tunable detail retention: cycle 119 exposed this for
                // Wavelet Skin; the brush now carries it per-spot so each
                // stroke remembers its own setting (default 0.45f = old
                // hardcoded behavior).
                val textureKeep = s.textureKeep.coerceIn(0f, 1f)
                // Mid-band texture survives partial smoothing longer than
                // fine grain (plastic-face fix): blotch tone is evened out
                // before pores die. midKeep >= textureKeep always, = 1 when
                // textureKeep = 1 (keep-all collapses to the original pixel).
                val midKeep = textureKeep + 0.35f * (1f - textureKeep)
                for (y in y0..y1) for (x in x0..x1) {
                    val f = falloff(x, y, cx, cy, radius, feather) * strength
                    if (f <= 0.003f) continue
                    val o = src[y * w + x]
                    val t = toneBlur[(y - region.y0) * region.w + (x - region.x0)]
                    val d = detailBlur[(y - region.y0) * region.w + (x - region.x0)]
                    val or = ((o shr 16) and 0xFF).toFloat()
                    val og = ((o shr 8) and 0xFF).toFloat()
                    val ob = (o and 0xFF).toFloat()
                    val tr = ((t shr 16) and 0xFF).toFloat()
                    val tg = ((t shr 8) and 0xFF).toFloat()
                    val tb = (t and 0xFF).toFloat()
                    val dr = ((d shr 16) and 0xFF).toFloat()
                    val dg = ((d shr 8) and 0xFF).toFloat()
                    val db = (d and 0xFF).toFloat()
                    // mid band (pore fields, soft form): detailBlur - toneBlur
                    val mr = (dr - tr) * midKeep
                    val mg = (dg - tg) * midKeep
                    val mb = (db - tb) * midKeep
                    // fine band (grain): original - detailBlur
                    val r = tr + mr + (or - dr) * textureKeep
                    val g = tg + mg + (og - dg) * textureKeep
                    val b = tb + mb + (ob - db) * textureKeep
                    blendInto(out, x - ox, y - oy, ow, r, g, b, f)
                }
            }
            RetouchOp.EYE_ENHANCE -> {
                // Same rim-context padding as SMOOTH_SKIN (see PaddedRegion):
                // the local-mean reference must see past the bbox edge.
                val ePad = max(1, (radius / 8f).toInt())
                val region = paddedRegion(src, w, h, x0, y0, x1, y1, ePad)
                val blurred = InpaintingCore.boxBlurRgb(region.px, region.w, region.h, ePad)
                for (y in y0..y1) for (x in x0..x1) {
                    val f = falloff(x, y, cx, cy, radius, feather) * strength
                    if (f <= 0.003f) continue
                    val o = src[y * w + x]
                    val bl = blurred[(y - region.y0) * region.w + (x - region.x0)]
                    var r = ((o shr 16) and 0xFF).toFloat()
                    var g = ((o shr 8) and 0xFF).toFloat()
                    var b = (o and 0xFF).toFloat()
                    // gentle local contrast + lift + saturation
                    r += (((o shr 16) and 0xFF) - ((bl shr 16) and 0xFF)) * 0.55f
                    g += (((o shr 8) and 0xFF) - ((bl shr 8) and 0xFF)) * 0.55f
                    b += ((o and 0xFF) - (bl and 0xFF)) * 0.55f
                    r *= 1.06f; g *= 1.06f; b *= 1.06f
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = l + (r - l) * 1.18f; g = l + (g - l) * 1.18f; b = l + (b - l) * 1.18f
                    blendInto(out, x - ox, y - oy, ow, r, g, b, f)
                }
            }
            RetouchOp.TEETH_WHITEN -> {
                for (y in y0..y1) for (x in x0..x1) {
                    val f = falloff(x, y, cx, cy, radius, feather) * strength
                    if (f <= 0.003f) continue
                    val o = src[y * w + x]
                    var r = ((o shr 16) and 0xFF).toFloat()
                    var g = ((o shr 8) and 0xFF).toFloat()
                    var b = (o and 0xFF).toFloat()
                    // yellow stain amount: warm and saturated
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val yellow = ((r + g) / 2f - b).coerceIn(0f, 255f) / 255f
                    val wgt = yellow * (l / 255f).coerceIn(0f, 1f)
                    // desaturate yellow toward neutral + slight lift
                    r = r + (l - r) * 0.55f * wgt + 6f * wgt
                    g = g + (l - g) * 0.55f * wgt + 6f * wgt
                    b = b + (l - b) * 0.25f * wgt + 10f * wgt
                    blendInto(out, x - ox, y - oy, ow, r, g, b, f * (0.35f + 0.65f * wgt))
                }
            }
            RetouchOp.UNDER_EYE -> {
                for (y in y0..y1) for (x in x0..x1) {
                    val f = falloff(x, y, cx, cy, radius, feather) * strength
                    if (f <= 0.003f) continue
                    val o = src[y * w + x]
                    var r = ((o shr 16) and 0xFF).toFloat()
                    var g = ((o shr 8) and 0xFF).toFloat()
                    var b = (o and 0xFF).toFloat()
                    val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                    // lift mostly the darker pixels, slight desaturation
                    val lift = (1f - l) * 26f
                    val gray = (r + g + b) / 3f
                    r = r + lift + (gray - r) * 0.18f
                    g = g + lift + (gray - g) * 0.18f
                    b = b + lift + (gray - b) * 0.18f
                    blendInto(out, x - ox, y - oy, ow, r, g, b, f)
                }
            }
            RetouchOp.RED_EYE -> {
                // Classic red-eye fix, no ML: desaturate only the red flash
                // glare. Redness = how far red exceeds the other channels;
                // non-red pixels (iris, catchlight) are left alone via a
                // red-weighted alpha, and the glare is tamed slightly darker
                // so the fixed area reads as a pupil again.
                for (y in y0..y1) for (x in x0..x1) {
                    val f = falloff(x, y, cx, cy, radius, feather) * strength
                    if (f <= 0.003f) continue
                    val o = src[y * w + x]
                    val r = ((o shr 16) and 0xFF).toFloat()
                    val g = ((o shr 8) and 0xFF).toFloat()
                    val b = (o and 0xFF).toFloat()
                    val mx = maxOf(g, b)
                    val red = ((r - mx) / 64f).coerceIn(0f, 1f)
                    if (red <= 0.003f) continue
                    val tame = 1f - 0.25f * red
                    blendInto(out, x - ox, y - oy, ow, mx * tame, g * tame, b * tame,
                        f * (0.3f + 0.7f * red))
                }
            }
            // REMOVE is handled via Inpainting.removeObject (bitmap patching),
            // not spot-based retouching.
            RetouchOp.REMOVE -> { /* no-op */ }
            RetouchOp.DODGE, RetouchOp.BURN -> {
                // Pro dodge & burn: selective tonal painting. Dodge lifts
                // midtones (like +exposure on the brush), burn deepens them.
                // Applied in a soft disc with the spot's feather/strength so
                // it sculpts light like a darkroom brush, not a flat overlay.
                val isDodge = s.op == RetouchOp.DODGE
                // Dodge: up to +0.9 EV at full strength; burn: down to -0.9 EV.
                // Scaled by strength so repeated light taps build gradually.
                val ev = (if (isDodge) 0.9f else -0.9f) * strength
                val gain = 2f.pow(ev)
                val x0 = max(0, (cx - radius).toInt())
                val x1 = min(w - 1, (cx + radius).toInt())
                val y0 = max(0, (cy - radius).toInt())
                val y1 = min(h - 1, (cy + radius).toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / radius
                    if (d > 1f) continue
                    // Soft brush falloff driven by the feather parameter.
                    val f = (1f - d).pow(1f + feather * 3f)
                    if (f <= 0.001f) continue
                    val idx = y * w + x
                    // Work on the composited pixel: the running composite
                    // (src image + spots applied so far). NOT `out` — that
                    // is this spot's own output buffer, which is transparent
                    // at this point; reading it used to apply the gain to
                    // black, turning every dodge stroke into a dark disc and
                    // making burn darken through black instead of the pixel.
                    val base = src[idx]
                    val br = ((base shr 16) and 0xFF) / 255f
                    val bg = ((base shr 8) and 0xFF) / 255f
                    val bb = (base and 0xFF) / 255f
                    // Tonal-range gating: the classic darkroom range control.
                    // Pixel luminance in the brush disc decides how much of
                    // the gain applies, so dodging "shadows" leaves the sky
                    // alone and burning "highlights" spares the shadows.
                    val luma = 0.2126f * br + 0.7152f * bg + 0.0722f * bb
                    val rw = rangeWeight(luma, s.range)
                    if (rw <= 0.001f) continue
                    // Blend the gain toward the pixel by the brush mask so
                    // edges stay soft; operate in linear-ish space via gamma.
                    val g = 1f + (gain - 1f) * f * rw
                    val nr = (br.pow(2.2f) * g).pow(1f / 2.2f).coerceIn(0f, 1f)
                    val ng = (bg.pow(2.2f) * g).pow(1f / 2.2f).coerceIn(0f, 1f)
                    val nb = (bb.pow(2.2f) * g).pow(1f / 2.2f).coerceIn(0f, 1f)
                    // `out` is the bbox-sized scratch (translated index);
                    // `src` above stays full-frame.
                    // Pack with round-to-nearest (not toInt truncation): truncation
                    // biased every dodged/burned photo pixel down by up to 1
                    // level — same defect class as the composite fold above.
                    out[(y - oy) * ow + (x - ox)] = (0xFF shl 24) or
                        ((nr * 255f).roundToInt() shl 16) or
                        ((ng * 255f).roundToInt() shl 8) or
                        (nb * 255f).roundToInt()
                }
            }
        }
    }

    /**
     * Picks a good automatic source patch for HEAL: probes candidate centers on
     * rings around the target and returns the one whose tone best matches the
     * destination while staying smooth (likely blemish-free) and fully
     * in-frame. Returns UV coords, or null when no candidate is trustworthy —
     * the caller then falls back to a fixed offset.
     *
     * Uses sparse per-pixel sampling (no full-frame allocation), so a tap
     * costs ~1k getPixel calls — a few ms on the UI thread.
     */
    fun findHealSource(src: Bitmap, uvX: Float, uvY: Float, sizeFrac: Float): Pair<Float, Float>? {
        val w = src.width; val h = src.height
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        return findHealSourcePx(px, w, h, uvX, uvY, sizeFrac)
    }

    /**
     * Pure-Kotlin core of [findHealSource] (JVM-testable, no android.graphics).
     *
     * Two correctness fixes over the original Bitmap version:
     *  1. The reference tone is sampled from an annulus around the target
     *     (1.15r–2.1r), NOT from the target disc itself. Judging tone from
     *     the disc pulled the reference toward the blemish — a dark spot
     *     made the picker prefer dark patches, sometimes a second blemish —
     *     while the heal must blend into the SURROUNDING tone.
     *  2. Candidate rings start at 2.3r so the candidate disc never overlaps
     *     the target disc (the old 1.7r ring overlapped it: center distance
     *     1.7r < 2r — the "clean" source could include the blemish itself).
     *  3. Candidates are scored on luminance AND chrominance. The chroma
     *     plumbing (chromaAt, annulus/sample chroma means) feeds two terms:
     *     (a) the vector distance between the candidate's mean chroma and
     *     the annulus reference chroma — an equally bright patch with the
     *     wrong color cast (e.g. cooler skin) now loses to a tint-matched
     *     one; (b) the candidate's chroma variance, penalized like the luma
     *     variance — a patch straddling a tint edge is invisible to the luma
     *     term when both tints share a luminance, but it would paste a
     *     two-tone disc. The annulus reference excludes the target disc by
     *     construction (1.15r–2.1r), so the blemish itself can't pull it;
     *     a tinted neighbor can only shift it mildly, which the vector
     *     distance still resolves (see HealSourcePickerTest).
     */
    fun findHealSourcePx(
        px: IntArray, w: Int, h: Int,
        uvX: Float, uvY: Float, sizeFrac: Float,
    ): Pair<Float, Float>? {
        if (w < 8 || h < 8 || px.size < w * h) return null
        val cx = uvX * w; val cy = uvY * h
        val radius = max(2f, sizeFrac * w * 0.5f)

        fun lumAt(x: Int, y: Int): Float {
            val c = px[y * w + x]
            return 0.2126f * ((c shr 16) and 0xFF) +
                0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)
        }

        /**
         * Luma-decoupled chroma at a pixel: R−B (red–blue axis) and 2G−R−B
         * (green–magenta axis). Both are exactly 0 for any neutral gray and
         * scale with tint independent of brightness, so they measure "wrong
         * color cast" without double-counting luminance.
         */
        fun chromaAt(x: Int, y: Int): FloatArray {
            val c = px[y * w + x]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            return floatArrayOf(r - b, 2f * g - r - b)
        }

        // mean luminance + variance + mean chroma (a, b) + chroma variance
        // of a disc, sparsely sampled.
        // Returns [mean, variance, chromaA, chromaB, chromaVar].
        fun sampleStats(pxx: Float, pyy: Float, r: Float): FloatArray {
            var sum = 0f; var sum2 = 0f; var n = 0
            var sumA = 0f; var sumB = 0f; var sumA2 = 0f; var sumB2 = 0f
            val step = max(1f, r / 4f)
            var dy = -r
            while (dy <= r) {
                var dx = -r
                while (dx <= r) {
                    if (dx * dx + dy * dy <= r * r) {
                        val x = (pxx + dx).toInt().coerceIn(0, w - 1)
                        val y = (pyy + dy).toInt().coerceIn(0, h - 1)
                        val l = lumAt(x, y)
                        sum += l; sum2 += l * l; n++
                        val ch = chromaAt(x, y)
                        sumA += ch[0]; sumB += ch[1]
                        sumA2 += ch[0] * ch[0]; sumB2 += ch[1] * ch[1]
                    }
                    dx += step
                }
                dy += step
            }
            if (n == 0) return floatArrayOf(0f, 0f, 0f, 0f, 0f)
            val mean = sum / n
            val meanA = sumA / n; val meanB = sumB / n
            return floatArrayOf(mean, max(0f, sum2 / n - mean * mean),
                meanA, meanB,
                max(0f, (sumA2 + sumB2) / n - (meanA * meanA + meanB * meanB)))
        }

        // mean luminance + mean chroma (a, b) of the annulus around the
        // target: the tone AND tint the healed disc must blend into
        // (see KDoc fixes #1 and #3). Returns [mean, chromaA, chromaB].
        fun sampleAnnulusStats(pxx: Float, pyy: Float, r: Float): FloatArray {
            var sum = 0f; var n = 0
            var sumA = 0f; var sumB = 0f
            val rIn = r * 1.15f; val rOut = r * 2.1f
            val step = max(1f, rOut / 6f)
            var dy = -rOut
            while (dy <= rOut) {
                var dx = -rOut
                while (dx <= rOut) {
                    val d2 = dx * dx + dy * dy
                    if (d2 >= rIn * rIn && d2 <= rOut * rOut) {
                        val x = (pxx + dx).toInt().coerceIn(0, w - 1)
                        val y = (pyy + dy).toInt().coerceIn(0, h - 1)
                        sum += lumAt(x, y); n++
                        val ch = chromaAt(x, y)
                        sumA += ch[0]; sumB += ch[1]
                    }
                    dx += step
                }
                dy += step
            }
            return if (n == 0) floatArrayOf(0f, 0f, 0f)
            else floatArrayOf(sum / n, sumA / n, sumB / n)
        }

        val dst = sampleAnnulusStats(cx, cy, radius)
        val dstMean = dst[0]; val dstA = dst[1]; val dstB = dst[2]
        if (dstMean < 4f) return null // surroundings nearly black: tone can't be judged

        var best: Pair<Float, Float>? = null
        var bestScore = Float.MAX_VALUE
        // ring radii keep every candidate disc clear of the target disc
        // (center distance >= 2.3r > 2r — see KDoc fix #2).
        val rings = floatArrayOf(2.3f, 3.2f, 4.5f)
        for (ring in rings) {
            for (k in 0 until 8) {
                val a = k * (Math.PI / 4).toFloat()
                val pxx = cx + cos(a) * radius * ring
                val pyy = cy + sin(a) * radius * ring
                // fully in-frame with a margin
                if (pxx - radius < 2f || pxx + radius > w - 2f) continue
                if (pyy - radius < 2f || pyy + radius > h - 2f) continue
                val st = sampleStats(pxx, pyy, radius)
                val mean = st[0]; val variance = st[1]
                if (mean < 4f) continue // skip pure-black patches
                // Prefer a patch matching the surroundings' tone AND tint;
                // penalize high luminance variance (likely another blemish or
                // a hard edge) and high chroma variance (a patch straddling
                // a tint edge — invisible to the luma term when both tints
                // share a luminance, but it would paste a two-tone disc).
                val chromaDist = sqrt((st[2] - dstA) * (st[2] - dstA) +
                    (st[3] - dstB) * (st[3] - dstB))
                val score = abs(mean - dstMean) + sqrt(variance) * 0.35f +
                    chromaDist + sqrt(st[4]) * 0.35f
                if (score < bestScore) {
                    bestScore = score
                    best = (pxx / w) to (pyy / h)
                }
            }
            // accept a good nearby candidate before looking farther out
            if (best != null && bestScore < 25f) break
        }
        // reject when even the best candidate's tone is far off
        if (best == null || bestScore > 90f) return null
        return best
    }

    private fun falloff(x: Int, y: Int, cx: Float, cy: Float, radius: Float, feather: Float): Float {
        val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / radius
        if (d >= 1f) return 0f
        return ImageMath.brushFalloff(d, feather)
    }

    /** Alpha-composites the computed color over any previous spot result. */
    private fun blendInto(out: IntArray, x: Int, y: Int, w: Int, r: Float, g: Float, b: Float, a: Float) {
        val i = y * w + x
        val dst = out[i]
        val da = ((dst ushr 24) and 0xFF) / 255f
        val na = (a + da * (1f - a)).coerceIn(0f, 1f)
        if (na <= 0f) return
        val sr = r.coerceIn(0f, 255f); val sg = g.coerceIn(0f, 255f); val sb = b.coerceIn(0f, 255f)
        val dr = ((dst shr 16) and 0xFF).toFloat()
        val dg = ((dst shr 8) and 0xFF).toFloat()
        val db = (dst and 0xFF).toFloat()
        val fr = (sr * a + dr * da * (1f - a)) / (na * 255f)
        val fg = (sg * a + dg * da * (1f - a)) / (na * 255f)
        val fb = (sb * a + db * da * (1f - a)) / (na * 255f)
        // Round-to-nearest, not truncation: the color terms are genuinely
        // fractional (float tone fields, feathered alpha blends), and
        // truncating biased every stamped pixel up to 1 level dark. This
        // also matters for exact sums like smooth(dest) − smooth(src) in
        // CLONE, where float rounding could otherwise leave the total at
        // N−ε and drop a whole level (single-pixel dips → visible steps).
        out[i] = ((na * 255f).roundToInt() shl 24) or
            ((fr * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((fg * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (fb * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun sampleBilinear(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val tx = (fx - x0).coerceIn(0f, 1f); val ty = (fy - y0).coerceIn(0f, 1f)
        fun c(px: Int): FloatArray = floatArrayOf(
            ((px shr 16) and 0xFF).toFloat(), ((px shr 8) and 0xFF).toFloat(), (px and 0xFF).toFloat())
        val a = c(src[y0 * w + x0]); val b = c(src[y0 * w + x1])
        val cc = c(src[y1 * w + x0]); val d = c(src[y1 * w + x1])
        val r = (a[0] * (1 - tx) + b[0] * tx) * (1 - ty) + (cc[0] * (1 - tx) + d[0] * tx) * ty
        val g = (a[1] * (1 - tx) + b[1] * tx) * (1 - ty) + (cc[1] * (1 - tx) + d[1] * tx) * ty
        val bl = (a[2] * (1 - tx) + b[2] * tx) * (1 - ty) + (cc[2] * (1 - tx) + d[2] * tx) * ty
        return (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
    }

    /**
     * Edge-aware (bilateral) smoothing, scale-correct for large brushes.
     *
     * The 25x25 kernel window can only represent spatial sigmas up to ~8px.
     * A large skin brush (sigmaS = radius/5 can exceed 30px) used to have
     * its kernel silently truncated at ±12px — where the Gaussian weight is
     * still ~0.9 — so big brushes visibly under-smoothed no matter how high
     * the strength was. Now the region is processed at a downsampled scale
     * (where the fixed window covers the intended support) and upsampled
     * back; small brushes take the identical single-scale path as before.
     */
    private fun bilateral(src: IntArray, w: Int, h: Int, sigmaS: Float, sigmaR: Float): IntArray {
        val want = max(1f, sigmaS * 1.5f)
        val scale = max(1, ceil(want / 12f).toInt()).coerceAtMost(8)
        if (scale <= 1) return bilateralCore(src, w, h, sigmaS, sigmaR)
        val dw = max(1, w / scale); val dh = max(1, h / scale)
        val small = downsampleBox(src, w, h, dw, dh)
        val smallBlur = bilateralCore(small, dw, dh, sigmaS / scale, sigmaR)
        return upsampleBilinear(smallBlur, dw, dh, w, h)
    }

    /** Box-average downsample (src is w x h, result is dw x dh). */
    private fun downsampleBox(src: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw) {
            val x0 = (x * w / dw).coerceIn(0, w - 1)
            val x1 = (((x + 1) * w / dw) - 1).coerceIn(x0, w - 1)
            val y0 = (y * h / dh).coerceIn(0, h - 1)
            val y1 = (((y + 1) * h / dh) - 1).coerceIn(y0, h - 1)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (yy in y0..y1) for (xx in x0..x1) {
                val p = src[yy * w + xx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                n++
            }
            out[y * dw + x] = (((r.toDouble() / n).roundToInt()) shl 16) or
                (((g.toDouble() / n).roundToInt()) shl 8) or (b.toDouble() / n).roundToInt()
        }
        return out
    }

    /** Bilinear upsample (src is sw x sh, result is w x h). */
    private fun upsampleBilinear(src: IntArray, sw: Int, sh: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = if (sw == 1) 0f else x * (sw - 1f) / (w - 1f).coerceAtLeast(1f)
            val gy = if (sh == 1) 0f else y * (sh - 1f) / (h - 1f).coerceAtLeast(1f)
            val x0 = gx.toInt().coerceIn(0, sw - 1)
            val y0 = gy.toInt().coerceIn(0, sh - 1)
            val x1 = (x0 + 1).coerceIn(0, sw - 1)
            val y1 = (y0 + 1).coerceIn(0, sh - 1)
            val tx = (gx - x0).coerceIn(0f, 1f)
            val ty = (gy - y0).coerceIn(0f, 1f)
            var r = 0f; var g = 0f; var b = 0f
            val ws = floatArrayOf((1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty)
            val xs = intArrayOf(x0, x1, x0, x1); val ys = intArrayOf(y0, y0, y1, y1)
            for (k in 0..3) {
                val p = src[ys[k] * sw + xs[k]]
                r += ((p shr 16) and 0xFF) * ws[k]
                g += ((p shr 8) and 0xFF) * ws[k]
                b += (p and 0xFF) * ws[k]
            }
            out[y * w + x] = (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }
        return out
    }

    // NOTE (2026-10-18): the three pack sites in this skin-smoothing chain
    // (downsampleBox, bilateralCore, upsampleBilinear) all use roundToInt,
    // not toInt. Truncation biased the tone field down by up to 1 level per
    // stage — measured -0.70 levels mean darkening inside skin-smoothing
    // strokes on a linear ramp (rounding-harness, SMOOTH_SKIN textureKeep=0).
    // Same round-to-nearest rationale as blendInto / LaplaceHeal.
    private fun bilateralCore(src: IntArray, w: Int, h: Int, sigmaS: Float, sigmaR: Float): IntArray {
        val out = IntArray(w * h)
        val r = max(1, (sigmaS * 1.5f).toInt()).coerceAtMost(12)
        val s2 = 2f * sigmaS * sigmaS
        val r2 = 2f * sigmaR * sigmaR
        for (y in 0 until h) for (x in 0 until w) {
            val c0 = src[y * w + x]
            val r0 = ((c0 shr 16) and 0xFF).toFloat()
            val g0 = ((c0 shr 8) and 0xFF).toFloat()
            val b0 = (c0 and 0xFF).toFloat()
            var wr = 0f; var wg = 0f; var wb = 0f; var ws = 0f
            for (ky in -r..r) for (kx in -r..r) {
                val xx = (x + kx).coerceIn(0, w - 1); val yy = (y + ky).coerceIn(0, h - 1)
                val c = src[yy * w + xx]
                val dr = ((c shr 16) and 0xFF) - r0
                val dg = ((c shr 8) and 0xFF) - g0
                val db = (c and 0xFF) - b0
                val wsPart = exp(-(kx * kx + ky * ky) / s2)
                val wrPart = exp(-(dr * dr + dg * dg + db * db) / r2)
                val wt = wsPart * wrPart
                wr += ((c shr 16) and 0xFF) * wt
                wg += ((c shr 8) and 0xFF) * wt
                wb += (c and 0xFF) * wt
                ws += wt
            }
            out[y * w + x] = ((wr / ws).roundToInt().coerceIn(0, 255) shl 16) or
                ((wg / ws).roundToInt().coerceIn(0, 255) shl 8) or (wb / ws).roundToInt().coerceIn(0, 255)
        }
        return out
    }
}
