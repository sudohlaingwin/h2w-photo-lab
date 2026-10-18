package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import kotlin.math.*

/**
 * Rasterizes [Mask]s into grayscale bitmaps for the GPU pipeline.
 *
 * Working resolution is capped ([MAX_RASTER]) — masks are inherently smooth
 * (feathered), so a lower-res raster sampled with linear filtering is
 * visually identical while keeping uploads cheap.
 */
object MaskEngine {

    const val MAX_RASTER = 512

    /**
     * Rasterize a full mask (all components composited) to an ARGB_8888
     * grayscale bitmap (value in R/G/B; the shader samples `.r`).
     *
     * @param srcLum optional source-image luminance at [MAX_RASTER] resolution
     * (row-major, 0..1). Required for pro auto-mask strokes: when a stroke
     * has [BrushStroke.autoMask], paint is gated by luminance similarity to
     * the stroke center so the brush respects strong edges (Lightroom-style).
     */
    fun rasterize(mask: Mask, aiStore: AiMaskStore, srcLum: FloatArray? = null): Bitmap {
        val w = MAX_RASTER
        val h = MAX_RASTER
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rp = IntArray(w * h) // accumulate in 0..255 ints
        val tmp = IntArray(w * h)
        mask.components.forEachIndexed { index, comp ->
            rasterComponent(comp, w, h, tmp, aiStore, srcLum)
            when {
                index == 0 && comp.op == MaskOp.ADD -> {
                    for (i in rp.indices) rp[i] = tmp[i]
                }
                comp.op == MaskOp.ADD -> {
                    for (i in rp.indices) rp[i] = max(rp[i], tmp[i])
                }
                comp.op == MaskOp.SUBTRACT -> {
                    for (i in rp.indices) rp[i] = (rp[i] * (255 - tmp[i]) / 255)
                }
                comp.op == MaskOp.INTERSECT -> {
                    for (i in rp.indices) rp[i] = min(rp[i], tmp[i])
                }
            }
        }
        // ---- Pro mask refine (Lightroom "Refine"): expand/contract the composited
        // mask, then soften its edge. Applied here (not in the shader) so the
        // refinement is baked into the GPU upload, the live overlay, AND the
        // export raster, which all share rasterize().
        val final = if (mask.refineExpand != 0f || mask.refineFeather > 0f)
            refineMask(rp, w, h, mask.refineExpand, mask.refineFeather) else rp
        // grayscale: value replicated to R/G/B, alpha opaque
        val out = IntArray(w * h) { i ->
            val v = (final[i] and 0xFF)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Hair/edge decontamination bitmap for [mask] (Lightroom "Decontaminate
     * colors"): the source image recolored so feathered edge pixels carry the
     * selected interior's color instead of the background's. Uploaded to the
     * GPU as a per-mask RGB texture; the shader mixes it in over the mask's
     * transition band, gated by [Mask.decontaminate].
     *
     * Returns null when [Mask.decontaminate] is 0 (feature off) so callers
     * can bind the placeholder texture. The bitmap is square [MAX_RASTER]
     * with the same center-crop geometry as [rasterize] (see
     * [buildMaskLuminance]), so it samples 1:1 against the mask raster in
     * image-UV space. May throw (OOM); callers must guard.
     *
     * @param raster the FINAL mask raster from [rasterize] (refine baked in).
     * @param src the source photo bitmap (any size/aspect).
     */
    fun despilledBitmap(mask: Mask, raster: Bitmap, src: Bitmap): Bitmap? {
        if (mask.decontaminate <= 0f) return null
        val size = MAX_RASTER
        // Same square center-crop geometry as the mask raster.
        val side = min(src.width, src.height).coerceAtLeast(1)
        val ox = (src.width - side) / 2
        val oy = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, ox, oy, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (scaled !== cropped) cropped.recycle()
        val w = scaled.width; val h = scaled.height
        val srcPx = IntArray(w * h)
        scaled.getPixels(srcPx, 0, w, 0, 0, w, h)
        scaled.recycle()
        // Effective selection: the shader inverts via uMaskInv, so the
        // despill donor side must be the post-inversion interior.
        val rw = raster.width; val rh = raster.height
        val rasterPx = IntArray(rw * rh)
        raster.getPixels(rasterPx, 0, rw, 0, 0, rw, rh)
        val eff = IntArray(w * h) { i ->
            // Bilinear-ish: nearest sample of the (same-resolution) raster.
            val rx = (i % w) * rw / w
            val ry = (i / w) * rh / h
            val v = (rasterPx[ry * rw + rx] shr 16) and 0xFF
            if (mask.inverted) 255 - v else v
        }
        // Hair needs a tight donor radius at mask resolution.
        val radius = (size / 128).coerceIn(2, 8)
        val out = HairDecontaminate.despill(srcPx, w, h, eff, mask.decontaminate, radius)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rasterComponent(
        comp: MaskComponent, w: Int, h: Int, out: IntArray, aiStore: AiMaskStore,
        srcLum: FloatArray?,
    ) {
        out.fill(0)
        when (comp.type) {
            MaskType.BRUSH -> {
                // paint strokes into a float accumulator then quantize
                val acc = FloatArray(w * h)
                for (stroke in comp.strokes) paintStroke(acc, w, h, stroke, srcLum)
                for (i in out.indices) out[i] = (acc[i].coerceIn(0f, 1f) * 255f).toInt()
            }
            MaskType.LINEAR_GRADIENT -> {
                for (y in 0 until h) for (x in 0 until w) {
                    val f = ImageMath.linearGradientFactor(
                        x / w.toFloat(), y / h.toFloat(), comp.shape)
                    out[y * w + x] = (f * 255f).toInt()
                }
            }
            MaskType.RADIAL_GRADIENT -> {
                for (y in 0 until h) for (x in 0 until w) {
                    val f = ImageMath.radialGradientFactor(
                        x / w.toFloat(), y / h.toFloat(), comp.shape)
                    out[y * w + x] = (f * 255f).toInt()
                }
            }
            MaskType.AI_SUBJECT, MaskType.AI_BACKGROUND,
            MaskType.AI_SKIN,
            MaskType.SKY_SELECT, MaskType.COLOR_RANGE, MaskType.LUMINANCE_RANGE,
            MaskType.DEPTH_RANGE -> {
                val bmp = comp.aiKey?.let { aiStore.load(it) }
                if (bmp != null) {
                    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                    val px = IntArray(w * h)
                    scaled.getPixels(px, 0, w, 0, 0, w, h)
                    // stored as ALPHA_8: value in alpha channel
                    for (i in out.indices) out[i] = (px[i] ushr 24) and 0xFF
                    if (scaled !== bmp) scaled.recycle()
                }
            }
        }
    }

    /** Paints one brush stroke into [acc] (0..1 float per pixel). */
    fun paintStroke(
        acc: FloatArray, w: Int, h: Int, stroke: BrushStroke,
        srcLum: FloatArray? = null,
    ) {
        if (stroke.points.isEmpty()) return
        val radiusPx = stroke.size * w // size normalized to image width
        if (radiusPx < 0.5f) return
        // Auto-mask needs source luminance at this raster resolution.
        val lum = if (stroke.autoMask) srcLum?.takeIf { it.size >= w * h } else null
        // walk the polyline, stamping at radius/3 intervals
        val pts = stroke.points
        var prev = pts[0]
        stamp(acc, w, h, prev.x * w, prev.y * h, radiusPx, stroke, lum)
        for (i in 1 until pts.size) {
            val p = pts[i]
            val dx = (p.x - prev.x) * w; val dy = (p.y - prev.y) * h
            val dist = sqrt(dx * dx + dy * dy)
            val steps = max(1, (dist / (radiusPx / 3f)).toInt())
            for (s in 1..steps) {
                val t = s / steps.toFloat()
                stamp(acc, w, h,
                    (prev.x + (p.x - prev.x) * t) * w,
                    (prev.y + (p.y - prev.y) * t) * h,
                    radiusPx, stroke, lum)
            }
            prev = p
        }
    }

    private fun stamp(
        acc: FloatArray, w: Int, h: Int,
        cx: Float, cy: Float, radius: Float, stroke: BrushStroke,
        srcLum: FloatArray?,
    ) {
        val r = ceil(radius).toInt()
        val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt())
        val a = (stroke.flow * stroke.opacity).coerceIn(0f, 1f)
        if (a <= 0f) return
        // Pro auto-mask: sample the luminance under the brush center; pixels
        // whose tone differs strongly are likely across an edge — suppress
        // paint there so the mask hugs the intended region (Lightroom-style).
        val autoMask = stroke.autoMask && srcLum != null
        val centerLum = if (autoMask) {
            val ix = cx.toInt().coerceIn(0, w - 1)
            val iy = cy.toInt().coerceIn(0, h - 1)
            srcLum!![iy * w + ix]
        } else 0f
        val tol = stroke.autoMaskTolerance.coerceIn(0.02f, 0.5f)
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / radius
            if (d > 1f) continue
            var f = ImageMath.brushFalloff(d, stroke.feather) * a
            if (autoMask) {
                // Gate by tone similarity: full paint near the center tone,
                // fading to zero at the tolerance. Uses smoothstep so the
                // mask edge stays soft, not binary.
                val dLum = abs(srcLum!![y * w + x] - centerLum)
                val t = ((dLum - tol * 0.45f) / (tol * 0.55f)).coerceIn(0f, 1f)
                val gate = 1f - t * t * (3f - 2f * t) // smoothstep
                f *= gate
                if (f <= 0.001f) continue
            }
            val idx = y * w + x
            acc[idx] = if (stroke.erase) acc[idx] * (1f - f)
            else 1f - (1f - acc[idx]) * (1f - f)
        }
    }

    /**
     * Incremental painting for live brush feedback: draws a stamp segment
     * directly onto a working ALPHA_8 bitmap (0..255 in alpha channel).
     */
    fun paintLive(
        bitmap: Bitmap, fromX: Float, fromY: Float, toX: Float, toY: Float,
        sizeNorm: Float, feather: Float, flow: Float, opacity: Float, erase: Boolean,
        autoMask: Boolean = false, srcLum: FloatArray? = null,
    ) {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val acc = FloatArray(w * h) { i -> ((px[i] ushr 24) and 0xFF) / 255f }
        val stroke = BrushStroke(
            points = listOf(BrushPoint(fromX, fromY), BrushPoint(toX, toY)),
            size = sizeNorm, feather = feather, flow = flow, opacity = opacity, erase = erase,
            autoMask = autoMask,
        )
        // Live preview bitmap may differ in resolution from MAX_RASTER; scale
        // the source luminance if provided.
        val lum = if (autoMask && srcLum != null && srcLum.size == w * h) srcLum else null
        paintStroke(acc, w, h, stroke, lum)
        for (i in px.indices) px[i] = ((acc[i].coerceIn(0f, 1f) * 255f).toInt() shl 24)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    // ------------------------------------------------------------------
    // Pro mask refine (Lightroom "Refine"): global expand/contract + feather.
    // ------------------------------------------------------------------

    /**
     * Applies global refinement to the composited mask raster (0..255 ints):
     * expand>0 dilates (grows) the selection, expand<0 erodes (contracts)
     * it, then feather softens the edge with a box blur. All separable
     * passes on the 512 raster — a few ms on Dispatchers.Default.
     */
    private fun refineMask(src: IntArray, w: Int, h: Int, expand: Float, feather: Float): IntArray {
        var cur = src
        if (expand != 0f) {
            val r = (abs(expand).coerceAtMost(1f) * 24f).roundToInt().coerceAtLeast(1)
            cur = minMaxFilter(cur, w, h, r, expand > 0f)
        }
        if (feather > 0f) {
            val r = (feather.coerceAtMost(1f) * 48f).roundToInt().coerceAtLeast(1)
            cur = boxBlur(cur, w, h, r)
        }
        return cur
    }

    /** Separable max (dilate) or min (erode) filter with radius [r]. */
    private fun minMaxFilter(src: IntArray, w: Int, h: Int, r: Int, isMax: Boolean): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = if (isMax) 0 else 255
                for (k in -r..r) {
                    val p = src[row + (x + k).coerceIn(0, w - 1)]
                    v = if (isMax) max(v, p) else min(v, p)
                }
                tmp[row + x] = v
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var v = if (isMax) 0 else 255
                for (k in -r..r) {
                    val p = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    v = if (isMax) max(v, p) else min(v, p)
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    /** Separable box blur with sliding-window sums (O(w*h) regardless of radius). */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val window = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0L
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = (sum / window).toInt()
                sum -= src[row + (x - r).coerceIn(0, w - 1)]
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0L
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum / window).toInt()
                sum -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }
}

/** Persistent store for AI-generated mask rasters (files, keyed by id). */
interface AiMaskStore {
    fun save(key: String, bitmap: Bitmap)
    fun load(key: String): Bitmap?
    fun delete(key: String)
}

/**
 * Pixel-format contract for [AiMaskStore] persistence. Pure Int math so it
 * is unit-testable on the JVM (see AiMaskStoreTest).
 *
 * Mask rasters carry the value (0..255) in the ALPHA channel (see
 * ImageMath.selectSkyAuto and MaskEngine's stored-bitmap branch, which reads
 * `(px ushr 24)`). The persisted PNG must therefore keep the value in its
 * alpha channel: load() recovers it with `copy(ALPHA_8)`, which keeps alpha
 * and drops color. Writing the value into gray RGB with alpha=0xFF (the old
 * mistake) loads back as 255 everywhere — every AI auto-mask then applied at
 * full strength to the whole image.
 */
object AiMaskFormat {
    /** Packs mask value [a] into a PNG pixel: value in ALPHA, white RGB. */
    fun pack(a: Int): Int = (a.coerceIn(0, 255) shl 24) or 0x00FFFFFF

    /** Reads the mask value back: the alpha channel. */
    fun unpack(px: Int): Int = (px ushr 24) and 0xFF
}

/**
 * Builds a square [size]x[size] luminance field (0..1, row-major) from
 * [src] for pro auto-mask gating. The mask raster is always square
 * ([MAX_RASTER]), so we center-crop to square first to keep the geometry
 * consistent with the mask UV space.
 */
fun buildMaskLuminance(src: Bitmap, size: Int = MaskEngine.MAX_RASTER): FloatArray {
    val side = min(src.width, src.height).coerceAtLeast(1)
    val ox = (src.width - side) / 2
    val oy = (src.height - side) / 2
    val cropped = Bitmap.createBitmap(src, ox, oy, side, side)
    val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
    if (scaled !== cropped) cropped.recycle()
    val px = IntArray(size * size)
    scaled.getPixels(px, 0, size, 0, 0, size, size)
    scaled.recycle()
    return FloatArray(size * size) { i ->
        val p = px[i]
        (0.2126f * ((p shr 16) and 0xFF) +
         0.7152f * ((p shr 8) and 0xFF) +
         0.0722f * (p and 0xFF)) / 255f
    }
}
