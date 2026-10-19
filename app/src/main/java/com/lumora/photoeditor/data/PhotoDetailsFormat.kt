package com.lumora.photoeditor.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure metadata-formatting contract behind the library "Details" dialog
 * ([PhotoDetails]) — every string the dialog renders for file, capture and
 * camera rows is produced here, with zero Android/NDK dependencies so the
 * whole contract is pinned by the standalone JVM harness
 * (PhotoDetailsFormatTest) instead of the GL-bound SelfTest.
 *
 * Moved verbatim out of PhotoDetails.kt on 2026-10-20 (visibility
 * private -> internal, no behavior change); PhotoDetails.kt keeps the
 * Android-bound loader and a thin [formatLabelFor] wrapper that injects
 * the NDK-side RAW resolver.
 *
 * Deterministic, no machine learning. Date rendering uses the device
 * default timezone (same as before the move) — the harness pins UTC.
 */

/**
 * The format label for the File section of the details dialog. RAW files
 * resolve through [rawFormatName] (production passes RawDecoder's — the
 * same label the editor caption uses, so details and editor always agree);
 * common raster formats map to their familiar names. Unknown -> null (row
 * simply doesn't render).
 *
 * [rawFormatName] is injected so the harness can pin this contract without
 * the NDK-bound RawDecoder.
 */
internal fun formatLabelFor(
    displayName: String?,
    rawFormatName: (String?) -> String?,
): String? {
    val raw = rawFormatName(displayName)
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

/** EXIF rationals look like "28/10" or "1/125". */
internal fun parseRational(s: String?): Double? {
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
internal fun formatShutter(seconds: Double): String =
    if (seconds >= 1.0) {
        val s = "%.1f".format(Locale.US, seconds).trimEnd('0').trimEnd('.')
        "${s}s"
    } else {
        "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
    }

/** EXIF datetime "2026:10:16 05:42:18" -> "Oct 16, 2026, 5:42 AM". */
internal fun parseExifDate(s: String): String? = try {
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
internal fun mergeMakeModel(make: String, model: String): String? {
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
internal fun formatExifEpoch(epochSeconds: Long): String = try {
    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(epochSeconds * 1000))
} catch (_: Exception) {
    "—"
}

/** [LibraryImage.dateAdded] is epoch seconds (MediaStore convention). */
internal fun formatEpochSeconds(epochSeconds: Long): String = try {
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochSeconds * 1000))
} catch (_: Exception) {
    "—"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.US, bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(Locale.US, bytes / 1_000.0)
    else -> "$bytes B"
}
