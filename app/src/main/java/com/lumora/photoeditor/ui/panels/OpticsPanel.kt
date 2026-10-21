package com.lumora.photoeditor.ui.panels

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import com.lumora.photoeditor.engine.LightLeakStyle
import com.lumora.photoeditor.engine.OpticsParams
import com.lumora.photoeditor.ui.components.LumoraChip
import com.lumora.photoeditor.ui.components.LumoraTextButton
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun OpticsPanel(
    vm: EditorViewModel,
    focusPickActive: Boolean = false,
    onMoveFocus: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val o = state.optics
    PanelColumn {
        PanelHeader(
            title = "Optics",
            showReset = state.optics != OpticsParams(),
            resetLabel = "Reset optics adjustments",
            onReset = { vm.update("Reset optics") { s -> s.copy(optics = OpticsParams()) } },
        )
        PanelSection("Lens corrections") {
            // Phase A#2 slice 2: per-lens correction memory. The remembered
            // values are the user's own corrections for this lens — the app
            // never invents correction numbers.
            val lensLabel by vm.lensLabel.collectAsState()
            val remembered = vm.lensProfile.collectAsState().value
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        lensLabel == null -> "Lens not detected in this photo"
                        remembered != null ->
                            "Lens: $lensLabel · profile D${remembered.distortion.toInt()} / F${remembered.defringe.toInt()}"
                        else -> "Lens: $lensLabel"
                    },
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (remembered != null) {
                    LumoraTextButton(onClick = { vm.forgetLensProfile() }) {
                        Text("Forget")
                    }
                } else if (lensLabel != null) {
                    LumoraTextButton(onClick = { vm.rememberLensProfile() }) {
                        Text("Remember")
                    }
                }
            }
            ParamSlider(vm, "Distortion", o.distortion, historyLabel = "Lens distortion") { s, v ->
                s.copy(optics = s.optics.copy(distortion = v))
            }
            ParamSlider(vm, "Defringe", o.defringe, 0f..100f, "Defringe") { s, v ->
                s.copy(optics = s.optics.copy(defringe = v))
            }
        }
        PanelSection("Vignette") {
            val autoApplied by vm.autoLensApplied.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (autoApplied)
                        "Auto lens fix applied — adjust to fine-tune"
                    else "Fix dark corners from the lens",
                    style = Lumora.type.caption,
                    color = Lumora.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                LumoraTextButton(onClick = { vm.autoLensCorrect() }) {
                    Text("Auto")
                }
            }
            ParamSlider(vm, "Amount", o.vignetteAmount, historyLabel = "Vignette") { s, v ->
                s.copy(optics = s.optics.copy(vignetteAmount = v))
            }
            ParamSlider(vm, "Midpoint", o.vignetteMidpoint, 0f..100f, "Vignette midpoint", default = 50f,
                valueText = "${o.vignetteMidpoint.roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(vignetteMidpoint = v))
            }
            ParamSlider(vm, "Feather", o.vignetteFeather, 0f..100f, "Vignette feather", default = 50f,
                valueText = "${o.vignetteFeather.roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(vignetteFeather = v))
            }
            ParamSlider(vm, "Roundness", o.vignetteRoundness, historyLabel = "Vignette roundness") { s, v ->
                s.copy(optics = s.optics.copy(vignetteRoundness = v))
            }
        }
        PanelSection("Lens blur") {
            ParamSlider(vm, "Amount", o.lensBlurAmount, 0f..100f, "Lens blur") { s, v ->
                s.copy(optics = s.optics.copy(lensBlurAmount = v))
            }
            ParamSlider(vm, "Focus size", o.lensSize, 0f..100f, "Lens blur size", default = 35f,
                valueText = "${o.lensSize.roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(lensSize = v))
            }
            ParamSlider(vm, "Feather", o.lensFeather, 0f..100f, "Lens blur feather", default = 50f,
                valueText = "${o.lensFeather.roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(lensFeather = v))
            }
            ParamSlider(vm, "Focus X", o.lensFocusX * 100f, 0f..100f, "Lens focus X", default = 50f,
                valueText = "${(o.lensFocusX * 100f).roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(lensFocusX = v / 100f))
            }
            ParamSlider(vm, "Focus Y", o.lensFocusY * 100f, 0f..100f, "Lens focus Y", default = 50f,
                valueText = "${(o.lensFocusY * 100f).roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(lensFocusY = v / 100f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0 to "Radial", 1 to "Linear").forEach { (value, label) ->
                    LumoraChip(
                        selected = o.lensShape == value,
                        onClick = {
                            vm.update("Lens blur shape") { s ->
                                s.copy(optics = s.optics.copy(lensShape = value))
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Tip: tap the button, then drag on the photo to move the focus point.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
            )
            LumoraChip(
                selected = focusPickActive,
                onClick = onMoveFocus,
                // Genuine mode toggle: announce "checked/not checked".
                role = Role.Checkbox,
                label = { Text(if (focusPickActive) "Done moving focus" else "Move focus on photo") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PanelSection("Film grain") {
            ParamSlider(vm, "Amount", o.grainAmount, 0f..100f, "Grain") { s, v ->
                s.copy(optics = s.optics.copy(grainAmount = v))
            }
            ParamSlider(vm, "Size", o.grainSize, 0f..100f, "Grain size", default = 25f,
                valueText = "${o.grainSize.roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(grainSize = v))
            }
            ParamSlider(vm, "Roughness", o.grainRoughness, 0f..100f, "Grain roughness", default = 50f,
                valueText = "${o.grainRoughness.roundToInt()}%") { s, v ->
                s.copy(optics = s.optics.copy(grainRoughness = v))
            }
        }
        PanelSection("Creative effects") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LightLeakStyle.values().forEach { style ->
                    LumoraChip(
                        selected = o.fx.leak == style,
                        onClick = {
                            vm.update("Light leak style") { s ->
                                val cur = s.optics.fx
                                // Picking a style with the amount at 0 would
                                // show nothing — start at 50 so it is visible.
                                val amount = if (style != LightLeakStyle.NONE && cur.leakAmount == 0f) {
                                    50f
                                } else cur.leakAmount
                                s.copy(optics = s.optics.copy(fx = cur.copy(leak = style, leakAmount = amount)))
                            }
                        },
                        label = { Text(style.label) },
                    )
                }
            }
            ParamSlider(vm, "Light leak", o.fx.leakAmount, 0f..100f, "Light leak") { s, v ->
                s.copy(optics = s.optics.copy(fx = s.optics.fx.copy(leakAmount = v)))
            }
            ParamSlider(vm, "Film dust", o.fx.dust, 0f..100f, "Film dust") { s, v ->
                s.copy(optics = s.optics.copy(fx = s.optics.fx.copy(dust = v)))
            }
            LumoraChip(
                selected = false,
                onClick = {
                    vm.update("Shuffle film dust") { s ->
                        s.copy(optics = s.optics.copy(fx = s.optics.fx.copy(dustSeed = Random.nextLong())))
                    }
                },
                label = { Text("Shuffle dust pattern") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tip: light leaks glow in from the corners; film dust adds specks and scratches.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
            )
        }
    }
}
