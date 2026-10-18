package com.lumora.photoeditor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.engine.GlEngine
import com.lumora.photoeditor.export.BatchExportResult
import com.lumora.photoeditor.export.GlHost
import com.lumora.photoeditor.export.batchExport
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LoadingState
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraTopBar
import com.lumora.photoeditor.ui.components.ThumbImage
import com.lumora.photoeditor.ui.components.ToolbarButton
import com.lumora.photoeditor.ui.preview.BatchGlSurface
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Batch export: renders every selected library photo with its own saved
 * edits and saves them to the gallery, one tap for the whole queue
 * (Lightroom "export multiple" parity). Each photo goes through the exact
 * same GPU pipeline as the single-photo export, so batch output is
 * pixel-identical to exporting the photos one by one.
 *
 * Rendering happens on a headless 1dp GL surface ([BatchGlSurface]) — no
 * editor session is opened, so the user's current edits are untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchExportScreen(
    ids: List<String>,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val scope = rememberCoroutineScope()
    val form = rememberExportFormState(container)

    var glEngine by remember { mutableStateOf<GlEngine?>(null) }
    var glHost by remember { mutableStateOf<GlHost?>(null) }
    var glError by remember { mutableStateOf<String?>(null) }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    var liveResults by remember { mutableStateOf(mapOf<Int, BatchExportResult>()) }
    var doneResults by remember { mutableStateOf<List<BatchExportResult>?>(null) }
    /**
     * Batch-level failure (the whole run died, not just one photo). Without
     * this, an unexpected throw out of [batchExport] — e.g. OOM allocating
     * its stand-in bitmap outside its own try — killed the launch coroutine
     * and left [running] true forever: a dead spinner, Back "cancelling" a
     * dead job, no way out short of killing the app.
     */
    var batchError by remember { mutableStateOf<String?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }

    // Kept 1:1 with `ids` (nullable slots) instead of mapNotNull: a photo
    // deleted from the library between selection and export must still
    // occupy its index, or BatchRunning's progress index (into `ids`) would
    // misalign against the row list and show statuses on the wrong rows.
    val photos by produceState<List<LibraryImage?>?>(initialValue = null, ids) {
        value = withContext(Dispatchers.IO) {
            ids.map { id ->
                runCatching { container.library.byId(id) }.getOrNull()
            }
        }
    }

    fun cancelBatch() {
        cancelFlag.set(true)
        container.exports.cancel()
    }
    // Back during a run cancels the batch rather than abandoning it.
    BackHandler(enabled = running) { cancelBatch() }

    fun start() {
        val engine = glEngine ?: return
        val host = glHost ?: return
        val list = ids
        if (list.isEmpty()) return
        form.persistStickyDefaults()
        cancelFlag.set(false)
        running = true
        progress = null
        liveResults = emptyMap()
        doneResults = null
        batchError = null
        scope.launch {
            try {
                val res = batchExport(
                    container = container,
                    imageIds = list,
                    settings = form.buildSettings(),
                    glEngine = engine,
                    glHost = host,
                    // Callbacks arrive on Dispatchers.IO — hop to Main before
                    // touching Compose state.
                    onPhotoProgress = { i, p ->
                        scope.launch { progress = i to p }
                    },
                    onPhotoDone = { i, r ->
                        scope.launch { liveResults = liveResults + (i to r) }
                    },
                    shouldCancel = { cancelFlag.get() },
                )
                doneResults = res
            } catch (t: Throwable) {
                // Cancellation is cooperative via shouldCancel/cancel(); a
                // real coroutine cancellation must keep propagating.
                if (t is kotlinx.coroutines.CancellationException) throw t
                batchError = if (t is OutOfMemoryError)
                    "Not enough memory to run the batch export — try fewer " +
                        "photos or a smaller export size. Your photos are unchanged."
                else
                    "The batch export stopped unexpectedly " +
                        "(${t.message ?: "unknown error"}). Your photos are unchanged."
            } finally {
                // Always restored, even on failure: the screen can never get
                // stuck on its running spinner again.
                running = false
                progress = null
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            LumoraTopBar(
                title = "Batch export",
                subtitle = "${ids.size} photo${if (ids.size == 1) "" else "s"}",
                navigationIcon = {
                    ToolbarButton(
                        onClick = { if (running) cancelBatch() else onBack },
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Headless renderer for the batch. Invisible (1dp) but attached,
            // so EGL can create the context the export pipeline needs.
            BatchGlSurface(
                onReady = { engine, host ->
                    glEngine = engine
                    glHost = host
                },
                onError = { glError = it },
            )
            when {
                ids.isEmpty() -> EmptyState(
                    title = "Nothing to export",
                    body = "Select photos in the library first.",
                    actionLabel = "Back",
                    onAction = onBack,
                    // Neutral share glyph, not the error glyph: an empty
                    // selection is not a failure, so it must not read as one.
                    icon = LumoraIcons.IosShare,
                )
                doneResults != null -> BatchDone(
                    results = doneResults!!,
                    total = ids.size,
                    onBack = onBack,
                )
                batchError != null -> EmptyState(
                    title = "Batch export stopped",
                    body = batchError
                        ?: "Something went wrong. Your photos are unchanged.",
                    actionLabel = "Try again",
                    onAction = { batchError = null },
                    icon = LumoraIcons.Error,
                )
                running -> BatchRunning(
                    ids = ids,
                    photos = photos,
                    progress = progress,
                    liveResults = liveResults,
                    onCancel = ::cancelBatch,
                )
                else -> BatchSetup(
                    ids = ids,
                    photos = photos,
                    form = form,
                    container = container,
                    glReady = glEngine != null && glHost != null,
                    glError = glError,
                    onStart = ::start,
                )
            }
        }
    }
}

@Composable
private fun BatchSetup(
    ids: List<String>,
    photos: List<LibraryImage?>?,
    form: ExportFormState,
    container: AppContainer,
    glReady: Boolean,
    glError: String?,
    onStart: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PHOTOS", style = type.sectionTitle, color = colors.textSecondary)
        if (photos == null) {
            LoadingState("Loading photos…")
        } else {
            photos.forEach { img ->
                // A null slot means the photo vanished from the library
                // after selection; keep the row so indices stay aligned
                // with the export queue and the missing photo is visible.
                BatchPhotoRow(
                    img = img,
                    name = if (img == null) "Photo not found" else null,
                    status = null,
                    error = null,
                )
            }
        }
        ExportSettingsForm(form = form, container = container)
        Spacer(Modifier.height(8.dp))
        if (glError != null) {
            Text(
                "Batch export needs the 3D renderer, which failed to start " +
                    "on this device ($glError). Single-photo export from the " +
                    "editor may still work.",
                style = type.body, color = colors.danger,
            )
        }
        LumoraButton(
            onClick = onStart,
            // `photos` is 1:1 with `ids` and may hold null (missing) slots —
            // require at least one real photo, not just a non-empty list.
            enabled = glReady && photos?.any { it != null } == true,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent, contentColor = Color.Black,
                disabledContainerColor = colors.divider),
        ) {
            Text(
                if (!glReady && glError == null) "Preparing renderer…"
                else "Export ${ids.size} photo${if (ids.size == 1) "" else "s"}",
            )
        }
        Text(
            "Each photo is exported with its own edits. Files are saved to " +
                "Pictures/Lumora in your gallery.",
            style = type.caption, color = colors.textTertiary,
        )
    }
}

private enum class RowStatus { PENDING, RUNNING, DONE, FAILED }

@Composable
private fun BatchRunning(
    ids: List<String>,
    photos: List<LibraryImage?>?,
    progress: Pair<Int, Float>?,
    liveResults: Map<Int, BatchExportResult>,
    onCancel: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val (index, fraction) = progress ?: (0 to 0f)
    val overall = (index + fraction) / ids.size.coerceAtLeast(1)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "Exporting photo ${index + 1} of ${ids.size}…",
            style = type.screenTitle, color = colors.textPrimary,
        )
        Spacer(Modifier.height(16.dp))
        LumoraProgressIndicator(
            progress = { overall },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.divider,
            contentDescription = "Batch export progress",
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val list = photos ?: emptyList()
            // Rows stay 1:1 with `ids` (null slot = photo vanished from the
            // library), so the progress/result indices always point at the
            // right row.
            itemsIndexed(list, key = { i, img -> img?.id ?: "missing-$i" }) { i, img ->
                val result = liveResults[i]
                val status = when {
                    result != null -> if (result.succeeded) RowStatus.DONE else RowStatus.FAILED
                    i < index -> RowStatus.DONE
                    i == index -> RowStatus.RUNNING
                    else -> RowStatus.PENDING
                }
                BatchPhotoRow(
                    img = img,
                    name = if (img == null) "Photo not found" else null,
                    status = status,
                    error = result?.error,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LumoraOutlineButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Cancel", color = colors.textPrimary)
        }
    }
}

@Composable
private fun BatchDone(
    results: List<BatchExportResult>,
    total: Int,
    onBack: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val ok = results.count { it.succeeded }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Icon(
            if (ok == total) Icons.Default.CheckCircle else LumoraIcons.Error,
            null,
            tint = if (ok == total) colors.ok else colors.accent,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (ok == total) "Exported $ok photo${if (ok == 1) "" else "s"}"
            else "Exported $ok of $total photos",
            style = type.screenTitle, color = colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Saved to Pictures/Lumora in your gallery.",
            style = type.caption, color = colors.textTertiary,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(results, key = { _, r -> r.imageId }) { _, r ->
                BatchPhotoRow(
                    img = null,
                    name = r.displayName.ifEmpty { "Photo" },
                    status = if (r.succeeded) RowStatus.DONE else RowStatus.FAILED,
                    error = r.error,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LumoraButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent, contentColor = Color.Black),
        ) {
            Text("Back to library")
        }
    }
}

/**
 * One row in the batch photo list: thumbnail, name, dimensions and an
 * optional status marker (pending spinner / running / done / failed with
 * the error message).
 */
@Composable
private fun BatchPhotoRow(
    img: LibraryImage?,
    name: String? = null,
    status: RowStatus?,
    error: String?,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val label = name ?: img?.displayName ?: "Photo"
    val dims = img?.let { "${it.width} × ${it.height}" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (img?.contentUri != null) {
            ThumbImage(
                model = img.contentUri,
                contentDescription = null,
                modifier = Modifier.size(52.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(colors.panel),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, style = type.body, color = colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (dims != null) {
                Text(dims, style = type.caption, color = colors.textTertiary)
            }
            if (status == RowStatus.FAILED && error != null) {
                Text(error, style = type.caption, color = colors.danger,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        when (status) {
            RowStatus.RUNNING -> LumoraProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = colors.accent,
                strokeWidth = 2.5.dp,
                contentDescription = "Exporting photo",
            )
            RowStatus.DONE -> Icon(
                Icons.Default.CheckCircle, "Exported",
                tint = colors.ok, modifier = Modifier.size(22.dp),
            )
            RowStatus.FAILED -> Icon(
                LumoraIcons.Error, "Export failed",
                tint = colors.danger, modifier = Modifier.size(22.dp),
            )
            RowStatus.PENDING, null -> Unit
        }
    }
}
