package com.lumora.photoeditor.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.LibraryImage
import com.lumora.photoeditor.engine.ExportFormat
import com.lumora.photoeditor.engine.ExportSettings
import com.lumora.photoeditor.engine.WatermarkAlign
import com.lumora.photoeditor.engine.WatermarkSettings
import com.lumora.photoeditor.engine.watermarkLayout
import com.lumora.photoeditor.ui.components.LoadingState
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.ThumbImage
import com.lumora.photoeditor.ui.components.ToolbarButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraTopBar
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.state.ExportUiState
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    vm: EditorViewModel,
    imageId: String,
    onBack: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current
    val container = remember { (context.applicationContext as com.lumora.photoeditor.LumoraApp).container }
    val exportUi by vm.exportUi.collectAsState()

    // Shared export settings form (single-photo and batch export screens
    // offer identical settings backed by the same sticky defaults).
    val form = rememberExportFormState(container)

    // sharedGlHost is a plain var, not state-backed: the editor's onGlHost
    // callback fires asynchronously (GL init), and the Export toolbar
    // button is always tappable, so a fast tap can land here before the
    // host exists. Without a state-backed read, the "Preparing renderer…"
    // disabled state would stick forever even once the host arrives.
    var glReady by remember(imageId) { mutableStateOf(vm.sharedGlHost != null) }

    LaunchedEffect(imageId) {
        // ensure the editor session is loaded (normally already open beneath)
        vm.open(imageId)
        // Refresh the GPU-rendered edited thumbnail so the export preview
        // shows the current edits, not a stale revision. Never throws —
        // a missing GL host just leaves the preview on the original photo.
        runCatching { vm.refreshEditedThumbnail() }
        // Bounded re-check (not an open poll): give the late-arriving
        // onGlHost callback a few seconds to land, then flip the UI once.
        if (!glReady) {
            repeat(12) {
                kotlinx.coroutines.delay(500)
                if (vm.sharedGlHost != null) {
                    glReady = true
                    return@LaunchedEffect
                }
            }
            glReady = vm.sharedGlHost != null
        }
    }
    DisposableEffect(Unit) { onDispose { vm.resetExportUi() } }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            LumoraTopBar(
                title = "Export",
                navigationIcon = {
                    ToolbarButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val ui = exportUi) {
                is ExportUiState.Idle -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("PREVIEW", style = type.sectionTitle, color = colors.textSecondary)
                        ExportPreview(
                            imageId = imageId,
                            container = container,
                            watermark = WatermarkSettings(
                                enabled = form.wmEnabled,
                                text = form.wmText,
                                position = form.wmPos,
                                sizeFrac = form.wmSize,
                                opacity = form.wmOpacity,
                            ),
                            longEdge = form.longEdge,
                        )
                        ExportSettingsForm(form = form, container = container)
                        LumoraButton(
                            onClick = {
                                val host = vm.sharedGlHost
                                if (host == null) return@LumoraButton
                                form.persistStickyDefaults()
                                vm.export(form.buildSettings(), host)
                            },
                            enabled = glReady,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent, contentColor = Color.Black,
                                disabledContainerColor = colors.divider),
                        ) {
                            Text(if (glReady) "Export to gallery"
                            else "Preparing renderer…")
                        }
                        if (!glReady) {
                            Text("The preview renderer wasn't ready. Go back to the editor " +
                                "so the photo loads, then return here.",
                                style = type.caption, color = colors.textTertiary)
                        }
                    }
                }
                is ExportUiState.Running -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LumoraProgressIndicator(
                            progress = { ui.progress },
                            modifier = Modifier.size(72.dp),
                            color = colors.accent,
                            trackColor = colors.divider,
                            strokeWidth = 6.dp,
                            contentDescription = null,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(exportPhaseLabel(ui.progress),
                            style = type.body, color = colors.textSecondary)
                        Spacer(Modifier.height(24.dp))
                        LumoraOutlineButton(onClick = { container.exports.cancel() },
                            shape = RoundedCornerShape(10.dp)) {
                            Text("Cancel", color = colors.textPrimary)
                        }
                    }
                }
                is ExportUiState.Done -> {
                    ExportDone(
                        uri = ui.uri,
                        settings = ui.settings,
                        onShare = {
                            val intent = container.exports.shareIntent(
                                ui.uri, formatMimeTypeFor(ui.settings.format))
                            // A share sheet exists on normal devices, but
                            // managed profiles can disable sharing — never
                            // crash right after a successful export; the
                            // photo is already saved to the gallery.
                            try {
                                context.startActivity(Intent.createChooser(intent, "Share photo"))
                            } catch (_: Exception) {
                                Toast.makeText(context,
                                    "No app available to share this photo",
                                    Toast.LENGTH_LONG).show()
                            }
                        },
                        onDone = onBack,
                    )
                }
                is ExportUiState.Error -> {
                    ExportResult(
                        success = false,
                        message = ui.message,
                        onShare = {},
                        onDone = onBack,
                        onRetry = { vm.resetExportUi() },
                    )
                }
            }
        }
    }
}

/**
 * Live export preview (Lightroom parity: Lightroom's export dialog shows what
 * you're about to get). Shows the GPU-rendered edited thumbnail while it is
 * still valid for the current edit revision, otherwise the original photo,
 * with the watermark drawn on top exactly as the export will stamp it —
 * same [watermarkLayout] math and same Paint recipe as the export package's
 * [applyWatermark][com.lumora.photoeditor.export.applyWatermark] — so
 * position / size / opacity can be judged before exporting instead of after.
 */
@Composable
private fun ExportPreview(
    imageId: String,
    container: AppContainer,
    watermark: WatermarkSettings,
    longEdge: Int,
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    // Same freshness protocol as the library grid: the edited thumbnail is
    // overwritten in place at a stable path, so the sidecar mtime doubles as
    // the Coil cache key (cycle 75).
    val thumbBump by container.thumbRevision.collectAsState()
    val sidecarMtime = remember(imageId, thumbBump) {
        container.editStore.sidecarLastModified(imageId)
    }
    val editedThumb by produceState<File?>(initialValue = null, imageId, thumbBump) {
        value = withContext(Dispatchers.IO) {
            container.editedThumbs.validThumb(imageId, sidecarMtime)
        }
    }
    val libImage by produceState<LibraryImage?>(initialValue = null, imageId) {
        value = withContext(Dispatchers.IO) {
            runCatching { container.library.byId(imageId) }.getOrNull()
        }
    }
    val rawUri = remember(libImage) {
        libImage?.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val wmActive = watermark.isActive()
    val previewDesc = buildString {
        append("Export preview")
        if (wmActive) append("; watermark \"${watermark.text.trim()}\" at ${watermark.position.label}")
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panel)
                .onSizeChanged { boxSize = it }
                .semantics { contentDescription = previewDesc },
        ) {
            ThumbImage(
                model = editedThumb ?: rawUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                cacheKey = editedThumb?.let { "$imageId-$sidecarMtime" },
            )
            if (wmActive && boxSize.width > 0 && boxSize.height > 0) {
                Canvas(Modifier.fillMaxSize()) {
                    val layout = watermarkLayout(boxSize.width, boxSize.height, watermark)
                    val paint = android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        alpha = (watermark.opacity * 255f).roundToInt().coerceIn(0, 255)
                        textSize = layout.textPx
                        typeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD)
                        textAlign = when (layout.align) {
                            WatermarkAlign.LEFT -> android.graphics.Paint.Align.LEFT
                            WatermarkAlign.CENTER -> android.graphics.Paint.Align.CENTER
                            WatermarkAlign.RIGHT -> android.graphics.Paint.Align.RIGHT
                        }
                        // soft drop shadow: same recipe as the export stamper
                        setShadowLayer(layout.textPx * 0.08f, 0f, layout.textPx * 0.05f,
                            android.graphics.Color.argb(170, 0, 0, 0))
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        watermark.text.trim(), layout.x, layout.baselineY, paint)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val dimsText = libImage?.let { img ->
            val scale = if (longEdge > 0)
                (longEdge / maxOf(img.width, img.height).toFloat()).coerceAtMost(1f)
            else 1f
            "${(img.width * scale).roundToInt()} × ${(img.height * scale).roundToInt()} px"
        }
        Text(
            buildString {
                if (dimsText != null) append("Export size: $dimsText. ")
                if (wmActive) append("Watermark shown as it will appear.")
                else append("Turn on the watermark below to preview its placement here.")
            },
            style = type.caption, color = colors.textTertiary)
    }
}

@Composable
private fun ExportResult(
    success: Boolean,
    message: String,
    onShare: () -> Unit,
    onDone: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (success) Icons.Default.CheckCircle else LumoraIcons.Error,
            null,
            tint = if (success) colors.ok else colors.danger,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (success) "Export complete" else "Export failed",
            style = type.screenTitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(message, style = type.body, color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        if (success) {
            LumoraButton(
                onClick = onShare,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent, contentColor = Color.Black),
            ) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            Spacer(Modifier.height(10.dp))
            LumoraOutlineButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)) {
                Text("Done", color = colors.textPrimary)
            }
        } else {
            if (onRetry != null) {
                LumoraButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = Color.Black),
                ) { Text("Try again") }
                Spacer(Modifier.height(10.dp))
            }
            LumoraOutlineButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)) {
                Text("Back", color = colors.textPrimary)
            }
        }
    }
}

/** Phase-aware progress label, derived from ExportManager's known bands. */
private fun exportPhaseLabel(p: Float): String = when {
    p < 0.08f -> "Decoding photo…"
    p < 0.78f -> "Rendering edits… ${(p * 100).toInt()}%"
    p < 0.92f -> "Encoding…"
    else -> "Saving to gallery…"
}

/** Metadata read from the finished gallery file; all fields best-effort. */
private data class ExportFileInfo(
    val displayName: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

private suspend fun readExportFileInfo(context: Context, uri: Uri): ExportFileInfo? =
    withContext(Dispatchers.IO) {
        try {
            var name: String? = null
            var size = -1L
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.SIZE,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(
                            c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                        size = c.getLong(
                            c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                    }
                }
            }
            var w = 0
            var h = 0
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                w = opts.outWidth
                h = opts.outHeight
            }
            ExportFileInfo(name, size, w, h)
        } catch (_: Throwable) {
            null
        }
    }

// Locale.US throughout: a bare "%.1f" follows the device default locale
// and would render "12,4 MB" on comma-decimal locales (his Burmese-UI vivo).
private fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

/**
 * Rich export result: shows the finished photo, its gallery file name and
 * real stats (dimensions, file size, format/quality) instead of a bare
 * "Saved to your gallery." Every metadata read is guarded — missing data
 * just hides that stat, never errors.
 */
@Composable
private fun ExportDone(
    uri: Uri,
    settings: ExportSettings,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val context = LocalContext.current
    val info by produceState<ExportFileInfo?>(initialValue = null, uri) {
        value = readExportFileInfo(context, uri)
    }
    val name = info?.displayName ?: settings.galleryFileName()
    val formatLabel = when (settings.format) {
        ExportFormat.JPEG -> "JPEG"
        ExportFormat.PNG -> "PNG"
        ExportFormat.WEBP -> "WebP"
    }
    val stats = buildList {
        val i = info
        if (i != null && i.width > 0 && i.height > 0)
            add("${i.width} × ${i.height} px")
        if (i != null && i.sizeBytes > 0) add(formatFileSize(i.sizeBytes))
        add(if (settings.format == ExportFormat.PNG) formatLabel
            else "$formatLabel q${settings.quality}")
    }.joinToString("  ·  ")
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Export complete", style = type.screenTitle,
            color = colors.textPrimary)
        Spacer(Modifier.height(16.dp))
        ThumbImage(
            model = uri,
            contentDescription = "Exported photo: $name",
            cacheKey = uri.toString(),
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panel),
        )
        Spacer(Modifier.height(12.dp))
        Text(name, style = type.body, color = colors.textPrimary,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(stats, style = type.caption, color = colors.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        LumoraButton(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent, contentColor = Color.Black),
        ) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
        Spacer(Modifier.height(10.dp))
        LumoraOutlineButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)) {
            Text("Done", color = colors.textPrimary)
        }
    }
}
