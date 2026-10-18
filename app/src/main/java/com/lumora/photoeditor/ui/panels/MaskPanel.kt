package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.*
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraDialog
import com.lumora.photoeditor.ui.components.LumoraIconButton
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraCard
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.components.EmptyState
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraBottomSheet
import com.lumora.photoeditor.ui.state.AiUiState
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons

sealed interface PaintRequest {
    data class Brush(val maskId: String, val compId: String, val erase: Boolean) : PaintRequest
    data class Linear(val maskId: String, val compId: String) : PaintRequest
    data class Radial(val maskId: String, val compId: String) : PaintRequest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaskPanel(
    vm: EditorViewModel,
    paintRequest: PaintRequest?,
    onPaintRequest: (PaintRequest?) -> Unit,
    onPickColorRange: () -> Unit,
    onRepickColorRange: (maskId: String, compId: String) -> Unit,
    onAddColorSample: (maskId: String, compId: String) -> Unit,
    /** Subtract/intersect flow: pick a color for a component appended to an
     *  existing mask instead of a brand-new mask. */
    onPickColorRangeComponent: (maskId: String, op: MaskOp) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    brushFeather: Float,
    onBrushFeather: (Float) -> Unit,
    brushFlow: Float,
    onBrushFlow: (Float) -> Unit,
    brushOpacity: Float,
    onBrushOpacity: (Float) -> Unit,
    brushAutoMask: Boolean,
    onBrushAutoMask: (Boolean) -> Unit,
    brushAutoMaskTolerance: Float,
    onBrushAutoMaskTolerance: (Float) -> Unit,
) {
    val state by vm.state.collectAsState()
    val selectedId by vm.selectedMaskId.collectAsState()
    val aiUi by vm.aiUi.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Mask?>(null) }
    var menuTarget by remember { mutableStateOf<Mask?>(null) }
    /** Subtract/intersect flow: (target mask id, op) while the type sheet is
     *  open. Null = the sheet creates a brand-new mask as usual. */
    var pendingOp by remember { mutableStateOf<Pair<String, MaskOp>?>(null) }

    val selected = state.masks.find { it.id == selectedId }

    PanelColumn {
        if (state.masks.isEmpty()) {
            EmptyState(
                title = "No masks yet",
                body = "Masks let you edit one part of the photo — the sky, a face, " +
                    "the background — without touching the rest.",
                icon = LumoraIcons.Brush,
                compact = true,
            )
        }
        // mask list
        state.masks.forEach { mask ->
            MaskRow(
                mask = mask,
                selected = mask.id == selectedId,
                onSelect = {
                    vm.selectMask(if (selectedId == mask.id) null else mask.id)
                    onPaintRequest(null)
                },
                onToggle = { vm.toggleMask(mask.id) },
                onMenu = { menuTarget = mask },
            )
        }

        // add mask button
        LumoraOutlineButton(
            onClick = { showAddMenu = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Default.Add, null, tint = Lumora.colors.accent,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add mask", color = Lumora.colors.textPrimary)
        }

        // One-click Auto Mask: analyzes the photo and creates smart masks
        // (Skin + Background for portraits, Sky for landscapes). Pro
        // workflow: one tap to isolate, then craft the look with local
        // adjustments.
        var autoMaskWorking by remember { mutableStateOf(false) }
        var autoMaskResult by remember { mutableStateOf<String?>(null) }
        /** True when the last Auto Mask run found nothing — the result row
         *  then offers a one-tap "Paint a mask" recovery instead of a
         *  dead-end message. */
        var autoMaskFoundNothing by remember { mutableStateOf(false) }
        LumoraButton(
            onClick = {
                autoMaskWorking = true
                autoMaskResult = null
                autoMaskFoundNothing = false
                vm.applyAutoMask { created ->
                    autoMaskWorking = false
                    autoMaskResult = if (created.isEmpty()) {
                        autoMaskFoundNothing = true
                        "No clear subject or sky found."
                    } else {
                        "Created: ${created.joinToString(", ")}"
                    }
                }
            },
            enabled = !autoMaskWorking,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (autoMaskWorking) {
                LumoraProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Lumora.colors.accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Analyzing photo…")
            } else {
                // Design-system icon (was a ✨ emoji glyph): decorative for
                // TalkBack — the "Auto Mask" label carries the meaning, and
                // emoji render inconsistently across fonts/devices.
                Icon(LumoraIcons.Brush, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Auto Mask")
            }
        }
        autoMaskResult?.let { result ->
            if (autoMaskFoundNothing) {
                // Recovery path, not a dead end: one tap creates a fresh
                // brush mask and drops straight into paint mode — identical
                // to picking "Brush" in the add-mask sheet then tapping the
                // Brush chip, without the detour.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        result,
                        style = Lumora.type.caption,
                        color = Lumora.colors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    LumoraTextButton(onClick = {
                        val maskId = vm.addMask(MaskType.BRUSH,
                            "Brush ${System.currentTimeMillis() % 1000}")
                        val compId = vm.addBrushComponent(maskId, false)
                        onPaintRequest(PaintRequest.Brush(maskId, compId, false))
                        autoMaskResult = null
                        autoMaskFoundNothing = false
                    }) {
                        Text("Paint a mask", color = Lumora.colors.accent)
                    }
                }
            } else {
                Text(
                    result,
                    style = Lumora.type.caption,
                    color = Lumora.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        // selected mask editor
        selected?.let { mask ->
            HorizontalDivider(color = Lumora.colors.divider,
                modifier = Modifier.padding(vertical = 4.dp))
            // Inline quick controls for the selected mask (Lightroom parity):
            // invert toggle + feather + amount, right under the mask list, so
            // the three most-reached-for settings don't need scrolling to the
            // Refine section or digging through the mask ⋮ menu.
            MaskQuickControls(vm = vm, mask = mask)
            val maskView by vm.maskViewMode.collectAsState()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Mask view", style = Lumora.type.body,
                    color = Lumora.colors.textPrimary)
                Text("How the selected mask looks while editing — B&W shows the exact selection edges",
                    style = Lumora.type.caption, color = Lumora.colors.textTertiary)
                Spacer(Modifier.height(6.dp))
                com.lumora.photoeditor.ui.components.SegmentedControl(
                    options = listOf(0 to "Off", 1 to "Red", 2 to "B&W"),
                    selected = maskView,
                    onSelect = { vm.setMaskViewMode(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Overlay opacity (Lightroom parity): the fixed 55% red can
                // hide the photo while refining fine edges (hair, sky), or be
                // too faint on bright scenes — let the user dial it. Shown
                // only when an overlay is actually visible.
                if (maskView != 0) {
                    val overlayAlpha by vm.maskOverlayAlpha.collectAsState()
                    Spacer(Modifier.height(2.dp))
                    com.lumora.photoeditor.ui.components.LumoraSlider(
                        label = "Overlay",
                        value = overlayAlpha * 100f,
                        valueRange = 5f..100f,
                        default = 55f,
                        onValueChange = { v -> vm.setMaskOverlayAlpha(v / 100f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            MaskEditor(
                vm = vm,
                mask = mask,
                paintRequest = paintRequest,
                onPaintRequest = onPaintRequest,
                onPickColorRange = onPickColorRange,
                onRepickColorRange = onRepickColorRange,
                onAddColorSample = onAddColorSample,
                brushSize = brushSize, onBrushSize = onBrushSize,
                brushFeather = brushFeather, onBrushFeather = onBrushFeather,
                brushFlow = brushFlow, onBrushFlow = onBrushFlow,
                brushOpacity = brushOpacity, onBrushOpacity = onBrushOpacity,
                brushAutoMask = brushAutoMask, onBrushAutoMask = onBrushAutoMask,
                brushAutoMaskTolerance = brushAutoMaskTolerance,
                onBrushAutoMaskTolerance = onBrushAutoMaskTolerance,
            )
        }

        // AI status
        when (val ai = aiUi) {
            is AiUiState.Working -> {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LumoraProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = Lumora.colors.accent)
                    Spacer(Modifier.width(12.dp))
                    Text(ai.label, style = Lumora.type.body,
                        color = Lumora.colors.textSecondary)
                }
            }
            is AiUiState.Error -> {
                LumoraCard(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    containerColor = Color(0xFF3A1D1D),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ai.message, style = Lumora.type.caption,
                            color = Color(0xFFFFB4B4), modifier = Modifier.weight(1f))
                        LumoraTextButton(onClick = vm::clearAiError) {
                            Text("Dismiss", color = Lumora.colors.accent)
                        }
                    }
                }
            }
            AiUiState.Idle -> {}
        }
    }

    if (showAddMenu) {
        val (targetId, op) = pendingOp ?: (null to null)
        val targetName = targetId?.let { id -> state.masks.find { it.id == id }?.name }
        AddMaskSheet(
            title = when (op) {
                MaskOp.SUBTRACT -> "Subtract from ${targetName ?: "mask"}"
                MaskOp.INTERSECT -> "Intersect with ${targetName ?: "mask"}"
                else -> "Add mask"
            },
            // Combining flow: only component types that can be appended to an
            // existing mask. Brush is excluded — the brush Add/Erase chips
            // already paint ADD/SUBTRACT brush components.
            componentMode = op != null,
            onDismiss = { showAddMenu = false; pendingOp = null },
            onPick = { type, label ->
                showAddMenu = false
                val pend = pendingOp
                pendingOp = null
                if (pend != null) {
                    val (maskId, maskOp) = pend
                    when (type) {
                        MaskType.SKY_SELECT -> vm.runAiComponent(maskId, type, maskOp, label)
                        MaskType.LUMINANCE_RANGE -> vm.runAiComponent(maskId, type, maskOp, label)
                        MaskType.DEPTH_RANGE -> vm.runAiComponent(maskId, type, maskOp, label)
                        MaskType.COLOR_RANGE -> onPickColorRangeComponent(maskId, maskOp)
                        else -> vm.addComponentToMask(maskId, type, maskOp)
                    }
                } else when (type) {
                    MaskType.AI_SUBJECT -> vm.runAiMask(type, "Subject")
                    MaskType.AI_BACKGROUND -> vm.runAiMask(type, "Background")
                    MaskType.SKY_SELECT -> vm.runAiMask(type, "Sky")
                    MaskType.LUMINANCE_RANGE -> vm.runAiMask(type, "Luminance",
                        "Could not build the luminance mask — try again.")
                    MaskType.DEPTH_RANGE -> vm.runAiMask(type, "Depth",
                        "Could not build the depth mask — try again.")
                    MaskType.COLOR_RANGE -> { showAddMenu = false; onPickColorRange() }
                    else -> vm.addMask(type, label)
                }
            },
        )
    }

    menuTarget?.let { mask ->
        MaskMenuSheet(
            mask = mask,
            onDismiss = { menuTarget = null },
            onRename = { renameTarget = mask; menuTarget = null },
            onDuplicate = { vm.duplicateMask(mask.id); menuTarget = null },
            onInvert = { vm.invertMask(mask.id); menuTarget = null },
            onSubtract = { pendingOp = mask.id to MaskOp.SUBTRACT; menuTarget = null; showAddMenu = true },
            onIntersect = { pendingOp = mask.id to MaskOp.INTERSECT; menuTarget = null; showAddMenu = true },
            onDelete = { vm.deleteMask(mask.id); menuTarget = null; onPaintRequest(null) },
        )
    }

    renameTarget?.let { mask ->
        RenameDialog(
            initial = mask.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.updateMask(mask.id, "Rename mask") { it.copy(name = name) }
                renameTarget = null
            },
        )
    }
}

@Composable
private fun maskTypeIcon(type: MaskType?) = when (type) {
    MaskType.BRUSH -> LumoraIcons.Brush
    MaskType.LINEAR_GRADIENT -> LumoraIcons.Gradient
    MaskType.RADIAL_GRADIENT -> LumoraIcons.DonutLarge
    MaskType.AI_SUBJECT -> Icons.Filled.Person
    MaskType.AI_BACKGROUND -> LumoraIcons.Landscape
    MaskType.AI_SKIN -> Icons.Filled.Person
    MaskType.SKY_SELECT -> LumoraIcons.Cloud
    MaskType.COLOR_RANGE -> LumoraIcons.Colorize
    MaskType.LUMINANCE_RANGE -> LumoraIcons.InvertColors
    MaskType.DEPTH_RANGE -> LumoraIcons.CenterFocusStrong
    null -> LumoraIcons.Tune
}

@Composable
private fun MaskRow(
    mask: Mask,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = Lumora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentDim else colors.panelElevated)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent.copy(alpha = 0.45f)
                else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            // Toggleable row (not a plain clickable): TalkBack announces
            // "selected/not selected" for the row while the eye and menu
            // icon buttons stay independently activatable. The mask name
            // text labels the control; the checkbox role adds the state.
            // Visuals unchanged.
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onSelect() },
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // mask-type tile: instant visual read of the mask kind, like Lightroom
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = maskTypeIcon(mask.components.firstOrNull()?.type),
                contentDescription = null, // caption below already names the type
                tint = if (mask.enabled) colors.accent else colors.textTertiary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        // Stateful description (not a static "Toggle mask"): TalkBack users
        // learn both the target mask and the outcome of activating it.
        LumoraIconButton(
            icon = if (mask.enabled) LumoraIcons.Visibility else LumoraIcons.VisibilityOff,
            contentDescription = if (mask.enabled) "Hide mask ${mask.name}"
            else "Show mask ${mask.name}",
            tint = if (mask.enabled) colors.textPrimary else colors.textTertiary,
            iconSize = 20.dp,
            onClick = onToggle,
        )
        Column(Modifier.weight(1f)) {
            Text(mask.name, style = Lumora.type.controlLabel,
                color = if (mask.enabled) colors.textPrimary else colors.textTertiary)
            Text(
                mask.components.joinToString(" · ") {
                    when (it.type) {
                        MaskType.BRUSH -> "Brush"
                        MaskType.LINEAR_GRADIENT -> "Linear"
                        MaskType.RADIAL_GRADIENT -> "Radial"
                        MaskType.AI_SUBJECT -> "Subject AI"
                        MaskType.AI_BACKGROUND -> "Background AI"
                        MaskType.AI_SKIN -> "Skin auto"
                        MaskType.SKY_SELECT -> "Sky auto"
                        MaskType.COLOR_RANGE -> "Color"
                        MaskType.LUMINANCE_RANGE -> "Luminance"
                        MaskType.DEPTH_RANGE -> "Depth"
                    } + when (it.op) {
                        MaskOp.ADD -> ""; MaskOp.SUBTRACT -> " −"; MaskOp.INTERSECT -> " ∩"
                    }
                } + if (mask.inverted) " · inverted" else "",
                style = Lumora.type.caption, color = colors.textTertiary)
        }
        if (mask.inverted) {
            Icon(
                LumoraIcons.InvertColors, "Mask inverted",
                tint = colors.accent, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        LumoraIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "Mask options",
            iconSize = 20.dp,
            onClick = onMenu,
        )
    }
}

/**
 * Inline quick controls for the selected mask (Lightroom parity): an invert
 * toggle plus feather and amount sliders, shown right under the mask list.
 * These bind the same state as the deeper Refine/Amount controls and the ⋮
 * menu's Invert item — every control stays in sync — so the user gets the
 * most-reached-for mask settings without scrolling or menu-diving.
 */
@Composable
private fun MaskQuickControls(vm: EditorViewModel, mask: Mask) {
    val colors = Lumora.colors
    val type = Lumora.type
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Selected mask", style = type.body, color = colors.textPrimary,
                modifier = Modifier.weight(1f))
            LumoraChip(
                selected = mask.inverted,
                onClick = { vm.invertMask(mask.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LumoraIcons.InvertColors, null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (mask.inverted) "Inverted" else "Invert")
                    }
                },
            )
        }
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Feather",
            value = mask.refineFeather * 100f,
            valueRange = 0f..100f,
            default = 0f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.update(gesture = true) { s ->
                    s.copy(masks = s.masks.map {
                        if (it.id == mask.id)
                            it.copy(refineFeather = v / 100f, revision = System.nanoTime())
                        else it
                    })
                }
            },
            onEnd = { vm.endGesture("Refine feather") },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Amount",
            value = mask.amount * 100f,
            valueRange = 0f..100f,
            default = 100f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.update(gesture = true) { s ->
                    s.copy(masks = s.masks.map {
                        if (it.id == mask.id)
                            it.copy(amount = v / 100f, revision = System.nanoTime())
                        else it
                    })
                }
            },
            onEnd = { vm.endGesture("Mask amount") },
        )
    }
}

@Composable
private fun MaskEditor(
    vm: EditorViewModel,
    mask: Mask,
    paintRequest: PaintRequest?,
    onPaintRequest: (PaintRequest?) -> Unit,
    onPickColorRange: () -> Unit,
    onRepickColorRange: (maskId: String, compId: String) -> Unit,
    onAddColorSample: (maskId: String, compId: String) -> Unit,
    brushSize: Float, onBrushSize: (Float) -> Unit,
    brushFeather: Float, onBrushFeather: (Float) -> Unit,
    brushFlow: Float, onBrushFlow: (Float) -> Unit,
    brushOpacity: Float, onBrushOpacity: (Float) -> Unit,
    brushAutoMask: Boolean, onBrushAutoMask: (Boolean) -> Unit,
    brushAutoMaskTolerance: Float, onBrushAutoMaskTolerance: (Float) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    var brushTab by remember(mask.id) { mutableStateOf("Add") } // Add | Erase
    /** Per-component editing (Lightroom parity): a mask can hold several
     *  components of one type (Subtract/Intersect). The editor edits the
     *  picked component instead of silently always editing the first one. */
    var selGradId by remember(mask.id) { mutableStateOf<String?>(null) }
    var selLumaId by remember(mask.id) { mutableStateOf<String?>(null) }
    var selColorId by remember(mask.id) { mutableStateOf<String?>(null) }
    var selDepthId by remember(mask.id) { mutableStateOf<String?>(null) }

    Text("Adjustments affect only this mask", style = type.caption,
        color = colors.textTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

    // per-component management (Lightroom parity — previously the combine
    // mode was fixed at creation time and only whole masks could be deleted):
    // every component of this mask with its combine mode (Add/Subtract/
    // Intersect) and a delete button. The first component is always the base.
    PanelSection("Components") {
        if (mask.components.isEmpty()) {
            Text("No components yet — add one with the buttons below.",
                style = type.caption, color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        val counts = mask.components.groupingBy { it.type }.eachCount()
        val seen = mutableMapOf<MaskType, Int>()
        mask.components.forEachIndexed { globalIdx, comp ->
            val idx = seen.getOrDefault(comp.type, 0)
            seen[comp.type] = idx + 1
            val label = compLabel(comp, idx, counts[comp.type] ?: 1)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = type.controlLabel, color = colors.textPrimary,
                    modifier = Modifier.weight(1f))
                if (globalIdx > 0) {
                    com.lumora.photoeditor.ui.components.SegmentedControl(
                        options = listOf(
                            MaskOp.ADD to "Add",
                            MaskOp.SUBTRACT to "−",
                            MaskOp.INTERSECT to "∩",
                        ),
                        selected = comp.op,
                        onSelect = { vm.setComponentOp(mask.id, comp.id, it) },
                        modifier = Modifier.width(190.dp),
                    )
                } else {
                    Text("Base", style = type.caption,
                        color = colors.textTertiary)
                }
                if (mask.components.size > 1) {
                    LumoraIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "Delete $label component",
                        iconSize = 20.dp,
                        onClick = {
                            if (vm.deleteComponent(mask.id, comp.id)) {
                                // clear an in-progress paint targeting the deleted component
                                val targeting = when (val pr = paintRequest) {
                                    is PaintRequest.Brush -> pr.compId == comp.id
                                    is PaintRequest.Linear -> pr.compId == comp.id
                                    is PaintRequest.Radial -> pr.compId == comp.id
                                    else -> false
                                }
                                if (targeting) onPaintRequest(null)
                            }
                        },
                    )
                }
            }
        }
    }

    // component paint controls
    val brushComps = mask.components.filter { it.type == MaskType.BRUSH }
    if (brushComps.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Add" to false, "Erase" to true).forEach { (label, erase) ->
                val active = paintRequest is PaintRequest.Brush &&
                    paintRequest.erase == erase && paintRequest.maskId == mask.id
                LumoraChip(
                    selected = active,
                    onClick = {
                        if (active) onPaintRequest(null)
                        else {
                            val compId = vm.addBrushComponent(mask.id, erase)
                            onPaintRequest(PaintRequest.Brush(mask.id, compId, erase))
                        }
                        brushTab = label
                    },
                    label = { Text(if (label == "Add") "Brush" else "Eraser") },
                )
            }
        }
        if (paintRequest is PaintRequest.Brush && paintRequest.maskId == mask.id) {
            // Live brush-footprint preview: same visual language as the
            // retouch brush, so Size/Feather read before painting.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.lumora.photoeditor.ui.components.BrushPreview(
                    diameter = brushSize, feather = brushFeather)
                Text(
                    "${(brushSize * 100).toInt()}% of photo width • " +
                        "${(brushFeather * 100).toInt()}% feather",
                    style = type.caption, color = colors.textTertiary)
            }
            // Brush defaults mirror EditorScreen's initial brush state
            // (0.08f size, 0.5f feather, 0.85f flow, 1f opacity): without them
            // the value pill rendered accent-tinted as "modified" on first
            // open and double-tap reset pushed sliders to 0 (a near-invisible
            // brush) instead of the real defaults.
            SliderRow("Size", brushSize, 0.01f..0.4f, onBrushSize, default = 0.08f)
            SliderRow("Feather", brushFeather, 0f..1f, onBrushFeather, default = 0.5f)
            SliderRow("Flow", brushFlow, 0.05f..1f, onBrushFlow, default = 0.85f)
            SliderRow("Opacity", brushOpacity, 0.05f..1f, onBrushOpacity, default = 1f)
            // Pro auto-mask: edge-aware brush that hugs tone boundaries.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                LumoraChip(
                    selected = brushAutoMask,
                    onClick = { onBrushAutoMask(!brushAutoMask) },
                    label = { Text(if (brushAutoMask) "Auto-mask ON" else "Auto-mask") },
                )
            }
            if (brushAutoMask) {
                // Edge tolerance: how far painted pixels may stray in tone
                // from the brush center before being rejected. Tight hugs
                // hard edges (hair against sky); loose follows soft
                // gradients. Percentage pill + double-tap reset come from
                // LumoraSlider like the other brush controls.
                SliderRow("Tolerance", brushAutoMaskTolerance, 0.02f..0.5f,
                    onBrushAutoMaskTolerance, default = 0.14f)
            }
            Text(
                if (brushAutoMask)
                    "Auto-mask: paint stays within similar tones — it won't bleed across strong edges. Lift your finger to commit."
                else "Paint directly on the photo. Flow sets how fast paint builds; Opacity caps the strongest the brush can paint. Lift your finger to commit.",
                style = type.caption, color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
    // gradient reposition hint (+ per-component editing: pick which gradient
    // the chip and feather slider below act on when several exist)
    val gradComps = mask.components.filter {
        it.type == MaskType.LINEAR_GRADIENT || it.type == MaskType.RADIAL_GRADIENT
    }
    val gradComp = gradComps.find { it.id == selGradId } ?: gradComps.firstOrNull()
    if (gradComp != null) {
        if (gradComps.size > 1) {
            ComponentPicker(
                comps = gradComps,
                selectedId = gradComp.id,
                onSelect = { selGradId = it },
            )
        }
        val active = when (val pr = paintRequest) {
            is PaintRequest.Linear -> pr.maskId == mask.id && pr.compId == gradComp.id
            is PaintRequest.Radial -> pr.maskId == mask.id && pr.compId == gradComp.id
            else -> false
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            LumoraChip(
                selected = active,
                onClick = {
                    onPaintRequest(
                        if (active) null
                        else if (gradComp.type == MaskType.LINEAR_GRADIENT)
                            PaintRequest.Linear(mask.id, gradComp.id)
                        else PaintRequest.Radial(mask.id, gradComp.id))
                },
                label = { Text(if (active) "Done repositioning" else "Reposition on photo") },
            )
        }
        if (active) Text("Drag on the photo to reposition the gradient.",
            style = type.caption, color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp))
        // radial gradient feather: edge softness of the ellipse (Lightroom parity —
        // previously hardcoded to 50% at drag time with no way to change it)
        if (gradComp.type == MaskType.RADIAL_GRADIENT) {
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Feather",
                value = gradComp.shape.getOrElse(5) { 0.5f } * 100f,
                valueRange = 0f..100f,
                default = 50f,
                onBegin = { vm.beginGesture() },
                onValueChange = { v ->
                    vm.updateGradientFeather(mask.id, gradComp.id, v / 100f)
                },
                onEnd = { vm.endGesture("Gradient feather") },
            )
        }
    }
    // luminance-range band editor (per-component: pick which range the
    // sliders act on when several exist)
    val lumaComps = mask.components.filter { it.type == MaskType.LUMINANCE_RANGE }
    val lumaComp = lumaComps.find { it.id == selLumaId } ?: lumaComps.firstOrNull()
    if (lumaComp != null) {
        if (lumaComps.size > 1) {
            ComponentPicker(
                comps = lumaComps,
                selectedId = lumaComp.id,
                onSelect = { selLumaId = it },
            )
        }
        Text("Select by brightness — e.g. darken only the sky, lift only shadows",
            style = type.caption, color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Range start",
            value = lumaComp.lumaLo * 100f,
            valueRange = 0f..100f,
            default = 0f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateLuminanceRange(mask.id, lumaComp.id, v / 100f,
                    lumaComp.lumaHi, lumaComp.lumaFeather)
            },
            onEnd = {
                vm.endGesture("Luminance range")
                vm.refreshLuminanceRaster(mask.id, lumaComp.id)
            },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Range end",
            value = lumaComp.lumaHi * 100f,
            valueRange = 0f..100f,
            default = 100f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateLuminanceRange(mask.id, lumaComp.id, lumaComp.lumaLo,
                    v / 100f, lumaComp.lumaFeather)
            },
            onEnd = {
                vm.endGesture("Luminance range")
                vm.refreshLuminanceRaster(mask.id, lumaComp.id)
            },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Range feather",
            value = lumaComp.lumaFeather * 100f,
            valueRange = 0f..100f,
            default = 50f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateLuminanceRange(mask.id, lumaComp.id, lumaComp.lumaLo,
                    lumaComp.lumaHi, v / 100f)
            },
            onEnd = {
                vm.endGesture("Luminance range")
                vm.refreshLuminanceRaster(mask.id, lumaComp.id)
            },
        )
    }

    // depth-range band editor (per-component: pick which range the sliders
    // act on when several exist). The map is ESTIMATED from focus
    // sharpness — the caption says so, not a real depth sensor read.
    val depthComps = mask.components.filter { it.type == MaskType.DEPTH_RANGE }
    val depthComp = depthComps.find { it.id == selDepthId } ?: depthComps.firstOrNull()
    if (depthComp != null) {
        if (depthComps.size > 1) {
            ComponentPicker(
                comps = depthComps,
                selectedId = depthComp.id,
                onSelect = { selDepthId = it },
            )
        }
        Text("Select by estimated focus depth — sharp areas are near, soft areas far",
            style = type.caption, color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Range start",
            value = depthComp.depthLo * 100f,
            valueRange = 0f..100f,
            default = 0f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateDepthRange(mask.id, depthComp.id, v / 100f,
                    depthComp.depthHi, depthComp.depthFeather)
            },
            onEnd = {
                vm.endGesture("Depth range")
                vm.refreshDepthRaster(mask.id, depthComp.id)
            },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Range end",
            value = depthComp.depthHi * 100f,
            valueRange = 0f..100f,
            default = 100f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateDepthRange(mask.id, depthComp.id, depthComp.depthLo,
                    v / 100f, depthComp.depthFeather)
            },
            onEnd = {
                vm.endGesture("Depth range")
                vm.refreshDepthRaster(mask.id, depthComp.id)
            },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Range feather",
            value = depthComp.depthFeather * 100f,
            valueRange = 0f..100f,
            default = 50f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateDepthRange(mask.id, depthComp.id, depthComp.depthLo,
                    depthComp.depthHi, v / 100f)
            },
            onEnd = {
                vm.endGesture("Depth range")
                vm.refreshDepthRaster(mask.id, depthComp.id)
            },
        )
    }

    // color-range editor: picked color swatch + repick + tolerance/feather
    // (per-component: pick which color range the editor acts on when several exist)
    val colorComps = mask.components.filter { it.type == MaskType.COLOR_RANGE }
    val colorComp = colorComps.find { it.id == selColorId } ?: colorComps.firstOrNull()
    if (colorComp != null) {
        if (colorComps.size > 1) {
            ComponentPicker(
                comps = colorComps,
                selectedId = colorComp.id,
                onSelect = { selColorId = it },
            )
        }
        Text("Select similar colors — e.g. shift only the sky blue, or only skin tones",
            style = type.caption, color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(colorComp.colorR, colorComp.colorG, colorComp.colorB))
                    .border(1.dp, colors.divider, RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            LumoraChip(
                selected = false,
                onClick = { onRepickColorRange(mask.id, colorComp.id) },
                label = { Text("Repick color") },
            )
        }
        // Refine (Lightroom "add color swatch"): extra samples widen the selection.
        // Primary target is never removable; each added swatch can be dropped.
        Text("Refine — add swatches to widen, tap ✕ to drop one",
            style = type.caption, color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            colorComp.colorSamples.forEachIndexed { index, sample ->
                Box(
                    modifier = Modifier.size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(sample.r, sample.g, sample.b))
                        .border(1.dp, colors.divider, RoundedCornerShape(10.dp)),
                ) {
                    LumoraIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Remove this color swatch",
                        tint = Color.White.copy(alpha = 0.9f),
                        iconSize = 12.dp,
                        // Default 48dp minimum hit area (no minTouchTarget
                        // override): the visual glyph stays a 12dp ✕ overlaid
                        // on the swatch, but the tappable region reaches the
                        // platform minimum. The swatch itself is not
                        // clickable, so the larger hit area conflicts with
                        // nothing.
                        modifier = Modifier.align(Alignment.TopEnd),
                        onClick = { vm.removeColorSample(mask.id, colorComp.id, index) },
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            if (colorComp.colorSamples.size < 8) {
                LumoraChip(
                    selected = false,
                    onClick = { onAddColorSample(mask.id, colorComp.id) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Add swatch")
                        }
                    },
                )
            }
        }
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Tolerance",
            value = colorComp.colorTolerance * 100f,
            valueRange = 0f..100f,
            default = 45f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateColorRange(mask.id, colorComp.id,
                    tolerance = v / 100f)
            },
            onEnd = {
                vm.endGesture("Color tolerance")
                vm.refreshColorRaster(mask.id, colorComp.id)
            },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Feather",
            value = colorComp.colorFeather * 100f,
            valueRange = 0f..100f,
            default = 50f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.updateColorRange(mask.id, colorComp.id,
                    feather = v / 100f)
            },
            onEnd = {
                vm.endGesture("Color feather")
                vm.refreshColorRaster(mask.id, colorComp.id)
            },
        )
    }

    // mask effect strength (Lightroom "Amount")
    com.lumora.photoeditor.ui.components.LumoraSlider(
        label = "Amount",
        value = mask.amount * 100f,
        valueRange = 0f..100f,
        default = 100f,
        onBegin = { vm.beginGesture() },
        onValueChange = { v ->
            vm.update(gesture = true) { s ->
                s.copy(masks = s.masks.map {
                    if (it.id == mask.id)
                        it.copy(amount = v / 100f, revision = System.nanoTime())
                    else it
                })
            }
        },
        onEnd = { vm.endGesture("Mask amount") },
    )

    // pro mask refine: grow/shrink the selection, then soften its edge
    // (Lightroom "Refine" — essential after AI/sky/subject selections)
    PanelSection("Refine") {
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Expand",
            value = mask.refineExpand * 100f,
            valueRange = -100f..100f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.update(gesture = true) { s ->
                    s.copy(masks = s.masks.map {
                        if (it.id == mask.id)
                            it.copy(refineExpand = v / 100f, revision = System.nanoTime())
                        else it
                    })
                }
            },
            onEnd = { vm.endGesture("Refine expand") },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Feather",
            value = mask.refineFeather * 100f,
            valueRange = 0f..100f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.update(gesture = true) { s ->
                    s.copy(masks = s.masks.map {
                        if (it.id == mask.id)
                            it.copy(refineFeather = v / 100f, revision = System.nanoTime())
                        else it
                    })
                }
            },
            onEnd = { vm.endGesture("Refine feather") },
        )
        com.lumora.photoeditor.ui.components.LumoraSlider(
            label = "Decontaminate",
            value = mask.decontaminate * 100f,
            valueRange = 0f..100f,
            onBegin = { vm.beginGesture() },
            onValueChange = { v ->
                vm.update(gesture = true) { s ->
                    s.copy(masks = s.masks.map {
                        if (it.id == mask.id)
                            it.copy(decontaminate = v / 100f, revision = System.nanoTime())
                        else it
                    })
                }
            },
            onEnd = { vm.endGesture("Decontaminate edges") },
        )
        Text(
            "Expand +100% grows the selection; −100% contracts it. Feather softens the mask edge. " +
                "Decontaminate recolors feathered edge pixels from the selected area — " +
                "fixes color halos around hair when the background is changed. Not AI.",
            style = com.lumora.photoeditor.ui.theme.Lumora.type.caption,
            color = com.lumora.photoeditor.ui.theme.Lumora.colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // local adjustments
    val a = mask.adjust
    PanelSection("Local adjustments") {
        LocalSlider(vm, mask, "Exposure") { adj, v -> adj.copy(exposure = v) } 
        LocalSlider(vm, mask, "Contrast") { adj, v -> adj.copy(contrast = v) }
        LocalSlider(vm, mask, "Highlights") { adj, v -> adj.copy(highlights = v) }
        LocalSlider(vm, mask, "Shadows") { adj, v -> adj.copy(shadows = v) }
        LocalSlider(vm, mask, "Temperature") { adj, v -> adj.copy(temp = v) }
        LocalSlider(vm, mask, "Tint") { adj, v -> adj.copy(tint = v) }
        LocalSlider(vm, mask, "Saturation") { adj, v -> adj.copy(saturation = v) }
        LocalSlider(vm, mask, "Clarity") { adj, v -> adj.copy(clarity = v) }
        LocalSlider(vm, mask, "Sharpness", 0f..100f) { adj, v -> adj.copy(sharpness = v) }
        LocalSlider(vm, mask, "Dehaze") { adj, v -> adj.copy(dehaze = v) }
    }
}

/**
 * Component picker (Lightroom parity): a mask can hold several components of
 * one type (e.g. a radial gradient subtracted from another). Previously the
 * editor silently edited only the first component of each type, so later
 * components could not be tuned at all. Shown only when the group has more
 * than one component; single-component masks behave exactly as before.
 */
@Composable
private fun ComponentPicker(
    comps: List<MaskComponent>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val type = Lumora.type
    val colors = Lumora.colors
    Text("Edit component:",
        style = type.caption, color = colors.textTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val counts = comps.groupingBy { it.type }.eachCount()
        val seen = mutableMapOf<MaskType, Int>()
        items(comps, key = { it.id }) { comp ->
            val idx = seen.getOrDefault(comp.type, 0)
            seen[comp.type] = idx + 1
            LumoraChip(
                selected = comp.id == selectedId,
                onClick = { onSelect(comp.id) },
                label = { Text(compLabel(comp, idx, counts[comp.type] ?: 1)) },
            )
        }
    }
}

/** Short label for a component chip: type (+ number when several of the type
 *  exist) + op suffix (" −" subtract, " ∩" intersect). */
private fun compLabel(comp: MaskComponent, indexWithinType: Int, typeCount: Int): String {
    val base = when (comp.type) {
        MaskType.AI_SKIN -> "Skin"
        MaskType.LINEAR_GRADIENT -> "Linear"
        MaskType.RADIAL_GRADIENT -> "Radial"
        MaskType.LUMINANCE_RANGE -> "Luminance"
        MaskType.COLOR_RANGE -> "Color range"
        MaskType.DEPTH_RANGE -> "Depth"
        else -> "Mask"
    }
    val numbered = if (typeCount > 1) "$base ${indexWithinType + 1}" else base
    return numbered + when (comp.op) {
        MaskOp.ADD -> ""
        MaskOp.SUBTRACT -> " −"
        MaskOp.INTERSECT -> " ∩"
    }
}

@Composable
private fun LocalSlider(
    vm: EditorViewModel,
    mask: Mask,
    label: String,
    range: ClosedFloatingPointRange<Float> = -100f..100f,
    set: (LocalAdjust, Float) -> LocalAdjust,
) {
    val value = when (label) {
        "Exposure" -> mask.adjust.exposure
        "Contrast" -> mask.adjust.contrast
        "Highlights" -> mask.adjust.highlights
        "Shadows" -> mask.adjust.shadows
        "Temperature" -> mask.adjust.temp
        "Tint" -> mask.adjust.tint
        "Saturation" -> mask.adjust.saturation
        "Clarity" -> mask.adjust.clarity
        "Sharpness" -> mask.adjust.sharpness
        "Dehaze" -> mask.adjust.dehaze
        else -> 0f
    }
    com.lumora.photoeditor.ui.components.LumoraSlider(
        label = label,
        value = value,
        valueRange = range,
        onBegin = { vm.beginGesture() },
        onValueChange = { v ->
            vm.update(gesture = true) { s ->
                s.copy(masks = s.masks.map {
                    if (it.id == mask.id)
                        it.copy(adjust = set(it.adjust, v), revision = System.nanoTime())
                    else it
                })
            }
        },
        onEnd = { vm.endGesture("Mask $label") },
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, default: Float = 0f) {
    com.lumora.photoeditor.ui.components.LumoraSlider(
        label = label, value = value, valueRange = range,
        default = default,
        onValueChange = onChange, onBegin = {}, onEnd = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMaskSheet(
    title: String = "Add mask",
    /** True when appending a subtract/intersect component to an existing mask:
     *  brush + AI-subject types are hidden (brush erase chips already cover
     *  brush subtract; subject/background AI was removed). */
    componentMode: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (MaskType, String) -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    LumoraBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(title, style = type.screenTitle, color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (!componentMode) {
                MaskTypeOption("Brush", "Paint precisely where the edit applies", LumoraIcons.Brush) {
                    onPick(MaskType.BRUSH, "Brush ${System.currentTimeMillis() % 1000}") }
            }
            MaskTypeOption("Linear gradient", "Smooth transition across the frame", LumoraIcons.Gradient) {
                onPick(MaskType.LINEAR_GRADIENT, "Linear gradient") }
            MaskTypeOption("Radial gradient", "Soft elliptical selection", LumoraIcons.RadioButtonUnchecked) {
                onPick(MaskType.RADIAL_GRADIENT, "Radial gradient") }
            MaskTypeOption("Luminance range", "Select by brightness", LumoraIcons.Tune) {
                onPick(MaskType.LUMINANCE_RANGE, "Luminance") }
            MaskTypeOption("Color range", "Tap a color to select it", LumoraIcons.Palette) {
                onPick(MaskType.COLOR_RANGE, "Color range") }
            MaskTypeOption("Depth range", "Select by estimated focus depth", LumoraIcons.CenterFocusStrong) {
                onPick(MaskType.DEPTH_RANGE, "Depth") }
            if (!componentMode) {
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                Text("Intelligent", style = type.sectionTitle, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            MaskTypeOption("Sky", "Automatic sky selection", LumoraIcons.Cloud) {
                onPick(MaskType.SKY_SELECT, "Sky") }
        }
    }
}

@Composable
private fun MaskTypeOption(
    title: String, subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Lumora.colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = Lumora.type.controlLabel, color = Lumora.colors.textPrimary)
            Text(subtitle, style = Lumora.type.caption, color = Lumora.colors.textTertiary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaskMenuSheet(
    mask: Mask, onDismiss: () -> Unit, onRename: () -> Unit,
    onDuplicate: () -> Unit, onInvert: () -> Unit,
    onSubtract: () -> Unit, onIntersect: () -> Unit,
    onDelete: () -> Unit,
) {
    LumoraBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            MenuItem("Rename", Icons.Default.Edit, onRename)
            MenuItem("Duplicate", LumoraIcons.ContentCopy, onDuplicate)
            MenuItem(if (mask.inverted) "Uninvert" else "Invert", LumoraIcons.InvertColors, onInvert)
            MenuItem("Subtract…", LumoraIcons.Remove, onSubtract)
            MenuItem("Intersect…", LumoraIcons.CenterFocusStrong, onIntersect)
            MenuItem("Delete", Icons.Default.Delete, onDelete, danger = true)
        }
    }
}

@Composable
private fun MenuItem(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit, danger: Boolean = false,
) {
    LumoraTextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null,
                tint = if (danger) Lumora.colors.danger else Lumora.colors.textSecondary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (danger) Lumora.colors.danger else Lumora.colors.textPrimary)
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "Rename mask",
        content = {
            LumoraTextField(
                value = text,
                onValueChange = { text = it },
                label = "Mask name",
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmLabel = "Save",
        onConfirm = { onConfirm(text.ifBlank { initial }) },
    )
}
