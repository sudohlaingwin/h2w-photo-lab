package com.lumora.photoeditor.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.lumora.photoeditor.engine.NativeLib
import com.lumora.photoeditor.engine.RawDecoder
import com.lumora.photoeditor.engine.parseRawExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Read-only photo metadata shown in the library "Details" dialog
 * (Lightroom-parity photo info: file, capture and camera data).
 *
 * Everything EXIF-derived is best-effort — EXIF may be stripped, the
 * content URI may no longer resolve, and [loadPhotoDetails] never throws:
 * missing values simply don't render.
 */
data class PhotoDetails(
    val displayName: String,
    val folder: String,
    /** "CR2"/"NEF"/"DNG"/"JPEG"/..., or null when it cannot be determined. */
    val format: String?,
    val dimensions: String,
    val fileSize: String?,
    val addedDate: String,
    val camera: String?,
    val capturedDate: String?,
    val iso: String?,
    val aperture: String?,
    val shutter: String?,
    val focalLength: String?,
    val rating: Int,
    val favorite: Boolean,
    val editCount: Int,
)

/**
 * The format label for the File section of the details dialog. RAW files
 * resolve through [RawDecoder.rawFormatName] (the same label the editor
 * caption uses, so details and editor always agree); common raster formats
 * map to their familiar names. Unknown -> null (row simply doesn't render).
 *
 * Pure function, pinned by SelfTest check 59.
 */
fun formatLabelFor(displayName: String?): String? {
    val raw = RawDecoder.rawFormatName(displayName)
    if (raw != null) return raw
    return when (displayName?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "JPEG"
        "png" -> "PNG"
        "heic", "heif" -> "HEIC"
        "webp" -> "WEBP"
        "gif" -> "GIF"
        "bmp" -> "BMP"
        "tif", "tiff" -> "TIFF"
        "avif" -> "AVIF"
        else -> null
    }
}

/**
 * Loads [PhotoDetails] for [img] on [Dispatchers.IO]. Reads EXIF straight
 * from the content URI (no full decode), so even large originals load fast.
 * Never throws.
 */
suspend fun loadPhotoDetails(context: Context, img: LibraryImage): PhotoDetails =
    withContext(Dispatchers.IO) {
        var camera: String? = null
        var capturedDate: String? = null
        var iso: String? = null
        var aperture: String? = null
        var shutter: String? = null
        var focalLength: String? = null
        var fileSize: String? = null
        try {
            img.contentUri?.let { Uri.parse(it) }?.let { uri ->
                // File size, three ways: the AssetFileDescriptor length is
                // fastest, but providers (and some SAF trees) report
                // UNKNOWN_LENGTH, so fall back to the MediaStore-style
                // OpenableColumns.SIZE query and finally to a plain file read
                // for file:// URIs.
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length > 0) fileSize = formatBytes(afd.length)
                    }
                } catch (_: Exception) { /* size stays unknown */ }
                if (fileSize == null) {
                    try {
                        context.contentResolver.query(
                            uri, arrayOf(OpenableColumns.SIZE), null, null, null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val size = cursor.getLong(0)
                                if (size > 0) fileSize = formatBytes(size)
                            }
                        }
                    } catch (_: Exception) { /* size stays unknown */ }
                }
                if (fileSize == null && uri.scheme == "file") {
                    try {
                        val len = File(uri.path ?: "").length()
                        if (len > 0) fileSize = formatBytes(len)
                    } catch (_: Exception) { /* size stays unknown */ }
                }
                try {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        val exif = ExifInterface(ins)
                        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                        camera = mergeMakeModel(make, model)
                        capturedDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?.let { parseExifDate(it) }
                        iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1)
                            .takeIf { it > 0 }?.let { "ISO $it" }
                        aperture = parseRational(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
                            ?.let { "ƒ/%.1f".format(Locale.US, it) }
                        shutter = parseRational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
                            ?.takeIf { it > 0 }?.let { formatShutter(it) }
                        focalLength = parseRational(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
                            ?.takeIf { it > 0 }?.let { "%.0f mm".format(Locale.US, it) }
                    }
                } catch (_: Exception) { /* EXIF stays unknown */ }
                // Proprietary RAW containers (CR2/NEF/ARW/RAF/...) are opaque
                // to Android's ExifInterface, so the fields above stay empty
                // even though the file genuinely carries the shooting data.
                // The native LibRaw bridge reads the real metadata; the
                // ExifInterface results above win wherever they already found
                // something (DNG works through the normal EXIF path). The
                // temp copy is bounded to RAW files with missing fields —
                // raster formats never pay for it.
                if (RawDecoder.isRaw(img.displayName) && NativeLib.isAvailable &&
                    listOf(camera, capturedDate, iso, aperture, shutter, focalLength)
                        .any { it == null }
                ) {
                    try {
                        // UUID suffix: Details for two RAW photos in the same
                        // millisecond must never share a temp path.
                        val tmp = File(context.cacheDir, "h2w_exif_${UUID.randomUUID()}.raw")
                        try {
                            val copied = context.contentResolver.openInputStream(uri)?.use { ins ->
                                tmp.outputStream().use { out -> ins.copyTo(out) }
                                true
                            } ?: false
                            if (copied) {
                                parseRawExif(NativeLib.rawExif(tmp.absolutePath))?.let { ex ->
                                    if (camera == null)
                                        mergeMakeModel(ex.make, ex.model)?.let { camera = it }
                                    if (capturedDate == null && ex.epochSeconds > 0)
                                        capturedDate = formatExifEpoch(ex.epochSeconds)
                                    if (iso == null && ex.iso > 0)
                                        iso = "ISO ${ex.iso.roundToInt()}"
                                    if (aperture == null && ex.aperture > 0)
                                        aperture = "ƒ/%.1f".format(Locale.US, ex.aperture)
                                    if (shutter == null && ex.shutterSeconds > 0)
                                        shutter = formatShutter(ex.shutterSeconds)
                                    if (focalLength == null && ex.focalMm > 0)
                                        focalLength = "%.0f mm".format(Locale.US, ex.focalMm)
                                }
                            }
                        } finally {
                            try { tmp.delete() } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { /* EXIF stays unknown */ }
                }
            }
        } catch (_: Exception) { /* never throw from the details loader */ }
        PhotoDetails(
            displayName = img.displayName,
            folder = img.folder,
            format = formatLabelFor(img.displayName),
            dimensions = "${img.width} × ${img.height}",
            fileSize = fileSize,
            addedDate = formatEpochSeconds(img.dateAdded),
            camera = camera,
            capturedDate = capturedDate,
            iso = iso,
            aperture = aperture,
            shutter = shutter,
            focalLength = focalLength,
            rating = img.rating,
            favorite = img.favorite,
            editCount = img.editCount,
        )
    }

/** EXIF rationals look like "28/10" or "1/125". */
private fun parseRational(s: String?): Double? {
    if (s.isNullOrBlank()) return null
    return try {
        val t = s.trim()
        if (t.contains('/')) {
            val parts = t.split('/')
            val n = parts[0].toDouble()
            val d = parts[1].toDouble()
            if (d == 0.0) null else n / d
        } else t.toDouble()
    } catch (_: Exception) {
        null
    }
}

/** 0.008 -> "1/125"; 2.5 -> "2.5s". */
private fun formatShutter(seconds: Double): String =
    if (seconds >= 1.0) {
        val s = "%.1f".format(Locale.US, seconds).trimEnd('0').trimEnd('.')
        "${s}s"
    } else {
        "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
    }

/** EXIF datetime "2026:10:16 05:42:18" -> "Oct 16, 2026, 5:42 AM". */
private fun parseExifDate(s: String): String? = try {
    val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s.trim())
    if (parsed == null) null
    else SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(parsed)
} catch (_: Exception) {
    null
}

/**
 * "Canon" + "Canon EOS R5" -> "Canon EOS R5" (no doubled make);
 * "NIKON CORPORATION" + "Z 8" -> "NIKON CORPORATION Z 8".
 * Shared by the ExifInterface path and the native RAW bridge so the
 * Camera row reads the same however the metadata arrived.
 */
private fun mergeMakeModel(make: String, model: String): String? {
    val mk = make.trim()
    val md = model.trim()
    return when {
        mk.isEmpty() && md.isEmpty() -> null
        md.startsWith(mk, ignoreCase = true) -> md
        mk.isEmpty() -> md
        md.isEmpty() -> mk
        else -> "$mk $md"
    }
}

/** LibRaw timestamps are Unix epoch seconds — render like EXIF capture dates. */
private fun formatExifEpoch(epochSeconds: Long): String = try {
    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(epochSeconds * 1000))
} catch (_: Exception) {
    "—"
}

/** [LibraryImage.dateAdded] is epoch seconds (MediaStore convention). */
private fun formatEpochSeconds(epochSeconds: Long): String = try {
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochSeconds * 1000))
} catch (_: Exception) {
    "—"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.US, bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(Locale.US, bytes / 1_000.0)
    else -> "$bytes B"
}
