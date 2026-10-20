package com.lumora.photoeditor.data

import java.io.File

/**
 * The pure file-IO core of the edited-thumbnail cache (see [EditedThumbs],
 * which stays a thin `Context`/Bitmap wrapper around this).
 *
 * The constructor takes the directory itself (not a Context) so the class is
 * unit-testable on the JVM — the same convention as [RawThumbs] and
 * [SnapshotSidecarIo]. Validity is anchored to the edit sidecar's
 * last-modified timestamp: a thumbnail is only shown while its `.rev` file
 * matches the sidecar's current mtime, so any edit made outside the editor
 * (paste settings, reset) automatically invalidates it instead of showing a
 * stale preview.
 *
 * Every entry is defensive: nothing here throws. Storage failures degrade to
 * "no thumbnail", never a failed save or a crashed library grid.
 */
class EditedThumbsIo(dir: File) {
    private val root: File = dir.apply { mkdirs() }

    fun thumbFile(imageId: String): File = File(root, "$imageId.jpg")
    private fun revFile(imageId: String): File = File(root, "$imageId.rev")

    /**
     * Stores the thumbnail JPEG bytes plus the sidecar revision they were
     * made from. False when the bytes are empty or the write fails — a zero-
     * byte "thumbnail" would otherwise pass the revision check and show a
     * broken image in the grid.
     */
    fun save(imageId: String, jpegBytes: ByteArray, sidecarMtime: Long): Boolean {
        if (jpegBytes.isEmpty()) return false
        return try {
            thumbFile(imageId).outputStream().use { it.write(jpegBytes) }
            revFile(imageId).writeText(sidecarMtime.toString())
            true
        } catch (_: Exception) {
            false
        }
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
        try {
            thumbFile(imageId).delete()
            revFile(imageId).delete()
        } catch (_: Exception) {
            // best effort; a leftover thumb can only make validThumb()
            // return it while the rev still matches, which clear()'s callers
            // pair with an edit/save that rewrites the rev anyway
        }
    }
}
