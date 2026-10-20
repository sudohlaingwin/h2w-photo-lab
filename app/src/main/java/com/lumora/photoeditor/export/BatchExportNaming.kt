package com.lumora.photoeditor.export

/**
 * Distinct gallery file stem per photo in a batch. A blank base falls back
 * to the source photo's own file stem (so "IMG_2041.jpg" exports as
 * "IMG_2041.jpg", not a bare timestamp); with more than one photo an index
 * suffix keeps the names distinct. [com.lumora.photoeditor.engine.sanitizeExportName]
 * is applied later by [com.lumora.photoeditor.engine.ExportSettings.galleryFileName].
 *
 * Lives in its own file (rather than inside BatchExport.kt) so this naming
 * contract stays testable on a plain JVM — BatchExport.kt itself needs
 * Android. Pure Kotlin, no Android dependencies.
 */
internal fun perPhotoFileName(
    base: String,
    displayName: String,
    index: Int,
    total: Int,
): String {
    val stem = base.ifBlank {
        displayName.substringBeforeLast('.').ifBlank { displayName }
    }
    return if (total > 1) "$stem ${index + 1}" else stem
}
