package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.lumora.photoeditor.engine.CurvePoint
import com.lumora.photoeditor.engine.CurvePreset
import com.lumora.photoeditor.engine.activePreset
import com.lumora.photoeditor.engine.ImageMath
import com.lumora.photoeditor.engine.LightParams
import com.lumora.photoeditor.engine.ToneCurve
import com.lumora.photoeditor.ui.components.HistogramView
import com.lumora.photoeditor.ui.components.RgbParadeView
import com.lumora.photoeditor.ui.components.VectorscopeView
import com.lumora.photoeditor.ui.components.WaveformView
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.SegmentedControl
import com.lumora.photoeditor.ui.components.ToneCurveEditor
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlinx.coroutines.delay

/** One-tap prompt-edit examples (each is a working prompt). */
private val promptExamples = listOf(
    "Dramatic sky",
    "Warm sunset",
    "Brighten the face",
    "Soften the skin",
    "Darken the background",
    "Lift the shadows",
    "Make it pop",
    "Black and white",
    "Remove blemishes",
    "Brighten the eyes",
    "Soften dark circles",
)

@Composable
fun LightPanel(
    vm: EditorViewModel,
    onPickWhiteBalance: () -> Unit = {},
    curveChannel: Int,
    onCurveChannel: (Int) -> Unit,
    curveTargetActive: Boolean,
    onCurveTarget: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val histogram by vm.histogram.collectAsState()
    val waveform by vm.waveform.collectAsState()
    val vectorscope by vm.vectorscope.collectAsState()
    val rgbParade by vm.rgbParade.collectAsState()
    val l = state.light
    var scopeMode by remember { mutableStateOf(0) } // 0 = histogram, 1 = waveform, 2 = vectorscope, 3 = RGB parade

    PanelColumn {
        // One-tap enhance feedback: what Auto Enhance changed, or why it
        // did nothing. Previously the button gave no confirmation at all —
        // failures were silent and successes left the user guessing which
        // sliders moved.
        var autoOutcome by remember { mutableStateOf<EditorViewModel.AutoEnhanceOutcome?>(null) }
        // Toggle affordance: while an auto result is live, the header
        // action reads "Undo Auto" (second tap reverts to the pre-auto
        // state — user decision 2026-10-17).
        val autoApplied by vm.autoEnhanceApplied.collectAsState()
        // ---- Prompt edit (pro-spec Phase 1, v1: offline keyword rules, no AI) ----
        var promptText by remember { mutableStateOf("") }
        var promptWorking by remember { mutableStateOf(false) }
        var promptResult by remember { mutableStateOf<String?>(null) }
        fun runPrompt(text: String) {
            val t = text.trim()
            if (t.isEmpty() || promptWorking) return
            promptWorking = true
            promptResult = null
            vm.applyPromptEdit(t) { msg ->
                promptWorking = false
                promptResult = msg
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
        ) {
            Text("Prompt edit", style = Lumora.type.sectionTitle,
                color = Lumora.colors.textPrimary)
            Text(
                "Describe the edit in plain words. Keyword rules — no AI, no network — " +
                    "applied as one undoable edit. Name a region (sky, face, background, " +
                    "skin, shadows) to mask it, ask for a portrait fix (\"remove blemishes\", " +
                    "\"brighten the eyes\"), or leave it out for the whole photo.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LumoraTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = "Describe the edit",
                    placeholder = "e.g. dramatic sky",
                    enabled = !promptWorking,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { runPrompt(promptText) }),
                )
                LumoraButton(
                    onClick = { runPrompt(promptText) },
                    enabled = !promptWorking && promptText.isNotBlank(),
                ) {
                    if (promptWorking) {
                        LumoraProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Apply")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(promptExamples) { ex ->
                    LumoraChip(
                        selected = false,
                        onClick = { promptText = ex; runPrompt(ex) },
                        label = { Text(ex) },
                    )
                }
            }
            promptResult?.let { result ->
                Text(
                    result,
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        PanelHeader(
            title = "Light",
            showReset = state.light != LightParams(),
            resetLabel = "Reset light adjustments",
            onReset = { vm.update("Reset light") { s -> s.copy(light = LightParams()) } },
            actionLabel = if (autoApplied) "Undo Auto" else "Auto",
            actionContentDescription = if (autoApplied)
                "Auto enhance is applied: tap to revert to the pre-auto state"
            else
                "Auto enhance: balance exposure and color automatically",
            onAction = {
                autoOutcome = null
                vm.applyAutoEnhance { outcome -> autoOutcome = outcome }
            },
        )
        autoOutcome?.let { outcome ->
            val failed = outcome is EditorViewModel.AutoEnhanceOutcome.Failed
            Text(
                text = when (outcome) {
                    is EditorViewModel.AutoEnhanceOutcome.Failed ->
                        "Couldn't analyze this photo — try again"
                    is EditorViewModel.AutoEnhanceOutcome.NoChange ->
                        "Already balanced — no changes needed"
                    is EditorViewModel.AutoEnhanceOutcome.Applied ->
                        "Auto Enhance: ${outcome.summary} — tap Undo Auto to revert"
                    is EditorViewModel.AutoEnhanceOutcome.Reverted ->
                        "Auto Enhance off — back to the pre-auto state"
                    is EditorViewModel.AutoEnhanceOutcome.Stale ->
                        "You edited after Auto Enhance — use Undo to go back"
                },
                style = Lumora.type.caption,
                color = if (failed) Lumora.colors.danger else Lumora.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Transient: dismiss after 5s; a fresh result restarts the timer
            // because the effect is keyed on the feedback instance.
            LaunchedEffect(outcome) {
                delay(5000)
                autoOutcome = null
            }
        }
        // Pro Auto: scene-aware professional recipe (true toggle — second
        // tap reverts to the exact pre-auto state).
        var proRunning by remember { mutableStateOf(false) }
        var proOutcome by remember { mutableStateOf<EditorViewModel.ProAutoOutcome?>(null) }
        val proApplied by vm.proAutoApplied.collectAsState()
        LumoraButton(
            onClick = {
                proRunning = true
                proOutcome = null
                vm.applyProAuto { outcome ->
                    proRunning = false
                    proOutcome = outcome
                }
            },
            enabled = !proRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (proRunning) {
                LumoraProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Analyzing…")
            } else {
                // Design-system icon (was a ✨ emoji glyph): decorative for
                // TalkBack — the "Pro Auto" label carries the meaning, and
                // emoji render inconsistently across fonts/devices.
                Icon(LumoraIcons.Tune, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (proApplied) "Undo Pro Auto" else "Pro Auto")
            }
        }
        proOutcome?.let { outcome ->
            val caption = when (outcome) {
                is EditorViewModel.ProAutoOutcome.Applied ->
                    "Applied ${outcome.sceneName} pro recipe — tap Undo Pro Auto to revert"
                is EditorViewModel.ProAutoOutcome.Reverted ->
                    "Pro Auto off — back to the pre-auto state"
                is EditorViewModel.ProAutoOutcome.Stale ->
                    "You edited after Pro Auto — use Undo to go back"
                is EditorViewModel.ProAutoOutcome.Failed -> null
            }
            if (caption != null) {
                Text(
                    caption,
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        // Scopes: histogram + luma waveform + vectorscope + RGB parade (Lightroom parity).
        // The histogram merges the whole frame; the waveform keeps horizontal
        // position, so a blown sky or crushed corner reads at a glance, per
        // image region; the vectorscope shows hue/saturation distribution for
        // judging skin tones and over-saturation; the parade shows per-channel
        // column envelopes so channel clipping reads by color.
        SegmentedControl(
            options = listOf(0 to "Histogram", 1 to "Waveform", 2 to "Vectorscope", 3 to "RGB Parade"),
            selected = scopeMode,
            onSelect = { scopeMode = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when (scopeMode) {
            0 -> HistogramView(histogram, modifier = Modifier.padding(horizontal = 16.dp))
            1 -> WaveformView(waveform, modifier = Modifier.padding(horizontal = 16.dp))
            2 -> VectorscopeView(vectorscope, modifier = Modifier.padding(horizontal = 16.dp))
            else -> RgbParadeView(rgbParade, modifier = Modifier.padding(horizontal = 16.dp))
        }

        ParamSlider(vm, "Exposure", l.exposure, -5f..5f, "Exposure") { s, v ->
            s.copy(light = s.light.copy(exposure = v))
        }
        ParamSlider(vm, "Contrast", l.contrast) { s, v ->
            s.copy(light = s.light.copy(contrast = v))
        }

        PanelSection("Tone") {
            ParamSlider(vm, "Highlights", l.highlights) { s, v ->
                s.copy(light = s.light.copy(highlights = v))
            }
            ParamSlider(vm, "Shadows", l.shadows) { s, v ->
                s.copy(light = s.light.copy(shadows = v))
            }
            ParamSlider(vm, "Whites", l.whites) { s, v ->
                s.copy(light = s.light.copy(whites = v))
            }
            ParamSlider(vm, "Blacks", l.blacks) { s, v ->
                s.copy(light = s.light.copy(blacks = v))
            }
        }

        PanelSection("White balance") {
            ParamSlider(vm, "Temperature", l.temp) { s, v ->
                s.copy(light = s.light.copy(temp = v))
            }
            ParamSlider(vm, "Tint", l.tint) { s, v ->
                s.copy(light = s.light.copy(tint = v))
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    // Labeled "Auto WB" (not "Auto"): the panel header already
                    // has an "Auto" action for full Auto Enhance — this chip
                    // only balances white, and the names must not collide.
                    LumoraChip(
                        selected = false,
                        onClick = { vm.autoWhiteBalance() },
                        label = { Text("Auto WB") },
                    )
                }
                items(ImageMath.WB_PRESETS) { p ->
                    val active = abs(l.temp - p.temp * 100f) < 0.5f &&
                        abs(l.tint - p.tint * 100f) < 0.5f
                    LumoraChip(
                        selected = active,
                        onClick = { vm.applyWbPreset(p) },
                        label = { Text(p.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LumoraTextButton(onClick = onPickWhiteBalance) {
                    androidx.compose.material3.Icon(
                        LumoraIcons.Colorize,
                        contentDescription = null,
                        tint = Lumora.colors.accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.Text(
                        "Pick from photo",
                        color = Lumora.colors.accent, style = Lumora.type.caption)
                }
            }
        }

        PanelSection("Presence") {
            ParamSlider(vm, "Clarity", l.clarity) { s, v ->
                s.copy(light = s.light.copy(clarity = v))
            }
            ParamSlider(vm, "Texture", l.texture) { s, v ->
                s.copy(light = s.light.copy(texture = v))
            }
            ParamSlider(vm, "Dehaze", l.dehaze) { s, v ->
                s.copy(light = s.light.copy(dehaze = v))
            }
        }

        // Single disclosure mechanism: the section header's chevron expands /
        // collapses the curve editor. The old "Edit curve" button fought the
        // section state — CollapsibleSection reads initiallyExpanded only
        // once, so the button's toggles never reached the section and the
        // editor appeared stuck.
        PanelSection("Tone curve", initiallyExpanded = false) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                // Lightroom parity: targeted adjustment — drag on the photo
                // to bend the selected channel's curve at the touched tone.
                LumoraTextButton(onClick = onCurveTarget) {
                    val tint = if (curveTargetActive) Lumora.colors.accent
                    else Lumora.colors.textSecondary
                    androidx.compose.material3.Icon(
                        LumoraIcons.Colorize,
                        contentDescription = null,
                        tint = tint,
                    )
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.Text(
                        if (curveTargetActive) "Stop targeting" else "Target from photo",
                        color = tint, style = Lumora.type.caption)
                }
            }
            val channels = listOf("Master" to 0, "Red" to 1, "Green" to 2, "Blue" to 3)
            SegmentedControl(
                options = channels.map { it.second to it.first },
                selected = curveChannel,
                onSelect = onCurveChannel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            val curve = when (curveChannel) {
                1 -> l.redCurve; 2 -> l.greenCurve; 3 -> l.blueCurve
                else -> l.toneCurve
            }
            val channelName = channels.first { it.second == curveChannel }.first
            // One reset path for the visual preset chips AND the TalkBack
            // "Reset curve to linear" accessibility action (TalkBack
            // gestures never reach double-tap).
            fun applyCurvePreset(preset: CurvePreset, label: String = preset.label) {
                vm.update("Tone curve: $label") { s ->
                    val nc = ToneCurve(preset.points)
                    val nl = when (curveChannel) {
                        1 -> s.light.copy(redCurve = nc)
                        2 -> s.light.copy(greenCurve = nc)
                        3 -> s.light.copy(blueCurve = nc)
                        else -> s.light.copy(toneCurve = nc)
                    }
                    s.copy(light = nl)
                }
            }
            // Lightroom parity: named point-curve presets for the active channel.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CurvePreset.entries) { preset ->
                    LumoraChip(
                        selected = curve.activePreset() == preset,
                        onClick = { applyCurvePreset(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val accent = when (curveChannel) {
                1 -> Color(0xFFE5484D); 2 -> Color(0xFF46A758); 3 -> Color(0xFF3E8EDE)
                else -> Lumora.colors.accent
            }
            ToneCurveEditor(
                curve = curve,
                accent = accent,
                histogram = histogram,
                histChannel = curveChannel,
                channelLabel = channelName,
                onReset = { applyCurvePreset(CurvePreset.LINEAR) },
                onBegin = { vm.beginGesture() },
                onChange = { c ->
                    vm.update(gesture = true) { s ->
                        val nl = when (curveChannel) {
                            1 -> s.light.copy(redCurve = c)
                            2 -> s.light.copy(greenCurve = c)
                            3 -> s.light.copy(blueCurve = c)
                            else -> s.light.copy(toneCurve = c)
                        }
                        s.copy(light = nl)
                    }
                },
                onEnd = { vm.endGesture("Tone curve") },
            )
        }
    }
}
