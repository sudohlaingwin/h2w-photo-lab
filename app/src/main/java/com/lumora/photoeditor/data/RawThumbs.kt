package com.lumora.photoeditor.data

import java.io.File

/**
 * RAW program Phase 2 slice 2: on-disk cache of the camera's embedded
 * preview JPEG for imported RAW photos, shown by the library grid.
 *
 * Coil cannot decode RAW files (CR2/NEF/DNG/...), so without this the grid
 * showed a blank cell for every RAW import. At import time the repository
 * stages the RAW stream, extracts the embedded preview through the native
 * LibRaw bridge (unpack_thumb — fast, no demosaic) and saves the JPEG bytes
 * here, keyed by the library row id. The thumbnail is written once at import
 * and deleted with the photo, so no revision key is needed (contrast
 * [EditedThumbs], whose files are overwritten in place on every edit).
 *
 * The constructor takes the directory itself (not a Context) so the class is
 * unit-testable on the JVM — see hidden_files/rawthumb-harness/.
 * Every entry is defensive: nothing here throws. Storage failures degrade to
 * "no thumbnail", never a failed import or a crashed grid.
 */
class RawThumbs(dir: File) {
    private val root: File = dir.apply { mkdirs() }

    /**
     * Resolves the thumbnail path for [imageId]. Ids are restricted to
     * alphanumerics, dash and underscore, and a canonical-path guard keeps
     * even a hostile id inside the store directory.
     */
    fun thumbFile(imageId: String): File {
        val safe = imageId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { "x" }
        val f = File(root, "$safe.jpg")
        return try {
            if (f.canonicalPath.startsWith(root.canonicalPath + File.separator)) f
            else File(root, "x.jpg")
        } catch (_: Exception) {
            File(root, "x.jpg")
        }
    }

    fun exists(imageId: String): Boolean = try {
        thumbFile(imageId).isFile
    } catch (_: Exception) {
        false
    }

    /** Stores the JPEG bytes; false when they are empty or unwritable. */
    fun save(imageId: String, jpegBytes: ByteArray): Boolean {
        if (jpegBytes.isEmpty()) return false
        return try {
            thumbFile(imageId).outputStream().use { it.write(jpegBytes) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear(imageId: String) {
        try {
            thumbFile(imageId).delete()
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }
}
