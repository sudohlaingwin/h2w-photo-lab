package com.lumora.photoeditor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.core.AppContainer
import com.lumora.photoeditor.engine.*
import com.lumora.photoeditor.export.GlHost
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.LumoraDialog
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.HistogramView
import com.lumora.photoeditor.ui.components.LoadingState
import com.lumora.photoeditor.ui.components.LumoraCompactSlider
import com.lumora.photoeditor.ui.components.ToolbarButton
import com.lumora.photoeditor.ui.components.ToolTile
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraFab
import com.lumora.photoeditor.ui.components.LumoraIconButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraDropdownMenu
import com.lumora.photoeditor.ui.components.LumoraDropdownMenuItem
import com.lumora.photoeditor.ui.components.LumoraBottomSheet
import com.lumora.photoeditor.ui.panels.*
import com.lumora.photoeditor.ui.preview.EditorGlView
import com.lumora.photoeditor.ui.preview.PreviewTouchMode
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.data.HistoryEntry
import java.text.DateFormat
import java.util.Date
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Color-range pick request: null ids = creating a new mask from the tap;
 *  non-null = repicking the color of an existing component. */
private data class ColorPick(
    val maskId: String?,
    val compId: String?,
    val addSample: Boolean = false, // true: append a refine swatch instead of repicking
    /** Subtract/intersect flow: append a color-range component with this op to
     *  maskId instead of creating a new mask (compId stays null). */
    val pendingOp: MaskOp? = null,
)

/** Guided-perspective axis: two taps define a line that should read
 *  vertical (Vertical) or horizontal (Horizontal) on screen. */
private enum class PerspectiveAxis { Vertical, Horizontal }

private enum class Tool(
    val label: String,
    val icon: ImageVector,
) {
    Light("Light", LumoraIcons.Tune),
    Color("Color", LumoraIcons.Palette),
    Grade("Grade", LumoraIcons.DonutLarge),
    Mask("Mask", LumoraIcons.Brush),
    Detail("Detail", LumoraIcons.Grain),
    Optics("Optics", LumoraIcons.Adjust),
    Crop("Crop", LumoraIcons.Crop),
    Retouch("Retouch", LumoraIcons.Healing),
    Presets("Presets", LumoraIcons.Style),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    container: AppContainer,
    imageId: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = Lumora.colors
    val state by vm.state.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val showRetouchSpots by vm.showRetouchSpots.collectAsState()
    val clipMode by vm.clipMode.collectAsState()
    val cropOverlayStyle by vm.cropOverlayStyle.collectAsState()
    val rating by vm.rating.collectAsState()
    val histogram by vm.histogram.collectAsState()
    // RAW program Phase 1 slice 3: when the open photo is a lossy-JPEG DNG
    // served from the camera's embedded preview, the editor says so — the
    // caption sits right under the histogram strip (see below).
    val embeddedPreviewFormat by vm.embeddedPreviewFormat.collectAsState()
    var tool by remember { mutableStateOf<Tool?>(Tool.Light) }

    // paint coordination
    var paintReq by remember { mutableStateOf<PaintRequest?>(null) }
    var brushSize by remember { mutableStateOf(0.08f) }
    var brushFeather by remember { mutableStateOf(0.5f) }
    var brushFlow by remember { mutableStateOf(0.85f) }
    var brushOpacity by remember { mutableStateOf(1f) }
    // Pro auto-mask: edge-aware brush that respects strong tone boundaries.
    var brushAutoMask by remember { mutableStateOf(false) }
    // Auto-mask edge tolerance: luminance distance at which a painted pixel
    // is fully rejected (0.02 = hugs hard edges, 0.5 = follows soft tones).
    var brushAutoMaskTolerance by remember { mutableStateOf(0.14f) }
    var retouchTool by remember { mutableStateOf<RetouchOp?>(null) }
    // Single-spot erase: tap a retouch spot on the photo to delete just it
    // (mutually exclusive with applying a tool).
    var retouchErase by remember { mutableStateOf(false) }
    var retouchSize by remember { mutableStateOf(0.06f) }
    var retouchFeather by remember { mutableStateOf(0.6f) }
    var retouchStrength by remember { mutableStateOf(1f) }
    /** Dodge/burn tonal range (the classic darkroom range control). */
    var retouchRange by remember { mutableStateOf(RetouchRange.ALL) }
    // Skin brush: per-spot detail retention (0 = fully smooth, 1 = keep all
    // pores). Mirrors the Wavelet Skin "Texture keep" slider; defaults to
    // 0.45f to match the engine's legacy hardcoded behavior.
    var retouchTextureKeep by remember { mutableStateOf(0.45f) }
    var cloneSource by remember { mutableStateOf<Pt?>(null) }
    var glHost by remember { mutableStateOf<GlHost?>(null) }
    var gradStart by remember { mutableStateOf<Pt?>(null) }
    // retouch drag-paint anchors: start of the current stroke, the clone source
    // at stroke start (kept so the source tracks the finger), and where the
    // last stroke spot was stamped (for spot spacing).
    var retouchDragStart by remember { mutableStateOf<Pt?>(null) }
    var retouchDragCloneStart by remember { mutableStateOf<Pt?>(null) }
    var retouchLastSpot by remember { mutableStateOf<Pt?>(null) }
    // HEAL stroke anchor: target->source offset resolved on DOWN and reused
    // for every MOVE spot, so the tone-matched source can't jump between
    // spots and leave seams along the stroke (aligned mode, like CLONE).
    var retouchDragHealOffset by remember { mutableStateOf<Pt?>(null) }
    var wbPick by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf(false) }
    // targeted-adjustment pick for the Color mixer's hue ranges
    var hslPick by remember { mutableStateOf(false) }
    var hslTargetRange by remember { mutableStateOf<HueRange?>(null) }
    // tone-curve targeted adjustment (Lightroom TAT): drag on the photo to
    // bend the curve at the touched tone; sticky until toggled off
    var curveTarget by remember { mutableStateOf(false) }
    // tone-curve channel shared by the panel and the TAT: 0 master 1 R 2 G 3 B
    var curveChannel by remember { mutableStateOf(0) }
    // color-range mask pick: null = inactive; ids null = new mask, else repick
    var colorPick by remember { mutableStateOf<ColorPick?>(null) }
    // transient confirmation chip ("Edits copied", "Edits pasted")
    var notice by remember { mutableStateOf<String?>(null) }
    // true while the crop quad has a corner outside the photo (rotation /
    // perspective with "Constrain to image" off) — those corners export
    // black, so the hint chip warns until the user re-constrains
    var cropHasOob by remember { mutableStateOf(false) }
    // "Reset all edits" confirm dialog (overflow menu)
    var showResetConfirm by remember { mutableStateOf(false) }
    // star-rating dialog (overflow menu; Lightroom parity with the library)
    var showRateDialog by remember { mutableStateOf(false) }
    val copiedEdits by container.copiedEdits.collectAsState()
    // edit-history sheet (Lightroom History panel parity)
    var showHistory by remember { mutableStateOf(false) }
    // named snapshots sheet (Lightroom Snapshots parity)
    var showSnapshots by remember { mutableStateOf(false) }
    // GPU self-test dialog: on-device verification of the mask shader path
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<SelfTestResult?>(null) }
    // guided straighten (two taps define a line that should be level)
    var straightenMode by remember { mutableStateOf(false) }
    // guided perspective (two taps define a line that should read vertical or horizontal)
    var perspectiveGuide by remember { mutableStateOf<PerspectiveAxis?>(null) }
    // lens-blur focus placement: tap/drag on the photo moves the focus point
    var lensFocusPick by remember { mutableStateOf(false) }
    // before/after split view: NDC x of the divider, null = off
    val splitXNdc by vm.splitX.collectAsState()

    LaunchedEffect(imageId) { vm.open(imageId) }

    // clear the "too dark" notice shortly after it appears
    LaunchedEffect(pickError) {
        if (pickError) {
            kotlinx.coroutines.delay(2500)
            pickError = false
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2200)
            notice = null
        }
    }

    val pickActive = wbPick && tool == Tool.Light
    val hslPickActive = hslPick && tool == Tool.Color
    val curveTargetActive = curveTarget && tool == Tool.Light
    val touchMode = when {
        pickActive -> PreviewTouchMode.Pick
        hslPickActive -> PreviewTouchMode.Pick
        colorPick != null -> PreviewTouchMode.Pick
        curveTargetActive -> PreviewTouchMode.CurveTarget
        straightenMode -> PreviewTouchMode.Straighten
        perspectiveGuide != null -> PreviewTouchMode.Perspective
        lensFocusPick -> PreviewTouchMode.FocusPoint
        tool == Tool.Crop -> PreviewTouchMode.Crop
        paintReq != null || retouchTool != null ||
                (tool == Tool.Retouch && retouchErase) -> PreviewTouchMode.Paint
        splitXNdc != null -> PreviewTouchMode.Split
        else -> PreviewTouchMode.Zoom
    }

    fun exitPaintModes() {
        paintReq = null
        retouchTool = null
        retouchErase = false
        straightenMode = false
        perspectiveGuide = null
        lensFocusPick = false
        colorPick = null
        hslPick = false
        curveTarget = false
    }

    /** Split view is a preview mode: any paint/pick/straighten/crop gesture
     *  mode takes over the preview drags, so it exits the split view. */
    LaunchedEffect(paintReq, retouchTool, retouchErase, wbPick, hslPick, colorPick, straightenMode, perspectiveGuide, lensFocusPick, curveTarget, tool) {
        // Drive the Remove tool's live red overlay: active exactly while the
        // Remove retouch tool is selected.
        vm.setRemoveToolActive(retouchTool == RetouchOp.REMOVE)
        if (paintReq != null || retouchTool != null || retouchErase || wbPick || hslPick || colorPick != null ||
            straightenMode || perspectiveGuide != null || lensFocusPick || curveTarget || tool == Tool.Crop
        ) {
            vm.setSplitEnabled(false)
        }
    }

    /** Crop preview mode: show the full image under the crop-rect overlay
     *  while the crop tool is active (fixes the "zoomed-in" crop bug). */
    LaunchedEffect(tool) {
        vm.setCropPreviewMode(tool == Tool.Crop)
    }

    fun toggleSplit() {
        if (splitXNdc == null) {
            // a drag must unambiguously move the divider
            paintReq = null
            retouchTool = null
            cloneSource = null
            straightenMode = false
            perspectiveGuide = null
            wbPick = false
            hslPick = false
            colorPick = null
            lensFocusPick = false
            curveTarget = false
        }
        vm.toggleSplit()
    }

    var showAiPanel by remember { mutableStateOf(false) }
    val aiAssistant by vm.aiAssistant.collectAsState()

    // Refresh AI key status when entering the editor (user may have just
    // set the key in Settings).
    LaunchedEffect(Unit) { vm.refreshAiKeyStatus() }

    // Single exit path for the toolbar back button AND the system back
    // gesture: capture the GPU-rendered edited thumbnail for the library
    // grid first, then navigate. The capture must never block the exit —
    // on any failure the grid just shows the original photo.
    val scope = rememberCoroutineScope()
    var exiting by remember { mutableStateOf(false) }
    fun exitEditor() {
        if (exiting) return
        exiting = true
        exitPaintModes()
        scope.launch {
            runCatching { vm.refreshEditedThumbnail() }
            onBack()
        }
    }
    BackHandler(enabled = !exiting) { exitEditor() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(colors.background)) {
        // ---------------- top toolbar ----------------
        EditorToolbar(
            canUndo = canUndo, canRedo = canRedo,
            clipMode = clipMode,
            onBack = { exitEditor() },
            onUndo = vm::undo,
            onRedo = vm::redo,
            onClip = vm::setClipMode,
            onCompareHold = { vm.setShowOriginal(it) },
            onToggleSplit = { toggleSplit() },
            splitActive = splitXNdc != null,
            onCopyEdits = {
                vm.copyEdits()
                notice = "Edits copied — paste onto any photo"
            },
            onPasteEdits = { if (vm.pasteEdits()) notice = "Edits pasted" },
            pasteAvailable = copiedEdits != null,
            onHistory = { showHistory = true },
            onSnapshots = { showSnapshots = true },
            rating = rating,
            onRate = { showRateDialog = true },
            onDuplicate = {
                vm.duplicateCurrent { newId ->
                    notice = if (newId != null) "Virtual copy created"
                    else "Could not create a copy"
                }
            },
            onSelfTest = {
                selfTestRunning = true
                selfTestResult = null
                vm.runGpuSelfTest { result ->
                    selfTestRunning = false
                    selfTestResult = result
                }
            },
            onResetAll = { showResetConfirm = true },
            onExport = onExport,
        )
        // ---------------- live histogram strip ----------------
        // Lightroom-style: always-visible exposure feedback while any tool is
        // in use; tap jumps to the Light panel. Hidden while loading/error so
        // the preview area keeps a clean state.
        if (!loading && error == null) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(colors.background)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open Light adjustments",
                        onClick = {
                            tool = Tool.Light
                            paintReq = null
                            retouchTool = null
                            cloneSource = null
                        },
                    )
                    .semantics {
                        contentDescription =
                            "Live RGB histogram of the edited photo. " +
                                "Activate to open Light adjustments."
                    }
                    .padding(horizontal = 20.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                HistogramView(histogram, height = 36.dp)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        }
        // ---------------- embedded-preview caption (RAW Phase 2, slice 4) --
        // RAWs LibRaw cannot unpack open from the camera's embedded
        // preview, not a fresh RAW decode. Say so in one quiet line with
        // the actual format instead of letting the user believe they are
        // editing RAW data.
        if (embeddedPreviewFormat != null && !loading && error == null) {
            Text(
                text = "Embedded preview ($embeddedPreviewFormat) — edits apply to the camera preview",
                style = Lumora.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
            )
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        }
        // ---------------- preview ----------------
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            when {
                loading -> LoadingState("Loading photo…")
                error != null -> EmptyState(
                    title = "Couldn't open this photo",
                    body = error ?: "",
                    actionLabel = "Back to library",
                    onAction = onBack,
                    icon = LumoraIcons.Error,
                )
                else -> {
                    EditorGlView(
                        vm = vm,
                        touchMode = touchMode,
                        onGlHost = { glHost = it; vm.sharedGlHost = it },
                        onSplitDrag = vm::setSplitX,
                        splitXNdc = splitXNdc,
                        cursorRadiusUv = when {
                            paintReq is PaintRequest.Brush -> brushSize
                            retouchTool != null -> retouchSize
                            tool == Tool.Retouch && retouchErase -> retouchSize
                            else -> null
                        },
                        // Pin markers for retouch spots — shown while a
                        // retouch tool or the spot eraser is active so the
                        // user can see (and erase) exactly what was placed.
                        retouchSpotUvs = if (showRetouchSpots &&
                            (retouchTool != null || retouchErase)
                        ) {
                            state.retouch.spots.takeIf { it.isNotEmpty() }
                        } else null,
                        // Amber clone-source pin: shows exactly where the
                        // clone texture is sampled from. Hidden mid-stroke
                        // (retouchDragCloneStart != null) because during a
                        // stroke the effective source tracks the finger.
                        cloneSourceUv = if (showRetouchSpots &&
                            retouchTool == RetouchOp.CLONE &&
                            retouchDragCloneStart == null
                        ) {
                            cloneSource
                        } else null,
                        cloneSourceDiameterUv = if (retouchTool == RetouchOp.CLONE) {
                            retouchSize
                        } else null,
                        // Crop overlay composition guide (thirds/grid/
                        // diagonal/golden) — persisted user preference.
                        cropOverlayStyle = cropOverlayStyle,
                        // warn when the crop quad leaves the photo (those
                        // corners render black in preview/export)
                        onCropOob = { cropHasOob = it },
                        onPaintStart = { uv ->
                            if (tool == Tool.Retouch && retouchErase &&
                                paintReq == null && retouchTool == null
                            ) {
                                // Spot-erase tap: delete the single spot under
                                // the finger. removeRetouchSpotNear is its own
                                // undo entry, so no gesture bookkeeping here.
                                if (vm.removeRetouchSpotNear(uv.x, uv.y)) {
                                    notice = "Retouch spot removed"
                                }
                            } else when (val req = paintReq) {
                                is PaintRequest.Brush -> {
                                    vm.beginGesture()
                                    vm.addBrushStroke(
                                        req.maskId, req.compId,
                                        BrushStroke(
                                            points = listOf(
                                                BrushPoint(uv.x, uv.y),
                                                BrushPoint(uv.x, uv.y)),
                                            size = brushSize, feather = brushFeather,
                                            flow = brushFlow, opacity = brushOpacity,
                                            erase = req.erase,
                                            autoMask = brushAutoMask,
                                            autoMaskTolerance = brushAutoMaskTolerance,
                                        ))
                                }
                                is PaintRequest.Linear -> gradStart = uv
                                is PaintRequest.Radial -> gradStart = uv
                                null -> retouchTool?.let { tool ->
                                    // REMOVE tool: paint into removal mask.
                                    if (tool == RetouchOp.REMOVE) {
                                        vm.paintRemovalMask(uv, uv, retouchSize)
                                        retouchLastSpot = uv
                                    } else {
                                    // A retouch "stroke": DOWN stamps the first spot, MOVE
                                    // stamps more spots as the finger travels, UP/CANCEL closes
                                    // the whole stroke as ONE undo entry. HEAL locks the
                                    // target->source offset on DOWN (aligned mode) so the
                                    // auto source can't jump between spots.
                                    vm.beginGesture()
                                    retouchDragStart = uv
                                    retouchDragCloneStart = cloneSource
                                    retouchDragHealOffset = null
                                    retouchLastSpot = null
                                    applyRetouchTap(vm, tool, uv, retouchSize,
                                        retouchFeather, retouchStrength, cloneSource,
                                        healSource = null,
                                        range = retouchRange,
                                        onSourceSet = {
                                            if (tool == RetouchOp.CLONE) {
                                                cloneSource = it
                                                retouchDragCloneStart = it
                                            } else {
                                                // HEAL: keep the persistent clone pick
                                                // untouched; only anchor this stroke.
                                                retouchDragHealOffset =
                                                    Pt(it.x - uv.x, it.y - uv.y)
                                            }
                                        },
                                        addSpot = vm::addRetouchSpotStroke,
                                        textureKeep = retouchTextureKeep)
                                    retouchLastSpot = uv
                                    } // else (not REMOVE)
                                }
                            }
                        },
                        onPaintSegment = { from, to ->
                            when (val req = paintReq) {
                                is PaintRequest.Brush -> vm.addBrushStroke(
                                    req.maskId, req.compId,
                                    BrushStroke(
                                        points = listOf(
                                            BrushPoint(from.x, from.y),
                                            BrushPoint(to.x, to.y)),
                                        size = brushSize, feather = brushFeather,
                                        flow = brushFlow, opacity = brushOpacity, erase = req.erase,
                                        autoMask = brushAutoMask,
                                        autoMaskTolerance = brushAutoMaskTolerance))
                                is PaintRequest.Linear -> {
                                    val s = gradStart ?: from
                                    vm.update(gesture = true) { st ->
                                        updateGradientShape(st, req.maskId, req.compId,
                                            listOf(s.x, s.y, to.x, to.y))
                                    }
                                }
                                is PaintRequest.Radial -> {
                                    val s = gradStart ?: from
                                    val rx = maxOf(kotlin.math.abs(to.x - s.x), 0.02f)
                                    val ry = maxOf(kotlin.math.abs(to.y - s.y), 0.02f)
                                    // preserves rotation + feather (no snap back to 50%)
                                    vm.updateRadialGeometry(
                                        req.maskId, req.compId, s.x, s.y, rx, ry)
                                }
                                null -> {
                                    // REMOVE tool: paint into the removal mask
                                    // (not retouch spots).
                                    if (retouchTool == RetouchOp.REMOVE) {
                                        vm.paintRemovalMask(from, to, retouchSize)
                                        retouchLastSpot = to
                                    } else {
                                    // Drag-paint a retouch stroke. Only the paint-like ops
                                    // support strokes; single-tap ops (eye, teeth, under-eye)
                                    // ignore movement so a slip can't stack spots.
                                    val tool = retouchTool
                                    val dragStart = retouchDragStart
                                    if (tool == RetouchOp.HEAL || tool == RetouchOp.CLONE ||
                                        tool == RetouchOp.SMOOTH_SKIN
                                    ) {
                                        val last = retouchLastSpot
                                        val dist = if (last == null) Float.MAX_VALUE
                                            else hypot(to.x - last.x, to.y - last.y)
                                        val cs = retouchDragCloneStart
                                        if (dragStart != null && dist >= retouchSize * 0.45f &&
                                            (tool != RetouchOp.CLONE || cs != null)
                                        ) {
                                            // Clone source tracks the finger: every stroke
                                            // spot keeps the same target->source offset as
                                            // the stroke's first spot. HEAL does the same
                                            // with its locked offset so the auto source
                                            // can't jump between spots along the stroke.
                                            val src = cs?.let {
                                                Pt(
                                                    it.x + (to.x - dragStart.x),
                                                    it.y + (to.y - dragStart.y),
                                                )
                                            }
                                            val healSrc = retouchDragHealOffset?.let {
                                                Pt(to.x + it.x, to.y + it.y)
                                            }
                                            applyRetouchTap(vm, tool, to, retouchSize,
                                                retouchFeather, retouchStrength, src,
                                                healSource = healSrc,
                                                range = retouchRange,
                                                onSourceSet = {},
                                                addSpot = vm::addRetouchSpotStroke,
                                                textureKeep = retouchTextureKeep)
                                            retouchLastSpot = to
                                        }
                                    }
                                    } // else (not REMOVE)
                                }
                            }
                        },
                        onPaintEnd = {
                            when (paintReq) {
                                is PaintRequest.Brush -> vm.endGesture("Brush stroke")
                                is PaintRequest.Linear -> {
                                    vm.endGesture("Linear gradient"); gradStart = null
                                }
                                is PaintRequest.Radial -> {
                                    vm.endGesture("Radial gradient"); gradStart = null
                                }
                                null -> {
                                    // Close a retouch stroke as a single undo entry and
                                    // drop the stroke anchors. endGesture() no-ops when
                                    // nothing changed (e.g. a clone source pick).
                                    if (retouchTool != null) vm.endGesture("Retouch")
                                    retouchDragStart = null
                                    retouchDragCloneStart = null
                                    retouchDragHealOffset = null
                                    retouchLastSpot = null
                                }
                            }
                        },
                        onCropStart = { vm.beginGesture() },
                        onCropDrag = { rect ->
                            vm.update(gesture = true) { s -> s.copy(crop = rect) }
                        },
                        onCropEnd = { vm.endGesture("Crop") },
                        onPickColor = { r, g, b ->
                            val cp = colorPick
                            when {
                                wbPick -> {
                                    if (vm.applyWhiteBalancePick(r, g, b)) {
                                        wbPick = false
                                        pickError = false
                                    } else {
                                        pickError = true
                                    }
                                }
                                hslPick -> {
                                    // targeted adjustment: one-shot, select the
                                    // tapped color's range in the Color mixer
                                    hslTargetRange = hueRangeFor(r, g, b)
                                    hslPick = false
                                }
                                cp != null -> {
                                    // one-shot: create / repick / add swatch, then leave pick mode
                                    colorPick = null
                                    if (cp.pendingOp != null && cp.maskId != null && cp.compId == null) {
                                        vm.addColorRangeComponent(cp.maskId, cp.pendingOp, r, g, b)
                                    } else if (cp.maskId == null || cp.compId == null) {
                                        vm.addColorRangeMask(r, g, b)
                                    } else if (cp.addSample) {
                                        vm.addColorSample(cp.maskId, cp.compId, r, g, b)
                                    } else {
                                        vm.updateColorRange(cp.maskId, cp.compId,
                                            r = r, g = g, b = b)
                                        vm.refreshColorRaster(cp.maskId, cp.compId)
                                    }
                                }
                            }
                        },
                        onStraighten = { p1, p2 ->
                            if (applyStraightenGuide(vm, p1, p2)) straightenMode = false
                        },
                        onPerspective = { p1, p2 ->
                            if (perspectiveGuide == PerspectiveAxis.Horizontal) {
                                if (vm.applyPerspectiveGuideH(p1, p2)) perspectiveGuide = null
                            } else {
                                if (vm.applyPerspectiveGuide(p1, p2)) perspectiveGuide = null
                            }
                        },
                        onFocusStart = { vm.focusPointStart() },
                        onFocusDrag = { vm.focusPointDrag(it) },
                        onFocusEnd = { vm.focusPointEnd() },
                        onCurveSample = { r, g, b ->
                            // Master targets luminance; R/G/B target the
                            // tapped channel value (Lightroom TAT behavior).
                            val v = when (curveChannel) {
                                1 -> r
                                2 -> g
                                3 -> b
                                else -> 0.2126f * r + 0.7152f * g + 0.0722f * b
                            }
                            vm.curveTargetStart(curveChannel, v)
                        },
                        onCurveDrag = { dyPx, hPx ->
                            // Drag up lifts the tone: a full view-height drag
                            // spans the whole 0..1 curve range.
                            if (hPx > 0f) vm.curveTargetDrag(-dyPx / hPx)
                        },
                        onCurveEnd = { vm.curveTargetEnd() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // active tool hint chip
                    ActiveToolChip(
                        paintReq = paintReq,
                        retouchTool = retouchTool,
                        retouchEraseActive = retouchErase && tool == Tool.Retouch,
                        cloneSource = cloneSource,
                        cropActive = tool == Tool.Crop,
                        cropOobWarning = cropHasOob,
                        pickActive = pickActive,
                        pickError = pickError,
                        colorPickActive = colorPick != null,
                        colorAddSampleActive = colorPick?.addSample == true,
                        hslPickActive = hslPickActive,
                        straightenActive = straightenMode,
                        perspectiveActive = perspectiveGuide != null,
                        perspectiveAxis = perspectiveGuide ?: PerspectiveAxis.Vertical,
                        splitActive = splitXNdc != null,
                        focusActive = lensFocusPick,
                        curveTargetActive = curveTargetActive,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    // transient confirmation (copy/paste edits)
                    // floating brush state, shared by the notice offset and the quick-bar
                    val brushReq = paintReq as? PaintRequest.Brush
                    // retouch paints with retouchSize; the quick-bar gives it
                    // the same on-canvas size/feather/strength control as the
                    // mask brush (REMOVE paints a removal mask, so it keeps
                    // the size row only).
                    val retouchBarActive = retouchTool != null && !retouchErase
                    if (notice != null) {
                        Box(
                            // mask bar is four rows (size + feather + opacity
                            // + flow) and sits ~176dp tall; the three-row
                            // retouch bar sits ~140dp; REMOVE keeps the
                            // single size row (~76dp)
                            Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = if (brushReq != null) 176.dp
                                    else if (retouchBarActive)
                                        (if (retouchTool != RetouchOp.REMOVE) 140.dp
                                            else 76.dp)
                                    else 12.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Lumora.colors.scrim)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(notice!!, style = Lumora.type.caption,
                                color = Color.White)
                        }
                    }
                    // floating brush quick-bar: size + feather + opacity + flow
                    // (+ add/erase for masks) while a mask brush or a retouch
                    // tool is painting, so the settings stay on the photo
                    // instead of forcing a look down at the bottom sheet.
                    // lastReq keeps the mask bar populated through the fade-out.
                    var lastBrushReq by remember { mutableStateOf<PaintRequest.Brush?>(null) }
                    if (brushReq != null) lastBrushReq = brushReq
                    val barActive = brushReq != null || retouchBarActive
                    val barAlpha by animateFloatAsState(
                        targetValue = if (barActive) 1f else 0f,
                        label = "brushBarAlpha",
                    )
                    if (barAlpha > 0.01f) {
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                .graphicsLayer {
                                    alpha = barAlpha
                                    translationY = (1f - barAlpha) * 64f
                                },
                        ) {
                            if (retouchBarActive) {
                                // retouch: size, feather and strength mid-stroke.
                                // REMOVE paints the removal mask rather than
                                // stamping spots, so it keeps the size row only.
                                val spotTool = retouchTool != RetouchOp.REMOVE
                                BrushQuickBar(
                                    brushSize = retouchSize,
                                    onBrushSize = { retouchSize = it },
                                    sizeDescription = "Retouch size",
                                    sizeRange = 0.01f..0.3f,
                                    sizeDefault = 0.06f,
                                    feather = if (spotTool) retouchFeather else null,
                                    onFeather = { retouchFeather = it },
                                    featherDescription = "Retouch feather",
                                    featherDefault = 0.6f,
                                    strength = if (spotTool) retouchStrength else null,
                                    onStrength = { retouchStrength = it },
                                    strengthDescription = "Retouch strength",
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                            } else {
                                // mask brush: the live req, or the frozen one fading out
                                val req = brushReq ?: lastBrushReq
                                req?.let {
                                    BrushQuickBar(
                                        brushSize = brushSize,
                                        onBrushSize = { brushSize = it },
                                        eraseActive = it.erase,
                                        // Feather is live here too: edge softness
                                        // can be tuned mid-stroke without opening
                                        // the Mask panel.
                                        feather = brushFeather,
                                        onFeather = { brushFeather = it },
                                        // Opacity is live here too: it sets how
                                        // strongly each stroke paints, so painters
                                        // can soften a stroke mid-drag without
                                        // opening the Mask panel.
                                        opacity = brushOpacity,
                                        onOpacity = { brushOpacity = it },
                                        // Flow is live here too: per-dab
                                        // paint strength without opening the
                                        // Mask panel.
                                        flow = brushFlow,
                                        onFlow = { brushFlow = it },
                                        // Mirrors the MaskPanel add/erase chips: switching
                                        // sides creates a fresh brush component with the
                                        // right mask op (ADD vs SUBTRACT). Disabled
                                        // during fade-out (bar no longer live).
                                        onOpSwitch = if (brushReq != null) switch@{ toErase ->
                                            if (toErase == brushReq.erase) return@switch
                                            val compId = vm.addBrushComponent(
                                                brushReq.maskId, toErase)
                                            paintReq = PaintRequest.Brush(
                                                brushReq.maskId, compId, toErase)
                                        } else null,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // ---------------- tool navigation ----------------
        ToolNav(
            selected = tool,
            state = state,
            onSelect = {
                tool = if (tool == it) null else it
                if (tool != Tool.Mask) paintReq = null
                if (tool != Tool.Retouch) { retouchTool = null; cloneSource = null; retouchErase = false }
                straightenMode = false
                perspectiveGuide = null
                lensFocusPick = false
                if (tool != Tool.Mask) colorPick = null
            },
        )
        // ---------------- panel ----------------
        AnimatedVisibility(
            visible = tool != null && !loading && error == null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                // NOTE: clip BEFORE background — the reverse order leaves the
                // background unclipped and the sheet's top corners square.
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(colors.panel),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // drag-handle pill: matches the ModalBottomSheet drag
                    // handles used in Library (import / photo menu), so every
                    // bottom sheet in the app carries the same affordance.
                    Box(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.divider),
                        )
                    }
                    // Animated tool switch: a short crossfade keeps panel
                    // changes from flashing, matching Lightroom Mobile's
                    // panel transitions. Fade only (no size transform) so
                    // the GL preview above never re-lays-out mid-gesture.
                    AnimatedContent(
                        targetState = tool,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(160))
                        },
                        label = "toolPanel",
                        modifier = Modifier.semantics {
                            tool?.let { contentDescription = "${it.label} adjustments panel" }
                        },
                    ) { activeTool ->
                    when (activeTool) {
                    Tool.Light -> LightPanel(
                        vm,
                        onPickWhiteBalance = { wbPick = true; pickError = false },
                        curveChannel = curveChannel,
                        onCurveChannel = { curveChannel = it },
                        curveTargetActive = curveTarget,
                        onCurveTarget = { curveTarget = !curveTarget },
                    )
                    Tool.Color -> ColorPanel(
                        vm,
                        onPickHslTarget = { hslPick = true; pickError = false },
                        targetRange = hslTargetRange,
                        onTargetRangeConsumed = { hslTargetRange = null },
                    )
                    Tool.Grade -> GradePanel(vm)
                    Tool.Mask -> MaskPanel(
                        vm = vm,
                        paintRequest = paintReq,
                        onPaintRequest = { paintReq = it },
                        onPickColorRange = { colorPick = ColorPick(null, null) },
                        onPickColorRangeComponent = { maskId, op ->
                            colorPick = ColorPick(maskId, null, pendingOp = op)
                        },
                        onRepickColorRange = { maskId, compId ->
                            colorPick = ColorPick(maskId, compId)
                        },
                        onAddColorSample = { maskId, compId ->
                            colorPick = ColorPick(maskId, compId, addSample = true)
                        },
                        brushSize = brushSize, onBrushSize = { brushSize = it },
                        brushFeather = brushFeather, onBrushFeather = { brushFeather = it },
                        brushFlow = brushFlow, onBrushFlow = { brushFlow = it },
                        brushOpacity = brushOpacity, onBrushOpacity = { brushOpacity = it },
                        brushAutoMask = brushAutoMask, onBrushAutoMask = { brushAutoMask = it },
                        brushAutoMaskTolerance = brushAutoMaskTolerance,
                        onBrushAutoMaskTolerance = { brushAutoMaskTolerance = it },
                    )
                    Tool.Detail -> DetailPanel(vm)
                    Tool.Optics -> OpticsPanel(vm,
                        focusPickActive = lensFocusPick,
                        onMoveFocus = { lensFocusPick = !lensFocusPick })
                    Tool.Crop -> GeometryPanel(
                        vm,
                        onStraighten = { straightenMode = true },
                        onAutoStraighten = {
                            vm.autoStraighten {
                                notice = "No clear horizon found — try \"Draw a line to straighten\""
                            }
                        },
                        onPerspectiveGuide = { perspectiveGuide = PerspectiveAxis.Vertical },
                        onPerspectiveGuideH = { perspectiveGuide = PerspectiveAxis.Horizontal },
                    )
                    Tool.Retouch -> RetouchPanel(
                        vm = vm,
                        tool = retouchTool,
                        onTool = { retouchTool = it; cloneSource = null; if (it != null) retouchErase = false },
                        size = retouchSize,
                        onSize = { retouchSize = it },
                        feather = retouchFeather,
                        onFeather = { retouchFeather = it },
                        strength = retouchStrength,
                        onStrength = { retouchStrength = it },
                        textureKeep = retouchTextureKeep,
                        onTextureKeep = { retouchTextureKeep = it },
                        cloneSource = cloneSource,
                        onCloneSource = { cloneSource = it },
                        onClearSource = { cloneSource = null },
                        eraseActive = retouchErase,
                        onEraseActive = {
                            retouchErase = it
                            if (it) { retouchTool = null; cloneSource = null }
                        },
                        onNotice = { notice = it },
                        range = retouchRange,
                        onRange = { retouchRange = it },
                    )
                    Tool.Presets -> PresetPanel(
                        vm = vm,
                        container = container,
                        glHost = glHost,
                        onNotice = { notice = it },
                    )
                    null -> {}
                }
                } // AnimatedContent
                }
            }
        }
    }
    // Edit-history sheet (Lightroom History panel parity).
    if (showHistory) {
        HistorySheet(
            vm = vm,
            onDismiss = { showHistory = false },
        )
    }
    // Named snapshots sheet (Lightroom Snapshots parity).
    if (showSnapshots) {
        SnapshotsSheet(
            vm = vm,
            onDismiss = { showSnapshots = false },
            onNotice = { notice = it },
        )
    }
    // GPU self-test: on-device verification of the mask shader path.
    if (selfTestRunning || selfTestResult != null) {
        val clipboard = LocalClipboardManager.current
        SelfTestDialog(
            running = selfTestRunning,
            result = selfTestResult,
            onDismiss = { selfTestRunning = false; selfTestResult = null },
            onCopyReport = {
                val r = selfTestResult
                if (r != null) {
                    clipboard.setText(AnnotatedString(r.toReportText()))
                    notice = "Self-test report copied"
                }
            },
        )
    }
    // "Reset all edits" confirm: destructive, so it asks first. resetAll()
    // is undoable + history-recorded, so the dialog is a guard, not the
    // only safety net.
    if (showResetConfirm) {
        LumoraDialog(
            onDismissRequest = { showResetConfirm = false },
            title = "Reset all edits",
            text = "This clears every adjustment on this photo — light, " +
                "color, masks, retouch and crop. You can undo it " +
                "afterwards from the toolbar.",
            confirmLabel = "Reset",
            confirmColor = colors.danger,
            onConfirm = {
                vm.resetAll()
                showResetConfirm = false
                notice = "All edits reset"
            },
        )
    }
    // Star rating (Lightroom parity with the library grid's rating). Tapping
    // the current rating clears it, mirroring the library's toggle behavior.
    if (showRateDialog) {
        RateDialog(
            rating = rating,
            onRate = { vm.setRating(it) },
            onDismiss = { showRateDialog = false },
        )
    }

    // ---- AI Photo Assistant (Gemini) ----
    // Floating shortcut button — only visible after the API key is set in
    // Settings. Tap to open the chat panel.
    if (aiAssistant.hasApiKey) {
        LumoraFab(
            onClick = { showAiPanel = true },
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(top = 64.dp, end = 12.dp),
            accessibilityLabel = "AI photo assistant",
        ) {
            Text("✨", style = Lumora.type.screenTitle)
        }
    }

    // AI chat panel as a bottom sheet overlay.
    if (showAiPanel) {
        LumoraBottomSheet(
            onDismissRequest = { showAiPanel = false },
            modifier = Modifier.fillMaxSize(),
        ) {
            AiAssistantPanel(
                messages = aiAssistant.messages,
                isLoading = aiAssistant.isLoading,
                error = aiAssistant.error,
                pendingSuggestions = aiAssistant.pendingSuggestions,
                hasApiKey = aiAssistant.hasApiKey,
                onAnalyzePhoto = vm::analyzePhotoWithAi,
                onSendMessage = vm::sendAiChatMessage,
                onApplySuggestions = vm::applyAiSuggestions,
                onDismissSuggestions = vm::dismissAiSuggestions,
                onClearError = vm::clearAiAssistantError,
                onClose = { showAiPanel = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    } // Box
}

private fun applyRetouchTap(
    vm: EditorViewModel,
    tool: RetouchOp,
    uv: Pt,
    size: Float,
    feather: Float,
    strength: Float,
    cloneSource: Pt?,
    /** Pre-resolved HEAL source for mid-stroke spots (aligned mode); null = solve fresh. */
    healSource: Pt? = null,
    onSourceSet: (Pt) -> Unit,
    addSpot: (RetouchSpot) -> Unit,
    /** SMOOTH_SKIN only: detail retention stored per spot. */
    textureKeep: Float = 0.45f,
    /** DODGE/BURN only: tonal range the stroke is limited to. */
    range: RetouchRange = RetouchRange.ALL,
) {
    if (uv.x !in 0f..1f || uv.y !in 0f..1f) return
    when (tool) {
        RetouchOp.CLONE -> {
            val src = cloneSource
            if (src == null) { onSourceSet(uv); return }
            addSpot(RetouchSpot(tool, uv.x, uv.y, src.x, src.y, size, feather, strength))
        }
        RetouchOp.HEAL -> {
            // Tone-matched auto source: probes nearby patches for one with a
            // similar tone that is smooth and fully in-frame. Falls back to a
            // fixed offset steered away from the nearest horizontal edge so it
            // can't collapse onto the target near the right edge.
            // Mid-stroke spots reuse the stroke's locked source offset instead
            // of re-solving per spot, so the source can't jump between spots
            // and leave seams along the stroke.
            val (sx, sy) = healSource?.let { it.x to it.y } ?: run {
                val auto = vm.healAutoSource(uv.x, uv.y, size)
                val s = auto ?: run {
                    val dir = if (uv.x > 0.75f) -1f else 1f
                    (uv.x + dir * 0.12f).coerceIn(0.02f, 0.98f) to uv.y
                }
                onSourceSet(Pt(s.first, s.second))
                s
            }
            addSpot(RetouchSpot(tool, uv.x, uv.y, sx, sy, size, feather, strength))
        }
        RetouchOp.SMOOTH_SKIN -> {
            // The texture-keep slider lives per stroke: remember it on the
            // spot so undo/redo and history keep the exact setting.
            addSpot(RetouchSpot(tool, uv.x, uv.y, uv.x, uv.y, size, feather, strength,
                textureKeep = textureKeep.coerceIn(0f, 1f)))
        }
        else -> addSpot(RetouchSpot(tool, uv.x, uv.y, uv.x, uv.y, size, feather, strength))
    }
}

private fun updateGradientShape(
    state: EditState, maskId: String, compId: String, shape: List<Float>,
): EditState = state.copy(
    masks = state.masks.map { m ->
        if (m.id != maskId) m
        else m.copy(
            components = m.components.map { c ->
                if (c.id != compId) c else c.copy(shape = shape, revision = System.nanoTime())
            },
            revision = System.nanoTime(),
        )
    }
)

/** Guided straighten: rotates the photo so the user-drawn line (source UV,
 *  y down) becomes horizontal in the final render. The displayed line angle
 *  is theta + totalRotate (flips cancel out mod 180°, uniform scale preserves
 *  angles), so replacing the total rotation with -theta levels it. Quarter
 *  turns go into rotate90 and the residual into the ±45° slider, so even
 *  near-vertical lines level correctly. Returns false when the two taps
 *  coincide — no line to straighten by — so the caller can keep straighten
 *  mode active for another try. */
private fun applyStraightenGuide(vm: EditorViewModel, p1: Pt, p2: Pt): Boolean {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    if (hypot(dx, dy) < 1e-4f) return false
    // Line angle in y-down UV space; the line is undirected, so fold to
    // (-90, 90] (drawing direction must not matter).
    var theta = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    theta = ((theta + 90f) % 180f + 180f) % 180f - 90f
    // Fold the needed rotation into quarter turns + slider residual, so
    // near-vertical lines (which the ±45° slider alone can't level) work.
    val k = (-theta / 90f).roundToInt()
    val rotate = (-theta - 90f * k).coerceIn(-45f, 45f)
    vm.update("Straighten") { s -> s.copy(geometry = s.geometry.copy(rotate = rotate, rotate90 = k)) }
    return true
}

@Composable
private fun ActiveToolChip(
    paintReq: PaintRequest?,
    retouchTool: RetouchOp?,
    retouchEraseActive: Boolean = false,
    cloneSource: Pt?,
    cropActive: Boolean,
    /** True while the crop quad has a corner outside the photo (renders
     *  black) — the hint switches to the out-of-bounds warning. */
    cropOobWarning: Boolean = false,
    pickActive: Boolean = false,
    pickError: Boolean = false,
    colorPickActive: Boolean = false,
    colorAddSampleActive: Boolean = false,
    hslPickActive: Boolean = false,
    straightenActive: Boolean = false,
    perspectiveActive: Boolean = false,
    perspectiveAxis: PerspectiveAxis = PerspectiveAxis.Vertical,
    splitActive: Boolean = false,
    focusActive: Boolean = false,
    curveTargetActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val text = when {
        pickError -> "Too dark to sample — tap a brighter neutral area"
        pickActive -> "Tap a neutral gray area to set white balance"
        colorAddSampleActive -> "Tap a color in the photo to add it to the selection"
        colorPickActive -> "Tap a color in the photo to select it"
        hslPickActive -> "Tap a color in the photo to target it"
        straightenActive -> "Grid shows level — tap two points on a line that should be level"
        perspectiveActive -> if (perspectiveAxis == PerspectiveAxis.Horizontal)
            "Tap two points on a line that should be horizontal"
        else "Tap two points on a line that should be vertical"
        splitActive -> "Drag the divider to compare — tap the compare icon to exit"
        focusActive -> "Drag on the photo to move the lens-blur focus point"
        curveTargetActive -> "Drag up or down on the photo to bend the curve at that tone"
        cropActive -> if (cropOobWarning)
            "Crop extends beyond the photo — those corners will export black"
        else "Drag corners or edges to crop — pinch to zoom in"
        retouchEraseActive -> "Tap a retouch spot to remove it"
        paintReq is PaintRequest.Brush && paintReq.erase -> "Eraser — paint to remove"
        paintReq is PaintRequest.Brush -> "Brush — paint the mask"
        paintReq is PaintRequest.Linear -> "Drag to place the gradient"
        paintReq is PaintRequest.Radial -> "Drag to size the radial"
        retouchTool == RetouchOp.CLONE && cloneSource == null -> "Tap to pick clone source"
        retouchTool == RetouchOp.CLONE -> "Tap or drag to stamp the clone"
        retouchTool == RetouchOp.HEAL -> "Tap or drag over blemishes to heal"
        retouchTool == RetouchOp.SMOOTH_SKIN -> "Paint over skin to smooth"
        retouchTool == RetouchOp.DODGE -> "Paint to lighten — sculpt light like a darkroom brush"
        retouchTool == RetouchOp.BURN -> "Paint to darken — deepen shadows and add depth"
        retouchTool == RetouchOp.EYE_ENHANCE -> "Tap an eye to enhance"
        retouchTool == RetouchOp.RED_EYE -> "Tap a red eye to fix"
        retouchTool == RetouchOp.TEETH_WHITEN -> "Tap teeth to whiten"
        retouchTool == RetouchOp.UNDER_EYE -> "Tap under-eye area"
        else -> null
    }
    AnimatedVisibility(visible = text != null, modifier = modifier) {
        text?.let {
            Box(
                Modifier.padding(top = 10.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Lumora.colors.scrim)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(it, style = Lumora.type.caption, color = Color.White)
            }
        }
    }
}

/**
 * Floating on-canvas brush controls, shown while a mask brush (or eraser) or
 * a retouch tool is painting. Keeps the most-used settings — size, feather,
 * flow and opacity for the mask brush (plus its add/erase switch), and
 * size/feather/strength for retouch spot tools — next to the photo,
 * Lightroom-style, instead of forcing the eye down to the bottom sheet
 * mid-stroke. Pass eraseActive = null to hide the add/erase switch
 * (retouch tools have no erase op).
 *
 * Every row behaves like LumoraSlider: double-tap the label (or the size
 * preview dot) to reset that row to its default, and TalkBack offers a
 * "Reset <label> to default" action plus a spoken "<label>, <value percent>".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrushQuickBar(
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    sizeDescription: String = "Brush size",
    sizeRange: ClosedFloatingPointRange<Float> = 0.01f..0.4f,
    sizeDefault: Float = 0.08f,
    eraseActive: Boolean? = null,
    onOpSwitch: ((toErase: Boolean) -> Unit)? = null,
    // optional second row: brush edge softness (mask brush) or retouch spot
    // feather (retouch paint tools)
    feather: Float? = null,
    onFeather: ((Float) -> Unit)? = null,
    featherDescription: String = "Brush feather",
    featherDefault: Float = 0.5f,
    // optional third row: effect strength (retouch spot tools) or brush
    // opacity (mask brush) — mirrors the feather row's compact layout so
    // the bar stays thumb-friendly.
    strength: Float? = null,
    onStrength: ((Float) -> Unit)? = null,
    strengthDescription: String = "Retouch strength",
    strengthDefault: Float = 1f,
    opacity: Float? = null,
    onOpacity: ((Float) -> Unit)? = null,
    opacityDescription: String = "Brush opacity",
    opacityDefault: Float = 1f,
    // optional fourth row: brush flow (mask brush) — per-dab paint strength,
    // 0.05..1 to match the Mask panel's Flow slider.
    flow: Float? = null,
    onFlow: ((Float) -> Unit)? = null,
    flowDescription: String = "Brush flow",
    flowDefault: Float = 0.85f,
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val haptics = LocalHapticFeedback.current
    fun resetHaptic() = haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    val sizeResettable = brushSize != sizeDefault
    val sizeResetLabel = "Reset $sizeDescription to default"
    fun resetSize() { resetHaptic(); onBrushSize(sizeDefault) }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.scrim)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // live brush-size dot: radius tracks the actual brush diameter.
            // Doubles as the size reset control (LumoraSlider parity).
            Canvas(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics {
                        contentDescription = "$sizeDescription preview"
                        if (sizeResettable) {
                            role = Role.Button
                            onClick(label = sizeResetLabel) { resetSize(); true }
                        }
                    }
                    .pointerInput(brushSize, sizeDefault) {
                        detectTapGestures(onDoubleTap = {
                            if (sizeResettable) resetSize()
                        })
                    },
            ) {
                val frac = ((brushSize - sizeRange.start) /
                    (sizeRange.endInclusive - sizeRange.start)).coerceIn(0f, 1f)
                val r = size.minDimension / 2 * (0.25f + 0.75f * frac)
                drawCircle(colors.divider, radius = size.minDimension / 2)
                drawCircle(colors.accent, radius = r)
            }
            LumoraCompactSlider(
                value = brushSize,
                onValueChange = onBrushSize,
                valueRange = sizeRange,
                default = sizeDefault,
                description =
                    "$sizeDescription, ${(brushSize * 100).roundToInt()} percent",
                resetLabel = sizeResetLabel,
                onReset = { resetSize() },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
            )
            Text(
                text = "${(brushSize * 100).roundToInt()}%",
                style = type.caption,
                color = colors.textPrimary,
                modifier = Modifier.width(40.dp),
            )
            if (eraseActive != null) {
                listOf("Add" to false, "Erase" to true).forEach { (label, erase) ->
                    val active = eraseActive == erase
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) colors.accentDim else Color.Transparent)
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                onClick(label = "$label brush") { onOpSwitch?.invoke(erase); true }
                            }
                            .clickable { onOpSwitch?.invoke(erase) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = type.toolLabel,
                            color = if (active) colors.accent else colors.textSecondary)
                    }
                }
            }
        }
        // feather row: softens the brush edge mid-stroke without opening the
        // Mask panel. Kept compact (shorter slider, shared %) so the bar
        // stays thumb-friendly.
        if (feather != null && onFeather != null) {
            QuickBarRow(
                label = "Feather",
                description = featherDescription,
                value = feather,
                valueRange = 0f..1f,
                default = featherDefault,
                onValueChange = onFeather,
                onResetHaptic = ::resetHaptic,
            )
        }
        // strength row: dials the effect strength mid-stroke without
        // opening the Retouch panel. Mirrors the feather row's compact
        // layout so the bar stays thumb-friendly.
        if (strength != null && onStrength != null) {
            QuickBarRow(
                label = "Strength",
                description = strengthDescription,
                value = strength,
                valueRange = 0f..1f,
                default = strengthDefault,
                onValueChange = onStrength,
                onResetHaptic = ::resetHaptic,
            )
        }
        // opacity row: mask brush paint opacity mid-stroke, without
        // opening the Mask panel. Same compact layout as the other rows;
        // the Mask panel's Opacity slider uses 0.05..1, matched here.
        if (opacity != null && onOpacity != null) {
            QuickBarRow(
                label = "Opacity",
                description = opacityDescription,
                value = opacity,
                valueRange = 0.05f..1f,
                default = opacityDefault,
                onValueChange = onOpacity,
                onResetHaptic = ::resetHaptic,
            )
        }
        // flow row: per-dab paint strength mid-stroke, without opening the
        // Mask panel. Same compact layout; range 0.05..1 matches the Mask
        // panel's Flow slider.
        if (flow != null && onFlow != null) {
            QuickBarRow(
                label = "Flow",
                description = flowDescription,
                value = flow,
                valueRange = 0.05f..1f,
                default = flowDefault,
                onValueChange = onFlow,
                onResetHaptic = ::resetHaptic,
            )
        }
    }
}

/**
 * One compact quick-bar slider row: label (double-tap to reset to [default])
 * + Lumora-styled Slider + % readout. Mirrors LumoraSlider's reset and
 * TalkBack behavior — resettable only when [value] != [default] — while
 * keeping the quick-bar's compact single-row layout (LumoraSlider's own
 * label-above-slider layout would not fit the floating bar).
 */
@Composable
private fun QuickBarRow(
    label: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    default: Float,
    onValueChange: (Float) -> Unit,
    onResetHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val resettable = value != default
    val resetLabel = "Reset $description to default"
    fun reset() { onResetHaptic(); onValueChange(default) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics {
                    if (resettable) {
                        role = Role.Button
                        onClick(label = resetLabel) { reset(); true }
                    }
                }
                .pointerInput(value, default) {
                    detectTapGestures(onDoubleTap = {
                        if (resettable) reset()
                    })
                }
                .padding(vertical = 8.dp),
        )
        LumoraCompactSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            default = default,
            description = "$description, ${(value * 100).roundToInt()} percent",
            resetLabel = resetLabel,
            onReset = { reset() },
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
        )
        Text(
            text = "${(value * 100).roundToInt()}%",
            style = type.caption,
            color = colors.textPrimary,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun EditorToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    clipMode: Int,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClip: (Int) -> Unit,
    onCompareHold: (Boolean) -> Unit,
    onToggleSplit: () -> Unit,
    splitActive: Boolean,
    onCopyEdits: () -> Unit,
    onPasteEdits: () -> Unit,
    pasteAvailable: Boolean,
    onHistory: () -> Unit,
    onSnapshots: () -> Unit,
    onSelfTest: () -> Unit,
    onResetAll: () -> Unit,
    rating: Int,
    onRate: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = Lumora.colors
    var clipMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 4.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, "Back")
        }
        Spacer(Modifier.weight(1f))
        ToolbarButton(onClick = onUndo, enabled = canUndo) {
            Icon(LumoraIcons.Undo, "Undo")
        }
        ToolbarButton(onClick = onRedo, enabled = canRedo) {
            Icon(LumoraIcons.Redo, "Redo")
        }
        Box {
            ToolbarButton(onClick = { clipMenu = true }) {
                Icon(
                    Icons.Default.Warning,
                    "Clipping indicators",
                    tint = if (clipMode == 0) colors.textSecondary else colors.accent,
                )
            }
            LumoraDropdownMenu(
                expanded = clipMenu,
                onDismissRequest = { clipMenu = false },
            ) {
                listOf(0 to "Off", 3 to "Shadows + highlights",
                    1 to "Shadows", 2 to "Highlights").forEach { (mode, label) ->
                    LumoraDropdownMenuItem(
                        text = label,
                        onClick = { onClip(mode); clipMenu = false },
                    )
                }
            }
        }
        // tap toggles the before/after split view; press-and-hold compares
        // with the original while held (onTap only fires for quick taps).
        // Gesture-driven (not clickable()), so it carries explicit semantics:
        // TalkBack announces a real button with its on/off state, and the
        // icon is decorative — its long text would otherwise double-announce.
        Box(
            modifier = Modifier.size(48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "Before/after compare"
                    stateDescription =
                        if (splitActive) "Split view on" else "Split view off"
                    onClick(label = "Toggle before/after split view") {
                        onToggleSplit(); true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleSplit() },
                        onPress = {
                            onCompareHold(true)
                            tryAwaitRelease()
                            onCompareHold(false)
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                LumoraIcons.CompareArrows,
                contentDescription = null,
                tint = if (splitActive) colors.accent else colors.textSecondary,
            )
        }
        // copy/paste edits (Lightroom "Copy Settings")
        Box {
            ToolbarButton(onClick = { overflowMenu = true }) {
                Icon(Icons.Default.MoreVert, "More options")
            }
            LumoraDropdownMenu(
                expanded = overflowMenu,
                onDismissRequest = { overflowMenu = false },
            ) {
                LumoraDropdownMenuItem(
                    text = "Copy edits",
                    icon = LumoraIcons.ContentCopy,
                    onClick = { onCopyEdits(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "Paste edits",
                    icon = LumoraIcons.ContentPaste,
                    enabled = pasteAvailable,
                    onClick = { onPasteEdits(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "Reset all edits",
                    icon = Icons.Default.Refresh,
                    onClick = { onResetAll(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "History",
                    icon = LumoraIcons.History,
                    onClick = { onHistory(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "Snapshots",
                    icon = LumoraIcons.Style,
                    onClick = { onSnapshots(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "Rate photo",
                    icon = Icons.Default.Star,
                    trailingContent = {
                        if (rating > 0) Text(
                            "$rating ★", color = colors.accent,
                            style = Lumora.type.caption,
                        )
                    },
                    onClick = { onRate(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "Create virtual copy",
                    icon = LumoraIcons.ContentCopy,
                    onClick = { onDuplicate(); overflowMenu = false },
                )
                LumoraDropdownMenuItem(
                    text = "Run GPU self-test",
                    icon = Icons.Default.CheckCircle,
                    onClick = { onSelfTest(); overflowMenu = false },
                )
            }
        }
        ToolbarButton(onClick = onExport) {
            Icon(LumoraIcons.IosShare, "Export", tint = colors.accent)
        }
    }
}

@Composable
private fun ToolNav(
    selected: Tool?,
    state: EditState,
    onSelect: (Tool) -> Unit,
) {
    val colors = Lumora.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tool.values().forEach { t ->
            ToolTile(
                label = t.label,
                selected = selected == t,
                dot = toolHasEdits(t, state),
                onClick = { onSelect(t) },
                modifier = Modifier.width(64.dp),
                icon = {
                    Icon(t.icon, t.label, modifier = Modifier.size(22.dp),
                        tint = if (selected == t) colors.accent else colors.textSecondary)
                },
            )
        }
    }
}

private fun toolHasEdits(t: Tool, s: EditState): Boolean = when (t) {
    Tool.Light -> s.light != com.lumora.photoeditor.engine.LightParams()
    Tool.Color -> s.color != com.lumora.photoeditor.engine.ColorParams()
    Tool.Grade -> s.grading != com.lumora.photoeditor.engine.GradingParams()
    Tool.Mask -> s.masks.isNotEmpty()
    Tool.Detail -> s.detail != com.lumora.photoeditor.engine.DetailParams()
    Tool.Optics -> s.optics != com.lumora.photoeditor.engine.OpticsParams()
    Tool.Crop -> s.crop != com.lumora.photoeditor.engine.CropParams() ||
        s.geometry != com.lumora.photoeditor.engine.GeometryParams()
    Tool.Retouch -> s.retouch.spots.isNotEmpty()
    Tool.Presets -> false
}

/**
 * On-device GPU self-test dialog. Runs the engine's synthetic-render checks
 * on the phone's own GPU and reports pass/fail per check — the verification
 * checkpoint for the mask shader path (the code the Mali fix touched).
 */
/**
 * Star-rating dialog (Lightroom parity): five 48dp star targets. Tapping the
 * star matching the current rating clears it back to 0, mirroring the
 * library grid's toggle. The dialog stays open after a tap so the rating
 * can be adjusted without reopening it; the live rating flows in via vm.
 */
@Composable
private fun RateDialog(
    rating: Int,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Lumora.colors
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "Rate photo",
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (i in 1..5) {
                    val filled = i <= rating
                    LumoraIconButton(
                        icon = if (filled) Icons.Default.Star else LumoraIcons.StarBorder,
                        contentDescription = if (filled && rating == i)
                            "Clear rating (currently $i of 5 stars)"
                        else "Rate $i of 5 stars",
                        onClick = { onRate(if (rating == i) 0 else i) },
                        tint = if (filled) colors.accent else colors.textTertiary,
                        iconSize = 32.dp,
                    )
                }
            }
        },
        confirmLabel = "Done",
        onConfirm = onDismiss,
        dismissLabel = null,
    )
}

@Composable
private fun SelfTestDialog(
    running: Boolean,
    result: SelfTestResult?,
    onDismiss: () -> Unit,
    onCopyReport: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "GPU self-test",
        onConfirm = onDismiss,
        confirmLabel = "Close",
        confirmEnabled = !running,
        // No separate Cancel: the Close action is the only dialog action;
        // Copy report moves into the content as an outlined button below
        // the results (it must stay visible even if Close is the confirm).
        dismissLabel = null,
        content = {
            if (running || result == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LumoraProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Rendering test patterns on your phone's GPU…",
                        style = type.body, color = colors.textSecondary,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (result.allPassed)
                            "All ${result.checks.size} checks passed on this device."
                        else
                            "${result.checks.count { it.passed }} of " +
                                "${result.checks.size} checks passed.",
                        style = type.body,
                        color = if (result.allPassed) colors.accent
                        else colors.danger,
                    )
                    result.checks.forEach { c ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                if (c.passed) Icons.Default.CheckCircle
                                else LumoraIcons.Error,
                                contentDescription =
                                    if (c.passed) "Passed" else "Failed",
                                tint = if (c.passed) colors.accent
                                else colors.danger,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = type.toolLabel,
                                    color = colors.textPrimary)
                                Text(c.detail, style = type.caption,
                                    color = colors.textSecondary)
                            }
                        }
                    }
                    Text(
                        "This verifies the mask shader renders correctly on " +
                            "your phone — the same code that draws mask " +
                            "overlays while you edit.",
                        style = type.caption, color = colors.textTertiary,
                    )
                    LumoraOutlineButton(
                        onClick = onCopyReport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy report", color = colors.accent,
                            style = type.body)
                    }
                }
            }
        },
    )
}

/**
 * Lightroom-style History panel: the persisted per-photo edit steps
 * (newest first), persisted across sessions. Tapping a step restores that
 * exact state — undoable, and the restore itself is recorded as a new step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val entries by vm.historyEntries.collectAsState()
    var failedRestore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshHistory() }
    LaunchedEffect(failedRestore) {
        if (failedRestore) {
            kotlinx.coroutines.delay(2200)
            failedRestore = false
        }
    }

    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "History", style = type.screenTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    LumoraTextButton(
                        onClick = { vm.clearHistory() },
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Text("Clear", color = colors.accent,
                            style = type.body)
                    }
                }
            }
            if (entries.isNotEmpty()) {
                Text(
                    "Tap a step to restore it. Restore is undoable.",
                    style = type.caption, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (entries.isEmpty()) {
                EmptyState(
                    title = "No history yet",
                    body = "Edits you make will appear here.",
                    icon = LumoraIcons.History,
                    compact = true,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    items(entries, key = { it.rowId }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onRestore = {
                                if (vm.restoreHistory(entry)) onDismiss()
                                else failedRestore = true
                            },
                        )
                    }
                }
            }
            if (failedRestore) {
                Text(
                    "Could not restore that step.",
                    style = type.caption, color = colors.danger,
                    modifier = Modifier.padding(horizontal = 20.dp)
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onRestore: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = "Restore ${entry.label}",
                onClick = onRestore,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LumoraIcons.History, contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entry.label, style = type.body, color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatHistoryTime(entry.timestamp),
            style = type.caption, color = colors.textTertiary,
        )
    }
}

/** Relative time for fresh edits, absolute date-time for older ones. */
private fun formatHistoryTime(ts: Long): String {
    val ageMin = (System.currentTimeMillis() - ts) / 60_000
    return when {
        ageMin < 1 -> "Just now"
        ageMin < 60 -> "$ageMin min ago"
        ageMin < 60 * 24 -> "${ageMin / 60} hr ago"
        else -> DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
    }
}

/**
 * Named snapshots sheet (Lightroom Snapshots parity): save the current full
 * edit state under a name, tap a snapshot to apply it (undoable + recorded
 * in History), rename or delete via the per-row options menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotsSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val snaps by vm.snapshots().collectAsState()
    var showNameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EditSnapshot?>(null) }
    var deleteTarget by remember { mutableStateOf<EditSnapshot?>(null) }

    LaunchedEffect(Unit) { vm.refreshSnapshots() }

    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        ) {
            Text(
                "Snapshots", style = type.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Save a named version of the current edits — tap one to jump back to it.",
                style = type.caption, color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            LumoraOutlineButton(
                onClick = { showNameDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(
                    Icons.Default.Add, contentDescription = null,
                    tint = colors.accent, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Take snapshot", color = colors.accent, style = type.body)
            }
            Spacer(Modifier.height(8.dp))
            if (snaps.isEmpty()) {
                EmptyState(
                    title = "No snapshots yet",
                    body = "Tap \"Take snapshot\" to freeze the current look.",
                    icon = LumoraIcons.Style,
                    compact = true,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    items(snaps, key = { it.id }) { snap ->
                        SnapshotRow(
                            snap = snap,
                            onApply = {
                                vm.applySnapshot(snap)
                                onNotice("Snapshot \"${snap.name}\" applied")
                                onDismiss()
                            },
                            onRename = { renameTarget = snap },
                            onDelete = { deleteTarget = snap },
                        )
                    }
                }
            }
        }
    }
    if (showNameDialog) {
        SnapshotNameDialog(
            title = "Take snapshot",
            initial = "Snapshot ${snaps.size + 1}",
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                showNameDialog = false
                vm.takeSnapshot(name) { ok ->
                    onNotice(if (ok) "Snapshot saved" else "Could not save snapshot")
                }
            },
        )
    }
    renameTarget?.let { snap ->
        SnapshotNameDialog(
            title = "Rename snapshot",
            initial = snap.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                vm.renameSnapshot(snap.id, name)
            },
        )
    }
    deleteTarget?.let { snap ->
        LumoraDialog(
            onDismissRequest = { deleteTarget = null },
            title = "Delete snapshot?",
            onConfirm = {
                vm.deleteSnapshot(snap.id)
                deleteTarget = null
            },
            text = "\"${snap.name}\" will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            confirmColor = colors.danger,
        )
    }
}

@Composable
private fun SnapshotRow(
    snap: EditSnapshot,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = "Apply snapshot ${snap.name}",
                onClick = onApply,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LumoraIcons.Style, contentDescription = null,
            tint = colors.textTertiary, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(snap.name, style = type.body, color = colors.textPrimary)
            Text(
                formatHistoryTime(snap.createdAt),
                style = type.caption, color = colors.textTertiary,
            )
        }
        Box {
            LumoraIconButton(
                icon = Icons.Default.MoreVert,
                contentDescription = "Snapshot options",
                onClick = { menuOpen = true },
            )
            LumoraDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                LumoraDropdownMenuItem(
                    text = "Rename",
                    onClick = { menuOpen = false; onRename() },
                )
                LumoraDropdownMenuItem(
                    text = "Delete",
                    textColor = colors.danger,
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** Name entry dialog shared by take-snapshot and rename-snapshot. */
@Composable
private fun SnapshotNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var name by remember { mutableStateOf(initial) }
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = title,
        onConfirm = { onConfirm(name) },
        content = {
            LumoraTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmLabel = "Save",
    )
}
