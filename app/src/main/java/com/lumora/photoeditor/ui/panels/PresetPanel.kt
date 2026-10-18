package com.lumora.photoeditor.ui.panels

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.data.Presets
import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.engine.Preset
import com.lumora.photoeditor.export.GlHost
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.LumoraDialog
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraSlider
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraBottomSheet
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import kotlinx.coroutines.*

/**
 * Preset browser with real GPU-rendered thumbnails (each preset is rendered
 * through the actual pipeline onto the current photo).
 */
private const val TAG_PRESET_PANEL = "PresetPanel"
/** Max wait for one GPU thumbnail render before giving up on it. */
private const val THUMB_TIMEOUT_MS = 10_000L
/** Max wait for the GL-thread source-restore after each thumbnail. */
private const val RESTORE_TIMEOUT_MS = 5_000L
@Composable
fun PresetPanel(
    vm: EditorViewModel,
    container: AppContainer,
    glHost: GlHost?,
    onNotice: (String) -> Unit,
) {
    // Built-ins are static; custom (user-saved) presets come from a StateFlow so
    // newly saved presets appear in the grid immediately and saved ones are
    // loaded from disk when the panel opens (the repository never self-loads).
    val builtIn = remember { container.presets.builtIn }
    val customPresets by container.presets.custom.collectAsState()
    val presets = remember(builtIn, customPresets) { builtIn + customPresets }
    val categories = remember(presets) {
        listOf("All") + presets.map { it.category }.distinct()
    }
    var category by remember { mutableStateOf("All") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var strength by remember { mutableStateOf(1f) }
    // Snapshot of edit state BEFORE the selected preset was applied.
    // Strength changes always re-blend from this base to avoid compounding.
    var baseState by remember { mutableStateOf<EditState?>(null) }
    var thumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    // Which photo the thumbnails were rendered for. Thumbnails must be
    // regenerated when the user opens a different photo: without this the old
    // isNotEmpty() guard kept showing the PREVIOUS photo's thumbnails, which
    // misleads (the tap applies the preset to the new photo).
    var thumbsFor by remember { mutableStateOf<String?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Preset?>(null) }
    // Long-press context menu (Rename/Delete) for user-saved presets.
    var menuTarget by remember { mutableStateOf<Preset?>(null) }
    var renameTarget by remember { mutableStateOf<Preset?>(null) }
    val scope = rememberCoroutineScope()
    val preview by vm.previewBitmap.collectAsState()
    val imageId by vm.imageId.collectAsState()
    val context = LocalContext.current

    // Import a preset file shared from another app / device (system picker,
    // JSON). The repository validates + normalizes; a bad file only yields a
    // notice, never a crash.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val imported = text?.let { container.presets.importPreset(it) }
            onNotice(if (imported != null) "Preset \"${imported.name}\" imported"
            else "Could not read that preset file")
        }
    }

    /** Shares [preset] as a JSON file through the system share sheet. */
    fun sharePreset(preset: Preset) {
        scope.launch {
            val file = container.presets.exportShareFile(preset)
            if (file == null) {
                onNotice("Could not prepare the preset for sharing")
                return@launch
            }
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
            if (uri == null) {
                onNotice("Could not prepare the preset for sharing")
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "H2W Photo Lab preset: ${preset.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, "Share preset"))
            } catch (_: Exception) {
                onNotice("No app available to share with")
            }
        }
    }

    // Load user-saved presets from disk on first open.
    LaunchedEffect(Unit) { container.presets.refresh() }

    // Recycle thumbnail bitmaps when the panel leaves the composition.
    val thumbsRef = rememberUpdatedState(thumbs)
    DisposableEffect(Unit) {
        onDispose {
            thumbsRef.value.values.forEach {
                runCatching { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    // Retry nonce for thumbnail generation: bumped by the error row's
    // Retry button (part of the LaunchedEffect keys below).
    var thumbAttempt by remember { mutableStateOf(0) }
    // Set when thumbnail rendering bailed out partway (e.g. a GL task never
    // answered): the panel shows an honest error + Retry instead of leaving
    // every card spinning forever.
    var thumbsError by remember { mutableStateOf(false) }

    // generate thumbnails once the preview + GL are ready; regenerate for a
    // new photo (stale thumbnails would show the wrong photo); render only
    // newly-added presets (e.g. a just-saved custom preset) when the photo
    // is unchanged, so saving doesn't redo the whole grid
    // Thumbnail pixel width is density-aware: on a 3x screen (vivo V60
    // class) a 192px thumbnail is upscaled ~1.7x into the preset card and
    // looks soft. 128dp of card width is plenty for all densities, capped
    // at 512px so panoramas don't blow up bitmap memory (37 presets × a
    // 512px thumb stays ~37MB transient, recycled on photo switch).
    val density = LocalDensity.current.density
    val thumbW = remember(density) {
        (128f * density).toInt().coerceIn(192, 512)
    }

    LaunchedEffect(imageId, preview, glHost, customPresets, thumbW, thumbAttempt) {
        val id = imageId ?: return@LaunchedEffect
        val src = preview ?: return@LaunchedEffect
        val host = glHost ?: return@LaunchedEffect
        var current = thumbs
        if (id != thumbsFor) {
            // Drop stale thumbnails FIRST: cards fall back to their loading
            // spinner, which is honest; keeping the old photo's thumbnails would
            // lie about what each preset does to the current photo.
            val stale = current
            current = emptyMap()
            thumbs = emptyMap()
            thumbsFor = null
            stale.values.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
        val missing = presets.filter { it.id !in current }
        if (missing.isEmpty()) {
            if (thumbsFor == null) thumbsFor = id
            return@LaunchedEffect
        }
        // Fresh attempt (including Retry): clear any previous timeout error.
        thumbsError = false
        var timedOut = false
        withContext(Dispatchers.Default) {
            val engine = vm.glEngineRef ?: return@withContext
            // The preview bitmap can be recycled by a concurrent photo switch
            // while thumbnails render. createScaledBitmap (and the GL-thread
            // setSource restore below) would then throw — uncaught, that kills
            // the app from this LaunchedEffect. Bail out quietly instead; the
            // effect re-runs for the new photo and regenerates its thumbnails.
            if (src.isRecycled) return@withContext
            val small = try {
                Bitmap.createScaledBitmap(src,
                    thumbW, (thumbW * src.height / src.width).toInt().coerceAtLeast(1), true)
            } catch (t: Throwable) {
                // Recycled mid-race (or OOM on a huge panorama): never crash
                // the panel for thumbnails — the cards keep their spinners.
                if (t is CancellationException) throw t
                return@withContext
            }
            try {
                val out = mutableMapOf<String, Bitmap>()
                for (p in missing) {
                    val done = CompletableDeferred<Bitmap>()
                    host.queueGl {
                        try {
                            // render preset over a neutral base (current image, preset state)
                            val bmp = engine.renderThumbnail(p.state, small)
                            done.complete(bmp)
                        } catch (e: Exception) {
                            done.completeExceptionally(e)
                        }
                    }
                    val bmp = try {
                        withTimeoutOrNull(THUMB_TIMEOUT_MS) { done.await() }
                    } catch (_: Exception) { null }
                    if (bmp == null) {
                        // A GL task never answered (or failed): don't leave
                        // every card spinning forever — bail out honestly and
                        // offer Retry instead.
                        Log.w(TAG_PRESET_PANEL, "thumbnail timed out for ${p.id}")
                        timedOut = true
                        break
                    }
                    out[p.id] = bmp
                    // restore the preview source on the GL thread
                    val restored = CompletableDeferred<Unit>()
                    host.queueGl {
                        try {
                            engine.setSource(src)
                        } catch (_: Throwable) {
                            // Preview went away mid-render (photo switched);
                            // the next preview frame re-uploads via
                            // invalidateGpuAssets below.
                        } finally {
                            restored.complete(Unit)
                        }
                    }
                    // Don't hang forever if the GL thread is stuck: the
                    // invalidateGpuAssets() below forces a re-upload anyway.
                    withTimeoutOrNull(RESTORE_TIMEOUT_MS) { restored.await() }
                }
                // thumbnails stomped GPU assets; force re-upload on next frame
                vm.invalidateGpuAssets()
                thumbs = current + out
                thumbsFor = id
            } finally {
                runCatching { if (!small.isRecycled) small.recycle() }
            }
        }
        thumbsError = timedOut
    }

    val shown = presets.filter { category == "All" || it.category == category }

    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories) { c ->
                LumoraChip(
                    selected = category == c,
                    onClick = { category = c },
                    label = { Text(c) },
                )
            }
        }
        if (selectedId != null) {
            LumoraSlider(
                label = "Strength",
                value = strength,
                valueRange = 0f..1f,
                default = 1f, // presets always apply at full strength on select
                onValueChange = { strength = it },
                onBegin = {},
                onEnd = {
                    val p = presets.find { it.id == selectedId } ?: return@LumoraSlider
                    val base = baseState ?: vm.state.value
                    vm.beginGesture()
                    vm.update(gesture = true) { Presets.apply(base, p, strength) }
                    vm.endGesture("Preset ${p.name}")
                },
                modifier = Modifier.padding(horizontal = 0.dp),
            )
        }
        if (thumbsError) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Couldn't render previews",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                LumoraTextButton(onClick = { thumbAttempt++ }) {
                    Text("Retry")
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(shown, key = { it.id }) { p ->
                PresetCard(
                    preset = p,
                    thumb = thumbs[p.id],
                    selected = selectedId == p.id,
                    // Long-press opens the context menu: Share for every
                    // preset; Rename/Delete additionally for user-saved ones.
                    onLongClick = { menuTarget = p },
                    onClick = {
                        if (selectedId == p.id) {
                            selectedId = null
                            baseState = null
                        } else {
                            selectedId = p.id
                            strength = 1f
                            baseState = vm.state.value
                            vm.beginGesture()
                            vm.update(gesture = true) { s -> Presets.apply(s, p, 1f) }
                            vm.endGesture("Preset ${p.name}")
                        }
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LumoraOutlineButton(
                onClick = { saveDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp),
                    tint = Lumora.colors.accent)
                Spacer(Modifier.width(6.dp))
                Text("Save current as preset", color = Lumora.colors.textPrimary,
                    style = Lumora.type.caption)
            }
            LumoraOutlineButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(LumoraIcons.FileDownload, "Import preset",
                    modifier = Modifier.size(16.dp), tint = Lumora.colors.accent)
                Spacer(Modifier.width(6.dp))
                Text("Import preset", color = Lumora.colors.textPrimary,
                    style = Lumora.type.caption)
            }
        }
    }

    if (saveDialog) {
        SavePresetDialog(
            onDismiss = { saveDialog = false },
            onSave = { name ->
                scope.launch {
                    // Store only the portable (cross-photo) part of the edit:
                    // retouch spots, AI-derived mask components and other
                    // image-specific work are dropped, same policy as
                    // copy/paste, so a user preset never paints this photo's
                    // blemishes onto another photo.
                    container.presets.saveCustom(
                        Presets.custom(name, vm.state.value.sanitizedForPaste()))
                    saveDialog = false
                }
            },
        )
    }

    val toDelete = deleteTarget
    if (toDelete != null) {
        LumoraDialog(
            onDismissRequest = { deleteTarget = null },
            title = "Delete preset?",
            text = "“${toDelete.name}” will be removed from My Presets.",
            confirmLabel = "Delete",
            confirmColor = Lumora.colors.danger,
            onConfirm = {
                scope.launch {
                    runCatching { container.presets.deleteCustom(toDelete.id) }
                    thumbs[toDelete.id]?.let {
                        runCatching { if (!it.isRecycled) it.recycle() }
                    }
                    thumbs = thumbs - toDelete.id
                    if (selectedId == toDelete.id) {
                        selectedId = null
                        baseState = null
                    }
                    deleteTarget = null
                }
            },
        )
    }

    // Long-press context menu: Share for every preset; Rename/Delete only
    // for user-saved ones (built-ins are fixed).
    menuTarget?.let { target ->
        PresetMenuSheet(
            onDismiss = { menuTarget = null },
            onShare = { sharePreset(target); menuTarget = null },
            isCustom = !target.builtIn,
            onRename = { renameTarget = target; menuTarget = null },
            onDelete = { deleteTarget = target; menuTarget = null },
        )
    }

    renameTarget?.let { target ->
        RenamePresetDialog(
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching { container.presets.renameCustom(target.id, name) }
                    renameTarget = null
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(
    preset: Preset,
    thumb: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = Lumora.colors
    val isSelected = selected
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentDim else colors.panelElevated)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Preset options" else null,
            )
            // TalkBack contract: the card is a selectable button, not plain
            // text — announce its role and applied state so preset browsing
            // is usable without sight (matches the LumoraChip standard).
            .semantics {
                role = Role.Button
                this.selected = isSelected
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .background(colors.panel),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = preset.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LumoraProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                    color = colors.textTertiary)
            }
            if (selected) {
                Icon(Icons.Default.Check, null, tint = colors.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(colors.scrim, RoundedCornerShape(6.dp)).padding(2.dp))
            }
        }
        Text(
            preset.name,
            style = Lumora.type.toolLabel,
            color = if (selected) colors.accent else colors.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "Save preset",
        content = {
            LumoraTextField(
                value = text, onValueChange = { text = it },
                label = "Preset name",
                placeholder = "Preset name",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmLabel = "Save",
        onConfirm = { onSave(text.ifBlank { "My preset" }) },
    )
}

/** Long-press context menu for user-saved presets (same bottom-sheet pattern
 *  as the mask menu). Share is offered for every preset (built-ins included);
 *  Rename/Delete only for user-saved ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetMenuSheet(
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    isCustom: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Lumora.colors
    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            LumoraTextButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LumoraIcons.IosShare, null, tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Share", color = colors.textPrimary)
                }
            }
            if (isCustom) {
                LumoraTextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Edit, null, tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Rename", color = colors.textPrimary)
                    }
                }
                LumoraTextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Delete, null, tint = colors.danger,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Delete", color = colors.danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenamePresetDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "Rename preset",
        onConfirm = { onConfirm(text.ifBlank { initial }) },
        content = {
            LumoraTextField(
                value = text, onValueChange = { text = it },
                label = "Preset name",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmLabel = "Save",
    )
}
