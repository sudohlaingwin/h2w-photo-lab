package com.lumora.photoeditor.export

import android.graphics.Bitmap
import android.net.Uri
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.engine.ExportSettings
import com.lumora.photoeditor.engine.GlEngine
import com.lumora.photoeditor.engine.MaskEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one photo inside a batch export. */
data class BatchExportResult(
    val imageId: String,
    val displayName: String,
    val uri: Uri?,
    /** Null when the export succeeded. */
    val error: String?,
) {
    val succeeded: Boolean get() = uri != null
}

/**
 * Exports several library photos sequentially with the same [settings],
 * reusing one GL engine/host (owned by the batch-export screen's headless
 * GL surface). Each photo is rendered with its own saved [EditState] —
 * masks are rasterized at export resolution exactly like the single-photo
 * export path, so batch output is pixel-identical to exporting each photo
 * individually.
 *
 * Photos are independent: one photo failing (unreadable file, OOM, …)
 * records an error for that photo and the batch continues with the next.
 * [shouldCancel] is checked between photos; the in-flight export is also
 * cancelled through [ExportManager.cancel].
 *
 * Runs on [Dispatchers.IO]; [onPhotoProgress]/[onPhotoDone] are invoked
 * from that background context — hop to Main before touching Compose
 * state.
 */
suspend fun batchExport(
    container: AppContainer,
    imageIds: List<String>,
    settings: ExportSettings,
    glEngine: GlEngine,
    glHost: GlHost,
    onPhotoProgress: (index: Int, fraction: Float) -> Unit = { _, _ -> },
    onPhotoDone: (index: Int, result: BatchExportResult) -> Unit = { _, _ -> },
    shouldCancel: () -> Boolean = { false },
): List<BatchExportResult> = withContext(Dispatchers.IO) {
    val errors = settings.validate()
    if (errors.isNotEmpty()) {
        return@withContext imageIds.map {
            BatchExportResult(it, "", null, errors.joinToString("; "))
        }
    }
    // Stand-in for the "preview" assets the single-photo export swaps back
    // in afterwards. Batch mode has no live preview session, so there is
    // nothing to restore — these pixels are never shown.
    val standIn = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    try {
        imageIds.mapIndexed { index, id ->
            if (shouldCancel()) {
                BatchExportResult(id, "", null, "Cancelled")
                    .also { onPhotoDone(index, it) }
            } else {
                exportOne(container, id, index, imageIds.size, settings,
                    glEngine, glHost, standIn, onPhotoProgress)
                    .also { onPhotoDone(index, it) }
            }
        }
    } finally {
        runCatching { if (!standIn.isRecycled) standIn.recycle() }
    }
}

private suspend fun exportOne(
    container: AppContainer,
    id: String,
    index: Int,
    total: Int,
    settings: ExportSettings,
    glEngine: GlEngine,
    glHost: GlHost,
    standIn: Bitmap,
    onPhotoProgress: (Int, Float) -> Unit,
): BatchExportResult {
    val img = try {
        container.library.byId(id)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    } ?: return BatchExportResult(id, "", null, "Photo not found in library")
    val name = img.displayName
    val uri = try {
        Uri.parse(img.contentUri
            ?: return BatchExportResult(id, name, null, "Photo has no readable file"))
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        return BatchExportResult(id, name, null, "Photo has no readable file")
    }
    return try {
        val state: EditState = container.editStore.load(id)
        // Export-resolution masks (1024), same recipe as the single-photo
        // path in EditorViewModel.export. A mask failure fails this photo,
        // not the batch — an export must reflect the real edits.
        val exportMasks = try {
            withContext(Dispatchers.Default) {
                state.masks.filter { it.enabled }.take(GlEngine.MAX_MASKS)
                    .map { m ->
                        val base = MaskEngine.rasterize(m, container.aiMasks)
                        Bitmap.createScaledBitmap(base, 1024, 1024, true)
                            .also { base.recycle() }
                    }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val msg = if (t is OutOfMemoryError)
                "Not enough memory to prepare this photo — try a smaller export size"
            else "Could not prepare masks (${t.message ?: "error"})"
            return BatchExportResult(id, name, null, msg)
        }
        try {
            // Per-photo file name: a fixed custom name (or the bare
            // timestamp default) would otherwise repeat for every photo, so
            // each export gets a distinct gallery name.
            val perPhoto = settings.copy(
                fileName = perPhotoFileName(settings.fileName, name, index, total),
            )
            val result = container.exports.export(
                ExportManager.Job(
                    sourceUri = uri,
                    state = state,
                    settings = perPhoto,
                    previewBitmap = standIn,
                    previewMasks = emptyList(),
                    previewRetouch = null,
                    exportMasks = exportMasks,
                    retouchSpots = state.retouch.spots,
                    glEngine = glEngine,
                    glHost = glHost,
                )
            ) { p -> onPhotoProgress(index, p) }
            result.fold(
                onSuccess = { BatchExportResult(id, name, it, null) },
                onFailure = {
                    if (it is CancellationException) throw it
                    BatchExportResult(id, name, null, it.message ?: "Export failed")
                },
            )
        } finally {
            exportMasks.forEach {
                runCatching { if (!it.isRecycled) it.recycle() }
            }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        val msg = if (t is OutOfMemoryError)
            "Not enough memory to export this photo — try a smaller export size or close other apps"
        else t.message ?: "Export failed"
        BatchExportResult(id, name, null, msg)
    }
}

/**
 * Distinct gallery file stem per photo in a batch. A blank base falls back
 * to the source photo's own file stem (so "IMG_2041.jpg" exports as
 * "IMG_2041.jpg", not a bare timestamp); with more than one photo an index
 * suffix keeps the names distinct. [sanitizeExportName] is applied later by
 * [ExportSettings.galleryFileName].
 */
private fun perPhotoFileName(
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
