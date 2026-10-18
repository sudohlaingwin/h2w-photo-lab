package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.Pt
import com.lumora.photoeditor.engine.RetouchOp
import com.lumora.photoeditor.engine.RetouchRange
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraOutlineButton
import com.lumora.photoeditor.ui.components.LumoraButton
import com.lumora.photoeditor.ui.components.LumoraSwitch
import com.lumora.photoeditor.ui.components.SegmentedControl
import com.lumora.photoeditor.ui.components.LumoraProgressIndicator
import com.lumora.photoeditor.ui.components.LumoraSlider
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons

@Composable
fun RetouchPanel(
    vm: EditorViewModel,
    tool: RetouchOp?,
    onTool: (RetouchOp?) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    feather: Float,
    onFeather: (Float) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
    /** Skin brush detail retention (0..1); shown only for SMOOTH_SKIN. */
    textureKeep: Float,
    onTextureKeep: (Float) -> Unit,
    /** Current clone source pick (photo UV); null = not picked yet. */
    cloneSource: Pt?,
    onCloneSource: (Pt) -> Unit,
    onClearSource: () -> Unit,
    eraseActive: Boolean,
    onEraseActive: (Boolean) -> Unit,
    onNotice: (String) -> Unit = {},
    /** DODGE/BURN only: tonal range the brush is limited to. */
    range: RetouchRange,
    onRange: (RetouchRange) -> Unit,
) {
    val state by vm.state.collectAsState()
    val tools = listOf(
        RetouchOp.HEAL to "Heal",
        RetouchOp.CLONE to "Clone",
        RetouchOp.REMOVE to "Remove",
        RetouchOp.SMOOTH_SKIN to "Skin",
        RetouchOp.DODGE to "Dodge",
        RetouchOp.BURN to "Burn",
        RetouchOp.EYE_ENHANCE to "Eyes",
        RetouchOp.RED_EYE to "Red eye",
        RetouchOp.TEETH_WHITEN to "Teeth",
        RetouchOp.UNDER_EYE to "Under-eye",
    )
    PanelColumn {
        Text(
            "Tap the photo to apply. Every retouch is non-destructive and undoable.",
            style = Lumora.type.caption,
            color = Lumora.colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // ---- AI Pro Retouch: one-tap image-adaptive pro grade ----
        val aiRetouch = state.aiRetouch
        var aiRunning by remember { mutableStateOf(false) }
        var aiResult by remember { mutableStateOf<String?>(null) }
        LumoraButton(
            onClick = {
                aiRunning = true
                aiResult = null
                vm.applyAiProRetouch { msg ->
                    aiRunning = false
                    aiResult = msg
                }
            },
            enabled = !aiRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (aiRunning) {
                LumoraProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Analyzing photo…")
            } else {
                Icon(LumoraIcons.Style, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI Pro Retouch")
            }
        }
        aiResult?.let {
            Text(
                it,
                style = Lumora.type.caption,
                color = Lumora.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (aiRetouch.enabled) {
            var aiStrength by remember(aiRetouch.strength) {
                mutableStateOf(aiRetouch.strength)
            }
            // Design-system slider (was the last raw Material3 Slider in the
            // app): signature thumb/track, double-tap the label to reset to
            // the AI-chosen strength, TalkBack reset action, and one undo
            // entry per drag via the gesture hooks — intensity drags
            // previously recorded no history entry at all, so undo could not
            // restore a changed intensity.
            LumoraSlider(
                label = "Intensity",
                value = aiStrength,
                onValueChange = {
                    aiStrength = it
                    vm.setAiProRetouchStrength(it)
                },
                valueRange = 0f..1f,
                default = aiRetouch.strength,
                valueText = "${(aiStrength * 100).toInt()}%",
                onBegin = { vm.beginGesture() },
                onEnd = { vm.endGesture("AI Pro Retouch intensity") },
            )
            LumoraOutlineButton(
                onClick = {
                    vm.clearAiProRetouch()
                    aiResult = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text("Remove AI grade")
            }
        }
        // One-tap auto retouch
        var autoRunning by remember { mutableStateOf(false) }
        var autoResult by remember { mutableStateOf<String?>(null) }
        LumoraButton(
            onClick = {
                autoRunning = true
                autoResult = null
                vm.applyAutoRetouch { count, skinFound, underEyes, condition ->
                    autoRunning = false
                    autoResult = buildString {
                        append("Healed $count blemish${if (count == 1) "" else "es"}")
                        if (underEyes > 0) append(" + brightened $underEyes under-eye${if (underEyes == 1) "" else "s"}")
                        if (skinFound) append(" + skin softened")
                        if (condition.isNotEmpty()) append(" [$condition]")
                    }
                }
            },
            enabled = !autoRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (autoRunning) {
                LumoraProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Detecting…")
            } else {
                // Design-system icon (was a ✨ emoji glyph): decorative for
                // TalkBack — the "Auto Retouch" label carries the meaning.
                Icon(LumoraIcons.Healing, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Auto Retouch")
            }
        }
        autoResult?.let {
            Text(
                it,
                style = Lumora.type.caption,
                color = Lumora.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        // ---- Pro Beauty Looks (one-tap, sellable results) ----
        Text(
            "Pro Beauty Looks",
            style = Lumora.type.sectionTitle,
            color = Lumora.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        var beautyRunning by remember { mutableStateOf<String?>(null) }
        var beautyResult by remember { mutableStateOf<String?>(null) }
        val beautyStyles = listOf(
            "Natural" to EditorViewModel.BeautyStyle.NATURAL,
            "Beauty" to EditorViewModel.BeautyStyle.BEAUTY,
            "Fashion" to EditorViewModel.BeautyStyle.FASHION,
            "Glamour" to EditorViewModel.BeautyStyle.GLAMOUR,
        )
        // 2x2 grid of beauty preset buttons
        for (row in beautyStyles.chunked(2)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((label, style) in row) {
                    LumoraButton(
                        onClick = {
                            beautyRunning = label
                            beautyResult = null
                            vm.applyBeautyLook(style) { summary ->
                                beautyRunning = null
                                beautyResult = summary
                            }
                        },
                        enabled = beautyRunning == null && !autoRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (beautyRunning == label) {
                            LumoraProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            // Plain text (was a 💄 emoji glyph): emoji read
                            // aloud by TalkBack ("lipstick Natural") and
                            // render inconsistently across devices.
                            Text(label)
                        }
                    }
                }
                // Fill row if odd (not needed for 4, but safe)
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        beautyResult?.let {
            Text(
                it,
                style = Lumora.type.caption,
                color = Lumora.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // Wavelet skin retouch (GIMP-inspired à trous decomposition)
        // Professional frequency-separation: smooths blemishes while keeping pores.
        // NOTE: the wavelet section keeps its own texture-keep state
        // (waveletTextureKeep) — deliberately separate from the Skin brush's
        // hoisted `textureKeep` parameter. A local named `textureKeep` here
        // used to shadow the parameter, making the Skin brush's Texture keep
        // slider read wavelet state while writing to the hoisted state, so
        // the slider snapped back to 100% and never moved.
        var waveletRunning by remember { mutableStateOf(false) }
        var waveletTextureKeep by remember { mutableStateOf(1f) }
        var blemishSmooth by remember { mutableStateOf(0.6f) }
        var toneEven by remember { mutableStateOf(0.3f) }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                // Plain text (was a 🌊 emoji glyph).
                "Wavelet Skin (pro)",
                style = Lumora.type.controlLabel,
                color = Lumora.colors.textPrimary,
            )
            Text(
                "GIMP-style frequency separation: smooths blemishes, keeps skin texture.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
            )
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Texture keep",
                value = waveletTextureKeep,
                onValueChange = { waveletTextureKeep = it },
                valueRange = 0f..1f,
                default = 1f,
                valueText = "${(waveletTextureKeep * 100).toInt()}%",
            )
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Blemish smooth",
                value = blemishSmooth,
                onValueChange = { blemishSmooth = it },
                valueRange = 0f..1f,
                default = 0.6f,
                valueText = "${(blemishSmooth * 100).toInt()}%",
            )
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Tone even",
                value = toneEven,
                onValueChange = { toneEven = it },
                valueRange = 0f..1f,
                default = 0.3f,
                valueText = "${(toneEven * 100).toInt()}%",
            )
            LumoraButton(
                onClick = {
                    waveletRunning = true
                    vm.applyWaveletSkinRetouch(
                        textureKeep = waveletTextureKeep,
                        blemishSmooth = blemishSmooth,
                        toneEven = toneEven,
                    ) { waveletRunning = false }
                },
                enabled = !waveletRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (waveletRunning) {
                    LumoraProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Retouching…")
                } else {
                    Text("Apply Wavelet Skin Retouch")
                }
            }
        }
        // spot eraser: tap a retouch spot on the photo to delete just it
        // (Lightroom parity — previously only "Undo last" / "Clear all").
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LumoraChip(
                selected = eraseActive,
                onClick = { onEraseActive(!eraseActive) },
                label = { Text("Erase spot") },
                modifier = Modifier.weight(1f),
            )
        }
        if (eraseActive) {
            Text(
                "Tap a retouch spot on the photo to remove it.",
                style = Lumora.type.caption,
                color = Lumora.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // Pin markers: show every retouch spot on the photo (Lightroom pins).
        // Without this the spots are invisible — and "Erase spot" is blind.
        val showSpots by vm.showRetouchSpots.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show spots", style = Lumora.type.body,
                    color = Lumora.colors.textPrimary)
                Text("Mark every retouch spot on the photo",
                    style = Lumora.type.caption, color = Lumora.colors.textTertiary)
            }
            LumoraSwitch(
                checked = showSpots,
                onCheckedChange = { vm.toggleRetouchSpots() },
                contentDescription = "Show retouch spot markers",
            )
        }
        // tool grid (rows of 3)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (op, label) ->
                        LumoraChip(
                            selected = tool == op,
                            onClick = { onTool(if (tool == op) null else op) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (tool != null) {
            Spacer(Modifier.height(4.dp))
            // Live brush-footprint preview: diameter = Size, softness =
            // Feather, so the user sees the brush before touching the photo.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                com.lumora.photoeditor.ui.components.BrushPreview(
                    diameter = size, feather = feather, maxDiameter = 0.3f)
                Column {
                    Text("Brush footprint", style = Lumora.type.controlLabel,
                        color = Lumora.colors.textSecondary)
                    Text(
                        "${(size * 100).toInt()}% of photo width • " +
                            "${(feather * 100).toInt()}% feather",
                        style = Lumora.type.caption,
                        color = Lumora.colors.textTertiary,
                    )
                }
            }
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Brush size",
                value = size,
                valueRange = 0.01f..0.3f,
                onValueChange = onSize,
                valueText = "${(size * 100).toInt()}%",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Feather",
                value = feather,
                valueRange = 0.05f..1f,
                onValueChange = onFeather,
                valueText = "${(feather * 100).toInt()}%",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            com.lumora.photoeditor.ui.components.LumoraSlider(
                label = "Strength",
                value = strength,
                valueRange = 0f..1f,
                onValueChange = onStrength,
                valueText = "${(strength * 100).toInt()}%",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            if (tool == RetouchOp.SMOOTH_SKIN) {
                // Detail retention for the Skin brush: 100% keeps every pore
                // (tone is still evened out), 0% gives maximum smoothing.
                // Mirrors the Wavelet Skin "Texture keep" slider so both skin
                // tools share the same mental model.
                com.lumora.photoeditor.ui.components.LumoraSlider(
                    label = "Texture keep",
                    value = textureKeep,
                    valueRange = 0f..1f,
                    default = 0.45f,
                    onValueChange = onTextureKeep,
                    valueText = "${(textureKeep * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
                Text(
                    "Higher texture keep = more natural pores, less smoothing.",
                    style = Lumora.type.caption,
                    color = Lumora.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (tool == RetouchOp.DODGE || tool == RetouchOp.BURN) {
                // Pro tonal-range control: limit dodge/burn to shadows,
                // midtones, or highlights (darkroom-style). E.g. dodge
                // "Shadows" lifts deep shadows without blowing the sky.
                Text(
                    "Range",
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val ranges = listOf(
                        RetouchRange.ALL to "All",
                        RetouchRange.SHADOWS to "Shadows",
                        RetouchRange.MIDTONES to "Midtones",
                        RetouchRange.HIGHLIGHTS to "Highlights",
                    )
                    for ((r, label) in ranges) {
                        // Design-system chip (was raw M3 FilterChip): 48dp
                        // touch target, warm-amber selected state, TalkBack
                        // role=Button + selected semantics.
                        LumoraChip(
                            selected = range == r,
                            onClick = { onRange(r) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "Limit the effect to a tonal range — e.g. burn Highlights to deepen skies without muddying shadows.",
                    style = Lumora.type.caption,
                    color = Lumora.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (tool == RetouchOp.CLONE) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Design-system chip (was raw M3 AssistChip): 48dp touch
                    // target, TalkBack role=Button, accent highlight while a
                    // source is set.
                    LumoraChip(
                        selected = cloneSource != null,
                        onClick = onClearSource,
                        label = {
                            Text(if (cloneSource != null) "Source set — tap to re-pick"
                            else "Tap photo to pick source")
                        },
                    )
                }
                val src = cloneSource
                if (src != null) {
                    Text(
                        "Amber ring marks the clone source on the photo. Nudge it precisely — no need to re-tap.",
                        style = Lumora.type.caption,
                        color = Lumora.colors.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    // Precision source placement: tap-picking is coarse and
                    // unusable without sight, so TalkBack-enabled sliders let
                    // anyone fine-tune the source to the exact spot. Moving
                    // the pick only affects future stamps, never past ones.
                    com.lumora.photoeditor.ui.components.LumoraSlider(
                        label = "Source X",
                        value = src.x,
                        valueRange = 0f..1f,
                        default = 0.5f,
                        onValueChange = { onCloneSource(src.copy(x = it)) },
                        valueText = "${(src.x * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    com.lumora.photoeditor.ui.components.LumoraSlider(
                        label = "Source Y",
                        value = src.y,
                        valueRange = 0f..1f,
                        default = 0.5f,
                        onValueChange = { onCloneSource(src.copy(y = it)) },
                        valueText = "${(src.y * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (tool == RetouchOp.REMOVE) {
                // Remove object UI: brush over the object, then apply
                var removing by remember { mutableStateOf(false) }
                val isRemoving by vm.isRemoving.collectAsState()
                val canUndoRemoval = vm.canUndoRemoval()
                // Re-evaluate mask presence on every mask revision so the
                // Remove button's enabled state stays honest.
                val removalRev by vm.removalMaskRevision.collectAsState()
                val hasRemovalMask = remember(removalRev) { vm.hasRemovalMask() }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Brush over the person or object to remove — the red area is exactly what will be removed. Then tap Remove.",
                        style = Lumora.type.caption,
                        color = Lumora.colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LumoraButton(
                            onClick = {
                                // Empty mask used to fail silently (applyRemoval
                                // calls back with false and the UI swallowed it).
                                // Tell the user what's missing instead.
                                if (!vm.hasRemovalMask()) {
                                    onNotice("Paint over the object first, then tap Remove")
                                    return@LumoraButton
                                }
                                removing = true
                                vm.applyRemoval { success ->
                                    removing = false
                                    onNotice(
                                        if (success) "Object removed"
                                        else "Couldn't remove — try painting a larger area"
                                    )
                                }
                            },
                            enabled = !removing && !isRemoving && hasRemovalMask,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (removing || isRemoving) {
                                LumoraProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Removing…")
                            } else {
                                // Plain text (was a 🪄 emoji glyph).
                                Text("Remove")
                            }
                        }
                        LumoraOutlineButton(
                            onClick = { vm.clearRemovalMask() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Clear mask") }
                    }
                    if (canUndoRemoval) {
                        LumoraOutlineButton(
                            onClick = { vm.undoRemoval() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Undo remove") }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        val count = state.retouch.spots.size
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LumoraOutlineButton(
                onClick = vm::undoRetouchSpot,
                enabled = count > 0,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Undo last", color = Lumora.colors.textPrimary) }
            LumoraOutlineButton(
                onClick = vm::clearRetouch,
                enabled = count > 0,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Clear all ($count)", color = Lumora.colors.textPrimary) }
        }
    }
}
