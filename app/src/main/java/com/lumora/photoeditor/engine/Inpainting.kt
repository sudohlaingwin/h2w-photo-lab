package com.lumora.photoeditor.engine

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Content-aware object removal via patch-based inpainting.
 *
 * The algorithm lives in [InpaintingCore] (pure Kotlin, harness-testable);
 * this is the thin android.graphics.Bitmap wrapper.
 *
 * Two paths, chosen from the full-res mask up front:
 *  - small removal (mask bounds fit in [CROP_MAX_PX]): the region is cropped
 *    and inpainted at near-full resolution, then feather-blended back — so a
 *    blemish on a 12MP photo gets a crisp fill instead of an 8x-upscaled
 *    soft blob;
 *  - large removal: the legacy full-image path at ~512px working resolution
 *    (speed over per-pixel detail).
 *
 * This is real on-device inpainting — no ML, no network.
 */
object Inpainting {

    /** Full-res context margin around a crop (px): room for the patch search
     *  and the feather blend back into the photo. */
    private const val CROP_MARGIN_PX = 24

    /** Crop path is used when the (margined) mask bounds fit in this long
     *  side (px); larger removals take the legacy full-image path. */
    private const val CROP_MAX_PX = 900

    /** Working-resolution cap for the crop path (px). The crop itself is at
     *  most [CROP_MAX_PX] on its long side, so this is effectively "no
     *  downscale" while still bounding worst-case compute. */
    private const val CROP_WORK_MAX = 1024

    /**
     * Removes the masked region from [bmp].
     * @param bmp source image (any size)
     * @param mask same-size ALPHA_8 or ARGB bitmap; max(alpha, red) > 100 = remove
     * @param onProgress 0..1 progress callback (called on worker thread)
     * @return new bitmap with the region filled, or null on failure
     */
    fun removeObject(
        bmp: Bitmap,
        mask: Bitmap,
        onProgress: (Float) -> Unit = {},
    ): Bitmap? {
        val w = bmp.width; val h = bmp.height
        if (w < 16 || h < 16) return null
        if (mask.width != w || mask.height != h) return null

        return try {
            // Size the job from the full-res mask before committing to a
            // working resolution.
            val maskPx = IntArray(w * h)
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)
            val bounds = InpaintingCore.computeFillBounds(maskPx, w, h, CROP_MARGIN_PX)
                ?: return bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
            val bw = bounds[2] - bounds[0]
            val bh = bounds[3] - bounds[1]
            val out = if (max(bw, bh) <= CROP_MAX_PX) {
                removeCropped(bmp, mask, bounds, onProgress)
            } else {
                removeFull(bmp, mask, onProgress, InpaintingCore.WORK_MAX)
            }
            onProgress(1f)
            out
        } catch (t: Throwable) {
            // The full-res mask scan and the crop blend allocate full-frame
            // buffers; an OutOfMemoryError here must NOT kill the app
            // (Exception alone would not catch it). The caller degrades
            // to "couldn't remove" feedback. Cancellation still propagates.
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }
    }

    /**
     * Crop path: inpaints just the (margined) mask bounds at near-full
     * resolution and feather-blends the crop back into the original.
     * [bounds] is intArrayOf(x0, y0, x1, y1) as returned by
     * [InpaintingCore.computeFillBounds].
     */
    private fun removeCropped(
        bmp: Bitmap,
        mask: Bitmap,
        bounds: IntArray,
        onProgress: (Float) -> Unit,
    ): Bitmap? {
        val x0 = bounds[0]; val y0 = bounds[1]
        val bw = bounds[2] - x0; val bh = bounds[3] - y0
        // The second allocation can throw (OOM) after the first succeeded;
        // without this guard `cropBmp` would leak before the try/finally
        // below is even entered.
        val cropBmp = Bitmap.createBitmap(bmp, x0, y0, bw, bh)
        val cropMask: Bitmap = try {
            Bitmap.createBitmap(mask, x0, y0, bw, bh)
        } catch (t: Throwable) {
            if (!cropBmp.isRecycled) cropBmp.recycle()
            throw t
        }
        try {
            // Fill-fraction guard against the FULL photo, not the crop: the
            // crop is the mask bounds + margin by construction, so a
            // crop-relative cap would misfire on ordinary medium-sized
            // removals (a solid mask with side ~83px+ already covers 40% of
            // its own margined crop). Refuse only genuinely absurd jobs —
            // filling most of the photo.
            val cropMaskPx = IntArray(bw * bh)
            cropMask.getPixels(cropMaskPx, 0, bw, 0, 0, bw, bh)
            var cropFill = 0
            for (f in InpaintingCore.buildFillMask(cropMaskPx)) if (f) cropFill++
            if (cropFill > bmp.width.toLong() * bmp.height *
                InpaintingCore.MAX_FILL_FRACTION
            ) return null
            // The crop-relative fill cap is disabled here (see removeFull):
            // the guard above already applied it against the full photo.
            val filled = removeFull(cropBmp, cropMask, { p -> onProgress(p * 0.9f) },
                CROP_WORK_MAX, enforceFillCap = false) ?: return null
            onProgress(0.95f)
            // blendCropped only READS `filled` (getPixels) — it never takes
            // ownership — so it must be recycled here; previously one
            // crop-resolution bitmap leaked on every successful removal.
            try {
                return blendCropped(bmp, filled, cropMask, x0, y0, featherPx = 4)
            } finally {
                if (!filled.isRecycled) filled.recycle()
            }
        } finally {
            if (!cropBmp.isRecycled) cropBmp.recycle()
            if (!cropMask.isRecycled) cropMask.recycle()
        }
    }

    /**
     * The legacy full-image pipeline: downscale to [workMaxPx], inpaint,
     * upscale and feather-blend the masked area back in.
     *
     * [enforceFillCap] refuses jobs that would fill more than
     * [InpaintingCore.MAX_FILL_FRACTION] of the working image. The crop
     * path passes false: the crop IS the mask bounds + margin by
     * construction, so a crop-relative cap would refuse ordinary
     * medium-sized removals — removeCropped applies the same cap against
     * the full photo instead.
     *
     * Quality is best for textured backgrounds; large flat areas may show
     * repetition.
     */
    private fun removeFull(
        bmp: Bitmap,
        mask: Bitmap,
        onProgress: (Float) -> Unit = {},
        workMaxPx: Int = InpaintingCore.WORK_MAX,
        enforceFillCap: Boolean = true,
    ): Bitmap? {
        val w = bmp.width; val h = bmp.height
        if (w < 16 || h < 16) return null
        if (mask.width != w || mask.height != h) return null

        return try {
            // Work at max workMaxPx px
            val scale = min(1f, workMaxPx / max(w, h).toFloat())
            val sw = max(16, (w * scale).toInt())
            val sh = max(16, (h * scale).toInt())

            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            // The second allocation can throw (OOM) after the first
            // succeeded; without this guard `small` would leak and the
            // outer catch would only convert the failure to null.
            val smallMask: Bitmap = try {
                Bitmap.createScaledBitmap(mask, sw, sh, true)
            } catch (t: Throwable) {
                if (small != bmp && !small.isRecycled) small.recycle()
                throw t
            }

            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val maskPx = IntArray(sw * sh)
            smallMask.getPixels(maskPx, 0, sw, 0, 0, sw, sh)

            if (small != bmp) small.recycle()
            if (smallMask != mask) smallMask.recycle()

            val toFill = InpaintingCore.buildFillMask(maskPx)
            var fillCount = 0
            for (b in toFill) if (b) fillCount++
            if (fillCount == 0) return bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
            // Don't try to fill more than 40% of the image. Disabled for the
            // crop path (see KDoc): the guard would misfire against the crop.
            if (enforceFillCap &&
                fillCount > sw * sh * InpaintingCore.MAX_FILL_FRACTION
            ) return null

            val result = InpaintingCore.inpaint(pixels, toFill, sw, sh) {
                onProgress(it * 0.9f)
            }

            onProgress(0.95f)

            // Write back to a bitmap at the small size, then upscale.
            // blendMasked only READS the inpainted bitmap (getPixels) — it
            // never takes ownership — so this frame must recycle both
            // temporaries itself: previously `up` (scaled branch) and
            // `outSmall` (unscaled branch) leaked one working-resolution
            // bitmap on every successful removal, and a throwing
            // createScaledBitmap left `outSmall` behind too.
            val outSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            outSmall.setPixels(result, 0, sw, 0, 0, sw, sh)

            val out = try {
                if (sw != w || sh != h) {
                    val up = Bitmap.createScaledBitmap(outSmall, w, h, true)
                    try {
                        // Blend: only replace the masked area, feather the edges
                        blendMasked(bmp, up, mask, featherPx = 3)
                    } finally {
                        if (!up.isRecycled) up.recycle()
                    }
                } else {
                    blendMasked(bmp, outSmall, mask, featherPx = 2)
                }
            } finally {
                if (!outSmall.isRecycled) outSmall.recycle()
            }
            onProgress(1f)
            out
        } catch (t: Throwable) {
            // PatchMatch + the full-res feather blend allocate several
            // full-frame buffers; an OutOfMemoryError here must NOT kill the
            // app (Exception alone would not catch it). The caller degrades
            // to "couldn't remove" feedback. Cancellation still propagates.
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }
    }

    /**
     * Blends the inpainted result with the original: only the masked area
     * is replaced, with feathered edges.
     *
     * Only the mask's bounding box (expanded by the feather reach) is
     * processed: the old version allocated five full-frame buffers and ran
     * a full (2r+1)^2 box blur over every pixel of the photo — hundreds of
     * MB and seconds on a 12MP export for a fingertip-sized removal. The
     * restricted region is visually identical: outside the feather reach
     * the output was already exactly the original pixel (alpha 0), and the
     * separable [InpaintingCore.boxBlurAlpha] is the same clamped-edge 2D
     * box blur the old code computed.
     */
    private fun blendMasked(
        original: Bitmap, inpainted: Bitmap, mask: Bitmap, featherPx: Int,
    ): Bitmap {
        val w = original.width; val h = original.height
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        // Mask may be different size; scale it
        val scaledMask = if (mask.width != w || mask.height != h) {
            Bitmap.createScaledBitmap(mask, w, h, true)
        } else mask
        val maskPx = IntArray(w * h)
        scaledMask.getPixels(maskPx, 0, w, 0, 0, w, h)
        if (scaledMask != mask) scaledMask.recycle()

        // Bounding box of the nonzero mask, expanded by the feather radius
        // so the blur kernel never reads past the processed region.
        var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = maskPx[row + x]
                // ALPHA_8 getPixels convention is ambiguous (the alpha may
                // land in the high or the red byte); take the max, like the
                // fill path does.
                if ((((c ushr 24) and 0xFF) or ((c shr 16) and 0xFF)) != 0) {
                    if (x < bx0) bx0 = x
                    if (y < by0) by0 = y
                    if (x > bx1) bx1 = x
                    if (y > by1) by1 = y
                }
            }
        }
        if (bx1 < 0) return out // empty mask: output == original
        val r = max(0, featherPx)
        val x0 = max(0, bx0 - r); val y0 = max(0, by0 - r)
        val x1 = min(w - 1, bx1 + r); val y1 = min(h - 1, by1 + r)
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1

        val origPx = IntArray(bw * bh)
        val inpPx = IntArray(bw * bh)
        original.getPixels(origPx, 0, bw, x0, y0, bw, bh)
        inpainted.getPixels(inpPx, 0, bw, x0, y0, bw, bh)
        // Build alpha from mask with feathering
        val alpha = FloatArray(bw * bh)
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val c = maskPx[(y0 + y) * w + (x0 + x)]
                val a = (c ushr 24) and 0xFF
                val rr = (c shr 16) and 0xFF
                alpha[y * bw + x] = max(a, rr) / 255f
            }
        }
        InpaintingCore.boxBlurAlpha(alpha, bw, bh, r)

        val outPx = IntArray(bw * bh)
        for (i in origPx.indices) {
            val a = alpha[i].coerceIn(0f, 1f)
            if (a <= 0f) {
                outPx[i] = origPx[i]
            } else if (a >= 1f) {
                outPx[i] = inpPx[i]
            } else {
                val o = origPx[i]; val n = inpPx[i]
                val red = (((o shr 16) and 0xFF) * (1 - a) + ((n shr 16) and 0xFF) * a).toInt()
                val g = (((o shr 8) and 0xFF) * (1 - a) + ((n shr 8) and 0xFF) * a).toInt()
                val b = ((o and 0xFF) * (1 - a) + (n and 0xFF) * a).toInt()
                outPx[i] = (255 shl 24) or (red shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(outPx, 0, bw, x0, y0, bw, bh)
        return out
    }

    /**
     * Blends a filled crop back into the full-res original at ([x0], [y0]).
     * Only the masked area of the crop is replaced, with feathered edges, so
     * the seam between the inpainted crop and the untouched photo is
     * invisible. [cropMask] is the same-size-as-[filled] mask the crop was
     * inpainted with.
     */
    private fun blendCropped(
        original: Bitmap, filled: Bitmap, cropMask: Bitmap,
        x0: Int, y0: Int, featherPx: Int,
    ): Bitmap {
        val w = original.width; val h = original.height
        val bw = filled.width; val bh = filled.height
        require(x0 >= 0 && y0 >= 0 && x0 + bw <= w && y0 + bh <= h) {
            "crop out of bounds"
        }
        val out = original.copy(Bitmap.Config.ARGB_8888, true)

        val maskPx = IntArray(bw * bh)
        cropMask.getPixels(maskPx, 0, bw, 0, 0, bw, bh)
        val alpha = FloatArray(bw * bh)
        for (i in maskPx.indices) {
            val a = (maskPx[i] ushr 24) and 0xFF
            val r = (maskPx[i] shr 16) and 0xFF
            alpha[i] = max(a, r) / 255f
        }
        // Feather via separable box blur (same clamped-edge 2D box blur the
        // old full-window loop computed, shared with blendMasked).
        InpaintingCore.boxBlurAlpha(alpha, bw, bh, featherPx)

        val origPx = IntArray(bw * bh)
        val fillPx = IntArray(bw * bh)
        original.getPixels(origPx, 0, bw, x0, y0, bw, bh)
        filled.getPixels(fillPx, 0, bw, 0, 0, bw, bh)
        val outPx = IntArray(bw * bh)
        for (i in origPx.indices) {
            val a = alpha[i].coerceIn(0f, 1f)
            if (a <= 0f) {
                outPx[i] = origPx[i]
            } else if (a >= 1f) {
                outPx[i] = fillPx[i]
            } else {
                val o = origPx[i]; val n = fillPx[i]
                val r = (((o shr 16) and 0xFF) * (1 - a) + ((n shr 16) and 0xFF) * a).toInt()
                val g = (((o shr 8) and 0xFF) * (1 - a) + ((n shr 8) and 0xFF) * a).toInt()
                val b = ((o and 0xFF) * (1 - a) + (n and 0xFF) * a).toInt()
                outPx[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(outPx, 0, bw, x0, y0, bw, bh)
        return out
    }
}
