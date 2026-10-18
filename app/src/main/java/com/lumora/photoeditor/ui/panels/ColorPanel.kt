package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.CalibrationParams
import com.lumora.photoeditor.engine.ColorParams
import com.lumora.photoeditor.engine.EditState
import com.lumora.photoeditor.engine.HslAdjust
import com.lumora.photoeditor.engine.HueRange
import com.lumora.photoeditor.engine.ImageMath
import com.lumora.photoeditor.engine.PrimaryAdjust
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraSlider
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons

private fun hueColor(range: HueRange): Color {
    val rgb = ImageMath.hsl2rgb(range.centerDeg / 360f, 0.75f, 0.55f)
    return Color(rgb[0], rgb[1], rgb[2])
}

@Composable
fun ColorPanel(
    vm: EditorViewModel,
    /** Enters the one-shot targeted-adjustment pick mode (tap the photo). */
    onPickHslTarget: () -> Unit = {},
    /** When non-null, the mixer selects this range and consumes it. */
    targetRange: HueRange? = null,
    onTargetRangeConsumed: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val c = state.color
    var selected by remember { mutableStateOf(HueRange.RED) }

    // One-shot target from the photo: select the range and clear the request.
    LaunchedEffect(targetRange) {
        if (targetRange != null) {
            selected = targetRange
            onTargetRangeConsumed()
        }
    }

    PanelColumn {
        PanelHeader(
            title = "Color",
            showReset = state.color != ColorParams() || state.calibration != CalibrationParams(),
            resetLabel = "Reset color adjustments",
            onReset = { vm.update("Reset color") { s -> s.copy(color = ColorParams(), calibration = CalibrationParams()) } },
        )
        PanelSection("Global color") {
            ParamSlider(vm, "Vibrance", c.vibrance) { s, v ->
                s.copy(color = s.color.copy(vibrance = v))
            }
            ParamSlider(vm, "Saturation", c.saturation) { s, v ->
                s.copy(color = s.color.copy(saturation = v))
            }
        }
        PanelSection("Black & white") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                // Top-aligned, not centered: the caption wraps to two lines
                // on narrow phones, and a centered switch lands mid-row and
                // overlaps the wrapped second line (on-device video review).
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Black & white", style = Lumora.type.body,
                        color = Lumora.colors.textPrimary)
                    Text("Convert to monochrome with per-color luminance control",
                        style = Lumora.type.caption, color = Lumora.colors.textTertiary)
                }
                LumoraSwitch(
                    checked = c.bw,
                    onCheckedChange = { on ->
                        vm.update(if (on) "Enable black & white" else "Disable black & white") { s ->
                            s.copy(color = s.color.copy(bw = on))
                        }
                    },
                    contentDescription = "Black & white",
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (c.bw) {
                HueRange.values().forEach { range ->
                    ParamSlider(vm, range.label, c.bwMixFor(range),
                        historyLabel = "B&W ${range.label}") { s, v ->
                        s.copy(color = s.color.copy(
                            bwMix = s.color.bwMix + (range.name to v)))
                    }
                }
            }
        }
        PanelSection("Color mixer") {
            TextRow("Tap a color, then adjust its hue, saturation and luminance.")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LumoraTextButton(onClick = onPickHslTarget) {
                    androidx.compose.material3.Icon(
                        LumoraIcons.Colorize,
                        contentDescription = null,
                        tint = Lumora.colors.accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.Text(
                        "Target from photo",
                        color = Lumora.colors.accent, style = Lumora.type.caption)
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(HueRange.values()) { range ->
                    val adj = c.forRange(range)
                    val active = adj != HslAdjust()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(hueColor(range))
                                .border(
                                    width = if (selected == range) 2.5.dp else 1.dp,
                                    color = if (selected == range) Color.White
                                    else Lumora.colors.divider,
                                    shape = CircleShape,
                                )
                                // TalkBack: the visual label sits below the
                                // swatch (not a descendant), so the swatch
                                // itself announces a name, role and selected
                                // state — without this it was an unnamed
                                // control.
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${range.label} hue range"
                                    this.selected = selected == range
                                }
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Select ${range.label}",
                                ) { selected = range },
                        ) {
                            if (active) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(10.dp)
                                        .clip(CircleShape)
                                        .background(Lumora.colors.accent),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.Text(
                            range.label,
                            style = Lumora.type.toolLabel,
                            color = if (selected == range) Lumora.colors.textPrimary
                            else Lumora.colors.textTertiary,
                        )
                    }
                }
            }
            val adj = c.forRange(selected)
            ParamSlider(vm, "Hue", adj.hue) { s, v ->
                s.copy(color = s.color.copy(
                    mixer = s.color.mixer + (selected.name to adj.copy(hue = v))))
            }
            ParamSlider(vm, "Saturation", adj.saturation) { s, v ->
                s.copy(color = s.color.copy(
                    mixer = s.color.mixer + (selected.name to adj.copy(saturation = v))))
            }
            ParamSlider(vm, "Luminance", adj.luminance) { s, v ->
                s.copy(color = s.color.copy(
                    mixer = s.color.mixer + (selected.name to adj.copy(luminance = v))))
            }
        }
        // Lightroom Camera Calibration: fine-tune the color science itself.
        PanelSection("Calibration") {
            val cal = state.calibration
            TextRow("Shadows tint shifts green-magenta in the shadows; primaries remap the color channels for film-like color.")
            ParamSlider(vm, "Shadows tint", cal.shadowsTint) { s, v ->
                s.copy(calibration = s.calibration.copy(shadowsTint = v))
            }
            CalibrationPrimaryRow(vm, "Red primary", cal.red) { s, v ->
                s.copy(calibration = s.calibration.copy(red = v))
            }
            CalibrationPrimaryRow(vm, "Green primary", cal.green) { s, v ->
                s.copy(calibration = s.calibration.copy(green = v))
            }
            CalibrationPrimaryRow(vm, "Blue primary", cal.blue) { s, v ->
                s.copy(calibration = s.calibration.copy(blue = v))
            }
        }
        PanelSection("Look transfer") {
            val look = state.lookTransfer
            var showPicker by remember { mutableStateOf(false) }
            var lookRunning by remember { mutableStateOf(false) }
            var lookResult by remember { mutableStateOf<String?>(null) }
            if (!look.isActive()) {
                TextRow("Match this photo's colors to another photo's colors — one tap, fully offline. Deterministic color statistics, no AI.")
                LumoraButton(
                    onClick = { showPicker = true },
                    enabled = !lookRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    if (lookRunning) {
                        LumoraProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Matching colors…")
                    } else {
                        Icon(
                            LumoraIcons.Style, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Match a photo's look")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Look: ${look.refName}",
                            style = Lumora.type.body,
                            color = Lumora.colors.textPrimary,
                        )
                        Text(
                            "Reference-photo color match",
                            style = Lumora.type.caption,
                            color = Lumora.colors.textTertiary,
                        )
                    }
                }
                var strength by remember(look.strength) {
                    mutableStateOf(look.strength)
                }
                LumoraSlider(
                    label = "Strength",
                    value = strength,
                    onValueChange = {
                        strength = it
                        vm.setLookTransferStrength(it)
                    },
                    valueRange = 0f..1f,
                    default = 1f,
                    valueText = "${(strength * 100).toInt()}%",
                    onBegin = { vm.beginGesture() },
                    onEnd = { vm.endGesture("Look transfer strength") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LumoraOutlineButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Change")
                    }
                    LumoraOutlineButton(
                        onClick = {
                            vm.clearLookTransfer()
                            lookResult = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove")
                    }
                }
            }
            lookResult?.let {
                Text(
                    it,
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (showPicker) {
                LookTransferSheet(
                    vm = vm,
                    onDismiss = { showPicker = false },
                    onPick = { id ->
                        showPicker = false
                        lookRunning = true
                        lookResult = null
                        vm.applyLookTransfer(id) { msg ->
                            lookRunning = false
                            lookResult = msg
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CalibrationPrimaryRow(
    vm: EditorViewModel,
    label: String,
    value: PrimaryAdjust,
    onChange: (EditState, PrimaryAdjust) -> EditState,
) {
    androidx.compose.material3.Text(
        label,
        style = Lumora.type.caption,
        color = Lumora.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    ParamSlider(vm, "Hue", value.hue) { s, v ->
        onChange(s, value.copy(hue = v))
    }
    ParamSlider(vm, "Saturation", value.sat) { s, v ->
        onChange(s, value.copy(sat = v))
    }
}

@Composable
private fun TextRow(text: String) {
    androidx.compose.material3.Text(
        text,
        style = Lumora.type.caption,
        color = Lumora.colors.textTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
