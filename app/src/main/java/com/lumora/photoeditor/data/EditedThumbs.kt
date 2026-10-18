package com.lumora.photoeditor.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * On-disk cache of GPU-rendered "edited" thumbnails for the library grid
 * (Lightroom parity: the grid shows the edited photo, not the original).
 *
 * Validity is anchored to the edit sidecar's last-modified timestamp: a
 * thumbnail is only shown while its `.rev` file matches the sidecar's
 * current mtime, so any edit made outside the editor (paste settings,
 * reset) automatically invalidates it instead of showing a stale preview.
 */
class EditedThumbs(context: Context) {
    private val dir = File(context.filesDir, "thumbs").also { it.mkdirs() }

    fun thumbFile(imageId: String): File = File(dir, "$imageId.jpg")
    private fun revFile(imageId: String): File = File(dir, "$imageId.rev")

    /** Stores the thumbnail JPEG plus the sidecar revision it was made from. */
    fun save(imageId: String, bmp: Bitmap, sidecarMtime: Long): Boolean = try {
        thumbFile(imageId).outputStream().use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)) return false
        }
        revFile(imageId).writeText(sidecarMtime.toString())
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Returns the thumbnail file iff it still matches [sidecarMtime].
     * Null means "show the original" — never a stale edit.
     */
    fun validThumb(imageId: String, sidecarMtime: Long): File? {
        return try {
            val thumb = thumbFile(imageId)
            if (!thumb.exists()) return null
            val rev = revFile(imageId).takeIf { it.exists() }?.readText()?.toLongOrNull()
            if (rev == sidecarMtime) thumb else null
        } catch (_: Exception) {
            null
        }
    }

    fun clear(imageId: String) {
        thumbFile(imageId).delete()
        revFile(imageId).delete()
    }
}
