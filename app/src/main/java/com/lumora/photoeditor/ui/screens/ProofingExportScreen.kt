package com.lumora.photoeditor.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.engine.GlEngine
import com.lumora.photoeditor.export.ContactSheetOptions
import com.lumora.photoeditor.export.ContactSheetOutcome
import com.lumora.photoeditor.export.GlHost
import com.lumora.photoeditor.export.buildContactSheets
import com.lumora.photoeditor.export.contactSheetShareIntent
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LoadingState
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.ui.components.LumoraTopBar
import com.lumora.photoeditor.ui.components.ToolbarButton
import com.lumora.photoeditor.ui.preview.BatchGlSurface
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase C#7 slice 2 — contact-sheet export for a proofing gallery
 * (Lightroom "print contact sheet" parity, the share half of the
 * share/select workflow). Renders every gallery photo with its saved edits
 * on a headless GL surface, composes one or more JPEG sheets (header,
 * captions, picked/rejected badges), and offers Share (send to the client)
 * or Save to the device gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProofingExportScreen(
    galleryId: String,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val galleries by container.library.galleries.collectAsState()
    val gallery = galleries.firstOrNull { it.id == galleryId }

    var glEngine by remember { mutableStateOf<GlEngine?>(null) }
    var glHost by remember { mutableStateOf<GlHost?>(null) }
    var glError by remember { mutableStateOf<String?>(null) }

    var columns by remember { mutableStateOf(0) } // 0 = auto
    var showMarks by remember { mutableStateOf(true) }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var outcome by remember { mutableStateOf<ContactSheetOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }

    fun cancelRun() {
        cancelFlag.set(true)
        container.exports.cancel()
    }
    BackHandler(enabled = running) { cancelRun() }

    fun start() {
        val engine = glEngine ?: return
        val host = glHost ?: return
        val g = gallery ?: return
        cancelFlag.set(false)
        running = true
        progress = null
        outcome = null
        error = null
        notice = null
        scope.launch {
            try {
                val res = buildContactSheets(
                    container = container,
                    gallery = g,
                    options = ContactSheetOptions(columns = columns, showMarks = showMarks),
                    glEngine = engine,
                    glHost = host,
                    onPhotoProgress = { done, total ->
                        scope.launch { progress = done to total }
                    },
                    shouldCancel = { cancelFlag.get() },
                )
                res.fold(
                    onSuccess = { outcome = it },
                    onFailure = {
                        error = if (it is kotlinx.coroutines.CancellationException)
                            "Cancelled" else it.message ?: "Could not build the contact sheet"
                    },
                )
            } catch (t: Throwable) {
                // buildContactSheets returns a Result, but a throw outside its
                // own guards must not leave the screen stuck on its spinner.
                if (t is kotlinx.coroutines.CancellationException) throw t
                error = if (t is OutOfMemoryError)
                    "Not enough memory to build the contact sheet — try fewer photos"
                else t.message ?: "Could not build the contact sheet"
            } finally {
                running = false
                progress = null
            }
        }
    }

    fun share() {
        val files = outcome?.files ?: return
        val intent = contactSheetShareIntent(context, files)
        if (intent != null) {
            // A share sheet exists on normal devices, but managed profiles
            // can disable sharing — never crash on a missing handler; the
            // contact sheet file is already built.
            try {
                context.startActivity(Intent.createChooser(intent, "Share contact sheet"))
            } catch (_: Exception) {
                notice = "No app available to share the contact sheet"
            }
        } else {
            notice = "Could not prepare the share — try again"
        }
    }

    fun saveToGallery() {
        val files = outcome?.files ?: return
        scope.launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                for (f in files) {
                    // One corrupt sheet file must not kill the whole save:
                    // decode sits outside the old try, so a throw here died
                    // the coroutine with no notice ever set.
                    val bmp = try {
                        BitmapFactory.decodeFile(f.absolutePath)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        null
                    } ?: continue
                    try {
                        val uri = container.exports.saveBitmapToGallery(
                            bmp,
                            f.nameWithoutExtension,
                        )
                        if (uri != null) saved++
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
            }
            notice = if (saved == files.size)
                "Saved ${if (saved == 1) "contact sheet" else "$saved sheets"} to your gallery"
            else "Saved $saved of ${files.size} — storage may be unavailable"
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            LumoraTopBar(
                title = "Contact sheet",
                subtitle = gallery?.let {
                    "${it.name} · ${it.photoIds.size} " +
                        if (it.photoIds.size == 1) "photo" else "photos"
                },
                navigationIcon = {
                    ToolbarButton(
                        onClick = { if (running) cancelRun() else onBack },
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            BatchGlSurface(
                onReady = { engine, host ->
                    glEngine = engine
                    glHost = host
                },
                onError = { glError = it },
            )
            when {
                gallery == null -> EmptyState(
                    title = "Gallery not found",
                    body = "This proofing gallery no longer exists.",
                    actionLabel = "Back",
                    onAction = onBack,
                    icon = LumoraIcons.Error,
                )
                gallery.photoIds.isEmpty() -> EmptyState(
                    title = "No photos yet",
                    body = "Add photos to \"${gallery.name}\" before making a contact sheet.",
                    actionLabel = "Back",
                    onAction = onBack,
                    icon = LumoraIcons.Landscape,
                )
                outcome != null -> SheetDone(
                    outcome = outcome!!,
                    galleryName = gallery.name,
                    notice = notice,
                    onShare = ::share,
                    onSave = ::saveToGallery,
                    onBack = onBack,
                )
                running -> SheetRunning(
                    progress = progress,
                    total = gallery.photoIds.size,
                    error = error,
                    onCancel = ::cancelRun,
                )
                else -> SheetSetup(
                    photoCount = gallery.photoIds.size,
                    columns = columns,
                    onColumns = { columns = it },
                    showMarks = showMarks,
                    onShowMarks = { showMarks = it },
                    glReady = glEngine != null && glHost != null,
                    glError = glError,
                    error = error,
                    onStart = ::start,
                )
            }
        }
    }
}

@Composable
private fun SheetSetup(
    photoCount: Int,
    columns: Int,
    onColumns: (Int) -> Unit,
    showMarks: Boolean,
    onShowMarks: (Boolean) -> Unit,
    glReady: Boolean,
    glError: String?,
    error: String?,
    onStart: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LAYOUT", style = type.sectionTitle, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(0 to "Auto", 3 to "3", 4 to "4", 5 to "5")
            for ((value, label) in options) {
                LumoraChip(
                    selected = columns == value,
                    onClick = { onColumns(value) },
                    label = { Text(label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Picked / rejected badges",
                    style = type.body, color = colors.textPrimary)
                Text("Show the client's marks on each photo and in the header.",
                    style = type.caption, color = colors.textTertiary)
            }
            LumoraSwitch(
                checked = showMarks,
                onCheckedChange = onShowMarks,
                contentDescription = "Picked and rejected badges",
            )
        }
        Text(
            "Each photo is rendered with its current edits, then tiled " +
                "onto a shareable sheet — $photoCount " +
                if (photoCount == 1) "photo" else "photos" + ".",
            style = type.caption,
            color = colors.textTertiary,
        )
        if (glError != null) {
            Text(
                "The photo renderer could not start ($glError) — " +
                    "contact sheets are unavailable on this device.",
                style = type.body,
                color = colors.danger,
            )
        }
        if (error != null) {
            Text(error, style = type.body, color = colors.danger)
        }
        Spacer(Modifier.weight(1f))
        LumoraButton(
            onClick = onStart,
            enabled = glReady && glError == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Build contact sheet")
        }
    }
}

@Composable
private fun SheetRunning(
    progress: Pair<Int, Int>?,
    total: Int,
    error: String?,
    onCancel: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        LoadingState("Rendering photos…")
        val (done, of) = progress ?: (0 to total)
        LumoraProgressIndicator(
            progress = { if (of > 0) done.toFloat() / of else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$done of $of photos",
            style = type.body,
            color = colors.textSecondary,
        )
        if (error != null) {
            Text(error, style = type.body, color = colors.danger)
        }
        LumoraOutlineButton(onClick = onCancel) {
            Text("Cancel")
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SheetDone(
    outcome: ContactSheetOutcome,
    galleryName: String,
    notice: String?,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    // Downsampled preview of the first page — the full sheet is far too
    // big to hold in Compose memory twice.
    val preview = remember(outcome.files) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        runCatching {
            BitmapFactory.decodeFile(outcome.files.first().absolutePath, opts)
        }.getOrNull()
    }
    // The decoded preview is ours to release: remember() drops the old value
    // without cleanup when a new outcome arrives, and on screen exit.
    DisposableEffect(outcome.files) {
        onDispose {
            if (preview != null && !preview.isRecycled) preview.recycle()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val summary = buildString {
            append(outcome.rendered)
            append(if (outcome.rendered == 1) " photo rendered" else " photos rendered")
            if (outcome.pages > 1) append(" · ${outcome.pages} pages")
            if (outcome.failed > 0) append(" · ${outcome.failed} unavailable")
        }
        Text(summary, style = type.body, color = colors.textSecondary)
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Contact sheet preview for $galleryName",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (notice != null) {
            Text(notice, style = type.body, color = colors.textSecondary)
        }
        LumoraButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text(if (outcome.pages > 1) "Share sheets" else "Share contact sheet")
        }
        LumoraOutlineButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("Save to gallery")
        }
        LumoraOutlineButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
