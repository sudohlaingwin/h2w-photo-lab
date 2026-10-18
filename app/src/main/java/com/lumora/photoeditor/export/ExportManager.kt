package com.lumora.photoeditor.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.lumora.photoeditor.R
import com.lumora.photoeditor.engine.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Runs tasks on the GL thread that owns the [GlEngine] context. */
interface GlHost {
    fun queueGl(task: () -> Unit)
}

/**
 * Full-resolution, non-destructive export. Renders the identical GPU
 * pipeline used for preview (tiled, never on the UI thread), then encodes
 * and inserts into MediaStore.
 */
class ExportManager(private val context: Context) {

    companion object {
        private const val TAG = "ExportManager"
        /**
         * Upper bound for one GPU render round-trip. The render itself is
         * tiled and progress-driven, so even a 16000px export finishes far
         * sooner on a V60-class GPU; this only bounds a dead/stalled GL
         * host, which previously froze the export UI at partial progress
         * with no way out.
         */
        private const val GL_RENDER_TIMEOUT_MS = 180_000L
    }

    data class Job(
        val sourceUri: Uri,
        val state: EditState,
        val settings: ExportSettings,
        val previewBitmap: Bitmap, // to restore after export
        val previewMasks: List<Bitmap>,
        /** Hair decontamination textures aligned with [previewMasks]. */
        val previewDecon: List<Bitmap?> = emptyList(),
        val previewRetouch: Bitmap?,
        val exportMasks: List<Bitmap>,
        /** Hair decontamination textures aligned with [exportMasks]. */
        val exportDecon: List<Bitmap?> = emptyList(),
        val retouchSpots: List<RetouchSpot>,
        val glEngine: GlEngine,
        val glHost: GlHost,
    )

    private var cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }

    suspend fun export(job: Job, onProgress: (Float) -> Unit): Result<Uri> =
        withContext(Dispatchers.IO) {
            cancelled.set(false)
            try {
                val errors = job.settings.validate()
                if (errors.isNotEmpty()) return@withContext Result.failure(
                    IllegalArgumentException(errors.joinToString("; ")))

                onProgress(0.02f)
                // 1-2b. decode at export resolution, then render through the
                // GPU pipeline (shared with the in-memory renderBitmap path).
                val bitmap = renderJobBitmap(job) { p ->
                    // progress 0.10..0.75 — called on GL thread; coarse is fine
                    onProgress(0.10f + p * 0.65f)
                }

                // 2c. watermark — deterministic Canvas text on the rendered
                // pixels (no AI). Must never kill the export if it fails.
                if (job.settings.watermark.isActive()) {
                    try {
                        applyWatermark(bitmap, job.settings.watermark)
                    } catch (t: Throwable) {
                        // keep the export; the watermark is cosmetic — but log
                        // it so a stamp failure is diagnosable instead of
                        // silently dropping the user's signature.
                        Log.w(TAG, "watermark stamp failed; exporting without it", t)
                    }
                }

                onProgress(0.80f)
                // 3. encode to a temp file
                // UUID suffix: batch export encodes several photos back-to-back,
                // so a millisecond-clock name could collide and corrupt output.
                val tmp = File(context.cacheDir, "export_${UUID.randomUUID()}.${job.settings.extension()}")
                try {
                    encode(bitmap, job.settings, tmp)
                } finally {
                    // encode() can throw (compress failure, OOM, IO error):
                    // the full-res bitmap must not leak on the failure path.
                    bitmap.recycle()
                }
                try {
                    if (cancelled.get()) return@withContext Result.failure(CancellationException())

                    onProgress(0.90f)
                    // 4. metadata
                    if (job.settings.keepMetadata) copyExif(job.sourceUri, tmp)

                    // 4b. sRGB ICC profile embedding (JPEG/PNG) — the last
                    // byte-level edit of the export, so the EXIF rewrite above
                    // cannot disturb the injected segment. Total-safe: a failure
                    // here never breaks the export.
                    try {
                        embedColorProfile(tmp, job.settings)
                    } catch (_: Throwable) { }

                    // 5. MediaStore insert — blank typed file name now inherits
                    // the source photo's own stem (sourceStemForName) so exports
                    // stay findable; typed names are unchanged (galleryFileName).
                    val uri = insertToGallery(tmp, job.settings, sourceStemForName(job.sourceUri))
                        ?: return@withContext Result.failure(
                            IllegalStateException("Could not save to gallery (storage unavailable?)"))
                    onProgress(1f)
                    Result.success(uri)
                } finally {
                    // The staging file is private to this export: insertToGallery
                    // streams its bytes into MediaStore, so once this block ends
                    // — success, failure, or cancellation — the cacheDir copy
                    // must go. Previously a throwing encode, a cancel after
                    // encode, or a failed gallery insert left a full-resolution
                    // JPEG/PNG behind on every attempt, silently filling
                    // storage. delete() on a missing file is a harmless no-op.
                    tmp.delete()
                }
            } catch (t: Throwable) {
                // Cancellation must propagate so the export coroutine can
                // actually stop; everything else (notably OutOfMemoryError on
                // big full-resolution allocations) becomes a graceful failure
                // with a human-readable message instead of an app crash.
                if (t is CancellationException) throw t
                val msg = if (t is OutOfMemoryError)
                    "Not enough memory to export at this size — try a smaller export size or close other apps"
                else t.message ?: "Export failed"
                Result.failure(IllegalStateException(msg, t))
            }
        }

    /**
     * Renders one photo with its saved edits to an in-memory bitmap at
     * [longEdge] px — the exact same GPU pipeline as [export], but without
     * watermarking, encoding, or any MediaStore insert. Used by the
     * proofing contact-sheet path, which composes many small renders onto
     * one sheet. The caller owns the returned bitmap and must recycle it.
     * Runs on [Dispatchers.IO]; [onProgress] is coarse, like [export].
     */
    suspend fun renderBitmap(
        job: Job,
        longEdge: Int,
        onProgress: (Float) -> Unit = {},
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        cancelled.set(false)
        try {
            val errors = job.settings.validate()
            if (errors.isNotEmpty()) return@withContext Result.failure(
                IllegalArgumentException(errors.joinToString("; ")))
            val sized = job.copy(settings = job.settings.copy(longEdge = longEdge))
            Result.success(renderJobBitmap(sized, onProgress))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val msg = if (t is OutOfMemoryError)
                "Not enough memory to render this photo — close other apps and try again"
            else t.message ?: "Render failed"
            Result.failure(IllegalStateException(msg, t))
        }
    }

    /**
     * Saves an in-memory bitmap (e.g. a composed contact sheet) into the
     * device photo gallery as a JPEG. Returns the MediaStore uri, or null
     * when storage is unavailable. Never throws.
     */
    fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): Uri? {
        return try {
            val tmp = File(context.cacheDir, "sheet_${UUID.randomUUID()}.jpg")
            try {
                FileOutputStream(tmp).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) return null
                }
                insertToGallery(
                    tmp,
                    ExportSettings(
                        format = ExportFormat.JPEG,
                        quality = 88,
                        fileName = displayName,
                        keepMetadata = false,
                        embedColorProfile = false,
                    ),
                    sourceStem = null,
                )
            } finally {
                // Staging copy: a compress failure (the early return above)
                // or a throwing gallery insert must not leave the sheet JPEG
                // in cacheDir. The success path deletes it here as before.
                tmp.delete()
            }
        } catch (t: Throwable) {
            // Documented "never throws": OOM on a big sheet must also come
            // back as null, not a crash.
            null
        }
    }

    /**
     * Decode + GPU render for one job: decodes the source at the job's
     * target long edge, rasterizes retouch spots at that resolution, then
     * renders on the GL thread — re-establishing the AI Pro Retouch grade
     * and look-transfer LUT (engine-local GPU state, same as before) and
     * restoring the caller's preview assets afterwards. Throws on failure
     * (including [CancellationException]); the decoded source is always
     * recycled, so the caller only owns the returned bitmap.
     */
    private suspend fun renderJobBitmap(
        job: Job,
        onProgress: (Float) -> Unit,
    ): Bitmap {
        // 1. decode source at the job's target resolution
        val full = decodeForExport(job.sourceUri, job.settings, job.state)?.bitmap
            ?: throw IllegalStateException("Could not decode the source image")
        try {
            if (cancelled.get()) throw CancellationException()
            onProgress(0.10f)
            // 2a. retouch overlay at render resolution (CPU, off GL thread)
            val exportRetouch = if (job.retouchSpots.isEmpty()) null
            else withContext(Dispatchers.Default) {
                RetouchEngine.rasterize(job.retouchSpots, full.width, full.height, full)
            }
            if (cancelled.get()) throw CancellationException()
            // 2b. render on the GL thread (tiled)
            val rendered = CompletableDeferred<Bitmap>()
            job.glHost.queueGl {
                try {
                    // AI Pro Retouch grade is engine-local GPU state: the
                    // batch headless engine (and any engine that never
                    // ran the apply tap) has no fused LUT, so a saved
                    // grade would silently vanish from the render while
                    // the preview showed it. Re-establish it here. A
                    // first-ever bank bake stomps the source texture, so
                    // this runs before setSource(full) below. When the
                    // grade is disabled the shader forces its strength
                    // to 0 anyway, so there is nothing to upload.
                    val ai = job.state.aiRetouch
                    if (ai.enabled) {
                        AiGradeUpload.upload(job.glEngine, ai.weights, ai.wbGains)
                    }
                    // Look transfer LUT is engine-local GPU state like
                    // the AI grade: re-establish per render so a saved
                    // match survives headless engines. Always called — an
                    // inactive match marks the LUT unavailable, so a
                    // previous photo's LUT can never leak into the next.
                    LookLutUpload.upload(job.glEngine, job.state.lookTransfer)
                    // swap in render-resolution assets
                    job.glEngine.setSource(full)
                    job.glEngine.uploadCurves(job.state)
                    job.glEngine.uploadMasks(job.exportMasks)
                    job.glEngine.uploadMaskDecon(job.exportDecon)
                    job.glEngine.uploadRetouch(exportRetouch)
                    val matrix = exportMatrix(full.width, full.height, job.state)
                    val bmp = job.glEngine.renderExport(job.state, matrix, onProgress)
                    // restore preview assets
                    job.glEngine.setSource(job.previewBitmap)
                    job.glEngine.uploadMasks(job.previewMasks)
                    job.glEngine.uploadMaskDecon(job.previewDecon)
                    job.glEngine.uploadRetouch(job.previewRetouch)
                    rendered.complete(bmp)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    rendered.completeExceptionally(t)
                } finally {
                    // RetouchEngine.rasterize hands us a fresh full-res
                    // bitmap; its pixels are in the GL texture now, so
                    // release the native memory before/after the (slow,
                    // tiled) render instead of waiting for GC. The
                    // caller's previewRetouch is untouched — it is
                    // re-uploaded above and stays owned by the caller.
                    runCatching { exportRetouch?.recycle() }
                }
            }
            val bitmap = try {
                withTimeout(GL_RENDER_TIMEOUT_MS) { rendered.await() }
            } catch (t: TimeoutCancellationException) {
                // Genuine coroutine cancellation arrives as
                // JobCancellationException and still propagates; only the
                // withTimeout watchdog lands here. Surface it as a
                // retryable failure, not a hang.
                throw IllegalStateException(
                    "The renderer stopped responding — try the export again", t)
            }
            if (cancelled.get()) {
                bitmap.recycle()
                throw CancellationException()
            }
            return bitmap
        } finally {
            runCatching { full.recycle() }
        }
    }

    /** Share intent for an exported file uri. */
    fun shareIntent(uri: Uri, mime: String): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // -------------------------------------------------------------

    private data class Decoded(val bitmap: Bitmap, val exifOrientation: Int)

    private fun decodeForExport(uri: Uri, s: ExportSettings, state: EditState): Decoded? {
        // bounds
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var srcW = bounds.outWidth; var srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null
        // target long edge
        var targetLong = max(srcW, srcH)
        if (s.longEdge > 0) targetLong = min(targetLong, s.longEdge)
        // account for crop: decode a bit larger, crop via matrix at render
        var sample = 1
        while (max(srcW, srcH) / sample > targetLong * 2 && sample < 8) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        // EXIF orientation -> bake into pixels
        var orient = ExifInterface.ORIENTATION_NORMAL
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                orient = ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Exception) { }
        val oriented = try {
            applyOrientation(bmp, orient)
        } catch (t: Throwable) {
            // applyOrientation recycles bmp only on success; on a throw
            // (bad matrix, OOM inside createBitmap) the source must not leak.
            runCatching { if (!bmp.isRecycled) bmp.recycle() }
            throw t
        }
        // downscale to target long edge if needed
        val longNow = max(oriented.width, oriented.height)
        val final = if (longNow > targetLong) {
            val sc = targetLong.toFloat() / longNow
            // A degenerate 1px-wide strip could scale to 0px, which
            // createScaledBitmap rejects with IllegalArgumentException.
            val dstW = (oriented.width * sc).toInt().coerceAtLeast(1)
            val dstH = (oriented.height * sc).toInt().coerceAtLeast(1)
            try {
                Bitmap.createScaledBitmap(oriented, dstW, dstH, true)
            } catch (t: Throwable) {
                // createScaledBitmap allocates before returning: on a
                // throw (notably OOM on very large sources) the oriented
                // decode must not leak native memory, or the retry has
                // even less headroom than the failed attempt.
                runCatching { if (!oriented.isRecycled) oriented.recycle() }
                throw t
            }.also { if (!oriented.isRecycled) oriented.recycle() }
        } else oriented
        return Decoded(final, orient)
    }

    private fun applyOrientation(bmp: Bitmap, orient: Int): Bitmap {
        val m = android.graphics.Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { bmp.recycle() }
    }

    /**
     * Export UV matrix: NDC quad -> source UV of the *transformed crop quad*
     * (no view-fit; the crop rect fills the output bitmap).
     */
    private fun exportMatrix(srcW: Int, srcH: Int, state: EditState): FloatArray {
        val quad = Geometry.transformedQuad(state.crop, state.geometry)
        val ndc = listOf(Pt(-1f, -1f), Pt(1f, -1f), Pt(-1f, 1f), Pt(1f, 1f))
        return Geometry.homography(ndc, quad)
    }

    private fun encode(bitmap: Bitmap, s: ExportSettings, dst: File) {
        FileOutputStream(dst).use { out ->
            val ok = when (s.format) {
                ExportFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, s.quality, out)
                ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                ExportFormat.WEBP -> {
                    if (Build.VERSION.SDK_INT >= 30)
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, s.quality, out)
                    else
                        bitmap.compress(Bitmap.CompressFormat.WEBP, s.quality, out)
                }
            }
            // A failed compress leaves a 0-byte file behind — surface it as
            // an error rather than inserting a corrupt image into the
            // user's gallery.
            if (!ok) throw IllegalStateException("Could not encode the image (${s.format})")
        }
    }

    private fun copyExif(srcUri: Uri, dst: File) {
        try {
            val srcExif = context.contentResolver.openInputStream(srcUri)?.use { ExifInterface(it) }
                ?: return
            val dstExif = ExifInterface(dst.absolutePath)
            val tags = listOf(
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
            )
            for (t in tags) srcExif.getAttribute(t)?.let { dstExif.setAttribute(t, it) }
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString())
            dstExif.setAttribute(ExifInterface.TAG_SOFTWARE, "H2W Photo Lab")
            dstExif.saveAttributes()
        } catch (_: Exception) { /* metadata is best-effort */ }
    }

    /**
     * Embeds the bundled sRGB ICC profile into an encoded export file when
     * the setting asks for it and the format supports it (JPEG -> APP2
     * segment, PNG -> iCCP chunk; WebP unchanged). [IccEmbed] is total —
     * malformed input or an already-tagged file returns the input bytes
     * unchanged (same reference), so the file is only rewritten when the
     * profile was actually injected. Any failure leaves the export intact.
     */
    private fun embedColorProfile(file: File, s: ExportSettings) {
        if (!s.embedColorProfile) return
        if (s.format != ExportFormat.JPEG && s.format != ExportFormat.PNG) return
        val profile = iccProfileBytes() ?: return
        val bytes = file.readBytes()
        val tagged = when (s.format) {
            ExportFormat.JPEG -> IccEmbed.embedJpeg(bytes, profile)
            ExportFormat.PNG -> IccEmbed.embedPng(bytes, profile)
            else -> bytes
        }
        if (tagged !== bytes) file.writeBytes(tagged)
    }

    /** Loads and caches the bundled sRGB ICC profile; null when unreadable. */
    private var iccProfile: ByteArray? = null
    private var iccProfileTried = false

    private fun iccProfileBytes(): ByteArray? {
        if (!iccProfileTried) {
            iccProfileTried = true
            iccProfile = runCatching {
                context.resources.openRawResource(R.raw.srgb_icc).use { it.readBytes() }
            }.getOrNull()?.takeIf { IccEmbed.profileLooksValid(it) }
        }
        return iccProfile
    }

    /**
     * Source photo's display-name stem for export naming (blank typed name
     * inherits it, see [ExportSettings.galleryFileName]). Guarded: any query
     * failure returns null and the caller falls back to the timestamp name.
     */
    private fun sourceStemForName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.trim()?.takeIf { it.isNotEmpty() } else null
            } else null
        }
    }.getOrNull()

    private fun insertToGallery(tmp: File, s: ExportSettings, sourceStem: String?): Uri? {
        val name = s.galleryFileName(sourceStem)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, s.mimeType())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Lumora")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        // openOutputStream can return null WITHOUT throwing (a revoked or
        // wedged provider is a real case). The old code only cleaned up the
        // pending entry on an exception, so a null stream left a zero-byte
        // gallery item behind and the export claimed success. Bail out the
        // same way for both: delete the entry, fail cleanly.
        val out = try {
            resolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }
        if (out == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        try {
            out.use { o ->
                tmp.inputStream().use { it.copyTo(o) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }
}

/**
 * Stamps a text watermark onto an export bitmap. White bold text with a
 * soft dark shadow so it stays legible on any photo — deterministic
 * Canvas drawing, no machine learning, no AI. Must be called on a
 * background thread; the bitmap must be mutable (renderExport's output
 * is). No-op when the watermark is not active or the bitmap is immutable.
 *
 * Top-level (not an [ExportManager] method): it touches no instance state,
 * so the on-device GPU self-test can exercise the exact production stamp
 * path without constructing an exporter.
 */
fun applyWatermark(bitmap: Bitmap, wm: WatermarkSettings) {
    if (!wm.isActive() || !bitmap.isMutable) return
    val layout = watermarkLayout(bitmap.width, bitmap.height, wm)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (wm.opacity * 255f).roundToInt().coerceIn(0, 255)
        textSize = layout.textPx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = when (layout.align) {
            WatermarkAlign.LEFT -> Paint.Align.LEFT
            WatermarkAlign.CENTER -> Paint.Align.CENTER
            WatermarkAlign.RIGHT -> Paint.Align.RIGHT
        }
        // soft drop shadow: keeps white text readable on bright photos
        setShadowLayer(layout.textPx * 0.08f, 0f, layout.textPx * 0.05f,
            Color.argb(170, 0, 0, 0))
    }
    Canvas(bitmap).drawText(wm.text.trim(), layout.x, layout.baselineY, paint)
}
