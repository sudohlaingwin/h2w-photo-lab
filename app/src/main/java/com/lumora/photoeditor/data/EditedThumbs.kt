package com.lumora.photoeditor.data

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * On-disk cache of GPU-rendered "edited" thumbnails for the library grid
 * (Lightroom parity: the grid shows the edited photo, not the original).
 *
 * Thin `Context`/Bitmap wrapper around [EditedThumbsIo], which holds the
 * real contract (harness-tested on the JVM): validity is anchored to the
 * edit sidecar's last-modified timestamp, so a thumbnail is only shown while
 * its `.rev` file matches the sidecar's current mtime — never a stale edit.
 */
class EditedThumbs(context: Context) {
    private val io = EditedThumbsIo(File(context.filesDir, "thumbs"))

    fun thumbFile(imageId: String): File = io.thumbFile(imageId)

    /** Stores the thumbnail JPEG plus the sidecar revision it was made from. */
    fun save(imageId: String, bmp: Bitmap, sidecarMtime: Long): Boolean = try {
        val bytes = ByteArrayOutputStream().also { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)) return false
        }.toByteArray()
        io.save(imageId, bytes, sidecarMtime)
    } catch (_: Exception) {
        false
    }

    /**
     * Returns the thumbnail file iff it still matches [sidecarMtime].
     * Null means "show the original" — never a stale edit.
     */
    fun validThumb(imageId: String, sidecarMtime: Long): File? =
        io.validThumb(imageId, sidecarMtime)

    fun clear(imageId: String) = io.clear(imageId)
}
