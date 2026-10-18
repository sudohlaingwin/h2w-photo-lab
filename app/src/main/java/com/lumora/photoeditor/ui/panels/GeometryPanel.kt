package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.CropParams
import com.lumora.photoeditor.engine.GeometryParams
import com.lumora.photoeditor.core.AppSettings
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraIconButton
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.theme.LumoraIcons
import kotlin.math.abs
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora

private val ASPECTS = listOf(
    null to "Free", 1f to "1:1", 4f / 3f to "4:3", 3f / 2f to "3:2",
    16f / 9f to "16:9", 9f / 16f to "9:16", 5f / 4f to "5:4",
    // cycle 172: Lightroom-parity additions — portrait/near-square classics
    3f / 4f to "3:4", 4f / 5f to "4:5", 5f / 7f to "5:7",
)

@Composable
fun GeometryPanel(
    vm: EditorViewModel,
    onStraighten: () -> Unit = {},
    onAutoStraighten: () -> Unit = {},
    onPerspectiveGuide: () -> Unit = {},
    onPerspectiveGuideH: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val geo = state.geometry
    val crop = state.crop
    PanelColumn {
        PanelHeader(
            title = "Geometry",
            showReset = geo != GeometryParams(),
            resetLabel = "Reset geometry adjustments",
            onReset = { vm.update("Reset geometry") { s -> s.copy(geometry = GeometryParams()) } },
        )
        PanelSection("Crop") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Aspect ratio",
                    style = Lumora.type.caption,
                    color = Lumora.colors.textTertiary,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                // Lightroom "X" parity: flip landscape <-> portrait while
                // keeping the current crop area. Hidden for Free (no ratio)
                // and 1:1 (swap is a no-op).
                val aspect = crop.aspect
                if (aspect != null && aspect != 1f) {
                    LumoraIconButton(
                        icon = LumoraIcons.CompareArrows,
                        contentDescription = "Swap aspect orientation",
                        onClick = {
                            vm.beginGesture()
                            vm.update(gesture = true) { s ->
                                val a = s.crop.aspect
                                if (a == null || a == 1f) s
                                else {
                                    // Snap to a known ratio so the aspect
                                    // chip keeps highlighting (1/(16/9f) is
                                    // ~4e-8 off 9f/16f in floating point).
                                    val swapped = 1f / a
                                    val snapped = ASPECTS.mapNotNull { it.first }
                                        .minByOrNull { abs(it - swapped) }
                                        ?.takeIf { abs(it - swapped) < 1e-4f }
                                        ?: swapped
                                    s.copy(crop = applyAspect(s.crop, snapped))
                                }
                            }
                            vm.endGesture("Swap crop orientation")
                        },
                        iconSize = 20.dp,
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ASPECTS) { (ratio, label) ->
                    val sel = crop.aspect == ratio
                    AspectChip(
                        ratio = ratio,
                        label = label,
                        selected = sel,
                        onClick = {
                            vm.beginGesture()
                            vm.update(gesture = true) { s ->
                                s.copy(crop = applyAspect(s.crop, ratio))
                            }
                            vm.endGesture("Crop aspect")
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Composition guide drawn over the crop area (Lightroom "O"
            // cycle parity) — persisted, applies live while cropping.
            val overlayStyle by vm.cropOverlayStyle.collectAsState()
            Text(
                "Overlay",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(AppSettings.CROP_OVERLAY_LABELS.size) { i ->
                    LumoraChip(
                        selected = overlayStyle == i,
                        onClick = { vm.setCropOverlayStyle(i) },
                    ) {
                        Text(
                            AppSettings.CROP_OVERLAY_LABELS[i],
                            style = Lumora.type.controlLabel,
                            color = if (overlayStyle == i) Lumora.colors.accent
                            else Lumora.colors.textSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (crop != CropParams()) {
                LumoraTextButton(
                    onClick = {
                        vm.update("Reset crop") { s ->
                            s.copy(crop = CropParams().copy(aspect = s.crop.aspect))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Reset crop", color = Lumora.colors.accent,
                        style = Lumora.type.controlLabel)
                }
            }
            // Lightroom "Constrain to Image" parity: when on, rotating or
            // keystoning auto-shrinks the crop to the largest rectangle that
            // fits inside the photo, so no black corners ever render.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Constrain to image", style = Lumora.type.controlLabel,
                        color = Lumora.colors.textPrimary)
                    Text("Auto-fit the crop after rotate or perspective so no black corners show.",
                        style = Lumora.type.caption, color = Lumora.colors.textTertiary)
                }
                LumoraSwitch(
                    checked = geo.constrainCrop,
                    onCheckedChange = { checked ->
                        vm.update("Constrain to image") { s ->
                            s.copy(geometry = s.geometry.copy(constrainCrop = checked))
                        }
                    },
                    contentDescription = "Constrain to image",
                )
            }
        }
        PanelSection("Straighten") {
            ParamSlider(vm, "Rotate", geo.rotate, -45f..45f, "Rotate") { s, v ->
                s.copy(geometry = s.geometry.copy(rotate = v))
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Rotate90Button("Rotate 90° left") {
                    vm.update("Rotate 90° left") { s ->
                        s.copy(geometry = s.geometry.copy(rotate90 = s.geometry.rotate90 - 1))
                    }
                }
                Rotate90Button("Rotate 90° right") {
                    vm.update("Rotate 90° right") { s ->
                        s.copy(geometry = s.geometry.copy(rotate90 = s.geometry.rotate90 + 1))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            PanelActionButton(
                label = "Auto straighten",
                onClickLabel = "Auto straighten",
                onClick = onAutoStraighten,
            )
            Text(
                "Finds the horizon and levels the photo automatically.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            PanelActionButton(
                label = "Draw a line to straighten",
                onClickLabel = "Draw a line to straighten",
                onClick = onStraighten,
            )
            Text(
                "Tap two points or drag along a line on the photo that should be level.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        PanelSection("Perspective") {
            ParamSlider(vm, "Vertical", geo.perspectiveV, historyLabel = "Perspective") { s, v ->
                s.copy(geometry = s.geometry.copy(perspectiveV = v))
            }
            ParamSlider(vm, "Horizontal", geo.perspectiveH, historyLabel = "Perspective") { s, v ->
                s.copy(geometry = s.geometry.copy(perspectiveH = v))
            }
            Spacer(Modifier.height(4.dp))
            PanelActionButton(
                label = "Draw a line to fix verticals",
                onClickLabel = "Draw a line to fix verticals",
                onClick = onPerspectiveGuide,
            )
            Text(
                "Tap two points or drag along a line on the photo that should be vertical.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(4.dp))
            PanelActionButton(
                label = "Draw a line to fix horizontals",
                onClickLabel = "Draw a line to fix horizontals",
                onClick = onPerspectiveGuideH,
            )
            Text(
                "Tap two points or drag along a line on the photo that should be horizontal.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        PanelSection("Flip") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlipButton("Flip horizontal", geo.flipH) {
                    vm.update("Flip") { s ->
                        s.copy(geometry = s.geometry.copy(flipH = !s.geometry.flipH))
                    }
                }
                FlipButton("Flip vertical", geo.flipV) {
                    vm.update("Flip") { s ->
                        s.copy(geometry = s.geometry.copy(flipV = !s.geometry.flipV))
                    }
                }
            }
        }
    }
}

/**
 * Full-width panel action button (Geometry panel). One component for the four
 * hand-rolled Box+clickable buttons so their surface, shape, type and
 * accessibility semantics can never drift apart again. Visuals are unchanged:
 * panelElevated surface, 10.dp corners, accent toolLabel text, 12.dp vertical
 * padding. Every instance carries role=Button and an onClickLabel so
 * TalkBack announces the action ("Auto straighten", ...) instead of a bare
 * "double-tap to activate".
 */
@Composable
private fun PanelActionButton(
    label: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Lumora.colors.panelElevated)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Lumora.type.toolLabel,
            color = Lumora.colors.accent,
        )
    }
}

@Composable
private fun RowScope.FlipButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Lumora.colors.accentDim else Lumora.colors.panelElevated)
            // Toggleable, not a plain clickable: TalkBack announces
            // "checked/not checked" for the flip state (the visible label
            // text names the control, same pattern as the aspect chips
            // below). Visuals unchanged.
            .toggleable(
                value = active,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Lumora.type.toolLabel,
            color = if (active) Lumora.colors.accent else Lumora.colors.textSecondary)
    }
}

/**
 * Aspect-ratio chip showing a frame icon drawn at the real ratio (Lightroom
 * parity) above the label. Single-choice semantics so TalkBack announces
 * "selected / not selected"; min 48dp touch target.
 */
@Composable
private fun AspectChip(
    ratio: Float?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Lumora.colors
    val frame = if (selected) colors.accent else colors.textSecondary
    Column(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentDim else colors.panelElevated)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            // The visible label text below names the chip; selectable adds the
            // radio-button role plus selected/not-selected announcements.
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .sizeIn(maxWidth = 48.dp, maxHeight = 28.dp)
                .then(
                    if (ratio != null) Modifier.aspectRatio(ratio)
                    // "Free": a neutral upright frame
                    else Modifier.size(20.dp, 28.dp)
                )
                .border(
                    BorderStroke(
                        1.5.dp,
                        if (ratio == null && !selected) colors.textTertiary else frame,
                    ),
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = Lumora.type.toolLabel,
            color = if (selected) colors.accent else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Quarter-turn rotate button for the Straighten section (48dp touch target, TalkBack-safe). */
@Composable
private fun RowScope.Rotate90Button(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Lumora.colors.panelElevated)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Lumora.type.toolLabel,
            color = Lumora.colors.accent,
            maxLines = 1,
        )
    }
}

/** Applies an aspect ratio to the crop rect, centered. */
fun applyAspect(crop: CropParams, aspect: Float?): CropParams {
    if (aspect == null) return crop.copy(aspect = null)
    val cw = crop.right - crop.left
    val ch = crop.bottom - crop.top
    val cx = (crop.left + crop.right) / 2f
    val cy = (crop.top + crop.bottom) / 2f
    // fit the largest centered rect of [aspect] inside current crop
    var w = cw; var h = w / aspect
    if (h > ch) { h = ch; w = h * aspect }
    return crop.copy(
        left = (cx - w / 2).coerceIn(0f, 1f),
        right = (cx + w / 2).coerceIn(0f, 1f),
        top = (cy - h / 2).coerceIn(0f, 1f),
        bottom = (cy + h / 2).coerceIn(0f, 1f),
        aspect = aspect,
    )
}
